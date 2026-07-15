package com.limelight.console;

/** Sole session-lifetime boundary; quit-host is intentionally not disconnect. */
public interface StreamSessionController {
    enum SessionState { IDLE, CONNECTING, CONNECTED, RECOVERING, DISCONNECTING }
    SessionState state();
    void connect();
    void disconnectTransport();
    void quitHostApplication();
}
