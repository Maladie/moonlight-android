package com.limelight.session;

import android.content.Intent;

import com.limelight.Game;
import com.limelight.session.SessionModel.Controller;
import com.limelight.session.SessionModel.DirectTermination;
import com.limelight.session.SessionModel.ProviderTransferAuthorization;
import com.limelight.session.SessionModel.Reply;
import com.limelight.session.SessionModel.Snapshot;
import com.limelight.session.SessionModel.SwitchCallback;
import com.limelight.session.SessionModel.SwitchOutcome;
import com.limelight.session.SessionModel.SwitchRequest;
import com.limelight.session.SessionModel.SwitchResult;
import com.limelight.session.SessionModel.TerminationCallback;
import com.limelight.session.SessionModel.TerminationResult;

import java.util.function.BooleanSupplier;

/**
 * The closed set of inputs to {@link SessionModel}: user intents, transport and presentation
 * reports, effect results and record changes. Constructors outside this package cannot extend
 * it. Each event runs once on the model thread; {@code R} is its reply type.
 */
public abstract class SessionEvent<R> {
    SessionEvent() { }

    abstract void apply(SessionModel model, Reply<R> reply);

    // ---- transport and presentation ------------------------------------------------------------

    public static final class EnterHome extends SessionEvent<Void> {
        private final Controller owner;
        private final String sessionId, hostId, profileId, gameId;
        private final int appId;

        public EnterHome(Controller owner, String sessionId, String hostId, String profileId,
                         int appId, String gameId) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.hostId = hostId;
            this.profileId = profileId;
            this.appId = appId;
            this.gameId = gameId;
        }

