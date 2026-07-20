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
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.overlay.DiscordGatewayClient;
import com.limelight.ui.overlay.OverlayMenuView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Discord state polling and overlay actions, deliberately independent of the stream transport. */
public final class DiscordOverlayController {
    private static final String PREFS = "discord_overlay_state";
    private static final String DOCK_KEY_PREFIX = "dock_enabled.";
    private static final long REFRESH_MS = 2_000L;

    private final Activity activity;
    private final OverlayMenuView overlay;
    private final LinearLayout dock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean refreshInFlight = new AtomicBoolean();
    private final AtomicBoolean actionInFlight = new AtomicBoolean();
    private final DiscordGatewayClient client = new DiscordGatewayClient();
    private final HostGatewayClient gatewayClient = new HostGatewayClient();
    private final HostGatewayStore store;
    private final String hostUuid;
    private final Runnable scheduledRefresh = () -> refresh(false);

    private DiscordGatewayClient.Connection connection;
    private DiscordGatewayClient.VoiceState voice;
    private DiscordGatewayClient.ChannelTarget recentChannel;
    private boolean dockEnabled;
    private boolean destroyed;

    public DiscordOverlayController(Activity activity, OverlayMenuView overlay,
                                    LinearLayout dock, PreferenceConfiguration preferences,
                                    String hostUuid, String activeHost) {
        this.activity = activity;
        this.overlay = overlay;
        this.dock = dock;
        this.hostUuid = hostUuid == null ? "" : hostUuid;
        store = new HostGatewayStore(activity);
        GatewayConnection stored = store.loadForHost(hostUuid, activeHost);
        try {
            connection = stored == null ? null : new DiscordGatewayClient.Connection(
                    stored.endpoint, stored.token, stored.certificateSha256, stored.profileId);
        } catch (IllegalArgumentException invalidConnection) {
            connection = null;
        }
        SharedPreferences state = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        dockEnabled = state.getBoolean(DOCK_KEY_PREFIX + this.hostUuid, false);
        overlay.setDiscordConfigured(connection != null);
        overlay.setDiscordDocked(dockEnabled);
        overlay.setDiscordShortcuts(preferences.discordMuteShortcut,
                preferences.discordLeaveShortcut);
        renderDock();
        prepareDiscord(stored);
    }

    public void onOverlayShown() {
        refresh(true);
    }

    public void onOverlayClosed() {
        renderDock();
        schedule();
    }

    public void toggleMute() {
        if (voice == null || !voice.connected) {
            unavailable(R.string.overlay_discord_unavailable);
            return;
        }
        runAction(() -> client.toggleMute(connection));
    }

    public void leave() {
        if (voice == null || !voice.connected) {
            unavailable(R.string.overlay_discord_unavailable);
            return;
        }
        recentChannel = new DiscordGatewayClient.ChannelTarget(voice.channelId,
                voice.channelName, voice.guildId, "Discord");
        runAction(() -> client.leaveVoice(connection));
    }

    public void rejoin() {
        if (recentChannel == null) {
            unavailable(R.string.overlay_discord_no_recent_channel);
            return;
        }
        DiscordGatewayClient.ChannelTarget target = recentChannel;
        runAction(() -> client.joinChannel(connection, target));
    }

    public void toggleDock() {
        dockEnabled = !dockEnabled;
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(DOCK_KEY_PREFIX + hostUuid, dockEnabled).apply();
        overlay.setDiscordDocked(dockEnabled);
        renderDock();
        if (dockEnabled) refresh(true);
        else schedule();
    }

    public void destroy() {
        destroyed = true;
        handler.removeCallbacks(scheduledRefresh);
        executor.shutdownNow();
    }

    private void refresh(boolean force) {
        handler.removeCallbacks(scheduledRefresh);
        if (destroyed || connection == null || !refreshInFlight.compareAndSet(false, true)) return;
        overlay.setDiscordState(voice, null, true);
        DiscordGatewayClient.Connection current = connection;
        executor.execute(() -> {
            DiscordGatewayClient.VoiceState result = null;
            DiscordGatewayClient.ChannelTarget recent = recentChannel;
            String error = null;
            try {
                result = client.getVoice(current, force);
                if (result.connected && !result.channelId.isEmpty() && !result.guildId.isEmpty()) {
                    recent = new DiscordGatewayClient.ChannelTarget(result.channelId,
                            result.channelName, result.guildId, "Discord");
                } else if (recent == null) {
                    HostGatewayStore.DiscordChannelSelection saved =
                            store.loadLastDiscordChannel(hostUuid, currentProfile());
                    if (saved != null) {
                        recent = new DiscordGatewayClient.ChannelTarget(saved.channelId,
                                saved.channelName, saved.guildId, saved.guildName);
                    } else {
                        recent = client.getRecentChannel(current);
                    }
                }
            } catch (Exception failure) {
                error = failure.getMessage();
            }
            DiscordGatewayClient.VoiceState finalResult = result;
            DiscordGatewayClient.ChannelTarget finalRecent = recent;
            String finalError = error;
            handler.post(() -> {
                refreshInFlight.set(false);
                if (destroyed || current != connection) return;
                if (finalResult != null) voice = finalResult;
                if (finalRecent != null) recentChannel = finalRecent;
                boolean connected = voice != null && voice.connected;
                overlay.setDiscordRejoinTarget(!connected && recentChannel != null,
                        recentChannel == null ? "" : recentChannel.channelName);
                if (overlay.getVisibility() == View.VISIBLE) {
                    overlay.setDiscordState(voice, finalError, false);
                }
                renderDock();
                schedule();
            });
        });
    }

