package com.limelight.console;

final class PlayniteIdentityResolutionPolicy {
    enum Action {
        REQUEST,
        CLEAR,
        PRESERVE
    }

    private PlayniteIdentityResolutionPolicy() {
    }

    static Action decide(boolean online, boolean paired, int runningGameAppId,
                         SessionSnapshot.State state) {
        if (state == SessionSnapshot.State.TERMINATING) return Action.CLEAR;
        if (online && paired && runningGameAppId != 0) return Action.REQUEST;
        if (state == SessionSnapshot.State.NONE) return Action.CLEAR;
        return Action.PRESERVE;
    }
}
