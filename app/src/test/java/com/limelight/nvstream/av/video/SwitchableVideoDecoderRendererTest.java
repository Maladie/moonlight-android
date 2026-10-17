package com.limelight.nvstream.av.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SwitchableVideoDecoderRendererTest {
    @Test
    public void detachDiscardsFramesAndAttachRestoresNegotiatedFormat() {
        FakeRenderer first = new FakeRenderer(17);
        SwitchableVideoDecoderRenderer renderer = new SwitchableVideoDecoderRenderer(first);

        assertEquals(0, renderer.setup(4, 1920, 1080, 60));
        renderer.start();
        renderer.submitDecodeUnit(new byte[] { 1 }, 1, 0, 1, 0,
                (char) 0, 0, 0, 0);
        assertEquals(1, first.frames);

        renderer.detach();
        assertTrue(renderer.isDetached());
        assertEquals(1, first.stops);
        assertEquals(1, first.cleanups);
        renderer.submitDecodeUnit(new byte[] { 2 }, 1, 0, 2, 0,
                (char) 0, 0, 0, 0);
        assertEquals(1, first.frames);

        FakeRenderer second = new FakeRenderer(99);
        assertTrue(renderer.attach(second));
        assertFalse(renderer.isDetached());
        assertEquals(4, second.format);
        assertEquals(1920, second.width);
        assertEquals(1080, second.height);
        assertEquals(60, second.redrawRate);
        assertEquals(1, second.starts);
        assertEquals(17, renderer.getCapabilities());
        renderer.submitDecodeUnit(new byte[] { 3 }, 1, 0, 3, 0,
                (char) 0, 0, 0, 0);
        assertEquals(1, second.frames);
    }

    @Test
    public void failedReplacementLeavesDiscardRendererAttached() {
        FakeRenderer first = new FakeRenderer(1);
        SwitchableVideoDecoderRenderer renderer = new SwitchableVideoDecoderRenderer(first);
        renderer.setup(1, 1280, 720, 60);
        renderer.start();
        renderer.detach();

        FakeRenderer failed = new FakeRenderer(1);
        failed.setupResult = -1;
        assertFalse(renderer.attach(failed));
        assertTrue(renderer.isDetached());
        assertEquals(1, failed.cleanups);
    }

    private static final class FakeRenderer extends VideoDecoderRenderer {
        final int capabilities;
        int setupResult;
        int format;
        int width;
        int height;
        int redrawRate;
        int starts;
        int stops;
        int cleanups;
        int frames;

        FakeRenderer(int capabilities) { this.capabilities = capabilities; }

        @Override public int setup(int format, int width, int height, int redrawRate) {
            this.format = format;
            this.width = width;
            this.height = height;
            this.redrawRate = redrawRate;
            return setupResult;
        }
        @Override public void start() { starts++; }
        @Override public void stop() { stops++; }
        @Override public int submitDecodeUnit(byte[] data, int length, int type, int frame,
                                               int frameType, char hostLatency, long receiveUs,
                                               long enqueueUs, long presentationUs) {
            frames++;
            return 0;
        }
        @Override public void cleanup() { cleanups++; }
        @Override public int getCapabilities() { return capabilities; }
        @Override public void setHdrMode(boolean enabled, byte[] metadata) { }
    }
}
