package com.limelight.ui.overlay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.binding.input.ControllerHandler.ControllerBatteryInfo;

import java.util.ArrayList;
import java.util.List;

public class OverlayMenuView extends LinearLayout {
    private static final int BUTTON_SPACING_DP = 8;
    private static final int BUTTON_HEIGHT_DP = 48;
    private static final int BUTTON_ICON_SIZE_DP = 24;
    private static final int BUTTON_PADDING_DP = 12;
    private static final float ANALOG_STICK_THRESHOLD = 0.5f;
    private static final long ANALOG_NAV_THROTTLE_MS = 200;

    public interface MenuActionListener {
        void onDisconnect();
        void onQuitSession();
        void onToggleStats();
        void onToggleMouseEmulation();
        void onShowKeyboard();
        void onSendGuideButton();
        void onApplyBitrate(int bitrateKbps);
        void onCustomCommand(CustomCommand command);
        void onReturnToFrontend();
        void onDiscordMute();
        void onDiscordLeave();
        void onDiscordRejoin();
        void onDiscordDockToggle();
        void onMenuClosed();
    }

    private LinearLayout verticalContainer;
    private LinearLayout batteryContainer;
    private HorizontalScrollView horizontalScrollView;
    private LinearLayout horizontalContainer;
    private LinearLayout discordContainer;

    private List<OverlayMenuButton> verticalButtons;
    private List<Integer> verticalActions;
    private List<OverlayMenuButton> horizontalButtons;
    private List<Integer> horizontalActions;

    private enum Region { VERTICAL, HORIZONTAL }
    private Region activeRegion = Region.VERTICAL;
    private int verticalIndex = 0;
    private int horizontalIndex = 0;

    private MenuActionListener actionListener;
    private CustomCommandsManager commandsManager;

    private static final int ACTION_DISCONNECT = 0;
    private static final int ACTION_QUIT = 1;
    private static final int ACTION_TOGGLE_STATS = 2;
    private static final int ACTION_CLOSE = 3;
    private static final int ACTION_SHOW_KEYBOARD = 4;
    private static final int ACTION_TOGGLE_MOUSE_EMULATION = 5;
    private static final int ACTION_SEND_GUIDE = 6;
    private static final int ACTION_BITRATE_DOWN = 7;
    private static final int ACTION_BITRATE_APPLY = 8;
    private static final int ACTION_BITRATE_UP = 9;
    private static final int ACTION_DISCORD_MUTE = 10;
    private static final int ACTION_DISCORD_LEAVE = 11;
    private static final int ACTION_RETURN_TO_FRONTEND = 12;
    private static final int ACTION_DISCORD_REJOIN = 13;
    private static final int ACTION_DISCORD_DOCK = 14;
    private static final int ACTION_CUSTOM_BASE = 100;
    private static final int BITRATE_STEP_KBPS = 5000;
    private static final int BITRATE_MIN_KBPS = 1000;
    private static final int BITRATE_MAX_KBPS = 150000;

    private long lastAnalogNavTime = 0;
    private boolean flipFaceButtons = false;
    private List<ControllerBatteryInfo> controllerBatteryInfo = new ArrayList<>();
    private int currentBitrateKbps = 10000;
    private int pendingBitrateKbps = 10000;
    private OverlayMenuButton bitrateValueButton;
    private boolean bitrateControlEnabled;
    private boolean discordConfigured;
    private boolean discordLoading;
    private String discordError;
    private String discordMuteShortcut = "x";
    private String discordLeaveShortcut = "y";
    private DiscordGatewayClient.VoiceState discordVoice;
    private OverlayMenuButton discordMuteButton;
    private OverlayMenuButton discordLeaveButton;
    private OverlayMenuButton discordRejoinButton;
    private OverlayMenuButton discordDockButton;
    private boolean externalFrontend;
    private boolean discordCanRejoin;
    private String discordRejoinChannel = "";
    private boolean discordDocked;

    public OverlayMenuView(Context context) {
        super(context);
        init(context);
    }

