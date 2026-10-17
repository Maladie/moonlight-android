package com.limelight.console;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
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
import java.util.Map;

/**
 * Host selection ("Wybierz komputer", UX_MENU_PL.md section 3.7) inside the entry shell: one row
 * of 240 px glass host tiles followed by "Add host", card focus, live waking seconds and the
 * shell header and legend for the focused tile. The activity supplies host facts and handles
 * the chosen actions.
 */
final class ConsoleHostSelectView extends HorizontalScrollView {
    interface Listener {
        void onHostSelected(String uuid);
        void onHostOptions(String uuid);
        void onAddHost();
    }

    /** One host's facts as the activity resolves them. */
    static final class Tile {
        final String uuid;
        final String name;
        final ConsoleHostPresentation.State state;
        final ConsoleHostPresentation.ProfileState profile;
        final String status;
        /** Uptime when this client started waking the host, or -1. */
        final long wakingSince;

        Tile(String uuid, String name, ConsoleHostPresentation.State state,
             ConsoleHostPresentation.ProfileState profile, String status, long wakingSince) {
            this.uuid = uuid;
            this.name = name;
            this.state = state;
            this.profile = profile;
            this.status = status;
            this.wakingSince = wakingSince;
        }
    }

    static final String TAG_HOST = "host.select:";
    static final String TAG_ADD = "host.add";
    /** Section 3.7 at 2 px/dp: 240 px tiles with 32 px corners. */
    static final int CARD_DP = 120;
    static final int CORNER_DP = 16;
    static final int COLUMN_DP = 156;
    static final float FOCUS_SCALE = 1.08f;
    static final float PRESSED_SCALE = 1.02f;
    static final float DIMMED_ALPHA = .55f;
    static final int DIMMED_NAME = 0xFFA0A6B2;
    static final int DIMMED_STATUS = 0xFF969CA8;
    /** Mockup "glass": tint rgb(18,22,32) at 35 % plus a 4 % white veil over the backdrop. */
    static final int GLASS_FILL = 0x14FFFFFF;
    static final long DOT_SPIN_MS = 900L;

    private final ConsoleEntryShell shell;
    private final ConsoleEntryShell.Page page;
    private final Listener listener;
    private final long wakingTimeoutMs;
    private final LinearLayout row;
    private final Map<String, TileViews> tiles = new LinkedHashMap<>();
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Runnable wakingTick = this::tickWaking;
    private String signature;
    private String focusUuid;
    private String pendingUuid;
    private boolean reducedMotion;
    private boolean controllerPresent;
    private boolean playStation;
    private Boolean legendOptions;
    private boolean longPressConsumed;

    ConsoleHostSelectView(Context context, ConsoleEntryShell shell, long wakingTimeoutMs,
                          Listener listener) {
        super(context);
        this.shell = shell;
        page = shell.addPage();
        this.listener = listener;
        this.wakingTimeoutMs = wakingTimeoutMs;
        setFillViewport(true);
        setHorizontalScrollBarEnabled(false);
        setClipChildren(false);
        setClipToPadding(false);
        setPadding(dp(48), dp(4), dp(48), dp(4));
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.setHeader(context.getString(R.string.console_entry_eyebrow),
                context.getString(R.string.console_host_selection_title),
                context.getString(R.string.console_host_selection_subtitle));
        page.setContent(this);
        updateLegend(false);
    }

    /** Back from the profile screen cross-fades it into this row above the same shell. */
    void show() {
        shell.show(page);
    }

