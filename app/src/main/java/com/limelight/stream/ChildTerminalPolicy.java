package com.limelight.stream;

/** Classifies a host child-session terminal result without retaining an Activity. */
public final class ChildTerminalPolicy {
    public enum Action { RETAIN_GAME_ONLY, TERMINATE_SESSION }

    private ChildTerminalPolicy() { }

    public static Action action(String reason, boolean cleanupRequired) {
        return !cleanupRequired && "game_stopped".equalsIgnoreCase(
                reason == null ? "" : reason.trim())
                ? Action.RETAIN_GAME_ONLY : Action.TERMINATE_SESSION;
    }

    public static void apply(String reason, boolean cleanupRequired,
                             boolean canRetain, Runnable retainGameOnly,
                             Runnable terminateSession) {
        if (action(reason, cleanupRequired) == Action.RETAIN_GAME_ONLY && canRetain) {
            retainGameOnly.run();
        } else {
            terminateSession.run();
        }
    }
}
