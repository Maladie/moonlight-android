package com.limelight.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LoadingArtworkPolicyTest {
    @Test
    public void acceptsLandscapeHdArtwork() {
        assertTrue(LoadingArtworkPolicy.canUseAsSplash(1004, 550));
        assertTrue(LoadingArtworkPolicy.canUseAsSplash(1004, 626));
        assertTrue(LoadingArtworkPolicy.canUseAsSplash(1280, 720));
        assertTrue(LoadingArtworkPolicy.canUseAsSplash(1920, 1080));
    }

    @Test
    public void rejectsSmallOrPortraitArtwork() {
        assertFalse(LoadingArtworkPolicy.canUseAsSplash(639, 360));
        assertFalse(LoadingArtworkPolicy.canUseAsSplash(640, 359));
        assertFalse(LoadingArtworkPolicy.canUseAsSplash(800, 1200));
    }
}
