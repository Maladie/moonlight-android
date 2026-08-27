package com.limelight.console;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.discord.DiscordSocialClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Data and action coordinator for the dedicated Community presentation. */
final class DiscordSocialPanelController {
    interface CommunitySource {
        void requestHome(DiscordPanelController.CommunityHomeCallback callback);
        void requestGuildChannels(HostGatewayClient.DiscordGuild guild,
                                  DiscordPanelController.CommunityChannelsCallback callback);
        void joinChannel(HostGatewayClient.DiscordChannel channel,
                         DiscordPanelController.CommunityActionCallback callback);
        void requestVoice(DiscordPanelController.CommunityVoiceCallback callback);
        void voiceAction(DiscordPanelController.CommunityVoiceAction action,
                         DiscordPanelController.CommunityVoiceCallback callback);
        void requestOptions(boolean refresh, DiscordPanelController.CommunityOptionsCallback callback);
        void setOption(DiscordPanelController.CommunitySetting setting, boolean enabled,
                       DiscordPanelController.CommunityOptionsCallback callback);
        void hostAction(DiscordPanelController.CommunityHostAction action,
                        DiscordPanelController.CommunityOptionsCallback callback);
        void requestAudio(DiscordPanelController.CommunityAudioCallback callback);
        void selectAudioDevice(HostGatewayClient.AudioDevice device,
                               DiscordPanelController.CommunityAudioCallback callback);
        void changeSystemVolume(int delta, DiscordPanelController.CommunityAudioCallback callback);
        void toggleSystemMute(DiscordPanelController.CommunityAudioCallback callback);
    }

    interface Ui {
        TextView action(String label);
        TextView label(String label);
        void show(String eyebrow, String title, String details, View... actions);
        void showCommunity(View shell);
        void toast(String message);
        void backPanel();
        void requestCommunityDictation(long recipientId, long directMessageGeneration);
        void dismissCommunityForAuthorization();
    }

    enum SummaryState { CONNECTED, AUTHORIZATION_REQUIRED, CONNECTING, UNAVAILABLE }
    private static final long SNAPSHOT_REFRESH_MS = 500L;
    private static final String FRIEND_TAG_PREFIX = "discord.social.friend:";

    private final Activity activity;
    private final Handler mainHandler;
    private final Ui ui;
    private final CommunitySource source;
    private boolean visible;
    private boolean shellShown;
    private int generation;
    private long revision = Long.MIN_VALUE;
    private boolean offlineExpanded;
    private String localError = "";
    private DiscordCommunityState state = DiscordCommunityState.initial();
    private HostGatewayClient.DiscordHome home;
    private boolean homeLoading;
    private boolean homeUnavailable;
    private List<HostGatewayClient.DiscordChannel> guildChannels = Collections.emptyList();
    private boolean guildLoading;
    private boolean guildUnavailable;
    private HostGatewayClient.DiscordVoice joinedVoice;
    private HostGatewayClient.DiscordChannel joinedVoiceChannel;
    private boolean voiceLoading;
    private boolean voiceBusy;
    private DiscordPanelController.CommunityOptions options;
    private boolean optionsLoading;
    private String optionsError = "";
    private HostGatewayClient.DiscordAudioState audio;
    private boolean audioLoading;
    private boolean audioBusy;
    private String audioError = "";
    private DiscordCommunityView communityView;
    private final DiscordDirectMessageState directMessages = new DiscordDirectMessageState();
    private long directMessageRequestGeneration;
    private long directMessageSendGeneration;
    private final LinkedHashMap<Long, String> directMessageDrafts = new LinkedHashMap<>(16, .75f, true);

    DiscordSocialPanelController(Activity activity, Handler mainHandler, Ui ui, CommunitySource source) {
        this.activity = activity;
        this.mainHandler = mainHandler;
        this.ui = ui;
        this.source = source;
    }

    void showHub() {
        visible = true;
        shellShown = false;
        generation++;
        revision = Long.MIN_VALUE;
        offlineExpanded = false;
        state = DiscordCommunityState.initial();
        home = null;
        homeLoading = true;
        homeUnavailable = false;
        guildChannels = Collections.emptyList();
        guildLoading = false;
        guildUnavailable = false;
        joinedVoice = null;
        joinedVoiceChannel = null;
        voiceLoading = true;
        voiceBusy = false;
        options = null;
        optionsLoading = false;
        optionsError = "";
        audio = null;
        audioLoading = false;
        audioBusy = false;
        audioError = "";
        attachSilently();
        render(DiscordSocialClient.getSnapshot());
        requestHome(generation);
        refreshVoice(generation);
        watch(generation);
    }

