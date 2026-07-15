package com.limelight.console;

import java.util.Objects;

/** Keeps one controller-owned stream attached to Console's persistent surface. */
final class MoonlightConsoleSession implements MoonlightConsoleResolvedStreamRuntime.Session {
    interface Connection {
        void connect();
        void prepareRendererForStop();
        void disconnect(Runnable afterStopped);
    }

    interface SurfaceOwnership {
        void attach();
        boolean bindConsoleSurface();
        void detach();
    }

    private final Connection connection;
    private final SurfaceOwnership surfaces;
    private boolean disconnected;

    static MoonlightConsoleSession create(MoonlightStreamSessionController controller) {
        Objects.requireNonNull(controller, "controller");
        return new MoonlightConsoleSession(new Connection() {
            @Override public void connect() {
                controller.connect();
            }

            @Override public void prepareRendererForStop() {
                controller.prepareRendererForStop();
            }

            @Override public void disconnect(Runnable afterStopped) {
                controller.disconnectTransport(afterStopped);
            }
        }, new SurfaceOwnership() {
            @Override public void attach() {
                ActiveStreamSurfaceBridge.attachSession(controller);
            }

            @Override public boolean bindConsoleSurface() {
                return ActiveStreamSurfaceBridge.bindConsoleIfForeground(controller);
            }

            @Override public void detach() {
                ActiveStreamSurfaceBridge.detachSession(controller);
            }
        });
    }

    MoonlightConsoleSession(Connection connection, SurfaceOwnership surfaces) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.surfaces = Objects.requireNonNull(surfaces, "surfaces");
        surfaces.attach();
    }

    @Override public synchronized void connect() {
        if (disconnected) {
            throw new IllegalStateException("Session is disconnected");
        }
        connection.connect();
    }

    @Override public synchronized void disconnect() {
        if (disconnected) return;
        disconnected = true;
        connection.prepareRendererForStop();
        connection.disconnect(surfaces::detach);
    }

    @Override public synchronized void showStream() {
        bindIfActive();
    }

    @Override public synchronized void showHome() {
        // Home is an opaque layer above the persistent stream surface. Keep the
        // renderer bound so returning to gameplay never recreates the decoder.
        bindIfActive();
    }

    private void bindIfActive() {
        if (!disconnected) {
            surfaces.bindConsoleSurface();
        }
    }
}
