package com.limelight.console;

import java.util.Objects;

/** Non-owning bridge used while Game still owns NvConnection and decoder lifetime. */
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
        throw new IllegalStateException("Legacy Game owns connection creation");
    }

    @Override public void disconnectTransport() { delegate.requestDisconnectTransport(); }
    @Override public void quitHostApplication() { delegate.requestQuitHostApplication(); }
}
