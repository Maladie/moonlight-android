package com.limelight.console;

import com.limelight.nvstream.http.NvApp;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class HostLaunchPreflightTest {
    @Test public void genericSunshineLaunchNeedsNoGatewayOrOptionalIntegration() {
        Fake fake = new Fake();
        fake.apps.add(Collections.singletonList(app("Discord", 42, "")));

        HostLaunchPreflight.Result result = fake.run(sunshine(), new AtomicBoolean());

        assertEquals(HostLaunchPreflight.Status.READY, result.status);
        assertEquals(42, result.target.getAppId());
        assertEquals(0, fake.profileChecks);
        assertEquals(0, fake.ensureCalls);
    }

    @Test public void playniteWithoutGatewayFailsAtGatewayStage() {
        Fake fake = new Fake();
        fake.profile = null;

        assertFailure(fake.run(game(42), new AtomicBoolean()),
                HostLaunchPreflight.Stage.GATEWAY_READY,
                HostLaunchPreflight.FailureReason.GATEWAY_UNAVAILABLE);
    }

    @Test public void missingProfileIsDistinctFromOfflinePlayniteBridge() {
        Fake missing = new Fake();
        missing.profile = profile(false, true, true, true);
        assertFailure(missing.run(game(42), new AtomicBoolean()),
                HostLaunchPreflight.Stage.PROFILE_READY,
                HostLaunchPreflight.FailureReason.SELECTED_PROFILE_UNAVAILABLE);

        Fake offline = new Fake();
        offline.profile = profile(true, false, true, true);
        assertFailure(offline.run(game(42), new AtomicBoolean()),
                HostLaunchPreflight.Stage.PLAYNITE_READY,
                HostLaunchPreflight.FailureReason.PLAYNITE_BRIDGE_OFFLINE);
    }

    @Test public void disconnectedPlayniteConnectorIsDetected() {
        Fake fake = new Fake();
        fake.profile = profile(true, true, false, true);

        assertFailure(fake.run(game(42), new AtomicBoolean()),
                HostLaunchPreflight.Stage.PLAYNITE_READY,
                HostLaunchPreflight.FailureReason.PLAYNITE_CONNECTOR_DISCONNECTED);
    }

    @Test public void directProviderRecordDoesNotRequirePlayniteConnector() {
        Fake fake = new Fake();
        fake.profile = profile(true, true, false, true);
        fake.apps.add(Collections.singletonList(app("Desktop", 8, "desktop")));

        HostLaunchPreflight.Result result = fake.run(providerGame(42), new AtomicBoolean());

        assertEquals(HostLaunchPreflight.Status.READY, result.status);
        assertEquals(8, result.target.getAppId());
        assertEquals(0, fake.ensureCalls);
    }

    @Test public void directProviderIgnoresTransientPerGameTarget() {
        Fake fake = new Fake();
        fake.profile = profile(true, true, false, true);
        fake.apps.add(Arrays.asList(app("Game", 42, "steam:289070"),
                app("Desktop", 8, "desktop")));

        HostLaunchPreflight.Result result = fake.run(providerGame(42), new AtomicBoolean());

        assertEquals(HostLaunchPreflight.Status.READY, result.status);
        assertEquals(8, result.target.getAppId());
        assertEquals(0, fake.ensureCalls);
    }

    @Test public void directProviderWithoutDesktopFailsWithoutCreatingTransientTarget() {
        Fake fake = new Fake();
        fake.profile = profile(true, true, false, true);
        fake.apps.add(Collections.emptyList());

        assertFailure(fake.run(providerGame(42), new AtomicBoolean()),
                HostLaunchPreflight.Stage.TARGET_READY,
                HostLaunchPreflight.FailureReason.TARGET_UNAVAILABLE);
        assertEquals(0, fake.ensureCalls);
    }

    @Test public void existingExactTargetDoesNotRequireVibepollo() {
        Fake fake = new Fake();
        fake.profile = profile(true, true, true, false);
        fake.apps.add(Collections.singletonList(app("Game", 42, "exact")));

        HostLaunchPreflight.Result result = fake.run(game(42), new AtomicBoolean());

        assertEquals(HostLaunchPreflight.Status.READY, result.status);
        assertEquals(HostLaunchPreflight.TargetResolution.EXISTING, result.resolution);
        assertEquals(0, fake.ensureCalls);
    }

    @Test public void missingTargetEnsuresAndWaitsForExactUuid() {
        Fake fake = ensuringFake();
        NvApp exact = app("Game", 77, "expected");
        fake.apps.add(Collections.emptyList());
        addStable(fake, Collections.singletonList(exact));

        HostLaunchPreflight.Result result = fake.run(game(42), new AtomicBoolean());

        assertEquals(HostLaunchPreflight.Status.READY, result.status);
        assertEquals(77, result.target.getAppId());
        assertEquals(HostLaunchPreflight.TargetResolution.ENSURED, result.resolution);
        assertEquals(1, fake.ensureCalls);
    }

    @Test public void similarlyNamedStaleAppDoesNotBeatEnsuredIdentity() {
        Fake fake = ensuringFake();
        NvApp stale = app("Game", 12, "stale");
        NvApp exact = app("Game", 77, "expected");
        fake.apps.add(Collections.singletonList(stale));
        addStable(fake, Arrays.asList(stale, exact));

        HostLaunchPreflight.Result result = fake.run(game(42), new AtomicBoolean());

        assertEquals(HostLaunchPreflight.Status.READY, result.status);
        assertEquals(77, result.target.getAppId());
    }

    @Test public void targetPropagationTimeoutIsDeterministic() {
        Fake fake = ensuringFake();
        fake.apps.add(Collections.emptyList());
        fake.waitStepMs = 300_000L;

        assertFailure(fake.run(game(42), new AtomicBoolean()),
                HostLaunchPreflight.Stage.TARGET_READY,
                HostLaunchPreflight.FailureReason.TARGET_PROPAGATION_TIMEOUT);
    }

    @Test public void cancellationDuringNetworkWaitStopsAllLaterWork() {
        Fake fake = new Fake();
        AtomicBoolean cancelled = new AtomicBoolean();
        fake.networkAction = () -> cancelled.set(true);

        HostLaunchPreflight.Result result = fake.run(game(42), cancelled);

        assertEquals(HostLaunchPreflight.Status.CANCELLED, result.status);
        assertEquals(0, fake.profileChecks);
        assertEquals(0, fake.refreshes);
    }

    @Test public void cancellationDuringGatewayCheckStopsTargetWork() {
        Fake fake = new Fake();
        AtomicBoolean cancelled = new AtomicBoolean();
        fake.profileAction = () -> cancelled.set(true);

        HostLaunchPreflight.Result result = fake.run(game(42), cancelled);

        assertEquals(HostLaunchPreflight.Status.CANCELLED, result.status);
        assertEquals(0, fake.refreshes);
    }

    @Test public void cancellationDuringEnsureStopsPolling() {
        Fake fake = ensuringFake();
        fake.apps.add(Collections.emptyList());
        AtomicBoolean cancelled = new AtomicBoolean();
        fake.ensureAction = () -> cancelled.set(true);

        HostLaunchPreflight.Result result = fake.run(game(42), cancelled);

        assertEquals(HostLaunchPreflight.Status.CANCELLED, result.status);
        assertEquals(1, fake.refreshes);
    }

    @Test public void cancellationDuringTargetPollingStopsFurtherPolls() {
        Fake fake = ensuringFake();
        fake.apps.add(Collections.emptyList());
        AtomicBoolean cancelled = new AtomicBoolean();
        fake.refreshAction = () -> {
            if (fake.refreshes == 2) cancelled.set(true);
        };

        HostLaunchPreflight.Result result = fake.run(game(42), cancelled);

        assertEquals(HostLaunchPreflight.Status.CANCELLED, result.status);
        assertEquals(2, fake.refreshes);
    }

    private static Fake ensuringFake() {
        Fake fake = new Fake();
        fake.ensured = new HostLaunchPreflight.EnsuredTarget(77, "expected");
        return fake;
    }

    private static void addStable(Fake fake, List<NvApp> apps) {
        for (int i = 0; i < 5; i++) fake.apps.add(apps);
    }

    private static HostLaunchPreflight.Profile profile(boolean selected,
                                                        boolean playnite,
                                                        boolean connector,
                                                        boolean vibepollo) {
        return new HostLaunchPreflight.Profile(selected, playnite, connector, vibepollo);
    }

    private static HostLaunchPreflight.Request sunshine() {
        return HostLaunchPreflight.Request.from(PlayIntent.sunshineApp(
                "host", 42, "Discord", false, ""), HostLaunchPreflight.Action.LAUNCH);
    }

    private static HostLaunchPreflight.Request game(int appId) {
        return HostLaunchPreflight.Request.from(PlayIntent.playniteGame(
                "host", appId, "Game", false, "game", "game"),
                HostLaunchPreflight.Action.LAUNCH);
    }

    private static HostLaunchPreflight.Request providerGame(int appId) {
        return HostLaunchPreflight.Request.from(PlayIntent.playniteGame(
                "host", appId, "Game", false, "steam:289070", "steam:289070"),
                HostLaunchPreflight.Action.LAUNCH);
    }

    private static NvApp app(String name, int id, String uuid) {
        NvApp app = new NvApp(name, id, false);
        app.setAppUuid(uuid);
        return app;
    }

    private static void assertFailure(HostLaunchPreflight.Result result,
                                      HostLaunchPreflight.Stage stage,
                                      HostLaunchPreflight.FailureReason reason) {
        assertEquals(HostLaunchPreflight.Status.FAILED, result.status);
        assertEquals(stage, result.failure.stage);
        assertEquals(reason, result.failure.reason);
        assertNull(result.target);
    }

    private static final class Fake implements HostLaunchPreflight.Network,
            HostLaunchPreflight.Gateway, HostLaunchPreflight.Sunshine,
            HostLaunchPreflight.Clock, HostLaunchPreflight.Waiter {
        final Deque<List<NvApp>> apps = new ArrayDeque<>();
        HostLaunchPreflight.Profile profile = profile(true, true, true, true);
        HostLaunchPreflight.EnsuredTarget ensured =
                new HostLaunchPreflight.EnsuredTarget(77, "expected");
        Runnable networkAction;
        Runnable profileAction;
        Runnable ensureAction;
        Runnable refreshAction;
        int profileChecks;
        int ensureCalls;
        int refreshes;
        long now;
        long waitStepMs = 10_000L;

        HostLaunchPreflight.Result run(HostLaunchPreflight.Request request,
                                       AtomicBoolean cancelled) {
            return new HostLaunchPreflight(this, this, this, this, this)
                    .run(request, cancelled::get, stage -> { });
        }

        @Override public boolean awaitReady(String hostId,
                                            java.util.function.BooleanSupplier cancelled) {
            if (networkAction != null) networkAction.run();
            return !cancelled.getAsBoolean();
        }

        @Override public HostLaunchPreflight.Profile selectedProfile(String hostId) {
            profileChecks++;
            if (profileAction != null) profileAction.run();
            return profile;
        }

        @Override public HostLaunchPreflight.EnsuredTarget ensureTarget(
                String hostId, String gameId, String name) throws IOException {
            ensureCalls++;
            if (ensureAction != null) ensureAction.run();
            return ensured;
        }

        @Override public List<NvApp> refreshApps(String hostId) {
            refreshes++;
            if (refreshAction != null) refreshAction.run();
            return apps.isEmpty() ? Collections.emptyList() : apps.removeFirst();
        }

        @Override public long now() { return now; }

        @Override public boolean await(long millis,
                                       java.util.function.BooleanSupplier cancelled) {
            now += waitStepMs;
            return !cancelled.getAsBoolean();
        }
    }
}
