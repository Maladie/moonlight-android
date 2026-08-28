package com.limelight.console;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.discord.DiscordSocialClient;
import com.limelight.gateway.GatewayConnection;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.overlay.DiscordGatewayClient;
import com.limelight.ui.overlay.OverlayMenuView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Discord state polling and Community projection for the active stream host only. */
public final class DiscordOverlayController {
    private static final String PREFS = "discord_overlay_state";
    private static final String DOCK_KEY_PREFIX = "dock_enabled.";
    private static final long REFRESH_MS = 2_000L;
    private static final long DM_REFRESH_MS = 500L;
    private static final long LEASE_RETRY_MS = 500L;
    private static final int LEASE_RETRY_LIMIT = 3;
    private static final long VOICE_RECONCILE_MS = 350L;
    private static final int VOICE_RECONCILE_LIMIT = 3;

    private final Activity activity;
    private final OverlayMenuView overlay;
    private final LinearLayout dock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean refreshInFlight = new AtomicBoolean();
    private final AtomicBoolean actionInFlight = new AtomicBoolean();
    // Keep the proven overlay voice controls separate from Community browsing.
    private final DiscordGatewayClient client = new DiscordGatewayClient();
    private final HostGatewayClient gatewayClient = new HostGatewayClient();
    private final HostGatewayStore store;
    private final String hostUuid;
    private final boolean socialAvailable;
    private final Runnable scheduledRefresh = () -> refresh(false);
    private final Runnable scheduledDmRefresh = this::refreshDirectMessages;
    private final DiscordDirectMessageState directMessages = new DiscordDirectMessageState();
    private final LinkedHashMap<Long, String> directMessageDrafts = new LinkedHashMap<>(16, .75f, true);
    private final Map<Long, Long> directMessageHistoryRequests = new HashMap<>();
    private final Map<Long, Boolean> directMessageHistoryFailures = new HashMap<>();
    private final Map<Long, SentDraft> directMessageSentDrafts = new HashMap<>();

    private GatewayConnection connection;
    private DiscordGatewayClient.VoiceState voice;
    private DiscordGatewayClient.ChannelTarget recentChannel;
    private HostGatewayClient.DiscordVoice communityVoiceOverride;
    private boolean expectedVoiceConnected;
    private String expectedVoiceChannelId = "";
    private int voiceReconcileAttempts;
    private boolean pendingForceRefresh;
    private boolean dockEnabled;
    private boolean destroyed;
    private boolean overlayVisible;
    private boolean communityActive;
    private boolean communityWasActiveBeforePause;
    private boolean paused;
    private int communityGeneration;
    private long communityRevision;
    private OverlayMenuView.CommunitySection communitySection = OverlayMenuView.CommunitySection.TOGETHER;
    private HostGatewayClient.DiscordHome home;
    private boolean homeLoading;
    private boolean homeFailed;
    private boolean homeLoadInFlight;
    private String selectedGuildId = "";
    private List<HostGatewayClient.DiscordChannel> guildChannels = Collections.emptyList();
    private boolean guildLoading;
    private boolean guildLoadInFlight;
    // Detail-only failures never change the Home/Together gateway projection.
    private boolean guildFailed;
    private boolean joinFailed;
    private boolean communityActionInFlight;
    private int guildGeneration;
    private int joinGeneration;
    private String chatFriendId = "";
    private boolean chatUnavailable;
    private boolean chatLeaseRetrying;
    private int chatGeneration;
    private int leaseRetryAttempts;
    private Runnable scheduledLeaseRetry;
    private DiscordSocialClient.MessageEventLease messageEventLease;
    private boolean authorizeAfterOverlayClose;
    private String lastCommunitySignature;

    private static final class SentDraft {
        final long requestId;
        final String value;

        SentDraft(long requestId, String value) {
            this.requestId = requestId;
            this.value = value;
        }
    }

    public DiscordOverlayController(Activity activity, OverlayMenuView overlay,
                                    LinearLayout dock, PreferenceConfiguration preferences,
                                    String hostUuid, String activeHost) {
        this.activity = activity;
        this.overlay = overlay;
        this.dock = dock;
        this.hostUuid = hostUuid == null ? "" : hostUuid;
        store = new HostGatewayStore(activity);
        GatewayConnection stored = store.loadForHost(hostUuid, activeHost);
        boolean enabled = stored != null && store.isDiscordEnabled(this.hostUuid, stored.profileId());
        connection = enabled ? stored : null;
        SharedPreferences state = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        dockEnabled = state.getBoolean(DOCK_KEY_PREFIX + this.hostUuid, false);
        overlay.setDiscordConfigured(connection != null);
        socialAvailable = DiscordSocialClient.isAvailable();
        overlay.setDiscordSocialAvailable(socialAvailable);
        refreshSocialSnapshot();
        overlay.setDiscordDocked(dockEnabled);
        overlay.setDiscordShortcuts(preferences.discordMuteShortcut, preferences.discordLeaveShortcut);
        renderDock();
        if (enabled) prepareDiscord(stored);
    }

    public void onOverlayShown() {
        overlayVisible = true;
        paused = false;
        refresh(true);
    }