    static boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    /**
     * Shows {@code hosts} in order with "Add host" last; rebuilds only when the set of hosts or
     * their names change. Returns whether the row was rebuilt.
     */
    boolean render(List<Tile> hosts, boolean searching) {
        page.setSubtitle(getContext().getString(searching
                ? R.string.console_host_selection_searching
                : R.string.console_host_selection_subtitle));
        StringBuilder next = new StringBuilder();
        for (Tile tile : hosts) next.append(tile.uuid).append(':').append(tile.name).append('|');
        if (next.toString().equals(signature)) {
            for (Tile tile : hosts) update(tile);
            return false;
        }
        signature = next.toString();
        for (TileViews views : tiles.values()) spinDot(views, false);
        tiles.clear();
        row.removeAllViews();
        for (Tile tile : hosts) {
            TileViews views = hostColumn(tile);
            tiles.put(tile.uuid, views);
            row.addView(views.column, columnParams());
            update(tile);
        }
        row.addView(addColumn(), columnParams());
        wireFocus();
        return true;
    }

    boolean shows(String uuid) {
        return tiles.containsKey(uuid);
    }

    void update(Tile tile) {
        TileViews views = tiles.get(tile.uuid);
        if (views == null) return;
        views.tile = tile;
        ConsoleHostTileLook look = ConsoleHostTileLook.of(tile.state, tile.profile,
                tile.wakingSince >= 0L);
        views.look = look;
        if (look.spinner) {
            views.dot.setBackground(new CheckingArc(look.dot,
                    getResources().getDisplayMetrics().density));
        } else {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(look.dot);
            views.dot.setBackground(dot);
        }
        spinDot(views, look.spinner && !reducedMotion);
        boolean live = look.badge == ConsoleHostTileLook.Badge.LIVE;
        views.live.setVisibility(live ? VISIBLE : GONE);
        // The mockup lifts the monitor above the live tag.
        views.monitor.setTranslationY(live ? -dp(6) : 0f);
        applyBadge(views, look.badge);
        // Mockup "desaturate": 15 % colour, 80 % brightness, 55 % opacity; dim text and edge.
        views.card.setAlpha(look.dimmed ? DIMMED_ALPHA : 1f);
        views.artwork.setColorFilter(look.dimmed ? DESATURATED : null);
        views.edge.setEdgeAlpha(look.dimmed ? 30 : 60);
        views.focus.setShadowAlpha(look.dimmed ? 60f / 255f : ConsoleCardFocus.SHADOW_ALPHA);
        views.name.setTextColor(look.dimmed ? DIMMED_NAME : ConsoleEntryShell.TEXT);
        views.status.setTextColor(look.dimmed ? DIMMED_STATUS : ConsoleEntryShell.TEXT_SECONDARY);
        applyStatus(views);
        if (look.wakingRing && tile.wakingSince >= 0L) {
            removeCallbacks(wakingTick);
            postDelayed(wakingTick, 1_000L);
        }
    }

