package com.limelight.console;

import java.util.Objects;

/** Explicit owner of the currently active input/focus region. */
public final class InputRouter {
    public enum Region { GAMEPLAY, HOME, OVERLAY, MODAL, NONE }

    private Region region;

    public InputRouter(Region initialRegion) {
        region = Objects.requireNonNull(initialRegion, "initialRegion");
    }

    /** Returns true only when gameplay capture should change. */
    public synchronized boolean routeTo(Region next) {
        Objects.requireNonNull(next, "next");
        boolean wasGameplay = region == Region.GAMEPLAY;
        region = next;
        return wasGameplay != (next == Region.GAMEPLAY);
    }

    public synchronized Region region() { return region; }
    public synchronized boolean isGameplayCaptured() { return region == Region.GAMEPLAY; }
}
