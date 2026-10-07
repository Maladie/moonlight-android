package com.limelight.console;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Community screen's detail: the standard glass side panel (x 1196-1896, y 24-1056 px) with
 * the content of each {@link DiscordCommunityState.Detail} state in the v4 panel rows of
 * {@link ConsolePanel} (mockups 19-discord-together/voice/options/audio/disconnected-v4). It owns
 * the panel's focus targets; {@link DiscordCommunityView} owns navigation between them.
 */
final class DiscordCommunityDetailPanel {
    private static final int VOLUME_STEP = 5;
    /** Mockup geometry (1080p px halved): 19-discord-options/audio/disconnected-v4. */
    private static final int OPTIONS_ROW_DP = 32;
    private static final int AUDIO_ROW_DP = 31;
    private static final int LIST_SECTION_DP = 30;
    private static final int CONNECT_SECTION_DP = 33;
    private static final int LIST_HEADER_GAP_DP = 10;
    private static final int VOLUME_GAP_DP = 6;
    private final Context context;
    private final DiscordCommunityView.Callback callback;
    private final ConsolePanel panel;
    final LockableScrollView scroll;
    private final LinearLayout detail;
    final List<View> focusables = new ArrayList<>();
    final Map<String, View> byTag = new HashMap<>();
    final Map<String, View> destinations = new HashMap<>();
    /** The first action of the detail, focused after opening it. */
    View firstAction;
    private View volumeRow;
    private DiscordCommunityView.Model model;
    /** The legend of the rendered state; a focused tab shows its own until focus leaves. */
    private String legend = "";

    DiscordCommunityDetailPanel(Context context, ConsoleMenuBackdrop backdrop,
                                DiscordCommunityView.Callback callback) {
        this.context = context;
        this.callback = callback;
        scroll = new LockableScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(32));
        detail = new LinearLayout(context);
        detail.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(detail, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        panel = new ConsolePanel(context, scroll, backdrop, false);
        panel.floatOnGlass(detail);
    }

