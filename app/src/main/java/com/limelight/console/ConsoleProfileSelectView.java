package com.limelight.console;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.ui.ControllerGlyphs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Profile selection ("Kto gra?", UX_MENU_PL.md section 3.7, mockups 12 and 13) as a page of the
 * entry shell: one row of round 176 px avatars with a badge cut into the edge, the name and at
 * most two status lines, card focus in the avatar's colour, the shell's blooms in the profile
 * screen's colours, and a small Options menu. The
 * activity supplies the profiles and handles the chosen actions.
 */
final class ConsoleProfileSelectView extends HorizontalScrollView {
    interface Listener {
        void onProfileSelected(String profileId);
        void onAutomaticProfileToggled(String profileId);
        void onChangeHost();
    }

    static final String TAG_PROFILE = "profile.select:";
    /** Mockup "profile_row" at 2 px/dp: 176 px avatars on a 300 px pitch, centred on y = 500. */
    static final int AVATAR_DP = 88;
    static final int COLUMN_DP = 150;
    static final float FOCUS_SCALE = 1.1f;
    /**
     * Mockup "soft_shadow" / "focus_ring" in dp: at rest black 110/255, blur 18 px, 10 px down;
     * focused black blur 26 px, 16 px down, and the glow blur 34 px, 4 px down, grown 6 px,
     * under a 3 px ring 6 px out. The focused values are divided by the scale they are drawn
     * at, so they measure as in the mockup on screen.
     */
    static final float SHADOW_ALPHA = 110f / 255f;
    static final ConsoleCardFocus.Layer SHADOW = new ConsoleCardFocus.Layer(9f, 5f, 0f);
    static final ConsoleCardFocus.Layer FOCUS_SHADOW = new ConsoleCardFocus.Layer(
            13f / FOCUS_SCALE, 8f / FOCUS_SCALE, 0f);
    static final ConsoleCardFocus.Layer GLOW = new ConsoleCardFocus.Layer(
            17f / FOCUS_SCALE, 2f / FOCUS_SCALE, 3f / FOCUS_SCALE);
    /** 6.2 px: 176 x 1.1 is 193.6, so the ring's outer edge lands on 212 px as drawn. */
    static final float RING_GAP_DP = 3.1f / FOCUS_SCALE;
    static final float RING_DP = 1.5f / FOCUS_SCALE;
    /** Mockup "profile_screen" blooms: rgb(110,80,230) and rgb(40,120,220), any profile. */
    static final int BLOOM_PRIMARY = 0xFF6E50E6;
    static final int BLOOM_SECONDARY = 0xFF2878DC;
    /** Names without focus: rgb(186,190,200), about 70 %. */
    static final int NAME_RESTING = 0xFFBABEC8;

    private final ConsoleEntryShell shell;
    private final ConsoleEntryShell.Page page;
    private final LinearLayout row;
    private final Map<String, TileViews> tiles = new LinkedHashMap<>();
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private Listener listener;
    private String hostUuid;
    private String signature;
    private String focusId;
    private boolean reducedMotion;
    private boolean controllerPresent;
    private boolean playStation;
    private ConsoleProfileOptionsMenu menu;
    private String menuProfileId;

    ConsoleProfileSelectView(Context context, ConsoleEntryShell shell) {
        super(context);
        this.shell = shell;
        page = shell.addPage();
        setFillViewport(true);
        setHorizontalScrollBarEnabled(false);
        setClipChildren(false);
        setClipToPadding(false);
        // The shell puts the content at y = 372 px; avatars start at 412 px.
        setPadding(dp(48), dp(10), dp(48), dp(4));
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.setContent(this);
        updateLegend();
    }

