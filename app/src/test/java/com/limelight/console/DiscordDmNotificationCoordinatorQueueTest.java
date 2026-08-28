package com.limelight.console;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.discord.DiscordSocialClient;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class DiscordDmNotificationCoordinatorQueueTest {
    private FakeScheduler scheduler;
    private DiscordDmNotificationCoordinator coordinator;
    private FakeHost host;
    private FakeCuePlayer cuePlayer;
    private DiscordDmNotificationCoordinator.HostToken token;

    @Before public void setUp() {
        scheduler = new FakeScheduler();
        cuePlayer = new FakeCuePlayer(scheduler);
        coordinator = new DiscordDmNotificationCoordinator(scheduler, scheduler, cuePlayer);
        host = new FakeHost();
        token = coordinator.registerHost(host);
        coordinator.activateHost(token);
        coordinator.setWindowFocused(token, true);
    }

    @Test public void acceptsOnlyIncomingCreatedEventsWithKnownSelfAndConnection() {
        DiscordSocialClient.MessageEvent.Type[] ignored = {
                DiscordSocialClient.MessageEvent.Type.HISTORY_BEGIN,
                DiscordSocialClient.MessageEvent.Type.HISTORY_MESSAGE,
                DiscordSocialClient.MessageEvent.Type.HISTORY_RESULT,
                DiscordSocialClient.MessageEvent.Type.UPDATED,
                DiscordSocialClient.MessageEvent.Type.DELETED,
                DiscordSocialClient.MessageEvent.Type.SEND_RESULT,
                DiscordSocialClient.MessageEvent.Type.OPEN_MESSAGE_RESULT,
                DiscordSocialClient.MessageEvent.Type.OVERFLOW
        };
        long messageId = 1;
        for (DiscordSocialClient.MessageEvent.Type type : ignored) {
            emit(messageId++, 2, true, true, "ignored", false, type);
        }
        emit(messageId++, 2, false, true, "offline", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        coordinator.acceptForTest(messageId++, 0, 2, 1, true, "Ada", "avatar", "no self",
                false, true, DiscordSocialClient.MessageEvent.Type.CREATED);
        coordinator.acceptForTest(messageId, 1, 1, 2, true, "Me", "avatar", "outgoing",
                false, true, DiscordSocialClient.MessageEvent.Type.CREATED);

        assertEquals(0, coordinator.queuedCountForTest());
        scheduler.runCurrent();
        assertTrue(host.models.isEmpty());
    }

    @Test public void unknownPeerIsInformationalAndNeverExposesAnId() {
        emit(1, 987654321L, true, false, "hello", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.runCurrent();

        DiscordDmNotificationCoordinator.ToastModel model = host.last();
        assertEquals("Discord", model.senderName);
        assertEquals("", model.avatarUrl);
        assertFalse(model.actionable);
        assertFalse(model.senderName.contains("987654321"));
    }

    @Test public void sanitizerRemovesControlsBidiAndNormalizesWhitespace() {
        String sanitized = DiscordDmNotificationCoordinator.sanitize(
                "  Ala\n\t ma\u202E  kota\u2028 i\u0000 psa  ", 72);

        assertEquals("Ala ma kota i psa", sanitized);
    }

    @Test public void sanitizerCountsUnicodeCodePointsAndCapsAtSeventyTwo() {
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 73; i++) input.appendCodePoint(0x1F642);

        String sanitized = DiscordDmNotificationCoordinator.sanitize(input.toString(), 72);

        assertEquals(72, sanitized.codePointCount(0, sanitized.length()));
        assertTrue(sanitized.endsWith("…"));
    }

    @Test public void knownPeerWithoutDirectMessageScopeIsNotActionable() {
        coordinator.acceptForTest(1, 1, 2, 1, true, "Ada", "avatar", "hello",
                false, true, false, DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.runCurrent();

        assertFalse(host.last().actionable);
        assertFalse(coordinator.hasQuickAction(token));
    }

    @Test public void urlsMediaAndDisclosureUseNeutralContent() {
        emit(1, 2, true, true, "visit https://example.com/private", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.runCurrent();
        assertTrue(host.last().neutral);
        assertEquals("", host.last().snippet);

        scheduler.advanceBy(DiscordDmNotificationCoordinator.EXPOSURE_MS);
        emit(2, 3, true, true, "secret attachment title", true,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.runCurrent();
        assertTrue(host.last().neutral);
        assertEquals("", host.last().snippet);
    }

    @Test public void deduplicatesMessageIdBeforeCoalescingSameSender() {
        emit(10, 2, true, true, "first", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.runCurrent();
        scheduler.advanceBy(100);
        emit(10, 2, true, true, "duplicate", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.runCurrent();
        assertEquals(0, host.last().additionalCount);
        assertEquals("first", host.last().snippet);

        emit(11, 2, true, true, "second", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.runCurrent();
        assertEquals(1, host.last().additionalCount);
        assertEquals("second", host.last().snippet);
    }

    @Test public void queueIsBoundedAndDropsOldestWaitingEntry() {
        for (int peer = 2; peer <= 5; peer++) {
            emit(peer, peer, true, true, "m" + peer, false,
                    DiscordSocialClient.MessageEvent.Type.CREATED);
        }

        assertEquals(3, coordinator.queuedCountForTest());
        assertArrayEquals(new long[]{2, 4, 5}, coordinator.queuedPeersForTest());
    }

    @Test public void repeatedCoalescingCannotExtendExposurePastTenSeconds() {
        emit(1, 2, true, true, "0", false, DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.runCurrent();
        for (int i = 1; i <= 5; i++) {
            scheduler.advanceBy(1_400);
            emit(i + 1, 2, true, true, Integer.toString(i), false,
                    DiscordSocialClient.MessageEvent.Type.CREATED);
            scheduler.runCurrent();
        }

        scheduler.advanceBy(2_999);
        assertEquals(1, coordinator.queuedCountForTest());
        scheduler.advanceBy(1);
        assertEquals(0, coordinator.queuedCountForTest());
        assertTrue(host.hideCount > 0);
    }

    @Test public void singleToastExpiresAfterEightSeconds() {
        emit(1, 2, true, true, "hello", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.advanceBy(DiscordDmNotificationCoordinator.EXPOSURE_MS - 1);
        assertEquals(1, coordinator.queuedCountForTest());
        scheduler.advanceBy(1);
        assertEquals(0, coordinator.queuedCountForTest());
    }

    @Test public void dedupeLruIsBoundedToRecentIds() {
        coordinator.setVisiblePeer(token, 2);
        for (int messageId = 1; messageId <= 257; messageId++) {
            emit(messageId, 2, true, true, "suppressed", false,
                    DiscordSocialClient.MessageEvent.Type.CREATED);
        }
        coordinator.setVisiblePeer(token, 0);
        emit(1, 2, true, true, "old id evicted", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.runCurrent();

        assertEquals("old id evicted", host.last().snippet);
    }

    @Test public void suppressesOnlyTheExactVisiblePeer() {
        coordinator.setVisiblePeer(token, 2);
        emit(1, 2, true, true, "same chat", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        emit(2, 3, true, true, "other chat", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.runCurrent();

        assertArrayEquals(new long[]{3}, coordinator.queuedPeersForTest());
        assertEquals(3, host.last().peerId);
    }

    @Test public void modalBlockRetainsQueueAndRestartsPresentationWhenReleased() {
        coordinator.setPresentationBlocked(token, true);
        scheduler.runCurrent();
        emit(1, 2, true, true, "queued", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.runCurrent();
        assertEquals(1, coordinator.queuedCountForTest());
        assertTrue(host.models.isEmpty());

        scheduler.advanceBy(10_000);
        coordinator.setPresentationBlocked(token, false);
        scheduler.runCurrent();
        assertEquals("queued", host.last().snippet);
        scheduler.advanceBy(DiscordDmNotificationCoordinator.EXPOSURE_MS);
        assertEquals(0, coordinator.queuedCountForTest());
    }

    @Test public void focusLossClearsVisibleAndPendingNotifications() {
        emit(1, 2, true, true, "visible", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        emit(2, 3, true, true, "waiting", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.runCurrent();

        coordinator.setWindowFocused(token, false);
        scheduler.runCurrent();
        assertEquals(0, coordinator.queuedCountForTest());
        assertTrue(host.hideCount > 0);

        emit(3, 4, true, true, "while unfocused", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        assertEquals(0, coordinator.queuedCountForTest());
    }

    @Test public void pipOauthScreenSaverAndBackgroundEachClearNotifications() {
        assertGuardClears(() -> coordinator.setPictureInPicture(token, true));
        coordinator.setPictureInPicture(token, false);
        assertGuardClears(() -> coordinator.setAuthorizationActive(token, true));
        coordinator.setAuthorizationActive(token, false);
        assertGuardClears(() -> coordinator.setScreenSaverActive(token, true));
        coordinator.setScreenSaverActive(token, false);
        assertGuardClears(() -> coordinator.deactivateHost(token));
    }

    @Test public void hostHandoffRejectsCallbacksQueuedForOldGeneration() {
        emit(1, 2, true, true, "stale", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        FakeHost nextHost = new FakeHost();
        DiscordDmNotificationCoordinator.HostToken next = coordinator.registerHost(nextHost);
        coordinator.activateHost(next);
        coordinator.setWindowFocused(next, true);

        scheduler.runCurrent();
        assertTrue(host.models.isEmpty());
        assertTrue(nextHost.models.isEmpty());
        assertEquals(0, coordinator.queuedCountForTest());
    }

    @Test public void inactiveHostModalStateDoesNotInvalidateActiveTimer() {
        emit(1, 2, true, true, "active", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        FakeHost inactiveHost = new FakeHost();
        DiscordDmNotificationCoordinator.HostToken inactive = coordinator.registerHost(inactiveHost);
        coordinator.setPresentationBlocked(inactive, true);

        scheduler.advanceBy(DiscordDmNotificationCoordinator.EXPOSURE_MS);
        assertEquals(0, coordinator.queuedCountForTest());
    }

    @Test public void newerToastReplacesQuickActionAndConsumeIsAtomic() {
        emit(1, 2, true, true, "first", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        emit(2, 3, true, true, "newer", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);

        assertTrue(coordinator.hasQuickAction(token));
        assertEquals(3L, coordinator.consumeQuickAction(token));
        assertEquals(0L, coordinator.consumeQuickAction(token));
        assertFalse(coordinator.hasQuickAction(token));
    }

    @Test public void quickActionExpiresAtExactlySixtySeconds() {
        emit(1, 2, true, true, "target", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);

        scheduler.advanceBy(DiscordDmNotificationCoordinator.QUICK_ACTION_TTL_MS - 1);
        assertTrue(coordinator.hasQuickAction(token));
        scheduler.advanceBy(1);
        assertFalse(coordinator.hasQuickAction(token));
        assertEquals(0L, coordinator.consumeQuickAction(token));
    }

    @Test public void unknownNewerToastAndLifecycleBoundaryClearQuickAction() {
        emit(1, 2, true, true, "known", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        emit(2, 3, true, false, "unknown", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        assertFalse(coordinator.hasQuickAction(token));

        emit(3, 4, true, true, "known again", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        assertTrue(coordinator.hasQuickAction(token));
        coordinator.setWindowFocused(token, false);
        assertFalse(coordinator.hasQuickAction(token));
    }

    @Test public void pauseRejectsAQueuedShowCallback() {
        emit(1, 2, true, true, "stale", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        coordinator.deactivateHost(token);

        scheduler.runCurrent();
        assertTrue(host.models.isEmpty());
        assertEquals(0, coordinator.queuedCountForTest());
    }

    @Test public void cueCooldownExpiresAtExactlyThirtySeconds() {
        cuePlayer.advanceDuringPlayMs = 250L;
        emit(1, 2, true, true, "first", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.runCurrent();
        assertEquals(1, cuePlayer.playCount);
        coordinator.setVisiblePeer(token, 2);

        scheduler.advanceBy(DiscordDmNotificationCoordinator.CUE_COOLDOWN_MS - 1);
        emit(2, 3, true, true, "too soon", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.runCurrent();
        assertEquals(1, cuePlayer.playCount);
        coordinator.setVisiblePeer(token, 3);

        scheduler.advanceBy(1);
        emit(3, 4, true, true, "on boundary", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.runCurrent();
        assertEquals(2, cuePlayer.playCount);
    }

    @Test public void oauthAndDisconnectInvalidatePresentationAndTarget() {
        emit(1, 2, true, true, "oauth", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        assertTrue(coordinator.hasQuickAction(token));
        coordinator.onSocialStateChanged(DiscordSocialClient.getSnapshot(), true);
        scheduler.runCurrent();
        assertEquals(0, coordinator.queuedCountForTest());
        assertFalse(coordinator.hasQuickAction(token));
        assertTrue(host.immediateHideCount > 0);
    }

    @Test public void failedPlaybackDoesNotStartCooldown() {
        cuePlayer.successful = false;
        emit(1, 2, true, true, "silent failure", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.runCurrent();
        coordinator.setVisiblePeer(token, 2);

        cuePlayer.successful = true;
        emit(2, 3, true, true, "retry", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.runCurrent();

        assertEquals(2, cuePlayer.attemptCount);
        assertEquals(1, cuePlayer.playCount);
    }

    @Test public void suppressedBackgroundAndNeutralToastsDoNotStartCueCooldown() {
        coordinator.setVisiblePeer(token, 2);
        emit(1, 2, true, true, "same peer", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        coordinator.setWindowFocused(token, false);
        emit(2, 3, true, true, "background", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        coordinator.setWindowFocused(token, true);
        coordinator.setVisiblePeer(token, 0);
        emit(3, 4, true, true, "media", true,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.runCurrent();

        assertEquals(0, cuePlayer.attemptCount);
    }

    @Test public void disablingPreferenceClearsPresentationAndStopsCueImmediately() {
        emit(1, 2, true, true, "visible", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        emit(2, 3, true, true, "queued", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.runCurrent();
        assertTrue(coordinator.hasQuickAction(token));

        coordinator.setNotificationsEnabled(false);
        scheduler.runCurrent();
        assertEquals(0, coordinator.queuedCountForTest());
        assertFalse(coordinator.hasQuickAction(token));
        assertEquals(1, cuePlayer.stopCount);
        assertTrue(host.immediateHideCount > 0);

        emit(3, 4, true, true, "disabled", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        assertEquals(0, coordinator.queuedCountForTest());
        coordinator.setNotificationsEnabled(true);
        emit(4, 5, true, true, "enabled", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        scheduler.runCurrent();
        assertEquals(1, coordinator.queuedCountForTest());
    }

    @Test public void disablingPreferenceRejectsAlreadyQueuedPresentationCallback() {
        emit(1, 2, true, true, "stale", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        coordinator.setNotificationsEnabled(false);

        scheduler.runCurrent();

        assertTrue(host.models.isEmpty());
        assertEquals(0, cuePlayer.attemptCount);
        assertEquals(0, coordinator.queuedCountForTest());
    }

    private void assertGuardClears(Runnable guard) {
        long id = scheduler.nowMs() + host.hideCount + 100;
        emit(id, id, true, true, "guard", false,
                DiscordSocialClient.MessageEvent.Type.CREATED);
        assertTrue(coordinator.queuedCountForTest() > 0);
        guard.run();
        scheduler.runCurrent();
        assertEquals(0, coordinator.queuedCountForTest());
    }

    private void emit(long messageId, long peerId, boolean connected, boolean knownPeer,
                      String content, boolean neutral,
                      DiscordSocialClient.MessageEvent.Type type) {
        coordinator.acceptForTest(messageId, 1, peerId, 1, connected, "Ada", "avatar",
                content, neutral, knownPeer, type);
    }

    private static final class FakeHost implements DiscordDmNotificationCoordinator.Host {
        final List<DiscordDmNotificationCoordinator.ToastModel> models = new ArrayList<>();
        int hideCount;
        int immediateHideCount;

        @Override public void showDiscordDmToast(
                DiscordDmNotificationCoordinator.ToastModel model, boolean announce) {
            models.add(model);
        }

        @Override public void hideDiscordDmToast() {
            hideCount++;
        }

        @Override public void hideDiscordDmToastImmediately() {
            immediateHideCount++;
            hideCount++;
        }

        DiscordDmNotificationCoordinator.ToastModel last() {
            return models.get(models.size() - 1);
        }
    }

    private static final class FakeCuePlayer
            implements DiscordDmNotificationCoordinator.CuePlayer {
        boolean successful = true;
        int attemptCount;
        int playCount;
        int stopCount;
        long advanceDuringPlayMs;
        final FakeScheduler scheduler;

        FakeCuePlayer(FakeScheduler scheduler) {
            this.scheduler = scheduler;
        }

        @Override public boolean play() {
            attemptCount++;
            if (!successful) return false;
            playCount++;
            scheduler.elapseWithoutRunning(advanceDuringPlayMs);
            return true;
        }

        @Override public void stop() {
            stopCount++;
        }
    }

    private static final class FakeScheduler implements DiscordDmNotificationCoordinator.Clock,
            DiscordDmNotificationCoordinator.TaskScheduler {
        private static final class Scheduled {
            final long dueMs;
            final long order;
            final Runnable task;

            Scheduled(long dueMs, long order, Runnable task) {
                this.dueMs = dueMs;
                this.order = order;
                this.task = task;
            }
        }

        private final List<Scheduled> tasks = new ArrayList<>();
        private long nowMs;
        private long nextOrder;

        @Override public long nowMs() {
            return nowMs;
        }

        @Override public void post(Runnable task) {
            tasks.add(new Scheduled(nowMs, nextOrder++, task));
        }

        @Override public void postDelayed(Runnable task, long delayMs) {
            tasks.add(new Scheduled(nowMs + Math.max(0L, delayMs), nextOrder++, task));
        }

        void advanceBy(long durationMs) {
            nowMs += durationMs;
            runCurrent();
        }

        void elapseWithoutRunning(long durationMs) {
            nowMs += durationMs;
        }

        void runCurrent() {
            while (true) {
                Scheduled next = tasks.stream()
                        .filter(task -> task.dueMs <= nowMs)
                        .min(Comparator.comparingLong((Scheduled task) -> task.dueMs)
                                .thenComparingLong(task -> task.order))
                        .orElse(null);
                if (next == null) return;
                tasks.remove(next);
                next.task.run();
            }
        }
    }
}
