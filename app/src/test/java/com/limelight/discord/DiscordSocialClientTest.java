package com.limelight.discord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DiscordSocialClientTest {
    @Test
    public void nativeSnapshotConvertsUtf8ToUtf16BeforeCreatingJavaStrings() throws IOException {
        Path source = Paths.get("src/main/jni/discord-social/discord-social-jni.cpp");
        if (!Files.exists(source)) {
            source = Paths.get("app/src/main/jni/discord-social/discord-social-jni.cpp");
        }
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertTrue(text.contains("env->NewString("));
        assertTrue(text.contains("codePoint > 0x10ffff"));
        assertTrue(text.contains("codePoint >= 0xd800"));
        assertFalse(text.contains("NewStringUTF"));
    }

    @Test
    public void mapsTerminalDeviceFlowErrors() {
        assertEquals("Authorization denied", DiscordSocialClient.pollFailure("access_denied"));
        assertEquals("Discord code expired", DiscordSocialClient.pollFailure("expired_token"));
        assertEquals("Unable to contact Discord", DiscordSocialClient.pollFailure("unknown"));
    }

    @Test
    public void accessExpiryUsesSkewBeforeTheActualExpiry() {
        DiscordSocialClient.TokenBundle bundle = new DiscordSocialClient.TokenBundle(
                "access", "refresh", 200_000L, "scope");
        assertTrue(bundle.accessUsable(100_000L));
        assertFalse(bundle.accessUsable(140_000L));
    }

    @Test
    public void refreshResponseRotatesOnlyWhenDiscordSuppliesNewRefreshToken() throws Exception {
        DiscordSocialClient.TokenBundle previous = new DiscordSocialClient.TokenBundle(
                "old", "old-refresh", 1L, "scope-a");
        DiscordSocialClient.TokenBundle retained = DiscordSocialClient.TokenBundle.fromResponse(
                new JSONObject("{\"access_token\":\"new\",\"expires_in\":100}"), previous, 1000L);
        assertNotNull(retained);
        assertEquals("old-refresh", retained.refreshToken);
        assertEquals("scope-a", retained.scopes);
        DiscordSocialClient.TokenBundle rotated = DiscordSocialClient.TokenBundle.fromResponse(
                new JSONObject("{\"access_token\":\"new\",\"refresh_token\":\"new-refresh\","
                        + "\"expires_in\":100,\"scope\":\"scope-b\"}"), previous, 1000L);
        assertEquals("new-refresh", rotated.refreshToken);
        assertEquals("scope-b", rotated.scopes);
    }

    @Test
    public void malformedNativePayloadIsRejectedAndDoesNotProducePartialData() {
        assertNull(DiscordSocialClient.Snapshot.parse(new String[]{"1", "Ready", "1", "1", "name", "1"}, 1));
        assertNull(DiscordSocialClient.Snapshot.parse(new String[]{"2", "Ready", "1", "1", "name", "0"}, 1));
    }

    @Test
    public void snapshotRevisionChangesForActivityChangesNotOnlyNames() {
        DiscordSocialClient.Snapshot idle = DiscordSocialClient.Snapshot.parse(new String[]{
                "2", "Ready", "1", "1", "me", "me.png", "1", "2", "friend", "ONLINE", "", "friend.png"}, 1);
        DiscordSocialClient.Snapshot playing = DiscordSocialClient.Snapshot.parse(new String[]{
                "2", "Ready", "1", "1", "me", "me.png", "1", "2", "friend", "PLAYING", "MoonWaker", "friend.png"}, 2);
        assertNotNull(idle);
        assertNotNull(playing);
        assertFalse(idle.sameData(playing));
        assertEquals(Collections.singletonList("friend"), playing.friends);
    }

    @Test
    public void avatarChangesParticipateInSnapshotEquality() {
        DiscordSocialClient.Snapshot first = DiscordSocialClient.Snapshot.parse(new String[]{
                "2", "Ready", "1", "1", "me", "me-a.png", "1", "2", "friend", "ONLINE", "", "a.png"}, 1);
        DiscordSocialClient.Snapshot changed = DiscordSocialClient.Snapshot.parse(new String[]{
                "2", "Ready", "1", "1", "me", "me-a.png", "1", "2", "friend", "ONLINE", "", "b.png"}, 2);
        assertNotNull(first);
        assertNotNull(changed);
        assertFalse(first.sameData(changed));
        assertEquals("b.png", changed.friendDetails.get(0).avatarUrl);
    }

    @Test
    public void authorizationRequiredBelongsToTheLocalSessionOwnerNotNativeStatusText() {
        assertTrue(DiscordSocialClient.authorizationRequiredForOwner(false, false, false));
        assertFalse(DiscordSocialClient.authorizationRequiredForOwner(true, false, false));
        assertFalse(DiscordSocialClient.authorizationRequiredForOwner(false, true, false));
        assertFalse(DiscordSocialClient.authorizationRequiredForOwner(false, false, true));
    }

    @Test
    public void refreshDeadlineHonorsSkewAndTransientBackoff() {
        DiscordSocialClient.TokenBundle bundle = new DiscordSocialClient.TokenBundle(
                "access", "refresh", 160_000L, "scope");
        assertFalse(DiscordSocialClient.refreshDue(bundle, 99_999L, 0L));
        assertTrue(DiscordSocialClient.refreshDue(bundle, 100_000L, 0L));
        assertFalse(DiscordSocialClient.refreshDue(bundle, 130_000L, 130_001L));
        assertTrue(DiscordSocialClient.refreshDue(bundle, 130_001L, 130_001L));
    }

    @Test
    public void presenceOnlyGrantNeedsExplicitCommunicationUpgrade() {
        assertFalse(DiscordSocialClient.hasDirectMessageScope("openid sdk.social_layer_presence"));
        assertTrue(DiscordSocialClient.hasDirectMessageScope("openid sdk.social_layer"));
        assertTrue(DiscordSocialClient.hasDirectMessageScope("  openid   sdk.social_layer  "));
    }

    @Test
    public void messageProtocolIsLengthPrefixedAndPreservesUnicodeNewlinesAndDelimiters() {
        String content = "zażółć\n| : 😀";
        String record = DiscordSocialClient.encodeMessageEventForTest("1", "CREATED", "42",
                "0", "9", "7", content, "100", "0", "Attachment", "", "1", "0");
        DiscordSocialClient.MessageEvent event = DiscordSocialClient.MessageEvent.parse(record);
        assertNotNull(event);
        assertEquals(DiscordSocialClient.MessageEvent.Type.CREATED, event.type);
        assertEquals(content, event.content);
        assertEquals(42L, event.recipientId);
        assertEquals(9L, event.messageId);
        assertEquals("Attachment", event.additionalContentType);
        assertEquals("", event.additionalContentTitle);
        assertEquals(1, event.additionalContentCount);
    }

    @Test
    public void messageProtocolPreservesAdditionalContentTitleAndOpenResult() {
        DiscordSocialClient.MessageEvent media = DiscordSocialClient.MessageEvent.parse(
                DiscordSocialClient.encodeMessageEventForTest("1", "HISTORY_MESSAGE", "42", "1",
                        "9", "7", "", "100", "0", "Poll", "Which one?", "2", "0"));
        assertNotNull(media);
        assertEquals("Poll", media.additionalContentType);
        assertEquals("Which one?", media.additionalContentTitle);
        assertEquals(2, media.additionalContentCount);

        DiscordSocialClient.MessageEvent open = DiscordSocialClient.MessageEvent.parse(
                DiscordSocialClient.encodeMessageEventForTest("1", "OPEN_MESSAGE_RESULT", "9", "0", "HTTPError"));
        assertNotNull(open);
        assertFalse(open.successful);
        assertEquals(9L, open.messageId);
    }

    @Test
    public void malformedOrUnknownMessageProtocolDoesNotProducePartialEvent() {
        assertNull(DiscordSocialClient.MessageEvent.parse("4:MQ=="));
        String unsupported = DiscordSocialClient.encodeMessageEventForTest("99", "OVERFLOW");
        assertNull(DiscordSocialClient.MessageEvent.parse(unsupported));
    }

    @Test
    public void messageProtocolRejectsInvalidBase64PaddingAlphabetAndTrailingData() {
        assertNull(DiscordSocialClient.MessageEvent.parse("4:MQ=$"));
        assertNull(DiscordSocialClient.MessageEvent.parse("4:MQ=A"));
        assertNull(DiscordSocialClient.MessageEvent.parse("5:MQ==x"));
    }

    @Test
    public void directMessagePeerIsTheOtherParticipantInBothDirections() {
        assertEquals(42L, DiscordSocialClient.directMessagePeer(7L, 42L, 7L));
        assertEquals(42L, DiscordSocialClient.directMessagePeer(7L, 7L, 42L));
    }

    @Test
    public void directMessageEventsHaveExactlyOneConsumerLease() {
        DiscordSocialClient.MessageEventLease first = DiscordSocialClient.tryAcquireMessageEventLease();
        assertNotNull(first);
        try {
            assertNull(DiscordSocialClient.tryAcquireMessageEventLease());
        } finally {
            DiscordSocialClient.releaseMessageEventLease(first);
        }
    }

    @Test
    public void staleLeaseCannotDrainOrReleaseAfterHandoff() {
        DiscordSocialClient.MessageEventLease first = DiscordSocialClient.tryAcquireMessageEventLease();
        assertNotNull(first);
        DiscordSocialClient.releaseMessageEventLease(first);

        DiscordSocialClient.MessageEventLease second = DiscordSocialClient.tryAcquireMessageEventLease();
        assertNotNull(second);
        try {
            DiscordSocialClient.enqueueMessageEventsForTest(DiscordSocialClient.encodeMessageEventForTest(
                    "1", "OVERFLOW"));
            assertTrue(DiscordSocialClient.drainMessageEvents(first).isEmpty());
            DiscordSocialClient.releaseMessageEventLease(first);
            assertEquals(1, DiscordSocialClient.drainMessageEvents(second).size());
        } finally {
            DiscordSocialClient.releaseMessageEventLease(second);
        }
    }

    @Test
    public void eventsRemainQueuedUntilAConsumerOwnsALease() {
        DiscordSocialClient.enqueueMessageEventsForTest(DiscordSocialClient.encodeMessageEventForTest(
                "1", "OVERFLOW"));
        assertTrue(DiscordSocialClient.drainMessageEvents(null).isEmpty());

        DiscordSocialClient.MessageEventLease lease = DiscordSocialClient.tryAcquireMessageEventLease();
        assertNotNull(lease);
        try {
            List<DiscordSocialClient.MessageEvent> events = DiscordSocialClient.drainMessageEvents(lease);
            assertEquals(1, events.size());
            assertEquals(DiscordSocialClient.MessageEvent.Type.OVERFLOW, events.get(0).type);
        } finally {
            DiscordSocialClient.releaseMessageEventLease(lease);
        }
    }

    @Test
    public void directMessageRequestIdsAreGloballyPositiveUniqueAndIncreasing() {
        Set<Long> ids = new HashSet<>();
        long previous = 0;
        for (int index = 0; index < 32; index++) {
            long requestId = DiscordSocialClient.nextDirectMessageRequestId();
            assertTrue(requestId > 0);
            assertTrue(requestId > previous);
            assertTrue(ids.add(requestId));
            previous = requestId;
        }
    }

    @Test
    public void requestIdOverflowSkipsZeroAndNegativeValues() {
        assertEquals(1L, DiscordSocialClient.nextPositiveValue(Long.MAX_VALUE));
        assertEquals(1L, DiscordSocialClient.nextPositiveValue(-1L));
    }
}
