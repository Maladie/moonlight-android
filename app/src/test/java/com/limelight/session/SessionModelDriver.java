package com.limelight.session;

import com.limelight.gateway.GatewayConnection;
import com.limelight.session.SessionModel.Controller;
import com.limelight.session.SessionModel.DirectTermination;
import com.limelight.session.SessionModel.ProviderTransferAuthorization;
import com.limelight.session.SessionModel.Snapshot;
import com.limelight.session.SessionModel.SwitchCallback;
import com.limelight.session.SessionModel.SwitchRequest;
import com.limelight.session.SessionModel.SwitchResult;
import com.limelight.session.SessionModel.TerminationCallback;
import com.limelight.session.SessionModel.TerminationResult;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;

/**
 * Deterministic test executor for {@link SessionModel}: the model thread and the main thread are
 * two manual queues. Each named step posts its event and drains both queues, so a test reads as
 * an event sequence with its replies, and readers see the state after every effect settled.
 */
public final class SessionModelDriver {
    public final ArrayDeque<Runnable> modelQueue = new ArrayDeque<>();
    public final ArrayDeque<Runnable> mainQueue = new ArrayDeque<>();
    public final MemoryPreferences resume;
    public final MemoryPreferences suspended;
    public final MemoryPreferences sleep;
    /** The model's monotonic clock (milliseconds); tests move it. */
    public long now;
    /** Receives the model's projection diagnostics; replaced by tests that read them. */
    public com.limelight.console.SessionStateResolver.DiagnosticSink diagnostics =
            (level, component, event, fields) -> true;
    public final SessionModel model;

    public SessionModelDriver() {
        this(new MemoryPreferences(), new MemoryPreferences(), new MemoryPreferences());
    }

    /** A model over existing stores, as a new process starts after the previous one died. */
    public SessionModelDriver(MemoryPreferences resume, MemoryPreferences suspended,
                              MemoryPreferences sleep) {
        this.resume = resume;
        this.suspended = suspended;
        this.sleep = sleep;
        this.model = new SessionModel(modelQueue::add, mainQueue::add, () -> false,
                () -> now, (level, component, event, fields) -> diagnostics.record(level,
                component, event, fields), resume, suspended, sleep);
    }

    /** Lets the model read stores a test wrote directly (any store write re-reads them). */
    public void refreshRecords() {
        post(new SessionEvent.HostSleepCleared(""));
    }

    /** A stored reconnect record as the model reads it back. */
    public static SessionResumeManager.PendingSession reconnectRecord(
            String streamSessionId, String hostId, String profileId, String executionProfileId,
            int appId, String gameId, boolean autoResume) {
        return reconnectRecord(streamSessionId, hostId, profileId, executionProfileId, appId,
                gameId, autoResume, false);
    }

    /** A stored reconnect record; {@code neutral}: of the neutral MoonWaker stream. */
    public static SessionResumeManager.PendingSession reconnectRecord(
            String streamSessionId, String hostId, String profileId, String executionProfileId,
            int appId, String gameId, boolean autoResume, boolean neutral) {
        java.util.Map<String, Object> stored = new java.util.HashMap<>();
        stored.put("neutralStreamTarget", neutral);
        stored.put("pending", true);
        stored.put("autoResume", autoResume);
        stored.put("streamSessionId", streamSessionId);
        stored.put("pcUuid", hostId);
        stored.put("profileId", profileId);
        stored.put(com.limelight.Game.EXTRA_EXECUTION_PROFILE_ID, executionProfileId);
        stored.put("appId", appId);
        stored.put("transitionGameId", gameId);
        return SessionResumeManager.read(stored);
    }

    /** Runs model events first, then main-thread work, until both queues are empty. */
    public int drain() {
        int ran = 0;
        while (!modelQueue.isEmpty() || !mainQueue.isEmpty()) {
            Runnable next = modelQueue.isEmpty() ? mainQueue.poll() : modelQueue.poll();
            next.run();
            ran++;
        }
        return ran;
    }

    public <R> R call(SessionEvent<R> event) {
        Object[] reply = {Unanswered.INSTANCE};
        model.dispatch(event, value -> reply[0] = value);
        drain();
        if (reply[0] == Unanswered.INSTANCE) throw new AssertionError("event not answered");
        @SuppressWarnings("unchecked") R result = (R) reply[0];
        return result;
    }

    public void post(SessionEvent<?> event) {
        model.post(event);
        drain();
    }