    void closePanel() {
        visible = false; shellShown = false; generation++;
        DiscordSocialClient.setShowingChat(false);
    }

    void onActivityPaused() {
        DiscordSocialClient.setShowingChat(false);
    }

    void onActivityResumed() {
        if (visible && state.detail == DiscordCommunityState.Detail.DIRECT_MESSAGE) {
            DiscordSocialClient.setShowingChat(true);
        }
    }

    boolean handleCommunityKey(KeyEvent event) {
        return visible && communityView != null && communityView.handleNavigationKey(event);
    }

    boolean isDirectMessageOpen() {
        return visible && state.detail == DiscordCommunityState.Detail.DIRECT_MESSAGE;
    }

    boolean handleCommunityBack(KeyEvent event) {
        return visible && communityView != null && communityView.handleDirectMessageBack(event);
    }

    boolean collapseCommunityKeyboardForDictation(long recipientId, long directMessageGeneration) {
        return isCurrentDirectMessage(recipientId, directMessageGeneration)
                && communityView != null && communityView.collapseEmbeddedKeyboardForDictation(recipientId,
                directMessageGeneration);
    }

    boolean insertCommunityDictationResult(long recipientId, long directMessageGeneration, String result) {
        return isCurrentDirectMessage(recipientId, directMessageGeneration)
                && communityView != null && communityView.insertDictationResult(recipientId,
                directMessageGeneration, result);
    }

    boolean restoreCommunityKeyboardAfterDictation(long recipientId, long directMessageGeneration) {
        return isCurrentDirectMessage(recipientId, directMessageGeneration)
                && communityView != null && communityView.restoreEmbeddedKeyboardAfterDictation(recipientId,
                directMessageGeneration);
    }

    void focusCommunityEntry() {
        if (visible && communityView != null) communityView.focusActiveTab();
    }

    boolean prepareForPanelBack() {
        if (!visible) return false;
        if (state.detail == DiscordCommunityState.Detail.FEED) {
            closePanel();
            return false;
        }
        DiscordCommunityState.Detail previousDetail = state.detail;
        state = state.back();
        if (previousDetail == DiscordCommunityState.Detail.DIRECT_MESSAGE) {
            DiscordSocialClient.setShowingChat(false);
        }
        render(DiscordSocialClient.getSnapshot());
        if (previousDetail == DiscordCommunityState.Detail.AUDIO
                || previousDetail == DiscordCommunityState.Detail.SOCIAL) {
            communityView.focusDetailEntry(optionsReturnFocusTag(previousDetail));
        } else {
            communityView.restoreSelectedFocus();
        }
        return true;
    }

    private void requestHome(final int expected) {
        source.requestHome(new DiscordPanelController.CommunityHomeCallback() {
            @Override public void onHome(HostGatewayClient.DiscordHome value) {
                if (!visible || expected != generation) return;
                home = value;
                homeLoading = false;
                homeUnavailable = false;
                render(DiscordSocialClient.getSnapshot());
                refreshVoice(expected);
            }
            @Override public void onUnavailable() {
                if (!visible || expected != generation) return;
                homeLoading = false;
                homeUnavailable = true;
                render(DiscordSocialClient.getSnapshot());
                refreshVoice(expected);
            }
        });
    }

    private void attachSilently() {
        localError = "";
        try { DiscordSocialClient.attach(activity); }
        catch (Exception | LinkageError error) { localError = readableError(error); }
    }

    private void authorize() {
        localError = "";
        try { DiscordSocialClient.authorize(activity); }
        catch (Exception | LinkageError error) {
            localError = readableError(error);
            ui.toast(activity.getString(R.string.discord_social_authorization_error, localError));
            render(DiscordSocialClient.getSnapshot());
        }
    }

    private void watch(final int expected) {
        mainHandler.postDelayed(() -> {
            if (!visible || expected != generation) return;
            DiscordSocialClient.Snapshot snapshot = DiscordSocialClient.getSnapshot();
            boolean messagesChanged = consumeDirectMessageEvents(snapshot);
            if (messagesChanged || shouldRender(revision, snapshot.revision)) render(snapshot);
            watch(expected);
        }, SNAPSHOT_REFRESH_MS);
    }

