package com.limelight.stream;

/** One-shot join for immediate local shutdown and independent host cleanup. */
public final class WholeSessionTermination {
    @FunctionalInterface
    public interface Starter { void start(Completion completion); }
    @FunctionalInterface
    public interface Completion { void complete(boolean success); }

    /** Owns the one terminal operation allowed for a Game instance. */
    public static final class Owner {
        private boolean running;

        public synchronized boolean isRunning() { return running; }

        public synchronized boolean begin() {
            if (running) return false;
            running = true;
            return true;
        }

        public void finish() {
            synchronized (this) { running = false; }
        }

        public Completion releaseAfter(Completion completion) {
            return success -> {
                try {
                    if (completion != null) completion.complete(success);
                } finally {
                    finish();
                }
            };
        }

        public void run(Runnable privacyAndInput, Runnable openHome,
                        Starter localStop, Starter hostCleanup,
                        Completion completion) {
            if (!isRunning()) throw new IllegalStateException("termination not begun");
            WholeSessionTermination.start(privacyAndInput, openHome, localStop,
                    hostCleanup, releaseAfter(completion));
        }
    }

    private boolean localDone;
    private boolean hostDone;
    private boolean localSuccess;
    private boolean hostSuccess;
    private boolean actionSuccess = true;
    private Completion completion;

    private WholeSessionTermination(Completion completion) {
        this.completion = completion;
    }

    public static void start(Runnable privacyAndInput, Runnable openHome,
                             Starter localStop, Starter hostCleanup,
                             Completion completion) {
        if (privacyAndInput == null || openHome == null || localStop == null
                || hostCleanup == null) throw new NullPointerException("termination action");
        WholeSessionTermination operation = new WholeSessionTermination(completion);
        try {
            privacyAndInput.run();
        } catch (RuntimeException failure) {
            operation.actionFailed();
        }
        try {
            localStop.start(success -> operation.finish(true, success));
        } catch (RuntimeException failure) {
            operation.finish(true, false);
        }
        try {
            openHome.run();
        } catch (RuntimeException failure) {
            operation.actionFailed();
        }
        try {
            hostCleanup.start(success -> operation.finish(false, success));
        } catch (RuntimeException failure) {
            operation.finish(false, false);
        }
    }

    private synchronized void actionFailed() { actionSuccess = false; }

    private synchronized void finish(boolean local, boolean success) {
        if (local ? localDone : hostDone) return;
        if (local) {
            localDone = true;
            localSuccess = success;
        } else {
            hostDone = true;
            hostSuccess = success;
        }
        if (!localDone || !hostDone) return;
        Completion terminal = completion;
        completion = null;
        if (terminal != null) terminal.complete(actionSuccess && localSuccess && hostSuccess);
    }
}
