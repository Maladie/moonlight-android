package com.limelight.binding.audio;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import com.limelight.gateway.GatewayConnection;
import com.limelight.gateway.GatewayTransport;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-local Discord playback, independent of the Moonlight stream. */
public final class DiscordAudioDownlink {
    public static final String GAIN_PREF = "seekbar_discord_audio_gain";
    public static final int DEFAULT_GAIN = 125;
    static final int FRAME_BYTES = 3840;
    static final int MAX_QUEUED_FRAMES = 6;
    private static final int STABLE_FRAMES = 50;

    private final Object lock = new Object();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final GatewayTransport transport = new GatewayTransport();
    private GatewayConnection connection;
    private Session session;
    private Runnable retry;
    private boolean started;
    private boolean playbackEnabled;
    private int gain = DEFAULT_GAIN;
    private int generation;
    private int retrySeconds = 1;

    public void start(GatewayConnection next, int nextGain, boolean nextPlaybackEnabled) {
        if (next == null) {
            stop();
            return;
        }
        Session old = null;
        synchronized (lock) {
            boolean changed = !sameConnection(connection, next);
            connection = next;
            gain = clampGain(nextGain);
            playbackEnabled = nextPlaybackEnabled;
            started = true;
            if (session != null) session.setPresentation(playbackEnabled, gain);
            if (!changed && (session != null || retry != null)) return;
            if (changed) {
                generation++;
                retrySeconds = 1;
                cancelRetryLocked();
                old = session;
                session = null;
            }
            scheduleLocked(0);
        }
        if (old != null) old.close();
    }

    public void setPlaybackEnabled(boolean enabled) {
        synchronized (lock) {
            playbackEnabled = enabled;
            if (session != null) session.setPresentation(enabled, gain);
        }
    }

    public void stop() {
        Session old;
        synchronized (lock) {
            started = false;
            connection = null;
            generation++;
            cancelRetryLocked();
            old = session;
            session = null;
        }
        if (old != null) old.close();
    }

    private void scheduleLocked(int delaySeconds) {
        final int expectedGeneration = generation;
        retry = () -> {
            synchronized (lock) {
                retry = null;
                if (!started || generation != expectedGeneration || session != null) return;
                session = new Session(expectedGeneration, connection, playbackEnabled, gain);
                session.start();
            }
        };
        handler.postDelayed(retry, delaySeconds * 1000L);
    }

    private void ended(Session ended, boolean stable) {
        handler.post(() -> {
            synchronized (lock) {
                if (session != ended) return;
                session = null;
                ended.close();
                if (!started || generation != ended.generation) return;
                if (stable) retrySeconds = 1;
                int delay = retrySeconds;
                retrySeconds = nextRetrySeconds(retrySeconds);
                scheduleLocked(delay);
            }
        });
    }

    private void cancelRetryLocked() {
        if (retry != null) handler.removeCallbacks(retry);
        retry = null;
    }

    static int nextRetrySeconds(int current) {
        return Math.min(15, Math.max(1, current) * 2);
    }

    static int clampGain(int value) {
        return Math.max(50, Math.min(200, value));
    }

    static void applyGain(byte[] frame, int percent) {
        int safe = clampGain(percent);
        for (int i = 0; i + 1 < frame.length; i += 2) {
            int sample = (short) ((frame[i] & 0xff) | (frame[i + 1] << 8));
            int scaled = sample * safe / 100;
            scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
            frame[i] = (byte) scaled;
            frame[i + 1] = (byte) (scaled >> 8);
        }
    }

    static void enqueue(ArrayDeque<byte[]> queue, byte[] frame) {
        if (queue.size() == MAX_QUEUED_FRAMES) queue.removeFirst();
        queue.addLast(frame);
    }

    private static boolean sameConnection(GatewayConnection left, GatewayConnection right) {
        return left != null && right != null
                && left.endpoint().equals(right.endpoint())
                && left.token().equals(right.token())
                && left.certificateSha256().equals(right.certificateSha256())
                && left.profileId().equals(right.profileId());
    }

