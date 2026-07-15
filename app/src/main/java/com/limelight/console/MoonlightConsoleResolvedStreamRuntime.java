package com.limelight.console;

import java.util.Objects;

/** Single-session runtime core for streaming inside ConsoleActivity. */
final class MoonlightConsoleResolvedStreamRuntime implements ConsoleResolvedStreamRuntime {
    interface Session {
        void connect();
        void disconnect();
        void showStream();
        void showHome();
    }

    interface SessionFactory {
        Session create(StreamLaunchParameters parameters,
                       ConsoleResolvedStreamRuntime.Listener listener);
    }

    private final SessionFactory sessionFactory;
    private Session session;
    private int generation;
    private boolean closed;

    MoonlightConsoleResolvedStreamRuntime(SessionFactory sessionFactory) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    }

    @Override public synchronized void connect(
            StreamLaunchParameters parameters,
            ConsoleResolvedStreamRuntime.Listener listener) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(listener, "listener");
        if (closed) {
            listener.onConnectionFailed("Runtime is closed");
            return;
        }
        if (session != null) {
            listener.onConnectionFailed("A stream session is already active");
            return;
        }

        int sessionGeneration = ++generation;
        ConsoleResolvedStreamRuntime.Listener guardedListener =
                new ConsoleResolvedStreamRuntime.Listener() {
                    @Override public void onConnected() {
                        deliverConnected(sessionGeneration, listener);
                    }

                    @Override public void onConnectionFailed(String reason) {
                        deliverFailure(sessionGeneration, listener, reason);
                    }
                };
        try {
            session = Objects.requireNonNull(
                    sessionFactory.create(parameters, guardedListener),
                    "sessionFactory returned null");
            session.connect();
        } catch (RuntimeException error) {
            Session failedSession = session;
            session = null;
            generation++;
            if (failedSession != null) {
                failedSession.disconnect();
            }
            listener.onConnectionFailed("Session preparation failed");
        }
    }

    @Override public synchronized void cancelPendingConnection() {
        disconnectCurrent();
    }

    @Override public synchronized void showStream() {
        if (session != null) {
            session.showStream();
        }
    }

    @Override public synchronized void showHome() {
        if (session != null) {
            session.showHome();
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        disconnectCurrent();
    }

    private synchronized void deliverConnected(
            int sessionGeneration,
            ConsoleResolvedStreamRuntime.Listener listener) {
        if (!closed && session != null && sessionGeneration == generation) {
            listener.onConnected();
        }
    }

    private synchronized void deliverFailure(
            int sessionGeneration,
            ConsoleResolvedStreamRuntime.Listener listener,
            String reason) {
        if (closed || session == null || sessionGeneration != generation) {
            return;
        }
        Session failedSession = session;
        session = null;
        generation++;
        failedSession.disconnect();
        listener.onConnectionFailed(reason == null ? "Connection failed" : reason);
    }

    private void disconnectCurrent() {
        generation++;
        Session current = session;
        session = null;
        if (current != null) {
            current.disconnect();
        }
    }
}
