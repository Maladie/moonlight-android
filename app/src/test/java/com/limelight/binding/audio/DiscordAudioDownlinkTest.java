package com.limelight.binding.audio;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiscordAudioDownlinkTest {
    @Test public void queueDropsOldestAtSixFrames() {
        ArrayDeque<byte[]> queue = new ArrayDeque<>();
        for (int value = 0; value < 7; value++) {
            DiscordAudioDownlink.enqueue(queue, new byte[]{(byte) value});
        }
        assertEquals(6, queue.size());
        assertEquals(1, queue.removeFirst()[0]);
        assertEquals(6, queue.removeLast()[0]);
    }

    @Test public void gainSaturatesPcm16() {
        byte[] frame = {0x10, 0x27, (byte) 0xf0, (byte) 0xd8}; // +10000, -10000
        DiscordAudioDownlink.applyGain(frame, 200);
        assertEquals(20000, sample(frame, 0));
        assertEquals(-20000, sample(frame, 2));

        byte[] clipping = {(byte) 0xff, 0x7f, 0x00, (byte) 0x80};
        DiscordAudioDownlink.applyGain(clipping, 200);
        assertEquals(Short.MAX_VALUE, sample(clipping, 0));
        assertEquals(Short.MIN_VALUE, sample(clipping, 2));
    }

    @Test public void reconnectBackoffIsBounded() {
        int delay = 1;
        int[] expected = {2, 4, 8, 15, 15};
        for (int value : expected) {
            delay = DiscordAudioDownlink.nextRetrySeconds(delay);
            assertEquals(value, delay);
        }
    }

    @Test public void applicationOwnsPresentationAndPlaybackFailureReconnects() throws Exception {
        String application = source("app/src/main/java/com/limelight/MoonWakerApplication.java");
        String downlink = source(
                "app/src/main/java/com/limelight/binding/audio/DiscordAudioDownlink.java");
        assertTrue(application.contains("!(activity instanceof Game)"));
        assertTrue(application.contains("postDelayed(stopDiscordAudio"));
        assertFalse(application.contains("PREF_ENABLED, false)\n                .start"));
        assertTrue(downlink.contains("if (!presentation) continue;"));
        assertTrue(downlink.contains("failPlayback();"));
        assertTrue(downlink.contains("frames >= STABLE_FRAMES"));
        assertTrue(downlink.contains("if (session != ended) return;"));
        assertTrue(downlink.contains("generation != ended.generation"));
        assertTrue(downlink.contains("GatewayTransport.DiscordAudioStream opened = null;"));
        assertTrue(downlink.contains("if (closed) return;"));
        assertTrue(downlink.contains("trackReleased.compareAndSet(false, true)"));
        assertTrue(downlink.indexOf("opened.close();")
                < downlink.indexOf("ended(this, frames >= STABLE_FRAMES)"));
    }

    private static int sample(byte[] data, int offset) {
        return (short) ((data[offset] & 0xff) | (data[offset + 1] << 8));
    }

    private static String source(String relative) throws Exception {
        Path path = Paths.get(relative);
        if (!Files.exists(path)) path = Paths.get("..", relative);
        return new String(Files.readAllBytes(path));
    }
}
