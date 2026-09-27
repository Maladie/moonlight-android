package com.limelight.console;

import com.limelight.diagnostics.MoonWakerDiagnostics;
import com.limelight.gateway.GatewayConnection;
import com.limelight.stream.RetainedStreamSessionCoordinator;
import com.limelight.utils.SessionResumeManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Single owner of the versioned host observation and its local session projection. */
final class SessionStateResolver {
    static final long OBSERVATION_LIVENESS_MS = 15_000L;
    private static final Map<String, ProjectionOwner> OWNERS = new LinkedHashMap<>();

    static synchronized ProjectionOwner owner(String hostId, String actorProfileId,
                                              String executionProfileId) {
        String key = SessionSnapshot.normalize(hostId) + "|"
                + GatewayConnection.normalizeProfileId(actorProfileId) + "|"
                + GatewayConnection.normalizeProfileId(executionProfileId);
        ProjectionOwner owner = OWNERS.get(key);
        if (owner == null) {
            owner = new ProjectionOwner(hostId, actorProfileId, executionProfileId);
            OWNERS.put(key, owner);
        }
        return owner;
    }

    static synchronized void resetProjectionOwnersForTests() { OWNERS.clear(); }

    /**
     * Allowlisted correlators of one published projection. The process token is reported
     * only as a short SHA-256 fingerprint; no payloads or credentials are included.
     */
    static Object[] projectionDiagnostic(SessionSnapshot snapshot,
                                         HostGatewayClient.Evidence gameEvidence,
                                         long episodeRemainingMs) {
        return new Object[] {
                "host_id", snapshot.hostId,
                "profile_id", snapshot.profileId,
                "execution_profile_id", snapshot.executionProfileId,
                "state", snapshot.state.name(),
                "observation_state", snapshot.observationState.name(),
                "correlation_matches", snapshot.observationCorrelated,
                "known_live", snapshot.retainedTransport,
                "game_id", snapshot.playniteGameId,
                "app_id", snapshot.hostGameAppId,
                "game_evidence", gameEvidence == null ? "UNAVAILABLE" : gameEvidence.name(),
                "process_evidence", snapshot.processEvidence,
                "process_id", snapshot.processId,
                "process_token_fp", fingerprint(snapshot.processToken),
                "window_evidence", snapshot.windowEvidence,
                "producer_boot_id", snapshot.producerBootId,
                "producer_revision", snapshot.producerRevision,
                "request_ordinal", snapshot.requestOrdinal,
                "transition_id", snapshot.transitionId,
                "operation_id", snapshot.operationId,
                "control_generation", snapshot.controlGeneration,
                "actor_lease_epoch", snapshot.actorLeaseEpoch,
                "transport_generation", snapshot.transportGeneration,
                "stream_session_id", snapshot.streamSessionId,
                "episode_remaining_ms", episodeRemainingMs,
                "reason", snapshot.observationReason
        };
    }

