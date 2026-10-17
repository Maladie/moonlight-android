package com.limelight.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LineHeightSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.console.transition.LaunchTransitionController;
import com.limelight.console.transition.LaunchTransitionSnapshot;
import com.limelight.console.transition.PresentationBarrier;
import com.limelight.diagnostics.MoonWakerDiagnostics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A fully opaque, modal privacy surface rendered in the Activity window above video: the game's
 * full background artwork (or the app's standard background), a light vignette and a scrim from
 * the left and the bottom, the console clock top-right and one information card bottom-left
 * ({@link LoadingCoverModel}). The card never moves or rescales between states; only its
 * content changes.
 */
public final class ConsoleStreamLoadingView extends FrameLayout {
    /** Sizes follow the approved mockup on a 960 dp wide TV: 1% of its width is 9.6 dp. */
    private static final int EDGE_DP = 40;
    /** Wide enough for the 10-foot message line (TV 2026-10-16: 442 dp clipped it). */
    private static final int CARD_WIDTH_DP = 520;
    /** Kicker and the game a switch leaves: one group, one font. */
    private static final float KICKER_SP = 12f;
    private static final float STAGE_SP = 15f;
    private static final float LINE_SP = 14f;
    /** Longest notice (host_session_locked) is ~190 chars at ~72 chars per line (507dp / 7dp): 3 lines. */
    private static final int LINE_COUNT = 3;
    private static final float ACTION_SP = 12.5f;
    private static final int ACTION_HEIGHT_DP = 36;
    private static final float TITLE_SP = 35f;
    private static final float CLOSING_TITLE_SP = 27f;
    private static final long MESSAGE_INTERVAL_MS = 3000L;
    private static final long MESSAGE_FADE_MS = 400L;
    private static final long STAGE_FADE_MS = 200L;
    private static final long SEGMENT_FILL_MS = 280L;
    private static final int INK = 0xFFF3F6FB;
    private static final int INK_SOFT = 0xB8F3F6FB;
    private static final int INK_FAINT = 0x75F3F6FB;
    private static final int LINE = 0x2EF3F6FB;
    private static final int ON_INK = 0xFF0B0F17;
    private static final int WARN = 0xFFFFB547;
    // ponytail: the last decoded splash stays for the window that takes the cover over (a few
    // MB until another game's artwork replaces it); key it by file version if artwork can
    // change under one path.
    private static final FencedArtwork.Cache<Bitmap> SPLASH_CACHE = new FencedArtwork.Cache<>();
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "MoonWaker-CoverArtwork");
        thread.setDaemon(true);
        return thread;
    });

    public ConsoleStreamLoadingView(Context context) {
        this(context, null, null, false, SystemClock.elapsedRealtime());
    }

    public ConsoleStreamLoadingView(Context context, AttributeSet attrs) {
        this(context, null, null, false, SystemClock.elapsedRealtime());
    }

    public interface Actions {
        void onCancel();
        void onRetry();
        void onShowStreamAnyway();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final String[] messages;
    private final LoadingCoverModel model;
    private final FencedArtwork<Bitmap> artwork;
    private final Backdrop defaultBackdrop;
    private final ImageView artworkView;
    private final Scrim scrim;
    private final TextView clock;
    private final LinearLayout card;
    private final TextView kickerView;
    private final TextView fromView;
    private final TextView titleView;
    private final Segments segments;
    private final TextView stageView;
    private final TextView elapsedView;
    /** The area under the stage: the notice and, while loading, the rotating message. */
    private final TextView lineView;
    private final LinearLayout actionsRow;
    private final TextView retryView;
    private final TextView showAnywayView;
    /** The Back-key slot: "Cancel" or "Back", with its key hint. */
    private final LinearLayout cancelView;
    private final TextView cancelLabel;
    private final boolean reducedMotion;
    /**
     * This cover continues one another window shows now: until it is attached it takes that
     * one's message and shows its artwork at once, so its first frame is the frame on screen.
     */
    private boolean handedOver;
    private Bitmap shownArtwork;
    /** A hidden warm-up's see-through cover: no background and no information layer. */
    private boolean neutral;
    private boolean stopped;
    private boolean revealRequested;
    private int revealGeneration;
    private int activeMotionDirection = KeyEvent.KEYCODE_UNKNOWN;
    private int lastMessageIndex = -1;
    /** The rotating message the line shows whenever there is no notice; a handoff continues it. */
    private String currentMessage;
    private boolean rotating;
    private CharSequence lineNotice = "";
    private String lineMessage = "";
    /** The stage text the label shows or fades to. */
    private String stageTarget = "";
    /** This cover's frames: effects ordered after it wait here, each settles exactly once. */
    private final ViewFrameSource frames = new ViewFrameSource(this);
    private Actions actions;

    private final Runnable rotateMessage = new Runnable() {
        @Override public void run() {
            if (stopped || !model.messageShown()
                    || getVisibility() != VISIBLE || messages.length == 0) {
                return;
            }
            currentMessage = messages[nextMessageIndex()];
            showLine(lineNotice, currentMessage);
            handler.postDelayed(this, MESSAGE_INTERVAL_MS);
        }
    };

    private final Runnable tickElapsed = new Runnable() {
        @Override public void run() {
            if (stopped) return;
            elapsedView.setText(model.elapsed(SystemClock.elapsedRealtime()));
            handler.postDelayed(this, 1000L);
        }
    };

    /**
     * {@code epochMillis}: the transition's start on {@link SystemClock#elapsedRealtime}, the
     * one cover time base (it keeps counting through deep sleep).
     */
    public ConsoleStreamLoadingView(Context context, String title,
                                    String initialMessage, boolean reducedMotion,
                                    long epochMillis) {
        super(context);
        this.reducedMotion = reducedMotion;
        handedOver = initialMessage != null && !initialMessage.trim().isEmpty();
        messages = getResources().getStringArray(R.array.console_loading_messages);
        model = new LoadingCoverModel(title == null || title.trim().isEmpty()
                ? context.getString(R.string.app_label) : title, epochMillis);
        artwork = new FencedArtwork<>(SPLASH_CACHE, ConsoleStreamLoadingView::decodeSplash,
                DECODER, handler::post, this::showArtwork);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setBackgroundColor(Color.BLACK);
        setElevation(dp(64));
        setLayerType(View.LAYER_TYPE_HARDWARE, null);

        defaultBackdrop = new Backdrop(context);
        addView(defaultBackdrop, match());
        artworkView = new ImageView(context);
        artworkView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artworkView.setVisibility(GONE);
        // Full-bleed: the game's backdrop is the whole background, as on the Home.
        addView(artworkView, match());
        scrim = new Scrim(context);
        addView(scrim, match());

        clock = ConsoleClock.create(context);
        addView(clock, ConsoleClock.topEnd(context));

        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        LayoutParams cardParams = new LayoutParams(dp(CARD_WIDTH_DP),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        cardParams.leftMargin = dp(EDGE_DP);
        cardParams.bottomMargin = dp(EDGE_DP);
        addView(card, cardParams);

        kickerView = text("", KICKER_SP, INK_SOFT);
        kickerView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        kickerView.setLetterSpacing(.14f);
        kickerView.setAllCaps(true);
        card.addView(kickerView, row(0));
        // Tight under the kicker, apart from the new title: "Switching / from game A".
        fromView = text("", KICKER_SP, INK_SOFT);
        fromView.setTypeface(kickerView.getTypeface());
        fromView.setSingleLine(true);
        fromView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams fromParams = row(4);
        fromParams.bottomMargin = dp(10);
        card.addView(fromView, fromParams);
        titleView = text("", TITLE_SP, INK);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleView.setLetterSpacing(-.01f);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
        card.addView(titleView, row(4));

        segments = new Segments(context);
        LinearLayout.LayoutParams segmentParams = row(11);
        segmentParams.height = dp(3);
        card.addView(segments, segmentParams);

        LinearLayout stageRow = new LinearLayout(context);
        stageRow.setOrientation(LinearLayout.HORIZONTAL);
        stageRow.setGravity(Gravity.BOTTOM);
        card.addView(stageRow, row(8));
        stageView = text("", STAGE_SP, INK);
        stageView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        stageView.setSingleLine(true);
        stageView.setEllipsize(TextUtils.TruncateAt.END);
        stageView.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        stageRow.addView(stageView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // A fixed slot at the card's right edge, whatever the stage says (TV 2026-10-16: glued
        // to the stage it moved with every change). Tabular digits keep it steady; a fixed
        // width lets the per-second tick redraw without a relayout of the card.
        elapsedView = text("", STAGE_SP, INK_SOFT);
        elapsedView.setFontFeatureSettings("tnum");
        elapsedView.setGravity(Gravity.END);
        stageRow.addView(elapsedView, new LinearLayout.LayoutParams(dp(12)
                + (int) Math.ceil(elapsedView.getPaint().measureText("0:00:00")),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        currentMessage = handedOver ? initialMessage
                : messages.length == 0 ? "" : messages[nextMessageIndex()];
        lineView = text("", LINE_SP, INK_SOFT);
        // A fixed area of LINE_COUNT lines for message and notice alike: a notice wraps inside
        // it, a change never moves the card or the buttons; beyond it, it ellipsizes.
        lineView.setLines(LINE_COUNT);
        lineView.setEllipsize(TextUtils.TruncateAt.END);
        lineView.setGravity(Gravity.TOP);
        card.addView(lineView, row(11));

        actionsRow = new LinearLayout(context);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        // A reserved row has no visible pill to measure: without this it collapses to 0 and the
        // bottom-anchored card jumps whenever actions come or go.
        actionsRow.setMinimumHeight(dp(ACTION_HEIGHT_DP));
        card.addView(actionsRow, row(14));
        retryView = pillText(context.getString(R.string.transition_retry));
        retryView.setOnClickListener(view -> requestRetryOnce());
        actionsRow.addView(retryView, actionParams());
        showAnywayView = pillText(context.getString(R.string.transition_reveal_now));
        showAnywayView.setOnClickListener(view -> {
            if (actions != null) actions.onShowStreamAnyway();
        });
        actionsRow.addView(showAnywayView, actionParams());
        cancelView = new LinearLayout(context);
        cancelView.setOrientation(LinearLayout.HORIZONTAL);
        cancelView.setGravity(Gravity.CENTER_VERTICAL);
        pill(cancelView);
        cancelLabel = text(context.getString(R.string.transition_cancel), ACTION_SP, INK);
        cancelLabel.setTextColor(pillInk());
        cancelLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cancelLabel.setDuplicateParentStateEnabled(true);
        cancelView.addView(cancelLabel, wrap());
        TextView keyHint = text(context.getString(R.string.transition_back_key_hint), 9.5f, INK);
        keyHint.setTextColor(pillInk());
        keyHint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        keyHint.setAlpha(.7f);
        keyHint.setPadding(dp(5), dp(1), dp(5), dp(1));
        GradientDrawable hintBorder = new GradientDrawable();
        hintBorder.setCornerRadius(dp(4));
        hintBorder.setStroke(Math.max(1, dp(.75f)), pillInk());
        keyHint.setBackground(hintBorder);
        keyHint.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams hintParams = wrap();
        hintParams.leftMargin = dp(6);
        cancelView.addView(keyHint, hintParams);
        cancelView.setOnClickListener(view -> { if (actions != null) actions.onCancel(); });
        actionsRow.addView(cancelView, actionParams());

        if (handedOver) lastMessageIndex = Arrays.asList(messages).indexOf(initialMessage);
        model.step(1, context.getString(R.string.transition_preparing_session), "");
        render();
        applyBackground();
        if (handedOver) requestDefaultActionFocus();
        tickElapsed.run();
        post(this::requestDefaultActionFocus);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // On screen from here on: artwork fades in and messages change as on any cover.
        handedOver = false;
    }

    /** See-through, the cover draws nothing of its own: what is under its window shows. */
    public void setSeeThrough(boolean seeThrough) {
        setBackgroundColor(seeThrough ? Color.TRANSPARENT : Color.BLACK);
    }

    public void setActions(Actions actions) {
        this.actions = actions;
    }

    public void setRetryLabel(int labelResId) {
        retryView.setText(labelResId);
    }

    private void requestRetryOnce() {
        if (actions == null || !model.takeRetry()) return;
        render();
        actions.onRetry();
    }

    public void showNeutralWarmUpAppearance() {
        if (stopped) return;
        neutral = true;
        applyBackground();
    }

    public void showFullTransitionAppearance() {
        if (stopped) return;
        neutral = false;
        model.leaveClosing();
        render();
        applyBackground();
    }

    /** UI thread: the layer follows the controller's snapshot ({@link LoadingCoverModel#follow}). */
    public void followTransition(LaunchTransitionSnapshot snapshot) {
        if (stopped || !model.follow(snapshot)) return;
        render();
    }

    /**
     * The cached artwork shows at once; any other decodes off the UI thread and shows only
     * while its path is still this cover's latest request. Empty clears it (the standard
     * background shows).
     */
    public void setSplashArtwork(String artworkPath) {
        artwork.request(artworkPath);
    }

    /** The artwork is still being decided: the plain dark ground until {@link #setSplashArtwork}. */
    public void awaitSplashArtwork() {
        artwork.hold();
    }

    /** The artwork this cover decided on (empty for the standard background): its stream keeps it. */
    public String splashArtworkPath() {
        return artwork.requested();
    }

    public void setTitle(String title) {
        if (stopped || !model.setTitle(title)) return;
        render();
    }

    /** The game a switch leaves ("from game A" above the title); blank clears it. */
    public void setFrom(String previousTitle) {
        if (stopped || !model.setFrom(previousTitle)) return;
        render();
    }

    private static Bitmap decodeSplash(String path) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        Bitmap decoded = null;
        if (LoadingArtworkPolicy.canUseAsSplash(bounds.outWidth, bounds.outHeight)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = LoadingArtworkPolicy.sampleSize(
                    bounds.outWidth, bounds.outHeight, 1920);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            decoded = BitmapFactory.decodeFile(path, options);
        }
        if (decoded == null) {
            MoonWakerDiagnostics.record("WARN", "android.cover", "cover.artwork",
                    "outcome", "decode_failed", "width", bounds.outWidth,
                    "height", bounds.outHeight);
        }
        return decoded;
    }

    public ViewFrameSource frames() {
        return frames;
    }

    public void setStage(String stage) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> setStage(stage));
            return;
        }
        if (stopped || !model.connectionStage(friendlyStage(stage), progressForStage(stage))) {
            return;
        }
        render();
    }

    public void setStep(int step, String status) {
        setStep(step, status, "");
    }

    /** {@code notice}: one attention line under the stage (e.g. "taking longer"), or empty. */
    public void setStep(int step, String status, String notice) {
        setStep(step, status, notice, -1);
    }

    /** {@code percent} 0-100 fills the current step's segment in proportion; below 0 it sweeps. */
    public void setStep(int step, String status, String notice, int percent) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> setStep(step, status, notice, percent));
            return;
        }
        if (stopped) return;
        if (!model.step(step, status == null || status.trim().isEmpty()
                ? getContext().getString(R.string.transition_preparing_session) : status,
                notice, percent)) {
            return;
        }
        render();
    }

    /** The decorative message a handoff continues; never the stage. */
    public String getCurrentMessage() {
        return currentMessage;
    }

    public void showError(String title, String details) {
        showError(title, details, false);
    }

    public void showError(String title, String details, boolean allowShowAnyway) {
        showError(title, details, allowShowAnyway, false, true);
    }

    /**
     * {@code preferReveal}: a launcher prompt or off-screen window is the likely cause, so an
     * available "Reveal now" takes the focus; otherwise the focus stays put or lands on Retry.
     * {@code allowRetry} false leaves only Cancel and the optional "Reveal now".
     */
    public void showError(String title, String details, boolean allowShowAnyway,
                          boolean preferReveal, boolean allowRetry) {
        showFailure(title, details, "", allowShowAnyway, preferReveal, allowRetry);
    }

    /** Error whose details end with a small, dim reason code for failure reports. */
    public void showError(String title, String details, String reasonCode) {
        showFailure(title, details, reasonCode, false, false, true);
    }

    private void showFailure(String title, String details, String reasonCode,
                             boolean allowShowAnyway, boolean preferReveal, boolean allowRetry) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> showFailure(title, details, reasonCode, allowShowAnyway,
                    preferReveal, allowRetry));
            return;
        }
        if (stopped) return;
        boolean revealNewlyShown = allowShowAnyway && !model.actions()
                .contains(LoadingCoverModel.Action.REVEAL);
        if (!model.error(title, details, reasonCode, allowShowAnyway, allowRetry)) return;
        render();
        if (preferReveal && revealNewlyShown) showAnywayView.requestFocus();
        else if (!isVisibleAction(findFocus())) requestDefaultActionFocus();
        sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT);
    }

    public void showLauncherInteraction(String title, String details, boolean allowReveal) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> showLauncherInteraction(title, details, allowReveal));
            return;
        }
        if (stopped) return;
        boolean revealNewlyShown = allowReveal && !model.actions()
                .contains(LoadingCoverModel.Action.REVEAL);
        if (!model.launcher(title, details, allowReveal)) return;
        render();
        if (revealNewlyShown) showAnywayView.requestFocus();
        else requestDefaultActionFocus();
        sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT);
    }

    public void showWarning(String title, String details, boolean allowReveal) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> showWarning(title, details, allowReveal));
            return;
        }
        if (stopped || !model.warning(title, details, allowReveal)) return;
        render();
        if (!isVisibleAction(findFocus())) cancelView.requestFocus();
        sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT);
    }

    public void showCancelling() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::showCancelling);
            return;
        }
        if (stopped) return;
        model.cancelling(getContext().getString(R.string.transition_cancelling));
        render();
        sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT);
    }

    /** The quiet closing card of an ended game: the same card, without steps or actions. */
    public void showClosing(String status) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> showClosing(status));
            return;
        }
        if (stopped) return;
        if (!model.closing()) {
            // Once per closing card: whether it keeps the ended game's artwork.
            MoonWakerDiagnostics.record("INFO", "android.cover", "cover.artwork",
                    "outcome", shownArtwork != null ? "shown" : "none", "kind", "closing");
        }
        model.closing(status);
        neutral = false;
        if (artworkView.getVisibility() == VISIBLE) {
            artworkView.animate().cancel();
            artworkView.setAlpha(1f);
        }
        render();
        applyBackground();
        sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT);
    }

    /**
     * Exposes a deliberate, DPAD-focusable opt-in after a real stream frame is
     * available, while game/Playnite readiness is still being verified.
     */
    public void setManualRevealAvailable(boolean available) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> setManualRevealAvailable(available));
            return;
        }
        if (stopped || revealRequested) return;
        boolean focused = findFocus() == showAnywayView;
        if (!model.manualReveal(available)) return;
        render();
        if (available) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT);
        } else if (focused) {
            cancelView.requestFocus();
        }
    }

    public boolean handleControllerKey(KeyEvent event) {
        if (event == null) return false;
        int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP) {
            return isLoadingActionKey(keyCode);
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
            return false;
        }
        if (actionsRow.getVisibility() != VISIBLE) return isLoadingActionKey(keyCode);
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            return cancelView.performClick();
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            return moveActionFocus(-1);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return moveActionFocus(1);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            requestDefaultActionFocus();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            View focused = findFocus();
            if (isVisibleAction(focused)) return focused.performClick();
            requestDefaultActionFocus();
            return true;
        }
        return false;
    }

    public boolean handleControllerMotion(MotionEvent event) {
        if (event == null || event.getAction() != MotionEvent.ACTION_MOVE) return false;
        float horizontal = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float vertical = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        int direction = KeyEvent.KEYCODE_UNKNOWN;
        if (Math.max(Math.abs(horizontal), Math.abs(vertical)) >= .45f) {
            direction = Math.abs(horizontal) > Math.abs(vertical)
                    ? (horizontal < 0f ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT)
                    : (vertical < 0f ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN);
        }
        if (direction == KeyEvent.KEYCODE_UNKNOWN) {
            boolean consumed = activeMotionDirection != KeyEvent.KEYCODE_UNKNOWN;
            activeMotionDirection = KeyEvent.KEYCODE_UNKNOWN;
            return consumed;
        }
        if (direction == activeMotionDirection) return true;
        activeMotionDirection = direction;
        return handleControllerKey(new KeyEvent(KeyEvent.ACTION_DOWN, direction));
    }

    public void waitingForVideo() {
        setStep(4, getContext().getString(R.string.console_stream_waiting_video));
    }

    /**
     * Fades the cover out after its "ready" frame, or at the barrier deadline when no frame
     * comes (UNAVAILABLE): {@code afterReveal} always follows unless the reveal was superseded
     * (cover shown again, view stopped) or {@code fence} cancelled.
     */
    public void revealStream(String fence, Runnable afterReveal) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> revealStream(fence, afterReveal));
            return;
        }
        // The controller decided this reveal: no phase of this layer refuses it.
        if (stopped || revealRequested) return;
        model.leaveClosing();
        revealRequested = true;
        int generation = ++revealGeneration;
        setStep(LoadingCoverModel.STEPS, getContext().getString(R.string.transition_ready));
        frames.await(fence, PresentationBarrier.COVER_DEADLINE_MS, outcome -> {
            if (stopped || generation != revealGeneration || !revealRequested) return;
            if (!LaunchTransitionController.coverAllowsEffect(outcome)) {
                // A cancelled reveal leaves the cover up and a later one (Reveal now) possible.
                revealRequested = false;
                return;
            }
            handler.removeCallbacks(rotateMessage);
            if (reducedMotion) {
                setVisibility(GONE);
                if (afterReveal != null) afterReveal.run();
            } else {
                animate().alpha(0f).setDuration(300L).withEndAction(() -> {
                    if (stopped || generation != revealGeneration || !revealRequested) return;
                    setVisibility(GONE);
                    if (afterReveal != null) afterReveal.run();
                }).start();
            }
        });
    }

    public void showOpaque() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::showOpaque);
            return;
        }
        if (stopped) return;
        if (getVisibility() == VISIBLE && !revealRequested && getAlpha() == 1f) {
            bringToFront();
            return;
        }
        // A new presentation: its time counts from here.
        model.newPresentation(SystemClock.elapsedRealtime());
        elapsedView.setText(model.elapsed(SystemClock.elapsedRealtime()));
        revealGeneration++;
        frames.newPresentation();
        animate().cancel();
        revealRequested = false;
        setAlpha(1f);
        setVisibility(VISIBLE);
        bringToFront();
        if (!model.actions().isEmpty()) requestDefaultActionFocus();
        scheduleLoadingMessage();
    }

    /** UI thread: the shown transition began at the user's action, maybe in another window. */
    public void setStartedAt(long startedAtElapsedMillis) {
        if (stopped) return;
        model.startedAt(startedAtElapsedMillis);
        elapsedView.setText(model.elapsed(SystemClock.elapsedRealtime()));
    }

    public void stopAndHide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::stopAndHide);
            return;
        }
        stopAnimations();
        setVisibility(GONE);
    }

    public void stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::stop);
            return;
        }
        stopAnimations();
        animate().cancel();
    }

    /** Draws the card from the model; the same in every background, only content changes. */
    private void render() {
        kickerView.setText(kickerLabel(model.kicker()));
        String from = model.from();
        fromView.setText(from.isEmpty() ? "" : getContext().getString(
                R.string.transition_switch_from, from));
        fromView.setVisibility(from.isEmpty() || model.closing() ? GONE : VISIBLE);
        titleView.setText(model.title());
        titleView.setTextSize(model.closing() ? CLOSING_TITLE_SP : TITLE_SP);
        showStage(model.stage().isEmpty()
                ? getContext().getString(R.string.transition_preparing_session) : model.stage());
        CharSequence notice = model.notice();
        if (!model.reasonCode().isEmpty()) {
            SpannableStringBuilder text = new SpannableStringBuilder(notice).append("  ");
            int start = text.length();
            text.append(model.reasonCode());
            text.setSpan(new RelativeSizeSpan(0.8f), start, text.length(), 0);
            text.setSpan(new ForegroundColorSpan(INK_FAINT), start, text.length(), 0);
            notice = text;
        }
        LoadingCoverModel.Line line = model.line();
        boolean message = model.messageShown();
        lineView.setVisibility(line == LoadingCoverModel.Line.NONE ? GONE : VISIBLE);
        if (line != LoadingCoverModel.Line.NONE) {
            showLine(line == LoadingCoverModel.Line.MESSAGE ? "" : notice,
                    message ? currentMessage : "");
        }
        if (message != rotating) {
            rotating = message;
            if (message) scheduleLoadingMessage();
            else handler.removeCallbacks(rotateMessage);
        }
        segments.show(model);
        segments.setVisibility(model.stepsVisible() ? VISIBLE : GONE);
        List<LoadingCoverModel.Action> offered = model.actions();
        boolean back = offered.contains(LoadingCoverModel.Action.BACK);
        cancelLabel.setText(back ? R.string.transition_back : R.string.transition_cancel);
        cancelView.setVisibility(back || offered.contains(LoadingCoverModel.Action.CANCEL)
                ? VISIBLE : GONE);
        retryView.setVisibility(offered.contains(LoadingCoverModel.Action.RETRY)
                ? VISIBLE : GONE);
        showAnywayView.setVisibility(offered.contains(LoadingCoverModel.Action.REVEAL)
                ? VISIBLE : GONE);
        actionsRow.setVisibility(visibility(model.actionsSlot()));
    }

    /** A reserved slot keeps its place on the card: invisible, never gone. */
    private static int visibility(LoadingCoverModel.Slot slot) {
        return slot == LoadingCoverModel.Slot.SHOWN ? VISIBLE
                : slot == LoadingCoverModel.Slot.RESERVED ? INVISIBLE : GONE;
    }

    /** Whether a change animates: on screen, motion allowed (also by the animator scale). */
    private boolean animatesChanges() {
        return !reducedMotion && !handedOver && !stopped && isAttachedToWindow()
                && getVisibility() == VISIBLE && ValueAnimator.areAnimatorsEnabled();
    }

    /**
     * A changed area cross-fades like the stage; a message rotating under an unchanged notice
     * swaps in place, so the notice never blinks.
     */
    private void showLine(CharSequence notice, String message) {
        boolean noticeChanged = !notice.toString().contentEquals(lineNotice);
        if (!noticeChanged && message.equals(lineMessage) && lineView.getText().length() > 0) {
            return;
        }
        lineNotice = notice;
        lineMessage = message;
        lineView.animate().cancel();
        if (!animatesChanges() || lineView.getText().length() == 0
                || !noticeChanged && notice.length() > 0) {
            applyLine();
            lineView.setAlpha(1f);
            return;
        }
        lineView.animate().alpha(0f).setDuration(MESSAGE_FADE_MS).withEndAction(() -> {
            applyLine();
            lineView.animate().alpha(1f).setDuration(MESSAGE_FADE_MS).start();
        }).start();
    }

    private void applyLine() {
        // The notice's dot is a bullet span: centred on the first line, wrapped lines hang
        // after it, and the area keeps its LINE_COUNT height (a tall compound drawable grew it).
        // The message is its own paragraph below; a long notice ellipsizes it away at the end.
        SpannableStringBuilder text = new SpannableStringBuilder(lineNotice);
        if (text.length() > 0) {
            text.setSpan(new BulletSpan(dp(7), WARN, dp(3)), 0, text.length(),
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        }
        if (!lineMessage.isEmpty()) {
            int noticeEnd = text.length();
            if (noticeEnd > 0) {
                // Only while loading, so no reason code's own colour sits under this one.
                text.setSpan(new ForegroundColorSpan(INK), 0, noticeEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.append("\n");
            }
            int start = text.length();
            text.append(lineMessage);
            text.setSpan(new StyleSpan(Typeface.ITALIC), start, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        // Every laid-out line as tall as the padding lines setLines adds below short text, so
        // one, two or three lines (italic, fallback glyphs) keep the area's height to the pixel.
        text.setSpan(new LineHeightSpan.Standard(lineView.getLineHeight()), 0, text.length(),
                Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        lineView.setText(text);
        // Both read from the couch: the notice in full ink, the message a step softer.
        lineView.setTextColor(lineMessage.isEmpty() ? INK : INK_SOFT);
        lineView.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
    }

    /** A changed stage cross-fades (out, then in) instead of swapping. */
    private void showStage(String stage) {
        if (stage.equals(stageTarget)) return;
        stageTarget = stage;
        stageView.animate().cancel();
        if (!animatesChanges()) {
            stageView.setText(stage);
            stageView.setAlpha(1f);
            return;
        }
        stageView.animate().alpha(0f).setDuration(STAGE_FADE_MS / 2).withEndAction(() -> {
            stageView.setText(stageTarget);
            stageView.animate().alpha(1f).setDuration(STAGE_FADE_MS / 2).start();
        }).start();
    }

    private String kickerLabel(LoadingCoverModel.Kicker kicker) {
        switch (kicker) {
            case RUNNING: return getContext().getString(R.string.transition_kicker_running);
            case SWITCHING: return getContext().getString(R.string.transition_kicker_switching);
            case CLOSING: return getContext().getString(R.string.transition_kicker_closing);
            case FAILED: return getContext().getString(R.string.transition_kicker_failed);
            default: return getContext().getString(R.string.transition_kicker_starting);
        }
    }

    /**
     * Artwork, when shown, replaces the standard backdrop; the card above never moves. While
     * the requested artwork decodes, the cover is its plain dark ground: the standard backdrop
     * never shows for a frame before the game's artwork.
     */
    private void applyBackground() {
        boolean art = shownArtwork != null;
        defaultBackdrop.setVisibility(neutral || art || artwork.pending() ? GONE : VISIBLE);
        artworkView.setVisibility(!neutral && art ? VISIBLE : GONE);
        scrim.setVisibility(neutral ? GONE : VISIBLE);
        clock.setVisibility(neutral ? GONE : VISIBLE);
        card.setVisibility(neutral ? GONE : VISIBLE);
    }

    private void showArtwork(Bitmap bitmap) {
        if (stopped) return;
        if (bitmap == shownArtwork) {
            // No artwork either way: whether one is still decoding decides the ground.
            applyBackground();
            return;
        }
        artworkView.animate().cancel();
        shownArtwork = bitmap;
        artworkView.setImageBitmap(bitmap);
        applyBackground();
        if (bitmap == null) return;
        MoonWakerDiagnostics.record("INFO", "android.cover", "cover.artwork",
                "outcome", "shown", "kind", model.kicker().name().toLowerCase(Locale.ROOT));
        // Not on screen yet (a handoff's first frame, a window still hidden): shown as is,
        // never faded in over black.
        if (handedOver || !isAttachedToWindow() || reducedMotion || getVisibility() != VISIBLE
                || getWindowVisibility() != VISIBLE) {
            artworkView.setAlpha(1f);
        } else {
            artworkView.setAlpha(0f);
            artworkView.animate().alpha(1f).setDuration(260L).start();
        }
    }

    private static int progressForStage(String stage) {
        if (stage == null) return 2;
        String lower = stage.toLowerCase(Locale.ROOT);
        if (lower.contains("video") || lower.contains("audio")) return 3;
        if (lower.contains("control") || lower.contains("input")) return 3;
        return 2;
    }

    public String friendlyStage(String stage) {
        if (stage == null || stage.isEmpty()) {
            return getContext().getString(R.string.transition_connecting_stream);
        }
        String lower = stage.toLowerCase(Locale.ROOT);
        if (lower.contains("rtsp") || lower.contains("video") || lower.contains("audio")
                || lower.contains("control") || lower.contains("input")) {
            return getContext().getString(R.string.transition_connecting_stream);
        }
        return getContext().getString(R.string.transition_preparing_session);
    }

    private void stopAnimations() {
        if (stopped) return;
        stopped = true;
        artwork.stop();
        frames.stop();
        segments.stopAll();
        handler.removeCallbacksAndMessages(null);
        lineView.animate().cancel();
        stageView.animate().cancel();
        animate().cancel();
    }

    private int nextMessageIndex() {
        int next;
        do {
            next = random.nextInt(messages.length);
        } while (messages.length > 1 && next == lastMessageIndex);
        lastMessageIndex = next;
        return next;
    }

    private void scheduleLoadingMessage() {
        handler.removeCallbacks(rotateMessage);
        if (stopped || reducedMotion || !model.messageShown() || messages.length == 0) {
            return;
        }
        handler.postDelayed(rotateMessage, MESSAGE_INTERVAL_MS);
    }

    /** A light pill: translucent with a thin border, solid ink with dark text when focused. */
    private void pill(View view) {
        view.setFocusable(true);
        view.setClickable(true);
        view.setMinimumHeight(dp(ACTION_HEIGHT_DP));
        view.setPadding(dp(18), 0, dp(18), 0);
        StateListDrawable background = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(999));
        focused.setColor(INK);
        GradientDrawable rest = new GradientDrawable();
        rest.setCornerRadius(dp(999));
        rest.setColor(0x14FFFFFF);
        rest.setStroke(Math.max(1, dp(.75f)), LINE);
        background.addState(new int[]{android.R.attr.state_focused}, focused);
        background.addState(new int[0], rest);
        view.setBackground(background);
    }

    private TextView pillText(String value) {
        TextView view = text(value, ACTION_SP, INK);
        view.setTextColor(pillInk());
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setGravity(Gravity.CENTER);
        pill(view);
        return view;
    }

    private static ColorStateList pillInk() {
        return new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{ON_INK, INK});
    }


    /** The visible actions in display order. */
    private List<View> visibleActions() {
        List<View> visible = new ArrayList<>(3);
        for (int index = 0; index < actionsRow.getChildCount(); index++) {
            View child = actionsRow.getChildAt(index);
            if (child.getVisibility() == VISIBLE) visible.add(child);
        }
        return visible;
    }

    /** The primary (first) action takes the focus unless an action already has it. */
    private void requestDefaultActionFocus() {
        if (isVisibleAction(findFocus())) return;
        List<View> visible = visibleActions();
        if (!visible.isEmpty()) visible.get(0).requestFocus();
    }

    private boolean moveActionFocus(int delta) {
        List<View> visible = visibleActions();
        int index = visible.indexOf(findFocus());
        if (index < 0) {
            requestDefaultActionFocus();
            return true;
        }
        visible.get(Math.max(0, Math.min(visible.size() - 1, index + delta))).requestFocus();
        return true;
    }

    private boolean isVisibleAction(View view) {
        return actionsRow.getVisibility() == VISIBLE
                && view != null && view.getVisibility() == VISIBLE
                && (view == cancelView || view == retryView || view == showAnywayView);
    }

    private static boolean isLoadingActionKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_BUTTON_B
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    private LayoutParams match() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams row(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(ACTION_HEIGHT_DP));
        params.rightMargin = dp(10);
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** The app's standard background, under a cover without game artwork. */
    private static final class Backdrop extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader backgroundShader;

        Backdrop(Context context) {
            super(context);
        }

        @Override protected void onSizeChanged(int width, int height,
                                               int oldWidth, int oldHeight) {
            backgroundShader = new LinearGradient(0, 0, width, height,
                    new int[]{0xFF090B14, 0xFF171633, 0xFF311A58}, null,
                    Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            paint.setShader(backgroundShader);
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);
            paint.setColor(0x347C4DFF);
            canvas.drawCircle(width * .76f, height * .25f, height * .44f, paint);
            paint.setColor(0x2937B5FF);
            canvas.drawCircle(width * .24f, height * .84f, height * .52f, paint);
        }
    }

    /**
     * A light corner vignette (clear to 60%, 42% black at the corners) under a scrim from the
     * left (82% to clear at 70% width) and from the bottom (85% to clear at 55% height).
     */
    private static final class Scrim extends View {
        private static final int SHADE = 0x05070B;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader vignette;
        private Shader left;
        private Shader bottom;

        Scrim(Context context) {
            super(context);
        }

        @Override protected void onSizeChanged(int width, int height,
                                               int oldWidth, int oldHeight) {
            vignette = new RadialGradient(width / 2f, width / 2f, Math.max(1f, width * .85f),
                    new int[]{SHADE, SHADE, 0x6B000000 | SHADE}, new float[]{0f, .6f, 1f},
                    Shader.TileMode.CLAMP);
            left = new LinearGradient(0, 0, width, 0,
                    new int[]{0xD1000000 | SHADE, 0x73000000 | SHADE, SHADE},
                    new float[]{0f, .38f, .7f}, Shader.TileMode.CLAMP);
            bottom = new LinearGradient(0, height, 0, 0,
                    new int[]{0xD9000000 | SHADE, SHADE}, new float[]{0f, .55f},
                    Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            if (width == 0 || height == 0) return;
            // The ellipse: a circle around (w/2, w/2) squeezed to the screen's height.
            canvas.save();
            canvas.scale(1f, height / (float) width);
            paint.setShader(vignette);
            canvas.drawRect(0, 0, width, width, paint);
            canvas.restore();
            paint.setShader(left);
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(bottom);
            canvas.drawRect(0, 0, width, height, paint);
        }
    }

    /**
     * Five thin segments: done is solid, the current one sweeps (static at half strength with
     * reduced motion or animations off), attention is amber, the rest faint.
     */
    private final class Segments extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final LoadingCoverModel.StepState[] states =
                new LoadingCoverModel.StepState[LoadingCoverModel.STEPS];
        /** How much of each done segment is filled: a completed one grows from 0 to 1. */
        private final float[] fill = new float[LoadingCoverModel.STEPS];
        private final ValueAnimator[] grow = new ValueAnimator[LoadingCoverModel.STEPS];
        private int currentIndex = -1;
        private ValueAnimator sweep;
        /** How much of the current segment is done; negative: unknown, the segment sweeps. */
        private float progress = -1f;

        Segments(Context context) {
            super(context);
            Arrays.fill(states, LoadingCoverModel.StepState.PENDING);
        }

        void show(LoadingCoverModel model) {
            boolean motion = animatesChanges();
            int current = -1;
            for (int index = 0; index < states.length; index++) {
                LoadingCoverModel.StepState next = model.stepState(index);
                boolean done = next == LoadingCoverModel.StepState.DONE;
                if (done && states[index] != LoadingCoverModel.StepState.DONE) {
                    fillIn(index, motion);
                } else if (!done) {
                    if (grow[index] != null) grow[index].cancel();
                    fill[index] = 0f;
                }
                states[index] = next;
                if (next == LoadingCoverModel.StepState.CURRENT) current = index;
            }
            // The next segment's sweep starts from its left edge, never mid-way.
            if (current != currentIndex) stopSweep();
            currentIndex = current;
            progress = model.stepFraction();
            boolean animate = current >= 0 && progress < 0f && model.stepsVisible()
                    && !reducedMotion && !stopped && ValueAnimator.areAnimatorsEnabled();
            if (animate && sweep == null) {
                sweep = ValueAnimator.ofFloat(0f, 1f);
                sweep.setDuration(1400L);
                sweep.setRepeatCount(ValueAnimator.INFINITE);
                sweep.setInterpolator(new AccelerateDecelerateInterpolator());
                sweep.addUpdateListener(animation -> invalidate());
                sweep.start();
            } else if (!animate) {
                stopSweep();
            }
            invalidate();
        }

        private void fillIn(int index, boolean motion) {
            if (grow[index] != null) grow[index].cancel();
            grow[index] = null;
            if (!motion) {
                fill[index] = 1f;
                return;
            }
            fill[index] = 0f;
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(SEGMENT_FILL_MS);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(animation -> {
                fill[index] = (float) animation.getAnimatedValue();
                invalidate();
            });
            grow[index] = animator;
            animator.start();
        }

        void stopSweep() {
            if (sweep == null) return;
            sweep.cancel();
            sweep = null;
        }

        /** Stops every animation; done segments show full. */
        void stopAll() {
            stopSweep();
            for (int index = 0; index < grow.length; index++) {
                if (grow[index] == null) continue;
                grow[index].cancel();
                grow[index] = null;
                if (states[index] == LoadingCoverModel.StepState.DONE) fill[index] = 1f;
            }
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stopAll();
        }

        @Override protected void onDraw(Canvas canvas) {
            float gap = dp(4);
            float width = (getWidth() - gap * (states.length - 1)) / states.length;
            float height = getHeight();
            float radius = height / 2f;
            for (int index = 0; index < states.length; index++) {
                float left = index * (width + gap);
                rect.set(left, 0, left + width, height);
                LoadingCoverModel.StepState state = states[index];
                paint.setColor(state == LoadingCoverModel.StepState.ATTENTION ? WARN : LINE);
                canvas.drawRoundRect(rect, radius, radius, paint);
                if (state == LoadingCoverModel.StepState.DONE) {
                    // A completed segment fills from its left edge.
                    paint.setColor(INK);
                    rect.right = left + width * fill[index];
                    canvas.drawRoundRect(rect, radius, radius, paint);
                    continue;
                }
                if (state != LoadingCoverModel.StepState.CURRENT) continue;
                paint.setColor(INK);
                if (progress >= 0f) {
                    rect.right = left + width * progress;
                    canvas.drawRoundRect(rect, radius, radius, paint);
                    continue;
                }
                if (sweep == null) {
                    paint.setAlpha(128);
                    canvas.drawRoundRect(rect, radius, radius, paint);
                    continue;
                }
                // A 40% bar crossing the segment, clipped to it.
                float bar = width * .4f;
                float x = left - bar + (width + bar) * (float) sweep.getAnimatedValue();
                canvas.save();
                canvas.clipRect(rect);
                rect.set(x, 0, x + bar, height);
                canvas.drawRoundRect(rect, radius, radius, paint);
                canvas.restore();
            }
        }
    }
}
