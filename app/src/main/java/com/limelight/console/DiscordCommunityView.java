package com.limelight.console;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.discord.DiscordSocialClient;
import com.limelight.ui.ControllerGlyphs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Native, full-screen Community composition.  It deliberately owns the stable shell, rather
 * than rebuilding a generic side-panel action list when Social/Gateway snapshots refresh.
 */
final class DiscordCommunityView extends LinearLayout {
    interface Callback {
        void onTab(DiscordCommunityState.Tab tab);
        void onDestinationFocused(DiscordCommunityPresentation.Destination destination);
        void onDestinationOpened(DiscordCommunityPresentation.Destination destination);
        void onOpenDirectMessage(DiscordCommunityPresentation.Destination destination);
        void onUpgradeDirectMessages();
        void onDirectMessageDraftChanged(long recipientId, String draft);
        void onSendDirectMessage(long recipientId, String content);
        void onOpenDirectMessageInDiscord(long messageId);
        void onDirectMessageDictationRequested(long recipientId, long directMessageGeneration);
        void onCloseDirectMessage();
        void onJoin(HostGatewayClient.DiscordChannel channel);
        void onAuthorize();
        void onOptions();
        void onVoiceAction(DiscordPanelController.CommunityVoiceAction action);
        void onOption(DiscordPanelController.CommunitySetting setting, boolean enabled);
        void onHostAction(DiscordPanelController.CommunityHostAction action);
        void onOpenAudio();
        void onOpenSocial();
        void onDmNotificationsChanged(boolean enabled);
        void onAudioDevice(HostGatewayClient.AudioDevice device);
        void onAudioVolume(int delta);
        void onAudioMute();
        void onUnlink();
    }

    static final class Model {
        final DiscordSocialClient.Snapshot snapshot;
        final DiscordCommunityState state;
        final List<DiscordCommunityPresentation.Destination> active;
        final List<DiscordCommunityPresentation.Destination> recent;
        final List<DiscordCommunityPresentation.Destination> guildChannels;
        final boolean homeLoading;
        final boolean homeUnavailable;
        final boolean guildLoading;
        final boolean guildUnavailable;
        final String error;
        final HostGatewayClient.DiscordVoice voice;
        final boolean voiceLoading;
        final boolean voiceBusy;
        final DiscordPanelController.CommunityOptions options;
        final boolean optionsLoading;
        final String optionsError;
        final HostGatewayClient.DiscordAudioState audio;
        final boolean audioLoading;
        final boolean audioBusy;
        final String audioError;
        final DiscordSocialClient.Friend directFriend;
        final List<DiscordDirectMessageState.Message> directMessages;
        final DiscordDirectMessageState.SendState directSendState;
        final String directDraft;
        final boolean directMessagesScopeAvailable;
        final boolean directMessagesCapable;
        final boolean dmNotificationsEnabled;

        Model(DiscordSocialClient.Snapshot snapshot, DiscordCommunityState state,
              List<DiscordCommunityPresentation.Destination> active,
              List<DiscordCommunityPresentation.Destination> recent,
              List<DiscordCommunityPresentation.Destination> guildChannels,
              boolean homeLoading, boolean homeUnavailable, boolean guildLoading,
              boolean guildUnavailable, String error, HostGatewayClient.DiscordVoice voice) {
            this(snapshot, state, active, recent, guildChannels, homeLoading, homeUnavailable,
                    guildLoading, guildUnavailable, error, voice, false, false, null, false, "",
                    null, false, false, "", null, Collections.emptyList(),
                    new DiscordDirectMessageState.SendState(false, false, 0, ""), "", false, false,
                    true);
        }

        Model(DiscordSocialClient.Snapshot snapshot, DiscordCommunityState state,
              List<DiscordCommunityPresentation.Destination> active,
              List<DiscordCommunityPresentation.Destination> recent,
              List<DiscordCommunityPresentation.Destination> guildChannels,
              boolean homeLoading, boolean homeUnavailable, boolean guildLoading,
              boolean guildUnavailable, String error, HostGatewayClient.DiscordVoice voice,
              boolean voiceLoading, boolean voiceBusy, DiscordPanelController.CommunityOptions options,
              boolean optionsLoading, String optionsError, HostGatewayClient.DiscordAudioState audio,
              boolean audioLoading, boolean audioBusy, String audioError,
              DiscordSocialClient.Friend directFriend,
              List<DiscordDirectMessageState.Message> directMessages,
              DiscordDirectMessageState.SendState directSendState, String directDraft,
              boolean directMessagesScopeAvailable, boolean directMessagesCapable,
              boolean dmNotificationsEnabled) {
            this.snapshot = snapshot;
            this.state = state;
            this.active = active;
            this.recent = recent;
            this.guildChannels = guildChannels;
            this.homeLoading = homeLoading;
            this.homeUnavailable = homeUnavailable;
            this.guildLoading = guildLoading;
            this.guildUnavailable = guildUnavailable;
            this.error = error;
            this.voice = voice;
            this.voiceLoading = voiceLoading;
            this.voiceBusy = voiceBusy;
            this.options = options;
            this.optionsLoading = optionsLoading;
            this.optionsError = optionsError;
            this.audio = audio;
            this.audioLoading = audioLoading;
            this.audioBusy = audioBusy;
            this.audioError = audioError == null ? "" : audioError;
            this.directFriend = directFriend;
            this.directMessages = directMessages == null ? Collections.emptyList() : directMessages;
            this.directSendState = directSendState == null
                    ? new DiscordDirectMessageState.SendState(false, false, 0, "") : directSendState;
            this.directDraft = directDraft == null ? "" : directDraft;
            this.directMessagesScopeAvailable = directMessagesScopeAvailable;
            this.directMessagesCapable = directMessagesScopeAvailable && directMessagesCapable;
            this.dmNotificationsEnabled = dmNotificationsEnabled;
        }
    }

    private static final int WHITE = 0xFFF4F7FF;
    private static final int MUTED = 0xFFA2A9BB;
    private static final int CYAN = 0xFF77E5FF;
    private static final int CARD = 0xFF171E2B;
    private static final int ROW = 0x18000000;
    // These are deliberately calibrated for the 960dp-wide, 540dp-tall 1080p TV viewport.
    // The visual mock is expressed in physical 1080p pixels, so the old 78/54dp tokens were
    // visibly twice as tall on the BRAVIA's density=2 configuration.
    private static final int ACTIVITY_HEIGHT_DP = 56;
    private static final int DESTINATION_HEIGHT_DP = 44;
    private static final float HISTORY_SCROLL_DEAD_ZONE = .45f;
    private static final long HISTORY_SCROLL_REPEAT_MS = 110L;
    enum ActionTone { NEUTRAL, POSITIVE_JOIN, DESTRUCTIVE_LEAVE }
    private final Callback callback;
    private final LinearLayout topChrome;
    private final LinearLayout tabBar;
    private final LinearLayout activeContainer;
    private final TextView recentLabel;
    private final ScrollView recentScroll;
    private final LinearLayout recentList;
    private final ScrollView detailScroll;
    private final LinearLayout detail;
    private final TextView accountName;
    private final FrameLayout accountAvatar;
    private final LinearLayout account;
    private final LinearLayout feedColumn;
    private final View contentDivider;
    private final LinearLayout directWorkspace;
    private final ScrollView directHistoryScroll;
    private final LinearLayout directHistory;
    private final LinearLayout communityFooter;
    private EditText directComposer;
    private TextView directSend;
    private EmbeddedTvKeyboardView directKeyboard;
    private boolean directKeyboardVisible = true;
    private int directKeyboardBackKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private long directKeyboardRecipientId = -1;
    private boolean directNearBottom = true;
    private long boundDirectRecipientId = -1;
    private long directLastMessageId;
    private boolean pendingNewMessagePill;
    private TextView pendingNewMessageView;
    private final List<DirectMediaRow> directMediaRows = new ArrayList<>();
    private final Map<String, View> feedDestinationViews = new HashMap<>();
    private final Map<String, View> detailDestinationViews = new HashMap<>();
    private final Map<String, View> detailFocusViewsByTag = new HashMap<>();
    private final Map<View, ScrollView> feedFocusOwners = new HashMap<>();
    private final List<List<View>> feedFocusRows = new ArrayList<>();
    private final List<View> detailFocusables = new ArrayList<>();
    private final List<TextView> tabs = new ArrayList<>();
    private Model model;
    private View firstDestination;
    private View detailAction;
    private String boundFeedKey = "";
    private DiscordCommunityState.Tab boundFeedTab;
    private String logicalFocusTag = "";
    // Android TV exposes the DualSense D-pad as AXIS_HAT_X/Y, not as DPAD KeyEvents.
    // Keep a latched direction so a held pad produces one semantic transition only.
    private int activeMotionDirection = KeyEvent.KEYCODE_UNKNOWN;
    private boolean leftTriggerLatched;
    private boolean rightTriggerLatched;
    private long lastHistoryScrollEventTime = Long.MIN_VALUE;

    private static final class DirectMediaRow {
        final View view;
        final long messageId;

        DirectMediaRow(View view, long messageId) {
            this.view = view;
            this.messageId = messageId;
        }
    }

