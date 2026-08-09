package com.limelight.console;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.limelight.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns the Gateway pairing and Discord panel tree without participating in streaming. */
final class DiscordPanelController {
    interface Ui {
        TextView action(String label);
        TextView back(String label);
        TextView label(String label);
        void show(String eyebrow, String title, String details, View... actions);
        void busy(String eyebrow, String title, String details);
        void toast(String message);
    }

    private interface Task<T> { T run() throws Exception; }
    private interface Result<T> { void accept(T value); }

    private final Context context;
    private final android.os.Handler mainHandler;
    private final ExecutorService executor;
    private final Ui ui;
    private final HostGatewayClient client = new HostGatewayClient();
    private final HostGatewayStore store;
    private final AtomicInteger requestGeneration = new AtomicInteger();

    private String hostUuid;
    private String hostAddress;
    private String hostName;
    private int currentPanelTitle = R.string.host_integrations_title;

    DiscordPanelController(Context context, android.os.Handler mainHandler,
                           ExecutorService executor, Ui ui) {
        this.context = context;
        this.mainHandler = mainHandler;
        this.executor = executor;
        this.ui = ui;
        store = new HostGatewayStore(context);
    }

    void destroy() {
        requestGeneration.incrementAndGet();
    }

    void closePanel() {
        requestGeneration.incrementAndGet();
    }

    void showHostIntegrations(String uuid, String address, String name) {
        currentPanelTitle = R.string.host_integrations_title;
        hostUuid = uuid;
        hostAddress = address;
        hostName = name == null || name.isEmpty()
                ? context.getString(R.string.discord_selected_host) : name;
        HostGatewayClient.Connection connection = connection();
        if (connection == null) {
            TextView pair = ui.action(context.getString(R.string.gateway_pair_action));
            pair.setOnClickListener(view -> showPairingDialog(false));
            ui.show(context.getString(R.string.host_integrations_title), hostName,
                    context.getString(R.string.gateway_pair_details), pair);
            return;
        }

        TextView profiles = ui.action(context.getString(
                R.string.gateway_integration_profile, connection.profileId));
        TextView refresh = ui.action(context.getString(R.string.gateway_refresh_status));
        TextView forget = ui.action(context.getString(R.string.gateway_forget));
        profiles.setOnClickListener(view -> showProfiles(connection));
        refresh.setOnClickListener(view -> showHostIntegrationStatus(connection));
        forget.setOnClickListener(view -> confirmForget());
        ui.show(context.getString(R.string.host_integrations_title), hostName,
                context.getString(R.string.gateway_paired_details, connection.endpoint),
                profiles, refresh, forget);
    }

    private void showHostIntegrationStatus(HostGatewayClient.Connection connection) {
        TextView loading = ui.label(context.getString(R.string.gateway_checking_status));
        TextView profiles = ui.action(context.getString(
                R.string.gateway_integration_profile, connection.profileId));
        TextView forget = ui.action(context.getString(R.string.gateway_forget));
        profiles.setOnClickListener(view -> showProfiles(connection));
        forget.setOnClickListener(view -> confirmForget());
        ui.show(context.getString(R.string.host_integrations_title), hostName,
                connection.endpoint, loading, profiles, forget);
        load(context.getString(R.string.gateway_status_error),
                () -> client.getCapabilities(connection), capabilities -> {
            loading.setText(context.getString(R.string.gateway_status_result,
                    context.getString(R.string.gateway_online),
                    context.getString(capabilities.discord
                            ? R.string.gateway_bridge_online : R.string.gateway_bridge_offline)));
        }, () -> showHostIntegrationStatus(connection));
    }

