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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns the Gateway pairing and Discord panel tree without participating in streaming. */
final class DiscordPanelController {
    interface Ui {
        TextView action(String label);
        TextView label(String label);
        void show(String eyebrow, String title, String details, View... actions);
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

    void showHostIntegrations(String uuid, String address, String name) {
        hostUuid = uuid;
        hostAddress = address;
        hostName = name == null || name.isEmpty() ? "Selected host" : name;
        HostGatewayClient.Connection connection = connection();
        if (connection == null) {
            TextView pair = ui.action("PAIR HOST GATEWAY");
            pair.setOnClickListener(view -> showPairingDialog());
            ui.show("HOST INTEGRATIONS", hostName,
                    "Pair this TV with the Wake & Play Gateway running on the selected PC.", pair);
            return;
        }

        TextView discord = ui.action("OPEN DISCORD  ›");
        TextView discordSettings = ui.action("DISCORD SETTINGS  ›");
        TextView profiles = ui.action("INTEGRATION PROFILE  ·  " + connection.profileId);
        TextView refresh = ui.action("REFRESH STATUS");
        TextView forget = ui.action("FORGET GATEWAY");
        discord.setOnClickListener(view -> showDiscordServers(false));
        discordSettings.setOnClickListener(view -> showDiscordSettings(connection));
        profiles.setOnClickListener(view -> showProfiles(connection));
        refresh.setOnClickListener(view -> showHostIntegrationStatus(connection));
        forget.setOnClickListener(view -> confirmForget());
        ui.show("HOST INTEGRATIONS", hostName,
                "Paired Gateway: " + connection.endpoint,
                discord, discordSettings, profiles, refresh, forget);
        showHostIntegrationStatus(connection);
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
            TextView back = ui.action("BACK");
            back.setOnClickListener(view -> showHostIntegrations(hostUuid, hostAddress, hostName));
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
        showBusy("DISCORD", "Loading servers and recent channels…");
        load("Unable to load Discord", () -> client.getDiscordHome(connection, force), home -> {
            List<View> actions = new ArrayList<>();
            addChannelGroup(actions, "FAVORITES", home.favorites, connection);
            addChannelGroup(actions, "RECENT", home.recent, connection);
            if (!home.guilds.isEmpty()) actions.add(ui.label("SERVERS"));
            for (HostGatewayClient.DiscordGuild guild : home.guilds) {
                TextView action = ui.action(guild.name + "  ›");
                action.setOnClickListener(view -> showDiscordChannels(connection, guild, false));
                actions.add(action);
            }
            TextView settings = ui.action("DISCORD SETTINGS  ›");
            settings.setOnClickListener(view -> showDiscordSettings(connection));
            TextView refresh = ui.action("REFRESH");
            refresh.setOnClickListener(view -> showDiscordServers(true));
            TextView back = ui.action("BACK TO HOST INTEGRATIONS");
            back.setOnClickListener(view -> showHostIntegrations(hostUuid, hostAddress, hostName));
            actions.add(settings); actions.add(refresh); actions.add(back);
            ui.show("DISCORD", "Servers and voice channels",
                    "Favorites and recent channels are shown first.", actions.toArray(new View[0]));
        }, () -> showDiscordServers(true));
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
            for (HostGatewayClient.DiscordChannel channel : channels) {
                String people = channel.people >= 0 ? "  ·  " + channel.people : "";
                TextView action = ui.action((channel.favorite ? "★  " : "#  ") + channel.name + people);
                action.setOnClickListener(view -> showDiscordChannel(connection, channel, false));
                actions.add(action);
            }
            TextView refresh = ui.action("REFRESH");
            refresh.setOnClickListener(view -> showDiscordChannels(connection, guild, true));
            TextView back = ui.action("BACK");
            back.setOnClickListener(view -> showDiscordServers(false));
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
                TextView leave = ui.action("LEAVE CHANNEL");
                leave.setOnClickListener(view -> operation("Leaving channel…",
                        () -> client.leaveDiscordChannel(connection),
                        () -> showDiscordChannel(connection, channel, true)));
                TextView people = ui.action("PEOPLE  ·  " + voice.participants + "  ›");
                people.setOnClickListener(view -> showParticipants(connection, channel));
                actions.add(mute); actions.add(leave); actions.add(people);
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
            TextView back = ui.action("BACK");
            back.setOnClickListener(view -> showDiscordServers(false));
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
            for (HostGatewayClient.DiscordParticipant participant : voice.participantList) {
                actions.add(ui.label((participant.speaking ? "●  " : "") + participant.name +
                        (participant.self ? "  ·  YOU" : "") +
                        (participant.muted ? "  ·  MUTED" : "") +
                        "\nVolume " + participant.volume + "%"));
                if (!participant.self) actions.add(participantControls(connection, channel, participant));
            }
            TextView refresh = ui.action("REFRESH");
            refresh.setOnClickListener(view -> showParticipants(connection, channel));
            TextView back = ui.action("BACK");
            back.setOnClickListener(view -> showDiscordChannel(connection, channel, false));
            actions.add(refresh); actions.add(back);
            ui.show("DISCORD", "People", voice.channelName,
                    actions.toArray(new View[0]));
        }, () -> showParticipants(connection, channel));
    }

    private View participantControls(HostGatewayClient.Connection connection,
                                     HostGatewayClient.DiscordChannel channel,
                                     HostGatewayClient.DiscordParticipant participant) {
        SeekBar volume = new SeekBar(context);
        volume.setMax(200);
        volume.setProgress(participant.volume);
        volume.setKeyProgressIncrement(10);
        volume.setFocusable(true);
        Runnable apply = () -> {
            int snapped = Math.max(0, Math.min(200, Math.round(volume.getProgress() / 10f) * 10));
            volume.setProgress(snapped);
            operation("Updating participant volume…",
                    () -> client.setDiscordParticipantVolume(connection, participant.id, snapped),
                    () -> showParticipants(connection, channel));
        };
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { apply.run(); }
        });
        volume.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                apply.run();
            }
            return false;
        });
        TextView mute = ui.action(participant.muted ? "UNMUTE" : "MUTE");
        mute.setOnClickListener(view -> operation("Updating participant mute…",
                () -> client.toggleDiscordParticipantMute(connection, participant.id),
                () -> showParticipants(connection, channel)));
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(volume, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams button = new LinearLayout.LayoutParams(
                Math.round(130 * context.getResources().getDisplayMetrics().density),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        button.leftMargin = Math.round(10 * context.getResources().getDisplayMetrics().density);
        row.addView(mute, button);
        return row;
    }

    private void showDiscordSettings(HostGatewayClient.Connection connection) {
        boolean autoConnect = store.isDiscordAutoConnectEnabled(hostUuid, connection.profileId);
        boolean autoJoin = store.isDiscordAutoJoinLastEnabled(hostUuid, connection.profileId);
        TextView connectSetting = ui.action("AUTO-CONNECT  ·  " + (autoConnect ? "ON" : "OFF"));
        TextView joinSetting = ui.action("AUTO-JOIN LAST CHANNEL  ·  " + (autoJoin ? "ON" : "OFF"));
        TextView start = ui.action("START DISCORD ON HOST");
        TextView reconnect = ui.action("CONNECT DISCORD RPC");
        TextView audio = ui.action("AUDIO DEVICES  ›");
        TextView status = ui.action("REFRESH STATUS");
        TextView back = ui.action("BACK");
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
        back.setOnClickListener(view -> showDiscordServers(false));
        ui.show("DISCORD", "Settings", "Integration profile: " + connection.profileId,
                connectSetting, joinSetting, start, reconnect, audio, status, back);
    }

    private void showDiscordStatus(HostGatewayClient.Connection connection) {
        showBusy("DISCORD", "Checking Discord Bridge…");
        load("Unable to read Discord status", () -> client.getDiscordStatus(connection), status -> {
            TextView back = ui.action("BACK");
            back.setOnClickListener(view -> showDiscordSettings(connection));
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
            TextView back = ui.action("BACK");
            back.setOnClickListener(view -> showDiscordSettings(connection));
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
        ui.show("HOST INTEGRATIONS", title, message, ui.label("Please wait…"));
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
                    TextView back = ui.action("BACK");
                    back.setOnClickListener(view -> showHostIntegrations(hostUuid, hostAddress, hostName));
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
