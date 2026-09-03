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
        // ponytail: allow at most 16% cropping; no content-aware cropping.
        boolean modestCrop = width > height
                && canFillWithModestCrop(width, height, screenWidth, screenHeight);
        float maximum = canUseAsSplash(width, height) ? 2f : 1f;
        return Math.min(modestCrop ? fill : fit, maximum);
    }

    public static boolean canFillWithModestCrop(int width, int height,
                                                 int targetWidth, int targetHeight) {
        if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) return false;
        float fit = Math.min((float) targetWidth / width, (float) targetHeight / height);
        float fill = Math.max((float) targetWidth / width, (float) targetHeight / height);
        return fit / fill >= .84f;
    }

    public static int sampleSize(int width, int height, int targetDimension) {
        int sample = 1;
        while (Math.max(width, height) / (sample * 2) >= targetDimension) sample *= 2;
        return sample;
    }

    private LoadingArtworkPolicy() {
    }
}
