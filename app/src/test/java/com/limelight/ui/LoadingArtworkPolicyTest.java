package com.limelight.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LoadingArtworkPolicyTest {
    @Test
    public void acceptsLandscapeHdArtwork() {
        assertTrue(LoadingArtworkPolicy.canUseAsSplash(1280, 720));
        assertTrue(LoadingArtworkPolicy.canUseAsSplash(1920, 1080));
    }

    @Test
    public void rejectsSmallOrPortraitArtwork() {
        assertFalse(LoadingArtworkPolicy.canUseAsSplash(1279, 720));
        assertFalse(LoadingArtworkPolicy.canUseAsSplash(1280, 719));
        assertFalse(LoadingArtworkPolicy.canUseAsSplash(800, 1200));
    }
}
