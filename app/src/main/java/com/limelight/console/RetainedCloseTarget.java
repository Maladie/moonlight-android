package com.limelight.console;

import com.limelight.session.SessionModel;

/**
 * Host, actor profile and provider game a "close game and MoonWaker" runs against once the
 * coordinator has no live controller for it. The coordinator owns them while it holds the
 * session; after process death only the matching durable resume record does.
 */
final class RetainedCloseTarget {
    final String hostId;
    final String profileId;
    final String providerGameId;

    private RetainedCloseTarget(String hostId, String profileId, String gameId) {
        this.hostId = hostId;
        this.profileId = profileId;
        this.providerGameId = HostGatewayClient.isPlayniteId(gameId) ? gameId : "";
    }

    /** {@code record*} come from the resume record matching the session; null without one. */
    static RetainedCloseTarget of(SessionModel.Snapshot retained,
                                  String recordHostId, String recordProfileId,
                                  String recordGameId) {
        if (!retained.hostId.isEmpty()) {
            return new RetainedCloseTarget(retained.hostId, retained.profileId,
                    retained.playniteGameId);
        }
        if (recordHostId == null) return null;
        return new RetainedCloseTarget(recordHostId, recordProfileId, recordGameId);
    }
}
