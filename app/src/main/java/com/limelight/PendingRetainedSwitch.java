package com.limelight;

import android.content.Intent;

/** A retained switch target selected while no presentation was attached to the transport. */
final class PendingRetainedSwitch {
    final String hostId;
    final String executionProfileId;
    final String targetProfileId;
    final boolean targetIsChild;
    final int appId;
    final String gameId;
    final String gameName;
    final String streamTarget;
    final String artwork;
    final long orchestrationId;

    PendingRetainedSwitch(String hostId, String executionProfileId, String targetProfileId,
                          boolean targetIsChild, int appId, String gameId, String gameName,
                          String streamTarget, String artwork, long orchestrationId) {
        this.hostId = hostId;
        this.executionProfileId = executionProfileId;
        this.targetProfileId = targetProfileId;
        this.targetIsChild = targetIsChild;
        this.appId = appId;
        this.gameId = gameId;
        this.gameName = gameName;
        this.streamTarget = streamTarget;
        this.artwork = artwork;
        this.orchestrationId = orchestrationId;
    }

    static PendingRetainedSwitch from(Intent intent) {
        String host = intent == null ? null
                : intent.getStringExtra(Game.EXTRA_PENDING_SWITCH_HOST_ID);
        if (host == null || host.trim().isEmpty()) return null;
        return new PendingRetainedSwitch(host,
                intent.getStringExtra(Game.EXTRA_PENDING_SWITCH_EXECUTION_PROFILE_ID),
                intent.getStringExtra(Game.EXTRA_PENDING_SWITCH_TARGET_PROFILE_ID),
                intent.getBooleanExtra(Game.EXTRA_PENDING_SWITCH_TARGET_CHILD, false),
                intent.getIntExtra(Game.EXTRA_PENDING_SWITCH_APP_ID, 0),
                intent.getStringExtra(Game.EXTRA_PENDING_SWITCH_GAME_ID),
                intent.getStringExtra(Game.EXTRA_PENDING_SWITCH_GAME_NAME),
                intent.getStringExtra(Game.EXTRA_PENDING_SWITCH_STREAM_TARGET),
                intent.getStringExtra(Game.EXTRA_PENDING_SWITCH_ARTWORK),
                intent.getLongExtra(Game.EXTRA_PENDING_SWITCH_ORCHESTRATION_ID, 0L));
    }

    /** The target belongs to this one presentation; it is never persisted into a resume record. */
    static void strip(Intent intent) {
        intent.removeExtra(Game.EXTRA_PENDING_SWITCH_HOST_ID);
        intent.removeExtra(Game.EXTRA_PENDING_SWITCH_EXECUTION_PROFILE_ID);
        intent.removeExtra(Game.EXTRA_PENDING_SWITCH_TARGET_PROFILE_ID);
        intent.removeExtra(Game.EXTRA_PENDING_SWITCH_TARGET_CHILD);
        intent.removeExtra(Game.EXTRA_PENDING_SWITCH_APP_ID);
        intent.removeExtra(Game.EXTRA_PENDING_SWITCH_GAME_ID);
        intent.removeExtra(Game.EXTRA_PENDING_SWITCH_GAME_NAME);
        intent.removeExtra(Game.EXTRA_PENDING_SWITCH_STREAM_TARGET);
        intent.removeExtra(Game.EXTRA_PENDING_SWITCH_ARTWORK);
        intent.removeExtra(Game.EXTRA_PENDING_SWITCH_ORCHESTRATION_ID);
    }

    /** Latest undispatched target wins; once submitted, later targets are rejected. */
    static final class Slot {
        private PendingRetainedSwitch pending;
        private boolean submitted;

        boolean offer(PendingRetainedSwitch next) {
            if (next == null || submitted) return false;
            pending = next;
            return true;
        }

        PendingRetainedSwitch take() {
            if (pending == null || submitted) return null;
            PendingRetainedSwitch next = pending;
            pending = null;
            submitted = true;
            return next;
        }

        void completed() {
            submitted = false;
        }

        boolean submitted() {
            return submitted;
        }

        /** A target waits to be submitted or a submitted switch has not completed yet. */
        boolean busy() {
            return pending != null || submitted;
        }
    }
}
