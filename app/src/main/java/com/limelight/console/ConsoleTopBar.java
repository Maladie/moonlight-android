package com.limelight.console;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The slim home top bar (UX_MENU_PL.md section 3.1). Left: the host status, bare at rest and a
 * light pill with dark text when focused (section 4.2, text controls). Right: bare icons with a
 * frosted circle and a label under the focused one (section 4.2, bare icons); Discord and the USB
 * microphone show only while they are in use. The caller feeds the facts it already renders and
 * performs the actions.
 */
final class ConsoleTopBar extends FrameLayout {
    /** The row of host status and icons, as tall as the bar before 6a. */
    private static final int ROW_DP = 56;
    /** Room under the row for the focused icon's label, so it never covers the carousel. */
    private static final int LABEL_RESERVE_DP = 14;
    static final int HEIGHT_DP = ROW_DP + LABEL_RESERVE_DP;
    private static final int HOST_PAD_X_DP = 10;
    /** Compose for TV IconButton "Large": a 56 dp item (the 112 px pitch at 1080p)... */
    private static final int ITEM_DP = 56;
    /** ...around a 28 dp icon canvas holding Material-keyline glyphs. */
    private static final int CANVAS_DP = 28;
    /** The focus circle sits 4 dp inside the item, the same for every icon. */
    private static final int FOCUS_INSET_DP = 4;
    /** The avatar circle is 20/24 of the canvas (about 46 px). */
    private static final float AVATAR_DIAMETER_DP = CANVAS_DP * 20f / 24f;
    private static final int FROST = 0x33FFFFFF;
    private static final int STATUS_TEXT = 0xFFC9CFDA;
    /** Ring around a badge in the bar's dark backdrop, so the badge reads as cut into the icon. */
    private static final int BADGE_RING = 0xFF0B0D12;
    static final int DISCORD_UNREAD = 0xFF3B82F6;

    private final BooleanSupplier reducedMotion;
    private final LinearLayout hostStatus;
    private final TextView hostName;
    private final TextView hostLine;
    private final GradientDrawable statusDot = new GradientDrawable();
    private final Icon discord;
    private final Icon microphone;
    private final Icon search;
    private final Icon profile;
    private final Icon settings;
    private final TextView label;
    private int belowId = View.NO_ID;
    private int discordStatusColor;
    private String discordDescription = "";
    private boolean discordUnread;

