package com.limelight.console;

import java.util.Objects;

/** Non-owning bridge used while ConsoleActivity still delegates visible stream lifecycle to Game. */
public final class LegacyGameSessionAdapter implements StreamSessionController {
    public interface Delegate {
        SessionState state();
        void requestDisconnectTransport();
        void requestQuitHostApplication();
    }

    private final Delegate delegate;

    public LegacyGameSessionAdapter(Delegate delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override public SessionState state() { return delegate.state(); }

    @Override public void connect() {
        throw new IllegalStateException("Legacy adapter cannot create a second connection");
    }

    @Override public void disconnectTransport() { delegate.requestDisconnectTransport(); }
    @Override public void quitHostApplication() { delegate.requestQuitHostApplication(); }
}
