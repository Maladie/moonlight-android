package com.limelight.console;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Small floating Options menu of the profile screen (UX_MENU_PL.md sections 3.7 and 4.3, mockup
 * "profile_options_v4"): the shell behind is blurred lightly once, the avatar it belongs to
 * stays sharp through a soft hole, and a glass card with one or two rows floats beside it. It
 * is a child of the profile page, so it leaves the screen with it. The avatar keeps the focus;
 * the owner feeds keys to {@link #handleKey}.
 */
final class ConsoleProfileOptionsMenu extends FrameLayout {
    /** Mockup: blur 10 px on a quarter-size copy, tint rgb(6,8,12) at 15 %. */
    static final int SNAPSHOT_DIVISOR = 4;
    static final float BLUR_DP = 5f;
    static final int TINT = 0x2606080C;
    static final int FADE_MS = 150;
    /** Mockup card: 500 px wide, 72 px rows, 12 px above and below, 28 px corners. */
    static final int WIDTH_DP = 250;
    static final int ROW_DP = 36;
    static final int CORNER_DP = 14;
    /** Its right edge sits 124 px left of the avatar centre, its top 60 px above it. */
    static final int ANCHOR_GAP_DP = 62;
    static final int ANCHOR_RISE_DP = 30;
    /**
     * Mockup "glass": what lies under the card blurred again by 46 px, saturated x1.35, tinted
     * rgb(22,24,34) at 66 % and veiled 6/255 white; edge 52/255, lit from above.
     */
    static final float GLASS_BLUR_DP = 23f;
    private static final float[] GLASS = ConsoleGlass.colorMatrix(1.35f, 0x161822, .66f,
            6f / 255f);
    static final int EDGE_ALPHA = 52;
    /** Mockup "soft_shadow" under the card: black 190/255, blur 34 px, 22 px down. */
    static final ConsoleCardFocus.Layer SHADOW = new ConsoleCardFocus.Layer(17f, 11f, 0f);
    static final float SHADOW_ALPHA = 190f / 255f;
    /** Behind the selected row: white 40/255, blur 16 px. */
    static final ConsoleCardFocus.Layer ROW_HALO = new ConsoleCardFocus.Layer(8f, 0f, 0f);
    static final float ROW_HALO_ALPHA = 40f / 255f;
    static final int CYAN = 0xFF73D7FF;

    private final ConsoleEntryShell shell;
    private final ViewGroup page;
    private final View backdrop;
    private final FrameLayout card;
    private final LinearLayout list;
    private final RectF keep = new RectF();
    private final int cardLeft;
    private final int cardTop;
    private final List<Runnable> actions = new ArrayList<>();
    private final Runnable onDismiss;
    private final ColorStateList ink = new ColorStateList(
            new int[][]{{android.R.attr.state_selected}, {}},
            new int[]{ConsolePanel.INK, ConsoleEntryShell.TEXT});
    private Toggle toggle;
    private int selected;
    /** A confirm key went down while the menu was open; only its release runs a row. */
    private boolean confirmDown;

    /** {@code extraLines}: status lines below the first that must stay sharp. */
    ConsoleProfileOptionsMenu(ConsoleEntryShell shell, ViewGroup page, View anchor,
                              int extraLines, Runnable onDismiss) {
        super(shell.getContext());
        this.shell = shell;
        this.page = page;
        this.onDismiss = onDismiss;
        setClipChildren(false);
        int[] anchorAt = new int[2];
        int[] shellAt = new int[2];
        anchor.getLocationOnScreen(anchorAt);
        shell.getLocationOnScreen(shellAt);
        // The anchor is scaled about its centre, which therefore stays put.
        float cx = anchorAt[0] - shellAt[0] + anchor.getWidth() * anchor.getScaleX() / 2f;
        float cy = anchorAt[1] - shellAt[1] + anchor.getHeight() * anchor.getScaleY() / 2f;
        // Mockup "keep" box: 280 x 340 px from 124 px above the avatar centre.
        keep.set(cx - dp(70), cy - dp(62), cx + dp(70), cy + dp(108 + 17 * extraLines));

        backdrop = new View(getContext());
        backdrop.setOnClickListener(view -> onDismiss.run());
        backdrop.setFocusable(false);
        addView(backdrop, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        int margin = dp(ConsoleCardFocus.MARGIN_DP);
        FrameLayout frame = new FrameLayout(getContext());
        frame.setPadding(margin, margin, margin, margin);
        frame.setClipChildren(false);
        frame.setClipToPadding(false);
        ConsoleCardFocus shadow = new ConsoleCardFocus(getResources(), margin, dp(CORNER_DP));
        shadow.setLayers(SHADOW, SHADOW, SHADOW);
        shadow.setShadowAlpha(SHADOW_ALPHA);
        frame.setBackground(shadow);
        ConsoleHostSelectView.GlassEdge edge = new ConsoleHostSelectView.GlassEdge(
                dp(CORNER_DP), getResources().getDisplayMetrics().density, 0);
        edge.setEdgeAlpha(EDGE_ALPHA);
        card = new FrameLayout(getContext());
        card.setClipChildren(false);
        card.setForeground(edge);
        list = new LinearLayout(getContext());
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setPadding(dp(5), dp(6), dp(5), dp(6));
        card.addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.addView(card, new LayoutParams(dp(WIDTH_DP),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LayoutParams frameParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
        // Left of the avatar as in the mockup; right of it where the left would leave the
        // safe area.
        float left = cx - dp(ANCHOR_GAP_DP) - dp(WIDTH_DP);
        if (left < dp(48)) left = cx + dp(ANCHOR_GAP_DP);
        cardLeft = Math.round(left);
        cardTop = Math.round(cy) - dp(ANCHOR_RISE_DP);
        frameParams.leftMargin = cardLeft - margin;
        frameParams.topMargin = cardTop - margin;
        addView(frame, frameParams);
    }

    /** A switch row; at most one per menu. */
    void addToggle(String label, boolean checked, Runnable action) {
        toggle = new Toggle(getContext());
        toggle.checked = checked;
        LinearLayout row = row(0, label, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(30), dp(17));
        params.leftMargin = dp(8);
        row.addView(toggle, params);
    }

    void addAction(int icon, String label, Runnable action) {
        row(icon, label, action);
    }

    void setChecked(boolean checked) {
        if (toggle == null || toggle.checked == checked) return;
        toggle.checked = checked;
        toggle.invalidate();
    }

    /**
     * Copies and blurs the shell as it is now, so open the menu before changing anything it
     * should not show; a previous menu still fading out of the page is removed first.
     */
    void open(boolean reducedMotion) {
        for (int index = page.getChildCount() - 1; index >= 0; index--) {
            if (page.getChildAt(index) instanceof ConsoleProfileOptionsMenu) {
                page.removeViewAt(index);
            }
        }
        // The avatar's hole shows the live shell: it must not breathe against the copy.
        shell.setBloomsFrozen(true);
        snapshot(new RectF(cardLeft, cardTop, cardLeft + dp(WIDTH_DP),
                cardTop + dp(12) + actions.size() * dp(ROW_DP)));
        page.addView(this, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        select(0);
        if (reducedMotion) return;
        setAlpha(0f);
        animate().alpha(1f).setDuration(FADE_MS).start();
    }

    void close(boolean reducedMotion) {
        shell.setBloomsFrozen(false);
        animate().cancel();
        if (reducedMotion || !isShown()) {
            page.removeView(this);
            return;
        }
        animate().alpha(0f).setDuration(FADE_MS)
                .withEndAction(() -> page.removeView(this)).start();
    }

    /** Every navigation key belongs to the open menu; returns whether consumed. */
    boolean handleKey(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) select(Math.max(0, selected - 1));
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) select(Math.min(actions.size() - 1, selected + 1));
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BUTTON_START:
                if (up) onDismiss.run();
                return true;
            case KeyEvent.KEYCODE_TAB:
                return true;
            default:
                // Space and the like would otherwise click the avatar beneath.
                if (!ConsoleHostSelectView.isConfirmKey(event.getKeyCode())
                        && event.getKeyCode() != KeyEvent.KEYCODE_SPACE) return false;
                if (down) {
                    confirmDown = true;
                } else if (up && confirmDown) {
                    confirmDown = false;
                    actions.get(selected).run();
                }
                return true;
        }
    }

    private void select(int index) {
        selected = index;
        for (int row = 0; row < list.getChildCount(); row++) {
            list.getChildAt(row).setSelected(row == index);
        }
        // The avatar keeps the focus, so the row is spoken instead of focused.
        announceForAccessibility(list.getChildAt(index).getContentDescription());
    }

    /** Mockup row: text 28 px at +36 px, or a 30 px icon centred at +52 px and text at +84. */
    private LinearLayout row(int icon, String label, Runnable action) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13), 0, dp(9), 0);
        row.setContentDescription(label);
        // Section 4.2 text focus: near-white fill with dark ink, 20 px corners, on a halo.
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(10));
        fill.setColor(ConsolePanel.FOCUS_FILL);
        ConsoleCardFocus halo = new ConsoleCardFocus(getResources(), 0f, dp(10));
        halo.setLayers(ROW_HALO, ROW_HALO, ROW_HALO);
        halo.setShadowColor(0xFFFFFFFF);
        halo.setShadowAlpha(ROW_HALO_ALPHA);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_selected},
                new LayerDrawable(new Drawable[]{halo, fill}));
        row.setBackground(background);
        if (icon != 0) {
            ImageView glyph = new ImageView(getContext());
            glyph.setImageResource(icon);
            glyph.setImageTintList(ink);
            LinearLayout.LayoutParams glyphParams = new LinearLayout.LayoutParams(
                    dp(15), dp(15));
            glyphParams.leftMargin = dp(.5f);
            glyphParams.rightMargin = dp(8.5f);
            row.addView(glyph, glyphParams);
        }
        TextView text = new TextView(getContext());
        text.setText(label);
        text.setTextSize(14);
        text.setTextColor(ink);
        text.setTypeface(Typeface.DEFAULT);
        text.setSingleLine(true);
        ConsoleText.centreOnGlyphs(text);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int index = actions.size();
        actions.add(action);
        row.setOnClickListener(view -> {
            select(index);
            action.run();
        });
        row.setFocusable(false);
        list.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)));
        return row;
    }

    /**
     * The shell drawn once at quarter size, blurred and tinted: the full-screen backdrop, cut
     * open softly around the avatar so it shows through live, and the glass of the card at
     * {@code box} (shell coordinates), blurred once more with the card's shadow under it.
     */
    private void snapshot(RectF box) {
        float density = getResources().getDisplayMetrics().density;
        float scale = 1f / SNAPSHOT_DIVISOR;
        int width = Math.max(1, shell.getWidth() / SNAPSHOT_DIVISOR);
        int height = Math.max(1, shell.getHeight() / SNAPSHOT_DIVISOR);
        Bitmap copy = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(copy);
        canvas.save();
        canvas.scale(scale, scale);
        shell.draw(canvas);
        canvas.restore();
        int[] pixels = new int[width * height];
        copy.getPixels(pixels, 0, width, 0, 0, width, height);
        ConsoleMenuBackdrop.blur(pixels, width, height, ConsoleMenuBackdrop.boxRadius(
                BLUR_DP * density * scale, ConsoleMenuBackdrop.BOX_PASSES),
                ConsoleMenuBackdrop.BOX_PASSES);
        copy.setPixels(pixels, 0, width, 0, 0, width, height);
        canvas.drawColor(TINT);

        Bitmap under = copy.copy(Bitmap.Config.ARGB_8888, true);
        Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        shade.setColor(0xFF000000);
        shade.setAlpha(Math.round(255 * SHADOW_ALPHA));
        shade.setMaskFilter(gaussian(SHADOW.blurDp * density * scale));
        box.set(box.left * scale, box.top * scale, box.right * scale, box.bottom * scale);
        box.offset(0f, SHADOW.dyDp * density * scale);
        new Canvas(under).drawRoundRect(box, dp(CORNER_DP) * scale, dp(CORNER_DP) * scale,
                shade);
        box.offset(0f, -SHADOW.dyDp * density * scale);
        under.getPixels(pixels, 0, width, 0, 0, width, height);
        ConsoleMenuBackdrop.blur(pixels, width, height, ConsoleMenuBackdrop.boxRadius(
                GLASS_BLUR_DP * density * scale, ConsoleMenuBackdrop.BOX_PASSES),
                ConsoleMenuBackdrop.BOX_PASSES);
        int left = Math.max(0, Math.min(width - 1, Math.round(box.left)));
        int top = Math.max(0, Math.min(height - 1, Math.round(box.top)));
        int glassWidth = Math.max(1, Math.min(width - left, Math.round(box.width())));
        int glassHeight = Math.max(1, Math.min(height - top, Math.round(box.height())));
        int[] glass = new int[glassWidth * glassHeight];
        for (int y = 0; y < glassHeight; y++) {
            System.arraycopy(pixels, (top + y) * width + left, glass, y * glassWidth,
                    glassWidth);
        }
        ConsoleGlass.applyColorMatrix(glass, GLASS);
        card.setBackground(new Glass(Bitmap.createBitmap(glass, glassWidth, glassHeight,
                Bitmap.Config.ARGB_8888), dp(CORNER_DP)));

        Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
        hole.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        // Mockup: 60 px corners, edge feathered by a 6 px blur.
        hole.setMaskFilter(gaussian(dp(3) * scale));
        keep.set(keep.left * scale, keep.top * scale, keep.right * scale, keep.bottom * scale);
        canvas.drawRoundRect(keep, dp(30) * scale, dp(30) * scale, hole);
        backdrop.setBackground(new BitmapDrawable(getResources(), copy));
    }

    /** A mask blur of the given Gaussian sigma (the filter's radius is not the sigma). */
    private static BlurMaskFilter gaussian(float sigma) {
        return new BlurMaskFilter(Math.max(.1f, (sigma - .5f) / .57735f),
                BlurMaskFilter.Blur.NORMAL);
    }

    /** The card's glass bitmap stretched over a rounded rectangle. */
    private static final class Glass extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix matrix = new Matrix();
        private final RectF rect = new RectF();
        private final Bitmap bitmap;
        private final float corner;

        Glass(Bitmap bitmap, float corner) {
            this.bitmap = bitmap;
            this.corner = corner;
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
            rect.set(getBounds());
            canvas.drawRoundRect(rect, corner, corner, paint);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter colorFilter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * Mockup "toggle_sprite": 60 x 34 px track, 12 px knob; ink track and white knob on the
     * selected row, cyan (off: grey) track and ink knob otherwise.
     */
    private static final class Toggle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        boolean checked;

        Toggle(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            boolean focused = isSelected();
            float height = getHeight();
            float radius = height / 2f;
            track.set(0, 0, getWidth(), height);
            paint.setColor(checked ? (focused ? ConsolePanel.INK : CYAN)
                    : focused ? 0xFFBEC2CC : 0xFF5A606E);
            canvas.drawRoundRect(track, radius, radius, paint);
            paint.setColor(focused ? 0xFFFFFFFF : ConsolePanel.INK);
            canvas.drawCircle(checked ? getWidth() - radius : radius, radius,
                    height * 12f / 34f, paint);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
