package com.limelight.ui;

/** Keeps undersized or portrait artwork from degrading the TV loading screen. */
public final class LoadingArtworkPolicy {
    private static final int MINIMUM_WIDTH = 640;
    private static final int MINIMUM_HEIGHT = 360;

    public static boolean canUseAsSplash(int width, int height) {
        return width >= MINIMUM_WIDTH && height >= MINIMUM_HEIGHT
                && width >= height * 1.3f;
    }

    private LoadingArtworkPolicy() {
    }
}