    private String currentProfile() {
        GatewayConnection stored = store.load(hostUuid);
        return stored == null ? GatewayConnection.DEFAULT_PROFILE_ID : stored.profileId;
    }

    private void prepareDiscord(GatewayConnection stored) {
        if (stored == null) return;
        boolean autoConnect = store.isDiscordAutoConnectEnabled(hostUuid, stored.profileId);
        boolean autoJoin = store.isDiscordAutoJoinLastEnabled(hostUuid, stored.profileId);
        if (!autoConnect && !autoJoin) return;
        HostGatewayClient.Connection gatewayConnection = new HostGatewayClient.Connection(
                stored.endpoint, stored.token, stored.certificateSha256, stored.profileId);
        executor.execute(() -> {
            if (autoConnect) {
                try { gatewayClient.startDiscord(gatewayConnection); } catch (Exception ignored) { }
                try { gatewayClient.connectDiscord(gatewayConnection, false); } catch (Exception ignored) { }
            }
            if (autoJoin) {
                HostGatewayStore.DiscordChannelSelection saved =
                        store.loadLastDiscordChannel(hostUuid, stored.profileId);
                if (saved != null) {
                    try {
                        DiscordGatewayClient.VoiceState current = client.getVoice(connection, true);
                        if (!current.connected) {
                            client.joinChannel(connection, new DiscordGatewayClient.ChannelTarget(
                                    saved.channelId, saved.channelName,
                                    saved.guildId, saved.guildName));
                        }
                    } catch (Exception ignored) { }
                }
            }
            handler.post(() -> { if (!destroyed) refresh(true); });
        });
    }

    private void schedule() {
        handler.removeCallbacks(scheduledRefresh);
        if (!destroyed && connection != null &&
                (overlay.getVisibility() == View.VISIBLE || dockEnabled)) {
            handler.postDelayed(scheduledRefresh, REFRESH_MS);
        }
    }

    private interface Action { void run() throws Exception; }

    private void runAction(Action action) {
        if (connection == null || !actionInFlight.compareAndSet(false, true)) return;
        executor.execute(() -> {
            String error = null;
            try {
                action.run();
            } catch (Exception failure) {
                error = failure.getMessage();
            }
            String finalError = error;
            handler.post(() -> {
                actionInFlight.set(false);
                if (destroyed) return;
                if (finalError != null && !finalError.isEmpty()) {
                    Toast.makeText(activity, finalError, Toast.LENGTH_LONG).show();
                }
                refresh(true);
            });
        });
    }

    private void unavailable(int stringId) {
        Toast.makeText(activity, stringId, Toast.LENGTH_SHORT).show();
    }

    private void renderDock() {
        dock.removeAllViews();
        if (!dockEnabled || connection == null || overlay.getVisibility() == View.VISIBLE) {
            dock.setVisibility(View.GONE);
            return;
        }
        dock.setVisibility(View.VISIBLE);
        boolean connected = voice != null && voice.connected;
        dock.addView(line(connected ? "DISCORD  ·  " + voice.channelName : "DISCORD",
                14, 0xFFB69CFF, true));
        if (!connected) {
            dock.addView(line(activity.getString(R.string.overlay_discord_disconnected),
                    13, 0xFFC5C8D3, false));
            return;
        }
        int shown = 0;
        for (DiscordGatewayClient.Participant participant : voice.participants) {
            if (shown++ >= 6) break;
            dock.addView(line((participant.speaking ? "●  " : "   ") + participant.name +
                            (participant.self ? "  ·  YOU" : ""), 13,
                    participant.speaking ? 0xFF69F0AE : Color.WHITE, false));
        }
    }

    private TextView line(String value, float size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
}
