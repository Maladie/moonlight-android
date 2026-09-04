package com.limelight.console;

import com.limelight.diagnostics.MoonWakerDiagnostics;
import com.limelight.nvstream.http.NvApp;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Validates and prepares the target selected by {@link SessionOrchestrator}. */
final class HostLaunchPreflight {
    private static final long GATEWAY_TIMEOUT_MS = 90_000L;
    private static final long SESSION_TIMEOUT_MS = 2 * 60_000L;
    private static final long PROFILE_TIMEOUT_MS = 90_000L;
    private static final long SESSION_POLL_MS = 1_000L;
    private static final long TARGET_TIMEOUT_MS = 5 * 60_000L;
    private static final long TARGET_POLL_MS = 1_000L;
    private static final long TARGET_STABLE_MS = 30_000L;
    private static final int TARGET_STABLE_POLLS = 5;

    enum Stage {
        NETWORK_READY,
        GATEWAY_READY,
        PROFILE_AUTHORIZED,
        INTERACTIVE_SESSION_READY,
        PROFILE_READY,
        PLAYNITE_READY,
        VIBEPOLLO_READY,
        TARGET_READY
    }

    enum Status { READY, FAILED, CANCELLED }

    enum FailureReason {
        NETWORK_UNAVAILABLE,
        GATEWAY_UNAVAILABLE,
        SELECTED_PROFILE_UNAVAILABLE,
        REMOTE_SIGN_IN_NOT_GRANTED,
        MANUAL_SIGN_IN_REQUIRED,
        CREDENTIAL_ACTION_REQUIRED,
        OTHER_PROFILE_ACTIVE,
        LOGIN_BROKER_UNAVAILABLE,
        WINDOWS_SIGN_IN_FAILED,
        WINDOWS_SIGN_IN_EXPIRED,
        WINDOWS_SIGN_IN_CANCELLED,
        WINDOWS_SIGN_IN_TIMEOUT,
        PLAYNITE_BRIDGE_OFFLINE,
        PLAYNITE_CONNECTOR_DISCONNECTED,
        VIBEPOLLO_UNAVAILABLE,
        TARGET_UNAVAILABLE,
        TARGET_PROPAGATION_TIMEOUT
    }

    enum Action { LAUNCH, RECONNECT, SWITCH_RETAINED, WARM_UP }

    enum TargetResolution { EXISTING, ENSURED }

    interface Progress {
        void onStage(Stage stage);
    }

    interface Network {
        boolean awaitReady(String hostId, Action action, BooleanSupplier cancelled);
    }

    interface Gateway {
        Profile profile(String hostId, String profileId) throws IOException;
        Session ensureSession(String hostId, String profileId, String requestId)
                throws IOException;
        Session sessionStatus(String hostId, String profileId, String requestId,
                              String attemptId) throws IOException;
        void cancelSession(String hostId, String profileId, String requestId,
                           String attemptId) throws IOException;
        EnsuredTarget ensureTarget(String hostId, String profileId,
                                   String gameId, String name)
                throws IOException;
    }

    interface Sunshine {
        List<NvApp> refreshApps(String hostId) throws IOException;
        default NvApp verifiedRetainedTarget(Request request) { return null; }
    }

    interface Clock {
        long now();
    }

    interface Waiter {
        boolean await(long millis, BooleanSupplier cancelled);
    }

    static final class Request {
        final String hostId;
        final HostProfileKey profileKey;
        final String profileId;
        final String requestId;
        final PlayIntent.Kind kind;
        final int appId;
        final String appName;
        final String gameId;
        final boolean requiresConnector;
        final boolean neutralStream;
        final Action action;

        private Request(PlayIntent intent, Action action, String requestId) {
            this.hostId = intent.hostId;
            this.profileKey = intent.profileKey;
            this.profileId = intent.profileId;
            this.requestId = requestId;
            this.kind = intent.kind;
            this.appId = intent.sunshineAppId;
            this.appName = intent.appName;
            this.gameId = intent.playniteGameId;
            this.requiresConnector = intent.requiresConnector;
            this.neutralStream = intent.neutralStream;
            this.action = action;
        }

        static Request from(PlayIntent intent, Action action) {
            return new Request(intent, action, "android-" + UUID.randomUUID());
        }

