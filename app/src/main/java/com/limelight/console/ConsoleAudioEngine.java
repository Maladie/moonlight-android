package com.limelight.console;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.media.audiofx.Equalizer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.SoundEffectConstants;

import com.limelight.R;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns menu ambience and short, rate-limited console cues. */
final class ConsoleAudioEngine {
    private static final String TAG = "MoonWakerAudio";
    private static final int SAMPLE_RATE = 22_050;
    private static final float DEFAULT_HOST_SELECTION_VOLUME = 0.30f;
    private static final float DEFAULT_MENU_VOLUME = 0.16f;
    private static final float DEFAULT_EFFECTS_VOLUME = 0.40f;
    private static final long NAVIGATION_THROTTLE_MS = 90L;
    private static final int FADE_STEPS = 18;
    private static final long FADE_STEP_MS = 35L;
    /** Leaving a console (pause, release) ramps the music down this fast instead of cutting it. */
    private static final int LEAVE_FADE_STEPS = 8;
    /** A NAVIGATE tick answers a directional key pressed at most this long ago. */
    static final long NAVIGATION_INPUT_WINDOW_MS = 320L;
    /** Players outlive release() until the leave fade and the longest cue have played out. */
    private static final long RELEASE_DELAY_MS = 800L;
    /** Ambience gain at stepper 1 and 10; the asset itself peaks at 0 dBFS (RMS about -15). */
    static final float AMBIENT_MIN_DB = -42f;
    static final float AMBIENT_MAX_DB = -10f;
    /**
     * Where the menu ambience stopped. One loop plays across the consoles of this process: the
     * next console's player continues it instead of starting the track over.
     */
    private static int ambientPositionMs;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService preparationExecutor = Executors.newSingleThreadExecutor();
    private final AudioManager systemAudio;
    private final Map<ConsoleAudioSynthesis.Cue, Integer> soundIds =
            new EnumMap<>(ConsoleAudioSynthesis.Cue.class);
    private SoundPool soundPool;
    private MediaPlayer ambientPlayer;
    private Equalizer ambientEqualizer;
    private boolean preparationStarted;
    private boolean released;
    private boolean resumed;
    private boolean menuVisible = true;
    private boolean hostSelectionVisible = true;
    private boolean effectsEnabled = true;
    private boolean ambientEnabled = true;
    private float hostSelectionVolume = DEFAULT_HOST_SELECTION_VOLUME;
    private float menuVolume = DEFAULT_MENU_VOLUME;
    private float effectsVolume = DEFAULT_EFFECTS_VOLUME;
    private int fadeGeneration;
    private float ambientVolume;
    private long lastNavigationAt;
    private long resumedAt;
    private long lastDirectionalInputAt = Long.MIN_VALUE / 2;