    /**
     * Marks the host whose OK is being answered (the profile gate may wait on the Gateway):
     * a pressed card with a connecting ring until the row leaves the screen or null clears it.
     */
    void setPending(String uuid) {
        String previous = pendingUuid;
        pendingUuid = uuid;
        for (String changed : new String[]{previous, uuid}) {
            TileViews views = changed == null ? null : tiles.get(changed);
            if (views == null) continue;
            applyStatus(views);
            scaleCard(views);
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (pendingUuid != null && !isShown()) setPending(null);
    }

    private boolean pending(TileViews views) {
        return views.tile.uuid.equals(pendingUuid);
    }

    private void scaleCard(TileViews views) {
        float scale = pending(views) ? PRESSED_SCALE
                : views.column.isFocused() ? FOCUS_SCALE : 1f;
        ConsoleCardFocus.scale(views.frame, scale, reducedMotion);
    }

    /** Remembers the tile to focus next, e.g. the host whose profile gate was open. */
    void setFocusTarget(String uuid) {
        focusUuid = uuid;
    }

    /**
     * Focuses the remembered tile, else {@code fallbackUuid} (the selected host), else the
     * first reachable host, else the first host; "Add host" only when there is none.
     */
    void requestTargetFocus(String fallbackUuid) {
        TileViews target = tiles.get(focusUuid);
        if (target == null) target = tiles.get(fallbackUuid);
        for (TileViews views : tiles.values()) {
            if (target != null) break;
            if (reachable(views.tile.state)) target = views;
        }
        if (target == null && !tiles.isEmpty()) target = tiles.values().iterator().next();
        View view = target != null ? target.column : row.findViewWithTag(TAG_ADD);
        if (view != null) view.post(view::requestFocus);
    }

    private static boolean reachable(ConsoleHostPresentation.State state) {
        return state == ConsoleHostPresentation.State.ONLINE
                || state == ConsoleHostPresentation.State.ACTIVE_SESSION
                || state == ConsoleHostPresentation.State.PROFILE_ATTENTION;
    }

    void setControllers(boolean present, boolean playStation) {
        if (controllerPresent == present && this.playStation == playStation) return;
        controllerPresent = present;
        this.playStation = playStation;
        boolean options = legendOptions != null && legendOptions;
        legendOptions = null;
        updateLegend(options);
    }

    void setReducedMotion(boolean reducedMotion) {
        this.reducedMotion = reducedMotion;
    }

    /** Options and a long OK on a host tile open its options; returns whether consumed. */
    boolean handleKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        String uuid = focusedHostUuid();
        if (isConfirmKey(keyCode)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && (event.getRepeatCount() > 0 || event.isLongPress())) {
                if (uuid != null && !longPressConsumed) {
                    longPressConsumed = true;
                    listener.onHostOptions(uuid);
                }
                return uuid != null;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && longPressConsumed) {
                longPressConsumed = false;
                return true;
            }
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && uuid != null
                && (keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_BUTTON_START)) {
            listener.onHostOptions(uuid);
            return true;
        }
        return false;
    }

    private String focusedHostUuid() {
        View focused = findFocus();
        Object tag = focused == null ? null : focused.getTag();
        return tag instanceof String && ((String) tag).startsWith(TAG_HOST)
                ? ((String) tag).substring(TAG_HOST.length()) : null;
    }

    private void applyStatus(TileViews views) {
        Tile tile = views.tile;
        ConsoleHostTileLook look = views.look;
        String status;
        boolean pending = pending(views);
        views.ring.setVisibility(look.wakingRing || pending ? VISIBLE : GONE);
        views.waking.setIndeterminate(pending, reducedMotion);
        if (pending) {
            status = getContext().getString(R.string.console_host_tile_connecting);
        } else if (look.label == R.string.console_host_tile_waking) {
            long elapsed = Math.max(0L, SystemClock.uptimeMillis() - tile.wakingSince);
            status = getContext().getString(look.label, elapsed / 1_000L);
            views.waking.setProgress(Math.min(1f, elapsed / (float) wakingTimeoutMs));
        } else {
            status = look.label != 0 ? getContext().getString(look.label) : tile.status;
            views.waking.setProgress(0f);
        }
        views.status.setText(status);
        views.column.setContentDescription(getContext().getString(
                R.string.console_host_selection_description, tile.name, status));
    }

    /** Turns the checking arc by the view's rotation alone, a GPU property: no redraws. */
    private static void spinDot(TileViews views, boolean spin) {
        if (spin == (views.spin != null)) return;
        if (!spin) {
            views.spin.cancel();
            views.spin = null;
            views.dot.setRotation(0f);
            return;
        }
        views.spin = ObjectAnimator.ofFloat(views.dot, ROTATION, 0f, 360f);
        views.spin.setDuration(DOT_SPIN_MS);
        views.spin.setRepeatCount(ValueAnimator.INFINITE);
        views.spin.setInterpolator(new LinearInterpolator());
        views.spin.start();
    }

    private void tickWaking() {
        boolean waking = false;
        for (TileViews views : tiles.values()) {
            if (views.look != null && views.look.wakingRing && views.tile.wakingSince >= 0L) {
                applyStatus(views);
                waking = true;
            }
        }
        if (waking && isShown()) postDelayed(wakingTick, 1_000L);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(wakingTick);
        for (TileViews views : tiles.values()) spinDot(views, false);
        super.onDetachedFromWindow();
    }

    private static void applyBadge(TileViews views, ConsoleHostTileLook.Badge badge) {
        int icon;
        switch (badge) {
            case ASLEEP:
                icon = R.drawable.ic_console_moon;
                break;
            case PAIR:
                icon = R.drawable.ic_console_unlink;
                break;
            case ATTENTION:
                icon = R.drawable.ic_console_attention;
                break;
            default:
                views.badge.setVisibility(GONE);
                return;
        }
        views.badgeIcon.setImageResource(icon);
        views.badgeIcon.setColorFilter(views.look.dot);
        views.badge.setVisibility(VISIBLE);
    }

    private TileViews hostColumn(Tile tile) {
        TileViews views = new TileViews();
        views.column = column(TAG_HOST + tile.uuid);
        int accent = HostTileArt.accent(tile.uuid);
        views.frame = (FrameLayout) views.column.getChildAt(0);
        views.focus = focus(views.frame, accent);

        views.edge = new GlassEdge(dp(CORNER_DP), getResources().getDisplayMetrics().density, 0);
        views.card = card(views.frame, views.edge);
        views.artwork = new ImageView(getContext());
        views.artwork.setImageBitmap(HostTileArt.bitmap(tile.uuid));
        views.artwork.setScaleType(ImageView.ScaleType.FIT_XY);
        views.card.addView(views.artwork, 0, match());
        views.monitor = icon(R.drawable.ic_console_monitor_tile, ConsoleEntryShell.TEXT);
        views.card.addView(views.monitor,
                new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER));
        views.live = livePill();
        FrameLayout.LayoutParams liveParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(18),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        liveParams.bottomMargin = dp(10);
        views.card.addView(views.live, liveParams);

        // Mockup "corner_badge": r 26 px on a 4 px dark separating ring, centred 12 px inside
        // the card's lower right corner. Frame children sit inside its padding, on the card.
        views.badge = new FrameLayout(getContext());
        GradientDrawable badgeBackground = new GradientDrawable();
        badgeBackground.setShape(GradientDrawable.OVAL);
        badgeBackground.setColor(0xF0161A24);
        badgeBackground.setStroke(dp(2), 0xFF080A10);
        views.badge.setBackground(badgeBackground);
        views.badgeIcon = new ImageView(getContext());
        views.badge.addView(views.badgeIcon, new FrameLayout.LayoutParams(
                dp(16), dp(16), Gravity.CENTER));
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                dp(30), dp(30), Gravity.TOP | Gravity.START);
        badgeParams.leftMargin = dp(CARD_DP - 6 - 15);
        badgeParams.topMargin = badgeParams.leftMargin;
        views.frame.addView(views.badge, badgeParams);

        views.waking = new WakingRing(dp(CORNER_DP), getResources().getDisplayMetrics().density);
        views.ring = new View(getContext());
        views.ring.setBackground(views.waking);
        views.frame.addView(views.ring, match());

        // Mockup: name baseline 62 px below the tile, status 40 px below it, hint at +96.
        views.name = text(tile.name, 15, ConsoleEntryShell.TEXT, medium);
        views.name.setSingleLine(true);
        views.name.setEllipsize(TextUtils.TruncateAt.END);
        views.column.addView(views.name, columnText(7));

        views.statusRow = new LinearLayout(getContext());
        views.statusRow.setOrientation(LinearLayout.HORIZONTAL);
        views.statusRow.setGravity(Gravity.CENTER);
        views.dot = new View(getContext());
        views.statusRow.addView(views.dot, new LinearLayout.LayoutParams(dp(6), dp(6)));
        views.status = text("", 12, ConsoleEntryShell.TEXT_SECONDARY, Typeface.DEFAULT);
        views.status.setSingleLine(true);
        views.status.setEllipsize(TextUtils.TruncateAt.END);
        views.dot.setTranslationY(ConsoleText.dotOffset(views.status, dp(6)));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.leftMargin = dp(4);
        views.statusRow.addView(views.status, statusParams);
        views.column.addView(views.statusRow, columnText(6));

        TextView options = text(getContext().getString(R.string.console_host_options_hint),
                12, ConsoleEntryShell.TEXT, medium);
        options.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_console_options, 0, 0, 0);
        options.setCompoundDrawablePadding(dp(6));
        options.setVisibility(INVISIBLE);
        LinearLayout.LayoutParams optionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        optionsParams.topMargin = dp(13);
        views.column.addView(options, optionsParams);

        String uuid = tile.uuid;
        views.column.setOnClickListener(view -> listener.onHostSelected(uuid));
        views.column.setOnLongClickListener(view -> {
            listener.onHostOptions(uuid);
            return true;
        });
        views.column.setOnFocusChangeListener((view, focused) -> {
            views.focus.setFocused(focused);
            scaleCard(views);
            options.setVisibility(focused ? VISIBLE : INVISIBLE);
            if (!focused) return;
            focusUuid = uuid;
            shell.setAccent(accent);
            updateLegend(true);
            centreOn(views.column);
        });
        return views;
    }

    /** Mockup "add_host_tile": a glass card with a plus, its label in the secondary colour. */
    private View addColumn() {
        LinearLayout column = column(TAG_ADD);
        FrameLayout frame = (FrameLayout) column.getChildAt(0);
        ConsoleCardFocus focus = focus(frame, 0xFFB4C4DC);
        focus.setShadowAlpha(80f / 255f);
        FrameLayout card = card(frame, new GlassEdge(dp(CORNER_DP),
                getResources().getDisplayMetrics().density, GLASS_FILL));
        card.addView(icon(R.drawable.ic_console_plus, ConsoleEntryShell.TEXT_SECONDARY),
                new FrameLayout.LayoutParams(dp(31), dp(31), Gravity.CENTER));
        String label = getContext().getString(R.string.console_add_host_short);
        TextView name = text(label, 15, ConsoleEntryShell.TEXT_SECONDARY, medium);
        column.addView(name, columnText(7));
        column.setContentDescription(label);
        column.setOnClickListener(view -> listener.onAddHost());
        column.setOnFocusChangeListener((view, focused) -> {
            focus.setFocused(focused);
            ConsoleCardFocus.scale(frame, focused ? FOCUS_SCALE : 1f, reducedMotion);
            if (!focused) return;
            updateLegend(false);
            centreOn(column);
        });
        return column;
    }

    /** A focusable column whose first child is the card frame with the focus background. */
    private LinearLayout column(String tag) {
        LinearLayout column = new LinearLayout(getContext());
        column.setId(View.generateViewId());
        column.setTag(tag);
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
        FrameLayout frame = new FrameLayout(getContext());
        frame.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        int margin = dp(ConsoleCardFocus.MARGIN_DP);
        frame.setPadding(margin, margin, margin, margin);
        frame.setClipChildren(false);
        frame.setClipToPadding(false);
        int size = dp(CARD_DP) + 2 * margin;
        column.addView(frame, new LinearLayout.LayoutParams(size, size));
        return column;
    }

    private ConsoleCardFocus focus(FrameLayout frame, int glow) {
        ConsoleCardFocus focus = new ConsoleCardFocus(getResources(),
                dp(ConsoleCardFocus.MARGIN_DP), dp(CORNER_DP));
        focus.setGlowColor(glow);
        frame.setBackground(focus);
        return focus;
    }

    /** The rounded card inside the frame's padding, clipped to its corners, with its edge. */
    private FrameLayout card(FrameLayout frame, GlassEdge edge) {
        FrameLayout card = new FrameLayout(getContext());
        frame.addView(card, match());
        int corner = dp(CORNER_DP);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
            }
        });
        card.setClipToOutline(true);
        if (edge.fill != 0) card.setBackground(edge.fillDrawable());
        card.setForeground(edge);
        return card;
    }

    /** Mockup "live_tag": 36 px glass pill, red dot, tracked "NA ŻYWO" 22 px. */
    private View livePill() {
        LinearLayout pill = new LinearLayout(getContext());
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(9));
        background.setColor(0x960A0A14);
        background.setStroke(Math.max(1, dp(.75f)), 0x46FFFFFF);
        pill.setBackground(background);
        View dot = new View(getContext());
        GradientDrawable red = new GradientDrawable();
        red.setShape(GradientDrawable.OVAL);
        red.setColor(ConsoleHostTileLook.LIVE_RED);
        dot.setBackground(red);
        pill.addView(dot, new LinearLayout.LayoutParams(dp(5), dp(5)));
        TextView label = text(getContext().getString(R.string.console_host_tile_live),
                11, ConsoleEntryShell.TEXT, medium);
        label.setLetterSpacing(1.6f / 22f);
        ConsoleText.centreOnGlyphs(label);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        labelParams.leftMargin = dp(5);
        pill.addView(label, labelParams);
        return pill;
    }

    private void updateLegend(boolean options) {
        if (legendOptions != null && legendOptions == options) return;
        legendOptions = options;
        page.clearLegend();
        String select = getContext().getString(R.string.console_select_hint);
        if (controllerPresent) {
            page.addLegendKey(ControllerGlyphs.text(playStation,
                    ControllerGlyphs.Button.CONFIRM), true, select);
        } else {
            page.addLegendKey("OK", false, select);
        }
        if (!options) return;
        String hostOptions = getContext().getString(R.string.console_host_options_label);
        if (controllerPresent) {
            page.addLegendKey(ControllerGlyphs.text(playStation, ControllerGlyphs.Button.MENU),
                    true, hostOptions);
        } else {
            page.addLegendIcon(R.drawable.ic_console_options, hostOptions);
        }
    }

    private void wireFocus() {
        List<View> columns = new ArrayList<>();
        for (int index = 0; index < row.getChildCount(); index++) columns.add(row.getChildAt(index));
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

    private ImageView icon(int resource, int tint) {
        ImageView icon = new ImageView(getContext());
        icon.setImageResource(resource);
        icon.setColorFilter(tint);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return icon;
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

    private LinearLayout.LayoutParams columnParams() {
        return new LinearLayout.LayoutParams(dp(COLUMN_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams columnText(int topMarginDp) {
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

    private static final ColorFilter DESATURATED;

    static {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(.15f);
        matrix.postConcat(new ColorMatrix(new float[]{
                .8f, 0, 0, 0, 0, 0, .8f, 0, 0, 0, 0, 0, .8f, 0, 0, 0, 0, 0, 1f, 0}));
        DESATURATED = new ColorMatrixColorFilter(matrix);
    }

    private static final class TileViews {
        Tile tile;
        ConsoleHostTileLook look;
        LinearLayout column;
        FrameLayout frame;
        ConsoleCardFocus focus;
        FrameLayout card;
        GlassEdge edge;
        ImageView artwork;
        ImageView monitor;
        View live;
        FrameLayout badge;
        ImageView badgeIcon;
        View ring;
        WakingRing waking;
        TextView name;
        LinearLayout statusRow;
        View dot;
        ObjectAnimator spin;
        TextView status;
    }

    /** The status dot of a host not polled yet: a three-quarter ring in the dot's colour. */
    private static final class CheckingArc extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        CheckingArc(int color, float density) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.25f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            rect.set(getBounds());
            float inset = paint.getStrokeWidth() / 2f;
            rect.inset(inset, inset);
            canvas.drawArc(rect, -90f, 270f, false, paint);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter colorFilter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * Mockup "hairline" (and the fill of "glass"): a 1.5 px rounded edge lit from above, its
     * alpha fading to 35 % at the bottom.
     */
    static final class GlassEdge extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float corner;
        private final float stroke;
        final int fill;
        private int alpha = 60;

        GlassEdge(float corner, float density, int fill) {
            this.corner = corner;
            this.fill = fill;
            stroke = .75f * density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
        }

        void setEdgeAlpha(int alpha) {
            if (this.alpha == alpha) return;
            this.alpha = alpha;
            onBoundsChange(getBounds());
            invalidateSelf();
        }

        Drawable fillDrawable() {
            GradientDrawable surface = new GradientDrawable();
            surface.setCornerRadius(corner);
            surface.setColor(fill);
            return surface;
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect bounds) {
            paint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                    alpha << 24 | 0xFFFFFF, Math.round(alpha * .35f) << 24 | 0xFFFFFF,
                    Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            rect.set(getBounds());
            rect.inset(stroke / 2f, stroke / 2f);
            canvas.drawRoundRect(rect, corner, corner, paint);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter colorFilter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * Faint track and amber progress just outside the focus ring, from top centre (a spinning
     * quarter arc while connecting); bounds are the card,
     * drawn outside them into the frame's unclipped padding.
     */
    private static final class WakingRing extends Drawable {
        private static final long SPIN_MS = 1_200L;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outline = new Path();
        private final Path segment = new Path();
        private final RectF rect = new RectF();
        private final float corner;
        private final float outset;
        private float progress;
        private boolean indeterminate;
        private boolean still;

        WakingRing(float corner, float density) {
            this.corner = corner;
            // Mockup "progress_ring": 4 px wide, 8 px outside the card, white track at 40/255.
            outset = (4f + 1f) * density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setProgress(float progress) {
            if (this.progress == progress) return;
            this.progress = progress;
            invalidateSelf();
        }

        /** A quarter arc circling the card; a still, brighter track with reduced motion. */
        void setIndeterminate(boolean indeterminate, boolean still) {
            if (this.indeterminate == indeterminate && this.still == still) return;
            this.indeterminate = indeterminate;
            this.still = still;
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect bounds) {
            ConsoleCardFocus.cardRect(rect, bounds, 0f, outset);
            float r = corner + outset;
            outline.reset();
            outline.moveTo(rect.centerX(), rect.top);
            outline.lineTo(rect.right - r, rect.top);
            outline.arcTo(new RectF(rect.right - 2 * r, rect.top, rect.right, rect.top + 2 * r),
                    -90f, 90f);
            outline.lineTo(rect.right, rect.bottom - r);
            outline.arcTo(new RectF(rect.right - 2 * r, rect.bottom - 2 * r, rect.right,
                    rect.bottom), 0f, 90f);
            outline.lineTo(rect.left + r, rect.bottom);
            outline.arcTo(new RectF(rect.left, rect.bottom - 2 * r, rect.left + 2 * r,
                    rect.bottom), 90f, 90f);
            outline.lineTo(rect.left, rect.top + r);
            outline.arcTo(new RectF(rect.left, rect.top, rect.left + 2 * r, rect.top + 2 * r),
                    180f, 90f);
            outline.lineTo(rect.centerX(), rect.top);
        }

        @Override
        public void draw(Canvas canvas) {
            if (indeterminate && still) {
                paint.setColor(ConsoleHostTileLook.AMBER);
                paint.setAlpha(0xB0);
            } else {
                paint.setColor(0x28FFFFFF);
            }
            canvas.drawPath(outline, paint);
            if (indeterminate && still) return;
            if (!indeterminate && progress <= 0f) return;
            PathMeasure measure = new PathMeasure(outline, false);
            float length = measure.getLength();
            float start = indeterminate
                    ? length * (SystemClock.uptimeMillis() % SPIN_MS) / SPIN_MS : 0f;
            float end = indeterminate ? start + length / 4f : length * progress;
            segment.reset();
            measure.getSegment(start, Math.min(end, length), segment, true);
            if (end > length) measure.getSegment(0f, end - length, segment, true);
            paint.setColor(ConsoleHostTileLook.AMBER);
            canvas.drawPath(segment, paint);
            if (indeterminate) invalidateSelf();
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter colorFilter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
