package com.limelight.console;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Generates MoonWaker's original, dependency-free console sound palette. */
final class ConsoleAudioSynthesis {
    enum Cue {
        NAVIGATE(0.075f),
        SUCCESS(0.45f),
        ERROR(0.30f),
        LAUNCH(0.68f);

        final float durationSeconds;

        Cue(float durationSeconds) {
            this.durationSeconds = durationSeconds;
        }
    }

    private ConsoleAudioSynthesis() { }

    static short[] renderCue(Cue cue, int sampleRate) {
        int frames = Math.max(1, Math.round(cue.durationSeconds * sampleRate));
        short[] samples = new short[frames];
        for (int frame = 0; frame < frames; frame++) {
            double time = frame / (double) sampleRate;
            double progress = frame / (double) Math.max(1, frames - 1);
            double attack = Math.min(1.0, time / 0.008);
            double release = Math.pow(Math.max(0.0, 1.0 - progress), 1.65);
            double value;
            switch (cue) {
                case NAVIGATE:
                    value = sine(610, time) * 0.70 + sine(915, time) * 0.30;
                    value *= attack * Math.pow(1.0 - progress, 2.7) * 0.20;
                    break;
                case SUCCESS:
                    value = successChord(time, cue.durationSeconds);
                    value *= attack * release * 0.25;
                    break;
                case ERROR:
                    double pulse = 0.62 + 0.38 * Math.cos(2.0 * Math.PI * 7.0 * time);
                    value = (sine(174, time) * 0.62 + sine(207, time) * 0.38)
                            * pulse * attack * release * 0.26;
                    break;
                case LAUNCH:
                default:
                    double lift = chirp(145, 820, time, cue.durationSeconds) * 0.40
                            + chirp(290, 1240, time, cue.durationSeconds) * 0.18;
                    double air = sine(1180, time) * Math.sin(Math.PI * progress) * 0.11;
                    value = (lift + air) * attack
                            * Math.pow(Math.max(0.0, 1.0 - progress), 0.72) * 0.46;
                    break;
            }
            samples[frame] = pcm(value);
        }
        return samples;
    }

    static void writeWave(File file, short[] samples, int sampleRate, int channels)
            throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create audio cache directory");
        }
        int dataBytes = samples.length * 2;
        try (BufferedOutputStream output = new BufferedOutputStream(
                new FileOutputStream(file))) {
            output.write(new byte[]{'R', 'I', 'F', 'F'});
            writeInt(output, 36 + dataBytes);
            output.write(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
            writeInt(output, 16);
            writeShort(output, 1);
            writeShort(output, channels);
            writeInt(output, sampleRate);
            writeInt(output, sampleRate * channels * 2);
            writeShort(output, channels * 2);
            writeShort(output, 16);
            output.write(new byte[]{'d', 'a', 't', 'a'});
            writeInt(output, dataBytes);
            for (short sample : samples) writeShort(output, sample);
        }
    }

    private static double successChord(double time, double duration) {
        double third = duration / 3.0;
        double first = noteWindow(time, 0.0, third * 1.25) * sine(440.0, time);
        double second = noteWindow(time, third * 0.72, third * 2.20) * sine(554.37, time);
        double thirdNote = noteWindow(time, third * 1.48, duration) * sine(659.25, time);
        return first * 0.58 + second * 0.48 + thirdNote * 0.54;
    }

    private static double noteWindow(double time, double start, double end) {
        if (time < start || time >= end) return 0.0;
        double progress = (time - start) / Math.max(0.001, end - start);
        return Math.min(1.0, progress / 0.08) * Math.pow(1.0 - progress, 1.25);
    }

    private static double sine(double frequency, double time) {
        return Math.sin(2.0 * Math.PI * frequency * time);
    }

    private static double chirp(double from, double to, double time, double duration) {
        double slope = (to - from) / duration;
        return Math.sin(2.0 * Math.PI * (from * time + 0.5 * slope * time * time));
    }

    private static short pcm(double value) {
        double safe = Math.max(-0.98, Math.min(0.98, value));
        return (short) Math.round(safe * Short.MAX_VALUE);
    }

    private static void writeInt(BufferedOutputStream output, int value) throws IOException {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 24) & 0xFF);
    }

    private static void writeShort(BufferedOutputStream output, int value) throws IOException {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
    }
}