    public void onOverlayClosed() {
        overlayVisible = false;
        communityWasActiveBeforePause = false;
        deactivateCommunity();
        renderDock();
        schedule();
        if (shouldStartDirectMessageAuthorization(true, authorizeAfterOverlayClose)) {
            authorizeAfterOverlayClose = false;
            try { DiscordSocialClient.authorizeForDirectMessages(activity); }
            catch (Exception | LinkageError ignored) {
                Toast.makeText(activity, R.string.discord_social_state_unavailable, Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void onActivityPaused() {
        communityWasActiveBeforePause = communityActive && overlayVisible
                && overlay.getVisibility() == View.VISIBLE;
        // Game lifecycle reaches this on the UI thread. Collapse the View's chat mode before
        // releasing the lease so a resumed overlay returns to the Friends root, never a composer.
        overlay.exitDiscordCommunityChatForPause();
        communitySection = OverlayMenuView.CommunitySection.FRIENDS;
        lastCommunitySignature = null;
        paused = true;
        deactivateCommunity();
    }

    public void onActivityResumed() {
        paused = false;
        boolean restore = shouldRestoreCommunityAfterPause(communityWasActiveBeforePause, overlayVisible,
                overlay.getVisibility() == View.VISIBLE);
        communityWasActiveBeforePause = false;
        if (!restore) return;
        // Resuming restores the Community shell/lease only. Chat is deliberately never reopened.
        activateCommunity(communitySection);
    }

    public void toggleMute() {
        if (voice == null || !voice.connected) { unavailable(R.string.overlay_discord_unavailable); return; }
        runAction(() -> client.toggleMute(connection));
    }

    public void leave() {
        if (voice == null || !voice.connected) { unavailable(R.string.overlay_discord_unavailable); return; }
        recentChannel = new DiscordGatewayClient.ChannelTarget(voice.channelId, voice.channelName,
                voice.guildId, "Discord");
        runAction(() -> client.leaveVoice(connection), () -> {
            communityVoiceOverride = new HostGatewayClient.DiscordVoice(false, "", "", "", false,
                    false, 0);
            expectVoice(false, "");
            renderCommunity();
        });
    }

    public void rejoin() {
        if (recentChannel == null) { unavailable(R.string.overlay_discord_no_recent_channel); return; }
        runAction(() -> client.joinChannel(connection, recentChannel));
    }

    public void toggleDock() {
        dockEnabled = !dockEnabled;
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(DOCK_KEY_PREFIX + hostUuid, dockEnabled).apply();
        overlay.setDiscordDocked(dockEnabled);
        renderDock();
        if (dockEnabled) refresh(true); else schedule();
    }

    public void toggleSocialFriends() { overlay.toggleDiscordSocialFriends(); }

    public void onDiscordCommunitySectionChanged(OverlayMenuView.CommunitySection section) {
        if (section != communitySection) {
            invalidateGuildAndJoinRequests();
            cancelLeaseRetry();
            chatGeneration++;
            // The visible panel changed, so project its current data even when the previous
            // panel's presentation was identical.
            lastCommunitySignature = null;
        }
        activateCommunity(section);
    }

    /** Called by the View exactly once when its Community tile opens the default Together section. */
    public void onDiscordCommunityOpened() {
        activateCommunity(OverlayMenuView.CommunitySection.TOGETHER);
    }

    private void activateCommunity(OverlayMenuView.CommunitySection section) {
        if (!shouldActivateCommunity(destroyed, paused, overlayVisible)
                || overlay.getVisibility() != View.VISIBLE) return;
        if (!communityActive) lastCommunitySignature = null;
        communityActive = true;
        communitySection = section == null ? OverlayMenuView.CommunitySection.TOGETHER : section;
        ensureMessageEventLease();
        if (communitySection == OverlayMenuView.CommunitySection.CHANNELS) loadHomeIfNeeded();
        renderCommunity();
    }

    static boolean shouldActivateCommunity(boolean destroyed, boolean paused, boolean overlayVisible) {
        return !destroyed && !paused && overlayVisible;
    }

    static boolean shouldRestoreCommunityAfterPause(boolean wasActiveBeforePause,
                                                    boolean overlayVisible, boolean overlayStillVisible) {
        return wasActiveBeforePause && overlayVisible && overlayStillVisible;
    }

    static boolean shouldShowChatUnavailable(boolean capabilityAvailable, boolean leaseAcquired) {
        return !capabilityAvailable || !leaseAcquired;
    }

    static boolean shouldStartDirectMessageAuthorization(boolean closeObserved, boolean pendingAuthorization) {
        return closeObserved && pendingAuthorization;
    }

    public void onDiscordCommunityLoadGuild(String guildId) {
        if (!canStartGuildLoad(isCommunityVisible(), guildLoadInFlight)) return;
        HostGatewayClient.DiscordGuild guild = findGuild(guildId);
        if (guild == null) return;
        invalidateGuildAndJoinRequests();
        selectedGuildId = guild.id;
        guildChannels = Collections.emptyList(); guildLoading = true; guildLoadInFlight = true; guildFailed = false;
        final GatewayConnection captured = connection;
        final int generation = communityGeneration;
        final int guildRequest = guildGeneration;
        renderCommunity();
        executor.execute(() -> {
            List<HostGatewayClient.DiscordChannel> result = null;
            try { result = gatewayClient.getDiscordChannels(captured, guild, false); } catch (Exception ignored) { }
            final List<HostGatewayClient.DiscordChannel> finalResult = result;
            handler.post(() -> {
                if (!acceptsGuildResult(captured, generation, guildRequest)) return;
                guildLoading = false; guildLoadInFlight = false; guildFailed = finalResult == null;
                guildChannels = finalResult == null ? Collections.emptyList() : finalResult;
                if (finalResult == null) Toast.makeText(activity, R.string.overlay_discord_unavailable,
                        Toast.LENGTH_SHORT).show();
                renderCommunity();
            });
        });
    }

    public void onDiscordCommunityJoinChannel(String channelId) {
        if (!isCommunityVisible() || communityActionInFlight) return;
        HostGatewayClient.DiscordChannel channel = findKnownChannel(channelId);
        if (channel == null || connection == null) return;
        communityActionInFlight = true;
        joinFailed = false;
        joinGeneration++;
        final GatewayConnection captured = connection;
        final int generation = communityGeneration;
        final int guildRequest = guildGeneration;
        final int joinRequest = joinGeneration;
        renderCommunity();
        executor.execute(() -> {
            HostGatewayClient.DiscordVoice verified = null;
            try {
                gatewayClient.joinDiscordChannel(captured, channel);
                verified = verifyJoinedChannel(captured, channel);
                if (verified != null) store.saveLastDiscordChannel(hostUuid, captured.profileId(), channel.id,
                        channel.guildId, channel.guildName, channel.name);
            } catch (Exception ignored) { }
            final HostGatewayClient.DiscordVoice finalVerified = verified;
            final boolean joined = finalVerified != null;
            handler.post(() -> {
                if (!acceptsJoinResult(captured, generation, guildRequest, joinRequest)) return;
                communityActionInFlight = false;
                if (!joined) {
                    joinFailed = true;
                    Toast.makeText(activity, R.string.discord_join_not_confirmed, Toast.LENGTH_SHORT).show();
                } else {
                    communityVoiceOverride = finalVerified;
                    expectVoice(true, channel.id);
                }
                renderCommunity();
                refresh(true);
            });
        });
    }

    public void onDiscordCommunityOpenFriendChat(String friendId) {
        if (!isCommunityVisible() || safeLong(friendId) <= 0 || findFriend(friendId) == null) return;
        cancelLeaseRetry();
        chatGeneration++;
        chatFriendId = friendId;
        boolean directMessagesAvailable = DiscordSocialClient.canUseDirectMessages();
        boolean acquiredLease = directMessagesAvailable && ensureMessageEventLease();
        chatUnavailable = shouldShowChatUnavailable(directMessagesAvailable, acquiredLease);
        chatLeaseRetrying = directMessagesAvailable && !acquiredLease;
        directMessages.setVisibleRecipient(safeLong(chatFriendId));
        updateShowingChat();
        if (!chatUnavailable) requestDirectMessageHistory(safeLong(chatFriendId));
        else if (chatLeaseRetrying) scheduleLeaseRetry(communityGeneration, chatGeneration, chatFriendId);
        renderCommunity();
    }

    public void onDiscordCommunityChatDraftChanged(String friendId, String draft) {
        if (!friendId.equals(chatFriendId)) return;
        setDirectMessageDraft(safeLong(friendId), draft);
        // The View's composer is the visual source while typing. Rendering here would
        // recreate the whole chat tree (and its scroll position) for every character.
    }

    public void onDiscordCommunitySendChat(String friendId, String draft) {
        long recipientId = safeLong(friendId);
        String content = draft == null ? "" : draft;
        if (!friendId.equals(chatFriendId) || messageEventLease == null || content.trim().isEmpty()
                || content.length() > 2000 || !DiscordSocialClient.canUseDirectMessages()) return;
        long requestId = DiscordSocialClient.nextDirectMessageRequestId();
        if (!directMessages.beginSend(recipientId, requestId)) return;
        directMessageSentDrafts.put(recipientId, new SentDraft(requestId, content));
        DiscordSocialClient.sendUserMessage(recipientId, requestId, content);
        // Sending is excluded from normal poll signatures, so deliberately project it once.
        lastCommunitySignature = null;
        renderCommunity();
    }

    public void onDiscordCommunityOpenMessageInDiscord(String messageId) {
        long id = safeLong(messageId);
        if (id > 0 && messageEventLease != null) DiscordSocialClient.openMessageInDiscord(id);
    }

    public void onDiscordCommunityBackToFriends() {
        cancelLeaseRetry();
        chatGeneration++;
        chatFriendId = ""; chatUnavailable = false; chatLeaseRetrying = false;
        directMessages.setVisibleRecipient(0);
        updateShowingChat();
        lastCommunitySignature = null;
        renderCommunity();
    }

    /** Releases DM ownership before the overlay hides; auth starts only from onOverlayClosed(). */
    public void authorizeDirectMessages() {
        if (destroyed) return;
        authorizeAfterOverlayClose = true;
        deactivateCommunity();
        overlay.closeMenu();
    }

    public void destroy() {
        destroyed = true;
        handler.removeCallbacks(scheduledRefresh); handler.removeCallbacks(scheduledDmRefresh);
        cancelLeaseRetry();
        deactivateCommunity();
        executor.shutdownNow();
    }

    private void refresh(boolean force) {
        handler.removeCallbacks(scheduledRefresh);
        if (destroyed) return;
        refreshSocialSnapshot();
        if (connection == null) {
            if (communityActive) renderCommunity();
            schedule();
            return;
        }
        if (!refreshInFlight.compareAndSet(false, true)) {
            if (force) pendingForceRefresh = true;
            if (communityActive) renderCommunity();
            schedule();
            return;
        }
        if (!communityActive) overlay.setDiscordState(voice, null, true);
        GatewayConnection current = connection;
        executor.execute(() -> {
            DiscordGatewayClient.VoiceState result = null;
            DiscordGatewayClient.ChannelTarget recent = recentChannel;
            String error = null;
            try {
                result = client.getVoice(current, force);
                if (result.connected && !result.channelId.isEmpty() && !result.guildId.isEmpty()) {
                    recent = new DiscordGatewayClient.ChannelTarget(result.channelId, result.channelName,
                            result.guildId, "Discord");
                } else if (recent == null) {
                    HostGatewayStore.DiscordChannelSelection saved = store.loadLastDiscordChannel(hostUuid, currentProfile());
                    if (saved != null) recent = new DiscordGatewayClient.ChannelTarget(saved.channelId,
                            saved.channelName, saved.guildId, saved.guildName);
                    else recent = client.getRecentChannel(current);
                }
            } catch (Exception failure) { error = failure.getMessage(); }
            final DiscordGatewayClient.VoiceState finalResult = result;
            final DiscordGatewayClient.ChannelTarget finalRecent = recent;
            final String finalError = error;
            final boolean finalForce = force;
            handler.post(() -> {
                refreshInFlight.set(false);
                if (destroyed || current != connection) return;
                if (finalResult != null) voice = finalResult;
                if (finalRecent != null) recentChannel = finalRecent;
                reconcileExpectedVoice(finalForce, finalResult);
                boolean connected = effectiveVoiceConnected(communityVoiceOverride, voice);
                overlay.setDiscordRejoinTarget(!connected && recentChannel != null,
                        recentChannel == null ? "" : recentChannel.channelName);
                if (overlay.getVisibility() == View.VISIBLE) {
                    overlay.setDiscordState(voice, finalError, false);
                }
                if (communityActive) renderCommunity();
                renderDock();
                if (pendingForceRefresh) {
                    pendingForceRefresh = false;
                    refresh(true);
                } else schedule();
            });
        });
    }

    private void expectVoice(boolean connected, String channelId) {
        expectedVoiceConnected = connected;
        expectedVoiceChannelId = channelId == null ? "" : channelId;
        voiceReconcileAttempts = 0;
    }

    private void reconcileExpectedVoice(boolean fresh, DiscordGatewayClient.VoiceState result) {
        if (communityVoiceOverride == null || !fresh) return;
        if (legacyVoiceMatchesExpected(expectedVoiceConnected, expectedVoiceChannelId, result)) {
            communityVoiceOverride = null;
            expectedVoiceChannelId = "";
            voiceReconcileAttempts = 0;
            return;
        }
        voiceReconcileAttempts++;
        if (shouldRetryVoiceReconciliation(voiceReconcileAttempts, VOICE_RECONCILE_LIMIT)) {
            handler.postDelayed(() -> refresh(true), VOICE_RECONCILE_MS);
            return;
        }
        communityVoiceOverride = null;
        expectedVoiceChannelId = "";
        Toast.makeText(activity, R.string.overlay_discord_unavailable, Toast.LENGTH_SHORT).show();
    }

    static boolean legacyVoiceMatchesExpected(boolean expectedConnected, String expectedChannelId,
                                               DiscordGatewayClient.VoiceState value) {
        if (value == null || value.connected != expectedConnected) return false;
        return !expectedConnected || (expectedChannelId != null && expectedChannelId.equals(value.channelId));
    }

    static boolean shouldRetryVoiceReconciliation(int attempts, int limit) {
        return attempts > 0 && attempts < limit;
    }

    static boolean effectiveVoiceConnected(HostGatewayClient.DiscordVoice override,
                                           DiscordGatewayClient.VoiceState legacyVoice) {
        return override != null ? override.connected : legacyVoice != null && legacyVoice.connected;
    }

    private void loadHomeIfNeeded() {
        if (connection == null || home != null || homeLoadInFlight) return;
        homeLoading = true; homeFailed = false; homeLoadInFlight = true;
        final GatewayConnection captured = connection;
        final int generation = communityGeneration;
        executor.execute(() -> {
            HostGatewayClient.DiscordHome value = null;
            try { value = loadCommunityHome(captured); } catch (Exception ignored) { }
            final HostGatewayClient.DiscordHome finalValue = value;
            handler.post(() -> {
                if (!acceptsCommunityResult(captured, generation)) return;
                homeLoadInFlight = false; homeLoading = false; homeFailed = finalValue == null;
                if (finalValue != null) home = finalValue;
                renderCommunity();
            });
        });
    }

    private HostGatewayClient.DiscordHome loadCommunityHome(GatewayConnection captured) throws Exception {
        try { return gatewayClient.getDiscordHome(captured, false); }
        catch (Exception firstFailure) {
            HostGatewayClient.DiscordStatus status = gatewayClient.getDiscordStatus(captured);
            if (!shouldRetryCommunityHome(false, status)) throw firstFailure;
            Thread.sleep(350L);
            return gatewayClient.getDiscordHome(captured, true);
        }
    }

    static boolean shouldRetryCommunityHome(boolean alreadyRetried, HostGatewayClient.DiscordStatus status) {
        return !alreadyRetried && status != null && status.bridgeOnline && status.rpcConnected && status.authenticated;
    }

    private HostGatewayClient.DiscordVoice verifyJoinedChannel(GatewayConnection captured,
                                                                 HostGatewayClient.DiscordChannel channel) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            HostGatewayClient.DiscordVoice value = gatewayClient.getDiscordVoice(captured, true);
            if (isVerifiedCommunityJoin(channel.id, value)) return value;
            if (attempt < 2) Thread.sleep(350L);
        }
        return null;
    }

    static boolean isVerifiedCommunityJoin(String channelId, HostGatewayClient.DiscordVoice voice) {
        return DiscordPanelController.matchesJoinedVoice(channelId, voice);
    }

    private boolean acceptsCommunityResult(GatewayConnection captured, int generation) {
        return acceptsCommunityGeneration(destroyed, paused, communityActive, overlayVisible,
                captured == connection, generation, communityGeneration)
                && overlay.getVisibility() == View.VISIBLE;
    }

    private boolean acceptsGuildResult(GatewayConnection captured, int generation, int guildRequest) {
        return acceptsCommunityResult(captured, generation) && guildRequest == guildGeneration;
    }

    private boolean acceptsJoinResult(GatewayConnection captured, int generation, int guildRequest,
                                      int joinRequest) {
        return acceptsGuildResult(captured, generation, guildRequest)
                && acceptsGuildGeneration(guildRequest, guildGeneration, joinRequest, joinGeneration);
    }

    private void invalidateGuildAndJoinRequests() {
        guildGeneration++;
        joinGeneration++;
        guildLoadInFlight = false;
        communityActionInFlight = false;
    }

    /** Called when the View leaves a selected guild back to the Channels root. */
    public void onDiscordCommunityBackToChannels() {
        invalidateGuildAndJoinRequests();
        guildLoading = false;
        lastCommunitySignature = null;
        renderCommunity();
    }

    static boolean acceptsCommunityGeneration(boolean destroyed, boolean paused, boolean active,
                                               boolean overlayVisible, boolean sameConnection,
                                               int expectedGeneration, int currentGeneration) {
        return !destroyed && !paused && active && overlayVisible && sameConnection
                && expectedGeneration == currentGeneration;
    }

    static boolean canStartGuildLoad(boolean communityVisible, boolean inFlight) {
        return communityVisible && !inFlight;
    }

    static boolean acceptsGuildGeneration(int expectedGuildGeneration, int currentGuildGeneration,
                                          int expectedJoinGeneration, int currentJoinGeneration) {
        return expectedGuildGeneration == currentGuildGeneration
                && expectedJoinGeneration == currentJoinGeneration;
    }

    static boolean shouldClearDraftAfterSendResult(boolean accepted, boolean successful,
                                                    String currentDraft, String sentDraft) {
        return accepted && successful && (currentDraft == null ? "" : currentDraft)
                .equals(sentDraft == null ? "" : sentDraft);
    }

    static boolean shouldRenderHistoryFailure(boolean accepted, boolean successful) {
        return accepted && !successful;
    }

    static boolean shouldShowOpenMessageFailure(boolean successful) {
        return !successful;
    }

    private boolean isCommunityVisible() {
        return !destroyed && !paused && communityActive && overlayVisible && overlay.getVisibility() == View.VISIBLE;
    }

    private void deactivateCommunity() {
        boolean wasCommunityActive = communityActive;
        cancelLeaseRetry();
        communityActive = false; communityGeneration++; invalidateGuildAndJoinRequests();
        homeLoadInFlight = false; chatFriendId = ""; chatGeneration++; chatLeaseRetrying = false;
        directMessages.setVisibleRecipient(0); directMessageHistoryRequests.clear();
        handler.removeCallbacks(scheduledDmRefresh);
        if (messageEventLease != null) {
            DiscordSocialClient.releaseMessageEventLease(messageEventLease);
            messageEventLease = null;
        }
        if (wasCommunityActive && overlay.getVisibility() == View.VISIBLE) syncLegacyOverlay();
    }

    private boolean ensureMessageEventLease() {
        if (messageEventLease != null) return true;
        if (!isCommunityVisible()) return false;
        messageEventLease = DiscordSocialClient.tryAcquireMessageEventLease();
        if (messageEventLease != null) {
            updateShowingChat(); handler.removeCallbacks(scheduledDmRefresh);
            handler.postDelayed(scheduledDmRefresh, DM_REFRESH_MS);
        }
        return messageEventLease != null;
    }

    private void scheduleLeaseRetry(final int communityRequest, final int chatRequest,
                                    final String friendId) {
        if (!shouldRetryLease(leaseRetryAttempts, LEASE_RETRY_LIMIT)) {
            chatLeaseRetrying = false;
            renderCommunity();
            return;
        }
        final Runnable retry = () -> {
            if (!isCurrentLeaseRetry(communityRequest, chatRequest, friendId)) return;
            leaseRetryAttempts++;
            if (ensureMessageEventLease()) {
                chatUnavailable = false;
                chatLeaseRetrying = false;
                updateShowingChat();
                requestDirectMessageHistory(safeLong(friendId));
                renderCommunity();
                return;
            }
            if (shouldRetryLease(leaseRetryAttempts, LEASE_RETRY_LIMIT)) {
                scheduleLeaseRetry(communityRequest, chatRequest, friendId);
            } else {
                chatLeaseRetrying = false;
                renderCommunity();
            }
        };
        scheduledLeaseRetry = retry;
        handler.postDelayed(retry, LEASE_RETRY_MS);
    }

    private boolean isCurrentLeaseRetry(int communityRequest, int chatRequest, String friendId) {
        return isCommunityVisible() && messageEventLease == null && chatUnavailable
                && chatLeaseRetrying && DiscordSocialClient.canUseDirectMessages()
                && communityRequest == communityGeneration && chatRequest == chatGeneration
                && friendId.equals(chatFriendId);
    }

    private void cancelLeaseRetry() {
        if (scheduledLeaseRetry != null) handler.removeCallbacks(scheduledLeaseRetry);
        scheduledLeaseRetry = null;
        leaseRetryAttempts = 0;
    }

    static boolean shouldRetryLease(int attempts, int limit) {
        return attempts >= 0 && attempts < limit;
    }

    private void refreshDirectMessages() {
        if (!isCommunityVisible() || messageEventLease == null) return;
        if (consumeDirectMessageEvents(DiscordSocialClient.getSnapshot())) renderCommunity();
        handler.postDelayed(scheduledDmRefresh, DM_REFRESH_MS);
    }

    private boolean consumeDirectMessageEvents(DiscordSocialClient.Snapshot snapshot) {
        boolean changed = false;
        long currentUser = safeLong(snapshot.userId);
        for (DiscordSocialClient.MessageEvent event : DiscordSocialClient.drainMessageEvents(messageEventLease)) {
            switch (event.type) {
                case OVERFLOW:
                    if (!chatFriendId.isEmpty()) requestDirectMessageHistory(safeLong(chatFriendId));
                    changed = true; break;
                case HISTORY_BEGIN: directMessages.beginHistory(event.recipientId, event.requestId); changed = true; break;
                case HISTORY_MESSAGE:
                    if (directMessages.acceptsHistory(event.recipientId, event.requestId)) {
                        directMessages.upsertHistory(event.recipientId, event); changed = true;
                    }
                    break;
                case CREATED:
                    directMessages.upsert(event.recipientId, event);
                    directMessages.markUnreadIfIncoming(event.recipientId, currentUser, event.authorId, false);
                    changed = true; break;
                case UPDATED: directMessages.upsert(event.recipientId, event); changed = true; break;
                case DELETED: directMessages.delete(event.messageId); changed = true; break;
                case HISTORY_RESULT:
                    Long request = directMessageHistoryRequests.get(event.recipientId);
                    boolean acceptedHistory = request != null && request == event.requestId
                            && directMessages.acceptsHistory(event.recipientId, event.requestId);
                    directMessages.finishHistory(event.recipientId, event.requestId);
                    if (acceptedHistory) {
                        directMessageHistoryRequests.remove(event.recipientId);
                        if (event.successful) directMessageHistoryFailures.remove(event.recipientId);
                        else if (shouldRenderHistoryFailure(true, event.successful)) {
                            directMessageHistoryFailures.put(event.recipientId, event.retryable);
                        }
                    }
                    changed = true; break;
                case SEND_RESULT:
                    boolean acceptedSend = directMessages.finishSend(event.recipientId, event.requestId,
                            event.successful, event.retryable, event.retryAfterSeconds, event.errorType);
                    SentDraft sentDraft = directMessageSentDrafts.get(event.recipientId);
                    if (acceptedSend && sentDraft != null && sentDraft.requestId == event.requestId) {
                        directMessageSentDrafts.remove(event.recipientId);
                        if (shouldClearDraftAfterSendResult(true, event.successful,
                                directMessageDraft(event.recipientId), sentDraft.value)) {
                            setDirectMessageDraft(event.recipientId, "");
                        }
                    }
                    if (acceptedSend && event.recipientId == safeLong(chatFriendId)) {
                        // Draft and sending are deliberately excluded from the steady-state
                        // chat signature. A valid terminal result must still update the composer/error once.
                        lastCommunitySignature = null;
                        changed = true;
                    }
                    break;
                case OPEN_MESSAGE_RESULT:
                    if (shouldShowOpenMessageFailure(event.successful)) Toast.makeText(activity, R.string.discord_dm_open_failed,
                            Toast.LENGTH_SHORT).show();
                    changed = true; break;
            }
        }
        return changed;
    }

    private void requestDirectMessageHistory(long recipientId) {
        if (messageEventLease == null || recipientId <= 0 || !DiscordSocialClient.canUseDirectMessages()) return;
        long requestId = DiscordSocialClient.nextDirectMessageRequestId();
        directMessages.requestHistory(recipientId, requestId);
        directMessageHistoryRequests.put(recipientId, requestId);
        directMessageHistoryFailures.remove(recipientId);
        DiscordSocialClient.requestUserMessages(recipientId, requestId);
    }

    private void updateShowingChat() {
        if (messageEventLease != null) DiscordSocialClient.setShowingChat(messageEventLease,
                isCommunityVisible() && !chatFriendId.isEmpty());
    }

    private void renderCommunity() {
        if (!isCommunityVisible()) return;
        DiscordSocialClient.Snapshot snapshot = DiscordSocialClient.getSnapshot();
        OverlayMenuView.CommunityStatus gatewayStatus = connection == null ? OverlayMenuView.CommunityStatus.UNAVAILABLE
                : (homeLoading ? OverlayMenuView.CommunityStatus.LOADING : (homeFailed
                ? OverlayMenuView.CommunityStatus.ERROR : OverlayMenuView.CommunityStatus.READY));
        String gatewayMessage = gatewayStatus == OverlayMenuView.CommunityStatus.UNAVAILABLE
                ? activity.getString(R.string.overlay_discord_unavailable) : (gatewayStatus == OverlayMenuView.CommunityStatus.LOADING
                ? activity.getString(R.string.overlay_discord_loading) : (gatewayStatus == OverlayMenuView.CommunityStatus.ERROR
                ? activity.getString(R.string.discord_join_not_confirmed) : ""));
        OverlayMenuView.CommunityStatus socialStatus = !socialAvailable ? OverlayMenuView.CommunityStatus.UNAVAILABLE
                : (snapshot.connected ? OverlayMenuView.CommunityStatus.READY : (snapshot.authorizationRequired
                ? OverlayMenuView.CommunityStatus.UNAVAILABLE : OverlayMenuView.CommunityStatus.LOADING));
        String socialMessage = !socialAvailable ? activity.getString(R.string.discord_social_sdk_missing)
                : (snapshot.connected ? activity.getString(R.string.discord_social_state_connected)
                : (snapshot.authorizationRequired ? activity.getString(R.string.discord_social_state_authorization_required)
                : activity.getString(R.string.discord_social_state_connecting)));
        OverlayMenuView.CommunityModel model = new OverlayMenuView.CommunityModel(communityRevision + 1,
                gatewayStatus, gatewayMessage, socialStatus, socialMessage, projectVoice(snapshot), projectFriends(snapshot),
                projectChannels(home == null ? null : home.favorites), projectChannels(home == null ? null : home.recent),
                projectGuilds(), projectChannels(guildChannels), selectedGuildId,
                DiscordSocialClient.canUseDirectMessages(), projectChat(snapshot));
        String signature = communityPresentationSignature(model, communitySection, !chatFriendId.isEmpty());
        if (!shouldRenderCommunitySignature(lastCommunitySignature, signature)) return;
        lastCommunitySignature = signature;
        communityRevision++;
        overlay.setDiscordCommunityModel(model);
        if (!chatFriendId.isEmpty()) directMessages.clearUnreadAfterRendered(safeLong(chatFriendId));
    }

    static boolean shouldRenderCommunitySignature(String previous, String next) {
        return previous == null || !previous.equals(next);
    }

    static String communityPresentationSignature(OverlayMenuView.CommunityModel model,
                                                 OverlayMenuView.CommunitySection section,
                                                 boolean chatVisible) {
        StringBuilder value = new StringBuilder();
        if (chatVisible) {
            append(value, model.socialStatus.name()); append(value, model.socialMessage);
            append(value, model.directMessagesAvailable); appendChat(value, model.chat);
            return value.toString();
        }
        OverlayMenuView.CommunitySection visible = section == null
                ? OverlayMenuView.CommunitySection.TOGETHER : section;
        if (visible == OverlayMenuView.CommunitySection.TOGETHER) {
            append(value, model.gatewayStatus.name()); append(value, model.gatewayMessage);
            append(value, model.socialStatus.name()); append(value, model.socialMessage);
            appendVoice(value, model.voice);
        } else if (visible == OverlayMenuView.CommunitySection.FRIENDS) {
            append(value, model.socialStatus.name()); append(value, model.socialMessage);
            append(value, model.directMessagesAvailable); appendFriends(value, model.friends);
        } else {
            append(value, model.gatewayStatus.name()); append(value, model.gatewayMessage);
            appendChannels(value, model.favorites); appendChannels(value, model.recent);
            appendGuilds(value, model.guilds); appendChannels(value, model.guildChannels);
            append(value, model.selectedGuildId);
        }
        return value.toString();
    }

    private static void appendVoice(StringBuilder value, OverlayMenuView.VoiceSummary voice) {
        append(value, voice != null);
        if (voice == null) return;
        append(value, voice.connected); append(value, voice.guildName); append(value, voice.channelName);
        append(value, voice.muted); append(value, voice.deafened); append(value, voice.participants.size());
        for (OverlayMenuView.VoiceParticipant participant : voice.participants) {
            append(value, participant.id); append(value, participant.name); append(value, participant.avatarUrl);
            append(value, participant.volume);
            append(value, participant.muted); append(value, participant.speaking); append(value, participant.self);
        }
    }

    private static void appendFriends(StringBuilder value, List<OverlayMenuView.CommunityFriend> friends) {
        append(value, friends.size());
        for (OverlayMenuView.CommunityFriend friend : friends) {
            append(value, friend.id); append(value, friend.name); append(value, friend.activity);
            append(value, friend.avatarUrl); append(value, friend.presence.name()); append(value, friend.unread);
        }
    }

    private static void appendGuilds(StringBuilder value, List<OverlayMenuView.CommunityGuild> guilds) {
        append(value, guilds.size());
        for (OverlayMenuView.CommunityGuild guild : guilds) { append(value, guild.id); append(value, guild.name); }
    }

    private static void appendChannels(StringBuilder value, List<OverlayMenuView.CommunityChannel> channels) {
        append(value, channels.size());
        for (OverlayMenuView.CommunityChannel channel : channels) {
            append(value, channel.id); append(value, channel.guildId); append(value, channel.guildName);
            append(value, channel.name); append(value, channel.people); append(value, channel.favorite);
        }
    }

    private static void appendChat(StringBuilder value, OverlayMenuView.ChatModel chat) {
        append(value, chat != null);
        if (chat == null) return;
        append(value, chat.recipientId); append(value, chat.recipientName); append(value, chat.recipientAvatarUrl);
        // Draft text lives in the View while it is being edited, and sending is only an
        // intermediate state. Neither may trigger a full chat reconstruction on a poll.
        append(value, chat.loadingHistory);
        append(value, chat.error); append(value, chat.retryable); append(value, chat.retryMessage);
        append(value, chat.messages.size());
        for (OverlayMenuView.ChatMessage message : chat.messages) {
            append(value, message.id); append(value, message.content); append(value, message.additionalContentType);
            append(value, message.additionalContentTitle); append(value, message.additionalContentCount);
            append(value, message.self); append(value, message.disclosure);
        }
    }

    private static void append(StringBuilder value, String part) {
        String safe = part == null ? "" : part;
        value.append(safe.length()).append(':').append(safe);
    }

    private static void append(StringBuilder value, boolean part) { append(value, part ? "1" : "0"); }
    private static void append(StringBuilder value, int part) { append(value, Integer.toString(part)); }

    private OverlayMenuView.VoiceSummary projectVoice(DiscordSocialClient.Snapshot snapshot) {
        if (communityVoiceOverride != null) {
            return projectVerifiedVoice(communityVoiceOverride,
                    guildNameFor(communityVoiceOverride.guildId), snapshot);
        }
        if (voice == null) return null;
        List<OverlayMenuView.VoiceParticipant> participants = new ArrayList<>();
        for (DiscordGatewayClient.Participant value : voice.participants) participants.add(
                new OverlayMenuView.VoiceParticipant(value.id, value.name, value.volume, value.muted,
                        value.speaking, value.self, voiceParticipantAvatarUrl(value.id, value.self, snapshot)));
        return new OverlayMenuView.VoiceSummary(voice.connected, guildNameFor(voice.guildId), voice.channelName,
                voice.muted, voice.deafened, participants);
    }

    static OverlayMenuView.VoiceSummary projectVerifiedVoice(HostGatewayClient.DiscordVoice value,
                                                              String guildName) {
        return projectVerifiedVoice(value, guildName, null);
    }

    static OverlayMenuView.VoiceSummary projectVerifiedVoice(HostGatewayClient.DiscordVoice value,
                                                              String guildName, DiscordSocialClient.Snapshot snapshot) {
        if (value == null) return null;
        List<OverlayMenuView.VoiceParticipant> participants = new ArrayList<>();
        for (HostGatewayClient.DiscordParticipant participant : value.participantList) {
            participants.add(new OverlayMenuView.VoiceParticipant(participant.id, participant.name,
                    participant.volume, participant.muted, participant.speaking, participant.self,
                    participant.bot ? "" : voiceParticipantAvatarUrl(participant.id, participant.self, snapshot)));
        }
        return new OverlayMenuView.VoiceSummary(value.connected, guildName, value.channelName,
                value.muted, value.deafened, participants);
    }

    static String voiceParticipantAvatarUrl(String id, boolean self, String selfId, String selfAvatarUrl,
                                            List<DiscordSocialClient.Friend> friends) {
        if (self || (id != null && id.equals(selfId))) return selfAvatarUrl == null ? "" : selfAvatarUrl;
        if (friends != null) for (DiscordSocialClient.Friend friend : friends) {
            if (id != null && id.equals(friend.userId)) return friend.avatarUrl == null ? "" : friend.avatarUrl;
        }
        return "";
    }

    private static String voiceParticipantAvatarUrl(String id, boolean self, DiscordSocialClient.Snapshot snapshot) {
        if (snapshot == null) return "";
        return voiceParticipantAvatarUrl(id, self, snapshot.userId, snapshot.avatarUrl, snapshot.friendDetails);
    }

    private List<OverlayMenuView.CommunityFriend> projectFriends(DiscordSocialClient.Snapshot snapshot) {
        List<OverlayMenuView.CommunityFriend> result = new ArrayList<>();
        for (DiscordSocialClient.Friend friend : snapshot.friendDetails) result.add(
                new OverlayMenuView.CommunityFriend(friend.userId, friend.displayName, friend.activityName,
                        friend.avatarUrl, friendPresence(friend.group), directMessages.hasUnread(safeLong(friend.userId))));
        return result;
    }

    static OverlayMenuView.FriendPresence friendPresence(DiscordSocialClient.Friend.Group group) {
        if (group == DiscordSocialClient.Friend.Group.PLAYING) return OverlayMenuView.FriendPresence.PLAYING;
        return group == DiscordSocialClient.Friend.Group.ONLINE ? OverlayMenuView.FriendPresence.ONLINE
                : OverlayMenuView.FriendPresence.OFFLINE;
    }

    private List<OverlayMenuView.CommunityGuild> projectGuilds() {
        if (home == null) return Collections.emptyList();
        List<OverlayMenuView.CommunityGuild> result = new ArrayList<>();
        for (HostGatewayClient.DiscordGuild guild : home.guilds) result.add(new OverlayMenuView.CommunityGuild(guild.id, guild.name));
        return result;
    }

    private static List<OverlayMenuView.CommunityChannel> projectChannels(List<HostGatewayClient.DiscordChannel> values) {
        if (values == null) return Collections.emptyList();
        List<OverlayMenuView.CommunityChannel> result = new ArrayList<>();
        for (HostGatewayClient.DiscordChannel channel : values) result.add(new OverlayMenuView.CommunityChannel(channel.id,
                channel.guildId, channel.guildName, channel.name, channel.people, channel.favorite));
        return result;
    }

    private OverlayMenuView.ChatModel projectChat(DiscordSocialClient.Snapshot snapshot) {
        if (chatFriendId.isEmpty()) return null;
        long recipientId = safeLong(chatFriendId);
        DiscordSocialClient.Friend friend = findFriend(chatFriendId);
        List<OverlayMenuView.ChatMessage> messages = new ArrayList<>();
        for (DiscordDirectMessageState.Message message : directMessages.history(recipientId)) messages.add(
                new OverlayMenuView.ChatMessage(Long.toString(message.id), message.content, message.additionalContentType,
                        message.additionalContentTitle, message.additionalContentCount,
                        message.authorId == safeLong(snapshot.userId), message.disclosure));
        DiscordDirectMessageState.SendState send = directMessages.sendState(recipientId);
        boolean historyFailed = directMessageHistoryFailures.containsKey(recipientId);
        boolean historyRetryable = historyFailed && Boolean.TRUE.equals(
                directMessageHistoryFailures.get(recipientId));
        String error = chatUnavailable
                ? activity.getString(DiscordSocialClient.canUseDirectMessages()
                ? (chatLeaseRetrying ? R.string.overlay_discord_chat_retrying
                : R.string.overlay_discord_chat_in_use)
                : R.string.discord_social_state_unavailable)
                : (historyFailed ? activity.getString(R.string.overlay_discord_chat_history_failed) : send.errorType);
        return new OverlayMenuView.ChatModel(chatFriendId, friend == null ? "" : friend.displayName,
                friend == null ? "" : friend.avatarUrl, directMessageDraft(recipientId), messages,
                directMessageHistoryRequests.containsKey(recipientId), send.inFlight, error,
                historyFailed ? historyRetryable : send.retryable,
                historyFailed ? activity.getString(R.string.overlay_discord_chat_history_failed)
                        : (send.retryable ? send.errorType : ""));
    }

    private HostGatewayClient.DiscordGuild findGuild(String id) {
        if (home == null || id == null) return null;
        for (HostGatewayClient.DiscordGuild guild : home.guilds) if (id.equals(guild.id)) return guild;
        return null;
    }

    private HostGatewayClient.DiscordChannel findKnownChannel(String id) {
        if (id == null || id.isEmpty()) return null;
        HostGatewayClient.DiscordChannel value = findChannel(home == null ? null : home.favorites, id);
        if (value == null) value = findChannel(home == null ? null : home.recent, id);
        return value != null ? value : findChannel(guildChannels, id);
    }

    static HostGatewayClient.DiscordChannel findChannel(List<HostGatewayClient.DiscordChannel> values, String id) {
        if (values == null || id == null) return null;
        for (HostGatewayClient.DiscordChannel value : values) if (id.equals(value.id)) return value;
        return null;
    }

    private String guildNameFor(String guildId) {
        HostGatewayClient.DiscordGuild guild = findGuild(guildId);
        if (guild != null) return guild.name;
        HostGatewayClient.DiscordChannel channel = findKnownChannel(voice == null ? "" : voice.channelId);
        return channel != null && guildId.equals(channel.guildId) ? channel.guildName : "";
    }

    private DiscordSocialClient.Friend findFriend(String id) {
        for (DiscordSocialClient.Friend friend : DiscordSocialClient.getSnapshot().friendDetails)
            if (id.equals(friend.userId)) return friend;
        return null;
    }

    private String currentProfile() {
        GatewayConnection stored = store.load(hostUuid);
        return stored == null ? GatewayConnection.DEFAULT_PROFILE_ID : stored.profileId();
    }

    private void prepareDiscord(GatewayConnection stored) {
        if (stored == null) return;
        boolean autoConnect = store.isDiscordAutoConnectEnabled(hostUuid, stored.profileId());
        boolean autoJoin = store.isDiscordAutoJoinLastEnabled(hostUuid, stored.profileId());
        if (!autoConnect && !autoJoin) return;
        executor.execute(() -> {
            if (autoConnect) {
                try { gatewayClient.startDiscord(stored); } catch (Exception ignored) { }
                try { gatewayClient.connectDiscord(stored, false); } catch (Exception ignored) { }
            }
            if (autoJoin) {
                HostGatewayStore.DiscordChannelSelection saved = store.loadLastDiscordChannel(hostUuid, stored.profileId());
                if (saved != null) try {
                    DiscordGatewayClient.VoiceState current = client.getVoice(connection, true);
                    if (!current.connected) client.joinChannel(connection, new DiscordGatewayClient.ChannelTarget(
                            saved.channelId, saved.channelName, saved.guildId, saved.guildName));
                } catch (Exception ignored) { }
            }
            handler.post(() -> { if (!destroyed) refresh(true); });
        });
    }

    private void schedule() {
        handler.removeCallbacks(scheduledRefresh);
        if (!destroyed && ((connection != null && dockEnabled) || (overlay.getVisibility() == View.VISIBLE
                && (connection != null || socialAvailable)))) handler.postDelayed(scheduledRefresh, REFRESH_MS);
    }

    private void refreshSocialSnapshot() {
        if (socialAvailable && !communityActive) overlay.setDiscordSocialState(DiscordSocialClient.getSnapshot());
    }

    private void syncLegacyOverlay() {
        refreshSocialSnapshot();
        overlay.setDiscordState(voice, null, false);
    }
    private interface Action { void run() throws Exception; }
    private interface ActionSuccess { void run(); }

    private void runAction(Action action) { runAction(action, null); }

    private void runAction(Action action, ActionSuccess success) {
        if (connection == null || !actionInFlight.compareAndSet(false, true)) return;
        executor.execute(() -> {
            String error = null;
            try { action.run(); } catch (Exception failure) { error = failure.getMessage(); }
            final String finalError = error;
            handler.post(() -> {
                actionInFlight.set(false);
                if (destroyed) return;
                if (finalError != null && !finalError.isEmpty()) {
                    Toast.makeText(activity, finalError, Toast.LENGTH_LONG).show();
                } else if (success != null) {
                    success.run();
                }
                refresh(true);
            });
        });
    }

    private String directMessageDraft(long recipientId) { String value = directMessageDrafts.get(recipientId); return value == null ? "" : value; }
    private void setDirectMessageDraft(long recipientId, String draft) {
        if (recipientId <= 0) return;
        String value = draft == null ? "" : draft;
        if (value.isEmpty()) directMessageDrafts.remove(recipientId); else directMessageDrafts.put(recipientId, value);
        while (directMessageDrafts.size() > DiscordDirectMessageState.CONVERSATION_LIMIT) {
            Iterator<Long> iterator = directMessageDrafts.keySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next(); iterator.remove();
        }
    }
    static String directMessageDraftAfterSendResult(String draft, boolean successful) { return successful ? "" : (draft == null ? "" : draft); }
    private static long safeLong(String value) { try { return Long.parseLong(value); } catch (RuntimeException ignored) { return 0; } }
    private void unavailable(int stringId) { Toast.makeText(activity, stringId, Toast.LENGTH_SHORT).show(); }

    private void renderDock() {
        dock.removeAllViews();
        if (!dockEnabled || connection == null || overlay.getVisibility() == View.VISIBLE) { dock.setVisibility(View.GONE); return; }
        dock.setVisibility(View.VISIBLE);
        boolean connected = voice != null && voice.connected;
        dock.addView(line(connected ? "DISCORD  ·  " + voice.channelName : "DISCORD", 14, 0xFFB69CFF, true));
        if (!connected) { dock.addView(line(activity.getString(R.string.overlay_discord_disconnected), 13, 0xFFC5C8D3, false)); return; }
        int shown = 0;
        for (DiscordGatewayClient.Participant participant : voice.participants) {
            if (shown++ >= 6) break;
            dock.addView(line((participant.speaking ? "●  " : "   ") + participant.name + (participant.self ? "  ·  YOU" : ""),
                    13, participant.speaking ? 0xFF69F0AE : Color.WHITE, false));
        }
    }

    private TextView line(String value, float size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
}
