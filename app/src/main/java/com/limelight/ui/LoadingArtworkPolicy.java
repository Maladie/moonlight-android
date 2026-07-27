package com.limelight.ui;

/** Keeps undersized or portrait artwork from degrading the TV loading screen. */
final class LoadingArtworkPolicy {
    private static final int MINIMUM_WIDTH = 1280;
    private static final int MINIMUM_HEIGHT = 720;

    static boolean canUseAsSplash(int width, int height) {
        return width >= MINIMUM_WIDTH && height >= MINIMUM_HEIGHT;
    }

    private LoadingArtworkPolicy() {
    }
}