    private boolean consumeDirectMessageEvents(DiscordSocialClient.Snapshot snapshot) {
        boolean changed = false;
        long currentUserId = safeLong(snapshot.userId);
        for (DiscordSocialClient.MessageEvent event : DiscordSocialClient.drainMessageEvents()) {
            switch (event.type) {
                case OVERFLOW:
                    // The native queue explicitly signals loss; a single active conversation
                    // reload restores a bounded, truthful model instead of guessing.
                    if (state.detail == DiscordCommunityState.Detail.DIRECT_MESSAGE) {
                        requestDirectMessageHistory(state.directMessageRecipientId);
                    }
                    changed = true;
                    break;
                case HISTORY_BEGIN:
                    directMessages.beginHistory(event.recipientId, event.requestId);
                    changed = true;
                    break;
                case HISTORY_MESSAGE:
                    if (directMessages.acceptsHistory(event.recipientId, event.requestId)) {
                        directMessages.upsertHistory(event.recipientId, event);
                        changed = true;
                    }
                    break;
                case CREATED:
                    directMessages.upsert(event.recipientId, event);
                    // The view clears only after a visible conversation has actually rendered.
                    directMessages.markUnreadIfIncoming(event.recipientId, currentUserId, event.authorId, false);
                    changed = true;
                    break;
                case UPDATED:
                    directMessages.upsert(event.recipientId, event);
                    changed = true;
                    break;
                case DELETED:
                    directMessages.delete(event.messageId);
                    changed = true;
                    break;
                case HISTORY_RESULT:
                    directMessages.finishHistory(event.recipientId, event.requestId);
                    changed = true;
                    break;
                case SEND_RESULT:
                    directMessages.finishSend(event.recipientId, event.requestId, event.successful,
                            event.retryable, event.retryAfterSeconds, event.errorType);
                    setDirectMessageDraft(event.recipientId, directMessageDraftAfterSendResult(
                            directMessageDraft(event.recipientId), event.successful));
                    changed = true;
                    break;
                case OPEN_MESSAGE_RESULT:
                    if (!event.successful) ui.toast(activity.getString(R.string.discord_dm_open_failed));
                    break;
            }
        }
        return changed;
    }

    private void requestDirectMessageHistory(long recipientId) {
        if (recipientId <= 0 || !DiscordSocialClient.canUseDirectMessages()) return;
        long requestId = ++directMessageRequestGeneration;
        directMessages.requestHistory(recipientId, requestId);
        DiscordSocialClient.requestUserMessages(recipientId, requestId);
    }

    private static long safeLong(String value) {
        try { return Long.parseLong(value); }
        catch (RuntimeException ignored) { return 0; }
    }

    private void render(DiscordSocialClient.Snapshot snapshot) {
        if (!visible) return;
        revision = snapshot.revision;
        directMessages.setVisibleRecipient(state.detail == DiscordCommunityState.Detail.DIRECT_MESSAGE
                ? state.directMessageRecipientId : 0);
        ArrayList<DiscordCommunityPresentation.Destination> active = new ArrayList<>();
        ArrayList<DiscordCommunityPresentation.Destination> recent = new ArrayList<>();
        project(snapshot, active, recent);
        ensureView();
        communityView.bind(new DiscordCommunityView.Model(snapshot, state, active, recent,
                DiscordCommunityPresentation.channels(guildChannels), homeLoading, homeUnavailable,
                guildLoading, guildUnavailable, localError, joinedVoice, voiceLoading, voiceBusy,
                options, optionsLoading, optionsError, audio, audioLoading, audioBusy, audioError,
                directFriend(snapshot, state.directMessageRecipientId),
                directMessages.history(state.directMessageRecipientId),
                directMessages.sendState(state.directMessageRecipientId),
                directMessageDraft(state.directMessageRecipientId), DiscordSocialClient.canUseDirectMessages()));
        if (state.detail == DiscordCommunityState.Detail.DIRECT_MESSAGE) {
            directMessages.clearUnreadAfterRendered(state.directMessageRecipientId);
        }
        if (!shellShown) { shellShown = true; ui.showCommunity(communityView); }
    }

