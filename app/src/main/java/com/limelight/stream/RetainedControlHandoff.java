package com.limelight.stream;

import java.util.function.Consumer;

/** Completes an already acknowledged host ownership transfer; never abandons its receipt. */
public final class RetainedControlHandoff {
    private RetainedControlHandoff() { }

    public interface Actions {
        boolean current();
        /** Commits the binding; {@code done} receives whether it committed. */
        void commitBinding(Consumer<Boolean> done);
        void publishBinding();
        boolean cancelled();
        void retainNeutral();
        void startGame();
        void compensate();
    }

    public static void finish(Actions actions) {
        try {
            if (actions.current()) {
                actions.commitBinding(committed -> complete(actions, committed));
                return;
            }
        } catch (RuntimeException failure) {
            // A post-ack presentation/connection failure has the same exact compensation.
        }
        actions.compensate();
    }

    private static void complete(Actions actions, boolean committed) {
        try {
            // The commit result arrives later: the switch must still be current to use it.
            if (committed && actions.current()) {
                // Cancellation suppresses the game, not an acknowledged ownership transfer.
                actions.publishBinding();
                if (actions.cancelled()) actions.retainNeutral();
                else actions.startGame();
                return;
            }
        } catch (RuntimeException failure) {
            // A post-ack presentation/connection failure has the same exact compensation.
        }
        actions.compensate();
    }
}
