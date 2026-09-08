package com.limelight.ui.overlay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputFilter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.binding.input.ControllerHandler.ControllerBatteryInfo;
import com.limelight.console.EmbeddedTvKeyboardView;
import com.limelight.discord.DiscordSocialClient;
import com.limelight.console.DiscordCommunityPresentation;
import com.limelight.ui.ControllerGlyphs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OverlayMenuView extends LinearLayout {
    private static final int BUTTON_SPACING_DP = 8;
    private static final int BUTTON_HEIGHT_DP = 48;
    private static final int BUTTON_ICON_SIZE_DP = 24;
    private static final int BUTTON_PADDING_DP = 12;
    private static final int COMMUNITY_QUICK_WIDTH_DP = 520;
    private static final int COMMUNITY_QUICK_HEIGHT_DP = 360;
    private static final int COMMUNITY_CHAT_WIDTH_DP = 600;
    private static final int COMMUNITY_CHAT_HEIGHT_DP = 460;
    private static final int COMMUNITY_LIST_SCROLL_DP = 84;
    private static final int COMMUNITY_HISTORY_SCROLL_DP = 96;
    private static final int COMMUNITY_RAIL_ICON_COUNT = 3;
    private static final float ANALOG_STICK_THRESHOLD = 0.5f;
    private static final long ANALOG_NAV_THROTTLE_MS = 200;

    public interface MenuActionListener {
        void onHome();
        void onEndGame();
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
        default void onDiscordCommunityOpenFriendChat(String friendId) { }
        default void onDiscordCommunityLoadGuild(String guildId) { }
        default void onDiscordCommunityJoinChannel(String channelId) { }
        default void onDiscordCommunityChatDraftChanged(String friendId, String draft) { }
        default void onDiscordCommunitySendChat(String friendId, String draft) { }
        default void onDiscordCommunityOpenMessageInDiscord(String messageId) { }
        default void onDiscordCommunityBackToFriends() { }
        default void onDiscordCommunityBackToChannels() { }
        default void onDiscordCommunityOpened() { }
        default void onDiscordCommunitySectionChanged(CommunitySection section) { }
        default void onDiscordCommunityAuthorizeDirectMessages() { }
        void onInstallationConfirmed();
        void onMenuClosed();
    }

    /** Immutable data projection for the stream overlay; network and SDK ownership stay outside. */
    public static final class CommunityModel {
        public final long revision;
        public final CommunityStatus gatewayStatus;
        public final String gatewayMessage;
        public final CommunityStatus socialStatus;
        public final String socialMessage;
        public final VoiceSummary voice;
        public final List<CommunityFriend> friends;
        public final List<CommunityChannel> favorites;
        public final List<CommunityChannel> recent;
        public final List<CommunityGuild> guilds;
        public final List<CommunityChannel> guildChannels;
        public final String selectedGuildId;
        public final boolean directMessagesAvailable;
        public final ChatModel chat;

        public CommunityModel(long revision, CommunityStatus gatewayStatus, String gatewayMessage,
                              CommunityStatus socialStatus, String socialMessage, VoiceSummary voice,
                              List<CommunityFriend> friends, List<CommunityChannel> favorites,
                              List<CommunityChannel> recent, List<CommunityGuild> guilds,
                              List<CommunityChannel> guildChannels, String selectedGuildId,
                              boolean directMessagesAvailable, ChatModel chat) {
            this.revision = revision;
            this.gatewayStatus = gatewayStatus == null ? CommunityStatus.UNAVAILABLE : gatewayStatus;
            this.gatewayMessage = clean(gatewayMessage);
            this.socialStatus = socialStatus == null ? CommunityStatus.UNAVAILABLE : socialStatus;
            this.socialMessage = clean(socialMessage);
            this.voice = voice;
            this.friends = immutable(friends);
            this.favorites = immutable(favorites);
            this.recent = immutable(recent);
            this.guilds = immutable(guilds);
            this.guildChannels = immutable(guildChannels);
            this.selectedGuildId = clean(selectedGuildId);
            this.directMessagesAvailable = directMessagesAvailable;
            this.chat = chat;
        }
    }

    public enum CommunityStatus { LOADING, READY, UNAVAILABLE, ERROR }
    public enum CommunitySection { TOGETHER, FRIENDS, CHANNELS }

    public static final class VoiceSummary {
        public final boolean connected, muted, deafened;
        public final String guildName, channelName;
        public final List<VoiceParticipant> participants;
        public VoiceSummary(boolean connected, String guildName, String channelName, boolean muted,
                            boolean deafened, List<VoiceParticipant> participants) {
            this.connected = connected; this.guildName = clean(guildName); this.channelName = clean(channelName);
            this.muted = muted; this.deafened = deafened; this.participants = immutable(participants);
        }
    }
    public static final class VoiceParticipant {
        public final String id, name, avatarUrl;
        public final int volume;
        public final boolean muted, speaking, self;
        public VoiceParticipant(String id, String name, int volume, boolean muted,
                                boolean speaking, boolean self) {
            this(id, name, volume, muted, speaking, self, "");
        }
        public VoiceParticipant(String id, String name, int volume, boolean muted,
                                boolean speaking, boolean self, String avatarUrl) {
            this.id = clean(id); this.name = clean(name); this.volume = Math.max(0, Math.min(200, volume));
            this.muted = muted; this.speaking = speaking; this.self = self;
            this.avatarUrl = clean(avatarUrl);
        }
    }
    public static final class CommunityFriend {
        public final String id, name, activity, avatarUrl;
        public final FriendPresence presence;
        public final boolean unread;
        public CommunityFriend(String id, String name, String activity, String avatarUrl,
                               FriendPresence presence) {
            this(id, name, activity, avatarUrl, presence, false);
        }
        public CommunityFriend(String id, String name, String activity, String avatarUrl,
                               FriendPresence presence, boolean unread) {
            this.id = clean(id); this.name = clean(name); this.activity = clean(activity);
            this.avatarUrl = clean(avatarUrl); this.presence = presence == null ? FriendPresence.OFFLINE : presence;
            this.unread = unread;
        }
    }
    public enum FriendPresence { PLAYING, ONLINE, OFFLINE }
    public static final class CommunityGuild {
        public final String id, name;
        public CommunityGuild(String id, String name) { this.id = clean(id); this.name = clean(name); }
    }
    public static final class CommunityChannel {
        public final String id, guildId, guildName, name;
        public final int people;
        public final boolean favorite;
        public CommunityChannel(String id, String guildId, String guildName, String name,
                                int people, boolean favorite) {
            this.id = clean(id); this.guildId = clean(guildId); this.guildName = clean(guildName);
            this.name = clean(name); this.people = people; this.favorite = favorite;
        }
    }
    public static final class ChatModel {
        public final String recipientId, recipientName, recipientAvatarUrl, draft, error, retryMessage;
        public final List<ChatMessage> messages;
        public final boolean loadingHistory, sending, retryable;
        public ChatModel(String recipientId, String recipientName, String recipientAvatarUrl,
                         String draft, List<ChatMessage> messages, boolean loadingHistory,
                         boolean sending, String error, boolean retryable, String retryMessage) {
            this.recipientId = clean(recipientId); this.recipientName = clean(recipientName);
            this.recipientAvatarUrl = clean(recipientAvatarUrl); this.draft = clean(draft);
            this.messages = immutable(messages); this.loadingHistory = loadingHistory; this.sending = sending;
            this.error = clean(error); this.retryable = retryable; this.retryMessage = clean(retryMessage);
        }
    }
    public static final class ChatMessage {
        public final String id, content, additionalContentType, additionalContentTitle;
        public final int additionalContentCount;
        public final boolean self, disclosure;
        public ChatMessage(String id, String content, String additionalContentType,
                           String additionalContentTitle, int additionalContentCount,
                           boolean self, boolean disclosure) {
            this.id = clean(id); this.content = clean(content); this.additionalContentType = clean(additionalContentType);
            this.additionalContentTitle = clean(additionalContentTitle);
            this.additionalContentCount = Math.max(0, additionalContentCount);
            this.self = self; this.disclosure = disclosure;
        }
    }

    private static String clean(String value) { return value == null ? "" : value; }
    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values == null ? Collections.<T>emptyList() : values));
    }

    private LinearLayout verticalContainer;
    private LinearLayout batteryContainer;
    private HorizontalScrollView horizontalScrollView;
    private LinearLayout horizontalContainer;
    private LinearLayout discordContainer;
    private LinearLayout discordRail;
    private LinearLayout discordQuickMain;
    private LinearLayout discordQuickHeader;
    private LinearLayout discordQuickFooter;
    private ScrollView discordContentScroll;
    private LinearLayout discordContentContainer;
    private LinearLayout discordVoiceContentContainer;
    private LinearLayout discordSocialContentContainer;
    private LinearLayout discordActionsContainer;
    private LinearLayout discordChatContainer;
    private ScrollView discordChatHistoryScroll;
    private LinearLayout discordChatHistory;
    private TextView discordChatStatus;
    private EditText discordChatComposer;
    private EmbeddedTvKeyboardView discordChatKeyboard;
    private View menuSpacer;

    private List<OverlayMenuButton> verticalButtons;
    private List<Integer> verticalActions;
    private List<OverlayMenuButton> horizontalButtons;
    private List<Integer> horizontalActions;
    private List<OverlayMenuButton> discordButtons;
    private List<Integer> discordActions;

    private enum Region { VERTICAL, HORIZONTAL, DISCORD }
    enum OverlayMode { MENU, COMMUNITY }
    private enum CommunityFocus { RAIL, LIST, ACTION, CHAT_COMPOSER, CHAT_KEYBOARD }
    enum CommunitySubmode { ROOT, GUILD_CHANNELS, FRIEND_CHAT }
    private enum CommunityRowKind { INFO, FRIEND, GUILD, CHANNEL }
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
    private static final int ACTION_END_GAME = 17;
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
    private boolean discordFeatureEnabled = true;
    private boolean hostActionsAvailable = true;
    private boolean discordConfigured;
    private boolean discordLoading;
    private String discordError;
    private String discordMuteShortcut = "x";
    private String discordLeaveShortcut = "y";
    private final GuideShortcutLatch discordShortcutChord = new GuideShortcutLatch();
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
    private long lastCommunityScrollTime;
    private CommunityModel communityModel;
    private CommunitySubmode communitySubmode = CommunitySubmode.ROOT;
    private CommunitySection communitySection = CommunitySection.TOGETHER;
    private final List<CommunityRow> communityRows = new ArrayList<>();
    private int communityListIndex = -1;
    private String selectedTogetherRowId = "";
    private String selectedFriendRowId = "";
    private String selectedChannelsRootRowId = "";
    private String selectedGuildChannelRowId = "";
    private String selectedGuildChannelGuildId = "";
    private String selectedFriendId = "";
    private String selectedGuildId = "";
    private String selectedChannelId = "";
    private String pendingGuildId = "";
    private String pendingChatFriendId = "";
    private long communityRenderGeneration;
    private long communityInteractionGeneration;
    private long renderedCommunityModelRevision = Long.MIN_VALUE;
    private CommunitySection renderedCommunitySection;
    private CommunitySubmode renderedCommunitySubmode;
    private String renderedCommunityHeaderSignature = "";
    private String renderedChatRecipientId = "";
    private String renderedChatLastOutgoingMessageId = "";
    private String renderedChatStructureSignature = "";
    private String renderedChatDraft = "";
    private String renderedChatStatusSignature = "";
    private boolean communityCancelKeyDown;
    private boolean updatingChatComposer;
    private boolean installationConfirmationAvailable;
    private boolean endGameAvailable;
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
        // Content rows own selection. A ScrollView must never become one giant focus target.
        discordContentScroll.setFocusable(false);
        discordContentScroll.setFocusableInTouchMode(false);
        discordContentScroll.setOnFocusChangeListener((ignored, focused) -> {
            styleDiscordContentScroll(focused);
            if (focused && overlayMode == OverlayMode.COMMUNITY) {
                communityFocus = CommunityFocus.LIST;
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
        discordChatContainer = new LinearLayout(context);
        discordChatContainer.setOrientation(LinearLayout.VERTICAL);
        discordChatContainer.setVisibility(GONE);
        discordChatHistoryScroll = new ScrollView(context);
        discordChatHistoryScroll.setFillViewport(true);
        discordChatHistoryScroll.setVerticalScrollBarEnabled(false);
        discordChatHistoryScroll.setFocusable(false);
        discordChatHistory = new LinearLayout(context);
        discordChatHistory.setOrientation(LinearLayout.VERTICAL);
        discordChatHistoryScroll.addView(discordChatHistory, new ScrollView.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        discordChatContainer.addView(discordChatHistoryScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        discordQuickMain.addView(discordChatContainer, 2, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        discordActionsContainer = new LinearLayout(context);
        discordActionsContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams discordActionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        discordActionsParams.topMargin = dp(8);
        discordQuickMain.addView(discordActionsContainer, discordActionsParams);
        discordQuickFooter = new LinearLayout(context);
        discordQuickFooter.setOrientation(LinearLayout.HORIZONTAL);
        discordQuickFooter.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        discordQuickMain.addView(discordQuickFooter, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(18)));
        renderDiscordCommunityFooter();
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
        renderDiscordCommunityFooter();
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
        if (endGameAvailable) {
            addVerticalButton(R.drawable.ic_overlay_power,
                    getContext().getString(R.string.overlay_menu_end_game),
                    ACTION_END_GAME, spacing);
        }
        if (hostActionsAvailable) {
            addVerticalButton(R.drawable.ic_overlay_power,
                getContext().getString(R.string.overlay_menu_suspend_session),
                ACTION_SUSPEND_SESSION, spacing);
            addVerticalButton(R.drawable.ic_overlay_power,
                getContext().getString(R.string.overlay_menu_quit_session), ACTION_QUIT, spacing);
        }
        if (canShowDiscordCard()) {
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

        DiscordActionVoiceState actionVoice = discordActionVoiceState();
        boolean connected = actionVoice.connected;
        if (connected) {
            LinearLayout firstRow = new LinearLayout(getContext());
            firstRow.setOrientation(LinearLayout.HORIZONTAL);
            discordActionsContainer.addView(firstRow, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            discordMuteButton = addDiscordButton(firstRow, R.drawable.ic_overlay_microphone,
                    discordMuteLabel(actionVoice.muted), ACTION_DISCORD_MUTE, spacing);
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
        DiscordActionVoiceState actionVoice = discordActionVoiceState();
        String signature = discordQuickSection + ":" + discordConfigured + ":"
                + actionVoice.connected + ":" + actionVoice.muted + ":"
                + actionVoice.deafened + ":" + discordCanRejoin;
        if (signature.equals(renderedDiscordActionSignature)) return;
        renderedDiscordActionSignature = signature;
        int previous = discordIndex;
        long interactionGeneration = communityInteractionGeneration;
        buildDiscordActions(dp(BUTTON_SPACING_DP));
        if (communityFocus == CommunityFocus.ACTION && !discordButtons.isEmpty()) {
            post(() -> {
                if (overlayMode == OverlayMode.COMMUNITY
                        && interactionGeneration == communityInteractionGeneration
                        && communityFocus == CommunityFocus.ACTION && !discordButtons.isEmpty()) {
                    setDiscordIndex(Math.min(previous, discordButtons.size() - 1));
                }
            });
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
        button.setOnClickListener(v -> {
            if (overlayMode == OverlayMode.COMMUNITY) communityInteractionGeneration++;
            selectAndActivate(Region.DISCORD, index);
        });
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
        icon.setOnClickListener(ignored -> {
            if (overlayMode == OverlayMode.COMMUNITY) communityInteractionGeneration++;
            selectDiscordQuickSection(section);
        });
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
        CommunitySection nextSection = section == 1 ? CommunitySection.FRIENDS
                : section == 2 ? CommunitySection.CHANNELS : CommunitySection.TOGETHER;
        boolean sectionChanged = communitySection != nextSection;
        communitySection = nextSection;
        if (communitySubmode != CommunitySubmode.ROOT) communitySubmode = CommunitySubmode.ROOT;
        for (int index = 0; index < discordRailIcons.size(); index++) {
            styleDiscordRailIcon(discordRailIcons.get(index), index == section,
                    discordRailIcons.get(index).hasFocus());
        }
        renderedDiscordSocialRevision = Long.MIN_VALUE;
        renderedDiscordSocialSection = -1;
        renderDiscordCard();
        applyOverlayMode();
        if (discordContentScroll != null) discordContentScroll.scrollTo(0, 0);
        if (sectionChanged && actionListener != null) {
            actionListener.onDiscordCommunitySectionChanged(communitySection);
        }
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
        if (!canShowDiscordCard()) return;
        if (!communityOpenNotifies(overlayMode)) return;
        invalidateCommunityProjectionCache();
        overlayMode = OverlayMode.COMMUNITY;
        discordQuickSection = 0;
        communitySection = CommunitySection.TOGETHER;
        communitySubmode = CommunitySubmode.ROOT;
        communityMotionDirection = KeyEvent.KEYCODE_UNKNOWN;
        communityFocus = CommunityFocus.RAIL;
        activeRegion = Region.DISCORD;
        discordIndex = 0;
        applyOverlayMode();
        renderDiscordCard();
        if (actionListener != null) actionListener.onDiscordCommunityOpened();
        post(() -> {
            if (!discordRailIcons.isEmpty()) discordRailIcons.get(discordQuickSection).requestFocus();
        });
    }

    /** Opens an exact known friend after the controller has secured DM ownership. */
    public boolean openDiscordFriendChat(String friendId) {
        if (friendId == null || friendId.isEmpty()) return false;
        if (overlayMode == OverlayMode.MENU) openCommunity();
        if (overlayMode != OverlayMode.COMMUNITY || communityModel == null
                || !communityModel.directMessagesAvailable || actionListener == null) return false;
        boolean known = false;
        for (CommunityFriend friend : communityModel.friends) {
            if (friendId.equals(friend.id)) {
                known = true;
                break;
            }
        }
        if (!known) return false;
        communityInteractionGeneration++;
        communitySection = CommunitySection.FRIENDS;
        discordQuickSection = 1;
        selectedFriendId = friendId;
        pendingChatFriendId = friendId;
        communitySubmode = CommunitySubmode.FRIEND_CHAT;
        communityFocus = CommunityFocus.CHAT_COMPOSER;
        renderDiscordCard();
        actionListener.onDiscordCommunityOpenFriendChat(friendId);
        return true;
    }

    private void returnToMenu() {
        overlayMode = OverlayMode.MENU;
        communityMotionDirection = KeyEvent.KEYCODE_UNKNOWN;
        communityFocus = CommunityFocus.RAIL;
        communitySubmode = CommunitySubmode.ROOT;
        invalidateCommunityProjectionCache();
        restoreCommunityPanelSize();
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
        discordContainer.setVisibility(community && canShowDiscordCard() ? VISIBLE : GONE);
    }

    static OverlayMode backMode(OverlayMode mode) {
        return mode == OverlayMode.COMMUNITY ? OverlayMode.MENU : OverlayMode.MENU;
    }

    static boolean communityVisible(OverlayMode mode, boolean available) {
        return mode == OverlayMode.COMMUNITY && available;
    }

    static boolean communityOpenNotifies(OverlayMode mode) {
        return mode != OverlayMode.COMMUNITY;
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

    static int communityRailRightRegion(boolean hasRows, boolean hasActions) {
        if (hasRows) return COMMUNITY_REGION_CONTENT;
        return hasActions ? COMMUNITY_REGION_ACTION : COMMUNITY_REGION_RAIL;
    }

    static boolean communityBackNotifiesChannels(CommunitySubmode submode) {
        return submode == CommunitySubmode.GUILD_CHANNELS;
    }

    static boolean shouldExitCommunityChatForPause(OverlayMode mode, CommunitySubmode submode) {
        return mode == OverlayMode.COMMUNITY && submode == CommunitySubmode.FRIEND_CHAT;
    }

    static int communityContentScrollTarget(int currentScroll, int maxScroll, int delta) {
        return Math.max(0, Math.min(Math.max(0, maxScroll), currentScroll + delta));
    }

    static int communityActionMaxRows() { return 2; }
    static int communityQuickWidthDp() { return COMMUNITY_QUICK_WIDTH_DP; }
    static int communityQuickHeightDp() { return COMMUNITY_QUICK_HEIGHT_DP; }
    static int communityChatWidthDp() { return COMMUNITY_CHAT_WIDTH_DP; }
    static int communityChatHeightDp() { return COMMUNITY_CHAT_HEIGHT_DP; }
    static int communityRailIconCount() { return COMMUNITY_RAIL_ICON_COUNT; }
    static boolean communityContentUsesSingleScrollOwner() { return false; }
    static boolean communityContentUsesFocusableRows() { return true; }
    static int communityPanelDimension(int requested, int available) {
        return available > 0 ? Math.min(requested, available) : requested;
    }
    static int communityStableIndex(List<String> ids, String selectedId) {
        if (ids == null || ids.isEmpty()) return -1;
        int index = selectedId == null ? -1 : ids.indexOf(selectedId);
        return index >= 0 ? index : 0;
    }

    static boolean shouldAutoScrollCommunityChat(boolean sameRecipient, boolean nearBottom,
                                                  boolean newOutgoingMessage) {
        return !sameRecipient || nearBottom || newOutgoingMessage;
    }

    static boolean isCommunityNavigationKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_BUTTON_B
                || keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_BUTTON_R1;
    }

    static boolean communityRestoreStillCurrent(long scheduledGeneration, long currentGeneration,
                                                long scheduledRevision, long currentRevision,
                                                CommunitySection scheduledSection, CommunitySection currentSection,
                                                CommunitySubmode scheduledSubmode, CommunitySubmode currentSubmode,
                                                long scheduledInteraction, long currentInteraction) {
        return scheduledGeneration == currentGeneration && scheduledRevision == currentRevision
                && scheduledSection == currentSection && scheduledSubmode == currentSubmode
                && scheduledInteraction == currentInteraction;
    }

    static boolean shouldRenderCommunityProjection(long renderedRevision, long revision,
                                                   CommunitySection renderedSection,
                                                   CommunitySection section,
                                                   CommunitySubmode renderedSubmode,
                                                   CommunitySubmode submode) {
        return renderedRevision != revision || renderedSection != section || renderedSubmode != submode;
    }

    static int communityActionLeftRegion(int actionIndex, boolean hasRows) {
        if (actionIndex % 2 == 1) return COMMUNITY_REGION_ACTION;
        return hasRows ? COMMUNITY_REGION_CONTENT : COMMUNITY_REGION_RAIL;
    }

    static boolean communityCancelDownHasEffect(boolean alreadyDown, int repeatCount) {
        return !alreadyDown && repeatCount == 0;
    }

    static boolean communityListDownEntersActions(int listIndex, int rowCount, boolean hasActions) {
        return hasActions && rowCount > 0 && listIndex >= rowCount - 1;
    }

    static boolean communityActionUpEntersList(int actionIndex, boolean hasRows) {
        return hasRows && actionIndex >= 0 && actionIndex < 2;
    }

    static boolean shouldRouteCommunityChatKeyboard(OverlayMode mode, CommunitySubmode submode,
                                                    boolean keyboardVisible) {
        return mode == OverlayMode.COMMUNITY && submode == CommunitySubmode.FRIEND_CHAT
                && keyboardVisible;
    }

    static boolean shouldForwardCommunityChatKeyAction(int action) {
        return action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP;
    }

    static ControllerGlyphs.Button communityFooterButton(boolean confirmAction, boolean flipFaceButtons) {
        if (confirmAction) {
            return flipFaceButtons ? ControllerGlyphs.Button.CANCEL : ControllerGlyphs.Button.CONFIRM;
        }
        return flipFaceButtons ? ControllerGlyphs.Button.CONFIRM : ControllerGlyphs.Button.CANCEL;
    }

    static String communityChatStructureSignature(ChatModel chat) {
        if (chat == null) return "null";
        StringBuilder value = new StringBuilder();
        appendCommunitySignature(value, chat.recipientId);
        appendCommunitySignature(value, chat.recipientName);
        appendCommunitySignature(value, chat.recipientAvatarUrl);
        appendCommunitySignature(value, chat.loadingHistory ? "1" : "0");
        appendCommunitySignature(value, Integer.toString(chat.messages.size()));
        for (ChatMessage message : chat.messages) {
            appendCommunitySignature(value, message.id);
            appendCommunitySignature(value, message.content);
            appendCommunitySignature(value, message.additionalContentType);
            appendCommunitySignature(value, message.additionalContentTitle);
            appendCommunitySignature(value, Integer.toString(message.additionalContentCount));
            appendCommunitySignature(value, message.self ? "1" : "0");
            appendCommunitySignature(value, message.disclosure ? "1" : "0");
        }
        return value.toString();
    }

    static boolean shouldRebuildCommunityChat(String renderedSignature, ChatModel chat) {
        return renderedSignature == null
                || !renderedSignature.equals(communityChatStructureSignature(chat));
    }

    static String communityChatStatusSignature(ChatModel chat) {
        if (chat == null) return "null";
        return (chat.sending ? "1" : "0") + ":" + (chat.retryable ? "1" : "0") + ":"
                + chat.error.length() + ":" + chat.error + chat.retryMessage.length() + ":"
                + chat.retryMessage;
    }

    private static void appendCommunitySignature(StringBuilder target, String value) {
        String safe = value == null ? "" : value;
        target.append(safe.length()).append(':').append(safe);
    }

    public void setMenuActionListener(MenuActionListener listener) {
        this.actionListener = listener;
    }

    public void setHostActionsAvailable(boolean available) {
        hostActionsAvailable = available;
    }

    public void setEndGameAvailable(boolean available) {
        endGameAvailable = available;
    }

    public void setFlipFaceButtons(boolean flip) {
        this.flipFaceButtons = flip;
        renderDiscordCommunityFooter();
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

    /** Hides the entire Discord surface for stream profiles that cannot use it. */
    public void setDiscordFeatureEnabled(boolean enabled) {
        discordFeatureEnabled = enabled;
        if (!enabled && overlayMode == OverlayMode.COMMUNITY) {
            returnToMenu();
        }
        renderDiscordCard();
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

    /** Projects controller-owned Community state without doing network or Discord SDK work. */
    public void setDiscordCommunityModel(CommunityModel model) {
        if (model == null) {
            cancelCommunityChatKeyboardHold();
            communityModel = null;
            invalidateCommunityProjectionCache();
            renderDiscordCard();
            return;
        }
        if (communityModel != null && model.revision == communityModel.revision) return;
        if (communitySubmode == CommunitySubmode.FRIEND_CHAT
                && !communityChatRecipientId(communityModel).equals(communityChatRecipientId(model))) {
            cancelCommunityChatKeyboardHold();
        }
        communityModel = model;
        if (!model.selectedGuildId.isEmpty()) selectedGuildId = model.selectedGuildId;
        if (!pendingGuildId.isEmpty() && pendingGuildId.equals(model.selectedGuildId)) pendingGuildId = "";
        renderDiscordCard();
    }

    /** Lifecycle-only chat exit. Controller cleanup already ran, so no user callback fires. */
    public void exitDiscordCommunityChatForPause() {
        if (!shouldExitCommunityChatForPause(overlayMode, communitySubmode)) return;
        cancelCommunityChatKeyboardHold();
        pendingChatFriendId = "";
        communitySubmode = CommunitySubmode.ROOT;
        communitySection = CommunitySection.FRIENDS;
        discordQuickSection = 1;
        communityFocus = CommunityFocus.RAIL;
        restoreCommunityPanelSize();
        renderDiscordCard();
        post(() -> {
            if (!communityRows.isEmpty()) focusCommunityList();
            else focusCommunityRail();
        });
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
        discordContainer.setVisibility(communityVisible(overlayMode, canShowDiscordCard())
                ? VISIBLE : GONE);
        if (!canShowDiscordCard()) return;

        renderDiscordQuickHeaderIfChanged();
        ensureDiscordActions();
        if (communityModel != null) {
            if (shouldRenderCommunityProjection(renderedCommunityModelRevision,
                    communityModel.revision, renderedCommunitySection, communitySection,
                    renderedCommunitySubmode, communitySubmode)) {
                renderCommunityModel();
                renderedCommunityModelRevision = communityModel.revision;
                renderedCommunitySection = communitySection;
                renderedCommunitySubmode = communitySubmode;
            }
        }
        else {
            invalidateCommunityProjectionCache();
            renderDiscordVoiceCard();
            renderDiscordSocialCard();
        }
    }

    private void renderDiscordQuickHeaderIfChanged() {
        String signature = discordQuickSection + ":" + discordSocialConnected + ":"
                + discordSocialDisplayName + ":" + discordSocialAvatarUrl;
        if (signature.equals(renderedCommunityHeaderSignature)) return;
        renderedCommunityHeaderSignature = signature;
        renderDiscordQuickHeader();
    }

    private void renderDiscordCommunityFooter() {
        if (discordQuickFooter == null) return;
        discordQuickFooter.removeAllViews();
        addDiscordCommunityFooterItem(true, R.string.discord_community_open, dp(14));
        addDiscordCommunityFooterItem(false, R.string.discord_community_back, 0);
    }

    private void addDiscordCommunityFooterItem(boolean confirmAction, int labelResource,
                                               int rightMargin) {
        TextView glyph = new TextView(getContext());
        glyph.setText(ControllerGlyphs.text(playStationButtons,
                communityFooterButton(confirmAction, flipFaceButtons)));
        glyph.setTextColor(0xFFAEB3C2);
        glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        glyph.setGravity(Gravity.CENTER);
        glyph.setTypeface(ControllerGlyphs.typeface(getContext()));
        discordQuickFooter.addView(glyph, new LinearLayout.LayoutParams(dp(18), dp(18)));

        TextView label = new TextView(getContext());
        label.setText(labelResource);
        label.setTextColor(0xFFAEB3C2);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setSingleLine(true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(18));
        labelParams.leftMargin = dp(3);
        labelParams.rightMargin = rightMargin;
        discordQuickFooter.addView(label, labelParams);
    }

    private void invalidateCommunityProjectionCache() {
        renderedCommunityModelRevision = Long.MIN_VALUE;
        renderedCommunitySection = null;
        renderedCommunitySubmode = null;
        renderedCommunityHeaderSignature = "";
        renderedChatStructureSignature = "";
        renderedChatDraft = "";
        renderedChatStatusSignature = "";
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

    private void renderCommunityModel() {
        CommunityFocus focusBeforeRender = communityFocus;
        String rowBeforeRender = selectedCommunityRowId();
        int actionBeforeRender = discordIndex;
        long interactionBeforeRender = communityInteractionGeneration;
        long revision = communityModel.revision;
        CommunitySection section = communitySection;
        CommunitySubmode submode = communitySubmode;
        if (communitySubmode == CommunitySubmode.FRIEND_CHAT) {
            if (!shouldRebuildCommunityChat(renderedChatStructureSignature, communityModel.chat)
                    && discordChatContainer.getVisibility() == VISIBLE) {
                ensureCommunityChatComposer(communityModel.chat);
                bindCommunityChatStatus(communityModel.chat);
                return;
            }
            long generation = ++communityRenderGeneration;
            renderCommunityChat(generation);
            postCommunityFocusRestore(generation, revision, section, submode,
                    interactionBeforeRender, focusBeforeRender, rowBeforeRender, actionBeforeRender);
            return;
        }
        long generation = ++communityRenderGeneration;
        restoreCommunityPanelSize();
        discordRail.setVisibility(VISIBLE);
        discordQuickHeader.setVisibility(VISIBLE);
        discordContentScroll.setVisibility(VISIBLE);
        discordActionsContainer.setVisibility(VISIBLE);
        discordQuickFooter.setVisibility(VISIBLE);
        discordChatContainer.setVisibility(GONE);
        discordVoiceContentContainer.removeAllViews();
        discordSocialContentContainer.removeAllViews();
        communityRows.clear();
        communityListIndex = -1;
        if (communitySection == CommunitySection.TOGETHER) renderCommunityTogether();
        else if (communitySection == CommunitySection.FRIENDS) renderCommunityFriends();
        else renderCommunityChannels();
        restoreCommunityRowSelection();
        postCommunityFocusRestore(generation, revision, section, submode,
                interactionBeforeRender, focusBeforeRender, rowBeforeRender, actionBeforeRender);
    }

    private void postCommunityFocusRestore(long generation, long revision, CommunitySection section,
                                           CommunitySubmode submode, long interactionGeneration,
                                           CommunityFocus focus, String rowId, int actionIndex) {
        post(() -> {
            if (overlayMode != OverlayMode.COMMUNITY || getVisibility() != VISIBLE
                    || communityModel == null || !communityRestoreStillCurrent(generation,
                    communityRenderGeneration, revision, communityModel.revision, section,
                    communitySection, submode, communitySubmode, interactionGeneration,
                    communityInteractionGeneration)) return;
            if (focus == CommunityFocus.LIST && submode != CommunitySubmode.FRIEND_CHAT) {
                int index = communityRowIndex(rowId);
                if (index < 0 && !communityRows.isEmpty()) index = 0;
                if (index >= 0) setCommunityListIndex(index);
                else if (!discordButtons.isEmpty()) {
                    communityFocus = CommunityFocus.ACTION;
                    setDiscordIndex(0);
                } else focusCommunityRail();
            } else if (focus == CommunityFocus.ACTION && !discordButtons.isEmpty()
                    && submode != CommunitySubmode.FRIEND_CHAT) {
                communityFocus = CommunityFocus.ACTION;
                setDiscordIndex(Math.max(0, Math.min(actionIndex, discordButtons.size() - 1)));
            } else if (focus == CommunityFocus.CHAT_COMPOSER
                    || focus == CommunityFocus.CHAT_KEYBOARD) {
                if (discordChatComposer != null) discordChatComposer.requestFocus();
            } else {
                focusCommunityRail();
            }
        });
    }

    private boolean isCurrentCommunityRender(long generation, long revision,
                                             CommunitySection section, CommunitySubmode submode) {
        return overlayMode == OverlayMode.COMMUNITY && getVisibility() == VISIBLE
                && communityModel != null && communityModel.revision == revision
                && communityRenderGeneration == generation && communitySection == section
                && communitySubmode == submode;
    }

    private void renderCommunityTogether() {
        VoiceSummary voice = communityModel.voice;
        if (voice == null || !voice.connected) {
            addCommunityStateLine(communityModel.gatewayStatus, communityModel.gatewayMessage,
                    getContext().getString(R.string.overlay_discord_disconnected));
            return;
        }
        String location = voice.guildName.isEmpty() ? voice.channelName
                : voice.guildName + "  ·  " + voice.channelName;
        discordVoiceContentContainer.addView(discordLine(location, 16, Color.WHITE, true));
        String state = voice.deafened ? getContext().getString(R.string.overlay_discord_deafened)
                : voice.muted ? getContext().getString(R.string.overlay_discord_muted)
                : getContext().getString(R.string.overlay_community_active);
        discordVoiceContentContainer.addView(discordLine(state, 12, 0xFF69F0AE, false));
        if (voice.participants.isEmpty()) {
            discordVoiceContentContainer.addView(discordLine(getContext().getString(
                    R.string.overlay_discord_empty), 13, 0xFF9FA3B2, false));
            return;
        }
        for (VoiceParticipant participant : voice.participants) {
            String title = participant.self ? getContext().getString(R.string.overlay_discord_self,
                    participant.name) : participant.name;
            String details = participant.speaking ? getContext().getString(
                    R.string.overlay_discord_participant_speaking, "")
                    : participant.muted ? getContext().getString(R.string.overlay_discord_muted)
                    : participant.volume != 100 ? participant.volume + "%" : "";
            addCommunityRow("voice:" + participant.id, CommunityRowKind.INFO, title, details, participant);
        }
    }

    private void renderCommunityFriends() {
        if (communityModel.socialStatus != CommunityStatus.READY) {
            addCommunityStateLine(communityModel.socialStatus, communityModel.socialMessage,
                    getContext().getString(R.string.overlay_discord_social_connect_in_menu));
            return;
        }
        addCommunityFriends(FriendPresence.PLAYING, R.string.overlay_discord_social_playing);
        addCommunityFriends(FriendPresence.ONLINE, R.string.overlay_discord_social_online);
        addCommunityFriends(FriendPresence.OFFLINE, R.string.discord_community_offline);
        if (communityRows.isEmpty()) {
            discordVoiceContentContainer.addView(discordLine(getContext().getString(
                    R.string.overlay_discord_social_no_friends), 13, 0xFF9FA3B2, false));
        }
    }

    private void addCommunityFriends(FriendPresence presence, int heading) {
        boolean added = false;
        for (CommunityFriend friend : communityModel.friends) {
            if (friend.presence != presence) continue;
            if (!added) {
                discordVoiceContentContainer.addView(discordLine(getContext().getString(heading),
                        12, 0xFF9FA3B2, true));
                added = true;
            }
            String details = communityFriendDetails(friend);
            addCommunityRow("friend:" + friend.id, CommunityRowKind.FRIEND, friend.name, details, friend);
        }
    }

    private void renderCommunityChannels() {
        if (communitySubmode == CommunitySubmode.GUILD_CHANNELS) {
            renderCommunityGuildChannels();
            return;
        }
        if (communityModel.gatewayStatus != CommunityStatus.READY) {
            addCommunityStateLine(communityModel.gatewayStatus, communityModel.gatewayMessage,
                    getContext().getString(R.string.discord_community_host_unavailable));
            return;
        }
        addCommunityChannels(communityModel.favorites, R.string.discord_community_active,
                "favorite-channel:");
        addCommunityChannels(communityModel.recent, R.string.discord_community_recent,
                "recent-channel:");
        if (!communityModel.guilds.isEmpty()) {
            discordVoiceContentContainer.addView(discordLine(getContext().getString(
                    R.string.discord_community_servers), 12, 0xFF9FA3B2, true));
            for (CommunityGuild guild : communityModel.guilds) {
                addCommunityRow("guild:" + guild.id, CommunityRowKind.GUILD, guild.name, "", guild);
            }
        }
        if (communityRows.isEmpty()) discordVoiceContentContainer.addView(discordLine(getContext().getString(
                R.string.discord_no_channels), 13, 0xFF9FA3B2, false));
    }

    private void renderCommunityGuildChannels() {
        CommunityGuild guild = findGuild(selectedGuildId);
        String title = guild == null ? getContext().getString(R.string.discord_community_servers) : guild.name;
        discordVoiceContentContainer.addView(discordLine(title, 16, Color.WHITE, true));
        if (!pendingGuildId.isEmpty()) {
            discordVoiceContentContainer.addView(discordLine(getContext().getString(
                    R.string.overlay_discord_loading), 13, 0xFFC5C8D3, false));
            return;
        }
        if (communityModel.gatewayStatus == CommunityStatus.ERROR) {
            addCommunityStateLine(communityModel.gatewayStatus, communityModel.gatewayMessage,
                    getContext().getString(R.string.discord_no_channels));
            return;
        }
        if (communityModel.guildChannels.isEmpty()) {
            discordVoiceContentContainer.addView(discordLine(getContext().getString(
                    R.string.discord_no_channels), 13, 0xFF9FA3B2, false));
            return;
        }
        for (CommunityChannel channel : communityModel.guildChannels) {
            String details = channel.people >= 0 ? channel.people + "" : "";
            addCommunityRow("guild-channel:" + channel.id, CommunityRowKind.CHANNEL,
                    "#  " + channel.name, details, channel);
        }
    }

    private void addCommunityChannels(List<CommunityChannel> channels, int heading, String stablePrefix) {
        if (channels.isEmpty()) return;
        discordVoiceContentContainer.addView(discordLine(getContext().getString(heading),
                12, 0xFF9FA3B2, true));
        for (CommunityChannel channel : channels) {
            String details = channel.guildName + (channel.people >= 0 ? "  ·  " + channel.people : "");
            addCommunityRow(stablePrefix + channel.id, CommunityRowKind.CHANNEL,
                    "#  " + channel.name, details, channel);
        }
    }

    private void addCommunityStateLine(CommunityStatus status, String message, String fallback) {
        String value = message.isEmpty() ? fallback : message;
        int color = status == CommunityStatus.ERROR ? 0xFFFFB4AB
                : status == CommunityStatus.READY ? 0xFF69F0AE : 0xFFC5C8D3;
        discordVoiceContentContainer.addView(discordLine(value, 14, color, false));
    }

    private void addCommunityRow(String id, CommunityRowKind kind, String title, String details, Object source) {
        LinearLayout row = new LinearLayout(getContext());
        CommunityFriend friend = source instanceof CommunityFriend ? (CommunityFriend) source : null;
        VoiceParticipant participant = source instanceof VoiceParticipant ? (VoiceParticipant) source : null;
        boolean person = friend != null || participant != null;
        row.setOrientation(person ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        if (person) row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(7), dp(10), dp(7));
        row.setMinimumHeight(dp(48));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        String description = details.isEmpty() ? title : title + ". " + details;
        if (friend != null && friend.unread) description += ". "
                + getContext().getString(R.string.overlay_discord_unread);
        row.setContentDescription(description);
        if (person) {
            String avatarTitle = friend != null ? friend.name : participant.name;
            String avatarUrl = friend != null ? friend.avatarUrl : participant.avatarUrl;
            FrameLayout avatar = DiscordCommunityPresentation.avatar(getContext(), avatarTitle, avatarUrl, 34);
            if (friend != null && communityFriendShowsPresenceDot(friend.presence)) {
                addCommunityPresenceDot(avatar);
            }
            if (friend != null && friend.presence == FriendPresence.OFFLINE) {
                avatar.setAlpha(0.58f);
            }
            LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(34), dp(34));
            avatarParams.rightMargin = dp(10);
            row.addView(avatar, avatarParams);
        }
        LinearLayout copy = person ? new LinearLayout(getContext()) : row;
        if (person) copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleRow = new LinearLayout(getContext());
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(getContext());
        name.setText(friend != null && friend.unread ? communityUnreadTitle(title) : title);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTextColor(0xFFEDEAF5);
        name.setSingleLine(true);
        titleRow.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        copy.addView(titleRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        if (!details.isEmpty()) {
            TextView meta = new TextView(getContext());
            meta.setText(details);
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            meta.setTextColor(friend != null && friend.presence == FriendPresence.OFFLINE
                    ? 0xFF777C89 : 0xFF9FA3B2);
            meta.setSingleLine(true);
            copy.addView(meta, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        if (person) row.addView(copy, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        CommunityRow item = new CommunityRow(id, kind, source, row);
        int index = communityRows.size();
        communityRows.add(item);
        row.setOnFocusChangeListener((ignored, focused) -> {
            styleCommunityRow(row, focused);
            if (focused) {
                communityFocus = CommunityFocus.LIST;
                communityListIndex = index;
                rememberCommunitySelection(item);
            }
        });
        row.setOnClickListener(ignored -> {
            communityInteractionGeneration++;
            openCommunityRow(item);
        });
        styleCommunityRow(row, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(4);
        discordVoiceContentContainer.addView(row, params);
    }

    private void addCommunityPresenceDot(FrameLayout avatar) {
        View dot = new View(getContext());
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0xFF61E594);
        background.setStroke(dp(1), 0xFF10131C);
        dot.setBackground(background);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(8), dp(8),
                Gravity.RIGHT | Gravity.BOTTOM);
        avatar.addView(dot, params);
    }

    private CharSequence communityUnreadTitle(String title) {
        SpannableStringBuilder value = new SpannableStringBuilder(title).append("  ●");
        value.setSpan(new ForegroundColorSpan(0xFF5BCBFF), title.length() + 2, value.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return value;
    }

    static boolean communityFriendShowsPresenceDot(FriendPresence presence) {
        return presence == FriendPresence.ONLINE || presence == FriendPresence.PLAYING;
    }

    static String communityFriendDetails(CommunityFriend friend) {
        return friend == null ? "" : friend.activity;
    }

    private void styleCommunityRow(View row, boolean focused) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(9));
        background.setColor(focused ? 0xFF1B5A78 : 0x142D5F78);
        background.setStroke(focused ? dp(2) : dp(1), focused ? 0xFF7CE4FF : 0x20FFFFFF);
        row.setBackground(background);
    }

    private void rememberCommunitySelection(CommunityRow item) {
        if (communitySection == CommunitySection.TOGETHER) selectedTogetherRowId = item.id;
        else if (communitySection == CommunitySection.FRIENDS) selectedFriendRowId = item.id;
        else if (communitySubmode == CommunitySubmode.GUILD_CHANNELS) {
            selectedGuildChannelRowId = item.id;
            selectedGuildChannelGuildId = selectedGuildId;
        } else selectedChannelsRootRowId = item.id;
        if (item.kind == CommunityRowKind.FRIEND) selectedFriendId = idPart(item.id);
        else if (item.kind == CommunityRowKind.GUILD) selectedGuildId = idPart(item.id);
        else if (item.kind == CommunityRowKind.CHANNEL) selectedChannelId = idPart(item.id);
    }

    private void openCommunityRow(CommunityRow row) {
        rememberCommunitySelection(row);
        if (actionListener == null) return;
        if (row.kind == CommunityRowKind.FRIEND) {
            if (!communityModel.directMessagesAvailable) {
                actionListener.onDiscordCommunityAuthorizeDirectMessages();
                return;
            }
            pendingChatFriendId = selectedFriendId;
            communitySubmode = CommunitySubmode.FRIEND_CHAT;
            communityFocus = CommunityFocus.CHAT_COMPOSER;
            renderDiscordCard();
            actionListener.onDiscordCommunityOpenFriendChat(selectedFriendId);
        } else if (row.kind == CommunityRowKind.GUILD) {
            pendingGuildId = selectedGuildId;
            communitySubmode = CommunitySubmode.GUILD_CHANNELS;
            renderDiscordCard();
            actionListener.onDiscordCommunityLoadGuild(selectedGuildId);
        } else if (row.kind == CommunityRowKind.CHANNEL && !selectedChannelId.isEmpty()) {
            actionListener.onDiscordCommunityJoinChannel(selectedChannelId);
        }
    }

    private void restoreCommunityRowSelection() {
        int index = communityRowIndex(selectedCommunityRowId());
        if (index >= 0) {
            communityListIndex = index;
            return;
        }
        if (!communityRows.isEmpty()) communityListIndex = 0;
    }

    private String selectedCommunityRowId() {
        if (communitySection == CommunitySection.TOGETHER) return selectedTogetherRowId;
        if (communitySection == CommunitySection.FRIENDS) return selectedFriendRowId;
        if (communitySubmode == CommunitySubmode.GUILD_CHANNELS) {
            return selectedGuildId.equals(selectedGuildChannelGuildId) ? selectedGuildChannelRowId : "";
        }
        return selectedChannelsRootRowId;
    }

    private int communityRowIndex(String id) {
        if (id == null || id.isEmpty()) return -1;
        for (int index = 0; index < communityRows.size(); index++) {
            if (id.equals(communityRows.get(index).id)) return index;
        }
        return -1;
    }

    private CommunityGuild findGuild(String id) {
        for (CommunityGuild guild : communityModel.guilds) if (guild.id.equals(id)) return guild;
        return null;
    }

    private static String idPart(String stableId) {
        int colon = stableId.indexOf(':');
        return colon < 0 ? stableId : stableId.substring(colon + 1);
    }

    private static final class CommunityRow {
        final String id;
        final CommunityRowKind kind;
        final Object source;
        final View view;
        CommunityRow(String id, CommunityRowKind kind, Object source, View view) {
            this.id = id; this.kind = kind; this.source = source; this.view = view;
        }
    }

    private void renderCommunityChat(long generation) {
        ChatModel chat = communityModel.chat;
        renderedChatStructureSignature = communityChatStructureSignature(chat);
        String recipientId = chat != null && !chat.recipientId.isEmpty()
                ? chat.recipientId : pendingChatFriendId;
        boolean sameRecipient = recipientId.equals(renderedChatRecipientId);
        int previousScroll = discordChatHistoryScroll == null ? 0 : discordChatHistoryScroll.getScrollY();
        View previousHistory = discordChatHistoryScroll == null ? null : discordChatHistoryScroll.getChildAt(0);
        int previousMaxScroll = previousHistory == null ? 0
                : Math.max(0, previousHistory.getHeight() - discordChatHistoryScroll.getHeight());
        boolean nearBottom = previousMaxScroll - previousScroll <= dp(48);
        String lastOutgoingMessageId = lastOutgoingMessageId(chat);
        boolean newOutgoingMessage = sameRecipient && !lastOutgoingMessageId.isEmpty()
                && !lastOutgoingMessageId.equals(renderedChatLastOutgoingMessageId);
        boolean autoScroll = shouldAutoScrollCommunityChat(sameRecipient, nearBottom, newOutgoingMessage);
        applyCommunityChatPanelSize();
        discordRail.setVisibility(GONE);
        discordQuickHeader.setVisibility(GONE);
        discordContentScroll.setVisibility(GONE);
        discordActionsContainer.setVisibility(GONE);
        discordQuickFooter.setVisibility(GONE);
        discordChatContainer.setVisibility(VISIBLE);
        discordChatContainer.removeAllViews();
        String recipient = chat != null && !chat.recipientName.isEmpty() ? chat.recipientName
                : selectedFriendName(pendingChatFriendId);
        TextView title = new TextView(getContext());
        title.setText(recipient.isEmpty() ? getContext().getString(R.string.discord_community_friends) : recipient);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTextColor(Color.WHITE);
        title.setSingleLine(true);
        discordChatContainer.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));
        discordChatHistoryScroll = new ScrollView(getContext());
        discordChatHistoryScroll.setFillViewport(true);
        discordChatHistoryScroll.setVerticalScrollBarEnabled(false);
        discordChatHistoryScroll.setFocusable(false);
        discordChatHistory = new LinearLayout(getContext());
        discordChatHistory.setOrientation(LinearLayout.VERTICAL);
        discordChatHistory.setMinimumHeight(dp(140));
        discordChatHistory.setPadding(0, dp(4), 0, dp(4));
        discordChatHistoryScroll.addView(discordChatHistory, new ScrollView.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        renderCommunityChatHistory(chat);
        discordChatContainer.addView(discordChatHistoryScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        ensureCommunityChatComposer(chat);
        ensureCommunityChatStatus();
        bindCommunityChatStatus(chat);
        discordChatContainer.addView(discordChatStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        discordChatContainer.addView(discordChatComposer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        discordChatKeyboard.setCompactMode(true);
        discordChatContainer.addView(discordChatKeyboard, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        renderedChatRecipientId = recipientId;
        renderedChatLastOutgoingMessageId = lastOutgoingMessageId;
        ScrollView renderedHistory = discordChatHistoryScroll;
        renderedHistory.post(() -> {
            if (overlayMode != OverlayMode.COMMUNITY
                    || communitySubmode != CommunitySubmode.FRIEND_CHAT
                    || communityRenderGeneration != generation
                    || renderedHistory != discordChatHistoryScroll) return;
            View history = renderedHistory.getChildAt(0);
            int maxScroll = history == null ? 0
                    : Math.max(0, history.getHeight() - renderedHistory.getHeight());
            if (autoScroll) renderedHistory.scrollTo(0, maxScroll);
            else renderedHistory.scrollTo(0, Math.min(previousScroll, maxScroll));
        });
    }

    private static String lastOutgoingMessageId(ChatModel chat) {
        if (chat == null) return "";
        for (int index = chat.messages.size() - 1; index >= 0; index--) {
            ChatMessage message = chat.messages.get(index);
            if (message.self && !message.disclosure) return message.id;
        }
        return "";
    }

    private void ensureCommunityChatStatus() {
        if (discordChatStatus != null) return;
        discordChatStatus = discordLine("", 11, 0xFFC5C8D3, false);
    }

    private void bindCommunityChatStatus(ChatModel chat) {
        ensureCommunityChatStatus();
        String signature = communityChatStatusSignature(chat);
        if (signature.equals(renderedChatStatusSignature)) return;
        renderedChatStatusSignature = signature;
        if (chat != null && chat.sending) {
            discordChatStatus.setText(R.string.discord_dm_sending);
            discordChatStatus.setTextColor(0xFFC5C8D3);
            discordChatStatus.setVisibility(VISIBLE);
            return;
        }
        if (chat != null && (!chat.error.isEmpty()
                || (chat.retryable && !chat.retryMessage.isEmpty()))) {
            discordChatStatus.setText(!chat.retryMessage.isEmpty() ? chat.retryMessage : chat.error);
            discordChatStatus.setTextColor(0xFFFFB4AB);
            discordChatStatus.setVisibility(VISIBLE);
            return;
        }
        discordChatStatus.setText("");
        discordChatStatus.setVisibility(GONE);
    }

    private String selectedFriendName(String id) {
        for (CommunityFriend friend : communityModel.friends) if (friend.id.equals(id)) return friend.name;
        return "";
    }

    private static String communityChatRecipientId(CommunityModel model) {
        return model == null || model.chat == null ? "" : model.chat.recipientId;
    }

    private void cancelCommunityChatKeyboardHold() {
        communityMotionDirection = KeyEvent.KEYCODE_UNKNOWN;
        if (discordChatKeyboard != null) discordChatKeyboard.cancelKeyboardHold();
    }

    private void renderCommunityChatHistory(ChatModel chat) {
        if (chat == null || chat.loadingHistory) {
            discordChatHistory.addView(discordLine(getContext().getString(R.string.overlay_discord_loading),
                    13, 0xFFC5C8D3, false));
            return;
        }
        if (chat.messages.isEmpty()) {
            discordChatHistory.addView(discordLine(getContext().getString(R.string.overlay_discord_chat_empty),
                    12, 0xFF9FA3B2, false));
            return;
        }
        for (ChatMessage message : chat.messages) {
            if (message.disclosure) {
                TextView notice = discordLine(getContext().getString(R.string.discord_dm_disclosure),
                        11, 0xFF9FA3B2, false);
                notice.setGravity(Gravity.CENTER);
                discordChatHistory.addView(notice);
                continue;
            }
            if (!message.content.isEmpty()) addCommunityChatBubble(message.content, message.self, false, message.id);
            if (!message.additionalContentType.isEmpty() || message.additionalContentCount > 0) {
                addCommunityChatBubble(additionalContentLabel(message), message.self, true, message.id);
            } else if (message.content.isEmpty()) {
                addCommunityChatBubble(getContext().getString(R.string.discord_dm_unsupported_content),
                        message.self, true, message.id);
            }
        }
    }

    private void addCommunityChatBubble(String value, boolean self, boolean media, String messageId) {
        TextView bubble = new TextView(getContext());
        bubble.setText(value);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, media ? 11 : 13);
        bubble.setTextColor(media ? 0xFF7CE4FF : Color.WHITE);
        bubble.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(12));
        background.setColor(self ? 0xFF1F6388 : 0xFF243041);
        bubble.setBackground(background);
        if (media && !messageId.isEmpty()) {
            bubble.setFocusable(true);
            bubble.setClickable(true);
            bubble.setOnClickListener(ignored -> {
                if (actionListener != null) actionListener.onDiscordCommunityOpenMessageInDiscord(messageId);
            });
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = self ? Gravity.RIGHT : Gravity.LEFT;
        params.topMargin = dp(3);
        discordChatHistory.addView(bubble, params);
    }

    private String additionalContentLabel(ChatMessage message) {
        int count = Math.max(1, message.additionalContentCount);
        int stringId;
        switch (message.additionalContentType) {
            case "Attachment": stringId = R.string.discord_dm_media_attachment; break;
            case "Poll": stringId = R.string.discord_dm_media_poll; break;
            case "VoiceMessage": stringId = R.string.discord_dm_media_voice_message; break;
            case "Thread": stringId = R.string.discord_dm_media_thread; break;
            case "Embed": stringId = R.string.discord_dm_media_embed; break;
            case "Sticker": stringId = R.string.discord_dm_media_sticker; break;
            default: stringId = R.string.discord_dm_media_other; break;
        }
        String label = getContext().getString(stringId, count);
        return message.additionalContentTitle.isEmpty() ? label : getContext().getString(
                R.string.discord_dm_media_titled, label, message.additionalContentTitle);
    }

    private void ensureCommunityChatComposer(ChatModel chat) {
        if (discordChatComposer == null) {
            discordChatComposer = new EditText(getContext());
            discordChatComposer.setTextColor(Color.WHITE);
            discordChatComposer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            discordChatComposer.setHintTextColor(0xFFA2A9BB);
            discordChatComposer.setHint(R.string.discord_dm_placeholder);
            discordChatComposer.setSingleLine(true);
            discordChatComposer.setShowSoftInputOnFocus(false);
            discordChatComposer.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
            discordChatComposer.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                    if (!updatingChatComposer && actionListener != null && !pendingChatFriendId.isEmpty()) {
                        actionListener.onDiscordCommunityChatDraftChanged(pendingChatFriendId, value.toString());
                    }
                }
                @Override public void afterTextChanged(Editable value) { }
            });
        }
        String draft = chat == null ? "" : chat.draft;
        if (!draft.equals(renderedChatDraft)) {
            if (!draft.equals(discordChatComposer.getText().toString())) {
                updatingChatComposer = true;
                discordChatComposer.setText(draft);
                discordChatComposer.setSelection(draft.length());
                updatingChatComposer = false;
            }
            renderedChatDraft = draft;
        }
        if (discordChatKeyboard == null) {
            discordChatKeyboard = new EmbeddedTvKeyboardView(getContext(), new EmbeddedTvKeyboardView.Callback() {
                @Override public void onText(String value) { insertCommunityChatText(value); }
                @Override public void onBackspace() { deleteCommunityChatText(); }
                @Override public void onMoveCursor(int direction) { moveCommunityChatCursor(direction); }
                @Override public void onMicrophone() { }
                @Override public void onSend() { sendCommunityChat(); }
                @Override public void onOpenVisibleAdditionalContent() { openVisibleCommunityChatContent(); }
            }, getContext().getString(R.string.discord_dm_keyboard_microphone),
                    getContext().getString(R.string.discord_dm_keyboard_space),
                    getContext().getString(R.string.discord_dm_send),
                    getContext().getString(R.string.discord_dm_keyboard_shift_legend),
                    getContext().getString(R.string.discord_dm_keyboard_backspace_legend),
                    getContext().getString(R.string.discord_dm_keyboard_cursor_legend),
                    getContext().getString(R.string.discord_dm_keyboard_send_legend),
                    getContext().getString(R.string.discord_dm_keyboard_history_scroll_legend),
                    getContext().getString(R.string.discord_dm_keyboard_media_open_legend));
        }
    }

    private void insertCommunityChatText(String value) {
        if (discordChatComposer == null) return;
        Editable editable = discordChatComposer.getText();
        int start = Math.max(0, discordChatComposer.getSelectionStart());
        int end = Math.max(start, discordChatComposer.getSelectionEnd());
        editable.replace(start, end, value == null ? "" : value);
        discordChatComposer.setSelection(Math.min(editable.length(), start + (value == null ? 0 : value.length())));
    }

    private void deleteCommunityChatText() {
        if (discordChatComposer == null) return;
        Editable editable = discordChatComposer.getText();
        int start = Math.max(0, discordChatComposer.getSelectionStart());
        int end = Math.max(start, discordChatComposer.getSelectionEnd());
        if (start != end) editable.delete(start, end);
        else if (start > 0) {
            int previous = Character.offsetByCodePoints(editable, start, -1);
            editable.delete(previous, start);
        }
    }

    private void moveCommunityChatCursor(int direction) {
        if (discordChatComposer == null) return;
        int current = Math.max(0, discordChatComposer.getSelectionStart());
        Editable editable = discordChatComposer.getText();
        int next = direction < 0 && current > 0 ? Character.offsetByCodePoints(editable, current, -1)
                : direction > 0 && current < editable.length() ? Character.offsetByCodePoints(editable, current, 1)
                : current;
        discordChatComposer.setSelection(next);
    }

    private void sendCommunityChat() {
        if (discordChatComposer == null || actionListener == null || pendingChatFriendId.isEmpty()) return;
        String draft = discordChatComposer.getText().toString();
        if (!draft.trim().isEmpty()) actionListener.onDiscordCommunitySendChat(pendingChatFriendId, draft);
    }

    private void openVisibleCommunityChatContent() {
        if (discordChatHistory == null || actionListener == null) return;
        int childCount = discordChatHistory.getChildCount();
        int[] tops = new int[childCount];
        int[] bottoms = new int[childCount];
        View[] candidates = new View[childCount];
        int candidateCount = 0;
        for (int index = 0; index < childCount; index++) {
            View child = discordChatHistory.getChildAt(index);
            if (!child.isFocusable() || !child.isClickable()) continue;
            android.graphics.Rect bounds = new android.graphics.Rect();
            child.getDrawingRect(bounds);
            discordChatHistoryScroll.offsetDescendantRectToMyCoords(child, bounds);
            tops[candidateCount] = bounds.top;
            bottoms[candidateCount] = bounds.bottom;
            candidates[candidateCount] = child;
            candidateCount++;
        }
        int selected = nearestVisibleCommunityMediaIndex(discordChatHistoryScroll.getScrollY(),
                discordChatHistoryScroll.getHeight(), tops, bottoms, candidateCount);
        if (selected >= 0) candidates[selected].performClick();
    }

    /** R3 must never open a stale CTA outside the currently visible chat viewport. */
    static int nearestVisibleCommunityMediaIndex(int scrollTop, int viewportHeight,
                                                  int[] tops, int[] bottoms, int count) {
        if (tops == null || bottoms == null || viewportHeight <= 0) return -1;
        int limit = Math.min(Math.min(tops.length, bottoms.length), Math.max(0, count));
        int scrollBottom = scrollTop + viewportHeight;
        int preferredCenter = scrollTop + Math.round(viewportHeight * .65f);
        int selected = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int index = 0; index < limit; index++) {
            if (bottoms[index] <= scrollTop || tops[index] >= scrollBottom) continue;
            int center = tops[index] + (bottoms[index] - tops[index]) / 2;
            int distance = Math.abs(center - preferredCenter);
            if (distance < bestDistance) {
                selected = index;
                bestDistance = distance;
            }
        }
        return selected;
    }

    private void applyCommunityChatPanelSize() { applyCommunityPanelSize(COMMUNITY_CHAT_WIDTH_DP, COMMUNITY_CHAT_HEIGHT_DP); }
    private void restoreCommunityPanelSize() { applyCommunityPanelSize(COMMUNITY_QUICK_WIDTH_DP, COMMUNITY_QUICK_HEIGHT_DP); }

    private void applyCommunityPanelSize(int widthDp, int heightDp) {
        if (discordContainer == null || !(discordContainer.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) discordContainer.getLayoutParams();
        int desiredWidth = dp(widthDp);
        int desiredHeight = dp(heightDp);
        View root = getRootView();
        int availableWidth = root == null ? 0 : root.getWidth() - dp(40);
        int availableHeight = root == null ? 0 : root.getHeight() - dp(40);
        params.width = communityPanelDimension(desiredWidth, availableWidth);
        params.height = communityPanelDimension(desiredHeight, availableHeight);
        discordContainer.setLayoutParams(params);
        if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) {
            post(() -> applyCommunityPanelSize(widthDp, heightDp));
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
        DiscordActionVoiceState actionVoice = discordActionVoiceState();
        boolean connected = actionVoice.connected;
        if (discordMuteButton != null) {
            discordMuteButton.setLabel(discordMuteLabel(actionVoice.muted));
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

    private boolean canShowDiscordCard() {
        return discordFeatureEnabled && shouldShowDiscordCard(discordConfigured,
                discordSocialAvailable);
    }

    static int socialActionLabel(boolean socialConnected, boolean socialExpanded) {
        if (!socialConnected) return R.string.overlay_discord_social_connect_in_menu;
        return socialExpanded ? R.string.overlay_discord_social_hide_friends
                : R.string.overlay_discord_social_friends;
    }

    private String discordMuteLabel(boolean muted) {
        int stringId = muted ?
                R.string.overlay_discord_unmute : R.string.overlay_discord_mute;
        return getContext().getString(stringId) + shortcutSuffix(discordMuteShortcut);
    }

    private DiscordActionVoiceState discordActionVoiceState() {
        return discordActionVoiceState(overlayMode, communityModel,
                discordVoice != null && discordVoice.connected,
                discordVoice != null && discordVoice.muted,
                discordVoice != null && discordVoice.deafened);
    }

    static DiscordActionVoiceState discordActionVoiceState(OverlayMode mode, CommunityModel model,
                                                            boolean legacyConnected,
                                                            boolean legacyMuted,
                                                            boolean legacyDeafened) {
        if (mode == OverlayMode.COMMUNITY && model != null) {
            VoiceSummary voice = model.voice;
            return voice == null
                    ? new DiscordActionVoiceState(false, false, false)
                    : new DiscordActionVoiceState(voice.connected, voice.muted, voice.deafened);
        }
        return new DiscordActionVoiceState(legacyConnected, legacyMuted, legacyDeafened);
    }

    static final class DiscordActionVoiceState {
        final boolean connected;
        final boolean muted;
        final boolean deafened;

        DiscordActionVoiceState(boolean connected, boolean muted, boolean deafened) {
            this.connected = connected;
            this.muted = muted;
            this.deafened = deafened;
        }
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

    private String discordShortcutLabel(String value) {
        String button;
        switch (normalizeDiscordShortcut(value)) {
            case "x": button = playStationButtons ? "□" : "X"; break;
            case "y": button = playStationButtons ? "△" : "Y"; break;
            case "l1": button = playStationButtons ? "L1" : "LB"; break;
            case "r1": button = playStationButtons ? "R1" : "RB"; break;
            case "l3": button = "L3"; break;
            case "r3": button = "R3"; break;
            default: return "";
        }
        return getContext().getString(R.string.discord_shortcut_guide, button);
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

    static final class GuideShortcutLatch {
        static final int NONE = 0;
        static final int CONSUMED = 1;
        static final int MUTE = 2;
        static final int LEAVE = 3;
        private boolean guideDown;
        private boolean triggered;

        int handle(int action, int rawKeyCode, int repeatCount,
                   int muteKeyCode, int leaveKeyCode) {
            if (rawKeyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
                if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
                    guideDown = true;
                    triggered = false;
                } else if (action == KeyEvent.ACTION_UP) {
                    reset();
                }
                return CONSUMED;
            }
            if (!guideDown || rawKeyCode == KeyEvent.KEYCODE_UNKNOWN
                    || (rawKeyCode != muteKeyCode && rawKeyCode != leaveKeyCode)) {
                return NONE;
            }
            if (action == KeyEvent.ACTION_DOWN && repeatCount == 0 && !triggered) {
                triggered = true;
                return rawKeyCode == muteKeyCode ? MUTE : LEAVE;
            }
            return CONSUMED;
        }

        void reset() {
            guideDown = false;
            triggered = false;
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
        DiscordActionVoiceState shortcutVoice = discordActionVoiceState();
        int chordAction = discordShortcutChord.handle(event.getAction(), event.getKeyCode(),
                event.getRepeatCount(), shortcutVoice.connected
                        ? discordShortcutKeyCode(discordMuteShortcut) : KeyEvent.KEYCODE_UNKNOWN,
                shortcutVoice.connected
                        ? discordShortcutKeyCode(discordLeaveShortcut) : KeyEvent.KEYCODE_UNKNOWN);
        if (chordAction != GuideShortcutLatch.NONE) {
            if (chordAction == GuideShortcutLatch.MUTE) activateDiscordMute();
            else if (chordAction == GuideShortcutLatch.LEAVE) activateDiscordLeave();
            return true;
        }
        int keyCode = flipFaceButtons ? handleFlipFaceButtons(event.getKeyCode()) : event.getKeyCode();
        boolean chatKeyboardVisible = discordChatKeyboard != null
                && discordChatKeyboard.getVisibility() == VISIBLE
                && discordChatContainer != null && discordChatContainer.getVisibility() == VISIBLE;
        if (shouldRouteCommunityChatKeyboard(overlayMode, communitySubmode, chatKeyboardVisible)
                && shouldForwardCommunityChatKeyAction(event.getAction())) {
            KeyEvent translated = keyCode == event.getKeyCode() ? event
                    : new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(), keyCode,
                    event.getRepeatCount(), event.getMetaState(), event.getDeviceId(), event.getScanCode(),
                    event.getFlags(), event.getSource());
            if ((keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK)
                    && discordChatKeyboard.handleAccentCancel(translated)) return true;
            if (discordChatKeyboard.handleNavigationKey(translated)) {
                communityFocus = CommunityFocus.CHAT_KEYBOARD;
                return true;
            }
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                boolean consumed = communityCancelKeyDown;
                communityCancelKeyDown = false;
                return consumed || isGamepadEvent(event) || keyCode == KeyEvent.KEYCODE_BACK;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (communityCancelDownHasEffect(communityCancelKeyDown, event.getRepeatCount())) {
                    communityCancelKeyDown = true;
                    if (keyCode != KeyEvent.KEYCODE_BACK || !isFullGamepadEvent(event)) {
                        if (overlayMode == OverlayMode.COMMUNITY) backCommunity(); else closeMenu();
                    }
                }
                return true;
            }
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (overlayMode == OverlayMode.COMMUNITY) {
                if (isCommunityNavigationKey(keyCode) || isGamepadEvent(event)) {
                    communityInteractionGeneration++;
                }
                if (communitySubmode == CommunitySubmode.FRIEND_CHAT) {
                    return dispatchCommunityChatKey(keyCode, event);
                }
                syncCommunityFocusFromFocusedView();
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
                        int targetRegion = communityActionLeftRegion(discordIndex,
                                !communityRows.isEmpty());
                        if (targetRegion == COMMUNITY_REGION_ACTION) {
                            setDiscordIndex(discordIndex - 1);
                            return true;
                        }
                        focusCommunityListOrRail();
                        return true;
                    }
                    if (communityFocus == CommunityFocus.LIST) {
                        focusCommunityRail();
                        return true;
                    }
                    focusCommunityRail();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (communityFocus == CommunityFocus.ACTION) {
                        int next = communityActionTarget(discordIndex, keyCode,
                                discordButtons.size());
                        if (next >= 0) setDiscordIndex(next);
                    } else if (communityFocus == CommunityFocus.LIST
                            && communityRegionTarget(COMMUNITY_REGION_CONTENT, keyCode,
                            !discordButtons.isEmpty()) == COMMUNITY_REGION_ACTION) {
                        communityFocus = CommunityFocus.ACTION;
                        setDiscordIndex(0);
                    } else if (communityFocus == CommunityFocus.RAIL
                            && communityRailRightRegion(!communityRows.isEmpty(),
                            !discordButtons.isEmpty()) == COMMUNITY_REGION_CONTENT) {
                        focusCommunityList();
                    } else if (communityFocus == CommunityFocus.RAIL
                            && communityRailRightRegion(!communityRows.isEmpty(),
                            !discordButtons.isEmpty()) == COMMUNITY_REGION_ACTION) {
                        communityFocus = CommunityFocus.ACTION;
                        setDiscordIndex(0);
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
                    } else if (communityFocus == CommunityFocus.LIST) {
                        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                                && communityListDownEntersActions(communityListIndex,
                                communityRows.size(), !discordButtons.isEmpty())) {
                            communityFocus = CommunityFocus.ACTION;
                            setDiscordIndex(0);
                        } else moveCommunityList(keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                    } else if (!discordButtons.isEmpty()) {
                        if (keyCode == KeyEvent.KEYCODE_DPAD_UP
                                && communityActionUpEntersList(discordIndex, !communityRows.isEmpty())) {
                            communityFocus = CommunityFocus.LIST;
                            setCommunityListIndex(communityRows.size() - 1);
                        } else setDiscordIndex(communityActionTarget(discordIndex, keyCode,
                                discordButtons.size()));
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (communityFocus == CommunityFocus.ACTION) activateSelected();
                    else if (communityFocus == CommunityFocus.LIST && communityListIndex >= 0
                            && communityListIndex < communityRows.size()) {
                        communityRows.get(communityListIndex).view.performClick();
                    } else selectDiscordQuickSection(discordQuickSection);
                    return true;
                }
                return isGamepadEvent(event) || isCommunityNavigationKey(keyCode);
            }

            if (handleDiscordRailKey(keyCode)) return true;

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

        if (isGamepadEvent(event)
                || (overlayMode == OverlayMode.COMMUNITY
                && isCommunityNavigationKey(event.getKeyCode()))) {
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

    private void focusCommunityList() {
        if (communityRows.isEmpty()) return;
        communityFocus = CommunityFocus.LIST;
        activeRegion = Region.DISCORD;
        setCommunityListIndex(Math.max(0, communityListIndex));
    }

    private void syncCommunityFocusFromFocusedView() {
        View focused = findFocus();
        if (focused == null) return;
        if (discordRailIcons.contains(focused)) {
            communityFocus = CommunityFocus.RAIL;
            activeRegion = Region.DISCORD;
            return;
        }
        for (int index = 0; index < communityRows.size(); index++) {
            if (communityRows.get(index).view == focused) {
                communityFocus = CommunityFocus.LIST;
                communityListIndex = index;
                activeRegion = Region.DISCORD;
                return;
            }
        }
        int actionIndex = discordButtons.indexOf(focused);
        if (actionIndex >= 0) {
            communityFocus = CommunityFocus.ACTION;
            discordIndex = actionIndex;
            activeRegion = Region.DISCORD;
        }
    }

    private void focusCommunityListOrRail() {
        if (communityRows.isEmpty()) focusCommunityRail();
        else focusCommunityList();
    }

    private void focusCommunityRail() {
        communityFocus = CommunityFocus.RAIL;
        activeRegion = Region.DISCORD;
        if (!discordRailIcons.isEmpty()) discordRailIcons.get(discordQuickSection).requestFocus();
    }

    private void restoreCommunityFocusAfterSectionChange(CommunityFocus previous, int section) {
        if (previous == CommunityFocus.LIST) {
            focusCommunityList();
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

    private void setCommunityListIndex(int index) {
        if (communityRows.isEmpty()) return;
        communityListIndex = Math.max(0, Math.min(communityRows.size() - 1, index));
        View row = communityRows.get(communityListIndex).view;
        row.requestFocus();
        revealCommunityRow(row);
    }

    private void moveCommunityList(int direction) {
        if (communityRows.isEmpty()) return;
        setCommunityListIndex(Math.max(0, Math.min(communityRows.size() - 1,
                communityListIndex + direction)));
    }

    private void revealCommunityRow(View row) {
        if (row == null || discordContentScroll == null) return;
        row.post(() -> {
            android.graphics.Rect bounds = new android.graphics.Rect();
            row.getDrawingRect(bounds);
            discordContentScroll.offsetDescendantRectToMyCoords(row, bounds);
            int top = discordContentScroll.getScrollY();
            int bottom = top + discordContentScroll.getHeight();
            if (bounds.top < top) discordContentScroll.smoothScrollTo(0, bounds.top);
            else if (bounds.bottom > bottom) discordContentScroll.smoothScrollTo(0,
                    Math.max(0, bounds.bottom - discordContentScroll.getHeight()));
        });
    }

    private void backCommunity() {
        if (communitySubmode == CommunitySubmode.FRIEND_CHAT) {
            cancelCommunityChatKeyboardHold();
            communitySubmode = CommunitySubmode.ROOT;
            communitySection = CommunitySection.FRIENDS;
            discordQuickSection = 1;
            pendingChatFriendId = "";
            communityFocus = CommunityFocus.LIST;
            restoreCommunityPanelSize();
            renderDiscordCard();
            if (actionListener != null) actionListener.onDiscordCommunityBackToFriends();
            post(this::focusCommunityList);
            return;
        }
        if (communityBackNotifiesChannels(communitySubmode)) {
            communitySubmode = CommunitySubmode.ROOT;
            pendingGuildId = "";
            communityFocus = CommunityFocus.LIST;
            renderDiscordCard();
            if (actionListener != null) actionListener.onDiscordCommunityBackToChannels();
            post(this::focusCommunityList);
            return;
        }
        returnToMenu();
    }

    private boolean dispatchCommunityChatKey(int keyCode, KeyEvent source) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK) {
            backCommunity();
            return true;
        }
        return isGamepadEvent(source) || isCommunityNavigationKey(keyCode);
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
                if (overlayMode == OverlayMode.COMMUNITY) backCommunity(); else closeMenu();
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
            boolean handled = handleCommunityMotion(event);
            return handled || isGamepadMotionEvent(event) || super.onGenericMotionEvent(event);
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
        boolean rightStickHandled = handleCommunityRightStickScroll(event);
        InputDevice device = event.getDevice();
        boolean hasHat = device != null && (device.getMotionRange(MotionEvent.AXIS_HAT_X) != null
                || device.getMotionRange(MotionEvent.AXIS_HAT_Y) != null);
        float horizontal = hasHat ? event.getAxisValue(MotionEvent.AXIS_HAT_X)
                : event.getAxisValue(MotionEvent.AXIS_X);
        float vertical = hasHat ? event.getAxisValue(MotionEvent.AXIS_HAT_Y)
                : event.getAxisValue(MotionEvent.AXIS_Y);
        int direction = communityMotionDirection(horizontal, vertical, hasHat ? .45f : .85f);
        boolean chatKeyboardVisible = discordChatKeyboard != null
                && discordChatKeyboard.getVisibility() == VISIBLE
                && discordChatContainer != null && discordChatContainer.getVisibility() == VISIBLE;
        if (shouldRouteCommunityChatKeyboard(overlayMode, communitySubmode, chatKeyboardVisible)) {
            communityMotionDirection = KeyEvent.KEYCODE_UNKNOWN;
            return discordChatKeyboard.updateDirectionalHat(direction) || rightStickHandled;
        }
        if (rightStickHandled) return true;
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

    private boolean handleCommunityRightStickScroll(MotionEvent event) {
        InputDevice device = event.getDevice();
        int axis = device == null ? -1 : communityRightStickVerticalAxis(
                hasCommunityAxis(device, MotionEvent.AXIS_RX), hasCommunityAxis(device, MotionEvent.AXIS_RY),
                hasCommunityAxis(device, MotionEvent.AXIS_Z), hasCommunityAxis(device, MotionEvent.AXIS_RZ));
        if (axis < 0) return false;
        float value = event.getAxisValue(axis);
        if (Math.abs(value) < .5f) return false;
        long now = event.getEventTime();
        if (now - lastCommunityScrollTime < ANALOG_NAV_THROTTLE_MS) return true;
        ScrollView target = communitySubmode == CommunitySubmode.FRIEND_CHAT
                ? discordChatHistoryScroll : discordContentScroll;
        if (target == null) return true;
        int direction = value > 0 ? 1 : -1;
        if (target.canScrollVertically(direction)) {
            target.smoothScrollBy(0, direction * dp(communitySubmode == CommunitySubmode.FRIEND_CHAT
                    ? COMMUNITY_HISTORY_SCROLL_DP : COMMUNITY_LIST_SCROLL_DP));
            lastCommunityScrollTime = now;
        }
        return true;
    }

    static int communityRightStickVerticalAxis(boolean hasRx, boolean hasRy, boolean hasZ, boolean hasRz) {
        if (hasRx && hasRy) return MotionEvent.AXIS_RY;
        return hasZ && hasRz ? MotionEvent.AXIS_RZ : -1;
    }

    private static boolean hasCommunityAxis(InputDevice device, int axis) {
        return device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null
                || device.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD) != null;
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
            } else if (action == ACTION_END_GAME) {
                actionListener.onEndGame();
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
        discordShortcutChord.reset();
        cancelCommunityChatKeyboardHold();
        overlayMode = OverlayMode.MENU;
        communityMotionDirection = KeyEvent.KEYCODE_UNKNOWN;
        invalidateCommunityProjectionCache();
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
        discordShortcutChord.reset();
        communityCancelKeyDown = false;
        invalidateCommunityProjectionCache();
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