    private void ensureView() {
        if (communityView != null) return;
        communityView = new DiscordCommunityView(activity, new DiscordCommunityView.Callback() {
            @Override public void onTab(DiscordCommunityState.Tab tab) {
                state = state.tab(tab);
                promoteConnectedVoiceIfAppropriate();
                render(DiscordSocialClient.getSnapshot());
            }
            @Override public void onDestinationFocused(DiscordCommunityPresentation.Destination item) {
                state = state.select(item.id);
            }
            @Override public void onDestinationOpened(DiscordCommunityPresentation.Destination item) {
                open(item);
            }
            @Override public void onOpenDirectMessage(DiscordCommunityPresentation.Destination item) {
                openDirectMessage(item);
            }
            @Override public void onUpgradeDirectMessages() { authorizeForDirectMessages(); }
            @Override public void onDirectMessageDraftChanged(long recipientId, String draft) {
                setDirectMessageDraft(recipientId, draft);
            }
            @Override public void onSendDirectMessage(long recipientId, String content) {
                sendDirectMessage(recipientId, content);
            }
            @Override public void onOpenDirectMessageInDiscord(long messageId) {
                DiscordSocialClient.openMessageInDiscord(messageId);
            }
            @Override public void onDirectMessageDictationRequested(long recipientId,
                                                                     long directMessageGeneration) {
                if (isCurrentDirectMessage(recipientId, directMessageGeneration)) {
                    ui.requestCommunityDictation(recipientId, directMessageGeneration);
                }
            }
            @Override public void onCloseDirectMessage() { prepareForPanelBack(); }
            @Override public void onJoin(HostGatewayClient.DiscordChannel channel) { join(channel); }
            @Override public void onAuthorize() { authorize(); }
            @Override public void onOptions() { openOptions(); }
            @Override public void onVoiceAction(DiscordPanelController.CommunityVoiceAction action) {
                voiceAction(action);
            }
            @Override public void onOption(DiscordPanelController.CommunitySetting setting,
                                           boolean enabled) { setOption(setting, enabled); }
            @Override public void onHostAction(DiscordPanelController.CommunityHostAction action) {
                hostAction(action);
            }
            @Override public void onOpenAudio() { openAudio(); }
            @Override public void onOpenSocial() { openSocial(); }
            @Override public void onAudioDevice(HostGatewayClient.AudioDevice device) {
                selectAudioDevice(device);
            }
            @Override public void onAudioVolume(int delta) { changeAudioVolume(delta); }
            @Override public void onAudioMute() { toggleAudioMute(); }
            @Override public void onUnlink() { confirmUnlink(); }
        });
    }

    private void project(DiscordSocialClient.Snapshot snapshot,
                         List<DiscordCommunityPresentation.Destination> active,
                         List<DiscordCommunityPresentation.Destination> recent) {
        List<DiscordCommunityPresentation.Destination> playing = annotateUnread(DiscordCommunityPresentation.friends(
                snapshot.friendDetails, DiscordSocialClient.Friend.Group.PLAYING,
                activity.getString(R.string.discord_community_playing)));
        List<DiscordCommunityPresentation.Destination> online = annotateUnread(DiscordCommunityPresentation.friends(
                snapshot.friendDetails, DiscordSocialClient.Friend.Group.ONLINE,
                activity.getString(R.string.discord_community_online)));
        List<DiscordCommunityPresentation.Destination> offline = annotateUnread(DiscordCommunityPresentation.friends(
                snapshot.friendDetails, DiscordSocialClient.Friend.Group.OFFLINE,
                activity.getString(R.string.discord_community_offline)));
        List<DiscordCommunityPresentation.Destination> channels = home == null ? Collections.emptyList()
                : DiscordCommunityPresentation.channels(home.favorites, home.recent);
        DiscordCommunityPresentation.Destination activeVoice = joinedVoiceDestination();
        if (activeVoice != null) channels = DiscordCommunityPresentation.withActiveVoice(activeVoice, channels);
        List<DiscordCommunityPresentation.Destination> guilds = home == null ? Collections.emptyList()
                : DiscordCommunityPresentation.guilds(home.guilds);
        if (state.tab == DiscordCommunityState.Tab.FRIENDS) {
            active.addAll(playing);
            recent.addAll(online);
            if (!offline.isEmpty()) {
                int preview = Math.min(8, offline.size());
                recent.addAll(offline.subList(0, offlineExpanded ? offline.size() : preview));
                if (offline.size() > preview) recent.add(offlineToggle(offline.size()));
            }
        } else if (state.tab == DiscordCommunityState.Tab.SERVERS) {
            for (DiscordCommunityPresentation.Destination channel : channels) {
                (channel.active ? active : recent).add(channel);
            }
            recent.addAll(guilds);
        } else {
            active.addAll(DiscordCommunityPresentation.togetherActive(playing, online, channels));
            recent.addAll(DiscordCommunityPresentation.togetherRecent(channels, guilds));
        }
        if (activeVoice != null) {
            removeDestination(active, activeVoice.id);
            removeDestination(recent, activeVoice.id);
            active.add(0, activeVoice);
        }
        while (active.size() > 4) recent.add(0, active.remove(active.size() - 1));
    }

    private static void removeDestination(List<DiscordCommunityPresentation.Destination> values,
                                          String id) {
        for (int index = values.size() - 1; index >= 0; index--) {
            if (id.equals(values.get(index).id)) values.remove(index);
        }
    }