    /** ◄ ► on the volume row; false when {@code current} is another row or the range ends. */
    boolean stepVolume(View current, int key) {
        return current == volumeRow && (key == KeyEvent.KEYCODE_DPAD_LEFT
                || key == KeyEvent.KEYCODE_DPAD_RIGHT)
                && stepVolumeBy(key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
    }

    void showTabLegend() { panel.setLegend(context.getString(R.string.discord_community_legend_tabs)); }

    void restoreLegend() { panel.setLegend(legend); }

    private void setLegend(int text) {
        legend = context.getString(text);
        panel.setLegend(legend);
    }

    /** Replaces the panel content for {@code selected} in the model's detail state. */
    void render(DiscordCommunityView.Model value,
                DiscordCommunityPresentation.Destination selected) {
        model = value;
        detail.removeAllViews();
        destinations.clear();
        byTag.clear();
        focusables.clear();
        firstAction = null;
        volumeRow = null;
        scroll.scrollTo(0, 0);
        if (model == null) return;
        DiscordCommunityState.Detail state = model.state.detail;
        if (state == DiscordCommunityState.Detail.OPTIONS) {
            addOptions();
            return;
        }
        if (state == DiscordCommunityState.Detail.AUDIO) {
            addAudio();
            return;
        }
        if (state == DiscordCommunityState.Detail.SOCIAL) {
            addSocial();
            return;
        }
        if (selected == null) {
            if (!DiscordCommunityView.isLinked(model.snapshot)) {
                addConnect();
            } else {
                header(context.getString(R.string.discord_community_selected), "", null, null);
                setLegend(R.string.discord_community_legend_feed);
            }
            return;
        }
        setLegend(state == DiscordCommunityState.Detail.FEED
                ? R.string.discord_community_legend_feed : R.string.discord_community_legend_detail);
        if (selected.source instanceof HostGatewayClient.DiscordChannel
                && DiscordCommunityView.isConnectedChannel(selected.id, model.voice)) {
            // Voice state is authoritative even when the surrounding route was restored as FEED.
            addActiveVoiceDetail(selected);
            return;
        }
        header(context.getString(R.string.discord_community_selected), selected.title,
                subtitle(context, selected, model.voice), avatar(selected.title, selected.avatarUrl, 40));
        if (selected.participantCount > 0) {
            addNote(context.getString(R.string.discord_community_people,
                    selected.participantCount));
        }
        if (state == DiscordCommunityState.Detail.SERVER_CHANNELS) {
            addGuildChannels(selected);
        } else if (state == DiscordCommunityState.Detail.CHANNEL
                && selected.source instanceof HostGatewayClient.DiscordChannel) {
            addJoinAction((HostGatewayClient.DiscordChannel) selected.source);
        } else if (selected.kind == DiscordCommunityPresentation.Kind.FRIEND) {
            // Also in the list state (mockups 19-together/friends): ► reaches it from the row.
            int label = model.directMessagesCapable ? R.string.discord_dm_send_message
                    : model.directMessagesScopeAvailable ? R.string.discord_dm_chat_open_elsewhere
                    : R.string.discord_dm_upgrade;
            TextView message = row(context.getString(label), R.drawable.ic_console_chat);
            message.setTag("discord.community.friend.message");
            message.setOnClickListener(ignored -> {
                if (model.directMessagesCapable) callback.onOpenDirectMessage(selected);
                else if (!model.directMessagesScopeAvailable) callback.onUpgradeDirectMessages();
            });
            message.setEnabled(model.directMessagesCapable || !model.directMessagesScopeAvailable);
            firstAction = message;
            addAction(message);
        } else if (selected.kind == DiscordCommunityPresentation.Kind.SERVER) {
            firstAction = navigationRow(context.getString(R.string.discord_community_open), 0);
            firstAction.setOnClickListener(ignored -> callback.onDestinationOpened(selected));
            addAction(firstAction);
        } else if (selected.kind == DiscordCommunityPresentation.Kind.CHANNEL) {
            firstAction = row(context.getString(R.string.discord_community_join),
                    R.drawable.ic_console_headset);
            firstAction.setOnClickListener(ignored -> callback.onDestinationOpened(selected));
            addAction(firstAction);
        }
    }

    /** Mockup "discord_disconnected": no account and nothing selected. */
    private void addConnect() {
        header("Discord", context.getString(R.string.discord_community_connect_title),
                context.getString(R.string.discord_community_connect_details), null);
        TextView connect = row(context.getString(R.string.discord_social_connect_action),
                R.drawable.ic_console_link);
        connect.setTag("discord.community.social.connect");
        connect.setOnClickListener(ignored -> callback.onAuthorize());
        addAction(connect);
        TextView options = navigationRow(context.getString(R.string.discord_community_options),
                R.drawable.ic_console_settings);
        options.setTag("discord.community.connect.options");
        options.setOnClickListener(ignored -> callback.onOptions());
        addAction(options);
        if (model.options != null && model.options.status != null) {
            detail.addView(section(context.getString(R.string.discord_community_host_state),
                    CONNECT_SECTION_DP));
            addNote(hostStatus(model.options.status));
        }
        setLegend(R.string.console_panel_legend);
    }

    private void addJoinAction(HostGatewayClient.DiscordChannel channel) {
        TextView joinAction = row(model.voiceBusy
                ? context.getString(R.string.discord_joining_channel)
                : context.getString(R.string.discord_join_channel, channel.name),
                R.drawable.ic_console_headset);
        joinAction.setTag("discord.community.join");
        joinAction.setOnClickListener(ignored -> {
            if (!model.voiceBusy) callback.onJoin(channel);
        });
        firstAction = joinAction;
        addAction(joinAction, model.voiceBusy);
    }

    /** Mockup "discord_voice": channel header, who is there, then the voice controls. */
    private void addActiveVoiceDetail(DiscordCommunityPresentation.Destination selected) {
        HostGatewayClient.DiscordVoice voice = model.voice;
        HostGatewayClient.DiscordChannel channel = selected.source instanceof HostGatewayClient.DiscordChannel
                ? (HostGatewayClient.DiscordChannel) selected.source : null;
        String guild = channel != null && !channel.guildName.isEmpty() ? channel.guildName
                : voice == null ? "" : voice.guildId;
        String connected = context.getString(R.string.discord_state_connected);
        FrameLayout badge = new FrameLayout(context);
        badge.setBackground(DiscordCommunityStyle.dot(DiscordCommunityStyle.BLURPLE, 0, 0));
        ImageView headset = new ImageView(context);
        headset.setImageResource(R.drawable.ic_console_headset);
        headset.setColorFilter(DiscordCommunityStyle.TEXT);
        badge.addView(headset, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        header(context.getString(R.string.discord_community_voice_channel), selected.title,
                guild.isEmpty() ? connected : guild + " · " + connected, badge);
        int people = voice == null ? selected.participantCount : voice.participants;
        TextView status = text(context.getString(R.string.discord_community_connected) + " · "
                + context.getString(R.string.discord_community_people, people), 13,
                DiscordCommunityStyle.ONLINE, false);
        GradientDrawable dot = DiscordCommunityStyle.dot(DiscordCommunityStyle.ONLINE, 0, 0);
        int dotSize = dp(6);
        int dotOffset = ConsoleText.dotOffset(status, dotSize);
        dot.setBounds(0, dotOffset, dotSize, dotSize + dotOffset);
        status.setCompoundDrawablesRelative(dot, null, null, null);
        status.setCompoundDrawablePadding(dp(6));
        status.setPadding(dp(ConsolePanel.ROW_INSET_DP), 0, dp(ConsolePanel.ROW_INSET_DP), 0);
        detail.addView(status, margins(MATCH_PARENT, WRAP_CONTENT, 0, 6));
        if (voice == null) return;
        if (voice.participantList.isEmpty()) {
            addNote(model.voiceLoading ? context.getString(R.string.discord_community_loading)
                    : context.getString(R.string.discord_no_participants));
        } else {
            for (HostGatewayClient.DiscordParticipant participant : voice.participantList) {
                addParticipant(participant);
            }
        }
        if (model.voiceBusy) addNote(context.getString(R.string.discord_community_updating_voice));
        detail.addView(divider(), dividerParams());
        addVoiceAction(model.voice.muted ? R.string.overlay_discord_unmute : R.string.overlay_discord_mute,
                DiscordPanelController.CommunityVoiceAction.MUTE, R.drawable.ic_console_microphone);
        addVoiceAction(model.voice.deafened ? R.string.discord_enable_audio : R.string.discord_disable_audio,
                DiscordPanelController.CommunityVoiceAction.DEAFEN, R.drawable.ic_console_speaker);
        addVoiceAction(R.string.overlay_discord_leave, DiscordPanelController.CommunityVoiceAction.LEAVE,
                R.drawable.ic_console_leave);
    }

    /** 44 px avatar, a 3 px green ring while speaking, name with "ty"/"wyciszony". */
    private void addParticipant(HostGatewayClient.DiscordParticipant participant) {
        LinearLayout line = new LinearLayout(context);
        line.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout ring = new FrameLayout(context);
        if (participant.speaking) {
            GradientDrawable outline = DiscordCommunityStyle.dot(0, DiscordCommunityStyle.ONLINE, dp(1.5f));
            ring.setBackground(outline);
        }
        ring.addView(avatar(participant.name, "", 22),
                new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        LinearLayout.LayoutParams ringParams = new LinearLayout.LayoutParams(dp(25), dp(25));
        ringParams.leftMargin = dp(ConsolePanel.ROW_INSET_DP - 1.5f);
        line.addView(ring, ringParams);
        TextView name = text(participant.name
                + (participant.self ? context.getString(R.string.discord_you_suffix) : "")
                + (participant.muted ? context.getString(R.string.discord_muted_suffix) : ""),
                14, DiscordCommunityStyle.TEXT, false);
        name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        nameParams.leftMargin = dp(6.5f);
        line.addView(name, nameParams);
        detail.addView(line, new LinearLayout.LayoutParams(MATCH_PARENT, dp(30)));
    }

    private void addVoiceAction(int label, DiscordPanelController.CommunityVoiceAction action, int icon) {
        TextView view = row(context.getString(label), icon);
        view.setTag("discord.community.voice." + action.name().toLowerCase(java.util.Locale.US));
        view.setOnClickListener(ignored -> {
            if (!model.voiceBusy) callback.onVoiceAction(action);
        });
        if (action == DiscordPanelController.CommunityVoiceAction.LEAVE) addDestructive(view, model.voiceBusy);
        else addAction(view, model.voiceBusy);
    }

    /** Mockup "discord_options": host settings, then this device, unlink last. */
    private void addOptions() {
        String account = model.snapshot.displayName.isEmpty() ? "Discord" : model.snapshot.displayName;
        boolean linked = DiscordCommunityView.isLinked(model.snapshot);
        DiscordPanelController.CommunityOptions options = model.options;
        header("Discord · " + account, context.getString(R.string.discord_community_options),
                options != null && options.status != null ? hostStatus(options.status)
                        : context.getString(linked ? R.string.discord_community_connected
                        : R.string.discord_social_connect_action), null, LIST_HEADER_GAP_DP);
        setLegend(R.string.discord_community_legend_options);
        if (!linked) {
            TextView connect = dense(row(context.getString(R.string.discord_social_connect_action),
                    R.drawable.ic_console_link), OPTIONS_ROW_DP);
            connect.setTag("discord.community.social.connect");
            connect.setOnClickListener(ignored -> {
                if (!model.optionsLoading) callback.onAuthorize();
            });
            addAction(connect, model.optionsLoading);
        }
        // A refresh after a setting action must retain the existing lane: the focused row is
        // rebuilt under its stable tag and remains a valid D-pad target.
        if (model.optionsLoading) addNote(context.getString(R.string.discord_community_loading));
        if (!model.optionsError.isEmpty()) addNote(model.optionsError);
        boolean actions = DiscordCommunityView.optionsActionsVisible(options != null);
        if (actions) {
            detail.addView(section(context.getString(R.string.discord_community_host_section),
                    LIST_SECTION_DP));
            addSetting(R.string.discord_community_integration, options.integrationEnabled,
                    DiscordPanelController.CommunitySetting.INTEGRATION);
            addSetting(R.string.discord_community_auto_connect, options.autoConnect,
                    DiscordPanelController.CommunitySetting.AUTO_CONNECT);
            addSetting(R.string.discord_community_auto_join, options.autoJoinLast,
                    DiscordPanelController.CommunitySetting.AUTO_JOIN_LAST);
            addHostAction(R.string.discord_start_on_host, DiscordPanelController.CommunityHostAction.START,
                    R.drawable.ic_console_play);
            addHostAction(R.string.discord_connect_rpc, DiscordPanelController.CommunityHostAction.RECONNECT,
                    R.drawable.ic_console_link);
            addHostAction(R.string.discord_refresh_status,
                    DiscordPanelController.CommunityHostAction.REFRESH_STATUS, R.drawable.ic_console_pulse);
        }
        detail.addView(section(context.getString(R.string.discord_community_device_section),
                LIST_SECTION_DP));
        TextView audio = dense(navigationRow(context.getString(R.string.discord_community_audio_devices),
                R.drawable.ic_console_speaker), OPTIONS_ROW_DP);
        audio.setTag("discord.community.options.audio");
        audio.setOnClickListener(ignored -> callback.onOpenAudio());
        addAction(audio);
        TextView social = dense(navigationRow(context.getString(R.string.discord_community_account),
                R.drawable.ic_console_chat), OPTIONS_ROW_DP);
        social.setTag("discord.community.options.social");
        social.setOnClickListener(ignored -> callback.onOpenSocial());
        addAction(social);
        if (actions && linked) addUnlink(true);
    }

    private String hostStatus(HostGatewayClient.DiscordStatus status) {
        return context.getString(R.string.discord_community_host_status,
                status.bridgeOnline ? context.getString(R.string.discord_state_online)
                        : context.getString(R.string.discord_state_offline),
                status.rpcConnected ? context.getString(R.string.discord_state_online)
                        : context.getString(R.string.discord_state_offline),
                status.authenticated ? context.getString(R.string.discord_state_on)
                        : context.getString(R.string.discord_state_off));
    }

    /** "Konto i powiadomienia": DM notifications and the Discord account link. */
    private void addSocial() {
        String account = model.snapshot.displayName.isEmpty() ? "Discord" : model.snapshot.displayName;
        boolean linked = DiscordCommunityView.isLinked(model.snapshot);
        header(context.getString(R.string.discord_community_options),
                context.getString(R.string.discord_community_account),
                account + " · " + context.getString(linked ? R.string.discord_community_connected
                        : R.string.discord_social_connect_action), null);
        setLegend(R.string.discord_community_legend_social);
        TextView notifications = panel.choice(context.getString(
                R.string.discord_dm_notifications_setting), model.dmNotificationsEnabled, true);
        notifications.setTag("discord.community.social.dm_notifications");
        notifications.setOnClickListener(ignored -> callback.onDmNotificationsChanged(
                !model.dmNotificationsEnabled));
        addAction(notifications);
        if (!linked) {
            TextView connect = row(context.getString(R.string.discord_social_connect_action),
                    R.drawable.ic_console_link);
            connect.setTag("discord.community.social.connect");
            connect.setOnClickListener(ignored -> callback.onAuthorize());
            addAction(connect);
        } else addUnlink(false);
    }

    /** Mockup "discord_audio": a volume stepper, the mute check and radio device lists. */
    private void addAudio() {
        String host = model.hostName.isEmpty() ? context.getString(R.string.discord_selected_host)
                : model.hostName;
        header(context.getString(R.string.discord_community_options),
                context.getString(R.string.discord_audio_title),
                context.getString(R.string.discord_community_audio_subtitle, host), null,
                LIST_HEADER_GAP_DP);
        setLegend(R.string.discord_community_legend_audio);
        if (model.audioLoading && model.audio == null) {
            addPassiveDetailEntry("discord.community.audio.loading",
                    context.getString(R.string.discord_community_loading));
            return;
        }
        if (!model.audioError.isEmpty()) addNote(model.audioError);
        HostGatewayClient.DiscordAudioState audio = model.audio;
        if (audio == null) {
            // A failed load keeps one focus target: retry.
            TextView retry = row(context.getString(R.string.discord_social_retry), R.drawable.ic_console_pulse);
            retry.setTag("discord.community.audio.retry");
            retry.setOnClickListener(ignored -> callback.onOpenAudio());
            addAction(retry);
            return;
        }
        if (audio.systemAvailable) {
            // OK has no action here; the arrow on a range end is hidden like ConsolePanel.stepperText.
            int level = audio.systemVolume;
            LinearLayout volume = panel.value(
                    context.getString(R.string.discord_community_windows_volume),
                    (canStepVolume(level, -1) ? "‹  " : "")
                            + context.getString(R.string.discord_community_volume_value, level)
                            + (canStepVolume(level, 1) ? "  ›" : ""));
            TextView value = (TextView) volume.getChildAt(1);
            value.setCompoundDrawablesRelative(null, null, null, null);
            value.setTextColor(ConsolePanel.rowColors(DiscordCommunityStyle.ACCENT));
            value.setTypeface(DiscordCommunityStyle.MEDIUM);
            volume.setTag("discord.community.audio.volume");
            // Mockup "discord_audio": the 70 px stepper fill 6 px below the header, then 62 px rows.
            volumeRow = volume;
            addAction(volume);
            ((LinearLayout.LayoutParams) volume.getLayoutParams()).topMargin = dp(VOLUME_GAP_DP);
            TextView mute = dense(panel.choice(context.getString(R.string.discord_mute_system),
                    audio.systemMuted, true), AUDIO_ROW_DP);
            mute.setTag("discord.community.audio.mute");
            mute.setOnClickListener(ignored -> { if (!model.audioBusy) callback.onAudioMute(); });
            addAction(mute);
        }
        addAudioDevices(context.getString(R.string.discord_system_devices), audio.systemDevices);
        addAudioDevices(context.getString(R.string.discord_discord_devices), audio.discordDevices);
        if (!audio.error.isEmpty()) addNote(audio.error);
        if (model.audioBusy) addNote(context.getString(R.string.discord_community_loading));
    }

    private void addAudioDevices(String title, List<HostGatewayClient.AudioDevice> devices) {
        if (devices.isEmpty()) return;
        detail.addView(section(title, LIST_SECTION_DP));
        for (HostGatewayClient.AudioDevice device : devices) {
            TextView action = dense(panel.choice(device.name + "  ·  " + device.flow, device.current, false),
                    AUDIO_ROW_DP);
            action.setTag("discord.community.audio.device." + device.system + "." + device.flow + "." + device.id);
            action.setOnClickListener(ignored -> { if (!model.audioBusy) callback.onAudioDevice(device); });
            addAction(action);
        }
    }

    /** ◄ ► on the volume row step 5 %; at the end of the range the key navigates instead. */
    private boolean stepVolumeBy(int direction) {
        HostGatewayClient.DiscordAudioState audio = model.audio;
        if (audio == null || !canStepVolume(audio.systemVolume, direction)) return false;
        if (!model.audioBusy) callback.onAudioVolume(direction * VOLUME_STEP);
        return true;
    }

    static boolean canStepVolume(int volume, int direction) {
        return direction < 0 ? volume > 0 : volume < 100;
    }

    /** In Options the row is dense so the whole list fits above the panel legend. */
    private void addUnlink(boolean dense) {
        TextView unlink = row(context.getString(R.string.discord_social_unlink),
                R.drawable.ic_console_unlink);
        if (dense) dense(unlink, OPTIONS_ROW_DP);
        unlink.setTag("discord.community.unlink");
        unlink.setOnClickListener(ignored -> {
            if (!model.optionsLoading) callback.onUnlink();
        });
        addDestructive(unlink, model.optionsLoading);
    }

    private void addSetting(int label, boolean enabled, DiscordPanelController.CommunitySetting setting) {
        TextView view = dense(panel.choice(context.getString(label), enabled, true), OPTIONS_ROW_DP);
        view.setTag("discord.community.setting." + setting.name().toLowerCase(java.util.Locale.US));
        view.setOnClickListener(ignored -> {
            if (!model.optionsLoading) callback.onOption(setting, !enabled);
        });
        addAction(view, model.optionsLoading);
    }

    private void addHostAction(int label, DiscordPanelController.CommunityHostAction action, int icon) {
        TextView view = dense(row(context.getString(label), icon), OPTIONS_ROW_DP);
        view.setTag("discord.community.host." + action.name().toLowerCase(java.util.Locale.US));
        view.setOnClickListener(ignored -> {
            if (!model.optionsLoading) callback.onHostAction(action);
        });
        addAction(view, model.optionsLoading);
    }

    private void addGuildChannels(DiscordCommunityPresentation.Destination selected) {
        if (model.guildLoading) { addNote(context.getString(R.string.discord_community_loading)); return; }
        if (model.guildUnavailable) {
            addNote(context.getString(R.string.discord_community_host_unavailable));
            if (selected.kind == DiscordCommunityPresentation.Kind.SERVER) {
                firstAction = row(context.getString(R.string.discord_social_retry),
                        R.drawable.ic_console_pulse);
                firstAction.setOnClickListener(ignored -> callback.onDestinationOpened(selected));
                addAction(firstAction);
            }
            return;
        }
        for (DiscordCommunityPresentation.Destination channel : model.guildChannels) {
            addDetailChannel(channel);
        }
    }

    private void addDetailChannel(DiscordCommunityPresentation.Destination item) {
        TextView row = row(item.title, R.drawable.ic_console_headset);
        row.setTag(item.id);
        row.setContentDescription(item.title + ", " + subtitle(context, item, model.voice));
        row.setOnFocusChangeListener((ignored, focused) -> {
            if (focused) {
                DiscordCommunityStyle.reveal(scroll, row);
                callback.onDestinationFocused(item);
            }
        });
        row.setOnClickListener(ignored -> callback.onDestinationOpened(item));
        destinations.put(item.id, row);
        detail.addView(row, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        focusables.add(row);
    }

    private void addAction(View action) { addAction(action, false); }

    private void addAction(View action, boolean busy) {
        if (action.getId() == View.NO_ID) action.setId(View.generateViewId());
        action.setFocusableInTouchMode(true);
        if (busy) action.setAlpha(.58f);
        detail.addView(action, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        focusables.add(action);
        Object tag = action.getTag();
        if (!(tag instanceof String)) {
            tag = "discord.community.detail." + focusables.size();
            action.setTag(tag);
        }
        byTag.put((String) tag, action);
        if (firstAction == null) firstAction = action;
    }

    /** Destructive rows: red, last, behind a hairline (UX_MENU_PL.md 4.1). */
    private void addDestructive(TextView action, boolean busy) {
        detail.addView(divider(), dividerParams());
        addAction(panel.destructive(action), busy);
    }

    private void addPassiveDetailEntry(String tag, String label) {
        TextView entry = row(label, 0);
        entry.setTag(tag);
        entry.setContentDescription(label);
        addAction(entry);
    }

    private void addNote(String value) {
        detail.addView(panel.note(value));
    }

    /** Panel header: eyebrow, then title and subtitle, beside an 80 px avatar when given. */
    private void header(String eyebrow, String title, String subtitle, View avatar) {
        header(eyebrow, title, subtitle, avatar, 12);
    }

    private void header(String eyebrow, String title, String subtitle, View avatar, int gapDp) {
        int inset = dp(ConsolePanel.ROW_INSET_DP);
        TextView eyebrowView = DiscordCommunityStyle.eyebrow(context, eyebrow);
        eyebrowView.setPadding(inset, 0, inset, 0);
        detail.addView(eyebrowView, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        if (title.isEmpty()) return;
        TextView titleView = text(title, avatar == null ? 22 : 20, DiscordCommunityStyle.TEXT, true);
        titleView.setSingleLine(true); titleView.setEllipsize(TextUtils.TruncateAt.END);
        TextView subtitleView = TextUtils.isEmpty(subtitle) ? null
                : text(subtitle, 13, DiscordCommunityStyle.TEXT_SECONDARY, false);
        if (avatar == null) {
            titleView.setPadding(inset, 0, inset, 0);
            detail.addView(titleView, margins(MATCH_PARENT, WRAP_CONTENT, 4, subtitleView == null ? gapDp : 0));
            if (subtitleView != null) {
                subtitleView.setPadding(inset, 0, inset, 0);
                detail.addView(subtitleView, margins(MATCH_PARENT, WRAP_CONTENT, 3, gapDp));
            }
            return;
        }
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        avatarParams.leftMargin = dp(18);
        row.addView(avatar, avatarParams);
        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(titleView);
        if (subtitleView != null) words.addView(subtitleView, margins(MATCH_PARENT, WRAP_CONTENT, 3, 0));
        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        wordsParams.leftMargin = dp(14);
        wordsParams.rightMargin = inset;
        row.addView(words, wordsParams);
        detail.addView(row, margins(MATCH_PARENT, WRAP_CONTENT, 17, 16));
    }

    /** A panel action row with an optional 15 dp line icon; untagged until the caller tags it. */
    private TextView row(String label, int icon) {
        TextView row = panel.action(label);
        row.setTag(null);
        if (icon != 0) iconize(row, icon);
        return row;
    }

    private TextView navigationRow(String label, int icon) {
        TextView row = panel.navigation(label);
        row.setTag(null);
        if (icon != 0) iconize(row, icon);
        return row;
    }

    /** Panel icons are 15 dp; a larger source (the top bar's gear) is drawn at that size. */
    private void iconize(TextView row, int icon) {
        panel.icon(row, icon);
        Drawable[] drawables = row.getCompoundDrawablesRelative();
        drawables[0].setBounds(0, 0, dp(15), dp(15));
        row.setCompoundDrawablesRelative(drawables[0], drawables[1], drawables[2], drawables[3]);
    }

    /** Mockup panel_rows section: {@code headDp} high, its 28 px caption 12 px above the rows. */
    private TextView section(String title, int headDp) {
        TextView section = panel.section(title);
        section.setPadding(section.getPaddingLeft(), dp(headDp - 20), section.getPaddingRight(),
                section.getPaddingBottom());
        return section;
    }

    /** Options (64 px) and audio (62 px) rows with 28 px text, as in their mockups. */
    private <T extends View> T dense(T row, int heightDp) {
        row.setMinimumHeight(dp(heightDp));
        TextView label = row instanceof TextView ? (TextView) row
                : (TextView) ((ViewGroup) row).getChildAt(0);
        label.setMinHeight(dp(heightDp));
        label.setTextSize(14);
        return row;
    }

    private View divider() {
        View divider = new View(context);
        divider.setBackgroundColor(0x1EFFFFFF);
        divider.setFocusable(false);
        return divider;
    }

    private LinearLayout.LayoutParams dividerParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH_PARENT, Math.max(1, dp(1) / 2));
        params.setMargins(dp(ConsolePanel.ROW_INSET_DP), dp(6), dp(ConsolePanel.ROW_INSET_DP), dp(6));
        return params;
    }

    static String subtitle(Context context, DiscordCommunityPresentation.Destination item,
                           HostGatewayClient.DiscordVoice voice) {
        if (DiscordCommunityView.isConnectedChannel(item.id, voice)) {
            return context.getString(R.string.discord_community_connected);
        }
        return item.participantCount > 0 ? item.subtitle + " · " + context.getString(
                R.string.discord_community_people, item.participantCount) : item.subtitle;
    }
    private FrameLayout avatar(String title, String url, int sizeDp) {
        return DiscordCommunityPresentation.avatar(context, title, url, sizeDp);
    }

    private TextView text(CharSequence value, float size, int color, boolean medium) {
        return DiscordCommunityStyle.text(context, value, size, color, medium);
    }

    private LinearLayout.LayoutParams margins(int width, int height, int topDp, int bottomDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = dp(topDp);
        params.bottomMargin = dp(bottomDp);
        return params;
    }

    private int dp(float value) { return DiscordCommunityStyle.dp(context, value); }

    private static final int MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT;
}