    ConsoleAudioEngine(Context context) {
        this.context = context.getApplicationContext();
        systemAudio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    synchronized void setEffectsEnabled(boolean enabled) {
        effectsEnabled = enabled;
    }

    synchronized void setAmbientEnabled(boolean enabled) {
        ambientEnabled = enabled;
        updateAmbient(false);
    }

    synchronized void setHostSelectionVolume(float volume) {
        hostSelectionVolume = clamp(volume);
        updateAmbient(false);
    }

    synchronized void setMenuVolume(float volume) {
        menuVolume = clamp(volume);
        updateAmbient(false);
    }

    synchronized void setEffectsVolume(float volume) {
        effectsVolume = clamp(volume);
    }

    synchronized void setMenuVisible(boolean visible) {
        menuVisible = visible;
        updateAmbient(false);
    }

    synchronized void setHostSelectionVisible(boolean visible) {
        hostSelectionVisible = visible;
        updateAmbientEqualizer();
        updateAmbient(false);
    }

    synchronized void resume() {
        if (released) return;
        resumed = true;
        resumedAt = SystemClock.uptimeMillis();
        prepareIfNeeded();
        updateAmbient(false);
    }

    synchronized void pause() {
        resumed = false;
        updateAmbient(true);
    }

    /** A directional key reached this console; {@code downTime} is its uptime press time. */
    synchronized void noteDirectionalInput(long downTime) {
        if (directionalInputCounts(resumed, resumedAt, downTime)) {
            lastDirectionalInputAt = SystemClock.uptimeMillis();
        }
    }

    /**
     * Only a key pressed on this resumed console counts. A key held or queued from the stream,
     * or another activity, across the return must not tick when focus is restored.
     */
    static boolean directionalInputCounts(boolean resumed, long resumedAt, long downTime) {
        return resumed && downTime >= resumedAt;
    }

    /** NAVIGATE follows real directional input, throttled; programmatic focus stays silent. */
    static boolean navigationCueDue(long now, long lastDirectionalInputAt, long lastNavigationAt) {
        return now - lastDirectionalInputAt < NAVIGATION_INPUT_WINDOW_MS
                && now - lastNavigationAt >= NAVIGATION_THROTTLE_MS;
    }

    synchronized void play(ConsoleAudioSynthesis.Cue cue) {
        if (released || !effectsEnabled || soundPool == null) return;
        if (cue == ConsoleAudioSynthesis.Cue.NAVIGATE) {
            long now = SystemClock.uptimeMillis();
            if (!navigationCueDue(now, lastDirectionalInputAt, lastNavigationAt)) return;
            lastNavigationAt = now;
        }
        Integer soundId = soundIds.get(cue);
        if (soundId == null) return;
        float volume = effectsVolume * (cue == ConsoleAudioSynthesis.Cue.NAVIGATE
                ? 0.95f : 1f);
        soundPool.play(soundId, volume, volume, 1, 0, 1f);
    }

    synchronized void playSystemConfirm() {
        if (!released && effectsEnabled && systemAudio != null) {
            systemAudio.playSoundEffect(SoundEffectConstants.CLICK, effectsVolume);
        }
    }

    synchronized void playSystemBack() {
        if (!released && effectsEnabled && systemAudio != null) {
            systemAudio.playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN, effectsVolume);
        }
    }

    synchronized void release() {
        if (released) return;
        released = true;
        preparationExecutor.shutdownNow();
        // Let the music ramp down and a playing cue finish; releasing them now cuts mid-note.
        if (ambientPlayer != null) fadeAmbient(0f, LEAVE_FADE_STEPS);
        mainHandler.postDelayed(this::freePlayers, RELEASE_DELAY_MS);
    }

    private synchronized void freePlayers() {
        fadeGeneration++;
        if (ambientEqualizer != null) {
            ambientEqualizer.release();
            ambientEqualizer = null;
        }
        if (ambientPlayer != null) {
            ambientPlayer.release();
            ambientPlayer = null;
        }
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        soundIds.clear();
    }

