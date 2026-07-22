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
        hostUuid = uuid;
        hostAddress = address;
        hostName = name == null || name.isEmpty()
                ? context.getString(R.string.discord_selected_host) : name;
        HostGatewayClient.Connection connection = connection();
        if (connection == null) {
            TextView pair = ui.action("PAIR HOST GATEWAY");
            pair.setOnClickListener(view -> showPairingDialog());
            ui.show("HOST INTEGRATIONS", hostName,
                    "Pair this TV with the Wake & Play Gateway running on the selected PC.", pair);
            return;
        }

        boolean enabled = store.isDiscordEnabled(hostUuid, connection.profileId);
        TextView discord = ui.action("OPEN DISCORD  ›");
        TextView discordSettings = ui.action("DISCORD SETTINGS  ›");
        TextView toggle = ui.action(context.getString(enabled
                ? R.string.discord_disable_integration : R.string.discord_enable_integration));
        TextView profiles = ui.action("INTEGRATION PROFILE  ·  " + connection.profileId);
        TextView refresh = ui.action("REFRESH STATUS");
        TextView forget = ui.action("FORGET GATEWAY");
        discord.setEnabled(enabled);
        discord.setAlpha(enabled ? 1f : .5f);
        discord.setOnClickListener(view -> showDiscordServers(false));
        discordSettings.setOnClickListener(view -> showDiscordSettings(connection));
        toggle.setOnClickListener(view -> {
            store.setDiscordEnabled(hostUuid, connection.profileId, !enabled);
            showHostIntegrations(hostUuid, hostAddress, hostName);
        });
        profiles.setOnClickListener(view -> showProfiles(connection));
        refresh.setOnClickListener(view -> showHostIntegrationStatus(connection));
        forget.setOnClickListener(view -> confirmForget());
        ui.show("HOST INTEGRATIONS", hostName,
                context.getString(R.string.discord_paired_gateway, connection.endpoint) +
                        (enabled ? "" : "\n" + context.getString(
                                R.string.discord_integration_disabled_details)),
                discord, discordSettings, toggle, profiles, refresh, forget);
    }

    private void showHostIntegrationStatus(HostGatewayClient.Connection connection) {
        TextView loading = ui.label("Checking Gateway and Discord Bridge…");
        TextView discord = ui.action("OPEN DISCORD  ›");
        TextView discordSettings = ui.action("DISCORD SETTINGS  ›");
        TextView profiles = ui.action("INTEGRATION PROFILE  ·  " + connection.profileId);
        TextView forget = ui.action("FORGET GATEWAY");
        discord.setOnClickListener(view -> showDiscordServers(false));
        discordSettings.setOnClickListener(view -> showDiscordSettings(connection));
        profiles.setOnClickListener(view -> showProfiles(connection));
        forget.setOnClickListener(view -> confirmForget());
        ui.show("HOST INTEGRATIONS", hostName, connection.endpoint,
                loading, discord, discordSettings, profiles, forget);
        load("Gateway status", () -> client.getCapabilities(connection), capabilities -> {
            loading.setText("Gateway online  ·  Discord Bridge " +
                    (capabilities.discord ? "online" : "offline"));
        }, () -> showHostIntegrationStatus(connection));
    }

    private void showPairingDialog() {
        if (hostUuid == null || hostAddress == null || hostAddress.trim().isEmpty()) {
            ui.toast("Select an online host before pairing.");
            return;
        }
        EditText code = new EditText(context);
        code.setSingleLine(true);
        code.setHint("6-digit code");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        int padding = Math.round(24 * context.getResources().getDisplayMetrics().density);
        FrameContainer container = new FrameContainer(context, padding, code);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Pair Host Gateway")
                .setMessage("Enter the code shown by the Gateway on " + hostName + ".")
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Pair", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String value = code.getText().toString().trim();
                    if (!value.matches("[0-9]{6}")) {
                        code.setError("Enter all six digits");
                        return;
                    }
                    dialog.dismiss();
                    pair(value);
                }));
        dialog.show();
        code.requestFocus();
    }

    private void pair(String code) {
        String endpoint = HostGatewayClient.endpointForHost(hostAddress);
        showBusy("PAIRING GATEWAY", "Connecting securely to " + endpoint + "…");
        load("Pairing failed", () -> client.pair(endpoint, code, "Moonlight Android TV"), pairing -> {
            store.save(hostUuid, pairing.connection);
            showHostIntegrations(hostUuid, hostAddress, hostName);
        }, this::showPairingDialog);
    }

    private void confirmForget() {
        new AlertDialog.Builder(context)
                .setTitle("Forget Host Gateway?")
                .setMessage("The certificate pin, access token and Discord preferences for this host will be removed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Forget", (dialog, which) -> {
                    store.remove(hostUuid);
                    showHostIntegrations(hostUuid, hostAddress, hostName);
                }).show();
    }

    private void showProfiles(HostGatewayClient.Connection connection) {
        showBusy("INTEGRATION PROFILES", "Loading profiles…");
        load("Unable to load profiles", () -> client.getIntegrationProfiles(connection), profiles -> {
            List<View> actions = new ArrayList<>();
            for (HostGatewayClient.IntegrationProfile profile : profiles.profiles) {
                String suffix = profile.id.equals(connection.profileId) ? "  ·  SELECTED" : "";
                TextView action = ui.action(profile.name + suffix);
                action.setOnClickListener(view -> {
                    store.setSelectedIntegrationProfileId(hostUuid, profile.id);
                    showHostIntegrations(hostUuid, hostAddress, hostName);
                });
                actions.add(action);
            }
            TextView back = ui.back("BACK");
            actions.add(back);
            ui.show("HOST INTEGRATIONS", "Integration profile",
                    "Choose the Windows and Discord session used for this host.",
                    actions.toArray(new View[0]));
        }, () -> showHostIntegrations(hostUuid, hostAddress, hostName));
    }

    void showDiscordServers(boolean force) {
        HostGatewayClient.Connection connection = connection();
        if (connection == null) {
            showHostIntegrations(hostUuid, hostAddress, hostName);
            return;
        }
        if (!store.isDiscordEnabled(hostUuid, connection.profileId)) {
            TextView enable = ui.action(context.getString(R.string.discord_enable_integration));
            enable.setOnClickListener(view -> {
                store.setDiscordEnabled(hostUuid, connection.profileId, true);
                showDiscordServers(true);
            });
            TextView back = ui.back(context.getString(R.string.discord_back_to_host_integrations));
            ui.show(context.getString(R.string.overlay_discord_title),
                    context.getString(R.string.discord_integration_disabled),
                    context.getString(R.string.discord_enable_gateway_details),
                    enable, back);
            return;
        }
        showBusy("DISCORD", "Loading servers and recent channels…");
        load("Unable to load Discord", () -> loadDiscordHome(connection, force), home -> {
            List<View> actions = new ArrayList<>();
            addChannelGroup(actions, "FAVORITES", home.favorites, connection);
            addChannelGroup(actions, "RECENT", home.recent, connection);
            if (!home.guilds.isEmpty()) actions.add(ui.label("SERVERS"));
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
            TextView settings = ui.action("DISCORD SETTINGS  ›");
            settings.setOnClickListener(view -> showDiscordSettings(connection));
            TextView refresh = ui.action("REFRESH");
            refresh.setOnClickListener(view -> showDiscordServers(true));
            TextView back = ui.back(context.getString(R.string.discord_back_to_host_integrations));
            actions.add(settings); actions.add(refresh); actions.add(back);
            ui.show("DISCORD", "Servers and voice channels",
                    "Favorites and recent channels are shown first.", actions.toArray(new View[0]));
        }, () -> showDiscordServers(true));
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
        showBusy("DISCORD", "Loading " + guild.name + "…");
        load("Unable to load channels", () -> client.getDiscordChannels(connection, guild, force), channels -> {
            List<View> actions = new ArrayList<>();
            if (channels.isEmpty()) actions.add(ui.label(
                    context.getString(R.string.discord_no_channels)));
            for (HostGatewayClient.DiscordChannel channel : channels) {
                String people = channel.people >= 0 ? "  ·  " + channel.people : "";
                TextView action = ui.action((channel.favorite ? "★  " : "#  ") + channel.name + people);
                action.setOnClickListener(view -> showDiscordChannel(connection, channel, false));
                actions.add(action);
            }
            TextView refresh = ui.action("REFRESH");
            refresh.setOnClickListener(view -> showDiscordChannels(connection, guild, true));
            TextView back = ui.back("BACK");
            actions.add(refresh); actions.add(back);
            ui.show("DISCORD", guild.name, "Select a voice channel.", actions.toArray(new View[0]));
        }, () -> showDiscordChannels(connection, guild, true));
    }

    private void showDiscordChannel(HostGatewayClient.Connection connection,
                                    HostGatewayClient.DiscordChannel channel, boolean force) {
        showBusy("DISCORD", "Loading #" + channel.name + "…");
        load("Unable to load channel", () -> client.getDiscordVoice(connection, force), voice -> {
            boolean active = voice.connected && channel.id.equals(voice.channelId);
            List<View> actions = new ArrayList<>();
            if (active) {
                TextView mute = ui.action(voice.muted ? "UNMUTE MICROPHONE" : "MUTE MICROPHONE");
                mute.setOnClickListener(view -> operation("Updating microphone…",
                        () -> client.setDiscordVoiceFlag(connection, "mute", "toggle"),
                        () -> showDiscordChannel(connection, channel, true)));
                TextView deafen = ui.action(voice.deafened ? "ENABLE AUDIO" : "DISABLE AUDIO");
                deafen.setOnClickListener(view -> operation("Updating Discord audio…",
                        () -> client.setDiscordVoiceFlag(connection, "deafen", "toggle"),
                        () -> showDiscordChannel(connection, channel, true)));
                TextView leave = ui.action("LEAVE CHANNEL");
                leave.setOnClickListener(view -> operation("Leaving channel…",
                        () -> client.leaveDiscordChannel(connection),
                        () -> showDiscordChannel(connection, channel, true)));
                TextView people = ui.action("PEOPLE  ·  " + voice.participants + "  ›");
                people.setOnClickListener(view -> showParticipants(connection, channel));
                actions.add(mute); actions.add(deafen); actions.add(leave); actions.add(people);
            } else {
                TextView join = ui.action("JOIN #" + channel.name);
                join.setOnClickListener(view -> operation("Joining channel…",
                        () -> client.joinDiscordChannel(connection, channel), () -> {
                            store.saveLastDiscordChannel(hostUuid, connection.profileId,
                                    channel.id, channel.guildId, channel.guildName, channel.name);
                            showDiscordChannel(connection, channel, true);
                        }));
                actions.add(join);
            }
            TextView back = ui.back("BACK");
            actions.add(back);
            ui.show("DISCORD", "# " + channel.name,
                    channel.guildName + (active ? "  ·  connected" : ""),
                    actions.toArray(new View[0]));
        }, () -> showDiscordChannel(connection, channel, true));
    }

    private void showParticipants(HostGatewayClient.Connection connection,
                                  HostGatewayClient.DiscordChannel channel) {
        showBusy("DISCORD", "Loading people…");
        load("Unable to load participants", () -> client.getDiscordVoice(connection, true), voice -> {
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
            TextView refresh = ui.action("REFRESH");
            refresh.setOnClickListener(view -> showParticipants(connection, channel));
            TextView back = ui.back("BACK");
            actions.add(refresh); actions.add(back);
            ui.show("DISCORD", "People", voice.channelName,
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
                            volume.getProgress()) + (adjustmentMode[0] ? " · ADJUSTING" : ""));
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
                + ". Press select to adjust.");
        TextView mute = ui.action(context.getString(participant.muted
                ? R.string.discord_unmute_participant : R.string.discord_mute_participant));
        mute.setOnClickListener(view -> operation("Updating participant mute…",
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
            showHostIntegrations(uuid, address, name);
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
                context.getString(enabled ? R.string.console_on : R.string.console_off)));
        TextView connectSetting = ui.action("AUTO-CONNECT  ·  " + (autoConnect ? "ON" : "OFF"));
        TextView joinSetting = ui.action("AUTO-JOIN LAST CHANNEL  ·  " + (autoJoin ? "ON" : "OFF"));
        TextView start = ui.action("START DISCORD ON HOST");
        TextView reconnect = ui.action("CONNECT DISCORD RPC");
        TextView audio = ui.action("AUDIO DEVICES  ›");
        TextView status = ui.action("REFRESH STATUS");
        TextView back = ui.back("BACK");
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
        start.setOnClickListener(view -> operation("Starting Discord…",
                () -> client.startDiscord(connection), () -> showDiscordSettings(connection)));
        reconnect.setOnClickListener(view -> operation("Connecting Discord RPC…",
                () -> client.connectDiscord(connection, false), () -> showDiscordSettings(connection)));
        audio.setOnClickListener(view -> showAudio(connection));
        status.setOnClickListener(view -> showDiscordStatus(connection));
        ui.show("DISCORD", "Settings", "Integration profile: " + connection.profileId,
                enabledSetting, connectSetting, joinSetting, start, reconnect, audio, status, back);
    }

    private void showDiscordStatus(HostGatewayClient.Connection connection) {
        showBusy("DISCORD", "Checking Discord Bridge…");
        load("Unable to read Discord status", () -> client.getDiscordStatus(connection), status -> {
            TextView back = ui.back("BACK");
            ui.show("DISCORD", "Connection status",
                    "Bridge: " + (status.bridgeOnline ? "online" : "offline") +
                            "\nRPC: " + (status.rpcConnected ? "connected" : "disconnected") +
                            "\nAuthenticated: " + (status.authenticated ? "yes" : "no") +
                            (status.error.isEmpty() ? "" : "\n" + status.error), back);
        }, () -> showDiscordStatus(connection));
    }

    private void showAudio(HostGatewayClient.Connection connection) {
        showBusy("DISCORD", "Loading audio devices…");
        load("Unable to load audio devices", () -> client.getDiscordAudioState(connection), audio -> {
            List<View> actions = new ArrayList<>();
            if (audio.systemAvailable) {
                actions.add(ui.label("WINDOWS AUDIO  ·  " + audio.systemVolume + "%" +
                        (audio.systemMuted ? "  ·  MUTED" : "")));
                TextView down = ui.action("SYSTEM VOLUME  −5");
                TextView up = ui.action("SYSTEM VOLUME  +5");
                TextView mute = ui.action(audio.systemMuted ? "UNMUTE SYSTEM" : "MUTE SYSTEM");
                down.setOnClickListener(view -> operation("Lowering system volume…",
                        () -> client.changeSystemVolume(connection, -5), () -> showAudio(connection)));
                up.setOnClickListener(view -> operation("Raising system volume…",
                        () -> client.changeSystemVolume(connection, 5), () -> showAudio(connection)));
                mute.setOnClickListener(view -> operation("Updating system mute…",
                        () -> client.toggleSystemMute(connection), () -> showAudio(connection)));
                actions.add(down); actions.add(up); actions.add(mute);
            }
            addAudioDevices(actions, connection, "SYSTEM DEVICES", audio.systemDevices);
            addAudioDevices(actions, connection, "DISCORD DEVICES", audio.discordDevices);
            if (!audio.error.isEmpty()) actions.add(ui.label(audio.error));
            TextView refresh = ui.action("REFRESH");
            refresh.setOnClickListener(view -> showAudio(connection));
            TextView back = ui.back("BACK");
            actions.add(refresh); actions.add(back);
            ui.show("DISCORD", "Audio", "Choose Windows and Discord audio devices.",
                    actions.toArray(new View[0]));
        }, () -> showAudio(connection));
    }

    private void addAudioDevices(List<View> actions, HostGatewayClient.Connection connection,
                                 String title, List<HostGatewayClient.AudioDevice> devices) {
        if (devices.isEmpty()) return;
        actions.add(ui.label(title));
        for (HostGatewayClient.AudioDevice device : devices) {
            TextView action = ui.action((device.current ? "●  " : "") + device.name +
                    "  ·  " + device.flow.toUpperCase(java.util.Locale.ROOT));
            action.setOnClickListener(view -> operation("Selecting audio device…",
                    () -> client.selectAudioDevice(connection, device), () -> showAudio(connection)));
            actions.add(action);
        }
    }

    private HostGatewayClient.Connection connection() {
        return hostUuid == null ? null : store.loadClientConnection(hostUuid, hostAddress);
    }

    private void showBusy(String title, String message) {
        ui.busy("HOST INTEGRATIONS", title, message);
    }

    private void operation(String message, Task<?> task, Runnable success) {
        showBusy("DISCORD", message);
        load("Discord operation failed", task, ignored -> success.run(), success);
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
                    TextView retryAction = ui.action("RETRY");
                    retryAction.setOnClickListener(view -> retry.run());
                    TextView back = ui.back("BACK");
                    String message = error.getMessage();
                    ui.show("HOST INTEGRATIONS", errorTitle,
                            message == null || message.isEmpty() ? "The host did not return a usable response." : message,
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
