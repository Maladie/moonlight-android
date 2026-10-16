package com.limelight.console;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.ui.ControllerGlyphs;

/**
 * PIN screen (UX_MENU_PL.md section 3.7 "PIN", mockups 14-16 "pin_remote_v4",
 * "pin_gamepad_v4" and "pin_error_v4") as a page of the entry shell: the screen beneath copied
 * once and blurred, the chosen avatar moving up to the top, four dots, and either a 3 x 4 glass
 * keypad for the remote or a 2 x 5 digit-to-button strip for a controller. A thin ring around
 * the avatar counts a lockout down or spins while the PIN is checked. Only the number of digits
 * entered reaches this view; the activity owns the PIN, its checks and the window's
 * FLAG_SECURE.
 */
final class ConsolePinEntryView extends FrameLayout {
    interface Listener {
        /** A keypad key: 0 to 9, or {@link #DELETE}. */
        void onDigit(int digit);
    }

    static final int DELETE = -1;
    /** Mockup "backdrop": blur 32 px on a quarter-size copy, tint rgb(6,8,12) at 30 %. */
    static final int SNAPSHOT_DIVISOR = 4;
    static final float BLUR_DP = 16f;
    static final int TINT = 0x4D06080C;
    /** Mockup "glass" keys: blur 24 px, saturation x1.35, rgb(18,20,30) at 45 %, veil 10/255. */
    static final float GLASS_BLUR_DP = 12f;
    private static final float[] GLASS = ConsoleGlass.colorMatrix(1.35f, 0x12141E, .45f, 0f);
    static final int VEIL_ALPHA = 10;
    static final int EDGE_ALPHA = 50;
    /** Mockup "keypad" dim while blocked. */
    static final float BLOCKED_DIM = .4f;
    static final int MOVE_MS = 240;
    /** Mockup "pin_base", at 2 px/dp: a 132 px avatar centred on y = 206 px. */
    static final float AVATAR_DP = 66f;
    static final float AVATAR_CY_DP = 103f;
    /** Mockup "soft_shadow" under it: black 140/255, blur 20 px, 10 px down. */
    static final ConsoleCardFocus.Layer SHADOW = new ConsoleCardFocus.Layer(10f, 5f, 0f);
    static final float SHADOW_ALPHA = 140f / 255f;
    /** Mockup "progress_ring": 4 px wide, its outer edge 10 px outside the avatar. */
    static final float RING_RADIUS_DP = 37f;
    static final float RING_WIDTH_DP = 2f;
    static final long SPIN_MS = 1_200L;
    /** Mockup "corner_badge": r 18 px on a 4 px dark ring, 4 px outside the edge diagonally. */
    static final float BADGE_DP = 22f;
    static final float BADGE_OFFSET_DP = AVATAR_DP / 2f * .707f + 2f;
    /** Baselines: title 342 px, subtitle 386, one status line 538, or 534 and 570 on error. */
    static final float TITLE_BASELINE_DP = 171f;
    static final float SUBTITLE_BASELINE_DP = 193f;
    static final float STATUS_BASELINE_DP = 269f;
    static final float ERROR_BASELINE_DP = 267f;
    static final float ERROR_STATUS_BASELINE_DP = 285f;
    /** Dots centred on y = 462 px, 52 px apart, r 11 px, outline 2.5 px drawn inside. */
    static final float DOTS_CY_DP = 231f;
    static final float DOT_PITCH_DP = 26f;
    static final float DOT_RADIUS_DP = 5.5f;
    static final float DOT_STROKE_DP = 1.25f;
    static final float SHAKE_DP = 6f;
    static final int SHAKE_MS = 360;
    static final int FLASH_MS = 200;
    static final int ERROR = 0xFFFF7078;
    /** Keypad: 120 x 76 px keys, 14 px apart, 24 px corners, from y = 584 px (610 on error). */
    static final float KEYS_TOP_DP = 292f;
    static final float ERROR_DROP_DP = 13f;
    static final float KEY_WIDTH_DP = 60f;
    static final float KEY_HEIGHT_DP = 38f;
    static final float KEY_GAP_DP = 7f;
    static final float KEY_CORNER_DP = 12f;
    /** The focused key grows 4 px sideways and 3 px up and down, on a white 50/255 halo. */
    static final float FOCUS_GROW_X_DP = 2f;
    static final float FOCUS_GROW_Y_DP = 1.5f;
    static final ConsoleCardFocus.Layer HALO = new ConsoleCardFocus.Layer(9f, 0f, 0f);
    static final float HALO_ALPHA = 50f / 255f;
    /** Controller strip: 150 x 84 px cells, 16 px apart, 22 px corners, from y = 614 px. */
    static final float CELLS_TOP_DP = 307f;
    static final float CELL_WIDTH_DP = 75f;
    static final float CELL_HEIGHT_DP = 42f;
    static final float CELL_GAP_DP = 8f;
    static final float CELL_CORNER_DP = 11f;
    /** Keypad order, row by row; the last cell stays empty. */
    private static final int[] KEYPAD = {1, 2, 3, 4, 5, 6, 7, 8, 9, DELETE, 0};