    public OverlayMenuView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public OverlayMenuView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.BOTTOM);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundDrawable(null);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setDefaultFocusHighlightEnabled(false);
        }

        verticalContainer = new LinearLayout(context);
        verticalContainer.setOrientation(LinearLayout.VERTICAL);
        verticalContainer.setBackgroundDrawable(null);
        discordContainer = new LinearLayout(context);
        discordContainer.setOrientation(LinearLayout.VERTICAL);
        discordContainer.setPadding(dp(16), dp(13), dp(16), dp(13));
        discordContainer.setMinimumWidth(dp(310));
        discordContainer.setVisibility(GONE);
        GradientDrawable discordBackground = new GradientDrawable();
        discordBackground.setShape(GradientDrawable.RECTANGLE);
        discordBackground.setCornerRadius(dp(10));
        discordBackground.setColor(0xE6101118);
        discordBackground.setStroke(dp(1), 0x667C4DFF);
        discordContainer.setBackground(discordBackground);
        // The visible Discord card is rendered by Game in a separate
        // top-right layer. This view retains only its state and action buttons.
        addView(verticalContainer, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        batteryContainer = new LinearLayout(context);
        batteryContainer.setOrientation(LinearLayout.HORIZONTAL);

        horizontalScrollView = new HorizontalScrollView(context);
        horizontalScrollView.setHorizontalScrollBarEnabled(false);
        horizontalScrollView.setFocusable(false);
        horizontalScrollView.setFocusableInTouchMode(false);
        horizontalScrollView.setBackgroundDrawable(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            horizontalScrollView.setDefaultFocusHighlightEnabled(false);
        }

        horizontalContainer = new LinearLayout(context);
        horizontalContainer.setOrientation(LinearLayout.HORIZONTAL);
        horizontalContainer.setBackgroundDrawable(null);
        horizontalScrollView.addView(horizontalContainer);
        addView(horizontalScrollView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        verticalButtons = new ArrayList<>();
        verticalActions = new ArrayList<>();
        horizontalButtons = new ArrayList<>();
        horizontalActions = new ArrayList<>();

        activeRegion = Region.VERTICAL;
        verticalIndex = 0;
        horizontalIndex = 0;

        commandsManager = new CustomCommandsManager(context);

        setVisibility(GONE);
    }

    public void buildMenu() {
        activeRegion = Region.VERTICAL;
        verticalIndex = 0;
        horizontalIndex = 0;
        clearFocus();

        verticalContainer.removeAllViews();
        horizontalContainer.removeAllViews();
        verticalButtons.clear();
        verticalActions.clear();
        horizontalButtons.clear();
        horizontalActions.clear();

        float density = getContext().getResources().getDisplayMetrics().density;
        int spacing = (int) (BUTTON_SPACING_DP * density);

        // Vertical column: top → bottom
        addVerticalButton(R.drawable.ic_overlay_guide,
            getContext().getString(R.string.overlay_menu_guide), ACTION_SEND_GUIDE, spacing);
        addVerticalButton(R.drawable.ic_overlay_mouse,
                getContext().getString(R.string.overlay_menu_mouse_emulation), ACTION_TOGGLE_MOUSE_EMULATION, spacing);
        addVerticalButton(R.drawable.ic_overlay_keyboard_toggle,
            getContext().getString(R.string.overlay_menu_keyboard), ACTION_SHOW_KEYBOARD, spacing);
        addVerticalButton(R.drawable.ic_overlay_perf,
                getContext().getString(R.string.overlay_menu_toggle_stats), ACTION_TOGGLE_STATS, spacing);
        addVerticalButton(R.drawable.ic_overlay_power,
            getContext().getString(R.string.overlay_menu_quit_session), ACTION_QUIT, spacing);
        addVerticalButton(R.drawable.ic_overlay_monitor,
            getContext().getString(R.string.overlay_menu_disconnect), ACTION_DISCONNECT, 0);
        if (externalFrontend) {
            addVerticalButton(0, getContext().getString(R.string.overlay_return_to_wake),
                    ACTION_RETURN_TO_FRONTEND, spacing);
        }

        // Add spacing between vertical column and horizontal row
        ((LinearLayout.LayoutParams) horizontalScrollView.getLayoutParams()).leftMargin = spacing;

        // Horizontal row: custom commands then Close
        horizontalContainer.addView(batteryContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        renderBatteryInfo(spacing);

        bitrateValueButton = null;
        discordMuteButton = null;
        discordLeaveButton = null;
        discordRejoinButton = null;
        discordDockButton = null;
        if (discordConfigured) {
            discordMuteButton = addHorizontalButton(R.drawable.ic_overlay_microphone,
                    discordMuteLabel(), ACTION_DISCORD_MUTE, spacing);
            discordLeaveButton = addHorizontalButton(R.drawable.ic_overlay_close,
                    discordLeaveLabel(), ACTION_DISCORD_LEAVE, spacing);
            discordRejoinButton = addHorizontalButton(0, discordRejoinLabel(),
                    ACTION_DISCORD_REJOIN, spacing);
            discordDockButton = addHorizontalButton(0, discordDockLabel(),
                    ACTION_DISCORD_DOCK, spacing);
        }
        if (bitrateControlEnabled) {
            addHorizontalButton(0, getContext().getString(R.string.overlay_bitrate_decrease),
                    ACTION_BITRATE_DOWN, spacing);
            bitrateValueButton = addHorizontalButton(0, bitrateLabel(), ACTION_BITRATE_APPLY, spacing);
            addHorizontalButton(0, getContext().getString(R.string.overlay_bitrate_increase),
                    ACTION_BITRATE_UP, spacing);
        }

        List<CustomCommand> customCommands = commandsManager.getCommands();
        for (int commandIndex = 0; commandIndex < customCommands.size(); commandIndex++) {
            CustomCommand command = customCommands.get(commandIndex);
            addHorizontalButton(command.getIconResId(), command.getName(),
                ACTION_CUSTOM_BASE + commandIndex, spacing);
        }
        addHorizontalButton(0,
            getContext().getString(R.string.overlay_menu_close), ACTION_CLOSE, 0);

        renderDiscordCard();

        verticalContainer.invalidate();
        verticalContainer.requestLayout();
        horizontalContainer.invalidate();
        horizontalContainer.requestLayout();
    }

    private void addVerticalButton(int iconResId, String label, int action, int bottomMarginPx) {
        OverlayMenuButton button = OverlayMenuButton.create(getContext(), iconResId, label);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = bottomMarginPx;
        verticalContainer.addView(button, params);

        verticalButtons.add(button);
        verticalActions.add(action);

        final int index = verticalButtons.size() - 1;
        button.setOnClickListener(v -> selectAndActivate(Region.VERTICAL, index));
        button.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                activeRegion = Region.VERTICAL;
                verticalIndex = index;
                button.setSelected(true);
            } else {
                button.setSelected(false);
            }
        });
    }

    private OverlayMenuButton addHorizontalButton(int iconResId, String label, int action, int rightMarginPx) {
        OverlayMenuButton button = OverlayMenuButton.create(getContext(), iconResId, label);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.rightMargin = rightMarginPx;
        horizontalContainer.addView(button, params);

        horizontalButtons.add(button);
        horizontalActions.add(action);

        final int index = horizontalButtons.size() - 1;
        button.setOnClickListener(v -> selectAndActivate(Region.HORIZONTAL, index));
        button.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                activeRegion = Region.HORIZONTAL;
                horizontalIndex = index;
                button.setSelected(true);
            } else {
                button.setSelected(false);
            }
        });
        return button;
    }

    public void setMenuActionListener(MenuActionListener listener) {
        this.actionListener = listener;
    }

    public void setFlipFaceButtons(boolean flip) {
        this.flipFaceButtons = flip;
    }

    public void setControllerBatteryInfo(List<ControllerBatteryInfo> info) {
        controllerBatteryInfo = info != null ? new ArrayList<>(info) : new ArrayList<>();
        if (getVisibility() == VISIBLE) {
            float density = getContext().getResources().getDisplayMetrics().density;
            renderBatteryInfo((int) (BUTTON_SPACING_DP * density));
        }
    }

    public void setBitrateKbps(int bitrateKbps) {
        currentBitrateKbps = clampBitrate(bitrateKbps);
        pendingBitrateKbps = currentBitrateKbps;
        updateBitrateLabel();
    }

    public void setBitrateControlEnabled(boolean enabled) {
        bitrateControlEnabled = enabled;
    }

    public void setExternalFrontend(boolean enabled) {
        externalFrontend = enabled;
    }

    public void setDiscordRejoinTarget(boolean available, String channelName) {
        discordCanRejoin = available;
        discordRejoinChannel = channelName == null ? "" : channelName;
        updateDiscordActionButtons();
    }

    public void setDiscordDocked(boolean docked) {
        discordDocked = docked;
        updateDiscordActionButtons();
    }

    public void setDiscordConfigured(boolean configured) {
        discordConfigured = configured;
        if (!configured) {
            discordVoice = null;
            discordError = null;
            discordLoading = false;
        }
        renderDiscordCard();
    }

    public void setDiscordShortcuts(String muteShortcut, String leaveShortcut) {
        discordMuteShortcut = normalizeDiscordShortcut(muteShortcut);
        discordLeaveShortcut = normalizeDiscordShortcut(leaveShortcut);
        if (!"none".equals(discordMuteShortcut) &&
                discordMuteShortcut.equals(discordLeaveShortcut)) {
            discordLeaveShortcut = "none";
        }
        updateDiscordActionButtons();
    }

    public void setDiscordState(DiscordGatewayClient.VoiceState voice, String error,
                                boolean loading) {
        if (voice != null) discordVoice = voice;
        discordError = error;
        discordLoading = loading;
        renderDiscordCard();
        updateDiscordActionButtons();
    }

    private void renderDiscordCard() {
        if (discordContainer == null) return;
        discordContainer.removeAllViews();
        discordContainer.setVisibility(discordConfigured ? VISIBLE : GONE);
        if (!discordConfigured) return;

        TextView title = discordLine(getContext().getString(R.string.overlay_discord_title),
                13, 0xFFB69CFF, true);
        discordContainer.addView(title);

        if (discordLoading && discordVoice == null) {
            discordContainer.addView(discordLine(
                    getContext().getString(R.string.overlay_discord_loading),
                    14, 0xFFC5C8D3, false));
            return;
        }
        if (discordError != null && !discordError.isEmpty()) {
            discordContainer.addView(discordLine(discordError, 13, 0xFFFFB4AB, false));
            return;
        }
        if (discordVoice == null || !discordVoice.connected) {
            discordContainer.addView(discordLine(
                    getContext().getString(R.string.overlay_discord_disconnected),
                    14, 0xFFC5C8D3, false));
            return;
        }

        String channel = discordVoice.channelName == null || discordVoice.channelName.isEmpty() ?
                "Voice" : discordVoice.channelName;
        discordContainer.addView(discordLine(getContext().getString(
                R.string.overlay_discord_channel, channel, discordVoice.participants.size()),
                15, Color.WHITE, true));

        int shown = 0;
        for (DiscordGatewayClient.Participant participant : discordVoice.participants) {
            if (shown >= 6) break;
            String name = participant.self ? getContext().getString(
                    R.string.overlay_discord_self, participant.name) : participant.name;
            if (participant.speaking) {
                name = getContext().getString(R.string.overlay_discord_participant_speaking, name);
            } else {
                name = getContext().getString(R.string.overlay_discord_participant, name);
            }
            if (participant.muted) {
                name += " · " + getContext().getString(R.string.overlay_discord_muted);
            } else if (!participant.self && participant.volume != 100) {
                name += " · " + participant.volume + "%";
            }
            discordContainer.addView(discordLine(name, 14,
                    participant.speaking ? 0xFF69F0AE : 0xFFE6E1E9, false));
            shown++;
        }
        if (discordVoice.participants.size() <= 1) {
            discordContainer.addView(discordLine(
                    getContext().getString(R.string.overlay_discord_empty),
                    12, 0xFF9FA3B2, false));
        } else if (discordVoice.participants.size() > shown) {
            discordContainer.addView(discordLine(
                    "+" + (discordVoice.participants.size() - shown),
                    12, 0xFF9FA3B2, false));
        }
    }

    private TextView discordLine(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        view.setSingleLine(true);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(4);
        view.setLayoutParams(params);
        return view;
    }

    private void updateDiscordActionButtons() {
        boolean connected = discordVoice != null && discordVoice.connected;
        if (discordMuteButton != null) {
            discordMuteButton.setLabel(discordMuteLabel());
            discordMuteButton.setAlpha(connected ? 1f : 0.45f);
        }
        if (discordLeaveButton != null) {
            discordLeaveButton.setLabel(discordLeaveLabel());
            discordLeaveButton.setAlpha(connected ? 1f : 0.45f);
        }
        if (discordRejoinButton != null) {
            discordRejoinButton.setLabel(discordRejoinLabel());
            discordRejoinButton.setAlpha(!connected && discordCanRejoin ? 1f : 0.45f);
        }
        if (discordDockButton != null) {
            discordDockButton.setLabel(discordDockLabel());
            discordDockButton.setAlpha(connected || discordDocked ? 1f : 0.6f);
        }
    }

    private String discordMuteLabel() {
        int stringId = discordVoice != null && discordVoice.muted ?
                R.string.overlay_discord_unmute : R.string.overlay_discord_mute;
        return getContext().getString(stringId) + shortcutSuffix(discordMuteShortcut);
    }

    private String discordLeaveLabel() {
        return getContext().getString(R.string.overlay_discord_leave) +
                shortcutSuffix(discordLeaveShortcut);
    }

    private String discordRejoinLabel() {
        if (discordRejoinChannel.isEmpty()) {
            return getContext().getString(R.string.overlay_discord_rejoin);
        }
        return getContext().getString(R.string.overlay_discord_rejoin_channel,
                discordRejoinChannel);
    }

    private String discordDockLabel() {
        return getContext().getString(discordDocked
                ? R.string.overlay_discord_undock : R.string.overlay_discord_dock);
    }

    private String shortcutSuffix(String shortcut) {
        String label = discordShortcutLabel(shortcut);
        return label.isEmpty() ? "" : " (" + label + ")";
    }

    private static String normalizeDiscordShortcut(String value) {
        if ("x".equals(value) || "y".equals(value) || "l1".equals(value) ||
                "r1".equals(value) || "l3".equals(value) || "r3".equals(value)) {
            return value;
        }
        return "none";
    }

    private static String discordShortcutLabel(String value) {
        switch (normalizeDiscordShortcut(value)) {
            case "x": return "X";
            case "y": return "Y";
            case "l1": return "LB";
            case "r1": return "RB";
            case "l3": return "L3";
            case "r3": return "R3";
            default: return "";
        }
    }

    private static int discordShortcutKeyCode(String value) {
        switch (normalizeDiscordShortcut(value)) {
            case "x": return KeyEvent.KEYCODE_BUTTON_X;
            case "y": return KeyEvent.KEYCODE_BUTTON_Y;
            case "l1": return KeyEvent.KEYCODE_BUTTON_L1;
            case "r1": return KeyEvent.KEYCODE_BUTTON_R1;
            case "l3": return KeyEvent.KEYCODE_BUTTON_THUMBL;
            case "r3": return KeyEvent.KEYCODE_BUTTON_THUMBR;
            default: return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }

    private void adjustBitrate(int deltaKbps) {
        pendingBitrateKbps = clampBitrate(pendingBitrateKbps + deltaKbps);
        updateBitrateLabel();
    }

    private int clampBitrate(int bitrateKbps) {
        return Math.max(BITRATE_MIN_KBPS, Math.min(BITRATE_MAX_KBPS, bitrateKbps));
    }

    private String bitrateLabel() {
        int mbps = Math.max(1, Math.round(pendingBitrateKbps / 1000f));
        return getContext().getString(
                pendingBitrateKbps == currentBitrateKbps ?
                        R.string.overlay_bitrate_current : R.string.overlay_bitrate_apply,
                mbps);
    }

    private void updateBitrateLabel() {
        if (bitrateValueButton != null) bitrateValueButton.setLabel(bitrateLabel());
    }

    private void renderBatteryInfo(int bottomMarginPx) {
        batteryContainer.removeAllViews();
        batteryContainer.setVisibility(controllerBatteryInfo.isEmpty() ? GONE : VISIBLE);

        float density = getContext().getResources().getDisplayMetrics().density;
        int horizontalPadding = (int) (BUTTON_PADDING_DP * density);
        int verticalPadding = (int) (BUTTON_PADDING_DP * density);
        int iconSize = (int) (BUTTON_ICON_SIZE_DP * density);
        int cardHeight = (int) (BUTTON_HEIGHT_DP * density);

        for (ControllerBatteryInfo info : controllerBatteryInfo) {
            int accentColor = info.percentage < 0 ? Color.LTGRAY :
                    info.isCharging() ? Color.rgb(100, 181, 246) :
                    info.percentage <= 10 ? Color.rgb(255, 82, 82) :
                    info.percentage <= 30 ? Color.rgb(255, 183, 77) :
                    Color.rgb(105, 240, 174);

            LinearLayout card = new LinearLayout(getContext());
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
            card.setMinimumHeight(cardHeight);

            ImageView gamepadIcon = new ImageView(getContext());
            gamepadIcon.setImageResource(R.drawable.ic_overlay_gamepad);
            gamepadIcon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            card.addView(gamepadIcon, new LinearLayout.LayoutParams(iconSize, iconSize));

            TextView player = new TextView(getContext());
            player.setText(getContext().getString(R.string.overlay_controller_player, info.controllerNumber));
            player.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            player.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams playerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            playerParams.leftMargin = (int) (5 * density);
            card.addView(player, playerParams);

            ImageView batteryIcon = new ImageView(getContext());
            batteryIcon.setImageDrawable(new BatteryLevelDrawable(
                    getContext(), info.percentage, info.isCharging(), accentColor));
            LinearLayout.LayoutParams batteryParams = new LinearLayout.LayoutParams(iconSize, iconSize);
            batteryParams.leftMargin = (int) (8 * density);
            card.addView(batteryIcon, batteryParams);

            TextView percentage = new TextView(getContext());
            percentage.setText(info.percentage < 0 ?
                    getContext().getString(R.string.overlay_battery_unknown) :
                    getContext().getString(R.string.overlay_battery_percentage, info.percentage));
            percentage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            percentage.setTextColor(accentColor);
            percentage.setSingleLine(true);
            LinearLayout.LayoutParams percentageParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            percentageParams.leftMargin = (int) (3 * density);
            card.addView(percentage, percentageParams);

            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.RECTANGLE);
            background.setCornerRadius(8 * density);
            background.setColor(0xD9000000);
            card.setBackground(background);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    cardHeight);
            params.rightMargin = bottomMarginPx;
            batteryContainer.addView(card, params);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (!isFullGamepadEvent(event) && event.getRepeatCount() == 0) {
                closeMenu();
            }
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (flipFaceButtons) {
                keyCode = handleFlipFaceButtons(keyCode);
            }

            if (event.getRepeatCount() == 0 && discordVoice != null && discordVoice.connected) {
                if (keyCode == discordShortcutKeyCode(discordMuteShortcut)) {
                    activateDiscordMute();
                    return true;
                }
                if (keyCode == discordShortcutKeyCode(discordLeaveShortcut)) {
                    activateDiscordLeave();
                    return true;
                }
            }

            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    navigateLeft();
                    return true;

                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    navigateRight();
                    return true;

                case KeyEvent.KEYCODE_DPAD_UP:
                    navigateUp();
                    return true;

                case KeyEvent.KEYCODE_DPAD_DOWN:
                    navigateDown();
                    return true;

                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    activateSelected();
                    return true;

                case KeyEvent.KEYCODE_BUTTON_B:
                    closeMenu();
                    return true;

                case KeyEvent.KEYCODE_BUTTON_X:
                    if (event.getRepeatCount() == 0) {
                        activateMouseEmulation();
                    }
                    return true;

                case KeyEvent.KEYCODE_BUTTON_Y:
                    if (event.getRepeatCount() == 0) {
                        activateKeyboard();
                    }
                    return true;

                case KeyEvent.KEYCODE_BUTTON_START:
                case KeyEvent.KEYCODE_MENU:
                    if (event.getRepeatCount() == 0) {
                        activateGuideButton();
                    }
                    return true;

                case KeyEvent.KEYCODE_BUTTON_R1:
                    if (event.getRepeatCount() == 0) {
                        activateToggleStats();
                    }
                    return true;
            }
        }

        if (isGamepadEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!isFullGamepadEvent(event) && event.getRepeatCount() == 0) {
                closeMenu();
            }
            return true;
        }

        if (isGamepadEvent(event)) {
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (isGamepadMotionEvent(event)) {
            float x = event.getAxisValue(MotionEvent.AXIS_X);
            float y = event.getAxisValue(MotionEvent.AXIS_Y);
            float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);

            float combinedX = Math.abs(x) > Math.abs(hatX) ? x : hatX;
            float combinedY = Math.abs(y) > Math.abs(hatY) ? y : hatY;

            long currentTime = System.currentTimeMillis();
            if (currentTime - lastAnalogNavTime >= ANALOG_NAV_THROTTLE_MS) {
                // Dominant axis wins to prevent cross-region jumps on diagonal inputs
                if (Math.abs(combinedX) >= Math.abs(combinedY)) {
                    if (combinedX < -ANALOG_STICK_THRESHOLD) {
                        navigateLeft();
                        lastAnalogNavTime = currentTime;
                    } else if (combinedX > ANALOG_STICK_THRESHOLD) {
                        navigateRight();
                        lastAnalogNavTime = currentTime;
                    }
                } else {
                    if (combinedY < -ANALOG_STICK_THRESHOLD) {
                        navigateUp();
                        lastAnalogNavTime = currentTime;
                    } else if (combinedY > ANALOG_STICK_THRESHOLD) {
                        navigateDown();
                        lastAnalogNavTime = currentTime;
                    }
                }
            }

            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    private boolean isGamepadEvent(KeyEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
               (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private boolean isFullGamepadEvent(KeyEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private int handleFlipFaceButtons(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return KeyEvent.KEYCODE_BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_B: return KeyEvent.KEYCODE_BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_X: return KeyEvent.KEYCODE_BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_Y: return KeyEvent.KEYCODE_BUTTON_X;
            default: return keyCode;
        }
    }

    private boolean isGamepadMotionEvent(MotionEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private void navigateUp() {
        if (activeRegion == Region.VERTICAL) {
            if (!verticalButtons.isEmpty()) {
                setVerticalIndex((verticalIndex - 1 + verticalButtons.size()) % verticalButtons.size());
            }
        } else {
            // From horizontal → Up: jump to button above Disconnect (second-to-last in vertical)
            clearHorizontalSelection();
            activeRegion = Region.VERTICAL;
            int target = verticalButtons.size() >= 2 ? verticalButtons.size() - 2 : 0;
            setVerticalIndex(target);
        }
    }

    private void navigateDown() {
        if (activeRegion == Region.VERTICAL) {
            if (!verticalButtons.isEmpty()) {
                setVerticalIndex((verticalIndex + 1) % verticalButtons.size());
            }
        } else {
            // From horizontal → Down: jump to topmost vertical button
            clearHorizontalSelection();
            activeRegion = Region.VERTICAL;
            setVerticalIndex(0);
        }
    }

    private void navigateLeft() {
        if (activeRegion == Region.HORIZONTAL) {
            if (horizontalIndex > 0) {
                setHorizontalIndex(horizontalIndex - 1);
            } else {
                // At leftmost horizontal button — cross to Disconnect (last vertical button)
                clearHorizontalSelection();
                activeRegion = Region.VERTICAL;
                setVerticalIndex(verticalButtons.size() - 1);
            }
        } else {
            // In vertical region — wrap around to rightmost horizontal button
            if (!horizontalButtons.isEmpty()) {
                clearVerticalSelection();
                activeRegion = Region.HORIZONTAL;
                setHorizontalIndex(horizontalButtons.size() - 1);
            }
        }
    }

    private void navigateRight() {
        if (activeRegion == Region.VERTICAL) {
            if (!horizontalButtons.isEmpty()) {
                clearVerticalSelection();
                activeRegion = Region.HORIZONTAL;
                setHorizontalIndex(0);
            }
        } else {
            if (horizontalIndex < horizontalButtons.size() - 1) {
                setHorizontalIndex(horizontalIndex + 1);
            } else {
                // At rightmost horizontal button — cross to Disconnect (last vertical button)
                clearHorizontalSelection();
                activeRegion = Region.VERTICAL;
                setVerticalIndex(verticalButtons.size() - 1);
            }
        }
    }

    private void setVerticalIndex(int index) {
        if (index < 0 || index >= verticalButtons.size()) return;

        for (OverlayMenuButton b : verticalButtons) {
            b.setSelected(false);
        }

        verticalIndex = index;
        verticalButtons.get(index).setSelected(true);
        verticalButtons.get(index).requestFocus();
    }

    private void setHorizontalIndex(int index) {
        if (index < 0 || index >= horizontalButtons.size()) return;

        for (OverlayMenuButton b : horizontalButtons) {
            b.setSelected(false);
        }

        horizontalIndex = index;
        horizontalButtons.get(index).setSelected(true);
        horizontalButtons.get(index).requestFocus();
    }

    private void clearVerticalSelection() {
        for (OverlayMenuButton b : verticalButtons) {
            b.setSelected(false);
        }
    }

    private void clearHorizontalSelection() {
        for (OverlayMenuButton b : horizontalButtons) {
            b.setSelected(false);
        }
    }

    private void selectAndActivate(Region region, int index) {
        activeRegion = region;
        if (region == Region.VERTICAL) {
            setVerticalIndex(index);
        } else {
            setHorizontalIndex(index);
        }
        activateSelected();
    }

    private void activateSelected() {
        int action;
        if (activeRegion == Region.VERTICAL) {
            if (verticalIndex < 0 || verticalIndex >= verticalActions.size()) return;
            action = verticalActions.get(verticalIndex);
        } else {
            if (horizontalIndex < 0 || horizontalIndex >= horizontalActions.size()) return;
            action = horizontalActions.get(horizontalIndex);
        }

        if (action == ACTION_BITRATE_DOWN) {
            adjustBitrate(-BITRATE_STEP_KBPS);
            return;
        } else if (action == ACTION_BITRATE_UP) {
            adjustBitrate(BITRATE_STEP_KBPS);
            return;
        } else if (action == ACTION_BITRATE_APPLY) {
            if (pendingBitrateKbps != currentBitrateKbps && actionListener != null) {
                actionListener.onApplyBitrate(pendingBitrateKbps);
            }
            return;
        }

        boolean shouldCloseMenu = false;

        if (actionListener != null) {
            if (action == ACTION_DISCONNECT) {
                actionListener.onDisconnect();
                shouldCloseMenu = true;
            } else if (action == ACTION_QUIT) {
                actionListener.onQuitSession();
                shouldCloseMenu = true;
            } else if (action == ACTION_TOGGLE_STATS) {
                actionListener.onToggleStats();
                // Keep menu open
            } else if (action == ACTION_TOGGLE_MOUSE_EMULATION) {
                actionListener.onToggleMouseEmulation();
                shouldCloseMenu = true;
            } else if (action == ACTION_SHOW_KEYBOARD) {
                activateKeyboard();
                return;
            } else if (action == ACTION_SEND_GUIDE) {
                actionListener.onSendGuideButton();
                shouldCloseMenu = true;
            } else if (action == ACTION_RETURN_TO_FRONTEND) {
                actionListener.onReturnToFrontend();
                return;
            } else if (action == ACTION_DISCORD_MUTE) {
                activateDiscordMute();
                return;
            } else if (action == ACTION_DISCORD_LEAVE) {
                activateDiscordLeave();
                return;
            } else if (action == ACTION_DISCORD_REJOIN) {
                if (discordCanRejoin) actionListener.onDiscordRejoin();
                return;
            } else if (action == ACTION_DISCORD_DOCK) {
                actionListener.onDiscordDockToggle();
                return;
            } else if (action == ACTION_CLOSE) {
                closeMenu();
                return;
            } else if (action >= ACTION_CUSTOM_BASE) {
                int commandIndex = action - ACTION_CUSTOM_BASE;
                List<CustomCommand> commands = commandsManager.getCommands();
                if (commandIndex >= 0 && commandIndex < commands.size()) {
                    actionListener.onCustomCommand(commands.get(commandIndex));
                }
                // Keep menu open
            }
        }

        if (shouldCloseMenu) {
            closeMenu();
        }
    }

    private void activateDiscordMute() {
        if (actionListener != null) actionListener.onDiscordMute();
    }

    private void activateDiscordLeave() {
        if (actionListener != null) actionListener.onDiscordLeave();
    }

    public void closeMenu() {
        hide(() -> {
            if (actionListener != null) {
                actionListener.onMenuClosed();
            }
        });
    }

    private void activateMouseEmulation() {
        if (actionListener != null) {
            actionListener.onToggleMouseEmulation();
        }
        closeMenu();
    }

    private void activateKeyboard() {
        // Hide first, then post the keyboard request so the window manager has a frame
        // to process the visibility change and return focus to the game view before the
        // IME is requested. Without this, newer Android silently drops showSoftInput()
        // because the overlay view still holds focus at the moment of the call.
        setVisibility(GONE);
        post(() -> {
            if (actionListener != null) {
                actionListener.onShowKeyboard();
            }
        });
    }

    private void activateGuideButton() {
        if (actionListener != null) {
            actionListener.onSendGuideButton();
        }
        closeMenu();
    }

    private void activateQuitSession() {
        if (actionListener != null) {
            actionListener.onQuitSession();
        }
        closeMenu();
    }

    private void activateToggleStats() {
        if (actionListener != null) {
            actionListener.onToggleStats();
        }
        closeMenu();
    }

    public void show() {
        buildMenu();
        setVisibility(VISIBLE);

        invalidate();
        requestLayout();

        requestFocus();

        post(() -> {
            // Equalize all vertical button widths to the widest one
            int maxWidth = 0;
            for (OverlayMenuButton b : verticalButtons) {
                maxWidth = Math.max(maxWidth, b.getWidth());
            }
            if (maxWidth > 0) {
                for (OverlayMenuButton b : verticalButtons) {
                    b.setMinimumWidth(maxWidth);
                }
                verticalContainer.requestLayout();
            }

            if (!verticalButtons.isEmpty()) {
                setVerticalIndex(verticalButtons.size() - 1);
            }
        });
    }

    public void hide(Runnable onComplete) {
        setVisibility(GONE);
        if (onComplete != null) {
            onComplete.run();
        }
    }
}
