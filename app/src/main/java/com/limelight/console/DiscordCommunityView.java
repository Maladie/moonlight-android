package com.limelight.console;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.InputFilter;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.discord.DiscordSocialClient;
import com.limelight.ui.ControllerGlyphs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Native, full-screen Community composition in the v4 look (mockups 19-discord-*-v4): header,
 * tabs and feed on the blurred Home, the selected item's detail on the glass side panel, and a
 * conversation with the on-screen keyboard on glass. It deliberately owns the stable shell,
 * rather than rebuilding a generic side-panel action list when Social/Gateway snapshots refresh.
 */
final class DiscordCommunityView extends FrameLayout {
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
        /** String resource explaining why voice places are missing; 0 while the home loaded. */
        final int homeUnavailableText;
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
        /** The paired host's name, for the audio detail's "Devices on host …" subtitle. */
        final String hostName;

        Model(DiscordSocialClient.Snapshot snapshot, DiscordCommunityState state,
              List<DiscordCommunityPresentation.Destination> active,
              List<DiscordCommunityPresentation.Destination> recent,
              List<DiscordCommunityPresentation.Destination> guildChannels,
              boolean homeLoading, int homeUnavailableText, boolean guildLoading,
              boolean guildUnavailable, String error, HostGatewayClient.DiscordVoice voice,
              boolean voiceLoading, boolean voiceBusy, DiscordPanelController.CommunityOptions options,
              boolean optionsLoading, String optionsError, HostGatewayClient.DiscordAudioState audio,
              boolean audioLoading, boolean audioBusy, String audioError,
              DiscordSocialClient.Friend directFriend,
              List<DiscordDirectMessageState.Message> directMessages,
              DiscordDirectMessageState.SendState directSendState, String directDraft,
              boolean directMessagesScopeAvailable, boolean directMessagesCapable,
              boolean dmNotificationsEnabled, String hostName) {
            this.snapshot = snapshot;
            this.state = state;
            this.active = active;
            this.recent = recent;
            this.guildChannels = guildChannels;
            this.homeLoading = homeLoading;
            this.homeUnavailableText = homeUnavailableText;
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
            this.hostName = hostName == null ? "" : hostName;
        }
    }

    // Mockup geometry at the TV's 960x540 dp viewport (1080p pixels halved).
    private static final int COLUMN_LEFT_DP = 48;
    private static final int COLUMN_GAP_DP = 23;
    private static final int PANEL_MARGIN_DP = 12;
    private static final int ACTIVITY_HEIGHT_DP = 56;
    private static final int DESTINATION_HEIGHT_DP = 42;
    /** Ten 66 dp key columns (120 px keys, 12 px gaps) and the keyboard's 2 dp padding. */
    private static final int KEYBOARD_WIDTH_DP = 664;
    private static final int KEYBOARD_CARD_DP = 150;
    /** The keyboard's four 36 dp rows and its 2 dp padding. */
    private static final int KEYBOARD_HEIGHT_DP = 148;
    /** In a chat the account chip keeps its feed position (right end x 1150 px). */
    private static final int CHAT_HEADER_RIGHT_DP = ConsolePanel.WIDTH_DP + PANEL_MARGIN_DP
            + COLUMN_GAP_DP - COLUMN_LEFT_DP;
    private static final float HISTORY_SCROLL_DEAD_ZONE = .45f;
    private static final long HISTORY_SCROLL_REPEAT_MS = 110L;
    private interface Styler { void style(boolean focused); }
    private final Callback callback;
    private final ConsoleMenuBackdrop backdrop;
    /** The DM shortcut's trigger; it decides which button opens "Więcej". */
    private final String dmShortcutTrigger;
    private final LinearLayout column;
    private final LinearLayout eyebrowRow;
    private final LinearLayout tabBar;
    private final LinearLayout activeContainer;
    private final TextView recentLabel;
    private final ScrollView recentScroll;
    private final LinearLayout recentList;
    private final DiscordCommunityDetailPanel detailPanel;
    private final LockableScrollView detailScroll;
    private final TextView accountName;
    private final FrameLayout accountAvatar;
    private final LinearLayout account;
    private final LinearLayout feedColumn;
    private final LinearLayout directWorkspace;
    private final LinearLayout conversation;
    private final FrameLayout keyboardCard;
    private final ScrollView directHistoryScroll;
    private final LinearLayout directHistory;
    private final LinearLayout legendRow;
    private final LinearLayout moreCard;
    private String boundLegendKey = "";
    private boolean moreVisible;
    private EditText directComposer;
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
    private final Map<String, View> detailDestinationViews;
    private final Map<String, View> detailFocusViewsByTag;
    private final Map<View, ScrollView> feedFocusOwners = new HashMap<>();
    private final List<List<View>> feedFocusRows = new ArrayList<>();
    private final List<View> detailFocusables;
    private final List<TextView> tabs = new ArrayList<>();
    private Model model;
    private View firstDestination;
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

    DiscordCommunityView(Context context, ConsoleMenuBackdrop backdrop, String dmShortcutTrigger,
                         Callback callback) {
        super(context);
        this.callback = callback;
        this.backdrop = backdrop;
        this.dmShortcutTrigger = dmShortcutTrigger;
        // The blurred Home comes from the menu backdrop under this view, lightly scrimmed.
        setBackgroundColor(DiscordCommunityStyle.SCRIM);
        setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.HORIZONTAL);
        // Feed rows draw their focus fill 7 dp left of the column (mockup "feed_row").
        content.setClipChildren(false);
        addView(content, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f);
        columnParams.leftMargin = dp(COLUMN_LEFT_DP);
        columnParams.rightMargin = dp(COLUMN_GAP_DP);
        content.addView(column, columnParams);

        // Header (mockup "discord_shell"): eyebrow with the account chip, title, tabs.
        eyebrowRow = new LinearLayout(context);
        eyebrowRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(context);
        logo.setImageResource(R.drawable.ic_console_discord);
        logo.setColorFilter(DiscordCommunityStyle.TEXT);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(20), dp(20));
        logoParams.leftMargin = dp(2);
        eyebrowRow.addView(logo, logoParams);
        LinearLayout.LayoutParams eyebrowParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        eyebrowParams.leftMargin = dp(6);
        eyebrowRow.addView(DiscordCommunityStyle.eyebrow(context, "Discord"), eyebrowParams);
        account = new LinearLayout(context);
        account.setGravity(Gravity.CENTER_VERTICAL);
        account.setPadding(dp(5), 0, dp(18), 0);
        accountAvatar = new FrameLayout(context);
        account.addView(accountAvatar, new LinearLayout.LayoutParams(dp(20), dp(20)));
        accountName = text("Discord", 13, DiscordCommunityStyle.TEXT, false);
        accountName.setSingleLine(true);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        nameParams.leftMargin = dp(5);
        account.addView(accountName, nameParams);
        eyebrowRow.addView(account, new LinearLayout.LayoutParams(WRAP_CONTENT, dp(28)));
        column.addView(eyebrowRow, margins(MATCH_PARENT, dp(28), 33, 0));
        column.addView(text(context.getString(R.string.discord_community_title), 22,
                DiscordCommunityStyle.TEXT, true), margins(MATCH_PARENT, WRAP_CONTENT, 5, 0));
        tabBar = new LinearLayout(context);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        // Mockup tab pill: x 92 for the first tab (4 px left of the column), y 204-260.
        LinearLayout.LayoutParams tabBarParams = margins(MATCH_PARENT, WRAP_CONTENT, 10, 0);
        tabBarParams.leftMargin = -dp(2);
        column.addView(tabBar, tabBarParams);

        feedColumn = new LinearLayout(context);
        feedColumn.setOrientation(LinearLayout.VERTICAL);
        feedColumn.setClipChildren(false);
        activeContainer = new LinearLayout(context);
        activeContainer.setOrientation(LinearLayout.VERTICAL);
        feedColumn.addView(activeContainer, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        recentLabel = DiscordCommunityStyle.eyebrow(context,
                context.getString(R.string.discord_community_recent));
        recentLabel.setVisibility(GONE);
        feedColumn.addView(recentLabel, margins(MATCH_PARENT, WRAP_CONTENT, 18, 0));
        recentScroll = new ScrollView(context);
        recentScroll.setFillViewport(true);
        // The feed column lets rows draw left of it, so the list clips itself above the legend.
        recentScroll.setOutlineProvider(android.view.ViewOutlineProvider.BOUNDS);
        recentScroll.setClipToOutline(true);
        recentScroll.setVerticalScrollBarEnabled(false);
        recentList = new LinearLayout(context);
        recentList.setOrientation(LinearLayout.VERTICAL);
        recentScroll.addView(recentList, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        // Rows draw their focus fill 7 dp left of the column (mockup "feed_row").
        LinearLayout.LayoutParams recentParams = margins(MATCH_PARENT, 0, 6, 0);
        recentParams.weight = 1f;
        recentParams.leftMargin = -dp(7);
        feedColumn.addView(recentScroll, recentParams);
        LinearLayout.LayoutParams feedParams = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f);
        column.addView(feedColumn, feedParams);

        // Conversation (mockup "discord_dm"): one wide glass card, the keyboard on glass below.
        directWorkspace = new LinearLayout(context);
        directWorkspace.setOrientation(LinearLayout.VERTICAL);
        directWorkspace.setVisibility(GONE);
        conversation = glass(new LinearLayout(context));
        conversation.setOrientation(LinearLayout.VERTICAL);
        directWorkspace.addView(conversation, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));
        // Mockup "keyboard": glass y 662-962 px, the first key row 24 px below its top edge.
        keyboardCard = glass(new FrameLayout(context));
        keyboardCard.setPadding(0, dp(7), 0, 0);
        directWorkspace.addView(keyboardCard, margins(MATCH_PARENT, dp(KEYBOARD_CARD_DP), 8, 0));
        // Mockup "discord_dm": the conversation card y 290-646 px, the keyboard ending at 962.
        LinearLayout.LayoutParams workspaceParams = margins(MATCH_PARENT, 0, 11, 3);
        workspaceParams.weight = 1f;
        column.addView(directWorkspace, workspaceParams);
        directHistoryScroll = new ScrollView(context);
        directHistoryScroll.setFillViewport(true);
        directHistoryScroll.setVerticalScrollBarEnabled(false);
        directHistory = new LinearLayout(context);
        directHistory.setOrientation(LinearLayout.VERTICAL);
        directHistory.setPadding(dp(20), dp(4), dp(20), dp(8));
        directHistoryScroll.addView(directHistory, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        directHistoryScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            directNearBottom = isNearBottom(directHistoryScroll);
            if (directNearBottom && pendingNewMessagePill) clearPendingNewMessagePill();
        });

        // Screen legend centred on y = 1004 px.
        legendRow = new LinearLayout(context);
        legendRow.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(legendRow, margins(MATCH_PARENT, dp(20), 8, 28));

        detailPanel = new DiscordCommunityDetailPanel(context, backdrop, callback);
        detailScroll = detailPanel.scroll;
        detailFocusables = detailPanel.focusables;
        detailFocusViewsByTag = detailPanel.byTag;
        detailDestinationViews = detailPanel.destinations;
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                dp(ConsolePanel.WIDTH_DP), MATCH_PARENT);
        detailParams.setMargins(0, dp(PANEL_MARGIN_DP), dp(PANEL_MARGIN_DP), dp(PANEL_MARGIN_DP));
        content.addView(detailScroll, detailParams);

        // "Więcej" in a chat: the whole shortcut list on glass just above the legend.
        moreCard = glass(new LinearLayout(context));
        moreCard.setOrientation(LinearLayout.VERTICAL);
        moreCard.setPadding(dp(14), dp(10), dp(18), dp(10));
        moreCard.setVisibility(GONE);
        FrameLayout.LayoutParams moreParams = new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        moreParams.leftMargin = dp(COLUMN_LEFT_DP);
        moreParams.bottomMargin = dp(56);
        addView(moreCard, moreParams);
        buildTabs();
    }

    /** A surface cropping the menu backdrop's blurred copy (UX_MENU_PL.md 4.3). */
    private <T extends View> T glass(T view) {
        view.setBackground(new ConsoleGlass(view, backdrop));
        view.setClipToOutline(true);
        return view;
    }

    void bind(Model value) {
        String focusId = focusedDestinationId();
        model = value;
        boolean chatOnly = isChatOnlyPresentation(value.state.detail);
        // A chat keeps focus in the composer: a focused tab would switch tabs and leave the chat,
        // and removing the composer on a rebind must not hand focus to the header.
        tabBar.setDescendantFocusability(chatOnly ? FOCUS_BLOCK_DESCENDANTS : FOCUS_AFTER_DESCENDANTS);
        bindAccount(value.snapshot, chatOnly);
        bindTabs();
        feedColumn.setVisibility(chatOnly ? GONE : VISIBLE);
        detailScroll.setVisibility(chatOnly ? GONE : VISIBLE);
        directWorkspace.setVisibility(chatOnly ? VISIBLE : GONE);
        // The conversation spans to the right safe area where the detail panel was.
        LinearLayout.LayoutParams columnParams = (LinearLayout.LayoutParams) column.getLayoutParams();
        int right = dp(chatOnly ? COLUMN_LEFT_DP : COLUMN_GAP_DP);
        if (columnParams.rightMargin != right) {
            columnParams.rightMargin = right;
            column.setLayoutParams(columnParams);
            LinearLayout.LayoutParams eyebrowParams = (LinearLayout.LayoutParams) eyebrowRow.getLayoutParams();
            eyebrowParams.rightMargin = chatOnly ? dp(CHAT_HEADER_RIGHT_DP) : 0;
            eyebrowRow.setLayoutParams(eyebrowParams);
        }
        if (!chatOnly) setMoreVisible(false);
        bindLegend(chatOnly);
        if (chatOnly) {
            bindDirectMessage();
            return;
        }
        String nextFeedKey = feedKey(value);
        if (!nextFeedKey.equals(boundFeedKey)) {
            boundFeedKey = nextFeedKey;
            bindFeed();
        }
        detailPanel.render(value, find(value.state.selectedId));
        if (tabs.contains(findFocus())) showTabLegend();
        wireCommunityFocus();
        if (focusId != null) logicalFocusTag = focusId;
        if (!logicalFocusTag.isEmpty()) restoreFocus(logicalFocusTag);
    }

    /**
     * At most four legend items (UX_MENU_PL.md 4.1). A chat has more shortcuts: the rest are
     * listed under "☰ Więcej"; every button keeps its function whether listed in the row or not.
     */
    private void bindLegend(boolean chatOnly) {
        String key = chatOnly + "|" + (chatOnly && model.directMessagesCapable);
        if (key.equals(boundLegendKey)) return;
        boundLegendKey = key;
        DiscordCommunityLegend.Item back = legendItem(R.string.discord_legend_key_back,
                R.string.discord_legend_close);
        if (!chatOnly) {
            DiscordCommunityLegend.fillRow(legendRow, Arrays.asList(
                    legendItem(R.string.discord_legend_key_bumpers, R.string.discord_legend_tabs),
                    legendItem(R.string.discord_legend_key_ok, R.string.console_select_hint), back));
            return;
        }
        if (!model.directMessagesCapable) {
            DiscordCommunityLegend.fillRow(legendRow, Collections.singletonList(back));
            return;
        }
        boolean playStation = ControllerGlyphs.hasPlayStationController();
        List<DiscordCommunityLegend.Item> all = Arrays.asList(
                legendItem(R.string.discord_legend_key_bumpers, R.string.discord_legend_cursor),
                legendItem(R.string.discord_legend_key_r2, R.string.discord_dm_send),
                legendItem(R.string.discord_legend_key_right_stick, R.string.discord_legend_scroll),
                back,
                legendItem(R.string.discord_legend_key_l2, R.string.discord_legend_shift),
                glyphItem(playStation, ControllerGlyphs.Button.WEST, R.string.discord_legend_delete),
                legendItem(R.string.discord_legend_key_r3, R.string.discord_legend_open_content),
                glyphItem(playStation, ControllerGlyphs.Button.NORTH, R.string.discord_dm_keyboard_space));
        DiscordCommunityLegend.Item more = DiscordCommunityLegend.moreOnSelect(dmShortcutTrigger)
                ? legendItem(R.string.overlay_trigger_select, R.string.console_section_more)
                : glyphItem(playStation, ControllerGlyphs.Button.MENU, R.string.console_section_more);
        DiscordCommunityLegend.fillRow(legendRow, DiscordCommunityLegend.visible(all, more));
        DiscordCommunityLegend.fillColumn(moreCard, all);
    }

    private DiscordCommunityLegend.Item legendItem(int key, int label) {
        return new DiscordCommunityLegend.Item(getContext().getString(key), false,
                getContext().getString(label));
    }

    private DiscordCommunityLegend.Item glyphItem(boolean playStation, ControllerGlyphs.Button key,
                                                  int label) {
        return new DiscordCommunityLegend.Item(ControllerGlyphs.text(playStation, key), true,
                getContext().getString(label));
    }

    private void setMoreVisible(boolean visible) {
        moreVisible = visible;
        moreCard.setVisibility(visible ? VISIBLE : GONE);
    }

    static boolean isChatOnlyPresentation(DiscordCommunityState.Detail detail) {
        return detail == DiscordCommunityState.Detail.DIRECT_MESSAGE;
    }

    void focusDetailAction() {
        View action = detailPanel.firstAction;
        if (action != null) action.post(() -> {
            action.requestFocus();
            revealInOwner(detailScroll, action);
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
                && model.directMessagesCapable
                && DiscordCommunityLegend.isMoreKey(dmShortcutTrigger, key)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                setMoreVisible(!moreVisible);
            }
            return true;
        }
        // Any other button closes the list and still does its own thing.
        if (moreVisible && event.getAction() == KeyEvent.ACTION_DOWN) setMoreVisible(false);
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
        // Analog L2/R2 and the right stick close "Więcej" like any other button.
        if (moreVisible && (direction != KeyEvent.KEYCODE_UNKNOWN || triggerHandled || historyHandled)) {
            setMoreVisible(false);
        }
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
            holder.setOrientation(LinearLayout.VERTICAL);
            TextView view = text(tabLabel(tab), 14, DiscordCommunityStyle.TEXT_SECONDARY, false);
            view.setGravity(Gravity.CENTER);
            view.setSingleLine(true);
            view.setPadding(dp(11), 0, dp(11), 0);
            ConsoleText.centreOnGlyphs(view);
            view.setId(View.generateViewId());
            view.setTag("discord.community.tab." + tab.name().toLowerCase(java.util.Locale.US));
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
            view.setClickable(true);
            View underline = new View(getContext());
            underline.setBackground(DiscordCommunityStyle.surface(getContext(),
                    DiscordCommunityStyle.ACCENT, 1));
            view.setOnFocusChangeListener((ignored, focused) -> {
                styleTab(view, underline, tab, focused);
                if (focused && (model == null || model.state.tab != tab)) callback.onTab(tab);
                if (focused) showTabLegend();
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
            // Mockup tab: 56 px pill, the active one underlined 2 px below it in the accent.
            holder.addView(view, new LinearLayout.LayoutParams(WRAP_CONTENT, dp(28)));
            LinearLayout.LayoutParams underlineParams = new LinearLayout.LayoutParams(1, dp(2));
            underlineParams.leftMargin = dp(11);
            underlineParams.topMargin = dp(1);
            holder.addView(underline, underlineParams);
            // A horizontal LinearLayout defaults children without params to MATCH_PARENT width.
            // Tabs must retain their label footprint; otherwise the first tab pushes the rest off TV.
            LinearLayout.LayoutParams holderParams = new LinearLayout.LayoutParams(
                    WRAP_CONTENT, WRAP_CONTENT);
            holderParams.rightMargin = dp(8);
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

    private void bindAccount(DiscordSocialClient.Snapshot snapshot, boolean chatOnly) {
        boolean connect = snapshot.authorizationRequired;
        String name = snapshot.displayName.isEmpty() ? "Discord" : snapshot.displayName;
        accountName.setText(connect ? getContext().getString(R.string.discord_social_connect_action)
                : name + "  ·  " + getContext().getString(R.string.console_host_options_hint));
        accountAvatar.removeAllViews();
        if (connect) {
            accountAvatar.setBackground(null);
            ImageView link = new ImageView(getContext());
            link.setImageResource(R.drawable.ic_console_link);
            accountAvatar.addView(link, new FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER));
        } else {
            FrameLayout replacement = avatar(name, snapshot.avatarUrl, 20);
            accountAvatar.setBackground(replacement.getBackground());
            while (replacement.getChildCount() > 0) {
                View child = replacement.getChildAt(0);
                replacement.removeView(child);
                accountAvatar.addView(child);
            }
        }
        // Host options are meaningful even when the optional Social SDK is unavailable.  The
        // header always enters the inline options detail; authorization stays an explicit
        // action inside that detail instead of making the header a dead D-pad target.
        account.setId(account.getId() == View.NO_ID ? View.generateViewId() : account.getId());
        account.setTag("discord.community.options");
        account.setFocusable(!chatOnly);
        account.setFocusableInTouchMode(!chatOnly);
        account.setClickable(true);
        account.setContentDescription(getContext().getString(R.string.discord_community_options));
        account.setOnClickListener(ignored -> callback.onOptions());
        account.setOnFocusChangeListener((ignored, focused) -> {
            styleAccount(focused);
            if (focused) detailPanel.restoreLegend();
        });
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
            activeContainer.addView(section(R.string.discord_community_active),
                    margins(MATCH_PARENT, WRAP_CONTENT, 21, 0));
            // Mockup "together_feed": two 519x112 px cards per row, 16 px apart.
            LinearLayout grid = new LinearLayout(getContext());
            grid.setOrientation(LinearLayout.VERTICAL);
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
                    rightParams.leftMargin = dp(8);
                    row.addView(right, rightParams);
                    focusRow.add(right);
                } else {
                    LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(
                            0, dp(ACTIVITY_HEIGHT_DP), 1f);
                    spacer.leftMargin = dp(8);
                    row.addView(new View(getContext()), spacer);
                }
                grid.addView(row, margins(MATCH_PARENT, WRAP_CONTENT, index > 0 ? 8 : 0, 0));
                feedFocusRows.add(focusRow);
            }
            activeContainer.addView(grid, margins(MATCH_PARENT, WRAP_CONTENT, 8, 0));
        }
        if (!model.recent.isEmpty()) {
            recentLabel.setVisibility(VISIBLE);
            recentLabel.setLayoutParams(margins(MATCH_PARENT, WRAP_CONTENT,
                    model.active.isEmpty() ? 21 : 18, 0));
            // Friends: "Online · n" over the list and "Offline · n" before the first offline row.
            boolean friends = model.state.tab == DiscordCommunityState.Tab.FRIENDS;
            int online = 0;
            int offline = 0;
            for (DiscordSocialClient.Friend friend : model.snapshot.friendDetails) {
                if (DiscordCommunityPresentation.isPresent(friend.group)) online++; else offline++;
            }
            recentLabel.setText(friendsHeader(!friends ? R.string.discord_community_recent
                    : online > 0 ? R.string.discord_community_online_count
                    : R.string.discord_community_offline_count, online > 0 ? online : offline));
            boolean offlineHeaderDue = friends && online > 0;
            for (DiscordCommunityPresentation.Destination item : model.recent) {
                if (offlineHeaderDue && item.kind == DiscordCommunityPresentation.Kind.FRIEND && !item.active) {
                    offlineHeaderDue = false;
                    LinearLayout.LayoutParams headerParams = margins(MATCH_PARENT, WRAP_CONTENT, 9, 6);
                    headerParams.leftMargin = dp(7);
                    recentList.addView(DiscordCommunityStyle.eyebrow(getContext(),
                            friendsHeader(R.string.discord_community_offline_count, offline)), headerParams);
                }
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
                    : model.homeUnavailableText != 0 ? getContext().getString(model.homeUnavailableText)
                    : model.snapshot.connected ? getContext().getString(R.string.discord_community_no_active)
                    : model.error;
            View card = emptyCard(empty);
            if (card != null) {
                activeContainer.addView(section(R.string.discord_community_active),
                        margins(MATCH_PARENT, WRAP_CONTENT, 21, 0));
                activeContainer.addView(card, margins(MATCH_PARENT, WRAP_CONTENT, 8, 0));
            }
        } else {
            recentLabel.setVisibility(GONE);
        }
        if (tabChanged) recentScroll.scrollTo(0, 0);
    }

    /** Mockup "discord_disconnected": the empty feed as one glass card with the Discord mark. */
    private View emptyCard(String message) {
        boolean linked = isLinked(model.snapshot);
        if (linked && TextUtils.isEmpty(message)) return null;
        LinearLayout card = new LinearLayout(getContext());
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(DiscordCommunityStyle.outlined(getContext(),
                DiscordCommunityStyle.CARD_GLASS, 16, DiscordCommunityStyle.CARD_HAIRLINE));
        card.setPadding(dp(25), dp(20), dp(24), dp(20));
        ImageView mark = new ImageView(getContext());
        mark.setImageResource(R.drawable.ic_console_discord);
        mark.setColorFilter(DiscordCommunityStyle.TEXT_SECONDARY);
        card.addView(mark, new LinearLayout.LayoutParams(dp(20), dp(20)));
        LinearLayout words = new LinearLayout(getContext());
        words.setOrientation(LinearLayout.VERTICAL);
        if (!linked) {
            words.addView(text(getContext().getString(R.string.discord_community_disconnected), 15,
                    DiscordCommunityStyle.TEXT, true));
        }
        if (!TextUtils.isEmpty(message)) {
            TextView body = text(message, 13, DiscordCommunityStyle.TEXT_SECONDARY, false);
            body.setLineSpacing(dp(3), 1f);
            words.addView(body, margins(MATCH_PARENT, WRAP_CONTENT, linked ? 0 : 6, 0));
        }
        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        wordsParams.leftMargin = dp(20);
        card.addView(words, wordsParams);
        return card;
    }

    /** Mockup "feed_card": glass at rest, the light fill with dark ink on focus. */
    private View activity(DiscordCommunityPresentation.Destination item) {
        LinearLayout card = new LinearLayout(getContext());
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), 0, dp(11), 0);
        FrameLayout portrait = new FrameLayout(getContext());
        portrait.addView(avatar(item.title, item.avatarUrl, 32), new FrameLayout.LayoutParams(dp(32), dp(32)));
        if (item.unread) {
            // 18 px red badge on the avatar's lower right, ringed in the card colour.
            View badge = new View(getContext());
            badge.setBackground(DiscordCommunityStyle.dot(DiscordCommunityStyle.LIVE, 0xFF16181F, dp(1.5f)));
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(dp(10), dp(10));
            badgeParams.leftMargin = dp(23);
            badgeParams.topMargin = dp(22);
            portrait.addView(badge, badgeParams);
        }
        card.addView(portrait, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout words = new LinearLayout(getContext());
        words.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(item.title, 15, DiscordCommunityStyle.TEXT, true);
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(title);
        TextView meta = text(subtitle(item), 12, DiscordCommunityStyle.TEXT_SECONDARY, false);
        meta.setSingleLine(true); meta.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(meta, margins(MATCH_PARENT, WRAP_CONTENT, 3, 0));
        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        wordsParams.leftMargin = dp(6);
        card.addView(words, wordsParams);
        boolean voice = item.kind == DiscordCommunityPresentation.Kind.CHANNEL;
        TextView pill = text(actionLabel(item), 12, DiscordCommunityStyle.TEXT, true);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(8.5f), 0, dp(8.5f), 0);
        ConsoleText.centreOnGlyphs(pill);
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(WRAP_CONTENT, dp(22));
        pillParams.leftMargin = dp(8);
        card.addView(pill, pillParams);
        bindDestination(card, item, true, focused -> {
            card.setBackground(focused ? fill(DiscordCommunityStyle.FOCUS_FILL, 14)
                    : DiscordCommunityStyle.outlined(getContext(), DiscordCommunityStyle.CARD_GLASS,
                    14, DiscordCommunityStyle.CARD_HAIRLINE));
            title.setTextColor(focused ? DiscordCommunityStyle.INK : DiscordCommunityStyle.TEXT);
            meta.setTextColor(focused ? DiscordCommunityStyle.INK_SECONDARY
                    : DiscordCommunityStyle.TEXT_SECONDARY);
            // Join pills are green (state colour); the others are a faint white capsule.
            pill.setBackground(fill(voice ? (focused ? DiscordCommunityStyle.ONLINE : 0x3C30D694)
                    : focused ? 0x28FFFFFF : 0x24FFFFFF, 11));
            pill.setTextColor(focused ? DiscordCommunityStyle.INK
                    : voice ? DiscordCommunityStyle.ONLINE : DiscordCommunityStyle.TEXT);
        });
        return card;
    }

    /** Mockup "feed_row": avatar, two lines and a chevron; the selected row's name in the accent. */
    private View destination(DiscordCommunityPresentation.Destination item) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(7), 0, dp(7), 0);
        row.addView(avatar(item.title, item.avatarUrl, 26), new LinearLayout.LayoutParams(dp(26), dp(26)));
        LinearLayout copy = new LinearLayout(getContext()); copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(item.title, 15, DiscordCommunityStyle.TEXT, false);
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(title);
        TextView meta = text(subtitle(item), 12, DiscordCommunityStyle.TEXT_SECONDARY, false);
        meta.setSingleLine(true); meta.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(meta, margins(MATCH_PARENT, WRAP_CONTENT, 3, 0));
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        copyParams.leftMargin = dp(9); row.addView(copy, copyParams);
        ImageView chevron = new ImageView(getContext());
        chevron.setImageResource(R.drawable.ic_console_chevron);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(15), dp(15)));
        bindDestination(row, item, false, focused -> {
            row.setBackground(focused ? fill(DiscordCommunityStyle.FOCUS_FILL, 12) : null);
            title.setTextColor(focused ? DiscordCommunityStyle.INK
                    : isSelected(item) ? DiscordCommunityStyle.ACCENT : DiscordCommunityStyle.TEXT);
            meta.setTextColor(focused ? DiscordCommunityStyle.INK_SECONDARY
                    : DiscordCommunityStyle.TEXT_SECONDARY);
            chevron.setColorFilter(focused ? DiscordCommunityStyle.INK
                    : DiscordCommunityStyle.TEXT_SECONDARY);
        });
        return row;
    }

    private void bindDestination(View view, DiscordCommunityPresentation.Destination item, boolean card,
                                 Styler styler) {
        view.setId(View.generateViewId());
        view.setTag(item.id);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setClickable(true);
        view.setContentDescription(item.title + ", " + subtitle(item));
        view.setOnFocusChangeListener((ignored, focused) -> {
            styler.style(focused);
            if (focused) {
                ScrollView owner = feedOwner(view);
                if (owner != null) revealInOwner(owner, view);
                callback.onDestinationFocused(item);
                detailPanel.render(model, item);
                wireCommunityFocus();
            }
        });
        view.setOnClickListener(ignored -> callback.onDestinationOpened(item));
        feedDestinationViews.put(item.id, view);
        feedFocusOwners.put(view, focusOwnerIsRecent(card) ? recentScroll : null);
        if (firstDestination == null) firstDestination = view;
        styler.style(false);
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
        conversation.removeAllViews();
        DiscordSocialClient.Friend friend = model.directFriend;
        String name = friend == null ? "Discord" : friend.displayName;
        String avatar = friend == null ? "" : friend.avatarUrl;

        // Mockup "discord_dm" header: avatar, name, "Wiadomość prywatna · activity", disclosure.
        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(7.5f), dp(12), dp(4));
        header.addView(avatar(name, avatar, 28), new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout words = new LinearLayout(getContext());
        words.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(name, 16, DiscordCommunityStyle.TEXT, true);
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(title);
        String presence = getContext().getString(R.string.discord_dm_private);
        if (friend != null && !friend.activityName.isEmpty()) presence += " · " + friend.activityName;
        TextView presenceView = text(presence, 12, DiscordCommunityStyle.TEXT_SECONDARY, false);
        presenceView.setSingleLine(true); presenceView.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(presenceView, margins(MATCH_PARENT, WRAP_CONTENT, 0, 0));
        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        wordsParams.leftMargin = dp(8);
        header.addView(words, wordsParams);
        // Mockup "discord_dm": the disclosure at the header's right; while sending, the send state.
        TextView disclosure = text(getContext().getString(model.directSendState.inFlight
                ? R.string.discord_dm_sending : R.string.discord_community_dm_disclosure), 12,
                DiscordCommunityStyle.HEADER, false);
        LinearLayout.LayoutParams disclosureParams = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        disclosureParams.leftMargin = dp(12);
        header.addView(disclosure, disclosureParams);
        conversation.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        if (!model.directMessagesCapable) {
            if (directKeyboard != null) directKeyboard.cancelKeyboardHold();
            directComposer = null;
            directKeyboard = null;
            directKeyboardVisible = false;
            keyboardCard.removeAllViews();
            keyboardCard.setVisibility(GONE);
            TextView unavailable = text(getContext().getString(model.directMessagesScopeAvailable
                    ? R.string.discord_dm_chat_open_elsewhere : R.string.discord_dm_upgrade), 13,
                    DiscordCommunityStyle.TEXT_SECONDARY, false);
            unavailable.setPadding(dp(20), dp(12), dp(20), dp(12));
            conversation.addView(unavailable, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            return;
        }

        directHistory.removeAllViews();
        pendingNewMessageView = null;
        directMediaRows.clear();
        String selfName = model.snapshot.displayName.isEmpty() ? "Discord" : model.snapshot.displayName;
        java.text.DateFormat timeFormat = DateFormat.getTimeFormat(getContext());
        for (DiscordDirectMessageState.Message message : model.directMessages) {
            if (message.disclosure) continue;
            boolean self = safeUserId(model.snapshot.userId) == message.authorId;
            if (!message.content.isEmpty()) addDirectMessageText(message, self);
            addMessageMeta(message, self, self ? selfName : name, timeFormat);
            if (shouldRenderAdditionalNotice(message.additionalContentType, message.additionalContentCount)) {
                addAdditionalContent(message, self);
            } else if (shouldRenderGenericUnsupportedContent(message.content, message.additionalContentType,
                    message.additionalContentCount, message.disclosure)) {
                addGenericUnsupportedContent(message, self);
            }
        }
        if (pendingNewMessagePill) {
            TextView newMessage = text(getContext().getString(R.string.discord_dm_new_message), 12,
                    DiscordCommunityStyle.TEXT, true);
            newMessage.setGravity(Gravity.CENTER);
            newMessage.setPadding(dp(10), 0, dp(10), 0);
            newMessage.setBackground(fill(0x28FFFFFF, 11));
            ConsoleText.centreOnGlyphs(newMessage);
            newMessage.setOnClickListener(ignored -> {
                clearPendingNewMessagePill();
                directHistoryScroll.post(() -> directHistoryScroll.fullScroll(View.FOCUS_DOWN));
            });
            LinearLayout.LayoutParams newParams = new LinearLayout.LayoutParams(WRAP_CONTENT, dp(22));
            newParams.gravity = Gravity.CENTER_HORIZONTAL; newParams.topMargin = dp(8);
            directHistory.addView(newMessage, newParams);
            pendingNewMessageView = newMessage;
        }
        conversation.addView(directHistoryScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

        if (!model.directSendState.errorType.isEmpty()) {
            int retrySeconds = (int) Math.ceil(model.directSendState.retryAfterSeconds);
            TextView sendError = text(model.directSendState.retryable && retrySeconds > 0
                    ? getContext().getString(R.string.discord_dm_retry_after, retrySeconds)
                    : getContext().getString(R.string.discord_dm_send_failed), 12,
                    DiscordCommunityStyle.TEXT_SECONDARY, false);
            sendError.setPadding(dp(20), dp(4), dp(20), dp(4));
            conversation.addView(sendError, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        }
        // Composer (mockup: 56 px capsule, white at 30/255 with a 120/255 hairline).
        LinearLayout composerRow = new LinearLayout(getContext());
        composerRow.setGravity(Gravity.CENTER_VERTICAL);
        directComposer = new EditText(getContext());
        directComposer.setId(View.generateViewId());
        directComposer.setTextColor(DiscordCommunityStyle.TEXT);
        directComposer.setTextSize(14);
        directComposer.setHintTextColor(DiscordCommunityStyle.TEXT_SECONDARY);
        directComposer.setHint(R.string.discord_dm_placeholder);
        directComposer.setBackground(DiscordCommunityStyle.outlined(getContext(),
                DiscordCommunityStyle.BUBBLE, 14, 0x78FFFFFF));
        directComposer.setPadding(dp(14), dp(5), dp(14), dp(5));
        directComposer.setMinHeight(dp(28));
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
        // "Wyślij" lives on the keyboard and under R2; the field spans the card (mockup).
        composerRow.addView(directComposer, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams composerParams = margins(MATCH_PARENT, WRAP_CONTENT, 4, 4);
        composerParams.leftMargin = dp(15);
        composerParams.rightMargin = dp(15);
        conversation.addView(composerRow, composerParams);
        if (!preserveComposer || directKeyboard == null || directKeyboardRecipientId != recipientId) {
            // The screen legend lists the chat shortcuts, so the keyboard shows none of its own.
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
                    getContext().getString(R.string.discord_dm_send), "", "", "", "", "", "", true);
            directKeyboard.setCompactMode(true);
            directKeyboardRecipientId = recipientId;
            directKeyboardVisible = true;
        }
        keyboardCard.removeAllViews();
        keyboardCard.addView(directKeyboard, new FrameLayout.LayoutParams(dp(KEYBOARD_WIDTH_DP),
                dp(KEYBOARD_HEIGHT_DP), Gravity.CENTER_HORIZONTAL));
        keyboardCard.setVisibility(directKeyboardVisible ? VISIBLE : GONE);
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

    /** Mockup "bubble": 64 px high, 26 px corners; own messages in Discord blurple. */
    private void addDirectMessageText(DiscordDirectMessageState.Message message, boolean self) {
        TextView bubble = text(message.content, 14, DiscordCommunityStyle.TEXT, false);
        bubble.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
        bubble.setTextIsSelectable(false);
        bubble.setBackground(fill(self ? DiscordCommunityStyle.BUBBLE_SELF : DiscordCommunityStyle.BUBBLE, 13));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        params.gravity = self ? Gravity.END : Gravity.START;
        params.topMargin = directHistory.getChildCount() == 0 ? 0 : dp(10);
        directHistory.addView(bubble, params);
    }

    /** "Ola · 20:41" under the message. */
    private void addMessageMeta(DiscordDirectMessageState.Message message, boolean self, String author,
                                java.text.DateFormat timeFormat) {
        String time = message.sentTimestampMs > 0
                ? timeFormat.format(new Date(message.sentTimestampMs)) : "";
        TextView meta = text(time.isEmpty() ? author : author + " · " + time, 12,
                DiscordCommunityStyle.HEADER, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        params.gravity = self ? Gravity.END : Gravity.START;
        params.topMargin = dp(3);
        directHistory.addView(meta, params);
    }

    private void addAdditionalContent(DiscordDirectMessageState.Message message, boolean self) {
        addDirectContentNotice(message, self, additionalContentLabel(message));
    }

    private void addGenericUnsupportedContent(DiscordDirectMessageState.Message message, boolean self) {
        addDirectContentNotice(message, self, getContext().getString(R.string.discord_dm_unsupported_content));
    }

    /** Mockup attachment line: 300 dp outlined capsule, white 70/255 hairline. */
    private void addDirectContentNotice(DiscordDirectMessageState.Message message, boolean self, String label) {
        TextView content = text(label, 12, DiscordCommunityStyle.TEXT_SECONDARY, false);
        content.setPadding(dp(12), dp(4), dp(12), dp(4));
        content.setMinHeight(dp(25));
        content.setMinWidth(dp(300));
        content.setClickable(true);
        content.setFocusable(false);
        content.setOnClickListener(ignored -> callback.onOpenDirectMessageInDiscord(message.id));
        content.setBackground(DiscordCommunityStyle.outlined(getContext(), 0, 10, 0x46FFFFFF));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        params.gravity = self ? Gravity.END : Gravity.START;
        params.topMargin = dp(3);
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
        if (moreVisible && event.getAction() == KeyEvent.ACTION_DOWN) setMoreVisible(false);
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
        if (directKeyboard != null) keyboardCard.setVisibility(VISIBLE);
        directKeyboardVisible = true;
        if (directComposer != null) directComposer.requestFocus();
    }

    private void hideEmbeddedKeyboard() {
        directKeyboardVisible = false;
        if (directKeyboard != null) {
            directKeyboard.cancelKeyboardHold();
            keyboardCard.setVisibility(GONE);
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

    /** Mockup 19-discord-friends: with a tab focused the panel legend explains the tabs. */
    private void showTabLegend() {
        if (model != null && model.state.detail == DiscordCommunityState.Detail.FEED
                && isLinked(model.snapshot)) detailPanel.showTabLegend();
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

    /** Account chip: faint glass capsule at rest, the light fill on focus (4.2). */
    private void styleAccount(boolean focused) {
        account.setBackground(focused ? fill(DiscordCommunityStyle.FOCUS_FILL, 14)
                : DiscordCommunityStyle.outlined(getContext(), 0x16FFFFFF, 14, 0x3CFFFFFF));
        accountName.setTextColor(focused ? DiscordCommunityStyle.INK : DiscordCommunityStyle.TEXT);
        View icon = accountAvatar.getChildCount() == 1 ? accountAvatar.getChildAt(0) : null;
        if (icon instanceof ImageView) {
            ((ImageView) icon).setColorFilter(focused ? DiscordCommunityStyle.INK : DiscordCommunityStyle.TEXT);
        }
    }

    /** Tabs: the active one semibold and underlined in the accent; focus is the light pill. */
    private void styleTab(TextView tab, View underline, DiscordCommunityState.Tab value, boolean focused) {
        boolean active = model != null && model.state.tab == value;
        tab.setTypeface(active ? DiscordCommunityStyle.MEDIUM : Typeface.DEFAULT);
        tab.setTextColor(focused ? DiscordCommunityStyle.INK
                : active ? DiscordCommunityStyle.TEXT : DiscordCommunityStyle.TEXT_SECONDARY);
        tab.setBackground(focused ? fill(DiscordCommunityStyle.FOCUS_FILL, 14) : null);
        underline.setVisibility(active && !focused ? VISIBLE : INVISIBLE);
        int width = Math.max(1, Math.round(tab.getPaint().measureText(tab.getText().toString())));
        ViewGroup.LayoutParams params = underline.getLayoutParams();
        if (params.width != width) {
            params.width = width;
            underline.setLayoutParams(params);
        }
    }

    private boolean focusFirstDestination() {
        if (firstDestination != null && firstDestination.isAttachedToWindow()) {
            focusTarget(firstDestination, feedOwner(firstDestination));
        } else if (firstDestination == null && !detailFocusables.isEmpty()) {
            // An empty feed (e.g. no account yet) leads straight into the panel's actions.
            focusTarget(detailFocusables.get(0), detailScroll);
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
        if (detailPanel.stepVolume(detailFocusables.get(index), key)) return true;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            View selected = feedDestinationViews.get(model.state.selectedId);
            View target = selected == null ? firstDestination : selected;
            if (target == null) focusActiveTab();
            else focusTarget(target, feedOwner(target));
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
        DiscordCommunityStyle.reveal(owner, target);
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
                .append('|').append(value.homeUnavailableText).append('|').append(isLinked(value.snapshot))
                .append('|').append(value.error);
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
        return DiscordCommunityDetailPanel.subtitle(getContext(), item, model == null ? null : model.voice);
    }

    private String friendsHeader(int label, int count) {
        return getContext().getString(label, count).toUpperCase(java.util.Locale.ROOT);
    }

    private TextView section(int label) {
        return DiscordCommunityStyle.eyebrow(getContext(), getContext().getString(label));
    }

    static boolean isLinked(DiscordSocialClient.Snapshot snapshot) {
        return snapshot.connected || !snapshot.userId.isEmpty();
    }

    private FrameLayout avatar(String title, String url, int sizeDp) {
        return DiscordCommunityPresentation.avatar(getContext(), title, url, sizeDp);
    }

    private GradientDrawable fill(int color, float radiusDp) {
        return DiscordCommunityStyle.surface(getContext(), color, radiusDp);
    }

    private TextView text(CharSequence value, float size, int color, boolean medium) {
        return DiscordCommunityStyle.text(getContext(), value, size, color, medium);
    }

    /** Linear params with top and bottom margins in dp. */
    private LinearLayout.LayoutParams margins(int width, int height, int topDp, int bottomDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = dp(topDp);
        params.bottomMargin = dp(bottomDp);
        return params;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static final int MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT;
}