    private List<DiscordCommunityPresentation.Destination> annotateUnread(
            List<DiscordCommunityPresentation.Destination> values) {
        List<DiscordCommunityPresentation.Destination> result = new ArrayList<>(values.size());
        for (DiscordCommunityPresentation.Destination value : values) {
            long userId = value.source instanceof DiscordSocialClient.Friend
                    ? safeLong(((DiscordSocialClient.Friend) value.source).userId) : 0;
            result.add(value.withUnread(directMessages.hasUnread(userId)));
        }
        return result;
    }

    private DiscordCommunityPresentation.Destination offlineToggle(int count) {
        return new DiscordCommunityPresentation.Destination("discord.social.offline.toggle",
                DiscordCommunityPresentation.Kind.ACTIVE_VOICE,
                activity.getString(offlineExpanded ? R.string.discord_social_hide_offline
                        : R.string.discord_social_show_offline, count), "", "", false, 0, null);
    }

    private void open(DiscordCommunityPresentation.Destination item) {
        if ("discord.social.offline.toggle".equals(item.id)) {
            offlineExpanded = !offlineExpanded;
            render(DiscordSocialClient.getSnapshot());
            return;
        }
        state = state.select(item.id);
        if (item.kind == DiscordCommunityPresentation.Kind.FRIEND) {
            state = state.enter(DiscordCommunityState.Detail.FRIEND);
            render(DiscordSocialClient.getSnapshot());
            if (shouldFocusDetailAfterOpen(item.kind)) communityView.focusDetailAction();
        } else if (item.kind == DiscordCommunityPresentation.Kind.SERVER) {
            loadChannels((HostGatewayClient.DiscordGuild) item.source);
        } else if (item.kind == DiscordCommunityPresentation.Kind.CHANNEL) {
            state = state.openChannel(item.id);
            render(DiscordSocialClient.getSnapshot());
            communityView.focusDetailAction();
        }
    }

    private void openDirectMessage(DiscordCommunityPresentation.Destination item) {
        if (!(item.source instanceof DiscordSocialClient.Friend)) return;
        long recipientId = safeLong(((DiscordSocialClient.Friend) item.source).userId);
        if (recipientId <= 0 || !DiscordSocialClient.canUseDirectMessages()) return;
        state = state.select(item.id).openDirectMessage(recipientId, ++directMessageRequestGeneration);
        DiscordSocialClient.setShowingChat(true);
        requestDirectMessageHistory(recipientId);
        render(DiscordSocialClient.getSnapshot());
        communityView.focusDirectComposer();
    }

    private void authorizeForDirectMessages() {
        localError = "";
        ui.dismissCommunityForAuthorization();
        try { DiscordSocialClient.authorizeForDirectMessages(activity); }
        catch (Exception | LinkageError error) {
            localError = readableError(error);
            ui.toast(activity.getString(R.string.discord_social_authorization_error, localError));
            render(DiscordSocialClient.getSnapshot());
        }
    }

    private void sendDirectMessage(long recipientId, String content) {
        String draft = content == null ? "" : content;
        if (draft.trim().isEmpty() || draft.length() > 2000) return;
        long requestId = ++directMessageSendGeneration;
        if (!directMessages.beginSend(recipientId, requestId)) return;
        DiscordSocialClient.sendUserMessage(recipientId, requestId, draft);
        render(DiscordSocialClient.getSnapshot());
    }

    private boolean isCurrentDirectMessage(long recipientId, long directMessageGeneration) {
        return visible && state.detail == DiscordCommunityState.Detail.DIRECT_MESSAGE
                && state.directMessageRecipientId == recipientId
                && state.directMessageGeneration == directMessageGeneration;
    }

    private static DiscordSocialClient.Friend directFriend(DiscordSocialClient.Snapshot snapshot,
                                                             long recipientId) {
        for (DiscordSocialClient.Friend friend : snapshot.friendDetails) {
            if (safeLong(friend.userId) == recipientId) return friend;
        }
        return null;
    }

    private void loadChannels(HostGatewayClient.DiscordGuild guild) {
        state = state.openServer("discord.community.guild:" + guild.id);
        guildChannels = Collections.emptyList();
        guildLoading = true;
        guildUnavailable = false;
        render(DiscordSocialClient.getSnapshot());
        final int expected = generation;
        source.requestGuildChannels(guild, new DiscordPanelController.CommunityChannelsCallback() {
            @Override public void onChannels(List<HostGatewayClient.DiscordChannel> value) {
                if (!visible || expected != generation) return;
                guildChannels = value == null ? Collections.emptyList() : value;
                guildLoading = false;
                render(DiscordSocialClient.getSnapshot());
            }
            @Override public void onUnavailable() {
                if (!visible || expected != generation) return;
                guildLoading = false;
                guildUnavailable = true;
                render(DiscordSocialClient.getSnapshot());
            }
        });
    }