    private enum Unanswered { INSTANCE }

    // ---- readers (after all pending work settled) ---------------------------------------------

    public Snapshot snapshot() { drain(); return model.snapshot(); }
    public SessionModel.State state() { drain(); return model.state(); }
    public boolean hasAttachedPresentation() { drain(); return model.hasAttachedPresentation(); }
    public boolean hasRetainedSession() { drain(); return model.hasRetainedSession(); }
    public boolean canResumeInstantly() {
        drain();
        boolean result = model.canResumeInstantly();
        drain();
        return result;
    }
    public boolean canResumeInstantly(Snapshot expected) {
        drain();
        boolean result = model.canResumeInstantly(expected);
        drain();
        return result;
    }
    public boolean canSwitchGame(Snapshot expected) {
        drain();
        boolean result = model.canSwitchGame(expected);
        drain();
        return result;
    }
    public boolean canSwitchGame(String hostId, int appId) {
        return canSwitchGame(hostId, GatewayConnection.DEFAULT_PROFILE_ID, appId);
    }
    public boolean canSwitchGame(String hostId, String profileId, int appId) {
        drain();
        boolean result = model.canSwitchGame(hostId, profileId, appId);
        drain();
        return result;
    }
    public boolean canSwitchOrigin(String hostId, String origin, int appId) {
        drain();
        boolean result = model.canSwitchOrigin(hostId, origin, appId);
        drain();
        return result;
    }
    public boolean isPreparing(Controller owner, String sessionId, String hostId, int appId,
                               String transitionId, long attempt) {
        drain();
        return model.isPreparing(owner, sessionId, hostId, appId, transitionId, attempt);
    }
    public boolean isPreparingHomeFrameAccepted(String sessionId, String hostId, int appId,
                                                String gameId, String transitionId, long attempt) {
        drain();
        return model.isPreparingHomeFrameAccepted(sessionId, hostId, appId, gameId,
                transitionId, attempt);
    }
    public boolean isPreparingSwitchOwned(String sessionId, String hostId, int appId,
                                          String transitionId, long attempt) {
        drain();
        return model.isPreparingSwitchOwned(sessionId, hostId, appId, transitionId, attempt);
    }
    public boolean ownsLiveOrParkedSession(Controller owner, String sessionId, String hostId,
                                           int appId) {
        drain();
        return model.ownsLiveOrParkedSession(owner, sessionId, hostId, appId);
    }
    public boolean ownsLiveOrParkedSession(Controller owner, String sessionId, String hostId,
                                           int appId, String gameId) {
        drain();
        return model.ownsLiveOrParkedSession(owner, sessionId, hostId, appId, gameId);
    }
    public boolean ownsSwitchBeforeMutation(Controller owner, SwitchRequest request,
                                            long controlGeneration) {
        drain();
        return model.ownsSwitchBeforeMutation(owner, request, controlGeneration);
    }
    public boolean consumeProviderTransferAuthorization(ProviderTransferAuthorization proof) {
        drain();
        return model.consumeProviderTransferAuthorization(proof);
    }
    public Snapshot subscribe(SessionModel.Listener listener) {
        drain();
        return model.subscribe(listener);
    }
    public void unsubscribe(SessionModel.Listener listener) { model.unsubscribe(listener); }

    // ---- events --------------------------------------------------------------------------------