    DiscordCommunityView(Context context, Callback callback) {
        super(context);
        this.callback = callback;
        setOrientation(VERTICAL);
        setPadding(dp(38), dp(48), dp(38), dp(20));
        setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        topChrome = new LinearLayout(context);
        topChrome.setGravity(Gravity.BOTTOM);
        LinearLayout heading = new LinearLayout(context);
        heading.setOrientation(VERTICAL);
        TextView title = text(context.getString(R.string.discord_community_title), 28, WHITE);
        heading.addView(title);
        tabBar = new LinearLayout(context);
        tabBar.setOrientation(HORIZONTAL);
        heading.addView(tabBar);
        topChrome.addView(heading, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        account = new LinearLayout(context);
        account.setGravity(Gravity.CENTER_VERTICAL);
        accountName = text("Discord", 13, MUTED);
        account.addView(accountName);
        accountAvatar = DiscordCommunityPresentation.avatar(context, "Discord", "", 34);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        avatarParams.leftMargin = dp(8);
        account.addView(accountAvatar, avatarParams);
        topChrome.addView(account);
        LinearLayout.LayoutParams topParams = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        topParams.bottomMargin = dp(22);
        addView(topChrome, topParams);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(HORIZONTAL);
        feedColumn = new LinearLayout(context);
        feedColumn.setOrientation(VERTICAL);
        activeContainer = new LinearLayout(context);
        activeContainer.setOrientation(VERTICAL);
        feedColumn.addView(activeContainer, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        recentLabel = section(context.getString(R.string.discord_community_recent));
        recentLabel.setVisibility(GONE);
        feedColumn.addView(recentLabel, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        recentScroll = new ScrollView(context);
        recentScroll.setFillViewport(true);
        recentScroll.setVerticalScrollBarEnabled(false);
        recentList = new LinearLayout(context);
        recentList.setOrientation(VERTICAL);
        recentScroll.addView(recentList, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        feedColumn.addView(recentScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));
        content.addView(feedColumn, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1.4f));
        contentDivider = new View(context);
        contentDivider.setBackgroundColor(0x24FFFFFF);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(1), MATCH_PARENT);
        dividerParams.leftMargin = dp(24);
        content.addView(contentDivider, dividerParams);
        detailScroll = new ScrollView(context);
        detailScroll.setFillViewport(true);
        detailScroll.setVerticalScrollBarEnabled(false);
        detail = new LinearLayout(context);
        detail.setOrientation(VERTICAL);
        detail.setPadding(dp(18), 0, 0, 0);
        detailScroll.addView(detail, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        content.addView(detailScroll, new LinearLayout.LayoutParams(0, MATCH_PARENT, .8f));
        directWorkspace = new LinearLayout(context);
        directWorkspace.setOrientation(VERTICAL);
        directWorkspace.setVisibility(GONE);
        directHistoryScroll = new ScrollView(context);
        directHistoryScroll.setFillViewport(true);
        directHistoryScroll.setVerticalScrollBarEnabled(false);
        directHistory = new LinearLayout(context);
        directHistory.setOrientation(VERTICAL);
        directHistory.setPadding(0, dp(8), 0, dp(8));
        directHistoryScroll.addView(directHistory, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        directHistoryScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            directNearBottom = isNearBottom(directHistoryScroll);
            if (directNearBottom && pendingNewMessagePill) clearPendingNewMessagePill();
        });
        content.addView(directWorkspace, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f));
        addView(content, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

        communityFooter = addCommunityFooter(context);
        buildTabs();
    }

    /** Fixed footer: use the installed controller glyph font, never look-alike Unicode symbols. */
    private LinearLayout addCommunityFooter(Context context) {
        LinearLayout footer = new LinearLayout(context);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.setPadding(0, dp(8), 0, 0);
        boolean playStation = ControllerGlyphs.hasPlayStationController();
        addFooterItem(footer, ControllerGlyphs.text(playStation, ControllerGlyphs.Button.CONFIRM),
                context.getString(R.string.console_select_hint));
        addFooterItem(footer, ControllerGlyphs.text(playStation, ControllerGlyphs.Button.CANCEL),
                context.getString(R.string.playnite_legend_back));
        addView(footer, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        return footer;
    }

    private void addFooterItem(LinearLayout footer, String glyph, String label) {
        TextView icon = text(glyph, 16, WHITE);
        icon.setTypeface(ControllerGlyphs.typeface(getContext()));
        footer.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView description = text(label, 11, MUTED);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        descriptionParams.leftMargin = dp(4);
        descriptionParams.rightMargin = dp(14);
        footer.addView(description, descriptionParams);
    }

    void bind(Model value) {
        String focusId = focusedDestinationId();
        model = value;
        boolean chatOnly = isChatOnlyPresentation(value.state.detail);
        setChatOnlyPresentation(chatOnly);
        if (chatOnly) {
            feedColumn.setVisibility(GONE);
            contentDivider.setVisibility(GONE);
            detailScroll.setVisibility(GONE);
            directWorkspace.setVisibility(VISIBLE);
            bindDirectMessage();
            return;
        }
        bindAccount(value.snapshot);
        bindTabs();
        feedColumn.setVisibility(VISIBLE);
        contentDivider.setVisibility(VISIBLE);
        detailScroll.setVisibility(VISIBLE);
        directWorkspace.setVisibility(GONE);
        String nextFeedKey = feedKey(value);
        if (!nextFeedKey.equals(boundFeedKey)) {
            boundFeedKey = nextFeedKey;
            bindFeed();
        }
        updateDetail(find(value.state.selectedId));
        wireCommunityFocus();
        if (focusId != null) logicalFocusTag = focusId;
        if (!logicalFocusTag.isEmpty()) restoreFocus(logicalFocusTag);
    }

    private void setChatOnlyPresentation(boolean chatOnly) {
        topChrome.setVisibility(chatOnly ? GONE : VISIBLE);
        communityFooter.setVisibility(chatOnly ? GONE : VISIBLE);
        int horizontal = dp(chatOnly ? 22 : 38);
        setPadding(horizontal, dp(chatOnly ? 18 : 48), horizontal, dp(chatOnly ? 18 : 20));
    }

    static boolean isChatOnlyPresentation(DiscordCommunityState.Detail detail) {
        return detail == DiscordCommunityState.Detail.DIRECT_MESSAGE;
    }

    void focusDetailAction() {
        if (detailAction != null) detailAction.post(() -> {
            detailAction.requestFocus();
            revealInOwner(detailScroll, detailAction);
        });
    }

    void focusDirectComposer() {
        showEmbeddedKeyboard();
    }

    /** Select a stable inline-detail target after its previous view was rebuilt. */
    void focusDetailEntry(String preferredTag) {
        View target = detailFocusViewsByTag.get(preferredTag);
        if (target == null && !detailFocusables.isEmpty()) target = detailFocusables.get(0);
        if (target != null) {
            View entry = target;
            entry.post(() -> focusTarget(entry, detailScroll));
        }
    }

    void focusActiveTab() {
        if (model == null || tabs.isEmpty()) return;
        focusTarget(tabs.get(model.state.tab.ordinal()), null);
    }

    /**
     * The modal Dialog owns raw TV key dispatch, so this reducer deliberately does not depend
     * on Android's geometry-based FocusFinder.  Each transition has one stable semantic target.
     */
    boolean handleNavigationKey(KeyEvent event) {
        if (event == null) return false;
        int key = event.getKeyCode();
        if (model != null && model.state.detail == DiscordCommunityState.Detail.DIRECT_MESSAGE
                && directKeyboardVisible && directKeyboard != null
                && directKeyboard.handleNavigationKey(event)) return true;
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return isCommunityNavigationKey(key);
        }
        if (!isCommunityNavigationKey(key) || model == null) return false;
        if (model.state.detail == DiscordCommunityState.Detail.DIRECT_MESSAGE) {
            return navigateDirectMessage(event);
        }
        View current = focusedCommunityView();
        if (current == null) {
            focusActiveTab();
            return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_L1 || key == KeyEvent.KEYCODE_BUTTON_R1) {
            int index = tabIndex(current);
            if (index < 0) index = model.state.tab.ordinal();
            int target = DiscordCommunityState.adjacentTabIndex(index, key, tabs.size());
            if (target != index) activateTab(target, true);
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_BUTTON_A
                || key == KeyEvent.KEYCODE_ENTER) {
            current.performClick();
            return true;
        }
        int tabIndex = tabIndex(current);
        if (tabIndex >= 0) return navigateFromTab(tabIndex, key);
        if (current == account) return navigateFromAccount(key);
        int[] feed = feedIndex(current);
        if (feed != null) return navigateFeed(feed[0], feed[1], key);
        int detailIndex = detailFocusables.indexOf(current);
        if (detailIndex >= 0) return navigateDetail(detailIndex, key);
        focusActiveTab();
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (handleNavigationMotion(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    /**
     * ADB `input keyevent` only covers the KeyEvent path.  Physical DualSense D-pad input on
     * Android TV is a joystick HAT MotionEvent, so route it through the same reducer here.
     */
    private boolean handleNavigationMotion(MotionEvent event) {
        if (event == null || event.getAction() != MotionEvent.ACTION_MOVE
                || !isCommunityGamepadSource(event.getSource())) return false;
        boolean triggerHandled = handleDirectKeyboardTriggerMotion(event);
        boolean historyHandled = handleDirectHistoryScrollMotion(event);
        InputDevice device = event.getDevice();
        boolean hasHat = device != null && (device.getMotionRange(MotionEvent.AXIS_HAT_X) != null
                || device.getMotionRange(MotionEvent.AXIS_HAT_Y) != null);
        float horizontal = !shouldUseFallbackAxes(hasHat) ? event.getAxisValue(MotionEvent.AXIS_HAT_X)
                : event.getAxisValue(MotionEvent.AXIS_X);
        float vertical = !shouldUseFallbackAxes(hasHat) ? event.getAxisValue(MotionEvent.AXIS_HAT_Y)
                : event.getAxisValue(MotionEvent.AXIS_Y);
        int direction = motionDirection(horizontal, vertical, hasHat ? .45f : .85f,
                activeMotionDirection);
        if (shouldRouteMotionHatToKeyboard(model == null ? null : model.state.detail,
                directKeyboardVisible, directKeyboard != null)) {
            activeMotionDirection = KeyEvent.KEYCODE_UNKNOWN;
            return directKeyboard.updateDirectionalHat(direction) || triggerHandled || historyHandled;
        }
        if (triggerHandled || historyHandled) return true;
        if (direction == KeyEvent.KEYCODE_UNKNOWN) {
            boolean consumed = activeMotionDirection != KeyEvent.KEYCODE_UNKNOWN;
            activeMotionDirection = KeyEvent.KEYCODE_UNKNOWN;
            return consumed;
        }
        if (!shouldDispatchMotionEdge(activeMotionDirection, direction)) return true;
        activeMotionDirection = direction;
        return handleNavigationKey(new KeyEvent(KeyEvent.ACTION_DOWN, direction));
    }

    private boolean handleDirectKeyboardTriggerMotion(MotionEvent event) {
        if (model == null || model.state.detail != DiscordCommunityState.Detail.DIRECT_MESSAGE
                || !directKeyboardVisible || directKeyboard == null) return false;
        InputDevice device = event.getDevice();
        if (device == null) return false;
        boolean hasLeft = hasTriggerRange(device, MotionEvent.AXIS_LTRIGGER)
                || hasTriggerRange(device, MotionEvent.AXIS_BRAKE);
        boolean hasRight = hasTriggerRange(device, MotionEvent.AXIS_RTRIGGER)
                || hasTriggerRange(device, MotionEvent.AXIS_GAS);
        if (!hasLeft && !hasRight) return false;
        boolean leftActive = hasLeft && Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE)) >= .5f;
        boolean rightActive = hasRight && Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_GAS)) >= .5f;
        boolean consumed = false;
        if (!leftActive) leftTriggerLatched = false;
        else if (shouldDispatchTriggerEdge(leftTriggerLatched, leftActive)) {
            leftTriggerLatched = true;
            consumed = directKeyboard.handleNavigationKey(new KeyEvent(KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_BUTTON_L2));
        }
        if (!rightActive) rightTriggerLatched = false;
        else if (shouldDispatchTriggerEdge(rightTriggerLatched, rightActive)) {
            rightTriggerLatched = true;
            consumed = directKeyboard.handleNavigationKey(new KeyEvent(KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_BUTTON_R2)) || consumed;
        }
        return consumed || leftActive || rightActive;
    }