    private void join(HostGatewayClient.DiscordChannel channel) {
        if (voiceBusy) return;
        voiceBusy = true;
        render(DiscordSocialClient.getSnapshot());
        final int expected = generation;
        source.joinChannel(channel, new DiscordPanelController.CommunityActionCallback() {
            @Override public void onComplete(HostGatewayClient.DiscordVoice verifiedVoice) {
                if (!visible || expected != generation) return;
                joinedVoice = verifiedVoice;
                joinedVoiceChannel = channel;
                voiceBusy = false;
                voiceLoading = false;
                promoteConnectedVoiceIfAppropriate();
                revision = Long.MIN_VALUE;
                render(DiscordSocialClient.getSnapshot());
                if (communityView != null) communityView.focusDetailAction();
                ui.toast(activity.getString(R.string.discord_join_success, channel.name));
            }
            @Override public void onError(String safeMessage) {
                if (!visible || expected != generation) return;
                voiceBusy = false;
                ui.toast(activity.getString(R.string.discord_join_failed, safeMessage));
                render(DiscordSocialClient.getSnapshot());
            }
        });
    }

    private void refreshVoice(final int expected) {
        source.requestVoice(new DiscordPanelController.CommunityVoiceCallback() {
            @Override public void onVoice(HostGatewayClient.DiscordVoice voice) {
                if (!visible || expected != generation) return;
                joinedVoice = voice;
                joinedVoiceChannel = findVoiceChannel(voice);
                voiceLoading = false;
                promoteConnectedVoiceIfAppropriate();
                render(DiscordSocialClient.getSnapshot());
            }
            @Override public void onError(String safeMessage) {
                if (!visible || expected != generation) return;
                voiceLoading = false;
                render(DiscordSocialClient.getSnapshot());
            }
        });
    }

    private HostGatewayClient.DiscordChannel findVoiceChannel(HostGatewayClient.DiscordVoice voice) {
        if (voice == null || !voice.connected) return null;
        if (home != null) {
            for (HostGatewayClient.DiscordChannel channel : home.favorites) {
                if (voice.channelId.equals(channel.id)) return channel;
            }
            for (HostGatewayClient.DiscordChannel channel : home.recent) {
                if (voice.channelId.equals(channel.id)) return channel;
            }
        }
        return new HostGatewayClient.DiscordChannel(voice.channelId, voice.guildId, "",
                voice.channelName.isEmpty() ? voice.channelId : voice.channelName,
                voice.participants, false);
    }

    private void promoteConnectedVoiceIfAppropriate() {
        DiscordCommunityPresentation.Destination activeVoice = joinedVoiceDestination();
        if (activeVoice == null || !shouldPromoteConnectedVoice(state.detail, state.selectedId,
                activeVoice.id)) return;
        state = state.select(activeVoice.id).openChannel(activeVoice.id);
    }

    private void voiceAction(DiscordPanelController.CommunityVoiceAction action) {
        if (voiceBusy) return;
        voiceBusy = true;
        render(DiscordSocialClient.getSnapshot());
        final int expected = generation;
        source.voiceAction(action, new DiscordPanelController.CommunityVoiceCallback() {
            @Override public void onVoice(HostGatewayClient.DiscordVoice voice) {
                if (!visible || expected != generation) return;
                voiceBusy = false;
                joinedVoice = voice;
                joinedVoiceChannel = findVoiceChannel(voice);
                if (action == DiscordPanelController.CommunityVoiceAction.LEAVE && !voice.connected) {
                    joinedVoice = null;
                    joinedVoiceChannel = null;
                }
                render(DiscordSocialClient.getSnapshot());
                if (shouldFocusDetailAfterVoiceAction(action, voice) && communityView != null) {
                    // The old Leave view was removed by the successful state transition.
                    // Keep the user in the same detail lane on the new Join action.
                    communityView.focusDetailAction();
                }
                ui.toast(activity.getString(action == DiscordPanelController.CommunityVoiceAction.LEAVE
                        ? R.string.discord_community_left : R.string.discord_community_voice_updated));
            }
            @Override public void onError(String safeMessage) {
                if (!visible || expected != generation) return;
                voiceBusy = false;
                ui.toast(activity.getString(R.string.discord_community_voice_failed, safeMessage));
                render(DiscordSocialClient.getSnapshot());
            }
        });
    }

