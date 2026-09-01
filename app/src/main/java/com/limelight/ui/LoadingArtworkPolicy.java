package com.limelight.ui;

/** Shared sizing rules for library backdrops, loading screens and the screen saver. */
public final class LoadingArtworkPolicy {
    private static final int MINIMUM_WIDTH = 640;
    private static final int MINIMUM_HEIGHT = 360;

    public static boolean canUseAsSplash(int width, int height) {
        return Math.max(width, height) >= MINIMUM_WIDTH
                && Math.min(width, height) >= MINIMUM_HEIGHT;
    }

    public static float scale(int width, int height, int screenWidth, int screenHeight) {
        if (width <= 0 || height <= 0 || screenWidth <= 0 || screenHeight <= 0) return 1f;
        float fit = Math.min((float) screenWidth / width, (float) screenHeight / height);
        float fill = Math.max((float) screenWidth / width, (float) screenHeight / height);
        // ponytail: allow at most 15% cropping for landscape art; no content-aware cropping.
        boolean modestCrop = width > height && fit / fill >= .85f;
        float maximum = canUseAsSplash(width, height) ? 2f : 1f;
        return Math.min(modestCrop ? fill : fit, maximum);
    }

    public static int sampleSize(int width, int height, int targetDimension) {
        int sample = 1;
        while (Math.max(width, height) / (sample * 2) >= targetDimension) sample *= 2;
        return sample;
    }

    private LoadingArtworkPolicy() {
    }
}