    ConsoleTopBar(Context context, BooleanSupplier reducedMotion, Runnable onHost,
                  Runnable onDiscord, Runnable onMicrophone, Runnable onSearch,
                  Runnable onProfile, Runnable onSettings) {
        super(context);
        this.reducedMotion = reducedMotion;
        setClipChildren(false);
        setClipToPadding(false);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP),
                Gravity.TOP));

        hostStatus = new LinearLayout(context);
        hostStatus.setId(View.generateViewId());
        hostStatus.setTag("global.host");
        hostStatus.setOrientation(LinearLayout.HORIZONTAL);
        hostStatus.setGravity(Gravity.CENTER_VERTICAL);
        hostStatus.setFocusable(true);
        hostStatus.setClickable(true);
        hostStatus.setSoundEffectsEnabled(false);
        hostStatus.setBackground(hostFocusFill());
        hostStatus.setPadding(dp(HOST_PAD_X_DP), dp(5), dp(HOST_PAD_X_DP + 6), dp(5));
        hostStatus.setOnClickListener(view -> onHost.run());
        hostStatus.setOnFocusChangeListener((view, focused) -> scale(view, focused ? 1.03f : 1f));
        ImageView glyph = new ImageView(context);
        glyph.setImageResource(R.drawable.ic_console_bar_monitor);
        glyph.setImageTintList(focusColors(Color.WHITE, ConsolePanel.INK));
        glyph.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams glyphParams = new LinearLayout.LayoutParams(dp(CANVAS_DP),
                dp(CANVAS_DP));
        // The name keeps its pre-6a x: 17 dp glyph + 15 dp gap = 28 dp canvas + 4 dp gap.
        glyphParams.rightMargin = dp(4);
        hostStatus.addView(glyph, glyphParams);
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setDuplicateParentStateEnabled(true);
        hostName = text(ConsolePanel.LABEL_SP, focusColors(Color.WHITE, ConsolePanel.INK));
        hostName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        texts.addView(hostName);
        hostLine = text(ConsolePanel.SECONDARY_SP,
                focusColors(STATUS_TEXT, ConsolePanel.INK_SECONDARY));
        int dot = dp(6);
        statusDot.setShape(GradientDrawable.OVAL);
        statusDot.setSize(dot, dot);
        hostLine.setCompoundDrawablesRelativeWithIntrinsicBounds(statusDot, null, null, null);
        hostLine.setCompoundDrawablePadding(dp(6));
        texts.addView(hostLine);
        hostStatus.addView(texts);
        row.addView(hostStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));

        label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, ConsolePanel.CAPTION_SP);
        label.setTextColor(Color.WHITE);
        label.setSingleLine(true);
        label.setIncludeFontPadding(false);
        label.setShadowLayer(dp(2), 0, dp(1), 0xE0000000);
        label.setVisibility(View.INVISIBLE);
        addView(label, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));

        discord = icon(row, "global.discord", R.drawable.ic_console_discord,
                R.string.console_action_discord, onDiscord);
        microphone = icon(row, "global.usb_microphone", R.drawable.ic_console_microphone,
                R.string.console_action_usb_microphone, onMicrophone);
        search = icon(row, "global.search", R.drawable.ic_console_bar_search,
                R.string.playnite_legend_search, onSearch);
        profile = icon(row, "global.profile", 0, 0, onProfile);
        profile.setImageTintList(null);
        // ProfileToolbarDrawable draws its circle at 0.76 of its bounds.
        int avatarInset = Math.round((dp(ITEM_DP) - AVATAR_DIAMETER_DP / .76f * density()) / 2f);
        profile.setPadding(avatarInset, avatarInset, avatarInset, avatarInset);
        settings = icon(row, "global.settings", R.drawable.ic_console_settings,
                R.string.console_settings_title, onSettings);
        // Keeps the pre-6a icon centres: the last 48 dp target used to end at the content edge.
        ((LinearLayout.LayoutParams) settings.getLayoutParams()).rightMargin =
                -dp((ITEM_DP - 48) / 2);
        discord.setVisibility(View.GONE);
        microphone.setVisibility(View.GONE);
        profile.setVisibility(View.GONE);
        wire();
    }

    /** Discord shows only while its integration is configured for an allowed profile. */
    static boolean discordVisible(boolean allowed, boolean configured) {
        return allowed && configured;
    }

    /** The USB microphone shows only while it is enabled and allowed for the profile. */
    static boolean microphoneVisible(boolean allowed, boolean enabled) {
        return allowed && enabled;
    }

    /** An unread message outranks the connection state on the Discord badge. */
    static int discordBadge(boolean unread, int statusColor) {
        return unread ? DISCORD_UNREAD : statusColor;
    }

    /** A running stream is the healthy state of the bar, so it shares the online green. */
    static int statusDotColor(ConsoleHostPresentation.State state) {
        return ConsoleHostPresentation.color(state == ConsoleHostPresentation.State.ACTIVE_SESSION
                ? ConsoleHostPresentation.State.ONLINE : state);
    }

    View hostStatus() {
        return hostStatus;
    }

    void showHost(String name, String status, int dotColor, String description) {
        hostName.setText(name);
        hostLine.setText(status);
        hostLine.setVisibility(View.VISIBLE);
        statusDot.setColor(dotColor);
        hostStatus.setContentDescription(description);
        hostStatus.setEnabled(true);
        hostStatus.setAlpha(1f);
    }

    void showNoHost(String text) {
        hostName.setText(text);
        hostLine.setVisibility(View.GONE);
        hostStatus.setContentDescription(text);
        hostStatus.setEnabled(false);
        hostStatus.setAlpha(.45f);
    }

    /** A null avatar hides the profile icon. */
    void showProfile(Drawable avatar, String name, String description) {
        if (avatar != null) {
            profile.setImageDrawable(avatar);
            profile.label = name;
            profile.setContentDescription(description);
            if (profile.hasFocus()) showLabel(profile);
        }
        setShown(profile, avatar != null);
    }

    void showDiscord(boolean visible) {
        setShown(discord, visible);
    }

    void setDiscordStatus(int color, String description) {
        discordStatusColor = color;
        discordDescription = description;
        updateDiscord();
    }

    void setDiscordUnread(boolean unread) {
        discordUnread = unread;
        updateDiscord();
    }

    void showMicrophone(boolean visible, int badgeColor, String description) {
        microphone.setBadge(badgeColor);
        microphone.setContentDescription(description);
        setShown(microphone, visible);
    }

    /** Down from every bar item goes to {@code below}; left and right stay inside the bar. */
    void wireFocus(View below) {
        belowId = below == null ? View.NO_ID : below.getId();
        wire();
    }

    private void updateDiscord() {
        discord.setBadge(discordBadge(discordUnread, discordStatusColor));
        if (discordDescription.isEmpty()) return;
        discord.setContentDescription(discordUnread ? discordDescription + ". "
                + getContext().getString(R.string.overlay_discord_unread) : discordDescription);
    }

    private void setShown(Icon icon, boolean shown) {
        if ((icon.getVisibility() == View.VISIBLE) == shown) return;
        boolean hadFocus = icon.hasFocus();
        icon.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (hadFocus) search.requestFocus();
        wire();
    }

    private void wire() {
        List<View> items = new ArrayList<>();
        items.add(hostStatus);
        for (Icon icon : new Icon[]{discord, microphone, search, profile, settings}) {
            if (icon.getVisibility() == View.VISIBLE) items.add(icon);
        }
        for (int index = 0; index < items.size(); index++) {
            View item = items.get(index);
            item.setNextFocusUpId(item.getId());
            item.setNextFocusDownId(belowId);
            item.setNextFocusLeftId(items.get(Math.max(0, index - 1)).getId());
            item.setNextFocusRightId(items.get(Math.min(items.size() - 1, index + 1)).getId());
        }
    }

    private Icon icon(LinearLayout row, String tag, int glyph, int labelRes, Runnable action) {
        Icon icon = new Icon(getContext());
        icon.setId(View.generateViewId());
        icon.setTag(tag);
        if (glyph != 0) icon.setImageResource(glyph);
        icon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // Background first: its inset focus circle would otherwise replace the padding.
        icon.setBackground(frost());
        int inset = dp((ITEM_DP - CANVAS_DP) / 2);
        icon.setPadding(inset, inset, inset, inset);
        icon.setFocusable(true);
        icon.setClickable(true);
        icon.setSoundEffectsEnabled(false);
        if (labelRes != 0) {
            icon.label = getContext().getString(labelRes);
            icon.setContentDescription(icon.label);
        }
        icon.setOnClickListener(view -> action.run());
        icon.setOnFocusChangeListener((view, focused) -> {
            scale(view, focused ? 1.1f : 1f);
            if (focused) showLabel(icon);
            else label.setVisibility(View.INVISIBLE);
        });
        row.addView(icon, new LinearLayout.LayoutParams(dp(ITEM_DP), dp(ITEM_DP)));
        return icon;
    }

    private void showLabel(Icon icon) {
        label.setText(icon.label);
        label.setVisibility(View.VISIBLE);
        label.post(() -> {
            if (!icon.hasFocus()) return;
            View row = (View) icon.getParent();
            float center = row.getLeft() + icon.getLeft() + icon.getWidth() / 2f;
            label.setTranslationX(Math.min(center - label.getWidth() / 2f,
                    getWidth() - label.getWidth()));
            // Just under the scaled focus circle, inside the bar's label reserve.
            label.setTranslationY(row.getTop() + icon.getBottom() - dp(1));
        });
    }

    private void scale(View view, float scale) {
        view.animate().cancel();
        if (reducedMotion.getAsBoolean() || !view.isLaidOut()) {
            view.setScaleX(scale);
            view.setScaleY(scale);
        } else {
            view.animate().scaleX(scale).scaleY(scale).setDuration(getResources()
                    .getInteger(R.integer.console_motion_focus_ms)).start();
        }
    }

    private Drawable hostFocusFill() {
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(HEIGHT_DP));
        pill.setColor(ConsolePanel.FOCUS_FILL);
        return focused(pill);
    }

    private Drawable frost() {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(FROST);
        circle.setStroke(Math.max(1, dp(1) / 2), 0x26FFFFFF);
        int inset = dp(FOCUS_INSET_DP);
        return focused(new InsetDrawable(circle, inset, inset, inset, inset));
    }

    private static Drawable focused(Drawable drawable) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, drawable);
        states.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        return states;
    }

    private static ColorStateList focusColors(int rest, int focused) {
        return new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{focused, rest});
    }

    private TextView text(float sp, ColorStateList colors) {
        TextView view = new TextView(getContext());
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(colors);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setMaxWidth(dp(300));
        view.setIncludeFontPadding(false);
        view.setDuplicateParentStateEnabled(true);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * density());
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    /** A bare icon with an optional state badge at the glyph's lower right. */
    private final class Icon extends ImageButton {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        String label = "";
        private int badge;

        Icon(Context context) {
            super(context);
        }

        void setBadge(int color) {
            if (badge == color) return;
            badge = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (badge == 0) return;
            // Lower right of the 28 dp canvas, just inside it.
            float x = getWidth() / 2f + dp(CANVAS_DP / 2 - 3);
            float y = getHeight() / 2f + dp(CANVAS_DP / 2 - 3);
            paint.setColor(BADGE_RING);
            canvas.drawCircle(x, y, dp(5), paint);
            paint.setColor(badge);
            canvas.drawCircle(x, y, dp(3), paint);
        }
    }
}