    /** How the board looks for a state of the PIN flow. */
    static final class Look {
        final boolean blocked;
        final boolean countdown;
        final boolean checking;
        final boolean error;

        private Look(boolean checking, boolean countdown, boolean error) {
            this.checking = checking;
            this.countdown = countdown;
            this.blocked = checking || countdown;
            this.error = error;
        }

        /** The red error stays until a digit is typed or the next PIN is being checked. */
        static Look of(int enteredDigits, boolean submitting, long cooldownMs, boolean invalid) {
            return new Look(submitting, !submitting && cooldownMs > 0L,
                    invalid && enteredDigits == 0 && !submitting);
        }

        float keyDim() {
            return blocked ? BLOCKED_DIM : 1f;
        }
    }

    private final ConsoleEntryShell shell;
    private final ConsoleEntryShell.Page page;
    private final ConsoleProfileSelectView profiles;
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final FrameLayout avatar;
    private final Ring ring;
    private final TextView title;
    private final TextView subtitle;
    private final Dots dots;
    private final TextView error;
    private final TextView status;
    private final FrameLayout keypad;
    private final FrameLayout strip;
    private final View[] keys = new View[11];
    private final Face[] faces = new Face[21];
    private final TextView[] cellGlyphs = new TextView[10];
    private final Matrix matrix = new Matrix();
    private BitmapShader glass;
    private Listener listener;
    private boolean reducedMotion;
    private boolean playStation;
    private boolean controller;
    private boolean invalid;
    private boolean showing;
    /** The shell was hidden when the screen opened from Home, so it is hidden again on close. */
    private boolean ownsShell;
    private long cooldownTotal;
    private String message = "";
    private Runnable onGone;
    /** Set while a key from the remote switches the board back from the controller strip. */
    private boolean switchArmed;
    private int swallowedKey;
    private Look look = Look.of(0, false, 0L, false);
    private String legendKey;
    private ObjectAnimator spin;
    private ObjectAnimator shake;