        static Request from(PlayIntent intent, Action action, long orchestrationId) {
            return new Request(intent, action, "android-"
                    + Long.toUnsignedString(orchestrationId) + "-" + UUID.randomUUID());
        }

        boolean requiresPlaynite() {
            return kind == PlayIntent.Kind.PLAYNITE_GAME;
        }

        boolean requiresPlayniteBridge() {
            return requiresPlaynite() || kind == PlayIntent.Kind.PLAYNITE_FULLSCREEN;
        }

        boolean requiresPlayniteConnector() {
            return requiresPlaynite() && requiresConnector;
        }

        boolean isDirectProvider() {
            return requiresPlaynite() && neutralStream;
        }
    }

    static final class Profile {
        final String name;
        final boolean selected;
        final boolean playniteBridgeOnline;
        final boolean playniteConnectorConnected;
        final boolean vibepolloBridgeOnline;

        Profile(boolean selected, boolean playniteBridgeOnline,
                boolean playniteConnectorConnected, boolean vibepolloBridgeOnline) {
            this("", selected, playniteBridgeOnline, playniteConnectorConnected,
                    vibepolloBridgeOnline);
        }

        Profile(String name, boolean selected, boolean playniteBridgeOnline,
                boolean playniteConnectorConnected, boolean vibepolloBridgeOnline) {
            this.name = name == null ? "" : name.trim();
            this.selected = selected;
            this.playniteBridgeOnline = playniteBridgeOnline;
            this.playniteConnectorConnected = playniteConnectorConnected;
            this.vibepolloBridgeOnline = vibepolloBridgeOnline;
        }
    }

    static final class Session {
        final String state;
        final String reason;
        final String attemptId;
        final int retryAfterMs;

        Session(String state, String reason, String attemptId, int retryAfterMs) {
            this.state = normalizeState(state, "failed");
            this.reason = normalizeState(reason, "manual_sign_in_required");
            this.attemptId = attemptId == null ? "" : attemptId.trim();
            this.retryAfterMs = Math.max(250, Math.min(3_000, retryAfterMs));
        }

        static Session ready() {
            return new Session("ready", "none", "", (int) SESSION_POLL_MS);
        }

        boolean isReady() {
            return "ready".equals(state);
        }

        boolean canPoll() {
            return !attemptId.isEmpty() && ("pending".equals(state)
                    || "credential_available".equals(state)
                    || "credential_issued".equals(state)
                    || "credential_acquired".equals(state)
                    || "credential_submitted".equals(state)
                    || "sign_in_requested".equals(state)
                    || "session_starting".equals(state));
        }

