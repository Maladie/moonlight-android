package com.limelight.console;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * v4 look of the Community screen (mockups 19-discord-*-v4, UX_MENU_PL.md section 4): colours,
 * type and surfaces shared by its feed, header, detail panel and conversation. Sizes are the
 * mockup's 1080p pixels halved (dp at the TV's density 2).
 */
final class DiscordCommunityStyle {
    static final int TEXT = ConsolePanel.TEXT;
    static final int TEXT_SECONDARY = 0xFFC8CCD6;
    static final int HEADER = ConsolePanel.HEADER;
    static final int INK = ConsolePanel.INK;
    static final int INK_SECONDARY = ConsolePanel.INK_SECONDARY;
    static final int FOCUS_FILL = ConsolePanel.FOCUS_FILL;
    static final int ACCENT = 0xFF73D7FF;
    static final int ONLINE = 0xFF30D694;
    static final int LIVE = 0xFFFF5460;
    static final int BLURPLE = 0xFF5865F2;
    /** Mockup "discord_shell": rgb(6,8,12) at 18 % over the blurred Home. */
    static final int SCRIM = 0x2E06080C;
    /**
     * Feed card glass (tint rgb(20,22,30) 45 %, veil 3 %) as one flat colour: what lies under the
     * card is already the blurred backdrop, so a second blur would change nothing visible.
     */
    static final int CARD_GLASS = 0x77242632;
    static final int CARD_HAIRLINE = 0x28FFFFFF;
    /** Bubbles and composer: white at 30/255; own messages Discord blurple at 235/255. */
    static final int BUBBLE = 0x1EFFFFFF;
    static final int BUBBLE_SELF = 0xEB5865F2;
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private DiscordCommunityStyle() { }

    static TextView text(Context context, CharSequence value, float sp, int color,
                         boolean medium) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setSoundEffectsEnabled(false);
        if (medium) view.setTypeface(MEDIUM);
        return view;
    }

    /** Eyebrow and section header: 24 px semibold capitals, 2 px tracking, grey. */
    static TextView eyebrow(Context context, String value) {
        TextView view = text(context, value.toUpperCase(Locale.ROOT), 12, HEADER, true);
        view.setLetterSpacing(2f / 24f);
        view.setSingleLine(true);
        return view;
    }

    static GradientDrawable surface(Context context, int color, float radiusDp) {
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(color);
        surface.setCornerRadius(radiusDp * density(context));
        return surface;
    }

    static GradientDrawable outlined(Context context, int color, float radiusDp, int stroke) {
        GradientDrawable surface = surface(context, color, radiusDp);
        surface.setStroke(Math.max(1, Math.round(.75f * density(context))), stroke);
        return surface;
    }

    static GradientDrawable dot(int color, int ring, int ringWidth) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        if (ringWidth > 0) dot.setStroke(ringWidth, ring);
        return dot;
    }

    /** Scrolls {@code owner} so {@code target} sits inside its padding with an 8 dp margin. */
    static void reveal(ScrollView owner, View target) {
        target.post(() -> {
            if (!target.isAttachedToWindow() || !owner.isAttachedToWindow()) return;
            Rect bounds = new Rect();
            target.getDrawingRect(bounds);
            owner.offsetDescendantRectToMyCoords(target, bounds);
            int margin = dp(owner.getContext(), 8);
            // The detail panel's padding holds its header inset and pinned legend.
            int top = owner.getScrollY() + owner.getPaddingTop() + margin;
            int bottom = owner.getScrollY() + owner.getHeight() - owner.getPaddingBottom() - margin;
            int next = owner.getScrollY();
            if (bounds.bottom > bottom) next += bounds.bottom - bottom;
            else if (bounds.top < top) next -= top - bounds.top;
            if (next != owner.getScrollY()) owner.smoothScrollTo(0, Math.max(0, next));
        });
    }

    static float density(Context context) {
        return context.getResources().getDisplayMetrics().density;
    }

    static int dp(Context context, float value) {
        return Math.round(value * density(context));
    }
}
