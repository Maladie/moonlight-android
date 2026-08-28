package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.discord.DiscordSocialClient;

import org.junit.Test;

public class DiscordDirectMessageStateTest {
    private static DiscordSocialClient.MessageEvent message(long id, long author, long sent) {
        return DiscordSocialClient.MessageEvent.parse(DiscordSocialClient.encodeMessageEventForTest(
                "1", "CREATED", "42", "0", Long.toString(id), Long.toString(author), "message " + id,
                Long.toString(sent), "0", "", "0", "0"));
    }

    @Test
    public void historySortsChronologicallyAndDeduplicatesUpdates() {
        DiscordDirectMessageState state = new DiscordDirectMessageState();
        state.upsert(42, message(2, 42, 200));
        state.upsert(42, message(1, 42, 100));
        state.upsert(42, message(2, 42, 250));
        assertEquals(2, state.history(42).size());
        assertEquals(1, state.history(42).get(0).id);
        assertEquals(2, state.history(42).get(1).id);
        assertEquals(250, state.history(42).get(1).sentTimestampMs);
    }

    @Test
    public void deleteRemovesKnownMessageFromBoundedMemory() {
        DiscordDirectMessageState state = new DiscordDirectMessageState();
        state.upsert(42, message(9, 42, 1));
        state.delete(9);
        assertTrue(state.history(42).isEmpty());
    }

    @Test
    public void unreadIsLocalAndNeverSetForSelf() {
        DiscordDirectMessageState state = new DiscordDirectMessageState();
        state.markUnreadIfIncoming(42, 7, 7, false);
        assertFalse(state.hasUnread(42));
        state.markUnreadIfIncoming(42, 7, 42, false);
        assertTrue(state.hasUnread(42));
        state.clearUnreadAfterRendered(42);
        assertFalse(state.hasUnread(42));
    }

    @Test
    public void onlyOneSendCanBeInFlightAndRetryStateIsManual() {
        DiscordDirectMessageState state = new DiscordDirectMessageState();
        assertTrue(state.beginSend(42, 1));
        assertFalse(state.beginSend(42, 2));
        assertTrue(state.finishSend(42, 1, false, true, 12f, "HTTPError"));
        assertFalse(state.sendState(42).inFlight);
        assertTrue(state.sendState(42).retryable);
        assertEquals(12f, state.sendState(42).retryAfterSeconds, 0f);
        assertTrue(state.beginSend(42, 2));
    }

    @Test
    public void lateSendResultIsRejectedWithoutChangingTheActiveRequest() {
        DiscordDirectMessageState state = new DiscordDirectMessageState();
        assertTrue(state.beginSend(42, 2));
        assertFalse(state.finishSend(42, 1, true, false, 0, ""));
        assertTrue(state.sendState(42).inFlight);
    }

    @Test public void successfulSendClearsOnlyTheUnchangedDraftForTheAcceptedRequest() {
        assertTrue(DiscordSocialPanelController.shouldClearDirectMessageDraftAfterSendResult(
                true, true, "hello", "hello"));
        assertFalse(DiscordSocialPanelController.shouldClearDirectMessageDraftAfterSendResult(
                false, true, "hello", "hello"));
        assertFalse(DiscordSocialPanelController.shouldClearDirectMessageDraftAfterSendResult(
                true, false, "hello", "hello"));
        assertFalse(DiscordSocialPanelController.shouldClearDirectMessageDraftAfterSendResult(
                true, true, "newer", "hello"));
    }

    @Test
    public void lateHistoryBeginCannotReplaceNewerRequest() {
        DiscordDirectMessageState state = new DiscordDirectMessageState();
        state.requestHistory(42, 1);
        state.requestHistory(42, 2);
        state.beginHistory(42, 1);
        assertTrue(state.history(42).isEmpty());
        assertTrue(state.acceptsHistory(42, 2));
        state.beginHistory(42, 2);
        assertTrue(state.acceptsHistory(42, 2));
    }

    @Test
    public void historyBeginAndOlderHistoryCannotEraseLiveMessages() {
        DiscordDirectMessageState state = new DiscordDirectMessageState();
        state.requestHistory(42, 1);
        state.upsert(42, message(3, 42, 300)); // Created before HISTORY_BEGIN.
        state.beginHistory(42, 1);
        state.upsertHistory(42, message(1, 42, 100));
        state.upsert(42, message(4, 42, 400)); // Created while history streams.
        state.upsertHistory(42, message(4, 42, 200)); // Older snapshot must not win.
        state.finishHistory(42, 1);

        assertEquals(3, state.history(42).size());
        assertEquals(1, state.history(42).get(0).id);
        assertEquals(3, state.history(42).get(1).id);
        assertEquals(4, state.history(42).get(2).id);
        assertEquals(400, state.history(42).get(2).sentTimestampMs);
    }

    @Test
    public void deletedMessageIsNotResurrectedByAnInFlightHistoryResult() {
        DiscordDirectMessageState state = new DiscordDirectMessageState();
        state.requestHistory(42, 1);
        state.delete(3);
        state.beginHistory(42, 1);
        state.upsertHistory(42, message(3, 42, 300));
        state.finishHistory(42, 1);
        assertTrue(state.history(42).isEmpty());
    }

    @Test
    public void oldConversationIsEvictedButVisibleAndInFlightConversationsAreRetained() {
        DiscordDirectMessageState state = new DiscordDirectMessageState();
        state.upsert(1, message(1, 1, 1));
        state.setVisibleRecipient(1);
        for (long recipient = 2; recipient <= DiscordDirectMessageState.CONVERSATION_LIMIT + 2; recipient++) {
            state.upsert(recipient, message(recipient, recipient, recipient));
        }
        assertFalse(state.history(1).isEmpty());
        assertTrue(state.history(2).isEmpty());
    }
}