    private void openOptions() {
        state = state.openOptions();
        if (!canStartOptionsRequest(optionsLoading)) {
            render(DiscordSocialClient.getSnapshot());
            return;
        }
        optionsLoading = true;
        optionsError = "";
        render(DiscordSocialClient.getSnapshot());
        loadOptions(true);
    }

    private void loadOptions(boolean refresh) {
        final int expected = generation;
        source.requestOptions(refresh, new DiscordPanelController.CommunityOptionsCallback() {
            @Override public void onOptions(DiscordPanelController.CommunityOptions value) {
                if (!visible || expected != generation) return;
                options = value; optionsLoading = false; optionsError = "";
                render(DiscordSocialClient.getSnapshot());
            }
            @Override public void onError(String safeMessage) {
                if (!visible || expected != generation) return;
                optionsLoading = false; optionsError = safeMessage;
                render(DiscordSocialClient.getSnapshot());
            }
        });
    }

    private void setOption(DiscordPanelController.CommunitySetting setting, boolean enabled) {
        if (!canStartOptionsRequest(optionsLoading)) return;
        final int expected = generation;
        optionsLoading = true;
        render(DiscordSocialClient.getSnapshot());
        source.setOption(setting, enabled, new DiscordPanelController.CommunityOptionsCallback() {
            @Override public void onOptions(DiscordPanelController.CommunityOptions value) {
                if (!visible || expected != generation) return;
                options = value; optionsLoading = false; render(DiscordSocialClient.getSnapshot());
            }
            @Override public void onError(String safeMessage) {
                if (!visible || expected != generation) return;
                optionsLoading = false; optionsError = safeMessage; render(DiscordSocialClient.getSnapshot());
            }
        });
    }

    private void hostAction(DiscordPanelController.CommunityHostAction action) {
        if (!canStartOptionsRequest(optionsLoading)) return;
        final int expected = generation;
        optionsLoading = true;
        render(DiscordSocialClient.getSnapshot());
        source.hostAction(action, new DiscordPanelController.CommunityOptionsCallback() {
            @Override public void onOptions(DiscordPanelController.CommunityOptions value) {
                if (!visible || expected != generation) return;
                options = value; optionsLoading = false; optionsError = "";
                render(DiscordSocialClient.getSnapshot());
            }
            @Override public void onError(String safeMessage) {
                if (!visible || expected != generation) return;
                optionsLoading = false; optionsError = safeMessage; render(DiscordSocialClient.getSnapshot());
            }
        });
    }

    private void openAudio() {
        state = state.openAudio();
        if (audio != null || audioLoading) {
            render(DiscordSocialClient.getSnapshot());
            communityView.focusDetailEntry("discord.community.audio.volume.down");
            return;
        }
        audioLoading = true;
        render(DiscordSocialClient.getSnapshot());
        communityView.focusDetailEntry("discord.community.audio.loading");
        final int expected = generation;
        source.requestAudio(new DiscordPanelController.CommunityAudioCallback() {
            @Override public void onAudio(HostGatewayClient.DiscordAudioState value) {
                if (!visible || expected != generation) return;
                audio = value; audioLoading = false; audioError = "";
                render(DiscordSocialClient.getSnapshot());
                communityView.focusDetailEntry("discord.community.audio.volume.down");
            }
            @Override public void onError(String safeMessage) {
                if (!visible || expected != generation) return;
                audioLoading = false; audioError = safeMessage;
                render(DiscordSocialClient.getSnapshot());
            }
        });
    }

    private void openSocial() {
        state = state.openSocial();
        render(DiscordSocialClient.getSnapshot());
        communityView.focusDetailEntry("discord.community.social.connect");
    }

    private void selectAudioDevice(HostGatewayClient.AudioDevice device) {
        if (audioBusy) return;
        audioBusy = true; render(DiscordSocialClient.getSnapshot());
        final int expected = generation;
        source.selectAudioDevice(device, audioCallback(expected));
    }

    private void changeAudioVolume(int delta) {
        if (audioBusy) return;
        audioBusy = true; render(DiscordSocialClient.getSnapshot());
        final int expected = generation;
        source.changeSystemVolume(delta, audioCallback(expected));
    }

    private void toggleAudioMute() {
        if (audioBusy) return;
        audioBusy = true; render(DiscordSocialClient.getSnapshot());
        final int expected = generation;
        source.toggleSystemMute(audioCallback(expected));
    }

    private DiscordPanelController.CommunityAudioCallback audioCallback(final int expected) {
        return new DiscordPanelController.CommunityAudioCallback() {
            @Override public void onAudio(HostGatewayClient.DiscordAudioState value) {
                if (!visible || expected != generation) return;
                audio = value; audioBusy = false; audioError = "";
                render(DiscordSocialClient.getSnapshot());
                communityView.focusDetailEntry("discord.community.audio.volume.down");
            }
            @Override public void onError(String safeMessage) {
                if (!visible || expected != generation) return;
                audioBusy = false; audioError = safeMessage;
                render(DiscordSocialClient.getSnapshot());
            }
        };
    }

