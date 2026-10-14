package com.limelight.stream;

import java.util.function.Consumer;

/**
 * The neutral stream's own parent picked the game the host verifiably runs: the stream observes
 * that exact process as its connection. Nothing is stopped, started or reauthorized; the host
 * stream record stays as the parent's stream launch left it (the provider binds a process only
 * for a child time session). Every failure goes back to the switch's recovery.
 */
public final class RetainedRunningAdoption {
    private RetainedRunningAdoption() { }

    static final String OWNER_CHANGED = "retained_switch_owner_changed";
    static final String PROCESS_CHANGED = "retained_switch_process_changed";
    static final String SESSION_CHANGED = "retained_switch_session_changed";

    public interface Actions {
        boolean current();
        boolean cancelled();
        /**
         * Reserves the provider owner of the observation and binds it to the decided process;
         * false when another owner holds the host or the process is not exactly proven.
         */
        boolean reserveOwner(String processToken, int processId);
        /** A fresh host read; {@code done} gets whether it still runs exactly that process. */
        void confirmProcess(String processToken, int processId, Consumer<Boolean> done);
        /** The session model takes the game; {@code done} gets whether it accepted. */
        void commitGame(Consumer<Boolean> done);
        void present();
        /** Releases the reserved owner of an adoption that does not complete. */
        void release();
        void recover(boolean cancelled, String reason);
    }

    public static void adopt(Actions actions, String processToken, int processId) {
        if (!actions.current()) return;
        if (actions.cancelled()) {
            actions.recover(true, "");
            return;
        }
        if (!actions.reserveOwner(processToken, processId)) {
            actions.release();
            actions.recover(false, OWNER_CHANGED);
            return;
        }
        // The decision read the host on another thread: the process must still be that one.
        actions.confirmProcess(processToken, processId, confirmed -> {
            if (!proceeds(actions, confirmed, true, PROCESS_CHANGED)) return;
            // Once the model holds the game, a later cancel is the presented switch's own.
            actions.commitGame(updated -> {
                if (proceeds(actions, updated, false, SESSION_CHANGED)) actions.present();
            });
        });
    }

    /** True when the adoption goes on; otherwise it released its owner and recovered. */
    private static boolean proceeds(Actions actions, boolean accepted, boolean cancellable,
                                    String refusal) {
        if (!actions.current()) {
            actions.release();
            return false;
        }
        boolean cancelled = accepted && cancellable && actions.cancelled();
        if (accepted && !cancelled) return true;
        actions.release();
        actions.recover(cancelled, cancelled ? "" : refusal);
        return false;
    }
}