    private boolean handleDirectHistoryScrollMotion(MotionEvent event) {
        if (model == null || model.state.detail != DiscordCommunityState.Detail.DIRECT_MESSAGE) return false;
        InputDevice device = event.getDevice();
        int verticalAxis = device == null ? -1 : rightStickVerticalAxis(
                hasTriggerRange(device, MotionEvent.AXIS_RX), hasTriggerRange(device, MotionEvent.AXIS_RY),
                hasTriggerRange(device, MotionEvent.AXIS_Z), hasTriggerRange(device, MotionEvent.AXIS_RZ));
        if (verticalAxis < 0) return false;
        float vertical = event.getAxisValue(verticalAxis);
        if (Math.abs(vertical) < HISTORY_SCROLL_DEAD_ZONE) return false;
        long eventTime = event.getEventTime();
        if (!shouldDispatchHistoryScroll(lastHistoryScrollEventTime, eventTime)) return true;
        int direction = vertical > 0 ? 1 : -1;
        if (!directHistoryScroll.canScrollVertically(direction)) return true;
        lastHistoryScrollEventTime = eventTime;
        directHistoryScroll.smoothScrollBy(0, direction * dp(96));
        return true;
    }

    static boolean shouldDispatchHistoryScroll(long lastEventTime, long eventTime) {
        return lastEventTime == Long.MIN_VALUE || eventTime - lastEventTime >= HISTORY_SCROLL_REPEAT_MS;
    }

    /** Matches ControllerHandler: modern controllers use RX/RY; others commonly use Z/RZ. */
    static int rightStickVerticalAxis(boolean hasRx, boolean hasRy, boolean hasZ, boolean hasRz) {
        if (hasRx && hasRy) return MotionEvent.AXIS_RY;
        return hasZ && hasRz ? MotionEvent.AXIS_RZ : -1;
    }

