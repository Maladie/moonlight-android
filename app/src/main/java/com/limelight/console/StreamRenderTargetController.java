package com.limelight.console;

import android.view.SurfaceHolder;

/** Session-owned boundary for binding decoder output without exposing the renderer. */
public interface StreamRenderTargetController {
    void setInitialRenderTarget(SurfaceHolder renderTarget);
    boolean switchToRenderTarget(SurfaceHolder renderTarget);
    boolean switchToBackgroundSurface();
    void prepareRendererForStop();
}
