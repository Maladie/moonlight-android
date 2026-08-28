package com.limelight.console;

import com.limelight.nvstream.http.NvApp;

import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Validates and prepares the target selected by {@link SessionOrchestrator}. */
final class HostLaunchPreflight {
    private static final long TARGET_TIMEOUT_MS = 5 * 60_000L;
    private static final long TARGET_POLL_MS = 1_000L;
    private static final long TARGET_STABLE_MS = 30_000L;
    private static final int TARGET_STABLE_POLLS = 5;

    enum Stage {
        NETWORK_READY,
        GATEWAY_READY,
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
        PLAYNITE_BRIDGE_OFFLINE,
        PLAYNITE_CONNECTOR_DISCONNECTED,
        VIBEPOLLO_UNAVAILABLE,
        TARGET_UNAVAILABLE,
        TARGET_PROPAGATION_TIMEOUT
    }

    enum Action { LAUNCH, RECONNECT }

    enum TargetResolution { EXISTING, ENSURED }

    interface Progress {
        void onStage(Stage stage);
    }

    interface Network {
        boolean awaitReady(String hostId, BooleanSupplier cancelled);
    }

    interface Gateway {
        Profile selectedProfile(String hostId) throws IOException;
        EnsuredTarget ensureTarget(String hostId, String gameId, String name)
                throws IOException;
    }

    interface Sunshine {
        List<NvApp> refreshApps(String hostId) throws IOException;
    }

    interface Clock {
        long now();
    }

    interface Waiter {
        boolean await(long millis, BooleanSupplier cancelled);
    }

    static final class Request {
        final String hostId;
        final PlayIntent.Kind kind;
        final int appId;
        final String appName;
        final String gameId;
        final Action action;

        private Request(PlayIntent intent, Action action) {
            this.hostId = intent.hostId;
            this.kind = intent.kind;
            this.appId = intent.sunshineAppId;
            this.appName = intent.appName;
            this.gameId = intent.playniteGameId;
            this.action = action;
        }

        static Request from(PlayIntent intent, Action action) {
            return new Request(intent, action);
        }

        boolean requiresPlaynite() {
            return kind == PlayIntent.Kind.PLAYNITE_GAME;
        }

        boolean requiresPlayniteConnector() {
            if (!requiresPlaynite()) return false;
            String normalized = gameId == null ? ""
                    : gameId.toLowerCase(java.util.Locale.ROOT);
            return !normalized.startsWith("steam:") && !normalized.startsWith("epic:");
        }

        boolean isDirectProvider() {
            String normalized = gameId == null ? ""
                    : gameId.toLowerCase(java.util.Locale.ROOT);
            return normalized.startsWith("steam:") || normalized.startsWith("epic:");
        }
    }

    static final class Profile {
        final boolean selected;
        final boolean playniteBridgeOnline;
        final boolean playniteConnectorConnected;
        final boolean vibepolloBridgeOnline;

        Profile(boolean selected, boolean playniteBridgeOnline,
                boolean playniteConnectorConnected, boolean vibepolloBridgeOnline) {
            this.selected = selected;
            this.playniteBridgeOnline = playniteBridgeOnline;
            this.playniteConnectorConnected = playniteConnectorConnected;
            this.vibepolloBridgeOnline = vibepolloBridgeOnline;
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

        Failure(Stage stage, FailureReason reason) {
            this.stage = stage;
            this.reason = reason;
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
        if (cancelled.getAsBoolean()) return Result.cancelled();
        progress.onStage(Stage.NETWORK_READY);
        if (!network.awaitReady(request.hostId, cancelled)) {
            return cancelled.getAsBoolean() ? Result.cancelled()
                    : Result.failed(Stage.NETWORK_READY,
                    FailureReason.NETWORK_UNAVAILABLE);
        }
        if (cancelled.getAsBoolean()) return Result.cancelled();

        Profile profile = null;
        if (request.requiresPlaynite()) {
            progress.onStage(Stage.GATEWAY_READY);
            try {
                profile = gateway.selectedProfile(request.hostId);
            } catch (IOException | RuntimeException unavailable) {
                return cancelled.getAsBoolean() ? Result.cancelled()
                        : Result.failed(Stage.GATEWAY_READY,
                        FailureReason.GATEWAY_UNAVAILABLE);
            }
            if (cancelled.getAsBoolean()) return Result.cancelled();
            if (profile == null) {
                return Result.failed(Stage.GATEWAY_READY,
                        FailureReason.GATEWAY_UNAVAILABLE);
            }
            progress.onStage(Stage.PROFILE_READY);
            if (!profile.selected) {
                return Result.failed(Stage.PROFILE_READY,
                        FailureReason.SELECTED_PROFILE_UNAVAILABLE);
            }
            progress.onStage(Stage.PLAYNITE_READY);
            if (!profile.playniteBridgeOnline) {
                return Result.failed(Stage.PLAYNITE_READY,
                        FailureReason.PLAYNITE_BRIDGE_OFFLINE);
            }
            if (request.requiresPlayniteConnector() && !profile.playniteConnectorConnected) {
                return Result.failed(Stage.PLAYNITE_READY,
                        FailureReason.PLAYNITE_CONNECTOR_DISCONNECTED);
            }
        }

        List<NvApp> apps;
        try {
            apps = sunshine.refreshApps(request.hostId);
        } catch (IOException | RuntimeException unavailable) {
            return cancelled.getAsBoolean() ? Result.cancelled()
                    : Result.failed(Stage.TARGET_READY,
                    FailureReason.TARGET_UNAVAILABLE);
        }
        if (cancelled.getAsBoolean()) return Result.cancelled();
        if (request.isDirectProvider()) {
            NvApp desktop = PlayniteTargetResolver.resolveProviderStream(apps);
            if (desktop != null) {
                progress.onStage(Stage.TARGET_READY);
                return Result.ready(desktop, TargetResolution.EXISTING);
            }
            return Result.failed(Stage.TARGET_READY, FailureReason.TARGET_UNAVAILABLE);
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
        if (!profile.vibepolloBridgeOnline) {
            return Result.failed(Stage.VIBEPOLLO_READY,
                    FailureReason.VIBEPOLLO_UNAVAILABLE);
        }
        EnsuredTarget ensured;
        try {
            ensured = gateway.ensureTarget(request.hostId, request.gameId, request.appName);
        } catch (IOException | RuntimeException unavailable) {
            return cancelled.getAsBoolean() ? Result.cancelled()
                    : Result.failed(Stage.VIBEPOLLO_READY,
                    FailureReason.VIBEPOLLO_UNAVAILABLE);
        }
        if (cancelled.getAsBoolean()) return Result.cancelled();
        progress.onStage(Stage.TARGET_READY);
        return awaitEnsuredTarget(request, ensured, cancelled);
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
