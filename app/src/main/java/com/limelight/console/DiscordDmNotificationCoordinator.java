package com.limelight.console;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.limelight.discord.DiscordSocialClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

/** Process-wide, passive presentation coordinator for incoming Discord DMs. */
public final class DiscordDmNotificationCoordinator
        implements DiscordSocialClient.MessageEventObserver {
    static final long COALESCE_WINDOW_MS = 1_500L;
    static final long EXPOSURE_MS = 8_000L;
    static final long HARD_EXPOSURE_MS = 10_000L;
    static final long QUICK_ACTION_TTL_MS = 60_000L;
    static final long CUE_COOLDOWN_MS = 30_000L;
    static final int MAX_QUEUE_SIZE = 3;
    private static final int MAX_DEDUPE_IDS = 256;
    private static final int MAX_SNIPPET_CODE_POINTS = 72;
    private static final int MAX_SENDER_CODE_POINTS = 32;
    private static final Pattern URL = Pattern.compile(
            "(?i)(?:\\b(?:https?|ftp)://|\\bwww\\.|\\b[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"
                    + "(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+(?:/\\S*)?)");

    public interface Host {
        void showDiscordDmToast(ToastModel model, boolean announce);
        void hideDiscordDmToast();
        default void hideDiscordDmToastImmediately() { hideDiscordDmToast(); }
    }

    public static final class HostToken {
        private Registration registration;

        private HostToken(Registration registration) {
            this.registration = registration;
        }
    }

    /** Already-sanitized presentation data. Raw message content never leaves this coordinator. */
    public static final class ToastModel {
        public final long peerId;
        public final String senderName;
        public final String avatarUrl;
        public final String snippet;
        public final boolean neutral;
        public final boolean actionable;
        public final int additionalCount;

        private ToastModel(long peerId, String senderName, String avatarUrl, String snippet,
                           boolean neutral, boolean actionable, int additionalCount) {
            this.peerId = peerId;
            this.senderName = senderName;
            this.avatarUrl = avatarUrl;
            this.snippet = snippet;
            this.neutral = neutral;
            this.actionable = actionable;
            this.additionalCount = additionalCount;
        }
    }

    interface Clock {
        long nowMs();
    }

    interface TaskScheduler {
        void post(Runnable task);
        void postDelayed(Runnable task, long delayMs);
    }

    interface CuePlayer {
        boolean play();
        void stop();
    }

    private static final class MainThreadScheduler implements TaskScheduler {
        private Handler handler;

        private synchronized Handler handler() {
            if (handler == null) handler = new Handler(Looper.getMainLooper());
            return handler;
        }

        @Override public void post(Runnable task) {
            handler().post(task);
        }

        @Override public void postDelayed(Runnable task, long delayMs) {
            handler().postDelayed(task, Math.max(0L, delayMs));
        }
    }

    private static final class Holder {
        private static final DiscordDmNotificationCoordinator INSTANCE =
                new DiscordDmNotificationCoordinator(SystemClock::elapsedRealtime,
                        new MainThreadScheduler(), true, null);
    }

    private static final class Registration {
        final Host host;
        boolean foreground;
        boolean focused;
        boolean pictureInPicture;
        boolean authorizationActive;
        boolean screenSaverActive;
        boolean presentationBlocked;
        long visiblePeerId;
        long generation;

        Registration(Host host) {
            this.host = host;
        }

        boolean eligible() {
            return foreground && focused && !pictureInPicture && !authorizationActive
                    && !screenSaverActive;
        }
    }

    private static final class IncomingMessage {
        final long messageId;
        final long peerId;
        final String senderName;
        final String avatarUrl;
        final String content;
        final boolean neutral;
        final boolean actionable;

        IncomingMessage(long messageId, long peerId, String senderName, String avatarUrl,
                        String content, boolean neutral, boolean actionable) {
            this.messageId = messageId;
            this.peerId = peerId;
            this.senderName = senderName;
            this.avatarUrl = avatarUrl;
            this.content = content;
            this.neutral = neutral;
            this.actionable = actionable;
        }
    }

    private static final class Entry {
        final long peerId;
        final String senderName;
        final String avatarUrl;
        final boolean actionable;
        long lastArrivalMs;
        long firstShownMs = -1L;
        String snippet;
        boolean neutral;
        int additionalCount;

        Entry(IncomingMessage incoming, long nowMs) {
            peerId = incoming.peerId;
            senderName = incoming.senderName;
            avatarUrl = incoming.avatarUrl;
            actionable = incoming.actionable;
            lastArrivalMs = nowMs;
            snippet = incoming.content;
            neutral = incoming.neutral;
        }

        ToastModel model() {
            return new ToastModel(peerId, senderName, avatarUrl, snippet, neutral,
                    actionable, additionalCount);
        }
    }

    private final Clock clock;
    private final TaskScheduler scheduler;
    @SuppressWarnings("FieldCanBeLocal")
    private final DiscordSocialClient.ObserverToken observerToken;
    private final Deque<Entry> queue = new ArrayDeque<>();
    private final LinkedHashMap<Long, Boolean> recentMessageIds =
            new LinkedHashMap<Long, Boolean>(MAX_DEDUPE_IDS + 1, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<Long, Boolean> eldest) {
                    return size() > MAX_DEDUPE_IDS;
                }
            };
    private Registration activeHost;
    private long presentationGeneration;
    private long quickActionPeerId;
    private long quickActionExpiresAtMs;
    private CuePlayer cuePlayer;
    private boolean notificationsEnabled = true;
    private boolean cuePlayed;
    private long lastCueAtMs;
    private boolean socialConnected = true;
    private boolean socialAuthorizationActive;

    DiscordDmNotificationCoordinator(Clock clock, TaskScheduler scheduler) {
        this(clock, scheduler, false, null);
    }

    DiscordDmNotificationCoordinator(Clock clock, TaskScheduler scheduler, CuePlayer cuePlayer) {
        this(clock, scheduler, false, cuePlayer);
    }

    private DiscordDmNotificationCoordinator(Clock clock, TaskScheduler scheduler,
                                              boolean observeEvents, CuePlayer cuePlayer) {
        this.clock = clock;
        this.scheduler = scheduler;
        this.cuePlayer = cuePlayer;
        observerToken = observeEvents ? DiscordSocialClient.addMessageEventObserver(this) : null;
    }

    public static DiscordDmNotificationCoordinator getInstance() {
        return Holder.INSTANCE;
    }

    public synchronized void initialize(Context context) {
        Context application = context.getApplicationContext();
        if (cuePlayer == null) cuePlayer = new DiscordDmCuePlayer(application);
        setNotificationsEnabled(DiscordDmNotificationPreferences.isEnabled(application));
    }

    public synchronized void setNotificationsEnabled(boolean enabled) {
        notificationsEnabled = enabled;
        if (enabled) return;
        if (cuePlayer != null) cuePlayer.stop();
        if (activeHost != null) clearPresentationLocked(activeHost);
        else {
            queue.clear();
            clearQuickActionLocked();
            presentationGeneration++;
        }
    }

    public synchronized HostToken registerHost(Host host) {
        if (host == null) throw new NullPointerException("host");
        return new HostToken(new Registration(host));
    }

    public synchronized void unregisterHost(HostToken token) {
        Registration registration = registration(token);
        if (registration == null) return;
        if (activeHost == registration) {
            clearPresentationLocked(registration);
            activeHost = null;
        }
        else registration.generation++;
        token.registration = null;
    }

    /** Marks this process surface as resumed and eligible once it gains window focus. */
    public synchronized void activateHost(HostToken token) {
        Registration registration = registration(token);
        if (registration == null || activeHost == registration) return;
        if (activeHost != null) clearPresentationLocked(activeHost);
        activeHost = registration;
        registration.foreground = true;
        registration.generation++;
        presentNextLocked();
    }

    /** Backgrounding is a privacy boundary: pending and visible notifications are discarded. */
    public synchronized void deactivateHost(HostToken token) {
        Registration registration = registration(token);
        if (registration == null || activeHost != registration) return;
        registration.foreground = false;
        clearPresentationLocked(registration);
        activeHost = null;
    }

    public synchronized void setWindowFocused(HostToken token, boolean focused) {
        updateGuard(token, registration -> registration.focused = focused);
    }

    public synchronized void setPictureInPicture(HostToken token, boolean active) {
        updateGuard(token, registration -> registration.pictureInPicture = active);
    }

    public synchronized void setAuthorizationActive(HostToken token, boolean active) {
        updateGuard(token, registration -> registration.authorizationActive = active);
    }

    public synchronized void setScreenSaverActive(HostToken token, boolean active) {
        updateGuard(token, registration -> registration.screenSaverActive = active);
    }

    /** Temporary modal UI hides the toast but retains its bounded queue. */
    public synchronized void setPresentationBlocked(HostToken token, boolean blocked) {
        Registration registration = registration(token);
        if (registration == null || registration.presentationBlocked == blocked) return;
        registration.presentationBlocked = blocked;
        registration.generation++;
        if (activeHost != registration) return;
        presentationGeneration++;
        if (blocked) {
            if (!queue.isEmpty()) queue.peekFirst().firstShownMs = -1L;
            postHide(registration, true);
        }
        else presentNextLocked();
    }

    /** Suppression is identity-exact; changing chats never suppresses another peer. */
    public synchronized void setVisiblePeer(HostToken token, long peerId) {
        Registration registration = registration(token);
        if (registration == null) return;
        registration.visiblePeerId = Math.max(0L, peerId);
        if (registration.visiblePeerId == quickActionPeerId) clearQuickActionLocked();
        if (activeHost != registration || peerId <= 0L) return;
        boolean removedVisible = !queue.isEmpty() && queue.peekFirst().peerId == peerId;
        Iterator<Entry> iterator = queue.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().peerId == peerId) iterator.remove();
        }
        if (removedVisible) {
            registration.generation++;
            presentationGeneration++;
            postHide(registration, true);
            presentNextLocked();
        }
    }

    public synchronized boolean hasQuickAction(HostToken token) {
        Registration registration = registration(token);
        return registration != null && registration == activeHost && hostEligible(registration)
                && !registration.presentationBlocked && currentQuickActionLocked() > 0L;
    }

    /** Atomically returns and clears the current host's unexpired target. */
    public synchronized long consumeQuickAction(HostToken token) {
        if (!hasQuickAction(token)) return 0L;
        long peerId = quickActionPeerId;
        clearQuickActionLocked();
        return peerId;
    }

    public synchronized void invalidateQuickAction() {
        clearQuickActionLocked();
    }

    @Override public void onMessageEvent(DiscordSocialClient.MessageEvent event) {
        if (event == null || event.type != DiscordSocialClient.MessageEvent.Type.CREATED) return;
        DiscordSocialClient.Snapshot snapshot = DiscordSocialClient.getSnapshot();
        long selfId = positiveLong(snapshot.userId);
        if (!snapshot.connected || selfId <= 0L || event.authorId == selfId) return;
        long peerId = DiscordSocialClient.directMessagePeer(selfId, event.authorId, event.recipientId);
        if (peerId <= 0L) return;

        DiscordSocialClient.Friend friend = null;
        String peer = Long.toString(peerId);
        for (DiscordSocialClient.Friend candidate : snapshot.friendDetails) {
            if (peer.equals(candidate.userId)) {
                friend = candidate;
                break;
            }
        }
        String sender = friend == null ? "Discord" : sanitize(friend.displayName,
                MAX_SENDER_CODE_POINTS);
        if (sender.isEmpty()) sender = "Discord";
        boolean neutral = event.disclosure || event.additionalContentCount > 0
                || !event.additionalContentType.isEmpty() || !event.additionalContentTitle.isEmpty();
        String content = sanitize(event.content, MAX_SNIPPET_CODE_POINTS);
        if (content.isEmpty() || URL.matcher(content).find()) {
            content = "";
            neutral = true;
        }
        accept(new IncomingMessage(event.messageId, peerId, sender,
                friend == null ? "" : friend.avatarUrl, content, neutral,
                friend != null && DiscordSocialClient.canUseDirectMessages()));
    }

    @Override public synchronized void onSocialStateChanged(
            DiscordSocialClient.Snapshot snapshot, boolean authorizationActive) {
        boolean wasAvailable = socialConnected && !socialAuthorizationActive;
        socialConnected = snapshot != null && snapshot.connected;
        socialAuthorizationActive = authorizationActive;
        boolean available = socialConnected && !socialAuthorizationActive;
        if (activeHost == null) return;
        if (wasAvailable && !available) clearPresentationLocked(activeHost);
        else if (available) presentNextLocked();
    }

    private interface GuardUpdate {
        void apply(Registration registration);
    }

    private void updateGuard(HostToken token, GuardUpdate update) {
        Registration registration = registration(token);
        if (registration == null) return;
        boolean wasEligible = hostEligible(registration);
        update.apply(registration);
        boolean eligible = hostEligible(registration);
        if (activeHost != registration) return;
        if (wasEligible && !eligible) clearPresentationLocked(registration);
        else if (eligible) presentNextLocked();
    }

    private void accept(IncomingMessage incoming) {
        synchronized (this) {
            Registration registration = activeHost;
            if (!notificationsEnabled || registration == null || !hostEligible(registration)) return;
            if (recentMessageIds.put(incoming.messageId, Boolean.TRUE) != null) return;
            if (registration.visiblePeerId == incoming.peerId) return;

            long now = clock.nowMs();
            if (incoming.actionable) {
                quickActionPeerId = incoming.peerId;
                quickActionExpiresAtMs = now + QUICK_ACTION_TTL_MS;
            } else {
                clearQuickActionLocked();
            }
            Entry newest = queue.peekLast();
            if (newest != null && newest.peerId == incoming.peerId
                    && now - newest.lastArrivalMs <= COALESCE_WINDOW_MS) {
                newest.lastArrivalMs = now;
                newest.snippet = incoming.content;
                newest.neutral = incoming.neutral;
                newest.additionalCount++;
                if (newest == queue.peekFirst() && newest.firstShownMs >= 0L) {
                    scheduleExpiryLocked(registration, newest, now);
                    postShow(registration, newest, false);
                }
                return;
            }

            if (queue.size() >= MAX_QUEUE_SIZE) removeOldestWaitingLocked();
            queue.addLast(new Entry(incoming, now));
            presentNextLocked();
        }
    }

    private void removeOldestWaitingLocked() {
        Iterator<Entry> iterator = queue.iterator();
        if (!iterator.hasNext()) return;
        if (queue.peekFirst().firstShownMs >= 0L) iterator.next();
        if (iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private void presentNextLocked() {
        Registration registration = activeHost;
        if (registration == null || !hostEligible(registration) || registration.presentationBlocked
                || queue.isEmpty()) return;
        Entry entry = queue.peekFirst();
        if (entry.firstShownMs >= 0L) return;
        entry.firstShownMs = clock.nowMs();
        scheduleExpiryLocked(registration, entry, clock.nowMs());
        postShow(registration, entry, true);
    }

    private void scheduleExpiryLocked(Registration registration, Entry entry, long nowMs) {
        long deadline = Math.min(nowMs + EXPOSURE_MS, entry.firstShownMs + HARD_EXPOSURE_MS);
        long generation = ++presentationGeneration;
        scheduler.postDelayed(() -> expire(registration, entry, generation), deadline - nowMs);
    }

    private void expire(Registration registration, Entry entry, long generation) {
        synchronized (this) {
            if (activeHost != registration || generation != presentationGeneration
                    || queue.peekFirst() != entry) return;
            queue.removeFirst();
            registration.generation++;
            presentationGeneration++;
            postHide(registration, false);
            presentNextLocked();
        }
    }

    private void postShow(Registration registration, Entry entry, boolean announce) {
        long hostGeneration = registration.generation;
        long generation = presentationGeneration;
        ToastModel model = entry.model();
        scheduler.post(() -> {
            synchronized (DiscordDmNotificationCoordinator.this) {
                if (activeHost != registration || registration.generation != hostGeneration
                        || presentationGeneration != generation || queue.peekFirst() != entry
                        || !hostEligible(registration) || registration.presentationBlocked) return;
                playCueLocked(entry, announce);
            }
            registration.host.showDiscordDmToast(model, announce);
        });
    }

    private void postHide(Registration registration, boolean immediate) {
        long hostGeneration = registration.generation;
        scheduler.post(() -> {
            synchronized (DiscordDmNotificationCoordinator.this) {
                if (registration.generation != hostGeneration) return;
            }
            if (immediate) registration.host.hideDiscordDmToastImmediately();
            else registration.host.hideDiscordDmToast();
        });
    }

    private void playCueLocked(Entry entry, boolean announce) {
        if (!notificationsEnabled || !announce || entry.neutral || cuePlayer == null) return;
        long now = clock.nowMs();
        if (cuePlayed && now - lastCueAtMs < CUE_COOLDOWN_MS) return;
        try {
            if (!cuePlayer.play()) return;
        } catch (RuntimeException ignored) {
            return;
        }
        cuePlayed = true;
        lastCueAtMs = clock.nowMs();
    }

    private void clearPresentationLocked(Registration registration) {
        queue.clear();
        clearQuickActionLocked();
        registration.generation++;
        presentationGeneration++;
        postHide(registration, true);
    }

    private long currentQuickActionLocked() {
        if (quickActionPeerId > 0L && clock.nowMs() < quickActionExpiresAtMs) {
            return quickActionPeerId;
        }
        clearQuickActionLocked();
        return 0L;
    }

    private void clearQuickActionLocked() {
        quickActionPeerId = 0L;
        quickActionExpiresAtMs = 0L;
    }

    private static Registration registration(HostToken token) {
        return token == null ? null : token.registration;
    }

    private boolean hostEligible(Registration registration) {
        return registration.eligible() && socialConnected && !socialAuthorizationActive;
    }

    private static long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0L ? parsed : 0L;
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    static String sanitize(String value, int maxCodePoints) {
        if (value == null || maxCodePoints <= 0) return "";
        StringBuilder result = new StringBuilder();
        boolean pendingSpace = false;
        int count = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (type == Character.CONTROL || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.SURROGATE || type == Character.UNASSIGNED) {
                if (Character.isWhitespace(codePoint)) pendingSpace = result.length() > 0;
                continue;
            }
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = result.length() > 0;
                continue;
            }
            if (pendingSpace && count < maxCodePoints) {
                result.append(' ');
                count++;
            }
            pendingSpace = false;
            if (count >= maxCodePoints) {
                int keep = Math.max(0, maxCodePoints - 1);
                if (count > keep) result.setLength(result.offsetByCodePoints(0, keep));
                result.append('\u2026');
                break;
            }
            result.appendCodePoint(codePoint);
            count++;
        }
        return result.toString();
    }

    // Narrow test seam: event filtering uses the same queue path without mutating SDK snapshots.
    void acceptForTest(long messageId, long selfId, long authorId, long recipientId,
                       boolean connected, String senderName, String avatarUrl, String content,
                       boolean mediaOrDisclosure, boolean knownPeer,
                       DiscordSocialClient.MessageEvent.Type type) {
        acceptForTest(messageId, selfId, authorId, recipientId, connected, senderName, avatarUrl,
                content, mediaOrDisclosure, knownPeer, true, type);
    }

    void acceptForTest(long messageId, long selfId, long authorId, long recipientId,
                       boolean connected, String senderName, String avatarUrl, String content,
                       boolean mediaOrDisclosure, boolean knownPeer,
                       boolean directMessagesAvailable,
                       DiscordSocialClient.MessageEvent.Type type) {
        if (type != DiscordSocialClient.MessageEvent.Type.CREATED || !connected || selfId <= 0L
                || authorId == selfId) return;
        long peerId = DiscordSocialClient.directMessagePeer(selfId, authorId, recipientId);
        if (peerId <= 0L) return;
        String sender = knownPeer ? sanitize(senderName, MAX_SENDER_CODE_POINTS) : "Discord";
        if (sender.isEmpty()) sender = "Discord";
        String safeContent = sanitize(content, MAX_SNIPPET_CODE_POINTS);
        boolean neutral = mediaOrDisclosure || safeContent.isEmpty() || URL.matcher(safeContent).find();
        if (neutral) safeContent = "";
        accept(new IncomingMessage(messageId, peerId, sender,
                knownPeer ? avatarUrl : "", safeContent, neutral,
                knownPeer && directMessagesAvailable));
    }

    synchronized int queuedCountForTest() {
        return queue.size();
    }

    synchronized long[] queuedPeersForTest() {
        long[] peers = new long[queue.size()];
        int index = 0;
        for (Entry entry : queue) peers[index++] = entry.peerId;
        return peers;
    }
}
