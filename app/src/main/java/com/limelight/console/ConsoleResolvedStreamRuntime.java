package com.limelight.console;

/** In-Activity runtime boundary after a saved host has been fully resolved. */
interface ConsoleResolvedStreamRuntime extends AutoCloseable {
    interface Listener {
        void onConnected();
        void onConnectionFailed(String reason);
    }

    void connect(StreamLaunchParameters parameters, Listener listener);
    void cancelPendingConnection();
    default void cancelPendingConnection(Runnable afterStopped) {
        cancelPendingConnection();
        if (afterStopped != null) afterStopped.run();
    }
    void showStream();
    void showHome();
    void quitHostApplication();
    @Override void close();
}
