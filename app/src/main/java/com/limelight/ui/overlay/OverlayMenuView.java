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
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.binding.input.ControllerHandler.ControllerBatteryInfo;
import com.limelight.discord.DiscordSocialClient;
import com.limelight.console.DiscordCommunityPresentation;
import com.limelight.ui.ControllerGlyphs;

import java.util.ArrayList;
import java.util.List;

public class OverlayMenuView extends LinearLayout {
    private static final int BUTTON_SPACING_DP = 8;
    private static final int BUTTON_HEIGHT_DP = 48;
    private static final int BUTTON_ICON_SIZE_DP = 24;
    private static final int BUTTON_PADDING_DP = 12;
    private static final int COMMUNITY_QUICK_WIDTH_DP = 520;
    private static final int COMMUNITY_QUICK_HEIGHT_DP = 360;
    private static final int COMMUNITY_RAIL_ICON_COUNT = 3;
    private static final float ANALOG_STICK_THRESHOLD = 0.5f;
    private static final long ANALOG_NAV_THROTTLE_MS = 200;

    public interface MenuActionListener {
        void onHome();
        void onQuitSession();
        void onSuspendSession();
        void onToggleStats();
        void onToggleMouseEmulation();
        void onShowKeyboard();
        void onSendGuideButton();
        void onApplyBitrate(int bitrateKbps);
        void onCustomCommand(CustomCommand command);
        void onDiscordMute();
        void onDiscordLeave();
        void onDiscordRejoin();
        void onDiscordDockToggle();
        void onDiscordSocialFriends();
        void onInstallationConfirmed();
        void onMenuClosed();
    }

    private LinearLayout verticalContainer;
    private LinearLayout batteryContainer;
    private HorizontalScrollView horizontalScrollView;
    private LinearLayout horizontalContainer;
    private LinearLayout discordContainer;
    private LinearLayout discordRail;
    private LinearLayout discordQuickMain;
    private LinearLayout discordQuickHeader;
    private TextView discordQuickFooter;
    private ScrollView discordContentScroll;
    private LinearLayout discordContentContainer;
    private LinearLayout discordVoiceContentContainer;
    private LinearLayout discordSocialContentContainer;
    private LinearLayout discordActionsContainer;
    private View menuSpacer;

    private List<OverlayMenuButton> verticalButtons;
    private List<Integer> verticalActions;
    private List<OverlayMenuButton> horizontalButtons;
    private List<Integer> horizontalActions;
    private List<OverlayMenuButton> discordButtons;
    private List<Integer> discordActions;

    private enum Region { VERTICAL, HORIZONTAL, DISCORD }
    enum OverlayMode { MENU, COMMUNITY }
    private enum CommunityFocus { RAIL, CONTENT, ACTION }
    static final int COMMUNITY_REGION_RAIL = 0;
    static final int COMMUNITY_REGION_CONTENT = 1;
    static final int COMMUNITY_REGION_ACTION = 2;
    private OverlayMode overlayMode = OverlayMode.MENU;
    private CommunityFocus communityFocus = CommunityFocus.RAIL;
    private Region activeRegion = Region.VERTICAL;
    private int verticalIndex = 0;
    private int horizontalIndex = 0;
    private int discordIndex = 0;

    private MenuActionListener actionListener;
    private CustomCommandsManager commandsManager;

    private static final int ACTION_HOME = 0;
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
    private static final int ACTION_DISCORD_SOCIAL_FRIENDS = 12;
    private static final int ACTION_DISCORD_REJOIN = 13;
    private static final int ACTION_DISCORD_DOCK = 14;
    private static final int ACTION_INSTALLATION_CONFIRMED = 15;
    private static final int ACTION_SUSPEND_SESSION = 16;
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
    private boolean discordCanRejoin;
    private String discordRejoinChannel = "";
    private boolean discordDocked;
    private boolean discordSocialAvailable;
    private long discordSocialRevision = Long.MIN_VALUE;
    private long renderedDiscordSocialRevision = Long.MIN_VALUE;
    private int renderedDiscordSocialSection = -1;
    private String renderedDiscordActionSignature = "";
    private boolean discordSocialConnected;
    private String discordSocialDisplayName = "";
    private String discordSocialAvatarUrl = "";
    private List<DiscordSocialClient.Friend> discordSocialFriends = new ArrayList<>();
    private boolean discordSocialExpanded;
    private OverlayMenuButton discordSocialFriendsButton;
    private List<ImageButton> discordRailIcons = new ArrayList<>();
    private int discordQuickSection;
    private int communityMotionDirection = KeyEvent.KEYCODE_UNKNOWN;
    private long lastCommunityDirectionalKeyAt;
    private boolean installationConfirmationAvailable;
    private boolean playStationButtons;

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
        setPadding(dp(20), dp(18), dp(18), dp(20));
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
        discordContainer.setOrientation(LinearLayout.HORIZONTAL);
        discordContainer.setPadding(0, 0, 0, 0);
        discordContainer.setMinimumWidth(dp(COMMUNITY_QUICK_WIDTH_DP));
        discordContainer.setVisibility(GONE);
        GradientDrawable discordBackground = new GradientDrawable();
        discordBackground.setShape(GradientDrawable.RECTANGLE);
        discordBackground.setCornerRadius(dp(18));
        discordBackground.setColor(0xEB10131C);
        discordContainer.setBackground(discordBackground);
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

        menuSpacer = new View(context);
        addView(menuSpacer, new LinearLayout.LayoutParams(0, 0, 1f));

