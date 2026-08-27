package com.limelight.console;

import com.limelight.discord.DiscordSocialClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded, process-local DM state. No message content, drafts, IDs, or unread data is persisted. */
final class DiscordDirectMessageState {
    static final int HISTORY_LIMIT = 30;
    static final int CONVERSATION_LIMIT = 32;

    static final class Message {
        final long id;
        final long authorId;
        final String content;
        final long sentTimestampMs;
        final long editedTimestampMs;
        final String additionalContentType;
        final String additionalContentTitle;
        final int additionalContentCount;
        final boolean disclosure;

        Message(DiscordSocialClient.MessageEvent event) {
            id = event.messageId;
            authorId = event.authorId;
            content = event.content;
            sentTimestampMs = event.sentTimestampMs;
            editedTimestampMs = event.editedTimestampMs;
            additionalContentType = event.additionalContentType;
            additionalContentTitle = event.additionalContentTitle;
            additionalContentCount = event.additionalContentCount;
            disclosure = event.disclosure;
        }
    }

    static final class SendState {
        final boolean inFlight;
        final boolean retryable;
        final float retryAfterSeconds;
        final String errorType;

        SendState(boolean inFlight, boolean retryable, float retryAfterSeconds, String errorType) {
            this.inFlight = inFlight;
            this.retryable = retryable;
            this.retryAfterSeconds = retryAfterSeconds;
            this.errorType = errorType == null ? "" : errorType;
        }
    }

    private final Map<Long, List<Message>> histories = new HashMap<>();
    private final Map<Long, Long> activeHistoryRequests = new HashMap<>();
    // Live events can arrive before, during, or after the async history callback. Keep them
    // separate until that callback finishes so HISTORY_BEGIN cannot erase them and older history
    // cannot overwrite a newer UPDATE.
    private final Map<Long, Map<Long, Message>> liveDuringHistory = new HashMap<>();
    private final Set<Long> deletedDuringHistory = new HashSet<>();
    private final Map<Long, Long> activeSendRequests = new HashMap<>();
    private final Map<Long, SendState> sendStates = new HashMap<>();
    private final Set<Long> unreadRecipients = new HashSet<>();
    private final LinkedHashMap<Long, Boolean> recentRecipients = new LinkedHashMap<>(16, .75f, true);
    private long visibleRecipientId;

    void requestHistory(long recipientId, long requestId) {
        touch(recipientId);
        activeHistoryRequests.put(recipientId, requestId);
        liveDuringHistory.put(recipientId, new HashMap<>());
        trim();
    }

    void beginHistory(long recipientId, long requestId) {
        if (!acceptsHistory(recipientId, requestId)) return;
        touch(recipientId);
        histories.put(recipientId, new ArrayList<>());
        Map<Long, Message> live = liveDuringHistory.get(recipientId);
        if (live != null) {
            for (Message message : live.values()) addMessage(histories.get(recipientId), message);
        }
    }

    boolean acceptsHistory(long recipientId, long requestId) {
        Long active = activeHistoryRequests.get(recipientId);
        return active != null && active == requestId;
    }

    void finishHistory(long recipientId, long requestId) {
        if (!acceptsHistory(recipientId, requestId)) return;
        List<Message> history = histories.get(recipientId);
        Map<Long, Message> live = liveDuringHistory.remove(recipientId);
        if (history != null && live != null) {
            for (Message message : live.values()) addMessage(history, message);
        }
        activeHistoryRequests.remove(recipientId);
        if (activeHistoryRequests.isEmpty()) deletedDuringHistory.clear();
    }

    void upsert(long recipientId, DiscordSocialClient.MessageEvent event) {
        upsertLive(recipientId, event);
    }

    void upsertHistory(long recipientId, DiscordSocialClient.MessageEvent event) {
        if (recipientId <= 0 || event.messageId <= 0 || deletedDuringHistory.contains(event.messageId)) return;
        Map<Long, Message> live = liveDuringHistory.get(recipientId);
        if (live != null && live.containsKey(event.messageId)) return;
        upsertIntoHistory(recipientId, new Message(event));
    }