    ConsolePinEntryView(Context context, ConsoleEntryShell shell,
                        ConsoleProfileSelectView profiles) {
        super(context);
        this.shell = shell;
        this.profiles = profiles;
        page = shell.addPage();
        setClipChildren(false);
        // Under the page's legend, which stays on top.
        page.addView(this, 0, match());

        int margin = dp(ConsoleCardFocus.MARGIN_DP);
        avatar = new FrameLayout(context);
        avatar.setClipChildren(false);
        avatar.setClipToPadding(false);
        avatar.setPadding(margin, margin, margin, margin);
        avatar.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        ConsoleCardFocus shadow = new ConsoleCardFocus(getResources(), margin,
                dp(AVATAR_DP) / 2f);
        shadow.setLayers(SHADOW, SHADOW, SHADOW);
        shadow.setShadowAlpha(SHADOW_ALPHA);
        avatar.setBackground(shadow);
        int avatarSize = dp(AVATAR_DP) + 2 * margin;
        LayoutParams avatarParams = new LayoutParams(avatarSize, avatarSize,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        avatarParams.topMargin = dp(AVATAR_CY_DP) - avatarSize / 2;
        addView(avatar, avatarParams);
        ring = new Ring(context);

        title = text(22, ConsoleEntryShell.TEXT, medium);
        addAt(title, TITLE_BASELINE_DP);
        subtitle = text(13, ConsoleEntryShell.TEXT_SECONDARY, Typeface.DEFAULT);
        addAt(subtitle, SUBTITLE_BASELINE_DP);

        dots = new Dots(context);
        dots.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams dotsParams = new LayoutParams(dp(4 * DOT_PITCH_DP), dp(4 * DOT_RADIUS_DP),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        dotsParams.topMargin = dp(DOTS_CY_DP - 2 * DOT_RADIUS_DP);
        addView(dots, dotsParams);

        error = text(13, ERROR, medium);
        error.setText(R.string.console_pin_invalid);
        addAt(error, ERROR_BASELINE_DP);
        status = text(12, ConsoleEntryShell.TEXT_SECONDARY, Typeface.DEFAULT);
        status.setMaxLines(2);
        addAt(status, STATUS_BASELINE_DP);

        keypad = new FrameLayout(context);
        keypad.setClipChildren(false);
        LayoutParams keypadParams = new LayoutParams(
                Math.round(3 * dp(KEY_WIDTH_DP) + 2 * dp(KEY_GAP_DP)),
                Math.round(4 * dp(KEY_HEIGHT_DP) + 3 * dp(KEY_GAP_DP)),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        keypadParams.topMargin = dp(KEYS_TOP_DP);
        addView(keypad, keypadParams);
        for (int index = 0; index < KEYPAD.length; index++) addKey(index);
        wireKeys();

        strip = new FrameLayout(context);
        strip.setClipChildren(false);
        LayoutParams stripParams = new LayoutParams(
                Math.round(5 * dp(CELL_WIDTH_DP) + 4 * dp(CELL_GAP_DP)),
                Math.round(2 * dp(CELL_HEIGHT_DP) + dp(CELL_GAP_DP)),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        stripParams.topMargin = dp(CELLS_TOP_DP);
        addView(strip, stripParams);
        for (int digit = 1; digit <= 10; digit++) addCell(digit % 10, digit - 1);
    }

    /**
     * Opens the screen titled {@code title} for {@code profile}, subtitled with its name and
     * {@code context} (the host, or what the PIN unlocks): the shell as it is now becomes the
     * blurred backdrop, and the profile's avatar, when the profile row shows it, moves up into
     * place.
     */
    void show(int title, String context, HostGatewayClient.IntegrationProfile profile,
              boolean playStationButtons, boolean controllerInput, boolean reducedMotion,
              Listener listener) {
        this.listener = listener;
        this.reducedMotion = reducedMotion;
        playStation = playStationButtons;
        controller = controllerInput;
        invalid = false;
        cooldownTotal = 0L;
        legendKey = null;
        showing = true;
        // Reopened while fading out: the caller's FLAG_SECURE must stay.
        onGone = null;
        // A key swallowed when the screen last closed must not eat this one's first press.
        switchArmed = false;
        swallowedKey = 0;
        ownsShell = shell.getVisibility() != VISIBLE;
        // The backdrop covers the shell, whose blooms need not breathe under it.
        shell.setBloomsFrozen(true);
        page.setVisibility(INVISIBLE);
        // From Home the hidden shell holds a stale page, maybe not even laid out yet: its
        // own gradient and blooms are the backdrop then, with plain veiled keys.
        if (ownsShell) {
            setBackground(null);
            glass = null;
        } else {
            snapshot();
        }
        for (Face face : faces) face.refresh();

        String name = ConsoleProfileSelectView.displayName(getContext(), profile);
        avatar.removeAllViews();
        ConsoleProfileSelectView.addAvatar(avatar, profile, AVATAR_DP);
        // Mockup order: avatar, ring, then the badge over the ring.
        avatar.addView(ring, match());
        avatar.addView(badge(), badgeParams());
        this.title.setText(title);
        subtitle.setText(context == null || context.trim().isEmpty()
                ? name : name + " · " + context.trim());
        for (int digit = 0; digit < cellGlyphs.length; digit++) {
            cellGlyphs[digit].setText(ControllerGlyphs.text(playStation, buttonForDigit(digit)));
        }
        moveAvatarFrom(profiles.avatarOf(profile.id));
        shell.show(page);
        render(0, "", false, 0L);
        keys[0].post(keys[0]::requestFocus);
    }

    /** The profile's avatar at the top, for a screen that takes it over. */
    View avatar() {
        return avatar;
    }

    /**
     * Closes the screen; the page beneath is shown by the caller, or the shell hides. Dots and
     * key focus clear at once; {@code onGone} runs once the page has faded off the screen
     * (e.g. to drop FLAG_SECURE), unless the screen reopened before that.
     */
    void hide(Runnable onGone) {
        // A page still fading out from an earlier close waits for the same end.
        this.onGone = onGone;
        if (!showing) {
            releaseIfGone();
            return;
        }
        showing = false;
        shell.setBloomsFrozen(false);
        stopSpin();
        if (shake != null) shake.cancel();
        avatar.animate().cancel();
        dots.set(0, false);
        for (Face face : faces) face.refresh();
        if (ownsShell) shell.setVisibility(GONE);
        ownsShell = false;
        releaseIfGone();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // Also runs while the constructor adds this view to its page.
        if (dots != null) releaseIfGone();
    }

    /** Once the page is off the screen: frees the backdrop copies and reports it gone. */
    private void releaseIfGone() {
        if (showing || onGone == null || isShown()) return;
        setBackground(null);
        glass = null;
        Runnable gone = onGone;
        onGone = null;
        gone.run();
    }

    void setPlayStationButtons(boolean playStationButtons) {
        if (playStation == playStationButtons) return;
        playStation = playStationButtons;
        for (int digit = 0; digit < cellGlyphs.length; digit++) {
            cellGlyphs[digit].setText(ControllerGlyphs.text(playStation, buttonForDigit(digit)));
        }
        updateLegend();
    }

    /** The last input came from a controller: its strip replaces the keypad, and back. */
    void setControllerInputMode(boolean controllerInput) {
        if (controller == controllerInput) return;
        controller = controllerInput;
        if (!showing) return;
        if (!controller) {
            // The activity calls this while dispatching the remote's key: that key only
            // reveals the keypad and must not also click or move the unseen focus.
            switchArmed = true;
            post(() -> switchArmed = false);
        }
        applyMode();
        updateStatus();
        updateLegend();
    }

    /**
     * {@code message}: the activity's status line ("Checking PIN…", a lockout's "Try again in
     * 25 s"); {@code cooldownMs}: time left of a lockout, zero or less when none.
     */
    void render(int enteredDigits, String message, boolean submitting, long cooldownMs) {
        if (!showing) return;
        look = Look.of(enteredDigits, submitting, cooldownMs, invalid);
        invalid = look.error;
        if (!look.countdown) cooldownTotal = 0L;
        else if (cooldownMs > cooldownTotal) cooldownTotal = cooldownMs;
        dots.set(enteredDigits, look.error);
        error.setVisibility(look.error ? VISIBLE : INVISIBLE);
        this.message = message == null ? "" : message;
        updateStatus();
        // Mockup 16: two lines push the keypad 26 px down.
        float drop = look.error ? dp(ERROR_DROP_DP) : 0f;
        keypad.setTranslationY(drop);
        strip.setTranslationY(drop);
        float dim = look.keyDim();
        for (Face face : faces) {
            if (face != null) face.setDim(dim);
        }
        ring.set(look.countdown ? (float) cooldownMs / cooldownTotal : 0f, look.checking);
        if (look.checking && !reducedMotion) startSpin();
        else stopSpin();
        applyMode();
        updateLegend();
    }

    /** A wrong PIN: the dots shake ±12 px three times in 360 ms, or flash red once. */
    void showInvalid() {
        if (!showing) return;
        invalid = true;
        if (reducedMotion) {
            dots.flash();
            return;
        }
        if (shake != null) shake.cancel();
        float x = dp(SHAKE_DP);
        shake = ObjectAnimator.ofFloat(dots, TRANSLATION_X, 0f, x, -x, x, -x, x, -x, 0f);
        shake.setDuration(SHAKE_MS);
        shake.setInterpolator(new LinearInterpolator());
        shake.start();
    }

    /** The activity's message, else the controller hint of mockup 15. */
    private void updateStatus() {
        status.setText(!message.isEmpty() || !controller
                ? message : getContext().getString(R.string.console_pin_gamepad_hint));
        placeBaseline(status, look.error ? ERROR_STATUS_BASELINE_DP : STATUS_BASELINE_DP);
    }

    private void applyMode() {
        // The keypad stays laid out and focused under the strip, so a remote finds it at once.
        keypad.setAlpha(controller ? 0f : 1f);
        strip.setVisibility(controller ? VISIBLE : INVISIBLE);
    }

    /** One legend entry; Back alone while a check or lockout blocks deleting. */
    private void updateLegend() {
        String label = getContext().getString(look.blocked
                ? R.string.console_profile_gate_back : R.string.console_pin_delete_or_back);
        String key = controller + ":" + playStation + ":" + label;
        if (key.equals(legendKey)) return;
        legendKey = key;
        page.clearLegend();
        if (controller) {
            page.addLegendKey(ControllerGlyphs.text(playStation, ControllerGlyphs.Button.CANCEL),
                    true, label);
        } else {
            page.addLegendIcon(R.drawable.ic_console_back, label);
        }
    }

    /** From the avatar in the profile row, at its size there, up to its place in 240 ms. */
    private void moveAvatarFrom(View origin) {
        avatar.animate().cancel();
        avatar.setTranslationX(0f);
        avatar.setTranslationY(0f);
        avatar.setScaleX(1f);
        avatar.setScaleY(1f);
        if (origin == null || reducedMotion) return;
        int[] originAt = new int[2];
        int[] shellAt = new int[2];
        origin.getLocationOnScreen(originAt);
        shell.getLocationOnScreen(shellAt);
        // The origin is scaled about its centre, which therefore stays put.
        float cx = originAt[0] - shellAt[0] + origin.getWidth() * origin.getScaleX() / 2f;
        float cy = originAt[1] - shellAt[1] + origin.getHeight() * origin.getScaleY() / 2f;
        float scale = dp(ConsoleProfileSelectView.AVATAR_DP) * origin.getScaleX()
                / (float) dp(AVATAR_DP);
        avatar.setTranslationX(cx - shell.getWidth() / 2f);
        avatar.setTranslationY(cy - dp(AVATAR_CY_DP));
        avatar.setScaleX(scale);
        avatar.setScaleY(scale);
        avatar.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(MOVE_MS).setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
    }

    /**
     * The shell drawn once at quarter size without its legend, blurred and tinted as this
     * page's background, and once more blurred and coloured as the keys' glass.
     */
    private void snapshot() {
        float density = getResources().getDisplayMetrics().density;
        float scale = 1f / SNAPSHOT_DIVISOR;
        int width = Math.max(1, shell.getWidth() / SNAPSHOT_DIVISOR);
        int height = Math.max(1, shell.getHeight() / SNAPSHOT_DIVISOR);
        Bitmap copy = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(copy);
        canvas.save();
        canvas.scale(scale, scale);
        shell.drawWithoutLegend(canvas);
        canvas.restore();
        int[] pixels = new int[width * height];
        copy.getPixels(pixels, 0, width, 0, 0, width, height);
        ConsoleMenuBackdrop.blur(pixels, width, height, ConsoleMenuBackdrop.boxRadius(
                BLUR_DP * density * scale, ConsoleMenuBackdrop.BOX_PASSES),
                ConsoleMenuBackdrop.BOX_PASSES);
        copy.setPixels(pixels, 0, width, 0, 0, width, height);
        canvas.drawColor(TINT);
        setBackground(new BitmapDrawable(getResources(), copy));

        copy.getPixels(pixels, 0, width, 0, 0, width, height);
        ConsoleMenuBackdrop.blur(pixels, width, height, ConsoleMenuBackdrop.boxRadius(
                GLASS_BLUR_DP * density * scale, ConsoleMenuBackdrop.BOX_PASSES),
                ConsoleMenuBackdrop.BOX_PASSES);
        ConsoleGlass.applyColorMatrix(pixels, GLASS);
        glass = new BitmapShader(Bitmap.createBitmap(pixels, width, height,
                Bitmap.Config.ARGB_8888), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        for (Face face : faces) {
            if (face != null) face.invalidateSelf();
        }
    }

    /** Mockup "keypad": glass keys with the digit in 36 px, "⌫" bottom left. */
    private void addKey(int index) {
        int digit = KEYPAD[index];
        int row = index / 3;
        int column = index % 3;
        View key;
        Face face = new Face(dp(KEY_CORNER_DP), true);
        if (digit == DELETE) {
            ImageView icon = new ImageView(getContext());
            icon.setImageResource(R.drawable.ic_console_backspace);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.setContentDescription(getContext().getString(R.string.console_pin_delete));
            face.ink = icon::setColorFilter;
            key = icon;
        } else {
            TextView number = text(18, ConsoleEntryShell.TEXT, Typeface.DEFAULT);
            number.setText(Integer.toString(digit));
            ConsoleText.centreOnGlyphs(number);
            number.setContentDescription(Integer.toString(digit));
            face.ink = number::setTextColor;
            key = number;
        }
        key.setId(View.generateViewId());
        key.setFocusable(true);
        key.setFocusableInTouchMode(true);
        key.setClickable(true);
        key.setSoundEffectsEnabled(false);
        face.attach(key);
        key.setOnClickListener(view -> {
            if (listener != null) listener.onDigit(digit);
        });
        key.setOnFocusChangeListener((view, focused) -> face.setFocused(focused));
        key.setOnKeyListener((view, keyCode, event) -> swallow(keyCode, event.getAction()));
        LayoutParams params = new LayoutParams(dp(KEY_WIDTH_DP), dp(KEY_HEIGHT_DP));
        params.leftMargin = Math.round(column * (dp(KEY_WIDTH_DP) + dp(KEY_GAP_DP)));
        params.topMargin = Math.round(row * (dp(KEY_HEIGHT_DP) + dp(KEY_GAP_DP)));
        keypad.addView(key, params);
        keys[index] = key;
        faces[index] = face;
    }

    /** Consumes the down and up of the key that switched the board to the keypad. */
    private boolean swallow(int keyCode, int action) {
        if (switchArmed && action == android.view.KeyEvent.ACTION_DOWN) {
            switchArmed = false;
            swallowedKey = keyCode;
            return true;
        }
        if (swallowedKey == 0 || keyCode != swallowedKey) return false;
        if (action == android.view.KeyEvent.ACTION_UP) swallowedKey = 0;
        return true;
    }

    /** D-pad focus stays on the keypad; an edge or the empty cell keeps it where it is. */
    private void wireKeys() {
        for (int index = 0; index < keys.length; index++) {
            int row = index / 3;
            int column = index % 3;
            keys[index].setNextFocusLeftId(keyId(row, column - 1, index));
            keys[index].setNextFocusRightId(keyId(row, column + 1, index));
            keys[index].setNextFocusUpId(keyId(row - 1, column, index));
            keys[index].setNextFocusDownId(keyId(row + 1, column, index));
        }
    }

    private int keyId(int row, int column, int fallback) {
        int index = row * 3 + column;
        boolean inside = row >= 0 && column >= 0 && column < 3 && index < keys.length;
        return keys[inside ? index : fallback].getId();
    }

    /**
     * Mockup "pin_gamepad_v4" cell: glass, the digit in 34 px Semibold centred 40 px in, a 1 px
     * divider at 72 px and the button glyph centred at 111 px.
     */
    private void addCell(int digit, int index) {
        FrameLayout cell = new FrameLayout(getContext());
        Face face = new Face(dp(CELL_CORNER_DP), false);
        TextView number = text(17, ConsoleEntryShell.TEXT, medium);
        number.setText(Integer.toString(digit));
        ConsoleText.centreOnGlyphs(number);
        face.ink = number::setTextColor;
        face.attach(cell);
        cell.addView(number, new LayoutParams(dp(30), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.LEFT));
        ((LayoutParams) number.getLayoutParams()).leftMargin = dp(5);
        View divider = new View(getContext());
        divider.setBackgroundColor(0x32FFFFFF);
        LayoutParams dividerParams = new LayoutParams(Math.max(1, dp(.5f)),
                dp(CELL_HEIGHT_DP - 20f), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        dividerParams.leftMargin = dp(36);
        cell.addView(divider, dividerParams);
        TextView glyph = text(24, ConsoleEntryShell.TEXT, ControllerGlyphs.typeface(getContext()));
        // Every glyph of that font shares one ink centre, so any of them stands in for the
        // buttons shown later.
        ConsoleText.centreOnInk(glyph, ControllerGlyphs.text(false, buttonForDigit(digit)));
        LayoutParams glyphParams = new LayoutParams(dp(36), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.LEFT);
        glyphParams.leftMargin = dp(37.5f);
        cell.addView(glyph, glyphParams);
        cellGlyphs[digit] = glyph;
        cell.setContentDescription(Integer.toString(digit));
        LayoutParams params = new LayoutParams(dp(CELL_WIDTH_DP), dp(CELL_HEIGHT_DP));
        params.leftMargin = Math.round((index % 5) * (dp(CELL_WIDTH_DP) + dp(CELL_GAP_DP)));
        params.topMargin = Math.round((index / 5) * (dp(CELL_HEIGHT_DP) + dp(CELL_GAP_DP)));
        strip.addView(cell, params);
        faces[11 + index] = face;
    }

    private static ControllerGlyphs.Button buttonForDigit(int digit) {
        switch (digit) {
            case 1: return ControllerGlyphs.Button.DPAD_LEFT;
            case 2: return ControllerGlyphs.Button.DPAD_UP;
            case 3: return ControllerGlyphs.Button.DPAD_RIGHT;
            case 4: return ControllerGlyphs.Button.DPAD_DOWN;
            case 5: return ControllerGlyphs.Button.RIGHT_BUMPER;
            case 6: return ControllerGlyphs.Button.RIGHT_TRIGGER;
            case 7: return ControllerGlyphs.Button.LEFT_BUMPER;
            case 8: return ControllerGlyphs.Button.LEFT_TRIGGER;
            case 9: return ControllerGlyphs.Button.NORTH;
            case 0: return ControllerGlyphs.Button.WEST;
            default: throw new IllegalArgumentException("Invalid PIN digit");
        }
    }

    /** The lock badge on the avatar's lower right edge; it scales with the avatar. */
    private View badge() {
        FrameLayout badge = new FrameLayout(getContext());
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0xF0161A24);
        background.setStroke(dp(2), 0xFF080A10);
        badge.setBackground(background);
        ImageView lock = new ImageView(getContext());
        lock.setImageResource(R.drawable.ic_console_lock);
        lock.setColorFilter(ConsoleEntryShell.TEXT);
        badge.addView(lock, new LayoutParams(dp(11), dp(11), Gravity.CENTER));
        return badge;
    }

    private LayoutParams badgeParams() {
        LayoutParams params = new LayoutParams(dp(BADGE_DP), dp(BADGE_DP),
                Gravity.TOP | Gravity.LEFT);
        // Relative to the avatar's padding box: its centre plus the diagonal offset.
        params.leftMargin = Math.round(dp(AVATAR_DP) / 2f + BADGE_OFFSET_DP
                * getResources().getDisplayMetrics().density - dp(BADGE_DP) / 2f);
        params.topMargin = params.leftMargin;
        return params;
    }

    private void startSpin() {
        if (spin != null) return;
        spin = ObjectAnimator.ofFloat(ring, ROTATION, 0f, 360f);
        spin.setDuration(SPIN_MS);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.start();
    }

    private void stopSpin() {
        if (spin != null) spin.cancel();
        spin = null;
        ring.setRotation(0f);
    }

    private void addAt(TextView view, float baselineDp) {
        LayoutParams params = new LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        addView(view, params);
        placeBaseline(view, baselineDp);
    }

    /** Puts the first baseline on {@code baselineDp}; the views have no font padding. */
    private void placeBaseline(TextView view, float baselineDp) {
        LayoutParams params = (LayoutParams) view.getLayoutParams();
        int top = Math.round(baselineDp * getResources().getDisplayMetrics().density
                + view.getPaint().getFontMetrics().ascent);
        if (params.topMargin == top) return;
        params.topMargin = top;
        view.requestLayout();
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

    private static LayoutParams match() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * A key's or cell's surface: the glass under it with a white veil and a hairline lit from
     * above, both weaker while blocked; a focused key instead grows into a near-white fill on a
     * white halo with dark ink.
     */
    private final class Face extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF rect = new RectF();
        private final float corner;
        private final boolean focusable;
        private final ConsoleHostSelectView.GlassEdge edge;
        private final ConsoleCardFocus halo;
        private View view;
        private boolean focused;
        private float dim = 1f;
        Ink ink;

        Face(float corner, boolean focusable) {
            this.corner = corner;
            this.focusable = focusable;
            edge = new ConsoleHostSelectView.GlassEdge(corner,
                    getResources().getDisplayMetrics().density, 0);
            edge.setEdgeAlpha(EDGE_ALPHA);
            halo = new ConsoleCardFocus(getResources(), 0f, corner);
            halo.setLayers(HALO, HALO, HALO);
            halo.setShadowColor(0xFFFFFFFF);
            halo.setShadowAlpha(HALO_ALPHA);
        }

        void attach(View view) {
            this.view = view;
            view.setBackground(this);
            applyInk();
        }

        void setFocused(boolean focused) {
            if (this.focused == focused) return;
            this.focused = focused;
            applyInk();
            invalidateSelf();
        }

        void setDim(float dim) {
            if (this.dim == dim) return;
            this.dim = dim;
            edge.setEdgeAlpha((int) (EDGE_ALPHA * dim));
            applyInk();
            invalidateSelf();
        }

        /**
         * No focus is shown on a dimmed key (mockup: focus only at full strength), nor once
         * the screen closes, so a fading page does not show the last key pressed.
         */
        private boolean lit() {
            return focusable && focused && dim >= 1f && showing;
        }

        void refresh() {
            applyInk();
            invalidateSelf();
        }

        /** Mockup ink: dark on focus, else white mixed to 12 grey by the dim. */
        private void applyInk() {
            if (ink == null) return;
            int grey = Math.round(12 * (1f - dim));
            int text = ConsoleEntryShell.TEXT;
            ink.apply(lit() ? ConsolePanel.INK : 0xFF000000
                    | (int) (((text >> 16) & 0xFF) * dim + grey) << 16
                    | (int) (((text >> 8) & 0xFF) * dim + grey) << 8
                    | (int) ((text & 0xFF) * dim + grey));
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            edge.setBounds(bounds);
            halo.setBounds(bounds.left - dp(FOCUS_GROW_X_DP), bounds.top - dp(FOCUS_GROW_Y_DP),
                    bounds.right + dp(FOCUS_GROW_X_DP), bounds.bottom + dp(FOCUS_GROW_Y_DP));
        }

        @Override
        public void draw(Canvas canvas) {
            if (lit()) {
                halo.draw(canvas);
                rect.set(halo.getBounds());
                paint.setShader(null);
                paint.setColor(ConsolePanel.FOCUS_FILL);
                canvas.drawRoundRect(rect, corner, corner, paint);
                return;
            }
            rect.set(getBounds());
            if (glass != null) {
                // The glass is a quarter-size copy of this whole page.
                float x = 0f;
                float y = 0f;
                for (View at = view; at != ConsolePinEntryView.this && at != null;
                     at = (View) at.getParent()) {
                    x += at.getLeft();
                    y += at.getTop();
                }
                matrix.setScale(SNAPSHOT_DIVISOR, SNAPSHOT_DIVISOR);
                matrix.postTranslate(-x, -y);
                glass.setLocalMatrix(matrix);
                paint.setShader(glass);
                paint.setColor(0xFF000000);
                canvas.drawRoundRect(rect, corner, corner, paint);
            }
            paint.setShader(null);
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha((int) (VEIL_ALPHA * dim));
            canvas.drawRoundRect(rect, corner, corner, paint);
            edge.draw(canvas);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter colorFilter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private interface Ink {
        void apply(int color);
    }

    /**
     * Mockup "pin_dots": filled for each digit entered, the next outlined bright, the rest
     * faint; all red outlines after a wrong PIN, or filled red for a moment as its flash.
     */
    private final class Dots extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Runnable endFlash = () -> {
            flashing = false;
            invalidate();
        };
        private int entered;
        private boolean error;
        private boolean flashing;

        Dots(Context context) {
            super(context);
            fill.setColor(ConsoleEntryShell.TEXT);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(DOT_STROKE_DP * getResources().getDisplayMetrics().density);
        }

        void set(int entered, boolean error) {
            if (this.entered == entered && this.error == error) return;
            this.entered = entered;
            this.error = error;
            invalidate();
        }

        void flash() {
            flashing = true;
            removeCallbacks(endFlash);
            postDelayed(endFlash, FLASH_MS);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float radius = DOT_RADIUS_DP * density;
            float cy = getHeight() / 2f;
            for (int index = 0; index < 4; index++) {
                float cx = getWidth() / 2f + (index - 1.5f) * DOT_PITCH_DP * density;
                if (flashing || index < entered) {
                    fill.setColor(flashing ? ERROR : ConsoleEntryShell.TEXT);
                    canvas.drawCircle(cx, cy, radius, fill);
                    continue;
                }
                stroke.setColor(error ? ERROR
                        : index == entered ? 0xEBFFFFFF : 0x6EFFFFFF);
                canvas.drawCircle(cx, cy, radius - stroke.getStrokeWidth() / 2f, stroke);
            }
        }
    }

    /**
     * Mockup "progress_ring" around the avatar, clockwise from 12 o'clock: a faint track with
     * the part of a lockout still left in amber, or an amber quarter turned by the view's
     * rotation while checking (still and brighter with reduced motion). Drawn on change only.
     */
    private final class Ring extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private float left;
        private boolean checking;

        Ring(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(RING_WIDTH_DP * getResources().getDisplayMetrics().density);
        }

        void set(float left, boolean checking) {
            if (this.left == left && this.checking == checking) return;
            this.left = left;
            this.checking = checking;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (left <= 0f && !checking) return;
            float radius = RING_RADIUS_DP * getResources().getDisplayMetrics().density;
            oval.set(getWidth() / 2f - radius, getHeight() / 2f - radius,
                    getWidth() / 2f + radius, getHeight() / 2f + radius);
            boolean still = checking && reducedMotion;
            paint.setColor(still ? ConsoleHostTileLook.AMBER & 0xB0FFFFFF : 0x28FFFFFF);
            canvas.drawOval(oval, paint);
            if (still) return;
            paint.setColor(ConsoleHostTileLook.AMBER);
            canvas.drawArc(oval, -90f, checking ? 90f : 360f * left, false, paint);
        }
    }
}
