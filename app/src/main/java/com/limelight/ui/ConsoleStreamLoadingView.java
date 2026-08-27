package com.limelight.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.limelight.R;

import java.util.Locale;
import java.util.Random;

/** A fully opaque, modal privacy surface rendered in the Activity window above video. */
public final class ConsoleStreamLoadingView extends FrameLayout {
    private static final long MESSAGE_INTERVAL_MS = 2300L;

    public ConsoleStreamLoadingView(Context context) {
        this(context, null, null, 0L, false);
    }

    public ConsoleStreamLoadingView(Context context, AttributeSet attrs) {
        this(context, null, null, 0L, false);
    }

    public interface Actions {
        void onCancel();
        void onRetry();
        void onShowStreamAnyway();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final String[] messages;
    private final Backdrop defaultBackdrop;
    private final View defaultShade;
    private final LinearLayout defaultContent;
    private final TextView titleView;
    private final TextView messageView;
    private final LinearLayout stepsView;
    private final LinearLayout statusLine;
    private final LinearLayout actionsRow;
    private final TextView statusView;
    private final TextView[] stepViews = new TextView[5];
    private final ProgressBar activityView;
    private final TextView cancelView;
    private final TextView retryView;
    private final TextView showAnywayView;
    private final boolean reducedMotion;
    private int currentStep = 1;
    private boolean error;
    private boolean stopped;
    private boolean revealRequested;
    private int lastMessageIndex = -1;
    private boolean splashLayout;
    private Actions actions;

    private final Runnable rotateMessage = new Runnable() {
        @Override public void run() {
            if (stopped || error || getVisibility() != VISIBLE || messages.length == 0) {
                return;
            }
            int next;
            do {
                next = random.nextInt(messages.length);
            } while (messages.length > 1 && next == lastMessageIndex);
            lastMessageIndex = next;
            String nextMessage = messages[next];
            if (reducedMotion) {
                messageView.setText(nextMessage);
                return;
            }
            messageView.animate().cancel();
            messageView.animate().alpha(0f).setDuration(160L).withEndAction(() -> {
                if (stopped || error) return;
                messageView.setText(nextMessage);
                messageView.animate().alpha(1f).setDuration(240L).start();
            }).start();
            handler.postDelayed(this, MESSAGE_INTERVAL_MS);
        }
    };