    private void showPairingDialog(boolean returnToDiscord) {
        if (hostUuid == null || hostAddress == null || hostAddress.trim().isEmpty()) {
            ui.toast(context.getString(R.string.gateway_pair_online_host_required));
            return;
        }
        EditText code = new EditText(context);
        code.setSingleLine(true);
        code.setHint(R.string.gateway_pair_code_hint);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        int padding = Math.round(24 * context.getResources().getDisplayMetrics().density);
        FrameContainer container = new FrameContainer(context, padding, code);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.gateway_pair_title)
                .setMessage(context.getString(R.string.gateway_pair_code_details, hostName))
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.gateway_pair_confirm, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String value = code.getText().toString().trim();
                    if (!value.matches("[0-9]{6}")) {
                        code.setError(context.getString(R.string.gateway_pair_code_error));
                        return;
                    }
                    dialog.dismiss();
                    pair(value, returnToDiscord);
                }));
        dialog.show();
        code.requestFocus();
    }

    private void pair(String code, boolean returnToDiscord) {
        String endpoint = HostGatewayClient.endpointForHost(hostAddress);
        showGatewayBusy(context.getString(R.string.gateway_pairing_title),
                context.getString(R.string.gateway_pairing_details, endpoint));
        load(context.getString(R.string.gateway_pair_error),
                () -> client.pair(endpoint, code, "Moonlight Android TV"), pairing -> {
            store.save(hostUuid, pairing.connection);
            if (returnToDiscord) showDiscordServers(true);
            else showHostIntegrations(hostUuid, hostAddress, hostName);
        }, () -> showPairingDialog(returnToDiscord));
    }

    private void confirmForget() {
        new AlertDialog.Builder(context)
                .setTitle(R.string.gateway_forget_title)
                .setMessage(R.string.gateway_forget_details)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.gateway_forget_confirm, (dialog, which) -> {
                    store.remove(hostUuid);
                    showHostIntegrations(hostUuid, hostAddress, hostName);
                }).show();
    }

    private void showProfiles(HostGatewayClient.Connection connection) {
        showGatewayBusy(context.getString(R.string.gateway_profiles_title),
                context.getString(R.string.gateway_profiles_loading));
        load(context.getString(R.string.gateway_profiles_error),
                () -> client.getIntegrationProfiles(connection), profiles -> {
            List<View> actions = new ArrayList<>();
            for (HostGatewayClient.IntegrationProfile profile : profiles.profiles) {
                String suffix = profile.id.equals(connection.profileId)
                        ? context.getString(R.string.discord_selected_suffix) : "";
                TextView action = ui.action(profile.name + suffix);
                action.setOnClickListener(view -> {
                    store.setSelectedIntegrationProfileId(hostUuid, profile.id);
                    showHostIntegrations(hostUuid, hostAddress, hostName);
                });
                actions.add(action);
            }
            TextView back = ui.back(context.getString(R.string.discord_back_action));
            actions.add(back);
            ui.show(context.getString(R.string.host_integrations_title),
                    context.getString(R.string.gateway_profile_title),
                    context.getString(R.string.gateway_profile_details),
                    actions.toArray(new View[0]));
        }, () -> showHostIntegrations(hostUuid, hostAddress, hostName));
    }

    void showDiscordServers(boolean force) {
        currentPanelTitle = R.string.discord_panel_title;
        HostGatewayClient.Connection connection = connection();
        if (connection == null) {
            showDiscordGatewayRequired();
            return;
        }
        if (!store.isDiscordEnabled(hostUuid, connection.profileId)) {
            TextView enable = ui.action(context.getString(R.string.discord_enable_integration));
            enable.setOnClickListener(view -> {
                store.setDiscordEnabled(hostUuid, connection.profileId, true);
                showDiscordServers(true);
            });
            TextView back = ui.back(context.getString(R.string.discord_back_action));
            ui.show(context.getString(R.string.discord_panel_title),
                    context.getString(R.string.discord_integration_disabled),
                    context.getString(R.string.discord_enable_gateway_details),
                    enable, back);
            return;
        }
        showDiscordBusy(context.getString(R.string.discord_loading_title),
                context.getString(R.string.discord_loading_home));
        load(context.getString(R.string.discord_home_error),
                () -> loadDiscordHome(connection, force), home -> {
            List<View> actions = new ArrayList<>();
            addChannelGroup(actions, context.getString(R.string.discord_favorites),
                    home.favorites, connection);
            addChannelGroup(actions, context.getString(R.string.discord_recent),
                    home.recent, connection);
            if (!home.guilds.isEmpty()) actions.add(ui.label(
                    context.getString(R.string.discord_servers)));
            String selectedGuild = store.loadLastDiscordGuildId(hostUuid, connection.profileId);
            if (home.guilds.isEmpty()) actions.add(ui.label(
                    context.getString(R.string.discord_no_servers)));
            for (HostGatewayClient.DiscordGuild guild : home.guilds) {
                String suffix = guild.id.equals(selectedGuild)
                        ? context.getString(R.string.discord_selected_suffix) : "";
                TextView action = ui.action(guild.name + suffix + "  ›");
                action.setOnClickListener(view -> {
                    store.saveLastDiscordGuild(hostUuid, connection.profileId,
                            guild.id, guild.name);
                    showDiscordChannels(connection, guild, false);
                });
                actions.add(action);
            }
            TextView settings = ui.action(context.getString(R.string.discord_settings_action));
            settings.setOnClickListener(view -> showDiscordSettings(connection));
            TextView refresh = ui.action(context.getString(R.string.discord_refresh));
            refresh.setOnClickListener(view -> showDiscordServers(true));
            TextView back = ui.back(context.getString(R.string.discord_back_action));
            actions.add(settings); actions.add(refresh); actions.add(back);
            ui.show(context.getString(R.string.discord_panel_title),
                    context.getString(R.string.discord_home_title),
                    context.getString(R.string.discord_home_details),
                    actions.toArray(new View[0]));
        }, () -> showDiscordServers(true));
    }

    private void showDiscordGatewayRequired() {
        TextView pair = ui.action(context.getString(R.string.discord_pair_gateway_action));
        pair.setOnClickListener(view -> showPairingDialog(true));
        TextView back = ui.back(context.getString(R.string.discord_back_action));
        ui.show(context.getString(R.string.discord_panel_title),
                context.getString(R.string.discord_gateway_required_title),
                context.getString(R.string.discord_gateway_required_details, hostName),
                pair, back);
    }

    private HostGatewayClient.DiscordHome loadDiscordHome(
            HostGatewayClient.Connection connection, boolean force) throws Exception {
        try {
            return client.getDiscordHome(connection, force);
        } catch (Exception firstFailure) {
            HostGatewayClient.DiscordStatus status = client.getDiscordStatus(connection);
            if (!status.bridgeOnline || !status.rpcConnected || !status.authenticated) {
                throw firstFailure;
            }
            // A healthy RPC bridge can briefly return stale home state while its
            // guild cache refreshes. Confirm health, then make one forced retry.
            Thread.sleep(350L);
            return client.getDiscordHome(connection, true);
        }
    }

    private void addChannelGroup(List<View> actions, String title,
                                 List<HostGatewayClient.DiscordChannel> channels,
                                 HostGatewayClient.Connection connection) {
        if (channels.isEmpty()) return;
        actions.add(ui.label(title));
        for (HostGatewayClient.DiscordChannel channel : channels) {
            TextView action = ui.action("#  " + channel.name + "  ·  " + channel.guildName);
            action.setOnClickListener(view -> showDiscordChannel(connection, channel, false));
            actions.add(action);
        }
    }

    private void showDiscordChannels(HostGatewayClient.Connection connection,
                                     HostGatewayClient.DiscordGuild guild, boolean force) {
        showDiscordBusy(context.getString(R.string.discord_loading_title),
                context.getString(R.string.discord_loading_named, guild.name));
        load(context.getString(R.string.discord_channels_error),
                () -> client.getDiscordChannels(connection, guild, force), channels -> {
            List<View> actions = new ArrayList<>();
            if (channels.isEmpty()) actions.add(ui.label(
                    context.getString(R.string.discord_no_channels)));
            for (HostGatewayClient.DiscordChannel channel : channels) {
                String people = channel.people >= 0 ? "  ·  " + channel.people : "";
                TextView action = ui.action((channel.favorite ? "★  " : "#  ") + channel.name + people);
                action.setOnClickListener(view -> showDiscordChannel(connection, channel, false));
                actions.add(action);
            }
            TextView refresh = ui.action(context.getString(R.string.discord_refresh));
            refresh.setOnClickListener(view -> showDiscordChannels(connection, guild, true));
            TextView back = ui.back(context.getString(R.string.discord_back_action));
            actions.add(refresh); actions.add(back);
            ui.show(context.getString(R.string.discord_panel_title), guild.name,
                    context.getString(R.string.discord_select_channel),
                    actions.toArray(new View[0]));
        }, () -> showDiscordChannels(connection, guild, true));
    }

    private void showDiscordChannel(HostGatewayClient.Connection connection,
                                    HostGatewayClient.DiscordChannel channel, boolean force) {
        showDiscordBusy(context.getString(R.string.discord_loading_title),
                context.getString(R.string.discord_loading_channel, channel.name));
        load(context.getString(R.string.discord_channel_error),
                () -> client.getDiscordVoice(connection, force), voice -> {
            boolean active = voice.connected && channel.id.equals(voice.channelId);
            List<View> actions = new ArrayList<>();
            if (active) {
                TextView mute = ui.action(context.getString(voice.muted
                        ? R.string.overlay_discord_unmute : R.string.overlay_discord_mute));
                mute.setOnClickListener(view -> operation(
                        context.getString(R.string.discord_updating_microphone),
                        () -> client.setDiscordVoiceFlag(connection, "mute", "toggle"),
                        () -> showDiscordChannel(connection, channel, true)));
                TextView deafen = ui.action(context.getString(voice.deafened
                        ? R.string.discord_enable_audio : R.string.discord_disable_audio));
                deafen.setOnClickListener(view -> operation(
                        context.getString(R.string.discord_updating_audio),
                        () -> client.setDiscordVoiceFlag(connection, "deafen", "toggle"),
                        () -> showDiscordChannel(connection, channel, true)));
                TextView leave = ui.action(context.getString(R.string.overlay_discord_leave));
                leave.setOnClickListener(view -> operation(
                        context.getString(R.string.discord_leaving_channel),
                        () -> client.leaveDiscordChannel(connection),
                        () -> showDiscordChannel(connection, channel, true)));
                TextView people = ui.action(context.getString(
                        R.string.discord_people_action, voice.participants));
                people.setOnClickListener(view -> showParticipants(connection, channel));
                actions.add(mute); actions.add(deafen); actions.add(leave); actions.add(people);
            } else {
                TextView join = ui.action(context.getString(
                        R.string.discord_join_channel, channel.name));
                join.setOnClickListener(view -> operation(
                        context.getString(R.string.discord_joining_channel),
                        () -> client.joinDiscordChannel(connection, channel), () -> {
                            store.saveLastDiscordChannel(hostUuid, connection.profileId,
                                    channel.id, channel.guildId, channel.guildName, channel.name);
                            showDiscordChannel(connection, channel, true);
                        }));
                actions.add(join);
            }
            TextView back = ui.back(context.getString(R.string.discord_back_action));
            actions.add(back);
            ui.show(context.getString(R.string.discord_panel_title), "# " + channel.name,
                    channel.guildName + (active ? context.getString(
                            R.string.discord_connected_suffix) : ""),
                    actions.toArray(new View[0]));
        }, () -> showDiscordChannel(connection, channel, true));
    }

    private void showParticipants(HostGatewayClient.Connection connection,
                                  HostGatewayClient.DiscordChannel channel) {
        showDiscordBusy(context.getString(R.string.discord_people_title),
                context.getString(R.string.discord_loading_people));
        load(context.getString(R.string.discord_people_error),
                () -> client.getDiscordVoice(connection, true), voice -> {
            List<View> actions = new ArrayList<>();
            if (voice.participantList.isEmpty()) {
                actions.add(ui.label(context.getString(R.string.discord_no_participants)));
            }
            for (HostGatewayClient.DiscordParticipant participant : voice.participantList) {
                String details = (participant.speaking ? "●  " : "") + participant.name +
                        (participant.username.isEmpty() ? "" : "  ·  @" + participant.username) +
                        (participant.self ? context.getString(R.string.discord_you_suffix) : "") +
                        (participant.muted ? context.getString(R.string.discord_muted_suffix) : "") +
                        (participant.deafened ? context.getString(R.string.discord_deafened_suffix) : "") +
                        (participant.bot ? context.getString(R.string.discord_bot_suffix) : "") +
                        "\n" + context.getString(R.string.discord_volume_value,
                                participant.volume) +
                        (participant.audioError.isEmpty() ? "" : "\n" + participant.audioError);
                actions.add(ui.label(details));
                if (!participant.self && participant.canSetVolume) {
                    actions.add(participantControls(connection, channel, participant));
                } else if (!participant.self) {
                    actions.add(ui.label(context.getString(
                            R.string.discord_participant_volume_unavailable)));
                }
            }
            TextView refresh = ui.action(context.getString(R.string.discord_refresh));
            refresh.setOnClickListener(view -> showParticipants(connection, channel));
            TextView back = ui.back(context.getString(R.string.discord_back_action));
            actions.add(refresh); actions.add(back);
            ui.show(context.getString(R.string.discord_panel_title),
                    context.getString(R.string.discord_people_title), voice.channelName,
                    actions.toArray(new View[0]));
        }, () -> showParticipants(connection, channel));
    }

    private View participantControls(HostGatewayClient.Connection connection,
                                     HostGatewayClient.DiscordChannel channel,
                                     HostGatewayClient.DiscordParticipant participant) {
        SeekBar volume = new SeekBar(context);
        volume.setMax(DiscordFeatureContract.MAX_PARTICIPANT_VOLUME);
        volume.setProgress(DiscordFeatureContract.snapParticipantVolume(participant.volume));
        volume.setKeyProgressIncrement(DiscordFeatureContract.PARTICIPANT_VOLUME_STEP);
        volume.setFocusable(true);
        TextView value = ui.label(context.getString(R.string.discord_volume_value,
                volume.getProgress()));
        value.setContentDescription(context.getString(
                R.string.discord_volume_description, volume.getProgress()));
        boolean[] adjustmentMode = new boolean[]{false};
        Runnable[] pending = new Runnable[1];
        Runnable apply = () -> {
            int snapped = DiscordFeatureContract.snapParticipantVolume(volume.getProgress());
            volume.setProgress(snapped);
            value.setText(context.getString(R.string.discord_volume_value, snapped));
            value.setContentDescription(context.getString(
                    R.string.discord_volume_description, snapped));
            if (pending[0] != null) mainHandler.removeCallbacks(pending[0]);
            pending[0] = () -> updateParticipantVolume(connection, participant, snapped);
            mainHandler.postDelayed(pending[0], DiscordFeatureContract.VOLUME_DEBOUNCE_MS);
        };
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) value.setText(context.getString(
                        R.string.discord_volume_value, progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { apply.run(); }
        });
        volume.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    adjustmentMode[0] = !adjustmentMode[0];
                    value.setText(context.getString(R.string.discord_volume_value,
                            volume.getProgress()) + (adjustmentMode[0]
                            ? context.getString(R.string.discord_adjusting_suffix) : ""));
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (!adjustmentMode[0]) return true;
                if (event.getAction() == KeyEvent.ACTION_UP) apply.run();
            }
            return false;
        });
        volume.setOnFocusChangeListener((view, focused) -> {
            if (!focused) adjustmentMode[0] = false;
        });
        volume.setContentDescription(context.getString(
                R.string.discord_volume_description, volume.getProgress())
                + ". " + context.getString(R.string.discord_volume_adjust_hint));
        TextView mute = ui.action(context.getString(participant.muted
                ? R.string.discord_unmute_participant : R.string.discord_mute_participant));
        mute.setOnClickListener(view -> operation(
                context.getString(R.string.discord_updating_participant_mute),
                () -> client.toggleDiscordParticipantMute(connection, participant.id),
                () -> showParticipants(connection, channel)));
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        LinearLayout sliderRow = new LinearLayout(context);
        sliderRow.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        sliderRow.addView(volume, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sliderRow.addView(value, new LinearLayout.LayoutParams(
                Math.round(110 * context.getResources().getDisplayMetrics().density),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(sliderRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout presets = new LinearLayout(context);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        TextView down = ui.action("−10");
        TextView zero = ui.action("0%");
        TextView fifty = ui.action("50%");
        TextView hundred = ui.action("100%");
        TextView reset = ui.action(context.getString(R.string.discord_volume_default));
        TextView up = ui.action("+10");
        down.setOnClickListener(view -> setParticipantVolume(volume,
                volume.getProgress() - DiscordFeatureContract.PARTICIPANT_VOLUME_STEP, apply));
        zero.setOnClickListener(view -> setParticipantVolume(volume, 0, apply));
        fifty.setOnClickListener(view -> setParticipantVolume(volume, 50, apply));
        hundred.setOnClickListener(view -> setParticipantVolume(volume, 100, apply));
        reset.setOnClickListener(view -> setParticipantVolume(volume,
                DiscordFeatureContract.DEFAULT_PARTICIPANT_VOLUME, apply));
        up.setOnClickListener(view -> setParticipantVolume(volume,
                volume.getProgress() + DiscordFeatureContract.PARTICIPANT_VOLUME_STEP, apply));
        TextView[] controls = {down, zero, fifty, hundred, reset, up};
        for (TextView control : controls) {
            presets.addView(control, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        container.addView(presets, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(mute, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return container;
    }

    void openDiscord(String uuid, String address, String name) {
        hostUuid = uuid;
        hostAddress = address;
        hostName = name == null || name.isEmpty()
                ? context.getString(R.string.discord_selected_host) : name;
        if (connection() == null) {
            showDiscordGatewayRequired();
        } else {
            showDiscordServers(false);
        }
    }

    private void setParticipantVolume(SeekBar volume, int value, Runnable apply) {
        volume.setProgress(DiscordFeatureContract.snapParticipantVolume(value));
        apply.run();
    }

    private void updateParticipantVolume(HostGatewayClient.Connection connection,
                                         HostGatewayClient.DiscordParticipant participant,
                                         int volume) {
        executor.execute(() -> {
            try {
                client.setDiscordParticipantVolume(connection, participant.id, volume);
            } catch (Exception error) {
                mainHandler.post(() -> ui.toast(error.getMessage() == null
                        ? context.getString(R.string.discord_volume_change_failed)
                        : error.getMessage()));
            }
        });
    }

    private void showDiscordSettings(HostGatewayClient.Connection connection) {
        boolean enabled = store.isDiscordEnabled(hostUuid, connection.profileId);
        boolean autoConnect = store.isDiscordAutoConnectEnabled(hostUuid, connection.profileId);
        boolean autoJoin = store.isDiscordAutoJoinLastEnabled(hostUuid, connection.profileId);
        TextView enabledSetting = ui.action(context.getString(
                R.string.discord_integration_setting,
                context.getString(enabled ? R.string.discord_state_on
                        : R.string.discord_state_off)));
        TextView connectSetting = ui.action(context.getString(
                R.string.discord_auto_connect_setting,
                context.getString(autoConnect ? R.string.discord_state_on
                        : R.string.discord_state_off)));
        TextView joinSetting = ui.action(context.getString(
                R.string.discord_auto_join_setting,
                context.getString(autoJoin ? R.string.discord_state_on
                        : R.string.discord_state_off)));
        TextView start = ui.action(context.getString(R.string.discord_start_on_host));
        TextView reconnect = ui.action(context.getString(R.string.discord_connect_rpc));
        TextView audio = ui.action(context.getString(R.string.discord_audio_devices_action));
        TextView status = ui.action(context.getString(R.string.discord_refresh_status));
        TextView back = ui.back(context.getString(R.string.discord_back_action));
        enabledSetting.setOnClickListener(view -> {
            store.setDiscordEnabled(hostUuid, connection.profileId, !enabled);
            showDiscordSettings(connection);
        });
        connectSetting.setOnClickListener(view -> {
            store.setDiscordAutoConnectEnabled(hostUuid, connection.profileId, !autoConnect);
            showDiscordSettings(connection);
        });
        joinSetting.setOnClickListener(view -> {
            store.setDiscordAutoJoinLastEnabled(hostUuid, connection.profileId, !autoJoin);
            showDiscordSettings(connection);
        });
        start.setOnClickListener(view -> operation(
                context.getString(R.string.discord_starting),
                () -> client.startDiscord(connection), () -> showDiscordSettings(connection)));
        reconnect.setOnClickListener(view -> operation(
                context.getString(R.string.discord_connecting_rpc),
                () -> client.connectDiscord(connection, false), () -> showDiscordSettings(connection)));
        audio.setOnClickListener(view -> showAudio(connection));
        status.setOnClickListener(view -> showDiscordStatus(connection));
        ui.show(context.getString(R.string.discord_panel_title),
                context.getString(R.string.discord_settings_title),
                context.getString(R.string.discord_profile_details, connection.profileId),
                enabledSetting, connectSetting, joinSetting, start, reconnect, audio, status, back);
    }

    private void showDiscordStatus(HostGatewayClient.Connection connection) {
        showDiscordBusy(context.getString(R.string.discord_status_title),
                context.getString(R.string.discord_checking_bridge));
        load(context.getString(R.string.discord_status_error),
                () -> client.getDiscordStatus(connection), status -> {
            TextView back = ui.back(context.getString(R.string.discord_back_action));
            ui.show(context.getString(R.string.discord_panel_title),
                    context.getString(R.string.discord_status_title),
                    context.getString(R.string.discord_status_bridge,
                            context.getString(status.bridgeOnline
                                    ? R.string.discord_state_online
                                    : R.string.discord_state_offline)) +
                            "\n" + context.getString(R.string.discord_status_rpc,
                            context.getString(status.rpcConnected
                                    ? R.string.discord_state_connected
                                    : R.string.discord_state_disconnected)) +
                            "\n" + context.getString(R.string.discord_status_authenticated,
                            context.getString(status.authenticated
                                    ? R.string.discord_state_yes : R.string.discord_state_no)) +
                            (status.error.isEmpty() ? "" : "\n" + status.error), back);
        }, () -> showDiscordStatus(connection));
    }

    private void showAudio(HostGatewayClient.Connection connection) {
        showDiscordBusy(context.getString(R.string.discord_audio_title),
                context.getString(R.string.discord_loading_audio));
        load(context.getString(R.string.discord_audio_error),
                () -> client.getDiscordAudioState(connection), audio -> {
            List<View> actions = new ArrayList<>();
            if (audio.systemAvailable) {
                actions.add(ui.label(context.getString(R.string.discord_windows_audio,
                        audio.systemVolume, audio.systemMuted
                                ? context.getString(R.string.discord_muted_suffix) : "")));
                TextView down = ui.action(context.getString(
                        R.string.discord_system_volume_change, -5));
                TextView up = ui.action(context.getString(
                        R.string.discord_system_volume_change, 5));
                TextView mute = ui.action(context.getString(audio.systemMuted
                        ? R.string.discord_unmute_system : R.string.discord_mute_system));
                down.setOnClickListener(view -> operation(
                        context.getString(R.string.discord_lowering_system_volume),
                        () -> client.changeSystemVolume(connection, -5), () -> showAudio(connection)));
                up.setOnClickListener(view -> operation(
                        context.getString(R.string.discord_raising_system_volume),
                        () -> client.changeSystemVolume(connection, 5), () -> showAudio(connection)));
                mute.setOnClickListener(view -> operation(
                        context.getString(R.string.discord_updating_system_mute),
                        () -> client.toggleSystemMute(connection), () -> showAudio(connection)));
                actions.add(down); actions.add(up); actions.add(mute);
            }
            addAudioDevices(actions, connection,
                    context.getString(R.string.discord_system_devices), audio.systemDevices);
            addAudioDevices(actions, connection,
                    context.getString(R.string.discord_discord_devices), audio.discordDevices);
            if (!audio.error.isEmpty()) actions.add(ui.label(audio.error));
            TextView refresh = ui.action(context.getString(R.string.discord_refresh));
            refresh.setOnClickListener(view -> showAudio(connection));
            TextView back = ui.back(context.getString(R.string.discord_back_action));
            actions.add(refresh); actions.add(back);
            ui.show(context.getString(R.string.discord_panel_title),
                    context.getString(R.string.discord_audio_title),
                    context.getString(R.string.discord_audio_details),
                    actions.toArray(new View[0]));
        }, () -> showAudio(connection));
    }

    private void addAudioDevices(List<View> actions, HostGatewayClient.Connection connection,
                                 String title, List<HostGatewayClient.AudioDevice> devices) {
        if (devices.isEmpty()) return;
        actions.add(ui.label(title));
        for (HostGatewayClient.AudioDevice device : devices) {
            TextView action = ui.action((device.current ? "●  " : "") + device.name +
                    "  ·  " + device.flow);
            action.setOnClickListener(view -> operation(
                    context.getString(R.string.discord_selecting_audio_device),
                    () -> client.selectAudioDevice(connection, device), () -> showAudio(connection)));
            actions.add(action);
        }
    }

    private HostGatewayClient.Connection connection() {
        return hostUuid == null ? null : store.loadClientConnection(hostUuid, hostAddress);
    }

    private void showGatewayBusy(String title, String message) {
        currentPanelTitle = R.string.host_integrations_title;
        ui.busy(context.getString(R.string.host_integrations_title), title, message);
    }

    private void showDiscordBusy(String title, String message) {
        currentPanelTitle = R.string.discord_panel_title;
        ui.busy(context.getString(R.string.discord_panel_title), title, message);
    }

    private void operation(String message, Task<?> task, Runnable success) {
        showDiscordBusy(context.getString(R.string.discord_working_title), message);
        load(context.getString(R.string.discord_operation_error),
                task, ignored -> success.run(), success);
    }

    private <T> void load(String errorTitle, Task<T> task, Result<T> success, Runnable retry) {
        int request = requestGeneration.incrementAndGet();
        executor.execute(() -> {
            try {
                T value = task.run();
                mainHandler.post(() -> {
                    if (request == requestGeneration.get()) success.accept(value);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (request != requestGeneration.get()) return;
                    TextView retryAction = ui.action(context.getString(R.string.discord_retry));
                    retryAction.setOnClickListener(view -> retry.run());
                    TextView back = ui.back(context.getString(R.string.discord_back_action));
                    String message = error.getMessage();
                    ui.show(context.getString(currentPanelTitle), errorTitle,
                            message == null || message.isEmpty()
                                    ? context.getString(R.string.discord_unusable_response) : message,
                            retryAction, back);
                });
            }
        });
    }

    private static final class FrameContainer extends android.widget.FrameLayout {
        FrameContainer(Context context, int padding, View child) {
            super(context);
            setPadding(padding, 0, padding, 0);
            addView(child, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
    }
}