    private synchronized void prepareIfNeeded() {
        if (preparationStarted || released) return;
        preparationStarted = true;
        preparationExecutor.execute(() -> {
            try {
                File directory = new File(context.getCacheDir(), "console-audio-v1");
                Map<ConsoleAudioSynthesis.Cue, File> cueFiles =
                        new EnumMap<>(ConsoleAudioSynthesis.Cue.class);
                for (ConsoleAudioSynthesis.Cue cue : ConsoleAudioSynthesis.Cue.values()) {
                    File file = new File(directory, cue.name().toLowerCase() + ".wav");
                    ensureWave(file, ConsoleAudioSynthesis.renderCue(cue, SAMPLE_RATE), 1);
                    cueFiles.put(cue, file);
                }
                preparePlayers(cueFiles);
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "Unable to prepare console audio", error);
            }
        });
    }

    private void preparePlayers(Map<ConsoleAudioSynthesis.Cue, File> cueFiles)
            throws IOException {
        AudioAttributes effectsAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        SoundPool preparedPool = new SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(effectsAttributes)
                .build();
        Map<ConsoleAudioSynthesis.Cue, Integer> preparedIds =
                new EnumMap<>(ConsoleAudioSynthesis.Cue.class);
        for (Map.Entry<ConsoleAudioSynthesis.Cue, File> entry : cueFiles.entrySet()) {
            preparedIds.put(entry.getKey(), preparedPool.load(entry.getValue().getPath(), 1));
        }

        MediaPlayer preparedAmbient = new MediaPlayer();
        preparedAmbient.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        try (AssetFileDescriptor descriptor = context.getResources()
                .openRawResourceFd(R.raw.moonwaker_ambient_background)) {
            if (descriptor == null) throw new IOException("Ambient resource is compressed");
            preparedAmbient.setDataSource(descriptor.getFileDescriptor(),
                    descriptor.getStartOffset(), descriptor.getLength());
        }
        preparedAmbient.setLooping(true);
        preparedAmbient.setVolume(0f, 0f);
        preparedAmbient.prepare();
        mainHandler.post(() -> installPreparedPlayers(
                preparedPool, preparedIds, preparedAmbient));
    }

    private synchronized void installPreparedPlayers(SoundPool preparedPool,
            Map<ConsoleAudioSynthesis.Cue, Integer> preparedIds,
            MediaPlayer preparedAmbient) {
        if (released) {
            preparedPool.release();
            preparedAmbient.release();
            return;
        }
        soundPool = preparedPool;
        soundIds.putAll(preparedIds);
        ambientPlayer = preparedAmbient;
        try {
            ambientEqualizer = new Equalizer(0, preparedAmbient.getAudioSessionId());
            ambientEqualizer.setEnabled(true);
            updateAmbientEqualizer();
        } catch (RuntimeException error) {
            Log.w(TAG, "Menu ambience equalizer is unavailable", error);
        }
        Log.i(TAG, "Console sounds and Pixabay ambience ready");
        updateAmbient(false);
    }

    private void ensureWave(File file, short[] samples, int channels) throws IOException {
        if (file.isFile() && file.length() > 44L) return;
        ConsoleAudioSynthesis.writeWave(file, samples, SAMPLE_RATE, channels);
    }

    /** {@code leaving}: this console is going away, so the music ramps down quickly. */
    private synchronized void updateAmbient(boolean leaving) {
        if (released || ambientPlayer == null) return;
        float target = resumed && menuVisible && ambientEnabled
                ? ambientGain(hostSelectionVisible ? hostSelectionVolume : menuVolume) : 0f;
        if (target > 0f && !ambientPlayer.isPlaying()) {
            ambientPlayer.seekTo(ambientPositionMs);
            ambientPlayer.start();
        }
        fadeAmbient(target, leaving ? LEAVE_FADE_STEPS : FADE_STEPS);
    }

    /** Every gain change, including the stop, is a ramp; the player pauses only at silence. */
    private void fadeAmbient(float target, int steps) {
        int generation = ++fadeGeneration;
        float start = ambientVolume;
        for (int step = 1; step <= steps; step++) {
            int currentStep = step;
            mainHandler.postDelayed(() -> applyFadeStep(
                    generation, start, target, currentStep, steps), FADE_STEP_MS * step);
        }
    }

    private synchronized void applyFadeStep(int generation, float start, float target, int step,
                                            int steps) {
        if (generation != fadeGeneration || ambientPlayer == null) return;
        float fraction = step / (float) steps;
        setAmbientVolume(start + (target - start) * fraction);
        if (step == steps && target == 0f && ambientPlayer.isPlaying()) {
            pauseAmbient();
        }
    }

    private void pauseAmbient() {
        ambientPositionMs = ambientPlayer.getCurrentPosition();
        ambientPlayer.pause();
    }

    private void setAmbientVolume(float volume) {
        ambientVolume = clamp(volume);
        ambientPlayer.setVolume(ambientVolume, ambientVolume);
    }

    /** Applies a restrained high-frequency roll-off after entering a host. */
    private void updateAmbientEqualizer() {
        if (ambientEqualizer == null) return;
        try {
            short bands = ambientEqualizer.getNumberOfBands();
            short minimum = ambientEqualizer.getBandLevelRange()[0];
            for (short band = 0; band < bands; band++) {
                int centerHz = ambientEqualizer.getCenterFreq(band) / 1_000;
                int attenuation = hostSelectionVisible ? 0
                        : centerHz >= 5_000 ? -900 : centerHz >= 1_200 ? -500 : 0;
                ambientEqualizer.setBandLevel(band,
                        (short) Math.max(minimum, attenuation));
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to update menu ambience equalizer", error);
        }
    }

    /**
     * Maps a music slider level (0..1, ten steps) to player gain with equal dB per step,
     * so step 1 sits barely audible under the UI and step 10 keeps 10 dB of headroom.
     */
    static float ambientGain(float level) {
        if (level <= 0f) return 0f;
        float steps = ConsolePanel.STEPS;
        float step = Math.max(1f, Math.min(steps, level * steps));
        float db = AMBIENT_MIN_DB + (step - 1f) * (AMBIENT_MAX_DB - AMBIENT_MIN_DB) / (steps - 1f);
        return (float) Math.pow(10.0, db / 20.0);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
