package com.limelight.console;

/** In-process Home status for the stream owned directly by ConsoleActivity. */
final class UnifiedConsoleHomeSession {
    private ConsoleDataRepository.Host host;
    private ConsoleDataRepository.App app;
    private int width;
    private int height;
    private int fps;
    private boolean connected;

    void begin(ConsoleDataRepository.Host host, ConsoleDataRepository.App app) {
        this.host = host;
        this.app = app;
        width = 0;
        height = 0;
        fps = 0;
        connected = false;
    }

    void planned(int width, int height, int fps) {
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
        this.fps = Math.max(0, fps);
    }

    void connected() {
        connected = true;
    }

    void clear() {
        host = null;
        app = null;
        width = 0;
        height = 0;
        fps = 0;
        connected = false;
    }

    ConsoleDataRepository.Session visibleOr(ConsoleDataRepository.Session fallback) {
        if (host == null || app == null) return fallback;
        return new ConsoleDataRepository.Session(
                connected ? "streaming" : "connecting",
                connected ? "ready" : "preparing",
                host.name,
                app.name,
                width,
                height,
                fps,
                connected);
    }
}
