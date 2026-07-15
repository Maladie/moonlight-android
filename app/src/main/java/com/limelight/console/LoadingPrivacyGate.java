package com.limelight.console;

/** Keeps the privacy layer opaque until all signals required by a launch agree. */
public final class LoadingPrivacyGate {
    private final int stableSamplesRequired;
    private boolean firstFrame;
    private boolean bridgeRequired;
    private LaunchOrchestrator.ReadinessSample readiness;

    public LoadingPrivacyGate(int stableSamplesRequired) {
        if (stableSamplesRequired < 1) throw new IllegalArgumentException("stableSamplesRequired");
        this.stableSamplesRequired = stableSamplesRequired;
    }

    public void reset(boolean bridgeRequired) {
        firstFrame = false;
        readiness = null;
        this.bridgeRequired = bridgeRequired;
    }

    public void onFirstDecodedFrame() { firstFrame = true; }
    public void onReadiness(LaunchOrchestrator.ReadinessSample sample) { readiness = sample; }

    public boolean mayReveal() {
        if (!firstFrame) return false;
        if (!bridgeRequired) return true;
        return readiness != null && readiness.visibleForegroundWindow &&
                readiness.onStreamedDisplay && readiness.finalGeometry &&
                readiness.consecutiveStableSamples >= stableSamplesRequired;
    }
}
