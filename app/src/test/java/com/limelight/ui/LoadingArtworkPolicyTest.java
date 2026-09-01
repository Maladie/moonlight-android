package com.limelight.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class LoadingArtworkPolicyTest {
    @Test
    public void acceptsLandscapeHdArtwork() {
        assertTrue(LoadingArtworkPolicy.canUseAsSplash(1004, 550));
        assertTrue(LoadingArtworkPolicy.canUseAsSplash(1004, 626));
        assertTrue(LoadingArtworkPolicy.canUseAsSplash(1280, 720));
        assertTrue(LoadingArtworkPolicy.canUseAsSplash(1920, 1080));
    }

    @Test
    public void rejectsThumbnailsButAllowsPortraitSplash() {
        assertFalse(LoadingArtworkPolicy.canUseAsSplash(639, 360));
        assertFalse(LoadingArtworkPolicy.canUseAsSplash(640, 359));
        assertTrue(LoadingArtworkPolicy.canUseAsSplash(800, 1200));
    }

    @Test public void fillsNearScreenAspectWithoutStretchingAndContainsPortraits() {
        assertEquals(1.5f, LoadingArtworkPolicy.scale(1280, 720, 1920, 1080), .001f);
        assertEquals(1920f / 1004, LoadingArtworkPolicy.scale(1004, 626, 1920, 1080), .001f);
        assertEquals(.9f, LoadingArtworkPolicy.scale(800, 1200, 1920, 1080), .001f);
        assertEquals(1.44f, LoadingArtworkPolicy.scale(1000, 750, 1920, 1080), .001f);
        assertEquals(2f, LoadingArtworkPolicy.scale(640, 360, 1920, 1080), .001f);
        assertEquals(1f, LoadingArtworkPolicy.scale(320, 180, 1920, 1080), .001f);
    }

    @Test public void downsamplingKeepsEnoughPixelsForTheDisplay() {
        assertEquals(1, LoadingArtworkPolicy.sampleSize(2560, 1440, 1920));
        assertEquals(2, LoadingArtworkPolicy.sampleSize(3840, 2160, 1920));
    }
}
