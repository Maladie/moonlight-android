package com.limelight.stream;

/** Completes an already acknowledged host ownership transfer; never abandons its receipt. */
public final class RetainedControlHandoff {
    private RetainedControlHandoff() { }

    public interface Actions {
        boolean current();
        boolean commitBinding();
        void publishBinding();
        boolean cancelled();
        void retainNeutral();
        void startGame();
        void compensate();
    }

    public static void finish(Actions actions) {
        try {
            if (actions.current() && actions.commitBinding()) {
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