        private static String normalizeState(String value, String fallback) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            return normalized.isEmpty() ? fallback : normalized;
        }
    }

    static final class EnsuredTarget {
        final Integer appId;
        final String appUuid;

        EnsuredTarget(Integer appId, String appUuid) {
            this.appId = appId;
            this.appUuid = appUuid == null ? "" : appUuid.trim();
        }
    }

    static final class Failure {
        final Stage stage;
        final FailureReason reason;
        final String profileName;

        Failure(Stage stage, FailureReason reason) {
            this(stage, reason, "");
        }

        Failure(Stage stage, FailureReason reason, String profileName) {
            this.stage = stage;
            this.reason = reason;
            this.profileName = profileName == null ? "" : profileName.trim();
        }
    }

    static final class Result {
        final Status status;
        final NvApp target;
        final TargetResolution resolution;
        final Failure failure;

        private Result(Status status, NvApp target, TargetResolution resolution,
                       Failure failure) {
            this.status = status;
            this.target = target;
            this.resolution = resolution;
            this.failure = failure;
        }

        static Result ready(NvApp target, TargetResolution resolution) {
            return new Result(Status.READY, target, resolution, null);
        }

        static Result failed(Stage stage, FailureReason reason) {
            return new Result(Status.FAILED, null, null, new Failure(stage, reason));
        }

        static Result failed(Stage stage, FailureReason reason, String profileName) {
            return new Result(Status.FAILED, null, null,
                    new Failure(stage, reason, profileName));
        }

        static Result cancelled() {
            return new Result(Status.CANCELLED, null, null, null);
        }
    }

    private final Network network;
    private final Gateway gateway;
    private final Sunshine sunshine;
    private final Clock clock;
    private final Waiter waiter;

    HostLaunchPreflight(Network network, Gateway gateway, Sunshine sunshine,
                        Clock clock, Waiter waiter) {
        this.network = network;
        this.gateway = gateway;
        this.sunshine = sunshine;
        this.clock = clock;
        this.waiter = waiter;
    }

    Result run(Request request, BooleanSupplier cancelled, Progress progress) {
        long started = System.nanoTime();
        Stage[] currentStage = {null};
        MoonWakerDiagnostics.record("INFO", "android.host-preflight", "preflight.started",
                "host_id", request.hostId, "game_id", request.gameId,
                "profile_id", request.profileId,
                "request_id", request.requestId,
                "app_id", request.appId, "kind", request.kind.name(),
                "operation", request.action.name());
        Progress tracedProgress = stage -> {
            currentStage[0] = stage;
            MoonWakerDiagnostics.record("INFO", "android.host-preflight",
                    "preflight.stage", "host_id", request.hostId,
                    "profile_id", request.profileId,
                    "game_id", request.gameId, "app_id", request.appId,
                    "kind", request.kind.name(), "operation", request.action.name(),
                    "stage", stage.name());
            progress.onStage(stage);
        };
        try {
            Result result = runInternal(request, cancelled, tracedProgress);
            Stage terminalStage = result.failure != null ? result.failure.stage : currentStage[0];
            String reason = result.failure != null ? result.failure.reason.name()
                    : result.status.name();
            MoonWakerDiagnostics.record(
                    result.status == Status.FAILED ? "WARN" : "INFO",
                    "android.host-preflight", "preflight." +
                            result.status.name().toLowerCase(java.util.Locale.ROOT),
                    "host_id", request.hostId, "game_id", request.gameId,
                    "profile_id", request.profileId,
                    "request_id", request.requestId,
                    "app_id", request.appId, "kind", request.kind.name(),
                    "operation", request.action.name(), "status", result.status.name(),
                    "stage", terminalStage == null ? "" : terminalStage.name(),
                    "reason", reason, "duration_ms", Math.max(0L,
                            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                    System.nanoTime() - started)));
            return result;
        } catch (RuntimeException error) {
            recordException(request, currentStage[0], error);
            throw error;
        }
    }

    private Result runInternal(Request request, BooleanSupplier cancelled, Progress progress) {
        if (cancelled.getAsBoolean()) return Result.cancelled();
        progress.onStage(Stage.NETWORK_READY);
        if (!network.awaitReady(request.hostId, request.action, cancelled)) {
            return cancelled.getAsBoolean() ? Result.cancelled()
                    : Result.failed(Stage.NETWORK_READY,
                    FailureReason.NETWORK_UNAVAILABLE);
        }
        if (cancelled.getAsBoolean()) return Result.cancelled();

        progress.onStage(Stage.GATEWAY_READY);
        Profile profile = awaitGatewayProfile(request, cancelled);
        if (cancelled.getAsBoolean()) return Result.cancelled();
        if (profile == null) {
            return Result.failed(Stage.GATEWAY_READY,
                    FailureReason.GATEWAY_UNAVAILABLE);
        }
        progress.onStage(Stage.PROFILE_AUTHORIZED);
        if (!profile.selected) {
            return Result.failed(Stage.PROFILE_AUTHORIZED,
                    FailureReason.SELECTED_PROFILE_UNAVAILABLE);
        }

        progress.onStage(Stage.INTERACTIVE_SESSION_READY);
        Result sessionResult = awaitInteractiveSession(
                request, profile.name, cancelled);
        if (sessionResult != null) return sessionResult;

        progress.onStage(Stage.PROFILE_READY);
        if (request.requiresPlayniteBridge()) {
            progress.onStage(Stage.PLAYNITE_READY);
            profile = awaitPlayniteProfile(request, profile, cancelled);
            if (cancelled.getAsBoolean()) return Result.cancelled();
            if (profile == null) {
                return Result.failed(Stage.PLAYNITE_READY,
                        FailureReason.PLAYNITE_BRIDGE_OFFLINE);
            }
            if (!profile.selected) {
                return Result.failed(Stage.PROFILE_AUTHORIZED,
                        FailureReason.SELECTED_PROFILE_UNAVAILABLE);
            }
            if (!profile.playniteBridgeOnline) {
                return Result.failed(Stage.PLAYNITE_READY,
                        FailureReason.PLAYNITE_BRIDGE_OFFLINE);
            }
            if (request.requiresPlayniteConnector() && !profile.playniteConnectorConnected) {
                return Result.failed(Stage.PLAYNITE_READY,
                        FailureReason.PLAYNITE_CONNECTOR_DISCONNECTED);
            }
        }

        if (request.action == Action.SWITCH_RETAINED) {
            NvApp retainedTarget = sunshine.verifiedRetainedTarget(request);
            if (cancelled.getAsBoolean()) return Result.cancelled();
            if (retainedTarget != null) {
                MoonWakerDiagnostics.record("INFO", "android.host-preflight",
                        "preflight.retained_target_reused", "host_id", request.hostId,
                        "app_id", retainedTarget.getAppId(), "game_id", request.gameId);
                progress.onStage(Stage.TARGET_READY);
                return Result.ready(retainedTarget, TargetResolution.EXISTING);
            }
        }

        List<NvApp> apps;
        try {
            apps = sunshine.refreshApps(request.hostId);
        } catch (IOException | RuntimeException unavailable) {
            boolean wasCancelled = cancelled.getAsBoolean();
            if (!wasCancelled) recordException(request, Stage.TARGET_READY, unavailable);
            return wasCancelled ? Result.cancelled()
                    : Result.failed(Stage.TARGET_READY,
                    FailureReason.TARGET_UNAVAILABLE);
        }
        if (cancelled.getAsBoolean()) return Result.cancelled();
        if (request.action == Action.WARM_UP) {
            NvApp neutral = PlayniteTargetResolver.resolveNeutralStream(apps);
            if (neutral == null) {
                return Result.failed(Stage.TARGET_READY,
                        FailureReason.TARGET_UNAVAILABLE);
            }
            progress.onStage(Stage.TARGET_READY);
            return Result.ready(neutral, TargetResolution.EXISTING);
        }
        if (request.isDirectProvider()) {
            NvApp desktop = PlayniteTargetResolver.resolveProviderStream(apps);
            if (desktop != null) {
                progress.onStage(Stage.TARGET_READY);
                return Result.ready(desktop, TargetResolution.EXISTING);
            }
            return Result.failed(Stage.TARGET_READY, FailureReason.TARGET_UNAVAILABLE);
        }
        NvApp neutral = request.requiresPlaynite()
                ? PlayniteTargetResolver.resolveNeutralStream(apps) : null;
        if (neutral != null) {
            progress.onStage(Stage.TARGET_READY);
            return Result.ready(neutral, TargetResolution.EXISTING);
        }
        NvApp existing = request.appId > 0
                ? PlayniteTargetResolver.findById(apps, request.appId) : null;
        if (existing != null) {
            progress.onStage(Stage.TARGET_READY);
            return Result.ready(existing, TargetResolution.EXISTING);
        }
        if (!request.requiresPlaynite()) {
            return Result.failed(Stage.TARGET_READY, FailureReason.TARGET_UNAVAILABLE);
        }

        progress.onStage(Stage.VIBEPOLLO_READY);
        profile = awaitVibepolloProfile(request, profile, cancelled);
        if (cancelled.getAsBoolean()) return Result.cancelled();
        if (profile == null || !profile.vibepolloBridgeOnline) {
            return Result.failed(Stage.VIBEPOLLO_READY,
                    FailureReason.VIBEPOLLO_UNAVAILABLE);
        }
        EnsuredTarget ensured;
        try {
            ensured = gateway.ensureTarget(request.hostId, request.profileId,
                    request.gameId, request.appName);
        } catch (IOException | RuntimeException unavailable) {
            boolean wasCancelled = cancelled.getAsBoolean();
            if (!wasCancelled) recordException(request, Stage.VIBEPOLLO_READY, unavailable);
            return wasCancelled ? Result.cancelled()
                    : Result.failed(Stage.VIBEPOLLO_READY,
                    FailureReason.VIBEPOLLO_UNAVAILABLE);
        }
        if (cancelled.getAsBoolean()) return Result.cancelled();
        progress.onStage(Stage.TARGET_READY);
        return awaitEnsuredTarget(request, ensured, cancelled);
    }

    private static void recordException(Request request, Stage stage, Exception error) {
        MoonWakerDiagnostics.record("WARN", "android.host-preflight", "preflight.exception",
                "host_id", request.hostId, "game_id", request.gameId,
                "profile_id", request.profileId,
                "request_id", request.requestId,
                "app_id", request.appId, "kind", request.kind.name(),
                "operation", request.action.name(), "stage", stage == null ? "" : stage.name(),
                "error_type", error.getClass().getName());
    }

    private Profile awaitGatewayProfile(Request request, BooleanSupplier cancelled) {
        long deadline = clock.now() + GATEWAY_TIMEOUT_MS;
        Exception lastError = null;
        do {
            try {
                Profile profile = gateway.profile(request.hostId, request.profileId);
                if (profile != null) return profile;
                return null;
            } catch (IOException | RuntimeException unavailable) {
                if (cancelled.getAsBoolean()) return null;
                lastError = unavailable;
            }
        } while (clock.now() < deadline && waiter.await(SESSION_POLL_MS, cancelled));
        if (lastError != null && !cancelled.getAsBoolean()) {
            recordException(request, Stage.GATEWAY_READY, lastError);
        }
        return null;
    }

    /** Returns a terminal result, or null when the interactive session is ready. */
    private Result awaitInteractiveSession(Request request, String profileName,
                                           BooleanSupplier cancelled) {
        Session session;
        try {
            session = gateway.ensureSession(
                    request.hostId, request.profileId, request.requestId);
        } catch (IOException | RuntimeException unavailable) {
            if (!cancelled.getAsBoolean()) {
                recordException(request, Stage.INTERACTIVE_SESSION_READY, unavailable);
            }
            return cancelled.getAsBoolean() ? Result.cancelled()
                    : Result.failed(Stage.INTERACTIVE_SESSION_READY,
                    FailureReason.GATEWAY_UNAVAILABLE, profileName);
        }
        if (session == null) {
            return null;
        }
        if (session.isReady()) return null;
        if (!session.canPoll()) {
            FailureReason failure = sessionFailure(session);
            return allowsManualSignIn(failure) ? null
                    : Result.failed(Stage.INTERACTIVE_SESSION_READY, failure, profileName);
        }

        String attemptId = session.attemptId;
        long deadline = clock.now() + SESSION_TIMEOUT_MS;
        while (!cancelled.getAsBoolean() && clock.now() < deadline) {
            if (!waiter.await(session.retryAfterMs, cancelled)) break;
            try {
                session = gateway.sessionStatus(request.hostId, request.profileId,
                        request.requestId, attemptId);
            } catch (IOException | RuntimeException unavailable) {
                continue;
            }
            if (session == null) continue;
            if (session.isReady()) return null;
            if (!session.canPoll()) {
                FailureReason failure = sessionFailure(session);
                return allowsManualSignIn(failure) ? null
                        : Result.failed(Stage.INTERACTIVE_SESSION_READY, failure, profileName);
            }
        }
        cancelAttempt(request, attemptId);
        return cancelled.getAsBoolean() ? Result.cancelled()
                : null;
    }

    private Profile awaitPlayniteProfile(Request request, Profile initial,
                                         BooleanSupplier cancelled) {
        long deadline = clock.now() + PROFILE_TIMEOUT_MS;
        Profile last = initial;
        do {
            if (last == null || !last.selected || last.playniteBridgeOnline
                    && (!request.requiresPlayniteConnector()
                    || last.playniteConnectorConnected)) return last;
            try {
                last = gateway.profile(request.hostId, request.profileId);
            } catch (IOException | RuntimeException unavailable) {
                if (cancelled.getAsBoolean()) return last;
            }
        } while (clock.now() < deadline && waiter.await(SESSION_POLL_MS, cancelled));
        return last;
    }

    private Profile awaitVibepolloProfile(Request request, Profile initial,
                                          BooleanSupplier cancelled) {
        long deadline = clock.now() + PROFILE_TIMEOUT_MS;
        Profile last = initial;
        do {
            if (last == null || !last.selected || last.vibepolloBridgeOnline) return last;
            try {
                last = gateway.profile(request.hostId, request.profileId);
            } catch (IOException | RuntimeException unavailable) {
                if (cancelled.getAsBoolean()) return last;
            }
        } while (clock.now() < deadline && waiter.await(SESSION_POLL_MS, cancelled));
        return last;
    }

    private void cancelAttempt(Request request, String attemptId) {
        if (attemptId == null || attemptId.isEmpty()) return;
        try {
            gateway.cancelSession(request.hostId, request.profileId,
                    request.requestId, attemptId);
        } catch (IOException | RuntimeException ignored) {
            // Best effort: Broker expiry remains the authoritative fallback.
        }
    }

    private static FailureReason sessionFailure(Session session) {
        String reason = session.reason;
        if ("remote_sign_in_not_granted".equals(reason)
                || "profile_permission_denied".equals(reason)) {
            return FailureReason.REMOTE_SIGN_IN_NOT_GRANTED;
        }
        if ("other_profile_active".equals(reason)
                || "other_user_active".equals(reason)) {
            return FailureReason.OTHER_PROFILE_ACTIVE;
        }
        if ("broker_unavailable".equals(reason)) {
            return FailureReason.LOGIN_BROKER_UNAVAILABLE;
        }
        if ("attempt_expired".equals(reason) || "expired".equals(session.state)) {
            return FailureReason.WINDOWS_SIGN_IN_EXPIRED;
        }
        if ("attempt_cancelled".equals(reason) || "cancelled".equals(session.state)) {
            return FailureReason.WINDOWS_SIGN_IN_CANCELLED;
        }
        if ("remote_sign_in_disabled".equals(reason)
                || "manual_sign_in_required".equals(reason)
                || "unsupported".equals(session.state)) {
            return FailureReason.MANUAL_SIGN_IN_REQUIRED;
        }
        if ("action_required".equals(session.state)
                || reason.startsWith("credential_")
                || "account_mapping_required".equals(reason)
                || "provider_failed".equals(reason)) {
            return FailureReason.CREDENTIAL_ACTION_REQUIRED;
        }
        return FailureReason.WINDOWS_SIGN_IN_FAILED;
    }

    private static boolean allowsManualSignIn(FailureReason failure) {
        return failure != FailureReason.OTHER_PROFILE_ACTIVE;
    }

    private Result awaitEnsuredTarget(Request request, EnsuredTarget ensured,
                                      BooleanSupplier cancelled) {
        long deadline = clock.now() + TARGET_TIMEOUT_MS;
        Integer stableId = null;
        int stablePolls = 0;
        long stableSince = 0L;
        while (!cancelled.getAsBoolean() && clock.now() < deadline) {
            NvApp candidate = null;
            try {
                List<NvApp> apps = sunshine.refreshApps(request.hostId);
                if (!ensured.appUuid.isEmpty()) {
                    candidate = PlayniteTargetResolver.findByUuid(apps, ensured.appUuid);
                }
                if (candidate == null && ensured.appId != null) {
                    candidate = PlayniteTargetResolver.findById(apps, ensured.appId);
                }
                if (candidate == null && ensured.appUuid.isEmpty() && ensured.appId == null) {
                    candidate = PlayniteTargetResolver.findPlayableExactName(
                            apps, request.appName);
                }
            } catch (IOException | RuntimeException unavailable) {
                candidate = null;
            }
            if (cancelled.getAsBoolean()) return Result.cancelled();
            long now = clock.now();
            if (candidate == null) {
                stableId = null;
                stablePolls = 0;
                stableSince = 0L;
            } else if (!Integer.valueOf(candidate.getAppId()).equals(stableId)) {
                stableId = candidate.getAppId();
                stablePolls = 1;
                stableSince = now;
            } else {
                stablePolls++;
            }
            if (candidate != null && stablePolls >= TARGET_STABLE_POLLS
                    && now - stableSince >= TARGET_STABLE_MS) {
                return Result.ready(candidate, TargetResolution.ENSURED);
            }
            if (!waiter.await(TARGET_POLL_MS, cancelled)) {
                return Result.cancelled();
            }
        }
        return cancelled.getAsBoolean() ? Result.cancelled()
                : Result.failed(Stage.TARGET_READY,
                FailureReason.TARGET_PROPAGATION_TIMEOUT);
    }
}
