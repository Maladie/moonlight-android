package com.limelight.session;

import com.limelight.gateway.ControlOperationClient;
import com.limelight.gateway.GatewayConnection;

/**
 * What the host's stream record of this TV and execution profile (the Gateway holds at most
 * one) says of the local stream of that host and execution profile.
 */
public final class HostStreamRecord {
    public enum Action {
        /** Nothing to change: the host holds this very stream, or nothing tells either way. */
        KEEP,
        /** The host holds this actor's stream and the client knows none: record it to resume. */
        ADOPT,
        /** The host confirms the local stream gone: closed there, or replaced by a newer one. */
        RETIRE
    }

    private HostStreamRecord() { }

    /**
     * Whether the session model's own session of {@code hostId} (a start, a reconnect, a live
     * or terminating stream) owns its records now, so a host record read meanwhile must not
     * adopt or retire them.
     */
    public static boolean sessionOwnsRecords(String hostId, SessionModel.Snapshot session) {
        return hostId != null && hostId.equalsIgnoreCase(session.hostId)
                && session.state != SessionModel.State.NONE
                && session.state != SessionModel.State.RECONNECT_REQUIRED;
    }

    /**
     * @param owned the host's record, or null when the host confirmed it holds none
     * @param actorProfileId this client's actor
     * @param localStreamSessionId the local stream (live, or a reconnect record) of this host
     *                             and execution profile; empty when there is none
     */
    public static Action decide(ControlOperationClient.Permit owned, String actorProfileId,
                                String localStreamSessionId) {
        String local = localStreamSessionId == null ? "" : localStreamSessionId.trim();
        if (owned == null) return local.isEmpty() ? Action.KEEP : Action.RETIRE;
        if (owned.streamSessionId.equals(local)) return Action.KEEP;
        if (!local.isEmpty()) return Action.RETIRE;
        // Another actor's stream on this TV (a child on its parent's profile, or the parent
        // beside a child) is not this actor's to resume. ponytail: a child's own stream is not
        // adopted either: its time session binding is not in the host record.
        return owned.childSessionId.isEmpty()
                && GatewayConnection.normalizeProfileId(actorProfileId)
                .equals(owned.actorProfileId) ? Action.ADOPT : Action.KEEP;
    }
}
