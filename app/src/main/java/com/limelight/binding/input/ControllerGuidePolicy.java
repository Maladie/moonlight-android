package com.limelight.binding.input;

import com.limelight.nvstream.input.ControllerPacket;

import java.util.Locale;

/** Short-lived host decision; never changes local Guide gestures or other buttons. */
public final class ControllerGuidePolicy {
    private String transitionId = "";
    private String expectedGameId = "";
    private boolean managed;
    private boolean allowed = true;

    public synchronized void begin(String transitionId, String gameId, boolean managed) {
        if (this.transitionId.equals(transitionId) && this.managed == managed) return;
        this.transitionId = transitionId;
        this.expectedGameId = normalize(gameId);
        this.managed = managed;
        // Do not expose Guide while a managed target's provider is still unknown.
        allowed = !managed;
    }

    public synchronized void observe(String transitionId, String gameId, boolean allowed) {
        if (!managed || !this.transitionId.equals(transitionId)) return;
        if (!expectedGameId.isEmpty() && !expectedGameId.equals(normalize(gameId))) return;
        this.allowed = allowed;
    }

    public synchronized int filter(int buttons) {
        return allowed ? buttons : buttons & ~ControllerPacket.SPECIAL_BUTTON_FLAG;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
