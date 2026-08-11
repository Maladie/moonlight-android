package com.limelight.nvstream.jni;

/** Native controls used when restoring a transport that stayed connected in the background. */
public final class BackgroundStreamBridge {
    private BackgroundStreamBridge() { }

    public static native void requestIdrFrame();
}