    private void upsertLive(long recipientId, DiscordSocialClient.MessageEvent event) {
        if (recipientId <= 0 || event.messageId <= 0) return;
        if (deletedDuringHistory.contains(event.messageId)) return;
        touch(recipientId);
        Message message = new Message(event);
        if (activeHistoryRequests.containsKey(recipientId)) {
            liveDuringHistory.computeIfAbsent(recipientId, ignored -> new HashMap<>()).put(message.id, message);
        }
        upsertIntoHistory(recipientId, message);
    }

    private void upsertIntoHistory(long recipientId, Message message) {
        List<Message> history = histories.get(recipientId);
        if (history == null) {
            history = new ArrayList<>();
            histories.put(recipientId, history);
        }
        addMessage(history, message);
        trim();
    }

    private static void addMessage(List<Message> history, Message message) {
        for (int index = 0; index < history.size(); index++) {
            if (history.get(index).id == message.id) {
                history.set(index, message);
                sortAndBound(history);
                return;
            }
        }
        history.add(message);
        sortAndBound(history);
    }

    void delete(long messageId) {
        if (messageId <= 0) return;
        if (!activeHistoryRequests.isEmpty()) {
            deletedDuringHistory.add(messageId);
            for (Map<Long, Message> live : liveDuringHistory.values()) live.remove(messageId);
        }
        for (List<Message> history : histories.values()) {
            for (int index = history.size() - 1; index >= 0; index--) {
                if (history.get(index).id == messageId) history.remove(index);
            }
        }
    }

    List<Message> history(long recipientId) {
        List<Message> history = histories.get(recipientId);
        return history == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(history));
    }

    void markUnreadIfIncoming(long recipientId, long currentUserId, long authorId,
                              boolean visibleAndRendered) {
        if (recipientId <= 0 || authorId == currentUserId || visibleAndRendered) return;
        touch(recipientId);
        unreadRecipients.add(recipientId);
        trim();
    }

    void clearUnreadAfterRendered(long recipientId) {
        unreadRecipients.remove(recipientId);
    }

    boolean hasUnread(long recipientId) { return unreadRecipients.contains(recipientId); }

    boolean beginSend(long recipientId, long requestId) {
        if (recipientId <= 0 || requestId < 0 || activeSendRequests.containsKey(recipientId)) return false;
        touch(recipientId);
        activeSendRequests.put(recipientId, requestId);
        sendStates.put(recipientId, new SendState(true, false, 0, ""));
        trim();
        return true;
    }

    void finishSend(long recipientId, long requestId, boolean successful, boolean retryable,
                    float retryAfterSeconds, String errorType) {
        Long active = activeSendRequests.get(recipientId);
        if (active == null || active != requestId) return;
        activeSendRequests.remove(recipientId);
        sendStates.put(recipientId, new SendState(false, retryable, retryAfterSeconds,
                successful ? "" : errorType));
        trim();
    }

    SendState sendState(long recipientId) {
        SendState state = sendStates.get(recipientId);
        return state == null ? new SendState(false, false, 0, "") : state;
    }

    void setVisibleRecipient(long recipientId) {
        visibleRecipientId = recipientId;
        if (recipientId > 0) touch(recipientId);
        trim();
    }

    private void touch(long recipientId) {
        if (recipientId > 0) recentRecipients.put(recipientId, Boolean.TRUE);
    }

    private void trim() {
        while (recentRecipients.size() > CONVERSATION_LIMIT) {
            boolean evicted = false;
            Iterator<Long> iterator = recentRecipients.keySet().iterator();
            while (iterator.hasNext()) {
                long candidate = iterator.next();
                if (candidate == visibleRecipientId || activeHistoryRequests.containsKey(candidate)
                        || activeSendRequests.containsKey(candidate)) continue;
                iterator.remove();
                histories.remove(candidate);
                liveDuringHistory.remove(candidate);
                sendStates.remove(candidate);
                unreadRecipients.remove(candidate);
                evicted = true;
                break;
            }
            if (!evicted) return; // active/in-flight conversations are never evicted.
        }
    }

    private static void sortAndBound(List<Message> history) {
        Collections.sort(history, Comparator.comparingLong((Message value) -> value.sentTimestampMs)
                .thenComparingLong(value -> value.id));
        while (history.size() > HISTORY_LIMIT) history.remove(0);
    }
}
