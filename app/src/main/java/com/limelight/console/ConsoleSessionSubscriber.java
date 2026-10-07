package com.limelight.console;

import com.limelight.R;
import com.limelight.session.SessionModel;

import java.util.function.IntConsumer;

/**
 * The retained-stream snapshot a Console screen renders: delivered by the session model while
 * the screen is started, then held (never re-read) until the next delivery.
 */
final class ConsoleSessionSubscriber implements SessionModel.Listener {
    private final IntConsumer render;
    /** Null until the first {@link #start()}; every reader treats that as nothing delivered. */
    private SessionModel.Snapshot delivered;

    /** {@code render} receives the console update channels a session change invalidates. */
    ConsoleSessionSubscriber(IntConsumer render) {
        this.render = render;
    }

    void start(SessionModel model) {
        SessionModel.Snapshot current = model.subscribe(this);
        if (delivered == null) delivered = current;
        else onSnapshot(current);
    }

    void stop(SessionModel model) {
        model.unsubscribe(this);
    }

    SessionModel.Snapshot snapshot() {
        return delivered;
    }

    @Override public void onSnapshot(SessionModel.Snapshot snapshot) {
        if (snapshot == delivered) return;
        delivered = snapshot;
        render.accept(ConsoleUpdateChannels.HOST_SELECTION
                | ConsoleUpdateChannels.SELECTED_HOST | ConsoleUpdateChannels.SESSION);
    }

    /**
     * Whether the delivered retained stream on {@code hostId} already runs {@code appId} or
     * Playnite game {@code gameId}. False before the first delivery.
     */
    boolean holdsTarget(String hostId, int appId, String gameId) {
        if (delivered == null || delivered.hostId.isEmpty()
                || !delivered.hostId.equalsIgnoreCase(hostId)) return false;
        String game = gameId == null ? "" : gameId.trim();
        return (appId > 0 && delivered.appId == appId) || (!delivered.playniteGameId.isEmpty()
                && delivered.playniteGameId.equalsIgnoreCase(game));
    }

    boolean reconnectRequired(String hostId) {
        return delivered != null
                && delivered.state == SessionModel.State.RECONNECT_REQUIRED
                && delivered.hostId.equalsIgnoreCase(hostId);
    }

    /** Warm-up status string for {@code hostId}, or 0 when the retained stream says nothing. */
    int warmUpStatus(String hostId, boolean neutralStreamTarget) {
        if (delivered == null || !delivered.hostId.equalsIgnoreCase(hostId)) return 0;
        if (delivered.state == SessionModel.State.PREPARING) {
            return R.string.console_warm_up_preparing;
        }
        boolean live = delivered.state == SessionModel.State.HOME_LIVE
                || delivered.state == SessionModel.State.PARKED_LIVE;
        return live && delivered.playniteGameId.isEmpty() && neutralStreamTarget
                ? R.string.console_warm_up_ready : 0;
    }
}