        discordRail = new LinearLayout(context);
        discordRail.setOrientation(LinearLayout.VERTICAL);
        discordRail.setGravity(Gravity.CENTER_HORIZONTAL);
        discordRail.setPadding(dp(10), dp(16), dp(10), dp(12));
        GradientDrawable railBackground = new GradientDrawable();
        railBackground.setColor(0x14000000);
        railBackground.setStroke(dp(1), 0x18FFFFFF);
        discordRail.setBackground(railBackground);
        View dot = new View(context);
        GradientDrawable dotBackground = new GradientDrawable();
        dotBackground.setShape(GradientDrawable.OVAL);
        dotBackground.setColor(0xFF5BE28D);
        dot.setBackground(dotBackground);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotParams.bottomMargin = dp(12);
        discordRail.addView(dot, dotParams);
        addDiscordRailIcon(context, R.drawable.ic_console_discord, R.string.discord_community_together, 0);
        addDiscordRailIcon(context, R.drawable.ic_overlay_community_friends, R.string.discord_community_friends, 1);
        addDiscordRailIcon(context, R.drawable.ic_overlay_community_servers, R.string.discord_community_servers, 2);
        discordContainer.addView(discordRail, new LinearLayout.LayoutParams(dp(68),
                LinearLayout.LayoutParams.MATCH_PARENT));

