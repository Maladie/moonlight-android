package com.limelight.console;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;

/** Passive in-app DM card shared by the stream and console roots. */
public final class DiscordDmToastView extends FrameLayout
        implements DiscordDmNotificationCoordinator.Host {
    static final int MAX_WIDTH_DP = 420;
    static final int MIN_PLACEMENT_WIDTH_DP = 220;
    static final int CARD_HEIGHT_DP = 88;
    static final int MAX_HEIGHT_DP = 96;
    static final int EDGE_DP = 18;
    static final int DOCK_GAP_DP = 12;
    static final long ENTER_MS = 180L;
    static final long EXIT_MS = 150L;

    static final class Placement {
        final int width;
        final int rightMargin;
        final int topMargin;

        Placement(int width, int rightMargin, int topMargin) {
            this.width = width;
            this.rightMargin = rightMargin;
            this.topMargin = topMargin;
        }
    }

    private final FrameLayout avatarSlot;
    private final TextView message;
    private final TextView metadata;
    private DiscordDmNotificationCoordinator.ToastModel model;
    private CharSequence shortcutLabel = "";
    private String avatarSignature = "";
    private View avoidView;
    private boolean avoidListenerAttached;
    private final View.OnLayoutChangeListener avoidLayoutListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    updatePlacement();
    private boolean reducedMotion;
    private boolean exiting;
    private DiscordDmNotificationCoordinator.ToastModel pendingModel;
    private boolean pendingAnnouncement;
    private int animationGeneration;

    public DiscordDmToastView(Context context) {
        super(context);
        passive(this);
        setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        setVisibility(GONE);
        setMinimumHeight(dp(CARD_HEIGHT_DP));
        setPadding(dp(16), dp(12), dp(16), dp(12));
        setElevation(dp(12));
        setBackground(cardBackground());
        setClipToOutline(true);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        passive(row);
        addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        avatarSlot = new FrameLayout(context);
        passive(avatarSlot);
        row.addView(avatarSlot, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(12), 0, 0, 0);
        passive(body);
        row.addView(body, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        message = new TextView(context);
        message.setTextColor(Color.WHITE);
        message.setTextSize(15);
        message.setMaxLines(2);
        message.setEllipsize(TextUtils.TruncateAt.END);
        message.setLineSpacing(0, 1.04f);
        passive(message);
        body.addView(message, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        metadata = new TextView(context);
        metadata.setTextColor(0xFFBDC4D8);
        metadata.setTextSize(11);
        metadata.setSingleLine(true);
        metadata.setEllipsize(TextUtils.TruncateAt.END);
        metadata.setVisibility(GONE);
        passive(metadata);
        LinearLayout.LayoutParams metadataParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metadataParams.topMargin = dp(3);
        body.addView(metadata, metadataParams);
        addOnLayoutChangeListener((view, left, top, right, bottom,
                                   oldLeft, oldTop, oldRight, oldBottom) -> {
            if (getVisibility() == VISIBLE
                    && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) {
                updatePlacement();
            }
        });
    }

    public DiscordDmToastView(Context context, AttributeSet attributes) {
        this(context);
    }

    public DiscordDmToastView(Context context, AttributeSet attributes, int style) {
        this(context);
    }

    public void setReducedMotion(boolean reducedMotion) {
        this.reducedMotion = reducedMotion;
    }

    public void setShortcutLabel(CharSequence shortcutLabel) {
        this.shortcutLabel = shortcutLabel == null ? "" : shortcutLabel;
        updateMetadata();
    }

    public void setShortcutTrigger(String trigger) {
        setShortcutLabel(getContext().getString(R.string.discord_dm_toast_shortcut,
                getContext().getString(DiscordDmShortcutHandler.triggerLabelResource(trigger))));
    }

    /** Keeps the Game card beside the pinned Discord dock, never on top of it. */
    public void setAvoidView(View avoidView) {
        detachAvoidListener();
        this.avoidView = avoidView;
        attachAvoidListener();
        post(this::updatePlacement);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (avoidView == null && getParent() instanceof View) {
            avoidView = ((View) getParent()).findViewById(R.id.discordDockView);
        }
        attachAvoidListener();
    }

    @Override protected void onDetachedFromWindow() {
        detachAvoidListener();
        super.onDetachedFromWindow();
    }

    @Override public void showDiscordDmToast(
            DiscordDmNotificationCoordinator.ToastModel model, boolean announce) {
        if (model == null) return;
        if (!isOnUiThread()) {
            post(() -> showDiscordDmToast(model, announce));
            return;
        }
        if (exiting) {
            pendingModel = model;
            pendingAnnouncement = announce;
            return;
        }
        this.model = model;
        renderModel();
        updatePlacement();
        boolean entering = getVisibility() != VISIBLE;
        int generation = ++animationGeneration;
        animate().cancel();
        setVisibility(VISIBLE);
        if (entering && !reducedMotion) {
            setAlpha(0f);
            setTranslationX(dp(70));
            animate().alpha(1f).translationX(0f).setDuration(ENTER_MS)
                    .withEndAction(() -> {
                        if (generation == animationGeneration) setTranslationX(0f);
                    }).start();
        } else {
            setAlpha(1f);
            setTranslationX(0f);
        }
        if (announce) announceForAccessibility(message.getText());
    }

    @Override public void hideDiscordDmToast() {
        if (!isOnUiThread()) {
            post(this::hideDiscordDmToast);
            return;
        }
        if (getVisibility() != VISIBLE) return;
        pendingModel = null;
        pendingAnnouncement = false;
        exiting = true;
        int generation = ++animationGeneration;
        animate().cancel();
        if (reducedMotion) {
            finishHide(generation);
        } else {
            animate().alpha(0f).translationX(dp(50)).setDuration(EXIT_MS)
                    .withEndAction(() -> finishHide(generation)).start();
        }
    }

    @Override public void hideDiscordDmToastImmediately() {
        if (!isOnUiThread()) {
            post(this::hideDiscordDmToastImmediately);
            return;
        }
        pendingModel = null;
        pendingAnnouncement = false;
        exiting = false;
        int generation = ++animationGeneration;
        animate().cancel();
        finishHide(generation);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        return true;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            widthSize = dp(MAX_WIDTH_DP);
        }
        int width = Math.min(dp(MAX_WIDTH_DP), widthSize);
        int constrainedWidth = MeasureSpec.makeMeasureSpec(width,
                MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
                        ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST);
        int heightSize = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? dp(MAX_HEIGHT_DP)
                : Math.min(dp(MAX_HEIGHT_DP), MeasureSpec.getSize(heightMeasureSpec));
        super.onMeasure(constrainedWidth, MeasureSpec.makeMeasureSpec(
                heightSize, MeasureSpec.AT_MOST));
        setMeasuredDimension(getMeasuredWidth(), Math.max(dp(CARD_HEIGHT_DP), getMeasuredHeight()));
    }

    private void renderModel() {
        CharSequence text = model.neutral
                ? getContext().getString(R.string.discord_dm_toast_neutral, model.senderName)
                : getContext().getString(R.string.discord_dm_toast_message,
                        model.senderName, model.snippet);
        message.setText(text);
        setContentDescription(text);
        updateMetadata();

        String signature = model.senderName + '\u0000' + model.avatarUrl;
        if (signature.equals(avatarSignature)) return;
        avatarSignature = signature;
        avatarSlot.removeAllViews();
        FrameLayout avatar = DiscordCommunityPresentation.avatar(
                getContext(), model.senderName, model.avatarUrl, 40);
        passiveTree(avatar);
        avatarSlot.addView(avatar, new FrameLayout.LayoutParams(dp(40), dp(40)));
    }

    private void updateMetadata() {
        if (metadata == null) return;
        String count = model != null && model.additionalCount > 0
                ? getResources().getQuantityString(R.plurals.discord_dm_toast_more,
                        model.additionalCount, model.additionalCount)
                : "";
        String shortcut = model != null && model.actionable
                ? shortcutLabel.toString().trim() : "";
        String value = count.isEmpty() ? shortcut
                : shortcut.isEmpty() ? count : count + "  ·  " + shortcut;
        metadata.setText(value);
        metadata.setVisibility(value.isEmpty() ? GONE : VISIBLE);
    }

    private void finishHide(int generation) {
        if (generation != animationGeneration) return;
        setVisibility(GONE);
        setAlpha(1f);
        setTranslationX(0f);
        model = null;
        message.setText("");
        metadata.setText("");
        metadata.setVisibility(GONE);
        setContentDescription(null);
        avatarSlot.removeAllViews();
        avatarSignature = "";
        exiting = false;
        DiscordDmNotificationCoordinator.ToastModel next = pendingModel;
        boolean announce = pendingAnnouncement;
        pendingModel = null;
        pendingAnnouncement = false;
        if (next != null) showDiscordDmToast(next, announce);
    }

    private void updatePlacement() {
        if (!(getParent() instanceof FrameLayout)) return;
        FrameLayout parent = (FrameLayout) getParent();
        if (parent.getWidth() <= 0 || parent.getHeight() <= 0) return;
        boolean avoiding = avoidView != null && avoidView.getVisibility() == VISIBLE
                && avoidView.getWidth() > 0;
        Placement placement = choosePlacement(parent.getWidth(), parent.getHeight(),
                avoiding ? avoidView.getLeft() : 0,
                avoiding ? avoidView.getBottom() : 0,
                avoiding, dp(EDGE_DP), dp(DOCK_GAP_DP), dp(MAX_WIDTH_DP),
                dp(MIN_PLACEMENT_WIDTH_DP), dp(CARD_HEIGHT_DP));
        FrameLayout.LayoutParams params = getLayoutParams() instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) getLayoutParams()
                : new FrameLayout.LayoutParams(placement.width, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.width = placement.width;
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.TOP | Gravity.END;
        params.rightMargin = placement.rightMargin;
        params.topMargin = placement.topMargin;
        setLayoutParams(params);
    }

    private void attachAvoidListener() {
        if (avoidView == null || avoidListenerAttached) return;
        avoidView.addOnLayoutChangeListener(avoidLayoutListener);
        avoidListenerAttached = true;
    }

    private void detachAvoidListener() {
        if (avoidView == null || !avoidListenerAttached) return;
        avoidView.removeOnLayoutChangeListener(avoidLayoutListener);
        avoidListenerAttached = false;
    }

    static Placement choosePlacement(int viewportWidth, int viewportHeight,
                                     int dockLeft, int dockBottom, boolean dockVisible,
                                     int edge, int gap, int maxWidth, int minWidth,
                                     int toastHeight) {
        int fullWidth = Math.min(maxWidth, Math.max(0, viewportWidth - edge * 2));
        if (!dockVisible) return new Placement(fullWidth, edge, edge);
        int leftWidth = Math.max(0, dockLeft - gap - edge);
        if (leftWidth >= minWidth) {
            return new Placement(Math.min(maxWidth, leftWidth),
                    Math.max(edge, viewportWidth - dockLeft + gap), edge);
        }
        return new Placement(fullWidth, edge, Math.min(dockBottom + gap,
                Math.max(edge, viewportHeight - edge - toastHeight)));
    }

    private GradientDrawable cardBackground() {
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xF21A2130, 0xF210141D});
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), 0x42FFFFFF);
        return background;
    }

    private static void passiveTree(View view) {
        passive(view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        group.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        for (int index = 0; index < group.getChildCount(); index++) {
            passiveTree(group.getChildAt(index));
        }
    }

    private static void passive(View view) {
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        view.setClickable(false);
        view.setSoundEffectsEnabled(false);
    }

    private boolean isOnUiThread() {
        return android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