        @Override void apply(SessionModel m, Reply<Void> reply) {
            m.enterHome(owner, sessionId, hostId, profileId, appId, gameId);
            reply.accept(null);
        }
    }

    public static final class ControlGenerationBound extends SessionEvent<Boolean> {
        private final Controller owner;
        private final String sessionId;
        private final long generation;

        public ControlGenerationBound(Controller owner, String sessionId, long generation) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.generation = generation;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(m.bindControlGeneration(owner, sessionId, generation));
        }
    }

    /** The retained transport is gone; the session needs a reconnect. */
    public static final class TransportLost extends SessionEvent<Void> {
        private final String sessionId;

        public TransportLost(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override void apply(SessionModel m, Reply<Void> reply) {
            m.markReconnectRequired(sessionId);
            reply.accept(null);
        }
    }

    public static final class TransportUnsafe extends SessionEvent<Boolean> {
        private final Controller owner;
        private final String sessionId, hostId;
        private final int appId;

        public TransportUnsafe(Controller owner, String sessionId, String hostId, int appId) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.hostId = hostId;
            this.appId = appId;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(m.markTransportUnsafe(owner, sessionId, hostId, appId));
        }
    }

    public static final class Parked extends SessionEvent<Void> {
        private final String sessionId;

        public Parked(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override void apply(SessionModel m, Reply<Void> reply) {
            m.markParked(sessionId);
            reply.accept(null);
        }
    }

    /** Park for background: replies whether the transport was parked. */
    public static final class ParkRequested extends SessionEvent<Boolean> {
        private final String sessionId;

        public ParkRequested(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            m.parkForBackground(sessionId, reply);
        }
    }

    static final class ParkResult extends SessionEvent<Boolean> {
        private final long operation;
        private final String sessionId;
        private final boolean parked;

        ParkResult(long operation, String sessionId, boolean parked) {
            this.operation = operation;
            this.sessionId = sessionId;
            this.parked = parked;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            m.parkResult(operation, sessionId, parked, reply);
        }
    }

    /** The preparing Home drew its first frame; replies whether the owner accepted it. */
    public static final class HomeFrameSubmitted extends SessionEvent<Boolean> {
        private final Snapshot expected;

        public HomeFrameSubmitted(Snapshot expected) {
            this.expected = expected;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            m.preparingHomeFrameSubmitted(expected, reply);
        }
    }

    static final class HomeFrameResult extends SessionEvent<Boolean> {
        private final long claim;
        private final Controller owner;
        private final Snapshot expected;
        private final boolean accepted;

        HomeFrameResult(long claim, Controller owner, Snapshot expected, boolean accepted) {
            this.claim = claim;
            this.owner = owner;
            this.expected = expected;
            this.accepted = accepted;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            m.homeFrameResult(claim, owner, expected, accepted, reply);
        }
    }

    static final class ProbeTransport extends SessionEvent<Void> {
        @Override void apply(SessionModel m, Reply<Void> reply) {
            m.probeTransport();
            reply.accept(null);
        }
    }

    // ---- preparing (warm-up) -------------------------------------------------------------------

    /** Identity of one preparing attempt. */
    abstract static class PreparingEvent extends SessionEvent<Boolean> {
        final Controller owner;
        final String sessionId, hostId, transitionId;
        final int appId;
        final long attempt;

        PreparingEvent(Controller owner, String sessionId, String hostId, int appId,
                       String transitionId, long attempt) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.hostId = hostId;
            this.appId = appId;
            this.transitionId = transitionId;
            this.attempt = attempt;
        }
    }

    public static final class PreparingBegun extends PreparingEvent {
        private final String profileId, gameId;

        public PreparingBegun(Controller owner, String sessionId, String hostId,
                              String profileId, int appId, String gameId,
                              String transitionId, long attempt) {
            super(owner, sessionId, hostId, appId, transitionId, attempt);
            this.profileId = profileId;
            this.gameId = gameId;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(m.beginPreparing(owner, sessionId, hostId, profileId, appId, gameId,
                    transitionId, attempt));
        }
    }

    public static final class PreparingCompleted extends PreparingEvent {
        public PreparingCompleted(Controller owner, Snapshot preparing) {
            super(owner, preparing.streamSessionId, preparing.hostId, preparing.appId,
                    preparing.transitionId, preparing.attempt);
        }

        PreparingCompleted(Controller owner, String sessionId, String hostId, int appId,
                           String transitionId, long attempt) {
            super(owner, sessionId, hostId, appId, transitionId, attempt);
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(m.completePreparing(owner, sessionId, hostId, appId, transitionId,
                    attempt));
        }
    }

    public static final class PreparingCancelled extends PreparingEvent {
        public PreparingCancelled(Controller owner, Snapshot preparing) {
            super(owner, preparing.streamSessionId, preparing.hostId, preparing.appId,
                    preparing.transitionId, preparing.attempt);
        }

        PreparingCancelled(Controller owner, String sessionId, String hostId, int appId,
                           String transitionId, long attempt) {
            super(owner, sessionId, hostId, appId, transitionId, attempt);
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(m.cancelPreparing(owner, sessionId, hostId, appId, transitionId,
                    attempt));
        }
    }

    public static final class PreparingTransportLost extends PreparingEvent {
        public PreparingTransportLost(Controller owner, Snapshot preparing) {
            super(owner, preparing.streamSessionId, preparing.hostId, preparing.appId,
                    preparing.transitionId, preparing.attempt);
        }

        PreparingTransportLost(Controller owner, String sessionId, String hostId, int appId,
                               String transitionId, long attempt) {
            super(owner, sessionId, hostId, appId, transitionId, attempt);
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(m.markPreparingReconnectRequired(owner, sessionId, hostId, appId,
                    transitionId, attempt));
        }
    }

    /** Asks the transport owner to cancel exactly this preparing attempt. */
    public static final class CancelPreparingTransport extends SessionEvent<Boolean> {
        private final Snapshot expected;

        public CancelPreparingTransport(Snapshot expected) {
            this.expected = expected;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            m.cancelPreparingTransport(expected, reply);
        }
    }

    // ---- game switch ---------------------------------------------------------------------------

    public static final class SwitchRequested extends SessionEvent<SwitchResult> {
        private final String hostId, executionProfileId, targetProfileId;
        private final boolean targetIsChild;
        private final int appId;
        private final String newGameId, newGameName, streamTargetName, artworkPath;
        private final long orchestrationId;
        private final BooleanSupplier cancelled;
        private final SwitchCallback completion;

        public SwitchRequested(String hostId, String executionProfileId, String targetProfileId,
                               boolean targetIsChild, int appId, String newGameId,
                               String newGameName, String streamTargetName, String artworkPath,
                               long orchestrationId, BooleanSupplier cancelled,
                               SwitchCallback completion) {
            this.hostId = hostId;
            this.executionProfileId = executionProfileId;
            this.targetProfileId = targetProfileId;
            this.targetIsChild = targetIsChild;
            this.appId = appId;
            this.newGameId = newGameId;
            this.newGameName = newGameName;
            this.streamTargetName = streamTargetName;
            this.artworkPath = artworkPath;
            this.orchestrationId = orchestrationId;
            this.cancelled = cancelled;
            this.completion = completion;
        }

        @Override void apply(SessionModel m, Reply<SwitchResult> reply) {
            m.switchGame(hostId, executionProfileId, targetProfileId, targetIsChild, appId,
                    newGameId, newGameName, streamTargetName, artworkPath, orchestrationId,
                    cancelled, completion, reply);
        }
    }

    static final class SwitchCompleted extends SessionEvent<Void> {
        private final long operation;
        private final SwitchRequest request;
        private final SwitchOutcome outcome;
        private final String error;
        private final SwitchCallback completion;

        SwitchCompleted(long operation, SwitchRequest request, SwitchOutcome outcome,
                        String error, SwitchCallback completion) {
            this.operation = operation;
            this.request = request;
            this.outcome = outcome;
            this.error = error;
            this.completion = completion;
        }

        @Override void apply(SessionModel m, Reply<Void> reply) {
            m.switchCompleted(operation, request, outcome, error, completion);
            reply.accept(null);
        }
    }

    public static final class PreparingSwitchUpdated extends SessionEvent<Boolean> {
        private final Controller owner;
        private final SwitchRequest request;
        private final String currentTransitionId, expectedGameId, newGameId, newTransitionId;

        public PreparingSwitchUpdated(Controller owner, SwitchRequest request,
                                      String currentTransitionId, String expectedGameId,
                                      String newGameId, String newTransitionId) {
            this.owner = owner;
            this.request = request;
            this.currentTransitionId = currentTransitionId;
            this.expectedGameId = expectedGameId;
            this.newGameId = newGameId;
            this.newTransitionId = newTransitionId;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(m.updatePreparingSwitch(owner, request, currentTransitionId,
                    expectedGameId, newGameId, newTransitionId));
        }
    }

    public static final class OwnedSwitchGameUpdated extends SessionEvent<Boolean> {
        private final Controller owner;
        private final SwitchRequest request;
        private final String expectedOldGameId, newGameId;

        public OwnedSwitchGameUpdated(Controller owner, SwitchRequest request,
                                      String expectedOldGameId, String newGameId) {
            this.owner = owner;
            this.request = request;
            this.expectedOldGameId = expectedOldGameId;
            this.newGameId = newGameId;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(m.updateOwnedSwitchGame(owner, request, expectedOldGameId, newGameId));
        }
    }

    /** Releases the switch lock of {@code request}. */
    public static final class SwitchFinished extends SessionEvent<Boolean> {
        private final Controller owner;
        private final SwitchRequest request;

        public SwitchFinished(Controller owner, SwitchRequest request) {
            this.owner = owner;
            this.request = request;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(request.preparing
                    ? m.finishSwitch(request.streamSessionId, request.transitionId,
                    request.attempt, owner)
                    : m.finishSwitch(request.streamSessionId, owner));
        }
    }

    public static final class GameStoppedRetainNeutral extends SessionEvent<Boolean> {
        private final Controller owner;
        private final String sessionId, hostId, gameId;
        private final int appId;

        public GameStoppedRetainNeutral(Controller owner, String sessionId, String hostId,
                                        int appId, String gameId) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.hostId = hostId;
            this.appId = appId;
            this.gameId = gameId;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(m.retainNeutralAfterGameStopped(owner, sessionId, hostId, appId,
                    gameId));
        }
    }

    // ---- termination ---------------------------------------------------------------------------

    /** Whole-session quit of the retained transport; completion receives the final result. */
    public static final class TerminateRequested extends SessionEvent<TerminationResult> {
        private final String sessionId;
        private final TerminationCallback completion;

        public TerminateRequested(String sessionId, TerminationCallback completion) {
            this.sessionId = sessionId;
            this.completion = completion;
        }

        @Override void apply(SessionModel m, Reply<TerminationResult> reply) {
            m.terminate(sessionId, completion, reply);
        }
    }

    static final class TerminationFinished extends SessionEvent<Void> {
        private final Object operation;
        private final boolean success;

        TerminationFinished(Object operation, boolean success) {
            this.operation = operation;
            this.success = success;
        }

        @Override void apply(SessionModel m, Reply<Void> reply) {
            m.terminationFinished(operation, success);
            reply.accept(null);
        }
    }

    static final class TerminationUnsafe extends SessionEvent<Void> {
        private final Object operation;

        TerminationUnsafe(Object operation) {
            this.operation = operation;
        }

        @Override void apply(SessionModel m, Reply<Void> reply) {
            m.terminationUnsafe(operation);
            reply.accept(null);
        }
    }

    /** A terminating marker without a token (the presentation owns the stop). */
    public static final class TerminatingMarked extends SessionEvent<Boolean> {
        private final String sessionId, hostId, gameId;
        private final int appId;

        public TerminatingMarked(String sessionId, String hostId, int appId, String gameId) {
            this.sessionId = sessionId;
            this.hostId = hostId;
            this.appId = appId;
            this.gameId = gameId;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(m.markTerminating(sessionId, hostId, appId, gameId));
        }
    }

    public static final class DirectTerminationBegun extends SessionEvent<DirectTermination> {
        private final Controller owner;
        private final Snapshot expected;
        private final String sessionId, hostId, gameId;
        private final int appId;

        /** Owner-fenced: a preparing session terminates only for its own transport. */
        public DirectTerminationBegun(Controller owner, String sessionId, String hostId,
                                      int appId, String gameId) {
            this.owner = owner;
            this.expected = null;
            this.sessionId = sessionId;
            this.hostId = hostId;
            this.appId = appId;
            this.gameId = gameId;
        }

        /** Every captured local ownership fence must still be current. */
        public DirectTerminationBegun(Snapshot expected) {
            this.owner = null;
            this.expected = expected;
            this.sessionId = null;
            this.hostId = null;
            this.appId = 0;
            this.gameId = null;
        }

        @Override void apply(SessionModel m, Reply<DirectTermination> reply) {
            reply.accept(expected != null ? m.beginDirectTermination(expected)
                    : m.beginDirectTermination(owner, sessionId, hostId, appId, gameId));
        }
    }

    public static final class DirectTerminationCompleted extends SessionEvent<Boolean> {
        private final DirectTermination token;
        private final boolean success, transportUnsafe;

        private DirectTerminationCompleted(DirectTermination token, boolean success,
                                           boolean transportUnsafe) {
            this.token = token;
            this.success = success;
            this.transportUnsafe = transportUnsafe;
        }

        public static DirectTerminationCompleted completed(DirectTermination token,
                                                           boolean success) {
            return new DirectTerminationCompleted(token, success, false);
        }

        /** The local stop is uncertain: never restore a reusable live state. */
        public static DirectTerminationCompleted abandoned(DirectTermination token) {
            return new DirectTerminationCompleted(token, false, true);
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(m.completeDirectTermination(token, success, transportUnsafe));
        }
    }

    /** The session ended locally; clears it unless it is preparing. */
    public static final class Cleared extends SessionEvent<Boolean> {
        private final String sessionId;
        private final boolean authoritativeEnd;

        public Cleared(String sessionId) {
            this(sessionId, false);
        }

        private Cleared(String sessionId, boolean authoritativeEnd) {
            this.sessionId = sessionId;
            this.authoritativeEnd = authoritativeEnd;
        }

        /** The host reported the session over; a terminating session keeps its own result. */
        public static Cleared byHost(String sessionId) {
            return new Cleared(sessionId, true);
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(authoritativeEnd ? m.clearAfterAuthoritativeEndIfMatches(sessionId)
                    : m.clearIfMatches(sessionId));
        }
    }

    /**
     * After cancelling {@code preparing}, gives up the reconnect its owner marked for it (a
     * host change). Posted after the owner's marker, so it clears exactly that record.
     */
    public static final class ReconnectRelinquished extends SessionEvent<Boolean> {
        private final Snapshot preparing;

        public ReconnectRelinquished(Snapshot preparing) {
            this.preparing = preparing;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(m.relinquishReconnect(preparing));
        }
    }

    public static final class HardReset extends SessionEvent<Boolean> {
        private final String hostId;

        public HardReset(String hostId) {
            this.hostId = hostId;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(m.hardResetIfHostMatches(hostId));
        }
    }

    // ---- actor ---------------------------------------------------------------------------------

    public static final class ActorBound extends SessionEvent<Boolean> {
        private final Controller owner;
        private final String sessionId, actor, origin;

        public ActorBound(Controller owner, String sessionId, String actor, String origin) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.actor = actor;
            this.origin = origin;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(m.bindActor(owner, sessionId, actor, origin));
        }
    }

    /**
     * Commits a host-authorized actor/generation to the model and the transport together. The
     * model checks every binding, runs {@code commitConnection} as an effect on the main thread
     * under an operation id, and applies the actor and generation only on the matching result.
     */
    public static final class Reauthorize extends SessionEvent<Boolean> {
        final Controller owner;
        final String sessionId, origin, actor;
        final long expectedGeneration, generation;
        final BooleanSupplier commitConnection;

        public Reauthorize(Controller owner, String sessionId, String origin, String actor,
                           long expectedGeneration, long generation,
                           BooleanSupplier commitConnection) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.origin = origin;
            this.actor = actor;
            this.expectedGeneration = expectedGeneration;
            this.generation = generation;
            this.commitConnection = commitConnection;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            m.reauthorize(owner, sessionId, origin, actor, expectedGeneration, generation,
                    commitConnection, null, value -> reply.accept((Boolean) value));
        }
    }

    /** Reauthorization that replies one-shot provider ownership proof, or null. */
    public static final class ProviderReauthorize
            extends SessionEvent<ProviderTransferAuthorization> {
        private final Reauthorize commit;
        private final SessionModel.ProviderTransfer transfer;

        public ProviderReauthorize(Controller owner, String sessionId, String origin,
                                   String actor, String previousTransitionId,
                                   String replacementTransitionId, String hostId, int appId,
                                   String gameId, String processToken, int processId,
                                   long expectedGeneration, long generation,
                                   long actorLeaseEpoch, BooleanSupplier commitConnection) {
            this.commit = new Reauthorize(owner, sessionId, origin, actor, expectedGeneration,
                    generation, commitConnection);
            this.transfer = new SessionModel.ProviderTransfer(previousTransitionId,
                    replacementTransitionId, hostId, appId, gameId, processToken, processId,
                    actorLeaseEpoch);
        }

        @Override void apply(SessionModel m, Reply<ProviderTransferAuthorization> reply) {
            m.reauthorize(commit.owner, commit.sessionId, commit.origin, commit.actor,
                    commit.expectedGeneration, commit.generation, commit.commitConnection,
                    transfer, value -> reply.accept((ProviderTransferAuthorization) value));
        }
    }

    static final class ReauthorizationResult extends SessionEvent<Object> {
        private final long operation;
        private final Controller owner;
        private final String sessionId, origin, actor, previousActor;
        private final long expectedGeneration, generation;
        private final boolean committed;
        private final SessionModel.ProviderTransfer transfer;

        ReauthorizationResult(long operation, Controller owner, String sessionId, String origin,
                              String actor, String previousActor, long expectedGeneration,
                              long generation, boolean committed,
                              SessionModel.ProviderTransfer transfer) {
            this.operation = operation;
            this.owner = owner;
            this.sessionId = sessionId;
            this.origin = origin;
            this.actor = actor;
            this.previousActor = previousActor;
            this.expectedGeneration = expectedGeneration;
            this.generation = generation;
            this.committed = committed;
            this.transfer = transfer;
        }

        @Override void apply(SessionModel m, Reply<Object> reply) {
            m.reauthorizationResult(operation, owner, sessionId, origin, actor, previousActor,
                    expectedGeneration, generation, committed, transfer, reply);
        }
    }

    public static final class ReauthorizationDiscarded extends SessionEvent<Boolean> {
        private final Controller owner;
        private final String sessionId;
        private final long previousGeneration, generation;

        public ReauthorizationDiscarded(Controller owner, String sessionId,
                                        long previousGeneration, long generation) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.previousGeneration = previousGeneration;
            this.generation = generation;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(m.discardReauthorization(owner, sessionId, previousGeneration,
                    generation));
        }
    }

    // ---- persistent records --------------------------------------------------------------------

    /** Saves reconnect material; replies the stored record. */
    public static final class ReconnectSaved
            extends SessionEvent<SessionResumeManager.PendingSession> {
        private final Intent record;
        private final String sessionId;
        private final boolean autoResume;

        /** Stamps the caller's intent with the session id and stores a copy of it. */
        public ReconnectSaved(Intent gameIntent, String sessionId, boolean autoResume) {
            String id = SessionResumeManager.requireId(sessionId);
            gameIntent.putExtra(Game.EXTRA_STREAM_SESSION_ID, id);
            this.record = new Intent(gameIntent);
            this.sessionId = id;
            this.autoResume = autoResume;
        }

        @Override void apply(SessionModel m,
                             Reply<SessionResumeManager.PendingSession> reply) {
            reply.accept(m.saveReconnect(record, sessionId, autoResume));
        }
    }

    public static final class ReconnectCleared extends SessionEvent<Boolean> {
        private final String sessionId;

        public ReconnectCleared(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(m.clearReconnect(sessionId));
        }
    }

    public static final class SuspendedSaved extends SessionEvent<Void> {
        private final SuspendedSessionStore.Session session;

        public SuspendedSaved(SuspendedSessionStore.Session session) {
            this.session = session;
        }

        @Override void apply(SessionModel m, Reply<Void> reply) {
            SuspendedSessionStore.save(m.suspendedStore(), session);
            reply.accept(null);
        }
    }

    public static final class SuspendedResumed extends SessionEvent<Boolean> {
        private final String suspendId, hostId, executionProfileId, gameId, sessionId;
        private final int appId;

        public SuspendedResumed(String suspendId, String hostId, String executionProfileId,
                                int appId, String gameId, String sessionId) {
            this.suspendId = suspendId;
            this.hostId = hostId;
            this.executionProfileId = executionProfileId;
            this.appId = appId;
            this.gameId = gameId;
            this.sessionId = sessionId;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(SuspendedSessionStore.markResumedForExecutionIfMatches(
                    m.suspendedStore(), suspendId, hostId, executionProfileId, appId, gameId,
                    sessionId));
        }
    }

    public static final class SuspendedSleepObserved
            extends SessionEvent<SuspendedSessionStore.Session> {
        private final SuspendedSessionStore.Session expected;

        public SuspendedSleepObserved(SuspendedSessionStore.Session expected) {
            this.expected = expected;
        }

        @Override void apply(SessionModel m, Reply<SuspendedSessionStore.Session> reply) {
            reply.accept(SuspendedSessionStore.markSleepObservedIfMatches(
                    m.suspendedStore(), expected));
        }
    }

    /** The suspended host session ended; by actor profile or by execution profile. */
    public static final class SuspendedEnded extends SessionEvent<Boolean> {
        private final String hostId, profileId, suspendId;
        private final boolean byExecution;

        private SuspendedEnded(String hostId, String profileId, String suspendId,
                               boolean byExecution) {
            this.hostId = hostId;
            this.profileId = profileId;
            this.suspendId = suspendId;
            this.byExecution = byExecution;
        }

        public static SuspendedEnded forProfile(String hostId, String profileId,
                                                String suspendId) {
            return new SuspendedEnded(hostId, profileId, suspendId, false);
        }

        public static SuspendedEnded forExecution(String hostId, String executionProfileId,
                                                  String suspendId) {
            return new SuspendedEnded(hostId, executionProfileId, suspendId, true);
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(byExecution
                    ? SuspendedSessionStore.markSessionEndedForExecutionIfMatches(
                    m.suspendedStore(), hostId, profileId, suspendId)
                    : SuspendedSessionStore.markSessionEndedIfMatches(
                    m.suspendedStore(), hostId, profileId, suspendId));
        }
    }

    public static final class HostSelectionRequested extends SessionEvent<Void> {
        private final String hostId;

        public HostSelectionRequested(String hostId) {
            this.hostId = hostId;
        }

        @Override void apply(SessionModel m, Reply<Void> reply) {
            SuspendedSessionStore.requestHostSelection(m.suspendedStore(), hostId);
            reply.accept(null);
        }
    }

    /** Takes the pending host-selection request; replies its host or "". */
    public static final class HostSelectionConsumed extends SessionEvent<String> {
        @Override void apply(SessionModel m, Reply<String> reply) {
            reply.accept(SuspendedSessionStore.consumeHostSelectionRequest(m.suspendedStore()));
        }
    }

    /** MoonWaker asked the host to sleep at {@code requestedAt}, the record's identity. */
    public static final class HostSleepRequested extends SessionEvent<Void> {
        private final String hostId;
        private final long requestedAt;

        public HostSleepRequested(String hostId, long requestedAt) {
            this.hostId = hostId;
            this.requestedAt = requestedAt;
        }

        @Override void apply(SessionModel m, Reply<Void> reply) {
            HostSleepStateStore.request(m.sleepStore(), hostId, requestedAt);
            reply.accept(null);
        }
    }

    public static final class HostSleepObserved extends SessionEvent<HostSleepStateStore.State> {
        private final String hostId;
        private final HostSleepStateStore.State state;

        public HostSleepObserved(String hostId, HostSleepStateStore.State state) {
            this.hostId = hostId;
            this.state = state;
        }

        @Override void apply(SessionModel m, Reply<HostSleepStateStore.State> reply) {
            reply.accept(HostSleepStateStore.markObserved(m.sleepStore(), hostId, state));
        }
    }

    /** Clears the host sleep record; with a request time, only that exact request. */
    public static final class HostSleepCleared extends SessionEvent<Boolean> {
        private final String hostId;
        private final long requestedAt;

        public HostSleepCleared(String hostId) {
            this(hostId, 0L);
        }

        public HostSleepCleared(String hostId, long requestedAt) {
            this.hostId = hostId;
            this.requestedAt = requestedAt;
        }

        @Override void apply(SessionModel m, Reply<Boolean> reply) {
            reply.accept(HostSleepStateStore.clearIfMatches(m.sleepStore(), hostId,
                    requestedAt));
        }
    }
}
