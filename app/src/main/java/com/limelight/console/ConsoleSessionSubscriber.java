package com.limelight.console;

import com.limelight.R;
import com.limelight.stream.RetainedStreamSessionCoordinator;

import java.util.function.IntConsumer;

/**
 * The retained-stream snapshot a Console screen renders: delivered by the coordinator while
 * the screen is started, then held (never re-read) until the next delivery.
 */
final class ConsoleSessionSubscriber implements RetainedStreamSessionCoordinator.Listener {
    private final IntConsumer render;
    /**
     * Set by the first {@link #start()}. Session rendering needs a known host, and hosts are
     * only loaded by callbacks that run after the screen's first onStart.
     */
    private RetainedStreamSessionCoordinator.Snapshot delivered;

    /** {@code render} receives the console update channels a session change invalidates. */
    ConsoleSessionSubscriber(IntConsumer render) {
        this.render = render;
    }

    void start() {
        RetainedStreamSessionCoordinator.Snapshot current =
                RetainedStreamSessionCoordinator.subscribe(this);
        if (delivered == null) delivered = current;
        else onSnapshot(current);
    }

    void stop() {
        RetainedStreamSessionCoordinator.unsubscribe(this);
    }

    RetainedStreamSessionCoordinator.Snapshot snapshot() {
        return delivered;
    }

    @Override public void onSnapshot(RetainedStreamSessionCoordinator.Snapshot snapshot) {
        if (snapshot == delivered) return;
        delivered = snapshot;
        render.accept(ConsoleUpdateChannels.HOST_SELECTION
                | ConsoleUpdateChannels.SELECTED_HOST | ConsoleUpdateChannels.SESSION);
    }

    boolean reconnectRequired(String hostId) {
        return delivered.state == RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED
                && delivered.hostId.equalsIgnoreCase(hostId);
    }

    /** Warm-up status string for {@code hostId}, or 0 when the retained stream says nothing. */
    int warmUpStatus(String hostId, boolean neutralStreamTarget) {
        if (!delivered.hostId.equalsIgnoreCase(hostId)) return 0;
        if (delivered.state == RetainedStreamSessionCoordinator.State.PREPARING) {
            return R.string.console_warm_up_preparing;
        }
        boolean live = delivered.state == RetainedStreamSessionCoordinator.State.HOME_LIVE
                || delivered.state == RetainedStreamSessionCoordinator.State.PARKED_LIVE;
        return live && delivered.playniteGameId.isEmpty() && neutralStreamTarget
                ? R.string.console_warm_up_ready : 0;
    }
}
