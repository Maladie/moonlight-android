package com.limelight.binding.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.ArrayDeque;

public class UsbMicrophoneCaptureTest {
    @Test
    public void boundedQueueDropsOldestAudio() {
        ArrayDeque<byte[]> queue = new ArrayDeque<>();
        byte[][] frames = new byte[8][];
        for (int index = 0; index < frames.length; index++) {
            frames[index] = new byte[] {(byte) index};
            UsbMicrophoneCapture.enqueueBounded(queue, frames[index]);
        }

        assertEquals(6, queue.size());
        assertSame(frames[2], queue.removeFirst());
        assertSame(frames[7], queue.removeLast());
        assertEquals(1920, UsbMicrophoneCapture.FRAME_BYTES);
    }
}
