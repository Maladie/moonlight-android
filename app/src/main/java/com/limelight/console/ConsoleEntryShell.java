package com.limelight.console;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import com.limelight.ui.ConsoleClock;
import com.limelight.ui.ControllerGlyphs;

import java.util.Random;

/**
 * Shared frame of the entry screens (UX_MENU_PL.md section 3.7, mockup "gate_backdrop"): a deep
 * navy base with two slowly breathing ambient blooms in the accent colour, faint dust and a
 * vignette, the clock top right and pages, each a centred eyebrow/title/subtitle header, one
 * centred legend and a content slot. Blooms, vignette and dust are small cached bitmaps or a
 * once-drawn view; the blooms breathe in scale and alpha inside their own draw. Pages
 * cross-fade above the background, which stays.
 */
final class ConsoleEntryShell extends FrameLayout {
    static final int CROSS_FADE_MS = 220;
    static final int ACCENT_MS = 1_400;
    static final int ACCENT_REDUCED_MS = 200;
    static final long BREATH_MS = 7_000L;
    static final float BREATH_SCALE = 1.08f;
    static final int BASE_TOP = 0xFF090B12;
    static final int BASE_BOTTOM = 0xFF05060B;
    static final int DEFAULT_ACCENT = 0xFF288CE6;
    /** Mockup blooms: rgb(40,110,230) at 70/255 and rgb(120,70,200) at 58/255. */
    static final float PRIMARY_ALPHA = 70f / 255f;
    static final float SECONDARY_ALPHA = 58f / 255f;
    static final float PRIMARY_SATURATION = .83f;
    static final float PRIMARY_VALUE = .9f;
    static final float SECONDARY_HUE_SHIFT = 53f;
    static final float SECONDARY_SATURATION = .65f;
    static final float SECONDARY_VALUE = .78f;
    static final int TEXT = 0xFFF5F6F9;
    static final int TEXT_SECONDARY = 0xFFC8CCD6;
    static final int HEADER = 0xFF949CAE;
    /**
     * Legend centred on y = 990 px on every screen: the mockups' 1000 (1010 on the PIN
     * screen) left the keys' bottoms at 1020-1027 px, on or past the 5 % title-safe edge of
     * 1026 px that an overscanning TV may still crop.
     */
    static final float LEGEND_CY_DP = 495f;
    static final float LEGEND_HEIGHT_DP = 32f;

    private static Bitmap bloomBitmap;
    private static Bitmap vignetteBitmap;

    private final Blooms blooms;
    private final TextClock clock;
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private Page current;
    private boolean reducedMotion;
    /** Both blooms as HSV, as drawn now, and the colours they are moving to. */
    private final float[][] shown = new float[2][3];
    private final int[] target = new int[2];
    private boolean pageChanged;
    private ValueAnimator accentAnimator;
    private Runnable visibilityListener;