    private static boolean hasTriggerRange(InputDevice device, int axis) {
        return device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null
                || device.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD) != null;
    }

    static boolean shouldDispatchTriggerEdge(boolean latched, boolean active) {
        return active && !latched;
    }

    static boolean isCommunityGamepadSource(int source) {
        return (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
    }

    static boolean shouldUseFallbackAxes(boolean hasHatRange) { return !hasHatRange; }

    static boolean shouldDispatchMotionEdge(int latchedDirection, int nextDirection) {
        return nextDirection != KeyEvent.KEYCODE_UNKNOWN && nextDirection != latchedDirection;
    }

    static boolean shouldRouteMotionHatToKeyboard(DiscordCommunityState.Detail detail,
                                                   boolean keyboardVisible, boolean keyboardPresent) {
        return detail == DiscordCommunityState.Detail.DIRECT_MESSAGE
                && keyboardVisible && keyboardPresent;
    }

    /** Equal diagonals prefer vertical, matching TV menus and avoiding left/right oscillation. */
    static int motionDirection(float horizontal, float vertical, float threshold, int previousDirection) {
        float absX = Math.abs(horizontal);
        float absY = Math.abs(vertical);
        if (Math.max(absX, absY) < threshold) return KeyEvent.KEYCODE_UNKNOWN;
        if (absX > absY) return horizontal < 0f ? KeyEvent.KEYCODE_DPAD_LEFT
                : KeyEvent.KEYCODE_DPAD_RIGHT;
        if (absY > absX) return vertical < 0f ? KeyEvent.KEYCODE_DPAD_UP
                : KeyEvent.KEYCODE_DPAD_DOWN;
        // Tie-break vertical deterministically. previousDirection is intentionally not used for
        // selection: changing diagonal sign must not resurrect a stale horizontal move.
        return vertical < 0f ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
    }

    void restoreSelectedFocus() { restoreFocus(model == null ? "" : model.state.selectedId); }

    private void buildTabs() {
        for (DiscordCommunityState.Tab tab : DiscordCommunityState.Tab.values()) {
            LinearLayout holder = new LinearLayout(getContext());
            holder.setOrientation(VERTICAL);
            TextView view = text(tabLabel(tab), 14, MUTED);
            view.setId(View.generateViewId());
            view.setTag("discord.community.tab." + tab.name().toLowerCase(java.util.Locale.US));
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
            view.setClickable(true);
            view.setPadding(0, dp(10), 0, dp(7));
            View underline = new View(getContext());
            view.setOnFocusChangeListener((ignored, focused) -> {
                styleTab(view, underline, tab, focused);
                if (focused && (model == null || model.state.tab != tab)) callback.onTab(tab);
            });
            view.setOnClickListener(ignored -> callback.onTab(tab));
            final int index = tabs.size();
            view.setOnKeyListener((ignored, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return focusFirstDestination();
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && index == tabs.size() - 1) {
                    account.requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                        || keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_BUTTON_R1) {
                    int target = DiscordCommunityState.adjacentTabIndex(index, keyCode, tabs.size());
                    if (target != index) activateTab(target, true);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) { view.performClick(); return true; }
                return false;
            });
            tabs.add(view);
            holder.addView(view);
            // MATCH_PARENT below a WRAP_CONTENT holder creates a measure cycle on Android TV.
            // Keep the cyan marker to the actual label width; right padding remains the tab gap.
            int underlineWidth = Math.max(1, (int) Math.ceil(view.getPaint()
                    .measureText(view.getText().toString())));
            holder.addView(underline, new LinearLayout.LayoutParams(underlineWidth, dp(2)));
            // A horizontal LinearLayout defaults children without params to MATCH_PARENT width.
            // Tabs must retain their label footprint; otherwise the first tab pushes the rest off TV.
            LinearLayout.LayoutParams holderParams = new LinearLayout.LayoutParams(
                    WRAP_CONTENT, WRAP_CONTENT);
            holderParams.rightMargin = dp(26);
            tabBar.addView(holder, holderParams);
        }
    }

    private void bindTabs() {
        for (int index = 0; index < tabs.size(); index++) {
            TextView tab = tabs.get(index);
            View underline = ((ViewGroup) tab.getParent()).getChildAt(1);
            styleTab(tab, underline, DiscordCommunityState.Tab.values()[index], tab.hasFocus());
        }
    }

    private void bindAccount(DiscordSocialClient.Snapshot snapshot) {
        String name = snapshot.authorizationRequired ? getContext().getString(R.string.discord_social_connect_action)
                : snapshot.displayName.isEmpty() ? "Discord" : snapshot.displayName;
        accountName.setText(name);
        accountAvatar.removeAllViews();
        FrameLayout replacement = DiscordCommunityPresentation.avatar(getContext(), name, snapshot.avatarUrl, 34);
        while (replacement.getChildCount() > 0) {
            View child = replacement.getChildAt(0);
            replacement.removeView(child);
            accountAvatar.addView(child);
        }
        // Host options are meaningful even when the optional Social SDK is unavailable.  The
        // header always enters the inline options detail; authorization stays an explicit
        // action inside that detail instead of making the header a dead D-pad target.
        account.setId(account.getId() == View.NO_ID ? View.generateViewId() : account.getId());
        account.setTag("discord.community.options");
        account.setFocusable(true);
        account.setFocusableInTouchMode(true);
        account.setClickable(true);
        account.setContentDescription(getContext().getString(R.string.discord_community_options));
        accountName.setText(name + " · " + getContext().getString(R.string.discord_community_options));
        account.setOnClickListener(ignored -> callback.onOptions());
        account.setOnFocusChangeListener((ignored, focused) -> styleAccount(focused));
        styleAccount(account.hasFocus());
    }

    private void bindFeed() {
        boolean tabChanged = shouldResetRecentScroll(boundFeedTab, model.state.tab);
        boundFeedTab = model.state.tab;
        activeContainer.removeAllViews();
        recentList.removeAllViews();
        feedDestinationViews.clear();
        feedFocusOwners.clear();
        feedFocusRows.clear();
        firstDestination = null;
        if (!model.active.isEmpty()) {
            activeContainer.addView(section(getContext().getString(R.string.discord_community_active)));
            LinearLayout grid = new LinearLayout(getContext());
            grid.setOrientation(VERTICAL);
            for (int index = 0; index < model.active.size(); index += 2) {
                LinearLayout row = new LinearLayout(getContext());
                DiscordCommunityPresentation.Destination left = model.active.get(index);
                View leftView = activity(left);
                row.addView(leftView, new LinearLayout.LayoutParams(0, dp(ACTIVITY_HEIGHT_DP), 1f));
                List<View> focusRow = new ArrayList<>();
                focusRow.add(leftView);
                if (index + 1 < model.active.size()) {
                    View right = activity(model.active.get(index + 1));
                    LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                            0, dp(ACTIVITY_HEIGHT_DP), 1f);
                    rightParams.leftMargin = dp(12);
                    row.addView(right, rightParams);
                    focusRow.add(right);
                } else row.addView(new View(getContext()), new LinearLayout.LayoutParams(
                        0, dp(ACTIVITY_HEIGHT_DP), 1f));
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
                if (index > 0) rowParams.topMargin = dp(12);
                grid.addView(row, rowParams);
                feedFocusRows.add(focusRow);
            }
            activeContainer.addView(grid);
        }
        if (!model.recent.isEmpty()) {
            recentLabel.setVisibility(VISIBLE);
            for (DiscordCommunityPresentation.Destination item : model.recent) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH_PARENT, dp(DESTINATION_HEIGHT_DP));
                params.bottomMargin = dp(4);
                View row = destination(item);
                recentList.addView(row, params);
                List<View> focusRow = new ArrayList<>();
                focusRow.add(row);
                feedFocusRows.add(focusRow);
            }
        } else if (model.active.isEmpty()) {
            recentLabel.setVisibility(GONE);
            String empty = model.homeLoading ? getContext().getString(R.string.discord_community_loading)
                    : model.homeUnavailable ? getContext().getString(R.string.discord_community_host_unavailable)
                    : model.snapshot.connected ? getContext().getString(R.string.discord_community_no_active)
                    : model.error;
            recentList.addView(text(empty, 14, MUTED));
        } else {
            recentLabel.setVisibility(GONE);
        }
        if (tabChanged) recentScroll.scrollTo(0, 0);
    }

    private View activity(DiscordCommunityPresentation.Destination item) {
        FrameLayout card = new FrameLayout(getContext());
        card.setMinimumHeight(dp(ACTIVITY_HEIGHT_DP));
        LinearLayout copy = new LinearLayout(getContext());
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setPadding(dp(9), dp(7), dp(9), dp(7));
        copy.addView(DiscordCommunityPresentation.avatar(getContext(), item.title, item.avatarUrl, 32),
                new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout words = new LinearLayout(getContext());
        words.setOrientation(VERTICAL);
        TextView title = text(item.title, 15, WHITE);
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(title);
        TextView meta = text(subtitle(item), 11, MUTED);
        meta.setSingleLine(true); meta.setEllipsize(TextUtils.TruncateAt.END); words.addView(meta);
        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        wordsParams.leftMargin = dp(8);
        // The compact context label lives on top of the card; reserve its footprint so title
        // and activity never run beneath it on the 56dp TV card.
        wordsParams.rightMargin = dp(46);
        copy.addView(words, wordsParams);
        card.addView(copy, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        TextView pill = pill(actionLabel(item), item.kind == DiscordCommunityPresentation.Kind.CHANNEL
                ? ActionTone.POSITIVE_JOIN : ActionTone.NEUTRAL);
        pill.setFocusable(false); pill.setClickable(false);
        FrameLayout.LayoutParams pillParams = new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT,
                Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        pillParams.rightMargin = dp(8); card.addView(pill, pillParams);
        bindDestination(card, item, true);
        return card;
    }

    private View destination(DiscordCommunityPresentation.Destination item) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(9), dp(5), dp(9), dp(5));
        row.addView(DiscordCommunityPresentation.avatar(getContext(), item.title, item.avatarUrl, 30),
                new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout copy = new LinearLayout(getContext()); copy.setOrientation(VERTICAL);
        TextView title = text(item.title, 14, WHITE); title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(title);
        TextView meta = text(subtitle(item), 11, MUTED); meta.setSingleLine(true); meta.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(meta);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        copyParams.leftMargin = dp(9); row.addView(copy, copyParams);
        TextView chevron = text("›", 20, MUTED); row.addView(chevron);
        bindDestination(row, item, false);
        return row;
    }

    private void bindDestination(View view, DiscordCommunityPresentation.Destination item, boolean card) {
        view.setId(View.generateViewId());
        view.setTag(item.id);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setClickable(true);
        view.setContentDescription(item.title + ", " + subtitle(item));
        view.setOnFocusChangeListener((ignored, focused) -> {
            styleDestination(view, card, focused, isSelected(item));
            if (focused) {
                ScrollView owner = feedOwner(view);
                if (owner != null) revealInOwner(owner, view);
                callback.onDestinationFocused(item);
                updateDetail(item);
                wireCommunityFocus();
            }
        });
        view.setOnClickListener(ignored -> callback.onDestinationOpened(item));
        feedDestinationViews.put(item.id, view);
        feedFocusOwners.put(view, focusOwnerIsRecent(card) ? recentScroll : null);
        if (firstDestination == null) firstDestination = view;
        styleDestination(view, card, false, isSelected(item));
    }

    private void updateDetail(DiscordCommunityPresentation.Destination selected) {
        detail.removeAllViews();
        detailDestinationViews.clear();
        detailFocusViewsByTag.clear();
        detailFocusables.clear();
        detailAction = null;
        detailScroll.scrollTo(0, 0);
        if (model != null && model.state.detail == DiscordCommunityState.Detail.OPTIONS) {
            addOptions();
            return;
        }
        if (model != null && model.state.detail == DiscordCommunityState.Detail.AUDIO) {
            addAudio();
            return;
        }
        if (model != null && model.state.detail == DiscordCommunityState.Detail.SOCIAL) {
            addSocial();
            return;
        }
        detail.addView(section(getContext().getString(R.string.discord_community_selected)));
        if (model == null || selected == null) return;
        detail.addView(DiscordCommunityPresentation.avatar(getContext(), selected.title, selected.avatarUrl, 40),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = text(selected.title, 18, WHITE); title.setPadding(0, dp(7), 0, 0); detail.addView(title);
        detail.addView(text(subtitle(selected), 12, MUTED));
        if (selected.participantCount > 0) {
            TextView people = text(getContext().getString(R.string.discord_community_people,
                    selected.participantCount), 12, MUTED);
            people.setPadding(0, dp(10), 0, 0); detail.addView(people);
        }
        if (selected.source instanceof HostGatewayClient.DiscordChannel
                && isConnectedChannel(selected)) {
            // Voice state is authoritative even when the surrounding route was restored as FEED.
            addActiveVoiceDetail(selected);
        } else if (model.state.detail == DiscordCommunityState.Detail.SERVER_CHANNELS) {
            addGuildChannels(selected);
        } else if (model.state.detail == DiscordCommunityState.Detail.CHANNEL
                && selected.source instanceof HostGatewayClient.DiscordChannel) {
            addJoinAction((HostGatewayClient.DiscordChannel) selected.source);
        } else if (model.state.detail == DiscordCommunityState.Detail.FRIEND) {
            int label = model.directMessagesCapable ? R.string.discord_dm_send_message
                    : model.directMessagesScopeAvailable ? R.string.discord_dm_chat_open_elsewhere
                    : R.string.discord_dm_upgrade;
            TextView message = pill(getContext().getString(label), ActionTone.POSITIVE_JOIN);
            message.setTag("discord.community.friend.message");
            message.setOnClickListener(ignored -> {
                if (model.directMessagesCapable) callback.onOpenDirectMessage(selected);
                else if (!model.directMessagesScopeAvailable) callback.onUpgradeDirectMessages();
            });
            message.setEnabled(model.directMessagesCapable || !model.directMessagesScopeAvailable);
            detailAction = message;
            addAction(message, ActionTone.POSITIVE_JOIN, false);
        } else if (selected.kind == DiscordCommunityPresentation.Kind.SERVER) {
            detailAction = pill(getContext().getString(R.string.discord_community_open));
            detailAction.setOnClickListener(ignored -> callback.onDestinationOpened(selected));
            addAction(detailAction);
        } else if (selected.kind == DiscordCommunityPresentation.Kind.CHANNEL) {
            detailAction = pill(getContext().getString(R.string.discord_community_join), ActionTone.POSITIVE_JOIN);
            detailAction.setOnClickListener(ignored -> callback.onDestinationOpened(selected));
            addAction(detailAction, ActionTone.POSITIVE_JOIN, false);
        }
    }

    private void bindDirectMessage() {
        final boolean wasNearBottom = directNearBottom;
        final long recipientId = model.state.directMessageRecipientId;
        if (boundDirectRecipientId != recipientId && directKeyboard != null) {
            directKeyboard.cancelKeyboardHold();
        }
        final boolean preserveComposer = shouldPreserveDirectComposer(boundDirectRecipientId, recipientId);
        final String retainedDraft = directComposerDraftForRebind(model.directDraft);
        final int retainedSelection = preserveComposer && directComposer != null
                ? directComposer.getSelectionStart() : retainedDraft.length();
        long latestMessageId = lastMessageId(model.directMessages);
        boolean latestIncoming = isIncomingLatest(model.directMessages);
        boolean latestChanged = preserveComposer && latestMessageId != 0
                && latestMessageId != directLastMessageId;
        if (shouldShowNewMessagePill(wasNearBottom, latestChanged, latestIncoming)) {
            pendingNewMessagePill = true;
        }
        final boolean scrollHistoryToBottom = shouldScrollDirectHistoryToBottom(wasNearBottom,
                latestChanged, latestIncoming);
        boundDirectRecipientId = recipientId;
        directLastMessageId = latestMessageId;
        directWorkspace.removeAllViews();
        DiscordSocialClient.Friend friend = model.directFriend;
        String name = friend == null ? "Discord" : friend.displayName;
        String avatar = friend == null ? "" : friend.avatarUrl;

        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(4), dp(4), dp(10));
        header.addView(DiscordCommunityPresentation.avatar(getContext(), name, avatar, 38),
                new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView title = text(name, 19, WHITE);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(10); header.addView(title, titleParams);
        directWorkspace.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        if (!model.directMessagesCapable) {
            if (directKeyboard != null) directKeyboard.cancelKeyboardHold();
            directComposer = null;
            directSend = null;
            directKeyboard = null;
            directKeyboardVisible = false;
            TextView unavailable = text(getContext().getString(model.directMessagesScopeAvailable
                    ? R.string.discord_dm_chat_open_elsewhere : R.string.discord_dm_upgrade), 13, MUTED);
            unavailable.setPadding(dp(8), dp(12), dp(8), dp(12));
            directWorkspace.addView(unavailable, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            return;
        }

        directHistory.removeAllViews();
        pendingNewMessageView = null;
        directMediaRows.clear();
        for (DiscordDirectMessageState.Message message : model.directMessages) {
            if (message.disclosure) {
                TextView disclosure = text(getContext().getString(R.string.discord_dm_disclosure), 12, MUTED);
                disclosure.setGravity(Gravity.CENTER); disclosure.setPadding(dp(12), dp(8), dp(12), dp(8));
                directHistory.addView(disclosure, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
                continue;
            }
            boolean self = safeUserId(model.snapshot.userId) == message.authorId;
            if (!message.content.isEmpty()) addDirectMessageText(message, self);
            if (shouldRenderAdditionalNotice(message.additionalContentType, message.additionalContentCount)) {
                addAdditionalContent(message, self);
            } else if (shouldRenderGenericUnsupportedContent(message.content, message.additionalContentType,
                    message.additionalContentCount, message.disclosure)) {
                addGenericUnsupportedContent(message, self);
            }
        }
        if (pendingNewMessagePill) {
            TextView newMessage = pill(getContext().getString(R.string.discord_dm_new_message));
            newMessage.setOnClickListener(ignored -> {
                clearPendingNewMessagePill();
                directHistoryScroll.post(() -> directHistoryScroll.fullScroll(View.FOCUS_DOWN));
            });
            LinearLayout.LayoutParams newParams = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            newParams.gravity = Gravity.CENTER_HORIZONTAL; newParams.topMargin = dp(6);
            directHistory.addView(newMessage, newParams);
            pendingNewMessageView = newMessage;
        }
        directWorkspace.addView(directHistoryScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

        if (!model.directSendState.errorType.isEmpty()) {
            int retrySeconds = (int) Math.ceil(model.directSendState.retryAfterSeconds);
            TextView sendError = text(model.directSendState.retryable && retrySeconds > 0
                    ? getContext().getString(R.string.discord_dm_retry_after, retrySeconds)
                    : getContext().getString(R.string.discord_dm_send_failed), 12, MUTED);
            sendError.setPadding(0, dp(4), 0, dp(4));
            directWorkspace.addView(sendError, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        }
        LinearLayout composerRow = new LinearLayout(getContext());
        composerRow.setGravity(Gravity.BOTTOM);
        directComposer = new EditText(getContext());
        directComposer.setId(View.generateViewId());
        directComposer.setTextColor(WHITE);
        directComposer.setTextSize(15);
        directComposer.setHintTextColor(MUTED);
        directComposer.setHint(R.string.discord_dm_placeholder);
        directComposer.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        directComposer.setSingleLine(false);
        directComposer.setMaxLines(5);
        directComposer.setShowSoftInputOnFocus(false);
        directComposer.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
        directComposer.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        directComposer.setText(retainedDraft);
        directComposer.setSelection(Math.max(0, Math.min(retainedSelection, directComposer.length())));
        directComposer.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                callback.onDirectMessageDraftChanged(model.state.directMessageRecipientId, value.toString());
                if (directSend != null && !model.directSendState.inFlight) {
                    directSend.setEnabled(!value.toString().trim().isEmpty());
                    styleDirectSend(directSend.hasFocus());
                }
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        directComposer.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendDirectMessage();
                return true;
            }
            return false;
        });
        composerRow.addView(directComposer, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        directSend = pill(getContext().getString(model.directSendState.inFlight
                ? R.string.discord_dm_sending : R.string.discord_dm_send));
        directSend.setId(View.generateViewId());
        directSend.setEnabled(!model.directSendState.inFlight && !directComposer.getText().toString().trim().isEmpty());
        directSend.setOnClickListener(ignored -> sendDirectMessage());
        directSend.setFocusable(false);
        directSend.setFocusableInTouchMode(false);
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        sendParams.leftMargin = dp(8); composerRow.addView(directSend, sendParams);
        directWorkspace.addView(composerRow, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        styleDirectSend(directSend.hasFocus());
        if (!preserveComposer || directKeyboard == null || directKeyboardRecipientId != recipientId) {
            directKeyboard = new EmbeddedTvKeyboardView(getContext(), new EmbeddedTvKeyboardView.Callback() {
                @Override public void onText(String value) { replaceComposerText(value); }
                @Override public void onBackspace() { deleteComposerCodePoint(); }
                @Override public void onMoveCursor(int direction) { moveComposerCursor(direction); }
                @Override public void onMicrophone() {
                    callback.onDirectMessageDictationRequested(model.state.directMessageRecipientId,
                            model.state.directMessageGeneration);
                }
                @Override public void onSend() { sendDirectMessage(); }
                @Override public void onOpenVisibleAdditionalContent() { openVisibleAdditionalContent(); }
            }, getContext().getString(R.string.discord_dm_keyboard_microphone),
                    getContext().getString(R.string.discord_dm_keyboard_space),
                    getContext().getString(R.string.discord_dm_send),
                    getContext().getString(R.string.discord_dm_keyboard_shift_legend),
                    getContext().getString(R.string.discord_dm_keyboard_backspace_legend),
                    getContext().getString(R.string.discord_dm_keyboard_cursor_legend),
                    getContext().getString(R.string.discord_dm_keyboard_send_legend),
                    getContext().getString(R.string.discord_dm_keyboard_history_scroll_legend),
                    getContext().getString(R.string.discord_dm_keyboard_media_open_legend));
            directKeyboardRecipientId = recipientId;
            directKeyboardVisible = true;
        }
        directKeyboard.setVisibility(directKeyboardVisible ? VISIBLE : GONE);
        directWorkspace.addView(directKeyboard, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        directHistoryScroll.post(() -> {
            if (scrollHistoryToBottom) directHistoryScroll.fullScroll(View.FOCUS_DOWN);
        });
        directComposer.post(directComposer::requestFocus);
    }

    private void sendDirectMessage() {
        if (directComposer == null || directComposer.getText().toString().trim().isEmpty()
                || model.directSendState.inFlight) return;
        callback.onSendDirectMessage(model.state.directMessageRecipientId, directComposer.getText().toString());
    }

    private void addDirectMessageText(DiscordDirectMessageState.Message message, boolean self) {
        TextView bubble = text(message.content, 14, WHITE);
        bubble.setPadding(dp(11), dp(8), dp(11), dp(8));
        bubble.setTextIsSelectable(false);
        GradientDrawable background = new GradientDrawable();
        background.setColor(self ? 0xFF1F6388 : CARD);
        background.setCornerRadius(dp(14));
        bubble.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        params.gravity = self ? Gravity.END : Gravity.START;
        params.topMargin = dp(4);
        directHistory.addView(bubble, params);
    }

    private void addAdditionalContent(DiscordDirectMessageState.Message message, boolean self) {
        addDirectContentNotice(message, self, additionalContentLabel(message));
    }

    private void addGenericUnsupportedContent(DiscordDirectMessageState.Message message, boolean self) {
        addDirectContentNotice(message, self, getContext().getString(R.string.discord_dm_unsupported_content));
    }

    private void addDirectContentNotice(DiscordDirectMessageState.Message message, boolean self, String label) {
        TextView content = text(label, 11, CYAN);
        content.setPadding(dp(11), dp(7), dp(11), dp(7));
        content.setClickable(true);
        content.setFocusable(false);
        content.setOnClickListener(ignored -> callback.onOpenDirectMessageInDiscord(message.id));
        GradientDrawable background = new GradientDrawable();
        background.setColor(ROW);
        background.setCornerRadius(dp(12));
        content.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        params.gravity = self ? Gravity.END : Gravity.START;
        params.topMargin = message.content.isEmpty() ? dp(4) : dp(2);
        directHistory.addView(content, params);
        directMediaRows.add(new DirectMediaRow(content, message.id));
    }

    static boolean shouldRenderAdditionalNotice(String additionalContentType, int additionalContentCount) {
        return !isEmpty(additionalContentType) || additionalContentCount > 0;
    }

    static boolean shouldRenderGenericUnsupportedContent(String content, String additionalContentType,
                                                         int additionalContentCount, boolean disclosure) {
        return !disclosure && isEmpty(content)
                && !shouldRenderAdditionalNotice(additionalContentType, additionalContentCount);
    }

    private static boolean isEmpty(String value) { return value == null || value.isEmpty(); }

    private String additionalContentLabel(DiscordDirectMessageState.Message message) {
        int count = Math.max(1, message.additionalContentCount);
        int resource;
        switch (message.additionalContentType) {
            case "Attachment": resource = R.string.discord_dm_media_attachment; break;
            case "Poll": resource = R.string.discord_dm_media_poll; break;
            case "VoiceMessage": resource = R.string.discord_dm_media_voice_message; break;
            case "Thread": resource = R.string.discord_dm_media_thread; break;
            case "Embed": resource = R.string.discord_dm_media_embed; break;
            case "Sticker": resource = R.string.discord_dm_media_sticker; break;
            default: resource = R.string.discord_dm_media_other; break;
        }
        String label = getContext().getString(resource, count);
        return message.additionalContentTitle.isEmpty() ? label
                : getContext().getString(R.string.discord_dm_media_titled, label,
                message.additionalContentTitle);
    }

    private void clearPendingNewMessagePill() {
        pendingNewMessagePill = false;
        if (pendingNewMessageView != null) {
            directHistory.removeView(pendingNewMessageView);
            pendingNewMessageView = null;
        }
    }

    private void openVisibleAdditionalContent() {
        if (directMediaRows.isEmpty()) return;
        int[] tops = new int[directMediaRows.size()];
        int[] bottoms = new int[directMediaRows.size()];
        for (int index = 0; index < directMediaRows.size(); index++) {
            Rect bounds = new Rect();
            View row = directMediaRows.get(index).view;
            row.getDrawingRect(bounds);
            directHistoryScroll.offsetDescendantRectToMyCoords(row, bounds);
            tops[index] = bounds.top;
            bottoms[index] = bounds.bottom;
        }
        int target = nearestVisibleMediaRow(directHistoryScroll.getScrollY(),
                directHistoryScroll.getScrollY() + directHistoryScroll.getHeight(), tops, bottoms);
        if (target >= 0) callback.onOpenDirectMessageInDiscord(directMediaRows.get(target).messageId);
    }

    static int nearestVisibleMediaRow(int viewportTop, int viewportBottom, int[] tops, int[] bottoms) {
        if (tops == null || bottoms == null || tops.length != bottoms.length) return -1;
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        int center = viewportTop + (viewportBottom - viewportTop) / 2;
        for (int index = 0; index < tops.length; index++) {
            if (bottoms[index] <= viewportTop || tops[index] >= viewportBottom) continue;
            int rowCenter = tops[index] + (bottoms[index] - tops[index]) / 2;
            int distance = Math.abs(rowCenter - center);
            if (distance < bestDistance) {
                best = index;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static long safeUserId(String value) {
        try { return Long.parseLong(value); }
        catch (RuntimeException ignored) { return 0; }
    }

    private static boolean isNearBottom(ScrollView scroll) {
        View child = scroll.getChildCount() == 0 ? null : scroll.getChildAt(0);
        return child == null || scroll.getScrollY() + scroll.getHeight() >= child.getHeight() - 24;
    }

    static boolean shouldPreserveDirectComposer(long previousRecipientId, long nextRecipientId) {
        return previousRecipientId > 0 && previousRecipientId == nextRecipientId;
    }

    static String directComposerDraftForRebind(String modelDraft) {
        return modelDraft == null ? "" : modelDraft;
    }

    static boolean shouldShowNewMessagePill(boolean wasNearBottom, boolean latestChanged,
                                             boolean latestIncoming) {
        return !wasNearBottom && latestChanged && latestIncoming;
    }

    static boolean shouldScrollDirectHistoryToBottom(boolean wasNearBottom, boolean latestChanged,
                                                      boolean latestIncoming) {
        return wasNearBottom || (latestChanged && !latestIncoming);
    }

    private long lastMessageId(List<DiscordDirectMessageState.Message> messages) {
        return messages.isEmpty() ? 0 : messages.get(messages.size() - 1).id;
    }

    private boolean isIncomingLatest(List<DiscordDirectMessageState.Message> messages) {
        return !messages.isEmpty() && messages.get(messages.size() - 1).authorId != safeUserId(model.snapshot.userId);
    }

    static boolean isDirectMessageBackKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B;
    }

    boolean handleDirectMessageBack(KeyEvent event) {
        if (event == null || model == null || model.state.detail != DiscordCommunityState.Detail.DIRECT_MESSAGE) {
            return false;
        }
        if (directKeyboard != null && directKeyboard.handleAccentCancel(event)) return true;
        if (!isDirectMessageBackKey(event.getKeyCode())) return false;
        int key = event.getKeyCode();
        if (directKeyboardBackKeyCode == key) {
            if (event.getAction() == KeyEvent.ACTION_UP) directKeyboardBackKeyCode = KeyEvent.KEYCODE_UNKNOWN;
            return true;
        }
        if (!directKeyboardVisible) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            directKeyboardBackKeyCode = key;
            hideEmbeddedKeyboard();
        }
        return true;
    }

    boolean collapseEmbeddedKeyboardForDictation(long recipientId, long directMessageGeneration) {
        if (!isCurrentDirectMessage(recipientId, directMessageGeneration)) return false;
        hideEmbeddedKeyboard();
        return true;
    }

    boolean insertDictationResult(long recipientId, long directMessageGeneration, String value) {
        if (!isCurrentDirectMessage(recipientId, directMessageGeneration) || TextUtils.isEmpty(value)) return false;
        replaceComposerText(value);
        return true;
    }

    boolean restoreEmbeddedKeyboardAfterDictation(long recipientId, long directMessageGeneration) {
        if (!isCurrentDirectMessage(recipientId, directMessageGeneration)) return false;
        showEmbeddedKeyboard();
        return true;
    }

    private boolean navigateDirectMessage(KeyEvent event) {
        if (directKeyboardVisible && directKeyboard != null && directKeyboard.handleNavigationKey(event)) return true;
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_DPAD_CENTER
                || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            showEmbeddedKeyboard();
        }
        if (directComposer != null) directComposer.requestFocus();
        return true;
    }

    private void showEmbeddedKeyboard() {
        if (directKeyboard != null) directKeyboard.setVisibility(VISIBLE);
        directKeyboardVisible = true;
        if (directComposer != null) directComposer.requestFocus();
    }

    private void hideEmbeddedKeyboard() {
        directKeyboardVisible = false;
        if (directKeyboard != null) {
            directKeyboard.cancelKeyboardHold();
            directKeyboard.setVisibility(GONE);
        }
        if (directComposer != null) directComposer.requestFocus();
    }

    void cancelDirectKeyboardHold() {
        if (directKeyboard != null) directKeyboard.cancelKeyboardHold();
        activeMotionDirection = KeyEvent.KEYCODE_UNKNOWN;
    }

    private void replaceComposerText(String value) {
        if (directComposer == null) return;
        Editable editable = directComposer.getText();
        EmbeddedTvKeyboardModel.Edit edit = EmbeddedTvKeyboardModel.insert(editable.toString(),
                directComposer.getSelectionStart(), directComposer.getSelectionEnd(), value);
        if (!edit.text.equals(editable.toString())) editable.replace(0, editable.length(), edit.text);
        directComposer.setSelection(edit.selection);
    }

    private void deleteComposerCodePoint() {
        if (directComposer == null) return;
        Editable editable = directComposer.getText();
        EmbeddedTvKeyboardModel.Edit edit = EmbeddedTvKeyboardModel.backspace(editable.toString(),
                directComposer.getSelectionStart(), directComposer.getSelectionEnd());
        if (!edit.text.equals(editable.toString())) editable.replace(0, editable.length(), edit.text);
        directComposer.setSelection(edit.selection);
    }

    private void moveComposerCursor(int direction) {
        if (directComposer == null) return;
        int selection = directComposer.getSelectionStart();
        directComposer.setSelection(EmbeddedTvKeyboardModel.moveCursorByCodePoints(
                directComposer.getText().toString(), selection, direction));
    }

    private boolean isCurrentDirectMessage(long recipientId, long directMessageGeneration) {
        return model != null && model.state.detail == DiscordCommunityState.Detail.DIRECT_MESSAGE
                && model.state.directMessageRecipientId == recipientId
                && model.state.directMessageGeneration == directMessageGeneration;
    }

    static boolean directSendUsesFocusedStyle(boolean focused, boolean enabled, boolean inFlight) {
        return focused && enabled && !inFlight;
    }

    private void styleDirectSend(boolean focused) {
        if (directSend == null || model == null) return;
        boolean enabled = directSend.isEnabled();
        styleAction(directSend, ActionTone.POSITIVE_JOIN,
                directSendUsesFocusedStyle(focused, enabled, model.directSendState.inFlight),
                model.directSendState.inFlight || !enabled);
    }

    private void addJoinAction(HostGatewayClient.DiscordChannel channel) {
        TextView joinAction = pill(getContext().getString(R.string.discord_join_channel, channel.name),
                ActionTone.POSITIVE_JOIN);
        joinAction.setTag("discord.community.join");
        if (model.voiceBusy) joinAction.setText(getContext().getString(R.string.discord_joining_channel));
        joinAction.setOnClickListener(ignored -> {
            if (!model.voiceBusy) callback.onJoin(channel);
        });
        detailAction = joinAction;
        addAction(joinAction, ActionTone.POSITIVE_JOIN, model.voiceBusy);
    }

    private void addActiveVoiceDetail(DiscordCommunityPresentation.Destination selected) {
        TextView status = text(getContext().getString(R.string.discord_community_connected), 14, CYAN);
        status.setPadding(0, dp(14), 0, dp(4));
        detail.addView(status);
        HostGatewayClient.DiscordVoice voice = model.voice;
        if (voice == null) return;
        HostGatewayClient.DiscordChannel channel = selected.source instanceof HostGatewayClient.DiscordChannel
                ? (HostGatewayClient.DiscordChannel) selected.source : null;
        String guild = channel != null && !channel.guildName.isEmpty() ? channel.guildName : voice.guildId;
        if (!guild.isEmpty()) detail.addView(text(guild, 12, MUTED));
        detail.addView(text(getContext().getString(R.string.discord_community_people,
                voice.participants), 12, MUTED));
        if (voice.participantList.isEmpty()) {
            detail.addView(text(model.voiceLoading ? getContext().getString(R.string.discord_community_loading)
                    : getContext().getString(R.string.discord_no_participants), 12, MUTED));
        } else {
            for (HostGatewayClient.DiscordParticipant participant : voice.participantList) {
                TextView person = text((participant.speaking ? "●  " : "") + participant.name
                        + (participant.self ? getContext().getString(R.string.discord_you_suffix) : ""),
                        13, participant.speaking ? CYAN : WHITE);
                person.setPadding(0, dp(5), 0, 0);
                detail.addView(person);
            }
        }
        if (model.voiceBusy) {
            TextView busy = text(getContext().getString(R.string.discord_community_updating_voice), 12, MUTED);
            busy.setPadding(0, dp(14), 0, dp(2)); detail.addView(busy);
        }
        addVoiceAction(model.voice.muted ? R.string.overlay_discord_unmute : R.string.overlay_discord_mute,
                DiscordPanelController.CommunityVoiceAction.MUTE);
        addVoiceAction(model.voice.deafened ? R.string.discord_enable_audio : R.string.discord_disable_audio,
                DiscordPanelController.CommunityVoiceAction.DEAFEN);
        addVoiceAction(R.string.overlay_discord_leave, DiscordPanelController.CommunityVoiceAction.LEAVE);
    }

    private void addVoiceAction(int label, DiscordPanelController.CommunityVoiceAction action) {
        ActionTone tone = action == DiscordPanelController.CommunityVoiceAction.LEAVE
                ? ActionTone.DESTRUCTIVE_LEAVE : ActionTone.NEUTRAL;
        TextView view = pill(getContext().getString(label), tone);
        view.setTag("discord.community.voice." + action.name().toLowerCase(java.util.Locale.US));
        if (model.voiceBusy) {
            view.setAlpha(.58f);
        }
        view.setOnClickListener(ignored -> {
            if (!model.voiceBusy) callback.onVoiceAction(action);
        });
        addAction(view, tone, model.voiceBusy);
    }

    private void addOptions() {
        detail.addView(section(getContext().getString(R.string.discord_community_options)));
        String account = model.snapshot.displayName.isEmpty() ? "Discord" : model.snapshot.displayName;
        detail.addView(text(account, 15, WHITE));
        boolean linked = model.snapshot.connected || !model.snapshot.userId.isEmpty();
        detail.addView(text(linked ? getContext().getString(R.string.discord_community_connected)
                : getContext().getString(R.string.discord_social_connect_action), 12, MUTED));
        if (!linked) {
            TextView connect = pill(getContext().getString(R.string.discord_social_connect_action),
                    ActionTone.POSITIVE_JOIN);
            connect.setTag("discord.community.social.connect");
            if (model.optionsLoading) connect.setAlpha(.58f);
            connect.setOnClickListener(ignored -> {
                if (!model.optionsLoading) callback.onAuthorize();
            });
            addAction(connect, ActionTone.POSITIVE_JOIN, model.optionsLoading);
        }
        TextView audio = pill(getContext().getString(R.string.discord_audio_devices_action));
        audio.setTag("discord.community.options.audio");
        audio.setOnClickListener(ignored -> callback.onOpenAudio());
        addAction(audio);
        TextView social = pill(getContext().getString(R.string.discord_community_title));
        social.setTag("discord.community.options.social");
        social.setOnClickListener(ignored -> callback.onOpenSocial());
        addAction(social);
        if (model.optionsLoading) {
            TextView busy = text(getContext().getString(R.string.discord_community_loading), 13, MUTED);
            busy.setPadding(0, dp(8), 0, dp(2));
            detail.addView(busy);
            // A refresh after a setting action must retain the existing lane: the focused
            // TextView is rebuilt under its stable tag and remains a valid D-pad target.
            if (!optionsActionsVisible(model.options != null)) return;
        }
        if (!model.optionsError.isEmpty()) {
            detail.addView(text(model.optionsError, 13, MUTED));
        }
        DiscordPanelController.CommunityOptions options = model.options;
        if (options == null) return;
        addSetting(R.string.discord_community_integration, options.integrationEnabled,
                DiscordPanelController.CommunitySetting.INTEGRATION);
        addSetting(R.string.discord_community_auto_connect, options.autoConnect,
                DiscordPanelController.CommunitySetting.AUTO_CONNECT);
        addSetting(R.string.discord_community_auto_join, options.autoJoinLast,
                DiscordPanelController.CommunitySetting.AUTO_JOIN_LAST);
        addHostAction(R.string.discord_start_on_host, DiscordPanelController.CommunityHostAction.START);
        addHostAction(R.string.discord_connect_rpc, DiscordPanelController.CommunityHostAction.RECONNECT);
        addHostAction(R.string.discord_refresh_status, DiscordPanelController.CommunityHostAction.REFRESH_STATUS);
        if (options.status != null) {
            detail.addView(text(getContext().getString(R.string.discord_community_host_status,
                    options.status.bridgeOnline ? getContext().getString(R.string.discord_state_online)
                            : getContext().getString(R.string.discord_state_offline),
                    options.status.rpcConnected ? getContext().getString(R.string.discord_state_online)
                            : getContext().getString(R.string.discord_state_offline),
                    options.status.authenticated ? getContext().getString(R.string.discord_state_on)
                            : getContext().getString(R.string.discord_state_off)), 12, MUTED));
        }
        if (linked) addUnlink();
    }

    private void addSocial() {
        detail.addView(section(getContext().getString(R.string.discord_community_title)));
        TextView notifications = pill(getContext().getString(
                R.string.discord_dm_notifications_setting,
                getContext().getString(model.dmNotificationsEnabled
                        ? R.string.discord_state_on : R.string.discord_state_off)));
        notifications.setTag("discord.community.social.dm_notifications");
        notifications.setOnClickListener(ignored -> callback.onDmNotificationsChanged(
                !model.dmNotificationsEnabled));
        addAction(notifications);
        String account = model.snapshot.displayName.isEmpty() ? "Discord" : model.snapshot.displayName;
        detail.addView(text(account, 15, WHITE));
        boolean linked = model.snapshot.connected || !model.snapshot.userId.isEmpty();
        detail.addView(text(linked ? getContext().getString(R.string.discord_community_connected)
                : getContext().getString(R.string.discord_social_connect_action), 12, MUTED));
        if (!linked) {
            TextView connect = pill(getContext().getString(R.string.discord_social_connect_action),
                    ActionTone.POSITIVE_JOIN);
            connect.setTag("discord.community.social.connect");
            connect.setOnClickListener(ignored -> callback.onAuthorize());
            addAction(connect, ActionTone.POSITIVE_JOIN, false);
        } else addUnlink();
    }

    private void addAudio() {
        detail.addView(section(getContext().getString(R.string.discord_audio_title)));
        if (model.audioLoading && model.audio == null) {
            addPassiveDetailEntry("discord.community.audio.loading",
                    getContext().getString(R.string.discord_community_loading));
            return;
        }
        if (!model.audioError.isEmpty()) detail.addView(text(model.audioError, 13, MUTED));
        HostGatewayClient.DiscordAudioState audio = model.audio;
        if (audio == null) return;
        if (audio.systemAvailable) {
            detail.addView(text(getContext().getString(R.string.discord_windows_audio,
                    audio.systemVolume, audio.systemMuted
                            ? getContext().getString(R.string.discord_muted_suffix) : ""), 13, MUTED));
            TextView down = pill(getContext().getString(R.string.discord_system_volume_change, -5));
            down.setTag("discord.community.audio.volume.down");
            down.setOnClickListener(ignored -> { if (!model.audioBusy) callback.onAudioVolume(-5); });
            addAction(down);
            TextView up = pill(getContext().getString(R.string.discord_system_volume_change, 5));
            up.setTag("discord.community.audio.volume.up");
            up.setOnClickListener(ignored -> { if (!model.audioBusy) callback.onAudioVolume(5); });
            addAction(up);
            TextView mute = pill(getContext().getString(audio.systemMuted
                    ? R.string.discord_unmute_system : R.string.discord_mute_system));
            mute.setTag("discord.community.audio.mute");
            mute.setOnClickListener(ignored -> { if (!model.audioBusy) callback.onAudioMute(); });
            addAction(mute);
        }
        addAudioDevices(getContext().getString(R.string.discord_system_devices), audio.systemDevices);
        addAudioDevices(getContext().getString(R.string.discord_discord_devices), audio.discordDevices);
        if (!audio.error.isEmpty()) detail.addView(text(audio.error, 12, MUTED));
        if (model.audioBusy) detail.addView(text(getContext().getString(R.string.discord_community_loading), 12, MUTED));
    }

    private void addAudioDevices(String title, List<HostGatewayClient.AudioDevice> devices) {
        if (devices.isEmpty()) return;
        detail.addView(section(title));
        for (HostGatewayClient.AudioDevice device : devices) {
            TextView action = pill((device.current ? "•  " : "") + device.name + "  ·  " + device.flow);
            action.setTag("discord.community.audio.device." + device.system + "." + device.flow + "." + device.id);
            action.setOnClickListener(ignored -> { if (!model.audioBusy) callback.onAudioDevice(device); });
            addAction(action);
        }
    }

    private void addUnlink() {
        TextView unlink = pill(getContext().getString(R.string.discord_social_unlink),
                ActionTone.DESTRUCTIVE_LEAVE);
        unlink.setTag("discord.community.unlink");
        if (model.optionsLoading) unlink.setAlpha(.58f);
        unlink.setOnClickListener(ignored -> {
            if (!model.optionsLoading) callback.onUnlink();
        });
        addAction(unlink, ActionTone.DESTRUCTIVE_LEAVE, model.optionsLoading);
    }

    private void addSetting(int label, boolean enabled, DiscordPanelController.CommunitySetting setting) {
        TextView view = pill(getContext().getString(label, getContext().getString(enabled
                ? R.string.discord_state_on : R.string.discord_state_off)));
        view.setTag("discord.community.setting." + setting.name().toLowerCase(java.util.Locale.US));
        if (model.optionsLoading) view.setAlpha(.58f);
        view.setOnClickListener(ignored -> {
            if (!model.optionsLoading) callback.onOption(setting, !enabled);
        });
        addAction(view);
    }

    private void addHostAction(int label, DiscordPanelController.CommunityHostAction action) {
        TextView view = pill(getContext().getString(label));
        view.setTag("discord.community.host." + action.name().toLowerCase(java.util.Locale.US));
        if (model.optionsLoading) view.setAlpha(.58f);
        view.setOnClickListener(ignored -> {
            if (!model.optionsLoading) callback.onHostAction(action);
        });
        addAction(view);
    }

    private void addGuildChannels(DiscordCommunityPresentation.Destination selected) {
        if (model.guildLoading) { detail.addView(text(getContext().getString(R.string.discord_community_loading), 13, MUTED)); return; }
        if (model.guildUnavailable) {
            detail.addView(text(getContext().getString(R.string.discord_community_host_unavailable), 13, MUTED));
            if (selected.kind == DiscordCommunityPresentation.Kind.SERVER) {
                detailAction = pill(getContext().getString(R.string.discord_social_retry));
                detailAction.setOnClickListener(ignored -> callback.onDestinationOpened(selected));
                addAction(detailAction);
            }
            return;
        }
        for (DiscordCommunityPresentation.Destination channel : model.guildChannels) {
            View row = detailChannel(channel);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH_PARENT, dp(56));
            params.topMargin = dp(4); detail.addView(row, params);
        }
    }

    private View detailChannel(DiscordCommunityPresentation.Destination item) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(6), dp(12), dp(6));
        row.addView(DiscordCommunityPresentation.avatar(getContext(), item.title, item.avatarUrl, 34),
                new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView title = text(item.title, 15, WHITE);
        title.setSingleLine(true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(10);
        row.addView(title, titleParams);
        row.setId(View.generateViewId());
        row.setTag(item.id);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        row.setContentDescription(item.title + ", " + subtitle(item));
        row.setOnFocusChangeListener((ignored, focused) -> {
            styleDestination(row, false, focused);
            if (focused) {
                revealInOwner(detailScroll, row);
                callback.onDestinationFocused(item);
            }
        });
        row.setOnClickListener(ignored -> callback.onDestinationOpened(item));
        detailDestinationViews.put(item.id, row);
        detailFocusables.add(row);
        styleDestination(row, false, false);
        return row;
    }

    private void addAction(View action) { addAction(action, ActionTone.NEUTRAL, false); }

    private void addAction(View action, ActionTone tone, boolean busy) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        params.topMargin = dp(8);
        if (action.getId() == View.NO_ID) action.setId(View.generateViewId());
        action.setFocusableInTouchMode(true);
        action.setOnFocusChangeListener((ignored, focused) -> {
            if (action instanceof TextView) styleAction((TextView) action, tone, focused, busy);
            if (focused) revealInOwner(detailScroll, action);
        });
        if (action instanceof TextView) styleAction((TextView) action, tone, action.hasFocus(), busy);
        detail.addView(action, params);
        detailFocusables.add(action);
        Object tag = action.getTag();
        if (!(tag instanceof String)) {
            tag = "discord.community.detail." + detailFocusables.size();
            action.setTag(tag);
        }
        detailFocusViewsByTag.put((String) tag, action);
        if (detailAction == null) detailAction = action;
    }

    private void addPassiveDetailEntry(String tag, String label) {
        TextView entry = text(label, 13, MUTED);
        entry.setTag(tag);
        entry.setFocusable(true);
        entry.setFocusableInTouchMode(true);
        entry.setContentDescription(label);
        entry.setOnFocusChangeListener((ignored, focused) -> {
            if (focused) revealInOwner(detailScroll, entry);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        params.topMargin = dp(8);
        detail.addView(entry, params);
        detailFocusables.add(entry);
        detailFocusViewsByTag.put(tag, entry);
        if (detailAction == null) detailAction = entry;
    }

    private DiscordCommunityPresentation.Destination find(String id) {
        if (model == null) return null;
        for (DiscordCommunityPresentation.Destination item : model.active) if (item.id.equals(id)) return item;
        for (DiscordCommunityPresentation.Destination item : model.recent) if (item.id.equals(id)) return item;
        for (DiscordCommunityPresentation.Destination item : model.guildChannels) if (item.id.equals(id)) return item;
        return !model.active.isEmpty() ? model.active.get(0) : model.recent.isEmpty() ? null : model.recent.get(0);
    }

    private boolean isSelected(DiscordCommunityPresentation.Destination item) {
        return model != null && item != null && item.id.equals(model.state.selectedId);
    }

    private void styleDestination(View view, boolean card, boolean focused) {
        styleDestination(view, card, focused, false);
    }

    private void styleDestination(View view, boolean card, boolean focused, boolean selected) {
        GradientDrawable surface = new GradientDrawable();
        surface.setCornerRadius(dp(card ? 16 : 10));
        surface.setColor(focused ? (card ? 0xFF22354A : 0xFF1D2C3B)
                : selected ? 0xFF193344 : (card ? CARD : ROW));
        if (focused) surface.setStroke(dp(2), WHITE);
        else if (selected) surface.setStroke(dp(1), CYAN);
        else surface.setStroke(0, Color.TRANSPARENT);
        view.setBackground(surface);
        view.setElevation(focused ? dp(6) : selected ? dp(2) : 0);
    }

    private void styleAccount(boolean focused) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(10));
        background.setColor(focused ? 0x1F77E5FF : Color.TRANSPARENT);
        background.setStroke(focused ? dp(2) : 0, focused ? WHITE : Color.TRANSPARENT);
        account.setBackground(background);
        account.setPadding(dp(7), dp(4), dp(7), dp(4));
    }

    private void styleTab(TextView tab, View underline, DiscordCommunityState.Tab value, boolean focused) {
        boolean active = model != null && model.state.tab == value;
        tab.setTextColor(active || focused ? WHITE : MUTED);
        underline.setBackgroundColor(active ? CYAN : Color.TRANSPARENT);
    }

    private boolean focusFirstDestination() {
        if (firstDestination != null && firstDestination.isAttachedToWindow()) {
            focusTarget(firstDestination, feedOwner(firstDestination));
        }
        return true;
    }

    private boolean navigateFromTab(int index, int key) {
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) return focusFirstDestination();
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int target = DiscordCommunityState.adjacentTabIndex(index, key, tabs.size());
            if (target != index) activateTab(target, true);
            else if (key == KeyEvent.KEYCODE_DPAD_RIGHT && index == tabs.size() - 1) {
                focusTarget(account, null);
            }
        }
        return true;
    }

    private boolean navigateFromAccount(int key) {
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            focusActiveTab();
        } else if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (shouldEnterDetailFromAccount(model.state.detail, detailFocusables.size())) {
                focusTarget(detailFocusables.get(0), detailScroll);
            } else {
                focusFirstDestination();
            }
        }
        return true;
    }

    private boolean navigateFeed(int row, int column, int key) {
        List<View> currentRow = feedFocusRows.get(row);
        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            View target = nearestColumn(row - 1, column);
            if (target == null) focusActiveTab(); else focusTarget(target, feedOwner(target));
        } else if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            View target = nearestColumn(row + 1, column);
            View resolved = target == null ? currentRow.get(column) : target;
            focusTarget(resolved, feedOwner(resolved));
        } else if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            View target = column > 0 ? currentRow.get(column - 1) : currentRow.get(column);
            focusTarget(target, feedOwner(target));
        } else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (column + 1 < currentRow.size()) {
                View target = currentRow.get(column + 1);
                focusTarget(target, feedOwner(target));
            }
            else if (!detailFocusables.isEmpty()) focusTarget(detailFocusables.get(0), detailScroll);
            else focusTarget(currentRow.get(column), feedOwner(currentRow.get(column)));
        }
        return true;
    }

    private boolean navigateDetail(int index, int key) {
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            View selected = feedDestinationViews.get(model.state.selectedId);
            View target = selected == null ? firstDestination : selected;
            focusTarget(target, feedOwner(target));
        } else if (key == KeyEvent.KEYCODE_DPAD_UP) {
            focusTarget(detailFocusables.get(Math.max(0, index - 1)), detailScroll);
        } else if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            focusTarget(detailFocusables.get(Math.min(detailFocusables.size() - 1, index + 1)), detailScroll);
        }
        return true;
    }

    private int tabIndex(View view) { return tabs.indexOf(view); }

    private int[] feedIndex(View view) {
        for (int row = 0; row < feedFocusRows.size(); row++) {
            int column = feedFocusRows.get(row).indexOf(view);
            if (column >= 0) return new int[]{row, column};
        }
        return null;
    }

    private View focusedCommunityView() {
        View focus = findFocus();
        if (focus != null && isCommunityTarget(focus)) return focus;
        View tagged = findTarget(logicalFocusTag);
        return tagged != null ? tagged : tabs.isEmpty() ? null : tabs.get(model.state.tab.ordinal());
    }

    private boolean isCommunityTarget(View view) {
        return tabs.contains(view) || view == account || feedIndex(view) != null
                || detailFocusables.contains(view);
    }

    private void focusTarget(View target, ScrollView owner) {
        if (target == null || !target.isAttachedToWindow() || !target.isFocusable()) return;
        Object tag = target.getTag();
        if (tag instanceof String) logicalFocusTag = (String) tag;
        target.requestFocus();
        if (owner != null) revealInOwner(owner, target);
    }

    private View findTarget(String tag) {
        if (tag == null || tag.isEmpty()) return null;
        for (TextView tab : tabs) if (tag.equals(tab.getTag())) return tab;
        if (tag.equals(account.getTag())) return account;
        View target = feedDestinationViews.get(tag);
        if (target == null) target = detailFocusViewsByTag.get(tag);
        if (target == null) target = detailDestinationViews.get(tag);
        return target;
    }

    static boolean isCommunityNavigationKey(int key) {
        return key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_BUTTON_L1
                || key == KeyEvent.KEYCODE_BUTTON_R1;
    }

    static boolean shouldEnterDetailFromAccount(DiscordCommunityState.Detail detail,
                                                int detailActionCount) {
        return detailActionCount > 0 && (detail == DiscordCommunityState.Detail.OPTIONS
                || detail == DiscordCommunityState.Detail.AUDIO
                || detail == DiscordCommunityState.Detail.SOCIAL);
    }

    static boolean focusOwnerIsRecent(boolean activityCard) { return !activityCard; }

    static boolean shouldResetRecentScroll(DiscordCommunityState.Tab previous,
                                           DiscordCommunityState.Tab next) {
        return previous != null && previous != next;
    }

    private void wireCommunityFocus() {
        if (model == null || tabs.isEmpty()) return;
        TextView activeTab = tabs.get(model.state.tab.ordinal());
        View detailTarget = !detailFocusables.isEmpty() ? detailFocusables.get(0) : account;
        for (TextView tab : tabs) {
            tab.setNextFocusDownId(firstDestination == null ? tab.getId() : firstDestination.getId());
        }
        account.setNextFocusLeftId(activeTab.getId());
        account.setNextFocusDownId(firstDestination == null ? account.getId() : firstDestination.getId());

        for (int rowIndex = 0; rowIndex < feedFocusRows.size(); rowIndex++) {
            List<View> row = feedFocusRows.get(rowIndex);
            for (int column = 0; column < row.size(); column++) {
                View item = row.get(column);
                View above = nearestColumn(rowIndex - 1, column);
                View below = nearestColumn(rowIndex + 1, column);
                item.setNextFocusUpId(above == null ? activeTab.getId() : above.getId());
                item.setNextFocusDownId(below == null ? item.getId() : below.getId());
                item.setNextFocusLeftId(column > 0 ? row.get(column - 1).getId() : item.getId());
                item.setNextFocusRightId(column + 1 < row.size()
                        ? row.get(column + 1).getId() : detailTarget.getId());
            }
        }
        View selectedFeed = feedDestinationViews.get(model.state.selectedId);
        if (selectedFeed == null) selectedFeed = firstDestination;
        for (int index = 0; index < detailFocusables.size(); index++) {
            View action = detailFocusables.get(index);
            action.setNextFocusLeftId(selectedFeed == null ? account.getId() : selectedFeed.getId());
            action.setNextFocusUpId(index == 0 ? action.getId() : detailFocusables.get(index - 1).getId());
            action.setNextFocusDownId(index + 1 < detailFocusables.size()
                    ? detailFocusables.get(index + 1).getId() : action.getId());
        }
    }

    private View nearestColumn(int rowIndex, int column) {
        if (rowIndex < 0 || rowIndex >= feedFocusRows.size()) return null;
        List<View> row = feedFocusRows.get(rowIndex);
        return row.isEmpty() ? null : row.get(Math.min(column, row.size() - 1));
    }

    private ScrollView feedOwner(View view) { return feedFocusOwners.get(view); }

    private void revealInOwner(ScrollView owner, View target) {
        target.post(() -> {
            if (!target.isAttachedToWindow() || !owner.isAttachedToWindow()) return;
            android.graphics.Rect bounds = new android.graphics.Rect();
            target.getDrawingRect(bounds);
            owner.offsetDescendantRectToMyCoords(target, bounds);
            int margin = dp(8);
            int top = owner.getScrollY() + margin;
            int bottom = owner.getScrollY() + owner.getHeight() - margin;
            int next = owner.getScrollY();
            if (bounds.bottom > bottom) next += bounds.bottom - bottom;
            else if (bounds.top < top) next -= top - bounds.top;
            if (next != owner.getScrollY()) owner.smoothScrollTo(0, Math.max(0, next));
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (model != null && model.state.detail == DiscordCommunityState.Detail.DIRECT_MESSAGE
                && directKeyboardVisible && directKeyboard != null
                && directKeyboard.handleNavigationKey(event)) return true;
        if (event.getAction() == KeyEvent.ACTION_DOWN && model != null
                && (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_L1
                || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_R1)) {
            int target = DiscordCommunityState.adjacentTabIndex(model.state.tab.ordinal(),
                    event.getKeyCode(), tabs.size());
            if (target != model.state.tab.ordinal()) activateTab(target, true);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void activateTab(int index, boolean requestFocus) {
        if (index < 0 || index >= tabs.size()) return;
        DiscordCommunityState.Tab target = DiscordCommunityState.Tab.values()[index];
        if (model == null || model.state.tab != target) callback.onTab(target);
        if (requestFocus) tabs.get(index).post(() -> tabs.get(index).requestFocus());
    }

    private void restoreFocus(String id) {
        if (id == null || id.isEmpty()) return;
        logicalFocusTag = id;
        View target = detailFocusViewsByTag.get(id);
        if (target == null && model != null && model.state.detail == DiscordCommunityState.Detail.SERVER_CHANNELS) {
            target = detailDestinationViews.get(id);
        }
        if (target == null) target = feedDestinationViews.get(id);
        if (target == null) target = detailDestinationViews.get(id);
        if (target != null && target.isAttachedToWindow()) {
            final View restore = target;
            restore.post(() -> focusTarget(restore,
                    detailFocusables.contains(restore) ? detailScroll : feedOwner(restore)));
        }
    }

    private String feedKey(Model value) {
        StringBuilder key = new StringBuilder(value.state.tab.name()).append('|').append(value.homeLoading)
                .append('|').append(value.homeUnavailable);
        appendDestinations(key, value.active);
        appendDestinations(key, value.recent);
        return key.toString();
    }

    private static void appendDestinations(StringBuilder key,
                                           List<DiscordCommunityPresentation.Destination> values) {
        for (DiscordCommunityPresentation.Destination value : values) {
            key.append('|').append(value.id).append(':').append(value.title).append(':')
                    .append(value.subtitle).append(':').append(value.avatarUrl).append(':')
                    .append(value.participantCount);
        }
    }

    private String focusedDestinationId() {
        View focus = findFocus();
        Object tag = focus == null ? null : focus.getTag();
        return tag instanceof String && ((String) tag).startsWith("discord.") ? (String) tag : null;
    }

    private String tabLabel(DiscordCommunityState.Tab tab) {
        return getContext().getString(tab == DiscordCommunityState.Tab.TOGETHER ? R.string.discord_community_together
                : tab == DiscordCommunityState.Tab.FRIENDS ? R.string.discord_community_friends
                : R.string.discord_community_servers);
    }
    private String actionLabel(DiscordCommunityPresentation.Destination item) {
        return item.kind == DiscordCommunityPresentation.Kind.CHANNEL ? (isConnectedChannel(item)
                ? getContext().getString(R.string.discord_community_connected)
                : getContext().getString(R.string.discord_community_join))
                : item.kind == DiscordCommunityPresentation.Kind.SERVER ? getContext().getString(R.string.discord_community_open)
                : getContext().getString(R.string.discord_community_profile);
    }

    static boolean isConnectedChannel(String destinationId, HostGatewayClient.DiscordVoice voice) {
        return voice != null && voice.connected && destinationId != null
                && destinationId.equals("discord.community.channel:" + voice.channelId);
    }

    static boolean optionsActionsVisible(boolean hasOptions) { return hasOptions; }

    private boolean isConnectedChannel(DiscordCommunityPresentation.Destination item) {
        return model != null && isConnectedChannel(item.id, model.voice);
    }
    private String subtitle(DiscordCommunityPresentation.Destination item) {
        if (isConnectedChannel(item)) {
            return getContext().getString(R.string.discord_community_connected);
        }
        return item.participantCount > 0 ? item.subtitle + " · " + getContext().getString(
                R.string.discord_community_people, item.participantCount) : item.subtitle;
    }
    private TextView section(String value) {
        TextView label = text(value.toUpperCase(java.util.Locale.US), 12, MUTED);
        label.setLetterSpacing(.08f); label.setPadding(dp(6), dp(7), 0, dp(4)); return label;
    }
    private TextView pill(String value) { return pill(value, ActionTone.NEUTRAL); }

    private TextView pill(String value, ActionTone tone) {
        TextView action = text(value, 12, 0xFF111721); action.setGravity(Gravity.CENTER);
        action.setPadding(dp(10), dp(5), dp(10), dp(5)); action.setFocusable(true); action.setClickable(true);
        styleAction(action, tone, false, false);
        return action;
    }

    static int actionBackground(ActionTone tone, boolean focused) {
        if (tone == ActionTone.POSITIVE_JOIN) return focused ? 0xFF1998D0 : 0xFF166D9B;
        if (tone == ActionTone.DESTRUCTIVE_LEAVE) return focused ? 0xFFC34B58 : 0xFF963B47;
        return focused ? 0xFF2A4054 : 0xFFEAF1F8;
    }

    static int actionTextColor(ActionTone tone, boolean focused) {
        return tone == ActionTone.NEUTRAL && !focused ? 0xFF111721 : WHITE;
    }

    private void styleAction(TextView action, ActionTone tone, boolean focused, boolean busy) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(actionBackground(tone, focused));
        background.setCornerRadius(dp(18));
        background.setStroke(dp(focused ? 2 : 1), focused ? WHITE :
                tone == ActionTone.POSITIVE_JOIN ? CYAN : tone == ActionTone.DESTRUCTIVE_LEAVE
                        ? 0xFFFFA5A5 : 0x334B6577);
        action.setBackground(background);
        action.setTextColor(actionTextColor(tone, focused));
        action.setAlpha(busy ? .68f : 1f);
        action.setElevation(focused ? dp(5) : 0);
    }
    private TextView text(String value, float size, int color) {
        TextView view = new TextView(getContext()); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static final int MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT;
}
