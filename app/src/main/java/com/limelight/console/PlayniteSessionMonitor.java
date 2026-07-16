package com.limelight.console;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Watches the active Playnite game without polling the TV UI thread. */
final class PlayniteSessionMonitor implements AutoCloseable {
    interface Listener { void onGameStopped(); }

    private final HostGatewayClient client;
    private final HostGatewayClient.Connection connection;
    private final String gameId;
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PlayniteLifecycle");
        thread.setDaemon(true);
        return thread;
    });

    PlayniteSessionMonitor(HostGatewayClient client,
                           HostGatewayClient.Connection connection,
                           String gameId, Listener listener) {
        this.client = client;
        this.connection = connection;
        this.gameId = gameId;
        this.listener = listener;
    }

    void start() {
        executor.execute(this::run);
    }

    private void run() {
        long cursor = -1;
        while (!closed.get()) {
            try {
                if (cursor < 0) {
                    HostGatewayClient.PlayniteEvents baseline =
                            client.getPlayniteEvents(connection, 0);
                    cursor = baseline.latestSequence;
                    HostGatewayClient.PlayniteCurrentGame current =
                            client.getPlayniteCurrentGame(connection);
                    if (!isCurrent(current)) {
                        notifyStopped();
                        return;
                    }
                }
                HostGatewayClient.PlayniteEvents batch =
                        client.getPlayniteEvents(connection, cursor);
                cursor = Math.max(cursor, batch.latestSequence);
                for (HostGatewayClient.PlayniteEvent event : batch.events) {
                    if ("game-stopped".equals(event.name) &&
                            (event.gameId.isEmpty() || gameId.equalsIgnoreCase(event.gameId))) {
                        notifyStopped();
                        return;
                    }
                }
                HostGatewayClient.PlayniteCurrentGame current =
                        client.getPlayniteCurrentGame(connection);
                if (!isCurrent(current)) {
                    notifyStopped();
                    return;
                }
            } catch (Exception ignored) {
                // Lifecycle telemetry is part of the privacy boundary. If it is
                // lost, cover the stream and enter the same safe return flow.
                notifyStopped();
                return;
            }
        }
    }

    private boolean isCurrent(HostGatewayClient.PlayniteCurrentGame current) {
        return current != null && "running".equals(current.state) &&
                gameId.equalsIgnoreCase(current.id);
    }

    private void notifyStopped() {
        if (closed.compareAndSet(false, true)) listener.onGameStopped();
    }

    @Override public void close() {
        closed.set(true);
        executor.shutdownNow();
    }
}