    public void enterHome(Controller owner, String sessionId, String hostId, int appId,
                          String gameId) {
        enterHome(owner, sessionId, hostId, GatewayConnection.DEFAULT_PROFILE_ID, appId, gameId);
    }
    public void enterHome(Controller owner, String sessionId, String hostId, String profileId,
                          int appId, String gameId) {
        call(new SessionEvent.EnterHome(owner, sessionId, hostId, profileId, appId, gameId));
    }
    public boolean beginPreparing(Controller owner, String sessionId, String hostId, int appId,
                                  String gameId, String transitionId, long attempt) {
        return beginPreparing(owner, sessionId, hostId, GatewayConnection.DEFAULT_PROFILE_ID,
                appId, gameId, transitionId, attempt);
    }
    public boolean beginPreparing(Controller owner, String sessionId, String hostId,
                                  String profileId, int appId, String gameId,
                                  String transitionId, long attempt) {
        return call(new SessionEvent.PreparingBegun(owner, sessionId, hostId, profileId, appId,
                gameId, transitionId, attempt));
    }
    public boolean completePreparing(Controller owner, String sessionId, String hostId,
                                     int appId, String transitionId, long attempt) {
        return call(new SessionEvent.PreparingCompleted(owner, sessionId, hostId, appId,
                transitionId, attempt));
    }
    public boolean cancelPreparing(Controller owner, String sessionId, String hostId,
                                   int appId, String transitionId, long attempt) {
        return call(new SessionEvent.PreparingCancelled(owner, sessionId, hostId, appId,
                transitionId, attempt));
    }
    public boolean cancelPreparing(Snapshot expected) {
        return call(new SessionEvent.CancelPreparingTransport(expected));
    }
    public boolean markPreparingReconnectRequired(Controller owner, String sessionId,
                                                  String hostId, int appId,
                                                  String transitionId, long attempt) {
        return call(new SessionEvent.PreparingTransportLost(owner, sessionId, hostId, appId,
                transitionId, attempt));
    }
    public boolean preparingHomeFrameSubmitted(Snapshot expected) {
        return call(new SessionEvent.HomeFrameSubmitted(expected));
    }
    public boolean bindControlGeneration(Controller owner, String sessionId, long generation) {
        return call(new SessionEvent.ControlGenerationBound(owner, sessionId, generation));
    }
    public boolean commitReauthorization(Controller owner, String sessionId, String origin,
                                         String actor, long expected, long generation,
                                         BooleanSupplier commitConnection) {
        return call(new SessionEvent.Reauthorize(owner, sessionId, origin, actor, expected,
                generation, commitConnection));
    }
    public ProviderTransferAuthorization commitProviderTransferReauthorization(
            Controller owner, String sessionId, String origin, String actor,
            String previousTransitionId, String replacementTransitionId, String hostId,
            int appId, String gameId, String processToken, int processId, long expected,
            long generation, long actorLeaseEpoch, BooleanSupplier commitConnection) {
        return call(new SessionEvent.ProviderReauthorize(owner, sessionId, origin, actor,
                previousTransitionId, replacementTransitionId, hostId, appId, gameId,
                processToken, processId, expected, generation, actorLeaseEpoch,
                commitConnection));
    }
    public boolean discardReauthorization(Controller owner, String sessionId,
                                          long previousGeneration, long generation) {
        return call(new SessionEvent.ReauthorizationDiscarded(owner, sessionId,
                previousGeneration, generation));
    }
    public boolean parkForBackground(String sessionId) {
        drain();
        return call(new SessionEvent.ParkRequested(sessionId,
                model.snapshot().presentationEpoch));
    }
    public void markParked(String sessionId) { call(new SessionEvent.Parked(sessionId)); }
    public void markReconnectRequired(String sessionId) {
        call(new SessionEvent.TransportLost(null, sessionId));
    }
    public boolean markTerminating(String sessionId, String hostId, int appId, String gameId) {
        return call(new SessionEvent.TerminatingMarked(sessionId, hostId, appId, gameId));
    }
    public DirectTermination beginDirectTermination(String sessionId, String hostId, int appId,
                                                    String gameId) {
        return beginDirectTermination(null, sessionId, hostId, appId, gameId);
    }
    public DirectTermination beginDirectTermination(Snapshot expected) {
        return call(new SessionEvent.DirectTerminationBegun(expected));
    }
    public DirectTermination beginDirectTermination(Controller owner, String sessionId,
                                                    String hostId, int appId, String gameId) {
        return call(new SessionEvent.DirectTerminationBegun(owner, sessionId, hostId, appId,
                gameId));
    }
    public boolean completeDirectTermination(DirectTermination token, boolean success) {
        return completeDirectTermination(token, success, false);
    }
    public boolean completeDirectTermination(DirectTermination token, boolean success,
                                             boolean gameLeft) {
        return call(SessionEvent.DirectTerminationCompleted.completed(token, success, gameLeft));
    }
    public void abandonDirectTermination(DirectTermination token) {
        call(SessionEvent.DirectTerminationCompleted.abandoned(token));
    }
    public boolean bindActor(Controller owner, String sessionId, String actor, String origin) {
        return call(new SessionEvent.ActorBound(owner, sessionId, actor, origin));
    }
    public SwitchResult switchGame(String hostId, int appId, String gameId, String gameName,
                                   String target, String artwork, BooleanSupplier cancelled,
                                   SwitchCallback completion) {
        return switchGame(hostId, GatewayConnection.DEFAULT_PROFILE_ID, appId, gameId,
                gameName, target, artwork, cancelled, completion);
    }
    public SwitchResult switchGame(String hostId, String profileId, int appId, String gameId,
                                   String gameName, String target, String artwork,
                                   BooleanSupplier cancelled, SwitchCallback completion) {
        return switchGame(hostId, profileId, profileId, false, appId, gameId, gameName,
                target, artwork, cancelled, completion);
    }
    public SwitchResult switchGame(String hostId, String executionProfileId,
                                   String targetProfileId, boolean targetIsChild, int appId,
                                   String gameId, String gameName, String target,
                                   String artwork, BooleanSupplier cancelled,
                                   SwitchCallback completion) {
        return switchGame(hostId, executionProfileId, targetProfileId, targetIsChild, appId,
                gameId, gameName, target, artwork, 0L, cancelled, completion);
    }
    public SwitchResult switchGame(String hostId, String executionProfileId,
                                   String targetProfileId, boolean targetIsChild, int appId,
                                   String gameId, String gameName, String target,
                                   String artwork, long orchestrationId,
                                   BooleanSupplier cancelled, SwitchCallback completion) {
        return call(new SessionEvent.SwitchRequested(hostId, executionProfileId,
                targetProfileId, targetIsChild, appId, gameId, gameName, target, artwork,
                orchestrationId, cancelled, completion));
    }
    public boolean updatePreparingSwitch(Controller owner, SwitchRequest request,
                                         String currentTransitionId, String expectedGameId,
                                         String newGameId, String newTransitionId) {
        return call(new SessionEvent.PreparingSwitchUpdated(owner, request, currentTransitionId,
                expectedGameId, newGameId, newTransitionId));
    }
    public boolean finishSwitch(String sessionId, Controller owner) {
        return call(new SessionEvent.SwitchFinished(owner, new SwitchRequest(sessionId, "",
                "", "", "", false, 0, "", "", "", "", "", "", 0L, false, 0L, null)));
    }
    public boolean finishSwitch(String sessionId, String transitionId, long attempt,
                                Controller owner) {
        return call(new SessionEvent.SwitchFinished(owner, new SwitchRequest(sessionId, "",
                "", "", "", false, 0, "", "", "", "", "", transitionId, attempt, true, 0L,
                null)));
    }
    public boolean updateOwnedSwitchGame(Controller owner, SwitchRequest request,
                                         String expectedOldGameId, String newGameId) {
        return call(new SessionEvent.OwnedSwitchGameUpdated(owner, request, expectedOldGameId,
                newGameId));
    }
    public boolean markTransportUnsafe(Controller owner, String sessionId, String hostId,
                                       int appId) {
        return call(new SessionEvent.TransportUnsafe(owner, sessionId, hostId, appId));
    }
    public boolean retainNeutralAfterGameStopped(Controller owner, String sessionId,
                                                 String hostId, int appId, String gameId) {
        return call(new SessionEvent.GameStoppedRetainNeutral(owner, sessionId, hostId, appId,
                gameId));
    }
    public TerminationResult terminate(String sessionId, TerminationCallback completion) {
        return call(new SessionEvent.TerminateRequested(sessionId, "", completion));
    }
    public boolean clearIfMatches(String sessionId) {
        return call(new SessionEvent.Cleared(sessionId));
    }
    public boolean clearAfterAuthoritativeEndIfMatches(String sessionId) {
        return call(SessionEvent.Cleared.byHost(sessionId));
    }
    public boolean closedIntentionally(Controller owner, String sessionId) {
        return call(new SessionEvent.ClosedIntentionally(owner, sessionId));
    }
    public void seedReconnectRecord(String sessionId) {
        resume.edit().putBoolean("pending", true).putString("streamSessionId", sessionId)
                .putString("pcUuid", "host").putString("profileId", "p").apply();
    }
    public boolean hasReconnectRecord() { drain(); return resume.getBoolean("pending", false); }
    public boolean hardResetIfHostMatches(String hostId) {
        return call(new SessionEvent.HardReset(hostId));
    }

    /** Ends whatever session the model holds, as its owner would. */
    public void clear() {
        Snapshot current = snapshot();
        if (current.state == SessionModel.State.PREPARING) {
            cancelPreparing(current.controller, current.streamSessionId, current.hostId,
                    current.appId, current.transitionId, current.attempt);
        } else if (!current.streamSessionId.isEmpty()) {
            clearIfMatches(current.streamSessionId);
        }
    }
}
