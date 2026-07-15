package com.limelight.console;

/** Secret-free availability state for a saved streaming host card. */
final class HostAvailability {
    enum State { CHECKING, ONLINE, SLEEPING, OFFLINE, ACTIVE }

    final State state;
    final String address;

    HostAvailability(State state, String address) {
        this.state = state;
        this.address = address == null || address.isEmpty() ? "Address unavailable" : address;
    }

    String label() {
        switch (state) {
            case ONLINE: return "● ONLINE · Host ready";
            case SLEEPING: return "◐ SLEEPING · Wake-on-LAN ready";
            case OFFLINE: return "○ OFFLINE";
            case ACTIVE: return "● THIS TV · Host ready";
            default: return "CHECKING · " + address;
        }
    }

    int color() {
        switch (state) {
            case ONLINE:
            case ACTIVE: return 0xFF69F0AE;
            case SLEEPING: return 0xFFFFB74D;
            case OFFLINE: return 0xFFFF8A80;
            default: return 0xFFABB3CA;
        }
    }
}