    /**
     * Shows the profiles of {@code host}, cross-fading from the host row when that is on
     * screen. The row is rebuilt only when the host or its set of profiles changes; otherwise
     * badges and status lines update in place and an open Options menu stays open.
     */
    void show(ConsoleHostSelectView.Tile host,
              List<HostGatewayClient.IntegrationProfile> profiles, String automaticProfileId,
              boolean reducedMotion, Listener listener) {
        this.listener = listener;
        this.reducedMotion = reducedMotion;
        hostUuid = host.uuid;
        String hostName = host.name == null || host.name.trim().isEmpty()
                ? getContext().getString(R.string.console_entry_eyebrow) : host.name.trim();
        page.setHeader(hostName.toUpperCase(Locale.ROOT),
                getContext().getString(R.string.console_profile_gate_title), "");
        setHostDot(host);
        List<HostGatewayClient.IntegrationProfile> shown = new ArrayList<>();
        StringBuilder next = new StringBuilder(host.uuid);
        for (HostGatewayClient.IntegrationProfile profile : profiles) {
            if (profile == null || profile.id == null) continue;
            shown.add(profile);
            next.append('|').append(profile.id).append(':').append(profile.name)
                    .append(':').append(profile.kind).append(':').append(profile.avatarId)
                    // A profile that gains a PIN loses its switch: rebuild, closing the menu.
                    .append(':').append(profile.pinRequired);
        }
        if (!next.toString().equals(signature)) {
            closeMenu();
            signature = next.toString();
            tiles.clear();
            row.removeAllViews();
            for (HostGatewayClient.IntegrationProfile profile : shown) {
                TileViews views = profileColumn(profile);
                tiles.put(profile.id, views);
                row.addView(views.column, new LinearLayout.LayoutParams(dp(COLUMN_DP),
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            wireFocus();
        }
        for (HostGatewayClient.IntegrationProfile profile : shown) {
            update(tiles.get(profile.id), profile, profile.id.equals(automaticProfileId),
                    shown);
        }
        setVisibility(VISIBLE);
        shell.show(page);
        shell.setBlooms(BLOOM_PRIMARY, BLOOM_SECONDARY);
        TileViews target = tiles.get(focusId);
        if (target == null && !tiles.isEmpty()) target = tiles.values().iterator().next();
        if (target != null) target.column.post(target.column::requestFocus);
    }

    /** Keeps the eyebrow's state dot current while the host row is rendered behind. */
    void updateHost(List<ConsoleHostSelectView.Tile> hosts) {
        for (ConsoleHostSelectView.Tile host : hosts) {
            if (host.uuid.equals(hostUuid)) setHostDot(host);
        }
    }

    private void setHostDot(ConsoleHostSelectView.Tile host) {
        page.setEyebrowDot(ConsoleHostTileLook.of(host.state, host.profile, false).dot);
    }

    void setControllers(boolean present, boolean playStation) {
        if (controllerPresent == present && this.playStation == playStation) return;
        controllerPresent = present;
        this.playStation = playStation;
        updateLegend();
    }

    /**
     * Options on a profile opens its menu, which then takes every navigation key; returns
     * whether consumed. Back without a menu is left to the caller.
     */
    boolean handleKey(KeyEvent event) {
        if (!shell.shows(page)) return false;
        if (menu != null) return menu.handleKey(event);
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_MENU && keyCode != KeyEvent.KEYCODE_BUTTON_START) {
            return false;
        }
        View focused = row.getFocusedChild();
        Object tag = focused == null ? null : focused.getTag();
        TileViews views = tag instanceof String
                ? tiles.get(((String) tag).substring(TAG_PROFILE.length())) : null;
        // Not while the host row is still fading out: the menu's backdrop would freeze it.
        if (views != null && event.getAction() == KeyEvent.ACTION_UP
                && !shell.crossFading()) openMenu(views);
        return true;
    }

    /** Mockup 13: the automatic-login switch where the profile may have it, "Change computer". */
    private void openMenu(TileViews views) {
        String profileId = views.profile.id;
        views.hint.setVisibility(INVISIBLE);
        // A confirm key held while Options opens must not leave the avatar pressed.
        views.column.setPressed(false);
        menu = new ConsoleProfileOptionsMenu(shell, page, views.frame,
                Math.max(0, views.look.lines.size() - 1), this::closeMenu);
        menuProfileId = profileId;
        if (views.look.automaticToggle) {
            menu.addToggle(getContext().getString(R.string.console_profile_gate_options),
                    views.look.badge == ConsoleProfileTileLook.Badge.AUTOMATIC,
                    () -> listener.onAutomaticProfileToggled(profileId));
        }
        menu.addAction(R.drawable.ic_console_monitor,
                getContext().getString(R.string.console_profile_gate_change_host), () -> {
                    closeMenu();
                    listener.onChangeHost();
                });
        menu.open(reducedMotion);
    }

    private void closeMenu() {
        if (menu == null) return;
        menu.close(reducedMotion);
        menu = null;
        TileViews views = tiles.get(menuProfileId);
        if (views != null && views.column.isFocused()) views.hint.setVisibility(VISIBLE);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (menu != null && !isShown()) closeMenu();
    }

    private void update(TileViews views, HostGatewayClient.IntegrationProfile profile,
                        boolean automatic, List<HostGatewayClient.IntegrationProfile> profiles) {
        ConsoleProfileTileLook look = ConsoleProfileTileLook.of(profile, automatic);
        views.profile = profile;
        views.look = look;
        views.badge.setVisibility(look.badge == ConsoleProfileTileLook.Badge.NONE
                ? GONE : VISIBLE);
        views.lock.setVisibility(look.badge == ConsoleProfileTileLook.Badge.LOCK
                ? VISIBLE : GONE);
        views.automatic.setVisibility(look.badge == ConsoleProfileTileLook.Badge.AUTOMATIC
                ? VISIBLE : GONE);
        List<String> spoken = new ArrayList<>();
        views.lines.removeAllViews();
        for (ConsoleProfileTileLook.Line line : look.lines) {
            String value;
            if (line == ConsoleProfileTileLook.Line.CHILD) {
                String parent = parentName(profile, profiles);
                value = parent.isEmpty()
                        ? getContext().getString(R.string.console_profile_gate_child)
                        : getContext().getString(R.string.console_profile_gate_child_of, parent);
            } else if (line == ConsoleProfileTileLook.Line.REMAINING) {
                value = getContext().getString(R.string.console_profile_gate_remaining,
                        remainingMinutes(profile.remainingDailySeconds));
            } else {
                value = getContext().getString(R.string.console_profile_gate_active);
            }
            spoken.add(value);
            views.lines.addView(statusLine(value, line == ConsoleProfileTileLook.Line.ACTIVE),
                    columnText(views.lines.getChildCount() == 0 ? 5 : 3));
        }
        // Mockup: the hint is centred 56 px below the name's baseline; lines push it down.
        ((LinearLayout.LayoutParams) views.hint.getLayoutParams()).topMargin =
                dp(look.lines.isEmpty() ? 17 : 14);
        views.hint.requestLayout();
        if (look.badge == ConsoleProfileTileLook.Badge.LOCK) {
            spoken.add(getContext().getString(R.string.console_profile_gate_pin));
        } else if (look.badge == ConsoleProfileTileLook.Badge.AUTOMATIC) {
            spoken.add(getContext().getString(R.string.console_profile_gate_automatic));
        }
        views.column.setContentDescription(getContext().getString(
                R.string.console_profile_gate_tile_description, views.name.getText(),
                TextUtils.join(" · ", spoken)));
        if (menu != null && profile.id.equals(menuProfileId)) {
            menu.setChecked(look.badge == ConsoleProfileTileLook.Badge.AUTOMATIC);
        }
    }

    private TileViews profileColumn(HostGatewayClient.IntegrationProfile profile) {
        TileViews views = new TileViews();
        String profileId = profile.id;
        String name = displayName(getContext(), profile);
        LinearLayout column = new LinearLayout(getContext());
        views.column = column;
        column.setId(View.generateViewId());
        column.setTag(TAG_PROFILE + profileId);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setFocusable(true);
        column.setFocusableInTouchMode(true);
        column.setClickable(true);
        column.setSoundEffectsEnabled(false);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        // TalkBack and UiAutomator read the column's description, not its parts.
        column.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        int margin = dp(ConsoleCardFocus.MARGIN_DP);
        FrameLayout frame = new FrameLayout(getContext());
        views.frame = frame;
        frame.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        frame.setPadding(margin, margin, margin, margin);
        frame.setClipChildren(false);
        frame.setClipToPadding(false);
        ConsoleCardFocus focus = new ConsoleCardFocus(getResources(), margin,
                dp(AVATAR_DP) / 2f);
        focus.setLayers(SHADOW, FOCUS_SHADOW, GLOW);
        focus.setRing(RING_GAP_DP, RING_DP);
        focus.setGlowColor(accent(profile));
        focus.setShadowAlpha(SHADOW_ALPHA);
        frame.setBackground(focus);
        int size = dp(AVATAR_DP) + 2 * margin;
        column.addView(frame, new LinearLayout.LayoutParams(size, size));
        addAvatar(frame, profile, AVATAR_DP);

        // Mockup "corner_badge": r 22 px on a 4 px dark separating ring, its centre 4 px
        // outside the avatar's edge on the lower right diagonal; it keeps its size when the
        // avatar grows.
        views.badge = new FrameLayout(getContext());
        GradientDrawable badgeBackground = new GradientDrawable();
        badgeBackground.setShape(GradientDrawable.OVAL);
        badgeBackground.setColor(0xF0161A24);
        badgeBackground.setStroke(dp(2), 0xFF080A10);
        views.badge.setBackground(badgeBackground);
        views.lock = new ImageView(getContext());
        views.lock.setImageResource(R.drawable.ic_console_lock);
        views.lock.setColorFilter(ConsoleEntryShell.TEXT);
        views.badge.addView(views.lock, new FrameLayout.LayoutParams(
                dp(13), dp(13), Gravity.CENTER));
        views.automatic = text("A", 12, ConsoleEntryShell.TEXT, medium);
        ConsoleText.centreOnGlyphs(views.automatic);
        views.badge.addView(views.automatic, match());
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                dp(26), dp(26), Gravity.TOP | Gravity.LEFT);
        badgeParams.leftMargin = dp(AVATAR_DP / 2f * 1.707f + 2 - 13);
        badgeParams.topMargin = badgeParams.leftMargin;
        frame.addView(views.badge, badgeParams);

        // Mockup: name baseline 62 px below the focused avatar, lines 40 px and then 34 px on.
        views.name = text(name, 15, NAME_RESTING, medium);
        views.name.setSingleLine(true);
        views.name.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(views.name, columnText(11.5f));
        views.lines = new LinearLayout(getContext());
        views.lines.setOrientation(LinearLayout.VERTICAL);
        views.lines.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(views.lines, columnText(0));
        views.hint = text(getContext().getString(R.string.console_host_options_hint),
                12, ConsoleEntryShell.TEXT, medium);
        views.hint.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_console_options,
                0, 0, 0);
        views.hint.setCompoundDrawablePadding(dp(6));
        views.hint.setVisibility(INVISIBLE);
        column.addView(views.hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        column.setOnClickListener(view -> listener.onProfileSelected(profileId));
        column.setOnFocusChangeListener((view, focused) -> {
            focus.setFocused(focused);
            ConsoleCardFocus.scale(frame, focused ? FOCUS_SCALE : 1f, reducedMotion);
            ConsoleCardFocus.scale(views.badge, focused ? 1f / FOCUS_SCALE : 1f, reducedMotion);
            views.name.setTextColor(focused ? ConsoleEntryShell.TEXT : NAME_RESTING);
            views.hint.setVisibility(focused && menu == null ? VISIBLE : INVISIBLE);
            // Flow mockup: the hint fades in over 220 ms once the focus has moved (180 ms).
            views.hint.animate().cancel();
            views.hint.setAlpha(focused && !reducedMotion ? 0f : 1f);
            if (!focused) return;
            views.hint.animate().alpha(1f).setStartDelay(180).setDuration(220).start();
            focusId = profileId;
            centreOn(column);
        });
        return views;
    }

    /** Mockup "dot_status": a 12 px dot 8 px before the text, both centred as one. */
    private View statusLine(String value, boolean dot) {
        LinearLayout line = new LinearLayout(getContext());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER);
        TextView status = text(value, 12, ConsoleEntryShell.TEXT_SECONDARY, Typeface.DEFAULT);
        if (dot) {
            View mark = new View(getContext());
            GradientDrawable green = new GradientDrawable();
            green.setShape(GradientDrawable.OVAL);
            green.setColor(ConsoleHostTileLook.ONLINE);
            mark.setBackground(green);
            mark.setTranslationY(ConsoleText.dotOffset(status, dp(6)));
            LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(6), dp(6));
            markParams.rightMargin = dp(4);
            line.addView(mark, markParams);
        }
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return line;
    }

    /** One centred legend; on a profile all three actions do something. */
    private void updateLegend() {
        page.clearLegend();
        String select = getContext().getString(R.string.console_select_hint);
        String options = getContext().getString(R.string.console_host_options_hint);
        String back = getContext().getString(R.string.console_profile_gate_back);
        if (controllerPresent) {
            page.addLegendKey(ControllerGlyphs.text(playStation,
                    ControllerGlyphs.Button.CONFIRM), true, select);
            page.addLegendKey(ControllerGlyphs.text(playStation, ControllerGlyphs.Button.MENU),
                    true, options);
            page.addLegendKey(ControllerGlyphs.text(playStation,
                    ControllerGlyphs.Button.CANCEL), true, back);
        } else {
            page.addLegendKey("OK", false, select);
            page.addLegendIcon(R.drawable.ic_console_options, options);
            page.addLegendIcon(R.drawable.ic_console_back, back);
        }
    }

    private void wireFocus() {
        List<View> columns = new ArrayList<>();
        for (TileViews views : tiles.values()) columns.add(views.column);
        for (int index = 0; index < columns.size(); index++) {
            View current = columns.get(index);
            current.setNextFocusLeftId(columns.get(Math.max(0, index - 1)).getId());
            current.setNextFocusRightId(
                    columns.get(Math.min(columns.size() - 1, index + 1)).getId());
            current.setNextFocusUpId(current.getId());
            current.setNextFocusDownId(current.getId());
        }
    }

    private void centreOn(View column) {
        column.post(() -> {
            int target = Math.max(0, column.getLeft() + getPaddingLeft()
                    - (getWidth() - column.getWidth()) / 2);
            if (reducedMotion) scrollTo(target, 0);
            else smoothScrollTo(target, 0);
        });
    }

    private static String parentName(HostGatewayClient.IntegrationProfile profile,
                                     List<HostGatewayClient.IntegrationProfile> profiles) {
        if (profile.parentProfileId == null || profile.parentProfileId.isEmpty()) return "";
        for (HostGatewayClient.IntegrationProfile candidate : profiles) {
            if (profile.parentProfileId.equals(candidate.id)) {
                return candidate.name == null ? "" : candidate.name.trim();
            }
        }
        return "";
    }

    private String remainingMinutes(long seconds) {
        return PlaynitePlaytimeFormatter.minutes(Math.max(1L, (seconds + 59L) / 60L),
                new PlaynitePlaytimeFormatter.Labels("",
                        getContext().getString(R.string.playnite_hours_short),
                        getContext().getString(R.string.playnite_minutes_short)));
    }

    /** The avatar of a profile on screen, for a screen that takes it over; null if not shown. */
    View avatarOf(String profileId) {
        TileViews views = tiles.get(profileId);
        return views != null && views.frame.isShown() ? views.frame : null;
    }

    static String displayName(Context context, HostGatewayClient.IntegrationProfile profile) {
        return profile.name == null || profile.name.trim().isEmpty()
                ? context.getString(R.string.console_entry_eyebrow) : profile.name.trim();
    }

    /**
     * The avatar's light colour, which its focus glow takes, as host tiles do; a child's
     * chosen colour replaces the generated art and so also its hue.
     */
    private static int accent(HostGatewayClient.IntegrationProfile profile) {
        if (!tinted(profile)) return HostTileArt.accent(profile.id);
        float[] hsv = new float[3];
        Color.colorToHSV(ConsoleChildProfileEditor.avatarColor(profile.avatarId), hsv);
        return Color.HSVToColor(new float[]{hsv[0], .6f, 1f});
    }

    private static boolean tinted(HostGatewayClient.IntegrationProfile profile) {
        return profile.isChild() && ConsoleChildProfileEditor.isKnownAvatar(profile.avatarId)
                && !"default".equals(profile.avatarId);
    }

    /**
     * Mockup "blob_avatar" with "initials_on", round, filling {@code frame} inside its padding:
     * initials 27 % of the avatar, shadow 4 px blur and 2 px down at 176 px, 55 % black.
     */
    static void addAvatar(FrameLayout frame, HostGatewayClient.IntegrationProfile profile,
                          float sizeDp) {
        Context context = frame.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        float scale = sizeDp / AVATAR_DP;
        ImageView artwork = new ImageView(context);
        // Round in its own drawing: the menus' software copies ignore outline clips.
        artwork.setImageDrawable(new RoundArt(HostTileArt.avatar(profile.id)));
        if (tinted(profile)) {
            artwork.setColorFilter(ConsoleChildProfileEditor.avatarColor(profile.avatarId),
                    PorterDuff.Mode.SRC_ATOP);
        }
        frame.addView(artwork, match());
        TextView initials = new TextView(context);
        initials.setText(initials(displayName(context, profile)));
        initials.setTextSize(24 * scale);
        initials.setTextColor(Color.WHITE);
        initials.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        initials.setGravity(Gravity.CENTER);
        initials.setIncludeFontPadding(false);
        initials.setShadowLayer(2 * scale * density, 0f, scale * density, 0x8C000000);
        frame.addView(initials, match());
    }

    private static String initials(String name) {
        String[] words = name.split("\\s+");
        if (words.length > 1) {
            return (words[0].substring(0, 1)
                    + words[words.length - 1].substring(0, 1)).toUpperCase(Locale.ROOT);
        }
        return name.substring(0, Math.min(2, name.length())).toUpperCase(Locale.ROOT);
    }

    private TextView text(String value, float sizeSp, int color, Typeface typeface) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(typeface);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        return view;
    }

    private LinearLayout.LayoutParams columnText(float topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** A bitmap stretched over a circle, with the colour filter a child's colour sets. */
    private static final class RoundArt extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix matrix = new Matrix();
        private final Bitmap bitmap;

        RoundArt(Bitmap bitmap) {
            this.bitmap = bitmap;
            paint.setShader(new BitmapShader(bitmap, Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP));
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            matrix.setScale(bounds.width() / (float) bitmap.getWidth(),
                    bounds.height() / (float) bitmap.getHeight());
            matrix.postTranslate(bounds.left, bounds.top);
            paint.getShader().setLocalMatrix(matrix);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            canvas.drawOval(bounds.left, bounds.top, bounds.right, bounds.bottom, paint);
        }

        @Override public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static final class TileViews {
        HostGatewayClient.IntegrationProfile profile;
        ConsoleProfileTileLook look;
        LinearLayout column;
        FrameLayout frame;
        FrameLayout badge;
        ImageView lock;
        TextView automatic;
        TextView name;
        LinearLayout lines;
        TextView hint;
    }
}
