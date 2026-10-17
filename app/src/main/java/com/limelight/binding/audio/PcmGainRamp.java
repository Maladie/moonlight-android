package com.limelight.binding.audio;

/**
 * Applies the stream gain to interleaved PCM, moving linearly toward the target over a short
 * ramp: a gain step on playing audio (a mute when leaving for the console) clicks.
 * The target may change on any thread; {@link #apply} runs on the audio thread only.
 */
final class PcmGainRamp {
    private final float stepPerFrame;
    private volatile float target;
    private float gain;

    PcmGainRamp(int rampFrames, float target) {
        this.stepPerFrame = 1f / Math.max(1, rampFrames);
        this.target = target;
    }

    void setTarget(float target) {
        this.target = Math.max(0f, Math.min(1f, target));
    }

    float gain() {
        return gain;
    }

    void apply(short[] pcm, int channels) {
        float goal = target;
        if (gain == 1f && goal == 1f) return;
        for (int frame = 0; frame + channels <= pcm.length; frame += channels) {
            if (gain < goal) gain = Math.min(goal, gain + stepPerFrame);
            else if (gain > goal) gain = Math.max(goal, gain - stepPerFrame);
            for (int c = 0; c < channels; c++) {
                pcm[frame + c] = (short) Math.round(pcm[frame + c] * gain);
            }
        }
    }
}
