package com.limelight.console;

import com.limelight.diagnostics.MoonWakerDiagnostics;
import com.limelight.gateway.GatewayConnection;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.session.SessionModel;
import com.limelight.session.SessionResumeManager;
import com.limelight.session.SuspendedSessionStore;

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
    /**
     * After a local close, an unresolved host observation still reads as closing for at most
     * this long; then the ordinary uncertain path (refresh/confirm) applies again.
     */
    static final long CLOSING_CONFIRMATION_MS = 3 * OBSERVATION_LIVENESS_MS;
    /**
     * How long a status line keeps the running game and the stream duration of the last
     * observation that showed them when no later one does. Polls come every 5 s and a fresh
     * observation lasts 5 s, so the status would otherwise blink; three liveness windows span
     * several lost polls, and a host that stopped answering for that long is not described.
     * Display only: readiness, Play and close decisions never read it.
     */
    static final long STATUS_DISPLAY_MS = 3 * OBSERVATION_LIVENESS_MS;
    /** A longer stream or game duration is not shown. */
    static final long MAX_STATUS_DURATION_MS = 7L * 24L * 60L * 60_000L;
    private static final Map<String, ProjectionOwner> OWNERS = new LinkedHashMap<>();

    static synchronized ProjectionOwner owner(SessionModel model, String hostId,
                                              String actorProfileId, String executionProfileId) {
        String key = SessionSnapshot.normalize(hostId) + "|"
                + GatewayConnection.normalizeProfileId(actorProfileId) + "|"
                + GatewayConnection.normalizeProfileId(executionProfileId);
        ProjectionOwner owner = OWNERS.get(key);
        if (owner == null) {
            owner = new ProjectionOwner(model, hostId, actorProfileId, executionProfileId);
            OWNERS.put(key, owner);
        }
        return owner;
    }

    /** A forgotten host keeps no projection owner (nor its close or observation state). */
    static synchronized void forgetHost(String hostId) {
        String normalized = SessionSnapshot.normalize(hostId);
        OWNERS.values().removeIf(owner -> owner.hostId.equals(normalized));
    }

    /** An unreachable host runs nothing this client may still describe. */
    static synchronized void hostUnreachable(String hostId) {
        String normalized = SessionSnapshot.normalize(hostId);
        for (ProjectionOwner owner : OWNERS.values()) {
            if (owner.hostId.equals(normalized)) owner.forgetDisplay();
        }
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
    /**
     * After a boundary sample, confirms persistence: drains the diagnostic writer and records
     * the post-drain loss count under the same marker. A missing confirmation or a changed
     * count means the boundary evidence is unavailable. Must not run on the main thread.
     */
    static boolean confirmSamples(String marker) {
        if (marker == null || !SAMPLE_MARKER.matcher(marker).matches()) return false;
        long dropped = MoonWakerDiagnostics.drainAndCount();
        return diagnosticSink.record("INFO", "android.session", "projection.sample_drained",
                "sample_marker", marker, "status", dropped >= 0L ? "drained" : "not_drained",
                "dropped", dropped);
    }

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
                false, false, false, 0);

        final SuspendedSessionStore.Session suspended;
        final boolean recentlyEnded;
        final boolean pendingReconnect;
        final SessionResumeManager.PendingSession pending;
        final boolean hostSleepRequested;
        final boolean hostSleepObserved;
        final boolean neutralStreamTarget;
        final int hostRunningAppId;

        LocalFacts(SuspendedSessionStore.Session suspended, boolean recentlyEnded,
                   boolean pendingReconnect, SessionResumeManager.PendingSession pending,
                   boolean hostSleepRequested, boolean hostSleepObserved,
                   boolean neutralStreamTarget, int hostRunningAppId) {
            this.suspended = suspended;
            this.recentlyEnded = recentlyEnded;
            this.pendingReconnect = pendingReconnect;
            this.pending = pending;
            this.hostSleepRequested = hostSleepRequested;
            this.hostSleepObserved = hostSleepObserved;
            this.neutralStreamTarget = neutralStreamTarget;
            this.hostRunningAppId = hostRunningAppId;
        }

        /**
         * Local facts beside {@code host}'s latest poll. A pending reconnect record counts when
         * it auto-resumes or names the app that poll sees running for its execution profile.
         */
        static LocalFacts forHost(ComputerDetails host, String executionProfileId,
                                  SuspendedSessionStore.Session suspended,
                                  boolean recentlyEnded,
                                  SessionResumeManager.PendingSession pending,
                                  boolean hostSleepRequested, boolean hostSleepObserved,
                                  boolean neutralStreamTarget) {
            boolean pendingMatchesRunningSession = pending != null
                    && host.uuid.equalsIgnoreCase(pending.hostUuid)
                    && executionProfileId.equals(pending.executionProfileId)
                    && host.runningGameId != 0
                    && host.runningGameId == pending.appId;
            boolean pendingReconnect = pending != null
                    && (pending.autoResume || pendingMatchesRunningSession)
                    && PlayniteIdentityResolutionPolicy.allowsPendingReconnect(
                            "unknown", pending.playniteGameId);
            return new LocalFacts(suspended, recentlyEnded, pendingReconnect, pending,
                    hostSleepRequested, hostSleepObserved, neutralStreamTarget,
                    host.runningGameId);
        }
    }

    static final class ProjectionOwner {
        private final SessionModel model;
        private final String hostId;
        private final String actorProfileId;
        private final String executionProfileId;
        private final Set<String> retiredBoots = new LinkedHashSet<>();
        private long nextRequestOrdinal;
        private long lastAcceptedOrdinal;
        private long observedTerminationEpoch;
        /** A local close finished: arm the bounded closing window at the next projection. */
        private boolean closingConfirmationArmed;
        private long closingConfirmationUntilMs;
        /**
         * A host-API close or a game stop; 0 when none runs. It counts for at most
         * {@link #CLOSING_CONFIRMATION_MS} from its start, so a close whose task never finished
         * cannot hold the host closing.
         */
        private long localCloseToken;
        private long nextLocalCloseToken;
        private long localCloseStartedAtMs;
        private boolean localCloseEndsGame;
        private String producerBootId = "";
        private long producerRevision;
        private HostGatewayClient.SessionObservation observation;
        private long observationAcceptedAtMs;
        private long lastResolvedAtMs = -1L;
        private String episodeKey = "";
        private long episodeDeadlineMs;
        private SessionSnapshot published;
        private HostGatewayClient.Evidence publishedGameEvidence;
        private long publishedAtMs;
        private String diagnosticSignature = "";
        private long diagnosticEvents;
        /**
         * Status-line facts of {@link SessionSnapshot.Display}; this owner is their only
         * holder. The running game is the last accepted observation's verified running
         * process, kept until an observation refutes it (idle, another game, a game that is
         * not running), a termination boundary, an unreachable host or
         * {@link #STATUS_DISPLAY_MS} without a confirming observation.
         */
        private String displayGameId = "";
        private long displayGameStartedHostMs;
        private long displayGameSeenAtMs;
        private String displayStreamSessionId = "";
        private long displayStreamStartedHostMs;
        /** Host wall clock minus this client's clock at the last accepted observation. */
        private long hostClockOffsetMs;

        private ProjectionOwner(SessionModel model, String hostId, String actorProfileId,
                                String executionProfileId) {
            this.model = model;
            this.hostId = SessionSnapshot.normalize(hostId);
            this.actorProfileId = GatewayConnection.normalizeProfileId(actorProfileId);
            this.executionProfileId = GatewayConnection.normalizeProfileId(executionProfileId);
            this.observedTerminationEpoch = model.snapshot().terminationEpoch;
        }

        synchronized long nextRequestOrdinal() {
            // A boundary that passed before this dispatch must not reject this very request.
            retireObservationAtTerminationBoundary(model.snapshot());
            if (nextRequestOrdinal == Long.MAX_VALUE) nextRequestOrdinal = 0L;
            return ++nextRequestOrdinal;
        }

        /**
         * Whether a rejected observation may still carry evidence for its caller's own control
         * permit: same scope and a producer boot that is not retired. Only the dispatch and
         * revision ordering of the published projection rejected it.
         */
        synchronized boolean admitsPermitEvidence(HostGatewayClient.SessionObservation candidate) {
            return candidate != null
                    && actorProfileId.equals(candidate.actorProfileId)
                    && executionProfileId.equals(candidate.executionProfileId)
                    && !retiredBoots.contains(candidate.producerBootId);
        }

        /**
         * Whether a rejected observation lost only the dispatch ordering: same scope and a live
         * producer boot, but a newer request was dispatched or accepted meanwhile (the dashboard
         * polls the same owner). A fresh read is expected to be admitted.
         */
        synchronized boolean lostDispatchOrdering(HostGatewayClient.SessionObservation candidate) {
            return admitsPermitEvidence(candidate)
                    && (candidate.requestOrdinal != nextRequestOrdinal
                    || candidate.requestOrdinal <= lastAcceptedOrdinal);
        }

        /**
         * The projection published for this exact observation, or null when rejected. The
         * boundary check reads one immutable model snapshot, so the result is the one a
         * termination boundary published after that snapshot would retire on the next call.
         */
        synchronized SessionSnapshot accept(HostGatewayClient.SessionObservation candidate,
                                            SessionModel.Snapshot caller, long nowMs) {
            SessionModel.Snapshot retained = liveSnapshot(caller);
            retireObservationAtTerminationBoundary(retained);
            if (candidate == null || candidate.requestOrdinal != nextRequestOrdinal
                    || candidate.requestOrdinal <= lastAcceptedOrdinal
                    || !actorProfileId.equals(candidate.actorProfileId)
                    || !executionProfileId.equals(candidate.executionProfileId)
                    || retiredBoots.contains(candidate.producerBootId)) return null;
            if (candidate.producerBootId.equals(producerBootId)) {
                if (candidate.producerRevision <= producerRevision) return null;
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
            holdDisplay(candidate, nowMs);
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
            published = publish(project(retained, LocalFacts.NONE, nowMs, "", false), nowMs);
            if (published.observationState == SessionSnapshot.ObservationState.READY
                    || published.observationState == SessionSnapshot.ObservationState.IDLE) {
                lastResolvedAtMs = nowMs;
            }
            return published;
        }

        synchronized SessionSnapshot unavailable(
                long failedOrdinal, SessionModel.Snapshot caller, long nowMs, String reason) {
            SessionModel.Snapshot retained = liveSnapshot(caller);
            retireObservationAtTerminationBoundary(retained);
            if (failedOrdinal != nextRequestOrdinal || failedOrdinal <= lastAcceptedOrdinal) {
                return project(retained, LocalFacts.NONE, nowMs, "", false);
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
                    reason == null ? "observation_unavailable" : reason, false), nowMs);
            return published;
        }

        synchronized SessionSnapshot current(SessionModel.Snapshot retained, long nowMs) {
            return current(retained, LocalFacts.NONE, nowMs);
        }

        synchronized SessionSnapshot current(SessionModel.Snapshot caller, LocalFacts local,
                                             long nowMs) {
            SessionModel.Snapshot retained = liveSnapshot(caller);
            retireObservationAtTerminationBoundary(retained);
            published = publish(project(retained, local == null ? LocalFacts.NONE : local,
                    nowMs, "", false), nowMs);
            return published;
        }

        /**
         * Render-only projection of a delivered, possibly older, model snapshot. It
         * publishes nothing and changes no owner state; an observation the owner holds across
         * a termination boundary from that snapshot is not shown.
         */
        synchronized SessionSnapshot rendered(SessionModel.Snapshot retained,
                                              LocalFacts local, long nowMs) {
            return project(retained, local, nowMs, "",
                    retained.terminationEpoch != observedTerminationEpoch);
        }

        /**
         * A caller's snapshot may predate blocking I/O, so a publication always projects the
         * model's current immutable snapshot and never a state older than one already
         * published; a newer model at most fails an observation's correlation.
         */
        private SessionModel.Snapshot liveSnapshot(SessionModel.Snapshot retained) {
            return retained == null ? null : model.snapshot();
        }

        /**
         * A close the client runs through the host API (no retained transport owns it), or a
         * game stop ({@code endsGame}): until {@link #finishLocalClose} (or the bound passes) the
         * session reads as closing; a game stop beside a live matching stream reads as ending
         * the game instead and keeps the stream. Returns its token.
         */
        synchronized long beginLocalClose(long nowMs, boolean endsGame) {
            if (nextLocalCloseToken == Long.MAX_VALUE) nextLocalCloseToken = 0L;
            localCloseToken = ++nextLocalCloseToken;
            localCloseStartedAtMs = nowMs;
            localCloseEndsGame = endsGame;
            return localCloseToken;
        }

        /**
         * Ends the local close {@code token} began; a newer close keeps going. Any completed
         * close is a termination boundary: observations gathered before it no longer count, and
         * the session reads as closing until the host confirms it free (or the bound passes).
         */
        synchronized void finishLocalClose(long token, boolean closed) {
            if (token == 0L) return;
            if (token == localCloseToken) localCloseToken = 0L;
            if (!closed) return;
            retireObservation();
            closingConfirmationArmed = true;
            closingConfirmationUntilMs = 0L;
        }

        private void retireObservationAtTerminationBoundary(
                SessionModel.Snapshot retained) {
            if (retained == null || retained.terminationEpoch == observedTerminationEpoch) return;
            retireObservationAtTerminationBoundary(retained.terminationEpoch);
            closingConfirmationArmed = retained.state != SessionModel.State.TERMINATING;
            closingConfirmationUntilMs = 0L;
        }

        /**
         * A local session termination (for example the game stop this client verifies) is a
         * boundary for host observations: one gathered before it, or by a poll dispatched before
         * it finished, describes a game that is going or gone. The model's termination
         * epoch advances on entering and leaving TERMINATING even if no owner sampled the state in
         * between; on any change drop the observation and reject every request dispatched so
         * far, so only a later poll can publish READY or IDLE again.
         */
        private void retireObservationAtTerminationBoundary(long terminationEpoch) {
            if (terminationEpoch == observedTerminationEpoch) return;
            observedTerminationEpoch = terminationEpoch;
            retireObservation();
        }

        private void retireObservation() {
            lastAcceptedOrdinal = nextRequestOrdinal;
            observation = null;
            episodeKey = "";
            episodeDeadlineMs = 0L;
            lastResolvedAtMs = -1L;
            forgetDisplay();
        }

        private void holdDisplay(HostGatewayClient.SessionObservation seen, long nowMs) {
            hostClockOffsetMs = seen.observedAtUnixMs - nowMs;
            displayStreamSessionId = seen.streamSessionId;
            displayStreamStartedHostMs = seen.streamStartedUnixMs;
            String gameId = SessionSnapshot.normalize(seen.gameId);
            // A child (it acts on its parent's execution profile) is told a process start
            // only for the game of its own live time session; any other game running there
            // is the parent's activity and is not held for the child's status line.
            boolean own = actorProfileId.equals(executionProfileId)
                    || seen.processStartedUnixMs > 0L;
            boolean running = own && seen.gameEvidence == HostGatewayClient.Evidence.VERIFIED
                    && "running".equals(seen.gameState);
            if (running && seen.processEvidence == HostGatewayClient.Evidence.VERIFIED) {
                displayGameId = gameId;
                displayGameStartedHostMs = seen.processStartedUnixMs;
                displayGameSeenAtMs = nowMs;
            } else if (seen.gameEvidence == HostGatewayClient.Evidence.ABSENT
                    || (seen.gameEvidence == HostGatewayClient.Evidence.VERIFIED
                    && !(running && gameId.equals(displayGameId)))) {
                // Refuted. Pending or unavailable evidence says nothing and keeps the game.
                displayGameId = "";
                displayGameStartedHostMs = 0L;
            }
        }

        synchronized void forgetDisplay() {
            displayGameId = "";
            displayGameStartedHostMs = 0L;
            displayStreamSessionId = "";
            displayStreamStartedHostMs = 0L;
        }

        private SessionSnapshot.Display display(SessionModel.Snapshot retained,
                                                boolean matchingTransport, long nowMs) {
            boolean game = !displayGameId.isEmpty()
                    && nowMs - displayGameSeenAtMs <= STATUS_DISPLAY_MS;
            boolean stream = displayStreamStartedHostMs > 0L
                    && nowMs - observationAcceptedAtMs <= STATUS_DISPLAY_MS
                    && (!matchingTransport
                    || retained.streamSessionId.equals(displayStreamSessionId));
            if (!game && !stream) return SessionSnapshot.Display.NONE;
            long hostNowMs = nowMs + hostClockOffsetMs;
            return new SessionSnapshot.Display(game ? displayGameId : "",
                    game ? wholeMinutes(hostNowMs, displayGameStartedHostMs) : -1L,
                    stream ? wholeMinutes(hostNowMs, displayStreamStartedHostMs) : -1L);
        }

        /** -1 for an unknown start and for one a host clock set forward puts over the limit. */
        private static long wholeMinutes(long hostNowMs, long startedHostMs) {
            long elapsedMs = hostNowMs - startedHostMs;
            return startedHostMs <= 0L || elapsedMs > MAX_STATUS_DURATION_MS ? -1L
                    : Math.max(0L, elapsedMs) / 60_000L;
        }

        synchronized long diagnosticEventsForTests() { return diagnosticEvents; }

        /**
         * Records each distinct published projection; a record that could not be queued is
         * retried on the next publication. Decisions are unaffected.
         */
        private SessionSnapshot publish(SessionSnapshot snapshot, long nowMs) {
            if (closingConfirmationArmed) {
                closingConfirmationArmed = false;
                closingConfirmationUntilMs = nowMs + CLOSING_CONFIRMATION_MS;
            }
            if (snapshot.observationState == SessionSnapshot.ObservationState.READY
                    || snapshot.observationState == SessionSnapshot.ObservationState.IDLE) {
                closingConfirmationUntilMs = 0L;
            }
            publishedGameEvidence = snapshot.observationCorrelated
                    ? observation.gameEvidence : null;
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
            // Raw age: a negative value exposes a publication/sampling time-base mismatch.
            fields[projection.length + 3] = published == null ? -1L : nowMs - publishedAtMs;
            fields[projection.length + 4] = "dropped";
            fields[projection.length + 5] = MoonWakerDiagnostics.droppedCount();
            return diagnosticSink.record("INFO", "android.session", "projection.sampled", fields);
        }

        private SessionSnapshot project(SessionModel.Snapshot retained,
                                        LocalFacts local, long nowMs,
                                        String unavailableReason, boolean retired) {
            HostGatewayClient.SessionObservation observed = retired ? null : observation;
            SessionModel.State retainedState = retained == null
                    ? SessionModel.State.NONE : retained.state;
            boolean matchingTransport = retained != null
                    && hostId.equals(SessionSnapshot.normalize(retained.hostId))
                    && executionProfileId.equals(retained.executionProfileId);
            boolean knownLive = matchingTransport && (retainedState
                    == SessionModel.State.HOME_LIVE
                    || retainedState == SessionModel.State.PARKED_LIVE);
            boolean localTransport = matchingTransport && retainedState
                    != SessionModel.State.NONE
                    && retainedState != SessionModel.State.RECONNECT_REQUIRED;
            boolean freshObservation = observed != null
                    && nowMs - observationAcceptedAtMs
                    <= Math.min(OBSERVATION_LIVENESS_MS, observed.freshForMs);
            HostGatewayClient.SessionObservation current = freshObservation ? observed : null;
            boolean correlationMatches = current != null && (!matchingTransport
                    || (!retained.streamSessionId.isEmpty()
                    && retained.streamSessionId.equals(current.streamSessionId)
                    && retained.controlGeneration > 0L
                    && current.controlGeneration == retained.controlGeneration
                    && (retained.transitionId.isEmpty()
                    || retained.transitionId.equals(current.callerTransitionId))));
            HostGatewayClient.SessionObservation evidence = correlationMatches ? current : null;
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
            boolean expired = !retired && episodeDeadlineMs > 0L
                    && nowMs >= Math.max(episodeDeadlineMs,
                    lastResolvedAtMs >= 0L ? lastResolvedAtMs + OBSERVATION_LIVENESS_MS : 0L);
            SessionSnapshot.ObservationState disposition = ready
                    ? SessionSnapshot.ObservationState.READY
                    : idle ? SessionSnapshot.ObservationState.IDLE
                    : expired ? (matchingTransport && retainedState
                    != SessionModel.State.NONE
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
            boolean localClosing = localCloseToken != 0L
                    && nowMs - localCloseStartedAtMs < CLOSING_CONFIRMATION_MS;
            boolean endingGame = localClosing && localCloseEndsGame;
            SessionSnapshot.State state;
            if ((localClosing && !(endingGame && knownLive)) || (matchingTransport
                    && retainedState == SessionModel.State.TERMINATING)) {
                state = SessionSnapshot.State.TERMINATING;
            } else if (matchingTransport && retainedState
                    == SessionModel.State.PREPARING) {
                state = SessionSnapshot.State.PREPARING;
            } else if (knownLive) {
                state = SessionSnapshot.State.ACTIVE;
            } else if (local.recentlyEnded) {
                state = SessionSnapshot.State.NONE;
            } else if (suspended) {
                state = SessionSnapshot.State.SUSPENDED;
            } else if ((matchingTransport && retainedState
                    == SessionModel.State.RECONNECT_REQUIRED) || pending) {
                state = SessionSnapshot.State.RECONNECT_REQUIRED;
            } else if (idle || disposition == SessionSnapshot.ObservationState.UNAVAILABLE) {
                state = SessionSnapshot.State.NONE;
            } else {
                state = SessionSnapshot.State.UNCERTAIN;
            }
            // Until the host confirms the closed session free (or the bound passes), a closed
            // session still reads as closing instead of uncertain. Only publish() starts the
            // bound; a render-only projection reads it as if published now.
            long closingUntilMs = closingConfirmationArmed
                    ? nowMs + CLOSING_CONFIRMATION_MS : closingConfirmationUntilMs;
            if (!idle && !ready && state == SessionSnapshot.State.UNCERTAIN
                    && (retired || nowMs < closingUntilMs)) {
                state = SessionSnapshot.State.TERMINATING;
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
                    : current == null ? observed == null
                    ? "observation_pending" : "observation_stale" : current.reason;
            return new SessionSnapshot(hostId, actorProfileId, executionProfileId,
                    state, appId, gameId, knownLive, suspended,
                    suspended && local.suspended.sleepObservedAt > 0L,
                    local.hostSleepRequested, local.hostSleepObserved,
                    suspended ? local.suspended.suspendId : "", local.neutralStreamTarget,
                    disposition, correlationMatches,
                    observed == null ? "" : observed.producerBootId,
                    observed == null ? 0L : observed.producerRevision,
                    observed == null ? 0L : observed.requestOrdinal,
                    retained == null ? 0L : retained.transportGeneration,
                    observed == null ? 0L : observed.controlGeneration,
                    observed == null ? 0L : observed.actorLeaseEpoch,
                    observed == null ? "" : observed.operationId,
                    observed == null ? "" : observed.callerTransitionId,
                    retained == null ? "" : retained.streamSessionId,
                    evidence == null ? "UNAVAILABLE" : evidence.processEvidence.name(),
                    evidence == null ? 0 : evidence.processId,
                    evidence == null ? "" : evidence.processToken,
                    evidence == null ? "UNAVAILABLE" : evidence.windowEvidence.name(),
                    reason, local.hostRunningAppId, endingGame,
                    retired ? SessionSnapshot.Display.NONE
                            : display(retained, matchingTransport, nowMs));
        }
    }
}