        discordQuickMain = new LinearLayout(context);
        discordQuickMain.setOrientation(LinearLayout.VERTICAL);
        discordQuickMain.setPadding(dp(18), dp(16), dp(18), dp(14));
        discordContainer.addView(discordQuickMain, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        discordQuickHeader = new LinearLayout(context);
        discordQuickHeader.setGravity(Gravity.CENTER_VERTICAL);
        discordQuickMain.addView(discordQuickHeader, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));
        discordContentScroll = new ScrollView(context);
        discordContentScroll.setFillViewport(true);
        discordContentScroll.setVerticalScrollBarEnabled(false);
        discordContentScroll.setFocusable(true);
        discordContentScroll.setFocusableInTouchMode(true);
        discordContentScroll.setOnFocusChangeListener((ignored, focused) -> {
            styleDiscordContentScroll(focused);
            if (focused && overlayMode == OverlayMode.COMMUNITY) {
                communityFocus = CommunityFocus.CONTENT;
                activeRegion = Region.DISCORD;
            }
        });
        styleDiscordContentScroll(false);
        discordQuickMain.addView(discordContentScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f));
        discordContentContainer = new LinearLayout(context);
        discordContentContainer.setOrientation(LinearLayout.VERTICAL);
        discordContentScroll.addView(discordContentContainer, new ScrollView.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        discordVoiceContentContainer = new LinearLayout(context);
        discordVoiceContentContainer.setOrientation(LinearLayout.VERTICAL);
        discordContentContainer.addView(discordVoiceContentContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        discordSocialContentContainer = new LinearLayout(context);
        discordSocialContentContainer.setOrientation(LinearLayout.VERTICAL);
        discordContentContainer.addView(discordSocialContentContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        discordActionsContainer = new LinearLayout(context);
        discordActionsContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams discordActionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        discordActionsParams.topMargin = dp(8);
        discordQuickMain.addView(discordActionsContainer, discordActionsParams);
        discordQuickFooter = new TextView(context);
        discordQuickFooter.setText(R.string.discord_community_footer);
        discordQuickFooter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        discordQuickFooter.setTextColor(0xFFAEB3C2);
        discordQuickFooter.setGravity(Gravity.RIGHT);
        discordQuickMain.addView(discordQuickFooter, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(18)));
        LinearLayout.LayoutParams discordParams = new LinearLayout.LayoutParams(
                dp(COMMUNITY_QUICK_WIDTH_DP), dp(COMMUNITY_QUICK_HEIGHT_DP));
        discordParams.leftMargin = dp(BUTTON_SPACING_DP);
        discordParams.gravity = Gravity.BOTTOM | Gravity.LEFT;
        addView(discordContainer, discordParams);

        verticalButtons = new ArrayList<>();
        verticalActions = new ArrayList<>();
        horizontalButtons = new ArrayList<>();
        horizontalActions = new ArrayList<>();
        discordButtons = new ArrayList<>();
        discordActions = new ArrayList<>();

        activeRegion = Region.VERTICAL;
        verticalIndex = 0;
        horizontalIndex = 0;
        discordIndex = 0;

        commandsManager = new CustomCommandsManager(context);

        setVisibility(GONE);
    }

    public void buildMenu() {
        overlayMode = OverlayMode.MENU;
        playStationButtons = ControllerGlyphs.hasPlayStationController();
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
        discordButtons.clear();
        discordActions.clear();

        float density = getContext().getResources().getDisplayMetrics().density;
        int spacing = (int) (BUTTON_SPACING_DP * density);

        // Vertical column: top → bottom
        addVerticalShortcut(R.drawable.ic_overlay_guide, R.string.overlay_menu_guide,
                ACTION_SEND_GUIDE, spacing, ControllerGlyphs.Button.MENU);
        addVerticalShortcut(R.drawable.ic_overlay_mouse, R.string.overlay_menu_mouse_emulation,
                ACTION_TOGGLE_MOUSE_EMULATION, spacing, ControllerGlyphs.Button.WEST);
        addVerticalShortcut(R.drawable.ic_overlay_keyboard_toggle, R.string.overlay_menu_keyboard,
                ACTION_SHOW_KEYBOARD, spacing, ControllerGlyphs.Button.NORTH);
        addVerticalShortcut(R.drawable.ic_overlay_perf, R.string.overlay_menu_toggle_stats,
                ACTION_TOGGLE_STATS, spacing, ControllerGlyphs.Button.RIGHT_BUMPER);
        if (installationConfirmationAvailable) {
            addVerticalButton(R.drawable.ic_overlay_play,
                    getContext().getString(R.string.playnite_install_confirmation_done),
                    ACTION_INSTALLATION_CONFIRMED, spacing);
        }
        addVerticalButton(R.drawable.ic_overlay_power,
            getContext().getString(R.string.overlay_menu_suspend_session),
            ACTION_SUSPEND_SESSION, spacing);
        addVerticalButton(R.drawable.ic_overlay_power,
            getContext().getString(R.string.overlay_menu_quit_session), ACTION_QUIT, spacing);
        if (shouldShowDiscordCard(discordConfigured, discordSocialAvailable)) {
            addVerticalButton(R.drawable.ic_console_discord,
                    getContext().getString(R.string.discord_community_title),
                    ACTION_DISCORD_SOCIAL_FRIENDS, spacing);
        }
        addVerticalButton(R.drawable.ic_overlay_monitor,
            getContext().getString(R.string.overlay_menu_home), ACTION_HOME, 0);
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
        discordSocialFriendsButton = null;
        renderedDiscordActionSignature = "";
        ensureDiscordActions();
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
        OverlayMenuButton closeButton = addHorizontalButton(0,
                getContext().getString(R.string.overlay_menu_close), ACTION_CLOSE, 0);
        closeButton.setShortcut(ControllerGlyphs.typeface(getContext()), ControllerGlyphs.text(
                playStationButtons, ControllerGlyphs.Button.CANCEL));

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

    private void addVerticalShortcut(int iconResId, int labelRes, int action,
                                     int bottomMarginPx, ControllerGlyphs.Button shortcut) {
        addVerticalButton(iconResId, getContext().getString(labelRes), action, bottomMarginPx);
        verticalButtons.get(verticalButtons.size() - 1).setShortcut(
                ControllerGlyphs.typeface(getContext()),
                ControllerGlyphs.text(playStationButtons, shortcut));
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

    private void buildDiscordActions(int spacing) {
        discordActionsContainer.removeAllViews();
        discordButtons.clear();
        discordActions.clear();
        discordMuteButton = null;
        discordLeaveButton = null;
        discordRejoinButton = null;
        discordDockButton = null;
        discordSocialFriendsButton = null;
        if (!communityVoiceActionsVisible(discordQuickSection, discordConfigured)) return;

        boolean connected = discordVoice != null && discordVoice.connected;
        if (connected) {
            LinearLayout firstRow = new LinearLayout(getContext());
            firstRow.setOrientation(LinearLayout.HORIZONTAL);
            discordActionsContainer.addView(firstRow, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            discordMuteButton = addDiscordButton(firstRow, R.drawable.ic_overlay_microphone,
                    discordMuteLabel(), ACTION_DISCORD_MUTE, spacing);
            discordLeaveButton = addDiscordButton(firstRow, R.drawable.ic_overlay_close,
                    discordLeaveLabel(), ACTION_DISCORD_LEAVE, 0);
            LinearLayout secondRow = new LinearLayout(getContext());
            secondRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams secondRowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            secondRowParams.topMargin = spacing;
            discordActionsContainer.addView(secondRow, secondRowParams);
            discordDockButton = addDiscordButton(secondRow, R.drawable.ic_overlay_restore,
                    discordDockLabel(), ACTION_DISCORD_DOCK, spacing);
        } else {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            discordActionsContainer.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            if (communityRejoinVisible(connected, discordCanRejoin)) {
                discordRejoinButton = addDiscordButton(row, R.drawable.ic_overlay_play,
                        discordRejoinLabel(), ACTION_DISCORD_REJOIN, spacing);
            }
            discordDockButton = addDiscordButton(row, R.drawable.ic_overlay_restore,
                    discordDockLabel(), ACTION_DISCORD_DOCK, 0);
        }
    }

    private void ensureDiscordActions() {
        String signature = discordQuickSection + ":" + discordConfigured + ":"
                + (discordVoice != null && discordVoice.connected) + ":" + discordCanRejoin;
        if (signature.equals(renderedDiscordActionSignature)) return;
        renderedDiscordActionSignature = signature;
        int previous = discordIndex;
        buildDiscordActions(dp(BUTTON_SPACING_DP));
        if (communityFocus == CommunityFocus.ACTION && !discordButtons.isEmpty()) {
            post(() -> setDiscordIndex(Math.min(previous, discordButtons.size() - 1)));
        } else if (discordButtons.isEmpty()) {
            communityFocus = CommunityFocus.RAIL;
        }
    }

    private OverlayMenuButton addDiscordButton(LinearLayout row, int iconResId,
                                                String label, int action, int rightMarginPx) {
        OverlayMenuButton button = OverlayMenuButton.create(getContext(), iconResId, label);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.rightMargin = rightMarginPx;
        row.addView(button, params);

        discordButtons.add(button);
        discordActions.add(action);
        final int index = discordButtons.size() - 1;
        button.setOnClickListener(v -> selectAndActivate(Region.DISCORD, index));
        button.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                activeRegion = Region.DISCORD;
                discordIndex = index;
                if (overlayMode == OverlayMode.COMMUNITY) communityFocus = CommunityFocus.ACTION;
                button.setSelected(true);
            } else {
                button.setSelected(false);
            }
        });
        return button;
    }

    private void addDiscordRailIcon(Context context, int iconResource, int descriptionResource, int section) {
        ImageButton icon = new ImageButton(context);
        icon.setImageResource(iconResource);
        icon.setContentDescription(context.getString(descriptionResource));
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        icon.setFocusable(true);
        icon.setClickable(true);
        icon.setOnClickListener(ignored -> selectDiscordQuickSection(section));
        icon.setOnFocusChangeListener((ignored, focused) -> {
            if (focused) {
                // Rail navigation changes the section immediately; Center is only an optional
                // confirmation, never a prerequisite for a visible content update.
                selectDiscordQuickSection(section);
                communityFocus = CommunityFocus.RAIL;
                activeRegion = Region.DISCORD;
            }
            else styleDiscordRailIcon(icon, section == discordQuickSection, false);
        });
        styleDiscordRailIcon(icon, section == discordQuickSection, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(46), dp(46));
        params.topMargin = dp(8);
        discordRail.addView(icon, params);
        discordRailIcons.add(icon);
    }

    private void selectDiscordQuickSection(int section) {
        if (section < 0 || section >= discordRailIcons.size()) return;
        if (discordQuickSection == section) {
            for (int index = 0; index < discordRailIcons.size(); index++) {
                styleDiscordRailIcon(discordRailIcons.get(index), index == section,
                        discordRailIcons.get(index).hasFocus());
            }
            return;
        }
        discordQuickSection = section;
        for (int index = 0; index < discordRailIcons.size(); index++) {
            styleDiscordRailIcon(discordRailIcons.get(index), index == section,
                    discordRailIcons.get(index).hasFocus());
        }
        renderedDiscordSocialRevision = Long.MIN_VALUE;
        renderedDiscordSocialSection = -1;
        renderDiscordCard();
        applyOverlayMode();
        if (discordContentScroll != null) discordContentScroll.scrollTo(0, 0);
    }

    private void styleDiscordRailIcon(ImageButton icon, boolean selected, boolean focused) {
        icon.setColorFilter(selected ? Color.WHITE : 0xFF9398AA);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(12));
        background.setColor(selected || focused ? 0x1FFFFFFF : Color.TRANSPARENT);
        if (selected || focused) background.setStroke(dp(focused ? 2 : 1), 0xFF7CE4FF);
        icon.setBackground(background);
    }

    private void styleDiscordContentScroll(boolean focused) {
        if (discordContentScroll == null) return;
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(10));
        background.setColor(focused ? 0x102D5F78 : Color.TRANSPARENT);
        background.setStroke(focused ? dp(1) : 0, focused ? 0xFF7CE4FF : Color.TRANSPARENT);
        discordContentScroll.setBackground(background);
    }

    private void openCommunity() {
        if (!shouldShowDiscordCard(discordConfigured, discordSocialAvailable)) return;
        overlayMode = OverlayMode.COMMUNITY;
        discordQuickSection = 0;
        communityMotionDirection = KeyEvent.KEYCODE_UNKNOWN;
        communityFocus = CommunityFocus.RAIL;
        activeRegion = Region.DISCORD;
        discordIndex = 0;
        applyOverlayMode();
        renderDiscordCard();
        post(() -> {
            if (!discordRailIcons.isEmpty()) discordRailIcons.get(discordQuickSection).requestFocus();
        });
    }

    private void returnToMenu() {
        overlayMode = OverlayMode.MENU;
        communityMotionDirection = KeyEvent.KEYCODE_UNKNOWN;
        communityFocus = CommunityFocus.RAIL;
        applyOverlayMode();
        post(() -> {
            int index = verticalActions.indexOf(ACTION_DISCORD_SOCIAL_FRIENDS);
            if (index >= 0) setVerticalIndex(index);
            else if (!verticalButtons.isEmpty()) setVerticalIndex(verticalButtons.size() - 1);
        });
    }

    private void applyOverlayMode() {
        boolean community = overlayMode == OverlayMode.COMMUNITY;
        verticalContainer.setVisibility(community ? GONE : VISIBLE);
        horizontalScrollView.setVisibility(community ? GONE : VISIBLE);
        batteryContainer.setVisibility(community || batteryContainer.getChildCount() == 0 ? GONE : VISIBLE);
        menuSpacer.setVisibility(community ? GONE : VISIBLE);
        discordContainer.setVisibility(community && shouldShowDiscordCard(discordConfigured,
                discordSocialAvailable) ? VISIBLE : GONE);
    }

    static OverlayMode backMode(OverlayMode mode) {
        return mode == OverlayMode.COMMUNITY ? OverlayMode.MENU : OverlayMode.MENU;
    }

    static boolean communityVisible(OverlayMode mode, boolean available) {
        return mode == OverlayMode.COMMUNITY && available;
    }

    static int adjacentCommunitySection(int current, int keyCode, int count) {
        if (count <= 0) return current;
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            return Math.max(0, current - 1);
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R1 || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return Math.min(count - 1, current + 1);
        }
        return current;
    }

    static int nextCommunityRailIndex(int current, int direction, int count) {
        if (count <= 0) return current;
        return Math.max(0, Math.min(count - 1, current + direction));
    }

    static boolean communityVoiceActionsVisible(int section, boolean configured) {
        return section == 0 && configured;
    }

    static boolean communityRejoinVisible(boolean connected, boolean canRejoin) {
        return !connected && canRejoin;
    }

    static int communityActionTarget(int current, int keyCode, int count) {
        if (count <= 0) return -1;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            return current % 2 == 1 ? current - 1 : -1;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return current % 2 == 0 && current + 1 < count ? current + 1 : current;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return current < 2 ? current : current - 2;
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return Math.min(count - 1, current + 2);
        return current;
    }

    static int communityRegionTarget(int currentRegion, int keyCode, boolean hasActions) {
        if (currentRegion == COMMUNITY_REGION_RAIL && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return COMMUNITY_REGION_CONTENT;
        }
        if (currentRegion == COMMUNITY_REGION_ACTION && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            return COMMUNITY_REGION_CONTENT;
        }
        if (currentRegion == COMMUNITY_REGION_CONTENT) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return COMMUNITY_REGION_RAIL;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && hasActions) return COMMUNITY_REGION_ACTION;
        }
        return currentRegion;
    }

    static int communityContentScrollTarget(int currentScroll, int maxScroll, int delta) {
        return Math.max(0, Math.min(Math.max(0, maxScroll), currentScroll + delta));
    }

    static int communityActionMaxRows() { return 2; }
    static boolean communityContentUsesSingleScrollOwner() { return true; }

    static int communityQuickWidthDp() { return COMMUNITY_QUICK_WIDTH_DP; }
    static int communityQuickHeightDp() { return COMMUNITY_QUICK_HEIGHT_DP; }
    static int communityRailIconCount() { return COMMUNITY_RAIL_ICON_COUNT; }

    public void setMenuActionListener(MenuActionListener listener) {
        this.actionListener = listener;
    }

    public void setFlipFaceButtons(boolean flip) {
        this.flipFaceButtons = flip;
    }

    public void setInstallationConfirmationAvailable(boolean available) {
        installationConfirmationAvailable = available;
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

    public void setDiscordSocialAvailable(boolean available) {
        if (discordSocialAvailable == available) return;
        discordSocialAvailable = available;
        renderedDiscordSocialRevision = Long.MIN_VALUE;
        renderedDiscordSocialSection = -1;
        renderDiscordCard();
    }

    /** The process-wide client owns this snapshot; the overlay only projects a new revision. */
    public void setDiscordSocialState(DiscordSocialClient.Snapshot snapshot) {
        if (snapshot == null || snapshot.revision == discordSocialRevision) return;
        discordSocialRevision = snapshot.revision;
        discordSocialConnected = snapshot.connected;
        discordSocialDisplayName = snapshot.displayName;
        discordSocialAvatarUrl = snapshot.avatarUrl;
        discordSocialFriends = snapshot.friendDetails;
        renderDiscordCard();
        updateDiscordActionButtons();
    }

    public void toggleDiscordSocialFriends() {
        if (!discordSocialConnected) return;
        discordSocialExpanded = !discordSocialExpanded;
        renderedDiscordSocialRevision = Long.MIN_VALUE;
        renderDiscordCard();
        updateDiscordActionButtons();
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
        if (discordContainer == null || discordContentContainer == null) return;
        discordContainer.setVisibility(communityVisible(overlayMode,
                shouldShowDiscordCard(discordConfigured, discordSocialAvailable)) ? VISIBLE : GONE);
        if (!shouldShowDiscordCard(discordConfigured, discordSocialAvailable)) return;

        renderDiscordQuickHeader();
        ensureDiscordActions();
        renderDiscordVoiceCard();
        renderDiscordSocialCard();
    }

    private void renderDiscordQuickHeader() {
        discordQuickHeader.removeAllViews();
        TextView title = new TextView(getContext());
        title.setText(getContext().getString(discordQuickSection == 1
                ? R.string.discord_community_friends : discordQuickSection == 2
                ? R.string.discord_community_servers : R.string.discord_community_together));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(Color.WHITE);
        discordQuickHeader.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (discordSocialConnected) {
            LinearLayout account = new LinearLayout(getContext());
            account.setGravity(Gravity.CENTER_VERTICAL);
            account.addView(DiscordCommunityPresentation.avatar(getContext(),
                    discordSocialDisplayName.isEmpty() ? "Discord" : discordSocialDisplayName,
                    discordSocialAvatarUrl, 34), new LinearLayout.LayoutParams(dp(34), dp(34)));
            TextView name = new TextView(getContext());
            name.setText(discordSocialDisplayName);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            name.setTextColor(0xFFB8BDCC);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nameParams.leftMargin = dp(7);
            account.addView(name, nameParams);
            discordQuickHeader.addView(account);
        }
    }

    private void renderDiscordVoiceCard() {
        discordVoiceContentContainer.removeAllViews();
        if (discordQuickSection == 1) return;
        if (!discordConfigured) return;

        if (discordLoading && discordVoice == null) {
            discordVoiceContentContainer.addView(discordLine(
                    getContext().getString(R.string.overlay_discord_loading),
                    14, 0xFFC5C8D3, false));
            return;
        }
        if (discordError != null && !discordError.isEmpty()) {
            discordVoiceContentContainer.addView(discordLine(discordError, 13, 0xFFFFB4AB, false));
            return;
        }
        if (discordVoice == null || !discordVoice.connected) {
            discordVoiceContentContainer.addView(discordLine(
                    getContext().getString(R.string.overlay_discord_disconnected),
                    14, 0xFFC5C8D3, false));
            return;
        }

        String channel = discordVoice.channelName == null || discordVoice.channelName.isEmpty() ?
                "Voice" : discordVoice.channelName;
        discordVoiceContentContainer.addView(discordLine(getContext().getString(
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
            discordVoiceContentContainer.addView(discordLine(name, 14,
                    participant.speaking ? 0xFF69F0AE : 0xFFE6E1E9, false));
            shown++;
        }
        if (discordVoice.participants.size() <= 1) {
            discordVoiceContentContainer.addView(discordLine(
                    getContext().getString(R.string.overlay_discord_empty),
                    12, 0xFF9FA3B2, false));
        } else if (discordVoice.participants.size() > shown) {
            discordVoiceContentContainer.addView(discordLine(
                    "+" + (discordVoice.participants.size() - shown),
                    12, 0xFF9FA3B2, false));
        }
    }

    private void renderDiscordSocialCard() {
        if (!discordSocialAvailable) {
            discordSocialContentContainer.removeAllViews();
            renderedDiscordSocialRevision = Long.MIN_VALUE;
            renderedDiscordSocialSection = -1;
            return;
        }
        if (renderedDiscordSocialRevision == discordSocialRevision
                && renderedDiscordSocialSection == discordQuickSection) return;
        discordSocialContentContainer.removeAllViews();
        renderedDiscordSocialRevision = discordSocialRevision;
        renderedDiscordSocialSection = discordQuickSection;
        if (discordQuickSection == 2) {
            discordSocialContentContainer.addView(discordLine(getContext().getString(
                    R.string.discord_community_host_unavailable), 13, 0xFF9FA3B2, false));
            return;
        }
        discordSocialContentContainer.addView(discordLine(getContext().getString(
                R.string.overlay_community_active), 13, 0xFF69F0AE, true));
        if (!discordSocialConnected) {
            discordSocialContentContainer.addView(discordLine(getContext().getString(
                    R.string.overlay_discord_social_connect_in_menu), 14, 0xFFC5C8D3, false));
            return;
        }
        discordSocialContentContainer.addView(discordLine(getContext().getString(
                R.string.overlay_discord_social_connected,
                discordSocialDisplayName.isEmpty() ? "Discord" : discordSocialDisplayName),
                14, Color.WHITE, false));
        if (!discordSocialExpanded && discordQuickSection != 1) return;
        int playing = 0;
        int online = 0;
        for (DiscordSocialClient.Friend friend : discordSocialFriends) {
            if (friend.group == DiscordSocialClient.Friend.Group.PLAYING) playing++;
            else if (friend.group == DiscordSocialClient.Friend.Group.ONLINE) online++;
        }
        if (playing == 0 && online == 0) {
            discordSocialContentContainer.addView(discordLine(getContext().getString(
                    R.string.overlay_discord_social_no_friends), 13, 0xFF9FA3B2, false));
            return;
        }
        int shown = addDiscordSocialFriends(DiscordSocialClient.Friend.Group.PLAYING,
                R.string.overlay_discord_social_playing, 3);
        addDiscordSocialFriends(DiscordSocialClient.Friend.Group.ONLINE,
                R.string.overlay_discord_social_online, 3 - shown);
    }

    private int addDiscordSocialFriends(DiscordSocialClient.Friend.Group group, int title, int max) {
        boolean headingAdded = false;
        int shown = 0;
        for (DiscordSocialClient.Friend friend : discordSocialFriends) {
            if (friend.group != group || shown >= max) continue;
            if (!headingAdded) {
                discordSocialContentContainer.addView(discordLine(getContext().getString(title),
                        12, 0xFF9FA3B2, true));
                headingAdded = true;
            }
            discordSocialContentContainer.addView(discordSocialFriendRow(friend));
            shown++;
        }
        return shown;
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

    private View discordSocialFriendRow(DiscordSocialClient.Friend friend) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setContentDescription(friend.activityName.isEmpty() ? friend.displayName
                : friend.displayName + ". " + friend.activityName);
        FrameLayout avatar = DiscordCommunityPresentation.avatar(getContext(), friend.displayName,
                friend.avatarUrl, 40);
        if (friend.group != DiscordSocialClient.Friend.Group.OFFLINE) {
            View dot = new View(getContext());
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(0xFF61E594);
            dot.setBackground(background);
            FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(dp(9), dp(9),
                    Gravity.RIGHT | Gravity.BOTTOM);
            dotParams.rightMargin = dp(1);
            dotParams.bottomMargin = dp(1);
            avatar.addView(dot, dotParams);
        }
        row.addView(avatar, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout text = new LinearLayout(getContext());
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = discordLine(friend.displayName, 14, 0xFFE6E1E9, false);
        title.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        text.addView(title);
        String status = friend.activityName;
        if (status.isEmpty()) {
            status = friend.group == DiscordSocialClient.Friend.Group.PLAYING
                    ? getContext().getString(R.string.overlay_discord_social_playing)
                    : getContext().getString(R.string.overlay_discord_social_online);
        }
        TextView meta = discordLine(status, 12, 0xFF9FA3B2, false);
        meta.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        text.addView(meta);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = dp(9);
        textParams.bottomMargin = dp(4);
        row.addView(text, textParams);
        return row;
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
        if (discordSocialFriendsButton != null) {
            discordSocialFriendsButton.setLabel(discordSocialFriendsLabel());
            discordSocialFriendsButton.setAlpha(discordSocialConnected ? 1f : 0.45f);
        }
    }

    private String discordSocialFriendsLabel() {
        return getContext().getString(socialActionLabel(discordSocialConnected, discordSocialExpanded));
    }

    static boolean shouldShowDiscordCard(boolean hostVoiceConfigured, boolean socialAvailable) {
        return hostVoiceConfigured || socialAvailable;
    }

    static int socialActionLabel(boolean socialConnected, boolean socialExpanded) {
        if (!socialConnected) return R.string.overlay_discord_social_connect_in_menu;
        return socialExpanded ? R.string.overlay_discord_social_hide_friends
                : R.string.overlay_discord_social_friends;
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

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
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
                if (overlayMode == OverlayMode.COMMUNITY) returnToMenu(); else closeMenu();
            }
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (flipFaceButtons) {
                keyCode = handleFlipFaceButtons(keyCode);
            }

            if (overlayMode == OverlayMode.COMMUNITY) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                        || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    lastCommunityDirectionalKeyAt = event.getEventTime();
                }
                if (keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_BUTTON_R1) {
                    CommunityFocus previousFocus = communityFocus;
                    int next = adjacentCommunitySection(discordQuickSection, keyCode,
                            discordRailIcons.size());
                    if (next != discordQuickSection) selectDiscordQuickSection(next);
                    restoreCommunityFocusAfterSectionChange(previousFocus, next);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (communityFocus == CommunityFocus.ACTION) {
                        int next = communityActionTarget(discordIndex, keyCode,
                                discordButtons.size());
                        if (next >= 0) {
                            setDiscordIndex(next);
                            return true;
                        }
                        if (communityRegionTarget(COMMUNITY_REGION_ACTION, keyCode,
                                !discordButtons.isEmpty()) == COMMUNITY_REGION_CONTENT) {
                            focusCommunityContent();
                            return true;
                        }
                    }
                    communityFocus = CommunityFocus.RAIL;
                    activeRegion = Region.DISCORD;
                    if (!discordRailIcons.isEmpty()) discordRailIcons.get(discordQuickSection).requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (communityFocus == CommunityFocus.ACTION) {
                        int next = communityActionTarget(discordIndex, keyCode,
                                discordButtons.size());
                        if (next >= 0) setDiscordIndex(next);
                    } else if (communityFocus == CommunityFocus.CONTENT
                            && communityRegionTarget(COMMUNITY_REGION_CONTENT, keyCode,
                            !discordButtons.isEmpty()) == COMMUNITY_REGION_ACTION) {
                        communityFocus = CommunityFocus.ACTION;
                        setDiscordIndex(0);
                    } else if (communityFocus == CommunityFocus.RAIL
                            && communityRegionTarget(COMMUNITY_REGION_RAIL, keyCode,
                            !discordButtons.isEmpty()) == COMMUNITY_REGION_CONTENT) {
                        focusCommunityContent();
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (communityFocus == CommunityFocus.RAIL) {
                        int direction = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                        int next = nextCommunityRailIndex(discordQuickSection, direction,
                                discordRailIcons.size());
                        if (next != discordQuickSection) selectDiscordQuickSection(next);
                        if (!discordRailIcons.isEmpty()) discordRailIcons.get(next).requestFocus();
                    } else if (communityFocus == CommunityFocus.CONTENT) {
                        scrollCommunityContent(keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                    } else if (!discordButtons.isEmpty()) {
                        setDiscordIndex(communityActionTarget(discordIndex, keyCode,
                                discordButtons.size()));
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (communityFocus == CommunityFocus.ACTION) activateSelected();
                    else selectDiscordQuickSection(discordQuickSection);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_BUTTON_B) { returnToMenu(); return true; }
                return isGamepadEvent(event);
            }

            if (handleDiscordRailKey(keyCode)) return true;

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

    private boolean handleDiscordRailKey(int keyCode) {
        View focused = findFocus();
        if (focused == null || focused.getParent() != discordRail) return false;
        int current = discordRailIcons.indexOf(focused);
        if (current < 0) return false;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            discordRailIcons.get(Math.max(0, current - 1)).requestFocus();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            discordRailIcons.get(Math.min(discordRailIcons.size() - 1, current + 1)).requestFocus();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (!discordButtons.isEmpty()) setDiscordIndex(0);
            else if (!verticalButtons.isEmpty()) setVerticalIndex(0);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            focused.performClick();
            return true;
        }
        return false;
    }

    private void focusCommunityContent() {
        if (discordContentScroll == null) return;
        communityFocus = CommunityFocus.CONTENT;
        activeRegion = Region.DISCORD;
        discordContentScroll.requestFocus();
    }

    private void restoreCommunityFocusAfterSectionChange(CommunityFocus previous, int section) {
        if (previous == CommunityFocus.CONTENT) {
            focusCommunityContent();
            return;
        }
        if (previous == CommunityFocus.ACTION && !discordButtons.isEmpty()) {
            communityFocus = CommunityFocus.ACTION;
            setDiscordIndex(Math.min(discordIndex, discordButtons.size() - 1));
            return;
        }
        communityFocus = CommunityFocus.RAIL;
        activeRegion = Region.DISCORD;
        if (!discordRailIcons.isEmpty()) discordRailIcons.get(section).requestFocus();
    }

    private void scrollCommunityContent(int direction) {
        if (discordContentScroll == null) return;
        View child = discordContentScroll.getChildCount() == 0 ? null
                : discordContentScroll.getChildAt(0);
        int max = child == null ? 0 : child.getHeight() - discordContentScroll.getHeight();
        int target = communityContentScrollTarget(discordContentScroll.getScrollY(), max,
                direction * dp(84));
        if (target != discordContentScroll.getScrollY()) {
            discordContentScroll.smoothScrollTo(0, target);
        }
    }

    /**
     * The quick Community panel has one content focus lane. Keep its vertical movement local
     * instead of letting Android search through the hidden overlay menu containers.
     */
    private void navigateCommunity(int direction) {
        if (discordButtons.isEmpty()) return;
        if (activeRegion != Region.DISCORD) {
            setDiscordIndex(0);
            return;
        }
        setDiscordIndex(Math.max(0, Math.min(discordButtons.size() - 1,
                discordIndex + direction)));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!isFullGamepadEvent(event) && event.getRepeatCount() == 0) {
                if (overlayMode == OverlayMode.COMMUNITY) returnToMenu(); else closeMenu();
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
        if (overlayMode == OverlayMode.COMMUNITY) {
            return handleCommunityMotion(event) || super.onGenericMotionEvent(event);
        }
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

    /** DualSense D-pad is HAT motion on Android TV; send its edges through the Community key reducer. */
    private boolean handleCommunityMotion(MotionEvent event) {
        if (!isGamepadMotionEvent(event) || event.getAction() != MotionEvent.ACTION_MOVE) return false;
        InputDevice device = event.getDevice();
        boolean hasHat = device != null && (device.getMotionRange(MotionEvent.AXIS_HAT_X) != null
                || device.getMotionRange(MotionEvent.AXIS_HAT_Y) != null);
        float horizontal = hasHat ? event.getAxisValue(MotionEvent.AXIS_HAT_X)
                : event.getAxisValue(MotionEvent.AXIS_X);
        float vertical = hasHat ? event.getAxisValue(MotionEvent.AXIS_HAT_Y)
                : event.getAxisValue(MotionEvent.AXIS_Y);
        int direction = communityMotionDirection(horizontal, vertical, hasHat ? .45f : .85f);
        if (direction == KeyEvent.KEYCODE_UNKNOWN) {
            boolean consumed = communityMotionDirection != KeyEvent.KEYCODE_UNKNOWN;
            communityMotionDirection = KeyEvent.KEYCODE_UNKNOWN;
            return consumed;
        }
        if (direction == communityMotionDirection) return true;
        communityMotionDirection = direction;
        if (Math.abs(event.getEventTime() - lastCommunityDirectionalKeyAt) < 80L) return true;
        return dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, direction));
    }

    static int communityMotionDirection(float horizontal, float vertical, float threshold) {
        float absX = Math.abs(horizontal);
        float absY = Math.abs(vertical);
        if (Math.max(absX, absY) < threshold) return KeyEvent.KEYCODE_UNKNOWN;
        if (absX > absY) return horizontal < 0f ? KeyEvent.KEYCODE_DPAD_LEFT
                : KeyEvent.KEYCODE_DPAD_RIGHT;
        return vertical < 0f ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
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
        } else if (activeRegion == Region.HORIZONTAL) {
            // From horizontal → Up: jump to button above Disconnect (second-to-last in vertical)
            clearHorizontalSelection();
            activeRegion = Region.VERTICAL;
            int target = verticalButtons.size() >= 2 ? verticalButtons.size() - 2 : 0;
            setVerticalIndex(target);
        } else if (!discordButtons.isEmpty()) {
            setDiscordIndex(discordIndex >= 2 ? discordIndex - 2 :
                    Math.min(discordIndex + 2, discordButtons.size() - 1));
        }
    }

    private void navigateDown() {
        if (activeRegion == Region.VERTICAL) {
            if (!verticalButtons.isEmpty()) {
                setVerticalIndex((verticalIndex + 1) % verticalButtons.size());
            }
        } else if (activeRegion == Region.HORIZONTAL) {
            // From horizontal → Down: jump to topmost vertical button
            clearHorizontalSelection();
            activeRegion = Region.VERTICAL;
            setVerticalIndex(0);
        } else if (!discordButtons.isEmpty()) {
            if (discordIndex + 2 < discordButtons.size()) {
                setDiscordIndex(discordIndex + 2);
            } else if (!horizontalButtons.isEmpty()) {
                clearDiscordSelection();
                activeRegion = Region.HORIZONTAL;
                int bitrateIndex = horizontalActions.indexOf(ACTION_BITRATE_APPLY);
                setHorizontalIndex(bitrateIndex >= 0 ? bitrateIndex : 0);
            }
        }
    }

    private void navigateLeft() {
        if (activeRegion == Region.DISCORD) {
            if (discordIndex % 2 == 1) {
                setDiscordIndex(discordIndex - 1);
            } else {
                clearDiscordSelection();
                activeRegion = Region.VERTICAL;
                setVerticalIndex(Math.min(verticalIndex, verticalButtons.size() - 1));
            }
        } else if (activeRegion == Region.HORIZONTAL) {
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
        if (activeRegion == Region.DISCORD) {
            if (discordIndex % 2 == 0 && discordIndex + 1 < discordButtons.size()) {
                setDiscordIndex(discordIndex + 1);
            } else {
                clearDiscordSelection();
                activeRegion = Region.VERTICAL;
                setVerticalIndex(0);
            }
        } else if (activeRegion == Region.VERTICAL) {
            // Discord is a top-level panel on the right, so it must be reachable
            // directly from the main column. Requiring users to traverse every
            // item in the crowded bottom strip made the most common voice
            // controls needlessly difficult to reach with a controller.
            if (!discordButtons.isEmpty()) {
                clearVerticalSelection();
                activeRegion = Region.DISCORD;
                setDiscordIndex(0);
            } else if (!horizontalButtons.isEmpty()) {
                clearVerticalSelection();
                activeRegion = Region.HORIZONTAL;
                setHorizontalIndex(0);
            }
        } else {
            if (horizontalIndex < horizontalButtons.size() - 1) {
                setHorizontalIndex(horizontalIndex + 1);
            } else if (!discordButtons.isEmpty()) {
                clearHorizontalSelection();
                activeRegion = Region.DISCORD;
                setDiscordIndex(0);
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

    private void setDiscordIndex(int index) {
        if (index < 0 || index >= discordButtons.size()) return;
        for (OverlayMenuButton button : discordButtons) button.setSelected(false);
        discordIndex = index;
        discordButtons.get(index).setSelected(true);
        discordButtons.get(index).requestFocus();
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

    private void clearDiscordSelection() {
        for (OverlayMenuButton button : discordButtons) button.setSelected(false);
    }

    private void selectAndActivate(Region region, int index) {
        activeRegion = region;
        if (region == Region.VERTICAL) {
            setVerticalIndex(index);
        } else if (region == Region.HORIZONTAL) {
            setHorizontalIndex(index);
        } else {
            setDiscordIndex(index);
        }
        activateSelected();
    }

    private void activateSelected() {
        int action;
        if (activeRegion == Region.VERTICAL) {
            if (verticalIndex < 0 || verticalIndex >= verticalActions.size()) return;
            action = verticalActions.get(verticalIndex);
        } else if (activeRegion == Region.HORIZONTAL) {
            if (horizontalIndex < 0 || horizontalIndex >= horizontalActions.size()) return;
            action = horizontalActions.get(horizontalIndex);
        } else {
            if (discordIndex < 0 || discordIndex >= discordActions.size()) return;
            action = discordActions.get(discordIndex);
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
            if (action == ACTION_HOME) {
                actionListener.onHome();
                shouldCloseMenu = true;
            } else if (action == ACTION_QUIT) {
                actionListener.onQuitSession();
                shouldCloseMenu = true;
            } else if (action == ACTION_SUSPEND_SESSION) {
                actionListener.onSuspendSession();
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
            } else if (action == ACTION_DISCORD_SOCIAL_FRIENDS) {
                if (overlayMode == OverlayMode.MENU) openCommunity();
                else actionListener.onDiscordSocialFriends();
                return;
            } else if (action == ACTION_INSTALLATION_CONFIRMED) {
                actionListener.onInstallationConfirmed();
                shouldCloseMenu = true;
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
        overlayMode = OverlayMode.MENU;
        communityMotionDirection = KeyEvent.KEYCODE_UNKNOWN;
        applyOverlayMode();
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