    private DiscordCommunityPresentation.Destination joinedVoiceDestination() {
        if (joinedVoice == null || !joinedVoice.connected || joinedVoiceChannel == null
                || !joinedVoiceChannel.id.equals(joinedVoice.channelId)) return null;
        return new DiscordCommunityPresentation.Destination(
                "discord.community.channel:" + joinedVoiceChannel.id,
                DiscordCommunityPresentation.Kind.CHANNEL,
                "# " + joinedVoiceChannel.name,
                activity.getString(R.string.discord_community_connected), "", true,
                joinedVoice.participants, joinedVoiceChannel);
    }

    private void confirmUnlink() {
        new AlertDialog.Builder(activity).setTitle(R.string.discord_social_unlink_title)
                .setMessage(R.string.discord_social_unlink_details)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.discord_social_unlink, (dialog, ignored) -> {
                    DiscordSocialClient.unlink();
                    directMessageDrafts.clear();
                    localError = "";
                    render(DiscordSocialClient.getSnapshot());
                }).show();
    }

    private String directMessageDraft(long recipientId) {
        String draft = directMessageDrafts.get(recipientId);
        return draft == null ? "" : draft;
    }

    static String directMessageDraftAfterSendResult(String existingDraft, boolean successful) {
        return successful ? "" : (existingDraft == null ? "" : existingDraft);
    }

    private void setDirectMessageDraft(long recipientId, String draft) {
        if (recipientId <= 0) return;
        String value = draft == null ? "" : draft;
        if (value.isEmpty()) directMessageDrafts.remove(recipientId);
        else directMessageDrafts.put(recipientId, value);
        trimDirectMessageDrafts();
    }

    private void trimDirectMessageDrafts() {
        while (directMessageDrafts.size() > DiscordDirectMessageState.CONVERSATION_LIMIT) {
            boolean evicted = false;
            Iterator<Long> iterator = directMessageDrafts.keySet().iterator();
            while (iterator.hasNext()) {
                long candidate = iterator.next();
                if (candidate == state.directMessageRecipientId
                        || directMessages.sendState(candidate).inFlight) continue;
                iterator.remove();
                evicted = true;
                break;
            }
            if (!evicted) return; // Never discard active/in-flight user input.
        }
    }

    static boolean shouldRender(long renderedRevision, long latestRevision) { return renderedRevision != latestRevision; }
    static boolean shouldFocusDetailAfterOpen(DiscordCommunityPresentation.Kind kind) {
        return kind == DiscordCommunityPresentation.Kind.FRIEND;
    }
    static boolean canStartOptionsRequest(boolean optionsLoading) { return !optionsLoading; }
    static boolean shouldPromoteConnectedVoice(DiscordCommunityState.Detail detail,
                                               String selectedId, String activeVoiceId) {
        return detail == DiscordCommunityState.Detail.FEED && activeVoiceId != null
                && !activeVoiceId.isEmpty() && (selectedId == null || selectedId.isEmpty()
                || activeVoiceId.equals(selectedId));
    }
    static String friendFocusTag(String userId) { return FRIEND_TAG_PREFIX + (userId == null ? "" : userId); }
    static int adjacentTabIndex(int index, int keyCode, int count) {
        return DiscordCommunityState.adjacentTabIndex(index, keyCode, count);
    }
    static String optionsReturnFocusTag(DiscordCommunityState.Detail detail) {
        return detail == DiscordCommunityState.Detail.AUDIO
                ? "discord.community.options.audio" : "discord.community.options.social";
    }
    static boolean shouldFocusDetailAfterVoiceAction(DiscordPanelController.CommunityVoiceAction action,
                                                     HostGatewayClient.DiscordVoice voice) {
        return action == DiscordPanelController.CommunityVoiceAction.LEAVE
                && voice != null && !voice.connected;
    }
    static SummaryState summaryState(boolean connected, boolean authorizationRequired, String status) {
        if (connected) return SummaryState.CONNECTED;
        if (authorizationRequired) return SummaryState.AUTHORIZATION_REQUIRED;
        String normalized = status == null ? "" : status.toLowerCase(Locale.US);
        return normalized.contains("unavailable") || normalized.startsWith("unable")
                ? SummaryState.UNAVAILABLE : SummaryState.CONNECTING;
    }
    private static String readableError(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
