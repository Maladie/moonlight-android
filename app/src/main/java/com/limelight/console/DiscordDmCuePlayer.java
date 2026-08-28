package com.limelight.console;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/** Short local sonification cue. It never requests audio focus or ducks the stream. */
final class DiscordDmCuePlayer implements DiscordDmNotificationCoordinator.CuePlayer {
    static final int DURATION_MS = 220;
    private static final int SAMPLE_RATE = 22_050;
    private static final float MAX_VOLUME = 0.18f;
    private static final String CONSOLE_PREFERENCES = "console_dashboard";

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioTrack currentTrack;

    DiscordDmCuePlayer(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public synchronized boolean play() {
        SharedPreferences preferences = context.getSharedPreferences(
                CONSOLE_PREFERENCES, Context.MODE_PRIVATE);
        if (!preferences.getBoolean("ui_sounds", true)) return false;
        float effects = Math.max(0, Math.min(100,
                preferences.getInt("effects_volume", 40))) / 100f;
        if (effects == 0f) return false;
        short[] samples = ConsoleAudioSynthesis.renderDiscordDmCue(SAMPLE_RATE);
        AudioTrack track = null;
        try {
            track = new AudioTrack(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(), new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(), samples.length * 2, AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (track.getState() != AudioTrack.STATE_INITIALIZED
                    || track.write(samples, 0, samples.length) != samples.length) {
                discard(track);
                return false;
            }
            track.setVolume(MAX_VOLUME * effects);
            track.play();
        } catch (RuntimeException error) {
            discard(track);
            return false;
        }
        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            discard(track);
            return false;
        }
        stop();
        currentTrack = track;
        AudioTrack playingTrack = track;
        handler.postDelayed(() -> release(playingTrack), DURATION_MS + 80L);
        return true;
    }

    @Override public synchronized void stop() {
        release(currentTrack);
    }

    private synchronized void release(AudioTrack track) {
        if (track == null || currentTrack != track) return;
        currentTrack = null;
        discard(track);
    }

    private static void discard(AudioTrack track) {
        if (track == null) return;
        try { track.stop(); }
        catch (RuntimeException ignored) { }
        track.release();
    }
}