    ConsoleEntryShell(Context context) {
        super(context);
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{BASE_TOP, BASE_BOTTOM}));
        setClipChildren(false);
        blooms = new Blooms(context);
        blooms.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(blooms, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setAccent(DEFAULT_ACCENT);
        View atmosphere = new Atmosphere(context);
        atmosphere.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(atmosphere, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        clock = ConsoleClock.create(context);
        addView(clock, ConsoleClock.topEnd(context));

    }

    /** A screen of the shell; the first one added is the one on screen. */
    Page addPage() {
        Page page = new Page(getContext());
        if (current == null) current = page;
        else page.setVisibility(INVISIBLE);
        addBelowClock(page);
        return page;
    }

    /** Full-screen layer above the pages; the clock stays above it. */
    void addBelowClock(View layer) {
        addView(layer, indexOfChild(clock), new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    boolean shows(Page page) {
        return current == page && isShown();
    }

    /** The previous page is still fading out under the current one. */
    boolean crossFading() {
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child instanceof Page && child != current
                    && child.getVisibility() == VISIBLE) return true;
        }
        return false;
    }

    /**
     * Shows the shell with {@code target} above its background. When the shell is already on
     * screen the previous page cross-fades into the new one; background and clock stay.
     */
    void show(Page target) {
        boolean animate = !reducedMotion && isShown();
        setVisibility(VISIBLE);
        Page previous = current;
        current = target;
        target.animate().cancel();
        target.setVisibility(VISIBLE);
        if (previous == target) {
            target.setAlpha(1f);
            return;
        }
        previous.animate().cancel();
        pageChanged = true;
        if (!animate || !previous.isShown()) {
            target.setAlpha(1f);
            hide(previous);
            return;
        }
        target.setAlpha(0f);
        target.animate().alpha(1f).setDuration(CROSS_FADE_MS).start();
        previous.animate().alpha(0f).setDuration(CROSS_FADE_MS)
                .withEndAction(() -> {
                    if (current != previous) hide(previous);
                }).start();
    }

    /**
     * Draws the shell as it is now without the legend of the page on screen, e.g. into the copy
     * a screen above it blurs (mockup "pin_base": the profile screen without its chrome).
     */
    void drawWithoutLegend(Canvas canvas) {
        int visibility = current.legend.getVisibility();
        current.legend.setVisibility(INVISIBLE);
        draw(canvas);
        current.legend.setVisibility(visibility);
    }

    private static void hide(Page page) {
        // A hidden page stays laid out so its row keeps the scroll position.
        page.setVisibility(INVISIBLE);
        page.setAlpha(1f);
    }

    /** Header, content row and legend of one screen (mockup "gate_header", "legend_v4"). */
    final class Page extends FrameLayout {
        private final TextView eyebrow;
        private final TextView title;
        private final TextView subtitle;
        private final LinearLayout legend;

        private Page(Context context) {
            super(context);
            setClipChildren(false);
            setClipToPadding(false);
            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.VERTICAL);
            header.setGravity(Gravity.CENTER_HORIZONTAL);
            eyebrow = text(12, HEADER, medium);
            eyebrow.setLetterSpacing(2f / 24f);
            eyebrow.setCompoundDrawablePadding(dp(5));
            header.addView(eyebrow, wrap(0));
            title = text(22, TEXT, medium);
            header.addView(title, wrap(9));
            subtitle = text(13, TEXT_SECONDARY, Typeface.DEFAULT);
            header.addView(subtitle, wrap(4));
            LayoutParams headerParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
            // Eyebrow ink from y = 179 px, title ink from about 229 px, as in the mockups.
            headerParams.topMargin = dp(87);
            addView(header, headerParams);

            legend = new LinearLayout(context);
            legend.setOrientation(LinearLayout.HORIZONTAL);
            legend.setGravity(Gravity.CENTER_VERTICAL);
            // At one place on every screen whatever the page shows above it, and tall enough
            // for a controller glyph's whole line box (a shorter view cut the glyph's bottom).
            LayoutParams legendParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(LEGEND_HEIGHT_DP), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            legendParams.topMargin = dp(LEGEND_CY_DP - LEGEND_HEIGHT_DP / 2f);
            addView(legend, legendParams);
        }

        /** The screen body; its top sits under the header, the row centred near y = 520 px. */
        void setContent(View content) {
            LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
            params.topMargin = dp(186);
            addView(content, params);
        }

        void setHeader(CharSequence eyebrowText, CharSequence titleText,
                       CharSequence subtitleText) {
            eyebrow.setText(eyebrowText);
            title.setText(titleText);
            setSubtitle(subtitleText);
        }

        /**
         * Mockup "gate_header" dot: 12 px, 10 px before the eyebrow, on its cap height's
         * middle; 0 removes it.
         */
        void setEyebrowDot(int color) {
            GradientDrawable dot = null;
            if (color != 0) {
                dot = new GradientDrawable();
                dot.setShape(GradientDrawable.OVAL);
                dot.setColor(color);
                int size = dp(6);
                int offset = ConsoleText.dotOffset(eyebrow, size);
                dot.setBounds(0, offset, size, size + offset);
            }
            eyebrow.setCompoundDrawables(dot, null, null, null);
        }

        void setSubtitle(CharSequence value) {
            subtitle.setText(value);
        }

        void clearLegend() {
            legend.removeAllViews();
        }

        /**
         * A key as text: a glass key chip ("OK"), or a controller glyph in a view as tall as
         * its font's line box, centred on the glyph's own ink.
         */
        void addLegendKey(String key, boolean controllerGlyph, String label) {
            if (controllerGlyph) {
                TextView glyph = text(20, TEXT, ControllerGlyphs.typeface(getContext()));
                glyph.setText(key);
                ConsoleText.centreOnInk(glyph, key);
                addLegendItem(glyph, ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, label);
                return;
            }
            TextView glyph = text(11, TEXT, medium);
            GradientDrawable chip = new GradientDrawable();
            chip.setCornerRadius(dp(10));
            chip.setColor(0x1EFFFFFF);
            chip.setStroke(Math.max(1, dp(.75f)), 0x50FFFFFF);
            glyph.setBackground(chip);
            glyph.setMinWidth(dp(20));
            glyph.setPadding(dp(5.5f), 0, dp(5.5f), 0);
            glyph.setText(key);
            ConsoleText.centreOnGlyphs(glyph);
            addLegendItem(glyph, ViewGroup.LayoutParams.WRAP_CONTENT, dp(20), label);
        }

        void addLegendIcon(int icon, String label) {
            ImageView glyph = new ImageView(getContext());
            glyph.setImageResource(icon);
            glyph.setColorFilter(TEXT);
            glyph.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addLegendItem(glyph, dp(15), dp(15), label);
        }

        private void addLegendItem(View glyph, int width, int height, String label) {
            LinearLayout.LayoutParams glyphParams = new LinearLayout.LayoutParams(width, height);
            glyphParams.leftMargin = legend.getChildCount() == 0 ? 0 : dp(22);
            legend.addView(glyph, glyphParams);
            TextView description = text(12, TEXT_SECONDARY, Typeface.DEFAULT);
            description.setText(label);
            LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            descriptionParams.leftMargin = dp(6);
            legend.addView(description, descriptionParams);
        }
    }

    /** The blooms in the hue of an accent colour, e.g. the focused host's. */
    void setAccent(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        setBlooms(Color.HSVToColor(new float[]{hsv[0], PRIMARY_SATURATION, PRIMARY_VALUE}),
                Color.HSVToColor(new float[]{(hsv[0] + SECONDARY_HUE_SHIFT) % 360f,
                        SECONDARY_SATURATION, SECONDARY_VALUE}));
    }

    /**
     * Moves both blooms to new colours along the shorter way round the colour wheel (a straight
     * RGB blend would pass through grey), easing from the colours now on screen, so a change
     * mid-way never jumps back. The first change after a page change takes the cross-fade's
     * time, later ones (focus moving along a row) the slow accent time.
     */
    void setBlooms(int primary, int secondary) {
        if (primary == target[0] && secondary == target[1]) return;
        target[0] = primary;
        target[1] = secondary;
        if (accentAnimator != null) accentAnimator.cancel();
        float[][] to = new float[2][3];
        Color.colorToHSV(primary, to[0]);
        Color.colorToHSV(secondary, to[1]);
        if (!isShown()) {
            applyBlooms(to);
            return;
        }
        float[][] from = {shown[0].clone(), shown[1].clone()};
        accentAnimator = ValueAnimator.ofFloat(0f, 1f);
        accentAnimator.setDuration(reducedMotion ? ACCENT_REDUCED_MS
                : pageChanged ? CROSS_FADE_MS : ACCENT_MS);
        pageChanged = false;
        accentAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        accentAnimator.addUpdateListener(animation -> {
            float t = (Float) animation.getAnimatedValue();
            float[][] now = new float[2][3];
            for (int bloom = 0; bloom < 2; bloom++) {
                float delta = ((to[bloom][0] - from[bloom][0]) % 360f + 540f) % 360f - 180f;
                now[bloom][0] = ((from[bloom][0] + delta * t) % 360f + 360f) % 360f;
                for (int part = 1; part < 3; part++) {
                    now[bloom][part] = from[bloom][part]
                            + (to[bloom][part] - from[bloom][part]) * t;
                }
            }
            applyBlooms(now);
        });
        accentAnimator.start();
    }

    void setReducedMotion(boolean reducedMotion) {
        this.reducedMotion = reducedMotion;
        updateBreathing();
    }

    /**
     * Holds the blooms' breath where it is, e.g. while a menu shows a frozen copy of the
     * shell, so the live parts left visible match the copy around them.
     */
    void setBloomsFrozen(boolean frozen) {
        blooms.setFrozen(frozen);
    }

    private void applyBlooms(float[][] hsv) {
        shown[0] = hsv[0];
        shown[1] = hsv[1];
        blooms.setColors(Color.HSVToColor(hsv[0]), Color.HSVToColor(hsv[1]));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateBreathing();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        updateBreathing();
    }

    /** Runs whenever the shell itself is shown or hidden, e.g. to hide the layer beneath. */
    void setOnVisibilityChange(Runnable listener) {
        visibilityListener = listener;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // May run before the constructor has created the blooms.
        if (blooms != null) updateBreathing();
        if (changedView == this && visibilityListener != null) visibilityListener.run();
    }

    private void updateBreathing() {
        blooms.setBreathing(!reducedMotion && isAttachedToWindow() && isShown());
    }

    /** Mockup "radial": exp(-3 d^2) falloff reaching zero at the edge, white, tinted per bloom. */
    static synchronized Bitmap bloomBitmap() {
        if (bloomBitmap == null) {
            int size = 128;
            int[] pixels = new int[size * size];
            float floor = (float) Math.exp(-3);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float dx = (x + .5f) / size * 2f - 1f;
                    float dy = (y + .5f) / size * 2f - 1f;
                    float d2 = dx * dx + dy * dy;
                    float k = ((float) Math.exp(-3f * d2) - floor) / (1f - floor);
                    int alpha = Math.round(255 * Math.max(0f, Math.min(1f, k)));
                    pixels[y * size + x] = alpha << 24 | 0xFFFFFF;
                }
            }
            bloomBitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
        }
        return bloomBitmap;
    }

    /** Mockup vignette: black at 0.6 x smoothstep(0.6, 1.5, sqrt(0.8 x^2 + y^2)). */
    private static synchronized Bitmap vignetteBitmap() {
        if (vignetteBitmap == null) {
            int width = 64;
            int height = 36;
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    float nx = (x + .5f) / width * 2f - 1f;
                    float ny = (y + .5f) / height * 2f - 1f;
                    float t = Math.max(0f, Math.min(1f,
                            ((float) Math.sqrt(.8f * nx * nx + ny * ny) - .6f) / .9f));
                    float k = .6f * t * t * (3f - 2f * t);
                    pixels[y * width + x] = Math.round(255 * k) << 24;
                }
            }
            vignetteBitmap = Bitmap.createBitmap(pixels, width, height,
                    Bitmap.Config.ARGB_8888);
        }
        return vignetteBitmap;
    }

    /**
     * Both blooms in one view that breathes inside onDraw. Animating view properties (scale,
     * translation) instead would report a content change to accessibility on every frame, which
     * keeps TalkBack and UiAutomator from ever seeing the screen settle.
     */
    private static final class Blooms extends View {
        /** Mockup centres at 1080p: (600, 380) radius 900 and (1450, 760) radius 820. */
        private static final float[][] SPOTS = {
                {600f / 1920f, 380f / 1080f, 900f / 1920f, PRIMARY_ALPHA, 0f},
                {1450f / 1920f, 760f / 1080f, 820f / 1920f, SECONDARY_ALPHA, .5f}};
        private final Paint[] paints = {
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG),
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG)};
        private final RectF rect = new RectF();
        private boolean breathing;
        private long start;
        private long frozenAt = -1L;

        Blooms(Context context) {
            super(context);
        }

        void setColors(int primary, int secondary) {
            paints[0].setColorFilter(new PorterDuffColorFilter(primary, PorterDuff.Mode.SRC_IN));
            paints[1].setColorFilter(new PorterDuffColorFilter(secondary,
                    PorterDuff.Mode.SRC_IN));
            invalidate();
        }

        void setFrozen(boolean frozen) {
            long now = AnimationUtils.currentAnimationTimeMillis();
            if (frozen == frozenAt >= 0L) return;
            if (frozen) {
                frozenAt = now;
            } else {
                start += now - frozenAt;
                frozenAt = -1L;
            }
            invalidate();
        }

        void setBreathing(boolean breathing) {
            if (this.breathing == breathing) return;
            this.breathing = breathing;
            start = AnimationUtils.currentAnimationTimeMillis();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            long now = frozenAt >= 0L ? frozenAt : AnimationUtils.currentAnimationTimeMillis();
            for (int index = 0; index < SPOTS.length; index++) {
                float[] spot = SPOTS[index];
                // Ease in and out between rest and full breath, phase-shifted per bloom.
                float eased = 0f;
                if (breathing) {
                    float cycle = ((now - start) / (float) (2 * BREATH_MS) + spot[4]) % 1f;
                    float linear = cycle < .5f ? cycle * 2f : 2f - cycle * 2f;
                    eased = (float) (1 - Math.cos(Math.PI * linear)) / 2f;
                }
                float radius = spot[2] * width * (1f + (BREATH_SCALE - 1f) * eased);
                float cx = spot[0] * width;
                float cy = spot[1] * height;
                rect.set(cx - radius, cy - radius, cx + radius, cy + radius);
                paints[index].setAlpha(Math.round(255 * spot[3] * (.8f + .4f * eased)));
                canvas.drawBitmap(bloomBitmap(), null, rect, paints[index]);
            }
            if (breathing && frozenAt < 0L) postInvalidateOnAnimation();
        }
    }

    /** Static dust and vignette above the blooms, drawn once (it is never invalidated). */
    static final class Atmosphere extends View {
        /** Mockup dust: 26 dots, x 80..1840, y 300..900, r 1.5..3.5 px, alpha 18..45. */
        private static final int DUST = 26;
        private static final int DUST_COLOR = 0xFFFFDCBE;
        private final float[] dust = new float[DUST * 4];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Rect bounds = new Rect();

        Atmosphere(Context context) {
            super(context);
            setWillNotDraw(false);
            Random random = new Random(11);
            for (int index = 0; index < DUST; index++) {
                dust[index * 4] = (80f + random.nextFloat() * 1760f) / 1920f;
                dust[index * 4 + 1] = (300f + random.nextFloat() * 600f) / 1080f;
                dust[index * 4 + 2] = (1.5f + random.nextFloat() * 2f) / 1920f;
                dust[index * 4 + 3] = 18f + random.nextFloat() * 27f;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            paint.setColor(DUST_COLOR);
            for (int index = 0; index < DUST; index++) {
                paint.setAlpha(Math.round(dust[index * 4 + 3]));
                canvas.drawCircle(dust[index * 4] * width, dust[index * 4 + 1] * height,
                        dust[index * 4 + 2] * width, paint);
            }
            paint.setAlpha(255);
            bounds.set(0, 0, width, height);
            canvas.drawBitmap(vignetteBitmap(), null, bounds, paint);
        }
    }

    private TextView text(float sizeSp, int color, Typeface typeface) {
        TextView view = new TextView(getContext());
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(typeface);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        return view;
    }

    private LinearLayout.LayoutParams wrap(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