    public ConsoleStreamLoadingView(Context context, String title, String initialMessage,
                                    long animationEpoch, boolean reducedMotion) {
        super(context);
        this.reducedMotion = reducedMotion;
        messages = getResources().getStringArray(R.array.console_loading_messages);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setBackgroundColor(Color.rgb(5, 6, 10));
        setElevation(dp(64));
        setLayerType(View.LAYER_TYPE_HARDWARE, null);

        defaultBackdrop = new Backdrop(context, animationEpoch);
        addView(defaultBackdrop, match());
        defaultShade = new View(context);
        defaultShade.setBackgroundColor(0x57000000);
        addView(defaultShade, match());

        defaultContent = new LinearLayout(context);
        defaultContent.setOrientation(LinearLayout.VERTICAL);
        defaultContent.setGravity(Gravity.CENTER_HORIZONTAL);
        defaultContent.setPadding(dp(36), dp(28), dp(36), dp(28));
        LayoutParams contentParams = new LayoutParams(dp(900),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        addView(defaultContent, contentParams);

        titleView = text(title == null || title.trim().isEmpty()
                ? context.getString(R.string.app_label) : title, 38, Color.WHITE, true);
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(2);
        defaultContent.addView(titleView, row());

        messageView = text(initialMessage == null || initialMessage.trim().isEmpty()
                ? context.getString(R.string.transition_preparing_session)
                : initialMessage, 23, 0xFFE5E8F5, false);
        messageView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = row();
        messageParams.topMargin = dp(12);
        defaultContent.addView(messageView, messageParams);

        stepsView = new LinearLayout(context);
        stepsView.setOrientation(LinearLayout.VERTICAL);
        stepsView.setVisibility(GONE);
        for (int index = 0; index < stepViews.length; index++) {
            stepViews[index] = text("", 17, 0xFF8E99AA, false);
            stepViews[index].setMinHeight(dp(42));
            stepViews[index].setGravity(Gravity.CENTER_VERTICAL);
            stepViews[index].setPadding(dp(16), 0, dp(16), 0);
            stepsView.addView(stepViews[index], row());
        }

        statusLine = new LinearLayout(context);
        statusLine.setOrientation(LinearLayout.HORIZONTAL);
        statusLine.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLineParams = row();
        statusLineParams.topMargin = dp(12);
        defaultContent.addView(statusLine, statusLineParams);

        activityView = new ProgressBar(context);
        activityView.setIndeterminate(true);
        statusLine.addView(activityView, new LinearLayout.LayoutParams(dp(28), dp(28)));
        statusView = text(context.getString(R.string.transition_preparing_session),
                14, 0xFFB8C7D8, false);
        statusView.setPadding(dp(12), 0, 0, 0);
        statusLine.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        actionsRow = new LinearLayout(context);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionsParams = row();
        actionsParams.topMargin = dp(22);
        defaultContent.addView(actionsRow, actionsParams);

        cancelView = action(context.getString(R.string.transition_cancel));
        cancelView.setId(View.generateViewId());
        cancelView.setOnClickListener(view -> { if (actions != null) actions.onCancel(); });
        actionsRow.addView(cancelView, actionParams());
        retryView = action(context.getString(R.string.transition_retry));
        retryView.setVisibility(GONE);
        retryView.setOnClickListener(view -> { if (actions != null) actions.onRetry(); });
        actionsRow.addView(retryView, actionParams());
        showAnywayView = action(context.getString(R.string.transition_reveal_now));
        showAnywayView.setId(View.generateViewId());
        showAnywayView.setVisibility(GONE);
        showAnywayView.setOnClickListener(view -> {
            if (actions != null) actions.onShowStreamAnyway();
        });
        actionsRow.addView(showAnywayView, actionParams());

        configureForPlaynite(false);
        setStep(1, context.getString(R.string.transition_preparing_session));
        scheduleLoadingMessage(false);
        post(this::requestDefaultActionFocus);
    }

    public void setActions(Actions actions) {
        this.actions = actions;
    }

    public void setSplashArtwork(String artworkPath) {
        if (artworkPath == null || artworkPath.trim().isEmpty() || stopped) return;
        String path = artworkPath.trim();
        Bitmap bitmap = decodeSplashArtwork(path);
        if (bitmap != null) applySplashArtwork(bitmap);
    }

    private Bitmap decodeSplashArtwork(String path) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (!LoadingArtworkPolicy.canUseAsSplash(
                bounds.outWidth, bounds.outHeight)) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= 1920
                && bounds.outHeight / (sample * 2) >= 1080) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(path, options);
    }

    public void configureForPlaynite(boolean playnite) {
        int[] labels = playnite
                ? new int[]{R.string.transition_preparing_session,
                R.string.transition_connecting_stream,
                R.string.transition_starting_playnite,
                R.string.transition_waiting_fullscreen,
                R.string.transition_ready}
                : new int[]{R.string.transition_preparing_session,
                R.string.transition_connecting_stream,
                R.string.transition_starting_game,
                R.string.transition_verifying_readiness,
                R.string.transition_ready};
        for (int index = 0; index < labels.length; index++) {
            stepViews[index].setTag(labels[index]);
        }
        renderSteps();
    }

    public void doAfterNextFrame(Runnable action) {
        if (action == null || stopped) return;
        postOnAnimation(() -> postOnAnimation(() -> {
            if (!stopped && isAttachedToWindow() && getVisibility() == VISIBLE) action.run();
        }));
    }