    private static String fingerprint(String token) {
        if (token == null || token.isEmpty()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 6; index++) hex.append(String.format("%02x", digest[index]));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return "";
        }
    }

    static final class LocalFacts {
        static final LocalFacts NONE = new LocalFacts(null, false, false, null,
                false, false, false);

        final SuspendedSessionStore.Session suspended;
        final boolean recentlyEnded;
        final boolean pendingReconnect;
        final SessionResumeManager.PendingSession pending;
        final boolean hostSleepRequested;
        final boolean hostSleepObserved;
        final boolean neutralStreamTarget;

        LocalFacts(SuspendedSessionStore.Session suspended, boolean recentlyEnded,
                   boolean pendingReconnect, SessionResumeManager.PendingSession pending,
                   boolean hostSleepRequested, boolean hostSleepObserved,
                   boolean neutralStreamTarget) {
            this.suspended = suspended;
            this.recentlyEnded = recentlyEnded;
            this.pendingReconnect = pendingReconnect;
            this.pending = pending;
            this.hostSleepRequested = hostSleepRequested;
            this.hostSleepObserved = hostSleepObserved;
            this.neutralStreamTarget = neutralStreamTarget;
        }
    }

    static final class ProjectionOwner {
        private final String hostId;
        private final String actorProfileId;
        private final String executionProfileId;
        private final Set<String> retiredBoots = new LinkedHashSet<>();
        private long nextRequestOrdinal;
        private long lastAcceptedOrdinal;
        private String producerBootId = "";
        private long producerRevision;
        private HostGatewayClient.SessionObservation observation;
        private long observationAcceptedAtMs;
        private String episodeKey = "";
        private long episodeDeadlineMs;
        private SessionSnapshot published;
        private HostGatewayClient.Evidence projectedGameEvidence;
        private String diagnosticSignature = "";
        private long diagnosticEvents;

        private ProjectionOwner(String hostId, String actorProfileId,
                                String executionProfileId) {
            this.hostId = SessionSnapshot.normalize(hostId);
            this.actorProfileId = GatewayConnection.normalizeProfileId(actorProfileId);
            this.executionProfileId = GatewayConnection.normalizeProfileId(executionProfileId);
        }

        synchronized long nextRequestOrdinal() {
            if (nextRequestOrdinal == Long.MAX_VALUE) nextRequestOrdinal = 0L;
            return ++nextRequestOrdinal;
        }

        synchronized boolean accept(HostGatewayClient.SessionObservation candidate,
                                    RetainedStreamSessionCoordinator.Snapshot retained,
                                    long nowMs) {
            if (candidate == null || candidate.requestOrdinal != nextRequestOrdinal
                    || candidate.requestOrdinal <= lastAcceptedOrdinal
                    || !actorProfileId.equals(candidate.actorProfileId)
                    || !executionProfileId.equals(candidate.executionProfileId)
                    || retiredBoots.contains(candidate.producerBootId)) return false;
            if (candidate.producerBootId.equals(producerBootId)) {
                if (candidate.producerRevision <= producerRevision) return false;
            } else if (!producerBootId.isEmpty()) {
                retiredBoots.add(producerBootId);
                while (retiredBoots.size() > 8) {
                    retiredBoots.remove(retiredBoots.iterator().next());
                }
            }
            producerBootId = candidate.producerBootId;
            producerRevision = candidate.producerRevision;
            lastAcceptedOrdinal = candidate.requestOrdinal;
            observation = candidate;
            observationAcceptedAtMs = nowMs;
            String nextEpisode = candidate.producerBootId + "|" + candidate.stateEpisode
                    + "|" + candidate.operationId + "|" + candidate.callerTransitionId
                    + "|" + candidate.providerState;
            if (!nextEpisode.equals(episodeKey)) {
                boolean continuingUnavailableEpisode = episodeDeadlineMs > 0L
                        && episodeKey.endsWith("|unavailable")
                        && published != null && published.observationState
                        == SessionSnapshot.ObservationState.PENDING;
                episodeKey = nextEpisode;
                if (!continuingUnavailableEpisode) {
                    episodeDeadlineMs = nowMs + OBSERVATION_LIVENESS_MS;
                }
            }
            published = publish(project(retained, LocalFacts.NONE, nowMs, ""), nowMs);
            return true;
        }

        synchronized SessionSnapshot unavailable(
                long failedOrdinal, RetainedStreamSessionCoordinator.Snapshot retained,
                long nowMs, String reason) {
            if (failedOrdinal != nextRequestOrdinal || failedOrdinal <= lastAcceptedOrdinal) {
                return project(retained, LocalFacts.NONE, nowMs, "");
            }
            String unavailableEpisode = producerBootId + "|unavailable";
            boolean resolvedEpisode = published != null
                    && (published.observationState == SessionSnapshot.ObservationState.READY
                    || published.observationState == SessionSnapshot.ObservationState.IDLE);
            if (episodeKey.isEmpty() || resolvedEpisode) {
                episodeKey = unavailableEpisode;
                episodeDeadlineMs = nowMs + OBSERVATION_LIVENESS_MS;
            }
            published = publish(project(retained, LocalFacts.NONE, nowMs,
                    reason == null ? "observation_unavailable" : reason), nowMs);
            return published;
        }

        synchronized SessionSnapshot current(
                RetainedStreamSessionCoordinator.Snapshot retained, long nowMs) {
            return current(retained, LocalFacts.NONE, nowMs);
        }

        synchronized SessionSnapshot current(
                RetainedStreamSessionCoordinator.Snapshot retained, LocalFacts local,
                long nowMs) {
            published = publish(project(retained, local == null ? LocalFacts.NONE : local,
                    nowMs, ""), nowMs);
            return published;
        }

        synchronized long diagnosticEventsForTests() { return diagnosticEvents; }

        /** Records each distinct published projection once; decisions are unaffected. */
        private SessionSnapshot publish(SessionSnapshot snapshot, long nowMs) {
            long remaining = episodeDeadlineMs > 0L ? Math.max(0L, episodeDeadlineMs - nowMs) : -1L;
            Object[] fields = projectionDiagnostic(snapshot, projectedGameEvidence, remaining);
            StringBuilder signature = new StringBuilder();
            for (int index = 0; index + 1 < fields.length; index += 2) {
                if (!"episode_remaining_ms".equals(fields[index])) {
                    signature.append(fields[index + 1]).append('|');
                }
            }
            if (!signature.toString().equals(diagnosticSignature)) {
                diagnosticSignature = signature.toString();
                diagnosticEvents++;
                MoonWakerDiagnostics.record("INFO", "android.session", "projection.published",
                        fields);
            }
            return snapshot;
        }

        private SessionSnapshot project(RetainedStreamSessionCoordinator.Snapshot retained,
                                        LocalFacts local, long nowMs,
                                        String unavailableReason) {
            RetainedStreamSessionCoordinator.State retainedState = retained == null
                    ? RetainedStreamSessionCoordinator.State.NONE : retained.state;
            boolean matchingTransport = retained != null
                    && hostId.equals(SessionSnapshot.normalize(retained.hostId))
                    && executionProfileId.equals(retained.executionProfileId);
            boolean knownLive = matchingTransport && (retainedState
                    == RetainedStreamSessionCoordinator.State.HOME_LIVE
                    || retainedState == RetainedStreamSessionCoordinator.State.PARKED_LIVE);
            boolean localTransport = matchingTransport && retainedState
                    != RetainedStreamSessionCoordinator.State.NONE
                    && retainedState != RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED;
            boolean freshObservation = observation != null
                    && nowMs - observationAcceptedAtMs
                    <= Math.min(OBSERVATION_LIVENESS_MS, observation.freshForMs);
            HostGatewayClient.SessionObservation current = freshObservation ? observation : null;
            boolean correlationMatches = current != null && (!matchingTransport
                    || (!retained.streamSessionId.isEmpty()
                    && retained.streamSessionId.equals(current.streamSessionId)
                    && retained.controlGeneration > 0L
                    && current.controlGeneration == retained.controlGeneration
                    && (retained.transitionId.isEmpty()
                    || retained.transitionId.equals(current.callerTransitionId))));
            HostGatewayClient.SessionObservation evidence = correlationMatches ? current : null;
            projectedGameEvidence = evidence == null ? null : evidence.gameEvidence;
            boolean ready = unavailableReason.isEmpty() && evidence != null
                    && evidence.gameEvidence == HostGatewayClient.Evidence.VERIFIED
                    && evidence.processEvidence == HostGatewayClient.Evidence.VERIFIED
                    && evidence.windowEvidence == HostGatewayClient.Evidence.VERIFIED
                    && evidence.windowReady && (!matchingTransport || localTransport);
            boolean idle = unavailableReason.isEmpty() && evidence != null
                    && evidence.gameEvidence == HostGatewayClient.Evidence.ABSENT
                    && "idle".equals(evidence.gameState)
                    && (!matchingTransport || knownLive);
            boolean expired = episodeDeadlineMs > 0L && nowMs >= episodeDeadlineMs;
            SessionSnapshot.ObservationState disposition = ready
                    ? SessionSnapshot.ObservationState.READY
                    : idle ? SessionSnapshot.ObservationState.IDLE
                    : expired ? (matchingTransport && retainedState
                    != RetainedStreamSessionCoordinator.State.NONE
                    ? SessionSnapshot.ObservationState.RECOVERY_REQUIRED
                    : SessionSnapshot.ObservationState.UNAVAILABLE)
                    : SessionSnapshot.ObservationState.PENDING;

            boolean suspended = local.suspended != null
                    && hostId.equals(SessionSnapshot.normalize(local.suspended.hostId))
                    && executionProfileId.equals(local.suspended.executionProfileId)
                    && local.suspended.resumedAt == 0L;
            boolean pending = local.pendingReconnect && local.pending != null
                    && hostId.equals(SessionSnapshot.normalize(local.pending.hostUuid))
                    && executionProfileId.equals(local.pending.executionProfileId);
            SessionSnapshot.State state;
            if (matchingTransport && retainedState
                    == RetainedStreamSessionCoordinator.State.TERMINATING) {
                state = SessionSnapshot.State.TERMINATING;
            } else if (matchingTransport && retainedState
                    == RetainedStreamSessionCoordinator.State.PREPARING) {
                state = SessionSnapshot.State.PREPARING;
            } else if (knownLive) {
                state = SessionSnapshot.State.ACTIVE;
            } else if (local.recentlyEnded) {
                state = SessionSnapshot.State.NONE;
            } else if (suspended) {
                state = SessionSnapshot.State.SUSPENDED;
            } else if ((matchingTransport && retainedState
                    == RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED) || pending) {
                state = SessionSnapshot.State.RECONNECT_REQUIRED;
            } else if (idle || disposition == SessionSnapshot.ObservationState.UNAVAILABLE) {
                state = SessionSnapshot.State.NONE;
            } else {
                state = SessionSnapshot.State.UNCERTAIN;
            }

            String gameId = evidence != null
                    && evidence.gameEvidence == HostGatewayClient.Evidence.VERIFIED
                    ? evidence.gameId : knownLive && !retained.playniteGameId.isEmpty()
                    ? retained.playniteGameId : "";
            int appId = matchingTransport ? retained.appId
                    : suspended ? local.suspended.sunshineAppId
                    : pending ? local.pending.appId : 0;
            String reason = !unavailableReason.isEmpty() ? unavailableReason
                    : current != null && !correlationMatches
                    ? "observation_correlation_mismatch"
                    : current == null ? observation == null
                    ? "observation_pending" : "observation_stale" : current.reason;
            return new SessionSnapshot(hostId, actorProfileId, executionProfileId,
                    state, appId, gameId, knownLive, suspended,
                    suspended && local.suspended.sleepObservedAt > 0L,
                    local.hostSleepRequested, local.hostSleepObserved,
                    suspended ? local.suspended.suspendId : "", local.neutralStreamTarget,
                    disposition, correlationMatches,
                    observation == null ? "" : observation.producerBootId,
                    observation == null ? 0L : observation.producerRevision,
                    observation == null ? 0L : observation.requestOrdinal,
                    retained == null ? 0L : retained.transportGeneration,
                    observation == null ? 0L : observation.controlGeneration,
                    observation == null ? 0L : observation.actorLeaseEpoch,
                    observation == null ? "" : observation.operationId,
                    observation == null ? "" : observation.callerTransitionId,
                    retained == null ? "" : retained.streamSessionId,
                    evidence == null ? "UNAVAILABLE" : evidence.processEvidence.name(),
                    evidence == null ? 0 : evidence.processId,
                    evidence == null ? "" : evidence.processToken,
                    evidence == null ? "UNAVAILABLE" : evidence.windowEvidence.name(),
                    reason);
        }
    }
}
