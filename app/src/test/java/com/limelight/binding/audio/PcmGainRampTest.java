package com.limelight.binding.audio;

import org.junit.Test;
import static org.junit.Assert.*;

public class PcmGainRampTest {
    @Test public void muteRampsDownPerFrameInsteadOfStepping() {
        PcmGainRamp ramp = new PcmGainRamp(4, 1f);
        short[] loud = {1000, 1000, 1000, 1000};
        ramp.apply(loud, 2); // fades in from silence: 0.25, 0.5
        assertArrayEquals(new short[] {250, 250, 500, 500}, loud);
        ramp.apply(new short[] {1000, 1000, 1000, 1000}, 2); // reaches 1
        assertEquals(1f, ramp.gain(), 0f);

        ramp.setTarget(0f);
        short[] tail = {1000, -1000, 1000, -1000, 1000, -1000, 1000, -1000, 1000, -1000};
        ramp.apply(tail, 2);
        assertArrayEquals(new short[] {750, -750, 500, -500, 250, -250, 0, 0, 0, 0}, tail);
        assertEquals(0f, ramp.gain(), 0f);
    }

    @Test public void fullGainLeavesPcmUntouched() {
        PcmGainRamp ramp = new PcmGainRamp(1, 1f);
        ramp.apply(new short[] {1, 1}, 2);
        short[] pcm = {32767, -32768};
        ramp.apply(pcm, 2);
        assertArrayEquals(new short[] {32767, -32768}, pcm);
    }
}