    private final class Session {
        final int generation;
        final GatewayConnection connection;
        final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        final AtomicBoolean trackReleased = new AtomicBoolean();
        volatile boolean closed;
        boolean presentation;
        int gain;
        int frames;
        GatewayTransport.DiscordAudioStream stream;
        AudioTrack track;
        Thread networkThread;
        Thread playbackThread;

        Session(int generation, GatewayConnection connection, boolean presentation, int gain) {
            this.generation = generation;
            this.connection = connection;
            this.presentation = presentation;
            this.gain = gain;
        }

        void start() {
            networkThread = new Thread(this::networkLoop, "Discord audio network");
            networkThread.start();
        }

        void networkLoop() {
            GatewayTransport.DiscordAudioStream opened = null;
            try {
                opened = transport.openDiscordAudioStream(connection);
                stream = opened;
                if (closed) return;
                int minimum = AudioTrack.getMinBufferSize(48000,
                        AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) throw new IOException("Discord audio output is unavailable.");
                if (closed) return;
                track = new AudioTrack(AudioManager.STREAM_MUSIC, 48000,
                        AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(minimum, FRAME_BYTES * MAX_QUEUED_FRAMES), AudioTrack.MODE_STREAM);
                if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                    throw new IOException("Discord audio output is unavailable.");
                }
                if (closed) return;
                playbackThread = new Thread(this::playbackLoop, "Discord audio playback");
                playbackThread.start();
                while (!closed) {
                    byte[] frame = new byte[FRAME_BYTES];
                    int offset = 0;
                    while (offset < frame.length) {
                        int read = stream.read(frame, offset, frame.length - offset);
                        if (read < 0) throw new IOException("Discord audio stream ended.");
                        if (read == 0) continue;
                        offset += read;
                    }
                    synchronized (queue) {
                        if (presentation) {
                            enqueue(queue, frame);
                            queue.notifyAll();
                        }
                    }
                    frames++;
                }
            } catch (IOException | RuntimeException ignored) {
                // Reconnect policy is intentionally state-only; never log audio or credentials.
            } finally {
                if (opened != null) {
                    try { opened.close(); }
                    catch (IOException ignored) { }
                }
                close();
                ended(this, frames >= STABLE_FRAMES);
            }
        }

        void playbackLoop() {
            while (!closed) {
                byte[] frame;
                synchronized (queue) {
                    while (!closed && (!presentation || queue.isEmpty())) {
                        try { queue.wait(); }
                        catch (InterruptedException ignored) { return; }
                    }
                    if (closed) return;
                    frame = queue.removeFirst();
                }
                applyGain(frame, gain);
                try {
                    synchronized (queue) {
                        if (!presentation) continue;
                    }
                    if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
                    int offset = 0;
                    while (!closed && offset < frame.length) {
                        synchronized (queue) {
                            if (!presentation) break;
                        }
                        int written = track.write(frame, offset, frame.length - offset);
                        if (written <= 0) throw new IllegalStateException("Discord audio write failed.");
                        offset += written;
                    }
                } catch (IllegalStateException ignored) {
                    synchronized (queue) {
                        if (!presentation) continue;
                    }
                    failPlayback();
                    return;
                }
            }
        }

        void failPlayback() {
            closed = true;
            if (stream != null) {
                try { stream.close(); }
                catch (IOException ignored) { }
            }
            if (networkThread != null) networkThread.interrupt();
        }

        void setPresentation(boolean enabled, int nextGain) {
            synchronized (queue) {
                presentation = enabled;
                gain = nextGain;
                if (!enabled) queue.clear();
                queue.notifyAll();
            }
            if (!enabled && track != null) {
                try { track.pause(); track.flush(); }
                catch (IllegalStateException ignored) { }
            }
        }

        void close() {
            closed = true;
            synchronized (queue) { queue.clear(); queue.notifyAll(); }
            if (stream != null) {
                try { stream.close(); }
                catch (IOException ignored) { }
            }
            if (networkThread != null) networkThread.interrupt();
            if (playbackThread != null) playbackThread.interrupt();
            AudioTrack currentTrack = track;
            if (currentTrack != null && trackReleased.compareAndSet(false, true)) {
                try { currentTrack.pause(); currentTrack.flush(); }
                catch (IllegalStateException ignored) { }
                currentTrack.release();
            }
        }
    }
}
