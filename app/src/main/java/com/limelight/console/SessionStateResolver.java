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

    static synchronized void resetProjectionOwnersForTests() {
        OWNERS.clear();
        diagnosticSink = MoonWakerDiagnostics::record;
    }

    /** Diagnostic writer; returns whether the record was queued. Replaced only in tests. */
    interface DiagnosticSink {
        boolean record(String level, String component, String event, Object... fields);
    }

    static DiagnosticSink diagnosticSink = MoonWakerDiagnostics::record;
    private static final java.util.regex.Pattern SAMPLE_MARKER =
            java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    /**
     * Records the currently published projection of every owner under an explicit boundary
     * marker, including unchanged state, so an external observer can sample phase boundaries.
     * Returns the number of owners whose sample was queued, or -1 for an invalid marker.
     */
    static synchronized int sampleAll(String marker, long nowMs) {
        if (marker == null || !SAMPLE_MARKER.matcher(marker).matches()) return -1;
        int queued = 0;
        for (ProjectionOwner owner : OWNERS.values()) {
            if (owner.sample(marker, nowMs)) queued++;
        }
        return queued;
    }

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
        private long lastResolvedAtMs = -1L;
        private String episodeKey = "";
        private long episodeDeadlineMs;
        private SessionSnapshot published;
        private HostGatewayClient.Evidence projectedGameEvidence;
        private HostGatewayClient.Evidence publishedGameEvidence;
        private long publishedAtMs;
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
            if (published.observationState == SessionSnapshot.ObservationState.READY
                    || published.observationState == SessionSnapshot.ObservationState.IDLE) {
                lastResolvedAtMs = nowMs;
            }
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

        /**
         * Records each distinct published projection; a record that could not be queued is
         * retried on the next publication. Decisions are unaffected.
         */
        private SessionSnapshot publish(SessionSnapshot snapshot, long nowMs) {
            publishedGameEvidence = projectedGameEvidence;
            publishedAtMs = nowMs;
            Object[] fields = projectionDiagnostic(snapshot, publishedGameEvidence,
                    remainingEpisodeMs(nowMs));
            StringBuilder signature = new StringBuilder();
            for (int index = 0; index + 1 < fields.length; index += 2) {
                if (!"episode_remaining_ms".equals(fields[index])) {
                    signature.append(fields[index + 1]).append('|');
                }
            }
            if (!signature.toString().equals(diagnosticSignature)
                    && diagnosticSink.record("INFO", "android.session", "projection.published",
                    fields)) {
                diagnosticSignature = signature.toString();
                diagnosticEvents++;
            }
            return snapshot;
        }

        private long remainingEpisodeMs(long nowMs) {
            return episodeDeadlineMs > 0L ? Math.max(0L, episodeDeadlineMs - nowMs) : -1L;
        }

        /** Boundary sample of the published projection (or its absence), even if unchanged. */
        synchronized boolean sample(String marker, long nowMs) {
            Object[] projection = published == null
                    ? new Object[] {"host_id", hostId, "profile_id", actorProfileId,
                            "execution_profile_id", executionProfileId,
                            "observation_state", "UNPUBLISHED"}
                    : projectionDiagnostic(published, publishedGameEvidence,
                            remainingEpisodeMs(nowMs));
            Object[] fields = java.util.Arrays.copyOf(projection, projection.length + 6);
            fields[projection.length] = "sample_marker";
            fields[projection.length + 1] = marker;
            fields[projection.length + 2] = "published_age_ms";
            fields[projection.length + 3] = published == null ? -1L : Math.max(0L, nowMs - publishedAtMs);
            fields[projection.length + 4] = "dropped";
            fields[projection.length + 5] = MoonWakerDiagnostics.droppedCount();
            return diagnosticSink.record("INFO", "android.session", "projection.sampled", fields);
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
            // A resolved (READY/IDLE) projection that merely goes stale between polls gets a
            // fresh bounded window; an unresolved episode never extends its own deadline.
            boolean expired = episodeDeadlineMs > 0L && nowMs >= Math.max(episodeDeadlineMs,
                    lastResolvedAtMs >= 0L ? lastResolvedAtMs + OBSERVATION_LIVENESS_MS : 0L);
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