    public void setStage(String stage) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> setStage(stage));
            return;
        }
        if (stopped) return;
        String friendly = friendlyStage(stage);
        statusView.setText(friendly);
        int step = progressForStage(stage);
        if (step > currentStep) setStep(step, friendly);
    }

    public void setStep(int step, String status) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> setStep(step, status));
            return;
        }
        if (stopped) return;
        boolean resumingAfterError = error;
        currentStep = Math.max(1, Math.min(5, step));
        error = false;
        statusView.setTextColor(0xFFB8C7D8);
        statusView.setText(status == null || status.trim().isEmpty()
                ? getContext().getString(R.string.transition_preparing_session) : status);
        activityView.setVisibility(currentStep == 5 ? INVISIBLE : VISIBLE);
        cancelView.setText(R.string.transition_cancel);
        retryView.setVisibility(GONE);
        showAnywayView.setVisibility(GONE);
        renderSteps();
        announceStep();
        if (resumingAfterError) scheduleLoadingMessage(true);
    }

    public String getCurrentMessage() {
        CharSequence value = messageView.getText();
        return value == null ? "" : value.toString();
    }

    public void showError(String title, String details) {
        showError(title, details, false);
    }

    public void showError(String title, String details, boolean allowShowAnyway) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> showError(title, details, allowShowAnyway));
            return;
        }
        if (stopped) return;
        error = true;
        handler.removeCallbacks(rotateMessage);
        messageView.animate().cancel();
        messageView.setAlpha(1f);
        messageView.setText(title);
        statusView.setText(details);
        statusView.setTextColor(0xFFFFAAA2);
        activityView.setVisibility(INVISIBLE);
        cancelView.setText(R.string.transition_cancel);
        retryView.setVisibility(VISIBLE);
        showAnywayView.setVisibility(allowShowAnyway ? VISIBLE : GONE);
        renderSteps();
        retryView.requestFocus();
        sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT);
    }

    public void showLauncherInteraction(String title, String details, boolean allowReveal) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> showLauncherInteraction(title, details, allowReveal));
            return;
        }
        if (stopped) return;
        error = true;
        handler.removeCallbacks(rotateMessage);
        messageView.animate().cancel();
        messageView.setAlpha(1f);
        messageView.setText(title);
        statusView.setText(details);
        statusView.setTextColor(0xFFFFAAA2);
        activityView.setVisibility(INVISIBLE);
        cancelView.setText(R.string.transition_back);
        retryView.setVisibility(GONE);
        showAnywayView.setVisibility(allowReveal ? VISIBLE : GONE);
        cancelView.setNextFocusRightId(showAnywayView.getId());
        showAnywayView.setNextFocusLeftId(cancelView.getId());
        renderSteps();
        if (allowReveal) showAnywayView.requestFocus();
        else cancelView.requestFocus();
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
        if (stopped || error || revealRequested) return;
        boolean changed = showAnywayView.getVisibility() != (available ? VISIBLE : GONE);
        showAnywayView.setVisibility(available ? VISIBLE : GONE);
        if (available) {
            // Keep the existing Cancel focus, but make Odsłoń teraz the explicit
            // next DPAD-right option for remotes and controllers.
            cancelView.setNextFocusRightId(showAnywayView.getId());
            showAnywayView.setNextFocusLeftId(cancelView.getId());
            showAnywayView.requestFocus();
            if (changed) {
                sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            }
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
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            return cancelView.performClick();
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            return focusPreviousAction();
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return focusNextAction();
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

    public void waitingForVideo() {
        setStep(4, getContext().getString(R.string.console_stream_waiting_video));
    }

    public void revealStream() {
        revealStream(null);
    }

    public void revealStream(Runnable afterReveal) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> revealStream(afterReveal));
            return;
        }
        if (stopped || revealRequested) return;
        revealRequested = true;
        setStep(5, getContext().getString(R.string.transition_ready));
        doAfterNextFrame(() -> {
            if (stopped) return;
            handler.removeCallbacks(rotateMessage);
            if (reducedMotion) {
                setVisibility(GONE);
                if (afterReveal != null) afterReveal.run();
            } else {
                animate().alpha(0f).setDuration(300L).withEndAction(() -> {
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
        animate().cancel();
        revealRequested = false;
        setAlpha(1f);
        setVisibility(VISIBLE);
        bringToFront();
        requestDefaultActionFocus();
        if (!error) scheduleLoadingMessage(false);
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

    private void renderSteps() {
        for (int index = 0; index < stepViews.length; index++) {
            TextView view = stepViews[index];
            Object tag = view.getTag();
            String label = tag instanceof Integer ? getContext().getString((Integer) tag) : "";
            if (index + 1 < currentStep) {
                view.setText(getContext().getString(R.string.transition_step_complete, label));
                view.setTextColor(0xFFB8D7E8);
                view.setTypeface(android.graphics.Typeface.DEFAULT,
                        android.graphics.Typeface.NORMAL);
            } else if (index + 1 == currentStep) {
                view.setText(getContext().getString(error
                        ? R.string.transition_step_error : R.string.transition_step_active, label));
                view.setTextColor(error ? 0xFFFFAAA2 : 0xFF7DD8FF);
                view.setTypeface(android.graphics.Typeface.DEFAULT,
                        android.graphics.Typeface.BOLD);
            } else {
                view.setText(getContext().getString(R.string.transition_step_pending, label));
                view.setTextColor(0xFF7F8999);
                view.setTypeface(android.graphics.Typeface.DEFAULT,
                        android.graphics.Typeface.NORMAL);
            }
        }
    }

    private void announceStep() {
        TextView current = stepViews[currentStep - 1];
        current.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        current.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
    }

    private static int progressForStage(String stage) {
        if (stage == null) return 2;
        String lower = stage.toLowerCase(Locale.ROOT);
        if (lower.contains("video") || lower.contains("audio")) return 3;
        if (lower.contains("control") || lower.contains("input")) return 3;
        return 2;
    }

    private String friendlyStage(String stage) {
        if (stage == null || stage.isEmpty()) {
            return getContext().getString(R.string.transition_connecting_stream);
        }
        String lower = stage.toLowerCase(Locale.ROOT);
        if (lower.contains("rtsp")) return getContext().getString(R.string.console_stream_rtsp);
        if (lower.contains("video")) return getContext().getString(R.string.console_stream_video);
        if (lower.contains("audio")) return getContext().getString(R.string.console_stream_audio);
        if (lower.contains("control")) return getContext().getString(R.string.console_stream_control);
        if (lower.contains("input")) return getContext().getString(R.string.console_stream_input);
        return getContext().getString(R.string.console_stream_starting_stage, stage);
    }

    private void stopAnimations() {
        if (stopped) return;
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        messageView.animate().cancel();
        animate().cancel();
    }

    private void applySplashArtwork(Bitmap bitmap) {
        if (splashLayout || stopped) {
            bitmap.recycle();
            return;
        }
        splashLayout = true;

        ImageView artwork = new ImageView(getContext());
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setImageBitmap(bitmap);
        artwork.setAlpha(0f);
        addView(artwork, 1, match());

        View readability = new View(getContext());
        GradientDrawable scrim = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB8000000, 0x18000000, 0x18000000, 0xD9000000});
        readability.setBackground(scrim);
        addView(readability, 2, match());

        defaultBackdrop.setVisibility(GONE);
        defaultShade.setVisibility(GONE);
        defaultContent.setVisibility(GONE);

        detach(messageView);
        detach(statusLine);
        detach(actionsRow);
        titleView.setVisibility(GONE);

        messageView.setTextSize(18);
        messageView.setGravity(Gravity.CENTER);
        messageView.setShadowLayer(dp(4), 0, dp(1), 0xE6000000);
        LayoutParams messageParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        messageParams.setMargins(dp(64), dp(32), dp(64), 0);
        addView(messageView, messageParams);

        LinearLayout centerControls = new LinearLayout(getContext());
        centerControls.setOrientation(LinearLayout.VERTICAL);
        centerControls.setGravity(Gravity.CENTER_HORIZONTAL);

        statusView.setTextSize(12);
        activityView.getLayoutParams().width = dp(22);
        activityView.getLayoutParams().height = dp(22);
        LinearLayout.LayoutParams compactStatus = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        centerControls.addView(statusLine, compactStatus);

        LinearLayout.LayoutParams compactActions = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        compactActions.topMargin = dp(10);
        centerControls.addView(actionsRow, compactActions);

        LayoutParams centerControlsParams = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        centerControlsParams.bottomMargin = dp(24);
        addView(centerControls, centerControlsParams);

        artwork.animate().alpha(1f).setDuration(reducedMotion ? 0L : 260L).start();
        bringChildToFront(messageView);
        bringChildToFront(centerControls);
        requestDefaultActionFocus();
        showSplashMessageImmediately();
    }

    private void showSplashMessageImmediately() {
        handler.removeCallbacks(rotateMessage);
        messageView.animate().cancel();
        if (stopped || error || messages.length == 0) return;
        int next;
        do {
            next = random.nextInt(messages.length);
        } while (messages.length > 1 && next == lastMessageIndex);
        lastMessageIndex = next;
        messageView.setAlpha(1f);
        messageView.setText(messages[next]);
        if (!reducedMotion) handler.postDelayed(rotateMessage, MESSAGE_INTERVAL_MS);
    }

    private static void detach(View view) {
        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(view);
    }

    private void scheduleLoadingMessage(boolean immediate) {
        handler.removeCallbacks(rotateMessage);
        messageView.animate().cancel();
        messageView.setAlpha(1f);
        if (stopped || error || messages.length == 0) return;
        if (immediate || reducedMotion) {
            rotateMessage.run();
        } else {
            handler.postDelayed(rotateMessage, MESSAGE_INTERVAL_MS);
        }
    }

    private TextView action(String value) {
        TextView view = text(value, 14, 0xFFE7F5FF, true);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setClickable(true);
        view.setMinWidth(dp(96));
        view.setMinHeight(dp(48));
        view.setPadding(dp(16), dp(8), dp(16), dp(8));
        view.setBackgroundColor(0xFF172532);
        view.setOnFocusChangeListener((focusedView, focused) ->
                focusedView.setBackgroundColor(focused ? 0xFF355B78 : 0xFF172532));
        return view;
    }

    private void requestDefaultActionFocus() {
        View focused = findFocus();
        if (isVisibleAction(focused)) return;
        if (showAnywayView.getVisibility() == VISIBLE) showAnywayView.requestFocus();
        else if (retryView.getVisibility() == VISIBLE) retryView.requestFocus();
        else cancelView.requestFocus();
    }

    private boolean focusPreviousAction() {
        View focused = findFocus();
        if (focused == showAnywayView && retryView.getVisibility() == VISIBLE) {
            return retryView.requestFocus();
        }
        if (focused == showAnywayView || focused == retryView) {
            return cancelView.requestFocus();
        }
        requestDefaultActionFocus();
        return true;
    }

    private boolean focusNextAction() {
        View focused = findFocus();
        if (focused == cancelView && retryView.getVisibility() == VISIBLE) {
            return retryView.requestFocus();
        }
        if ((focused == cancelView || focused == retryView)
                && showAnywayView.getVisibility() == VISIBLE) {
            return showAnywayView.requestFocus();
        }
        requestDefaultActionFocus();
        return true;
    }

    private boolean isVisibleAction(View view) {
        return view != null && view.getVisibility() == VISIBLE
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

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD);
        return view;
    }

    private LayoutParams match() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams row() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        params.leftMargin = dp(6);
        params.rightMargin = dp(6);
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Backdrop extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float phase;
        private Shader backgroundShader;

        Backdrop(Context context, long animationEpoch) {
            super(context);
            long epoch = animationEpoch > 0L ? animationEpoch : SystemClock.uptimeMillis();
            phase = (SystemClock.uptimeMillis() - epoch) / 9000f;
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
            canvas.drawCircle(width * (.76f + .05f * (float) Math.sin(phase)),
                    height * (.20f + .05f * (float) Math.cos(phase)), height * .44f, paint);
            paint.setColor(0x2937B5FF);
            canvas.drawCircle(width * (.20f + .04f * (float) Math.cos(phase * .8f)),
                    height * (.84f + .04f * (float) Math.sin(phase * .8f)),
                    height * .52f, paint);
        }
    }
}
