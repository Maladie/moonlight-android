package com.limelight.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;

import java.util.Locale;
import java.util.Random;

/** Opaque console loader displayed until Moonlight renders the first stream frame. */
public final class ConsoleStreamLoadingView extends FrameLayout {
    private static final long MESSAGE_INTERVAL_MS = 2300L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final TextView messageView;
    private final TextView statusView;
    private final TextView progressView;
    private final boolean reducedMotion;
    private int lastMessageIndex = -1;
    private boolean stopped;
    private boolean revealRequested;

    private final String[] messages;

    private final Runnable rotateMessage = new Runnable() {
        @Override public void run() {
            if (stopped || getVisibility() != VISIBLE) return;
            int next;
            do {
                next = random.nextInt(messages.length);
            } while (messages.length > 1 && next == lastMessageIndex);
            lastMessageIndex = next;
            String nextMessage = messages[next];
            if (reducedMotion) {
                messageView.setText(nextMessage);
            } else {
                messageView.animate().cancel();
                messageView.animate().alpha(0f).setDuration(160L).withEndAction(() -> {
                    if (stopped) return;
                    messageView.setText(nextMessage);
                    messageView.animate().alpha(1f).setDuration(240L).start();
                }).start();
            }
            handler.postDelayed(this, MESSAGE_INTERVAL_MS);
        }
    };

    public ConsoleStreamLoadingView(Context context, String title, String initialMessage,
                                    long animationEpoch, boolean reducedMotion) {
        super(context);
        this.reducedMotion = reducedMotion;
        messages = getResources().getStringArray(R.array.console_loading_messages);
        setClickable(true);
        setFocusable(false);
        setBackgroundColor(Color.rgb(5, 6, 10));
        setLayerType(View.LAYER_TYPE_HARDWARE, null);

        addView(new Backdrop(context, animationEpoch), match());
        View shade = new View(context);
        shade.setBackgroundColor(0x57000000);
        addView(shade, match());

        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_HORIZONTAL);
        LayoutParams copyParams = new LayoutParams(dp(900),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        addView(copy, copyParams);

        TextView titleView = text(title == null || title.isEmpty() ? "MOONLIGHT" : title,
                38, Color.WHITE, true);
        titleView.setGravity(Gravity.CENTER);
        copy.addView(titleView, row());

        String firstMessage = initialMessage;
        if (firstMessage == null || firstMessage.isEmpty()) {
            lastMessageIndex = messages.length == 0 ? -1 : random.nextInt(messages.length);
            firstMessage = lastMessageIndex < 0
                    ? context.getString(R.string.console_stream_preparing)
                    : messages[lastMessageIndex];
        }
        messageView = text(firstMessage, 23, 0xFFE5E8F5, false);
        messageView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = row();
        messageParams.topMargin = dp(18);
        copy.addView(messageView, messageParams);

        statusView = text(context.getString(R.string.console_stream_initializing), 15,
                0xFFB8C0D9, false);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = row();
        statusParams.topMargin = dp(16);
        copy.addView(statusView, statusParams);

        progressView = text("", 13,
                0xFFB99CFF, true);
        progressView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams progressParams = row();
        progressParams.topMargin = dp(14);
        copy.addView(progressView, progressParams);

        setProgress(1);

        TextView hint = text(context.getString(R.string.console_stream_back_cancel),
                13, 0xBFFFFFFF, false);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = row();
        hintParams.topMargin = dp(30);
        copy.addView(hint, hintParams);

        if (!reducedMotion) handler.postDelayed(rotateMessage, MESSAGE_INTERVAL_MS);
    }

    public void setStage(String stage) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> setStage(stage));
            return;
        }
        if (stopped) return;
        String friendly = friendlyStage(stage);
        statusView.setText(friendly);
        setProgress(progressForStage(stage));
    }

    public void setStep(int step, String status) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> setStep(step, status));
            return;
        }
        if (stopped) return;
        statusView.setTextColor(0xFFB8C0D9);
        statusView.setText(status == null || status.isEmpty()
                ? getContext().getString(R.string.console_stream_preparing) : status);
        setProgress(step);
    }

    public String getCurrentMessage() {
        CharSequence value = messageView.getText();
        return value == null ? "" : value.toString();
    }

    public void showError(String title, String details) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> showError(title, details));
            return;
        }
        if (stopped) return;
        messageView.animate().cancel();
        messageView.setAlpha(1f);
        messageView.setText(title);
        statusView.setText(details);
        statusView.setTextColor(0xFFFF9B92);
    }

    public void waitingForVideo() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::waitingForVideo);
            return;
        }
        if (stopped) return;
        statusView.setText(getContext().getString(R.string.console_stream_waiting_video));
        setProgress(5);
    }

    public void revealStream() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::revealStream);
            return;
        }
        if (stopped || revealRequested) return;
        revealRequested = true;
        statusView.setText(getContext().getString(R.string.console_stream_ready));
        setProgress(5);
        postOnAnimation(() -> postOnAnimation(() -> postDelayed(() -> {
            if (stopped) return;
            stopAnimations();
            setVisibility(GONE);
        }, 650L)));
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

    private void setProgress(int completedStep) {
        int step = Math.max(1, Math.min(5, completedStep));
        StringBuilder value = new StringBuilder(getContext().getString(
                R.string.console_stream_step, step)).append("   ");
        for (int index = 1; index <= 5; index++) {
            if (index > 1) value.append("  —  ");
            value.append(index <= step ? "●" : "○");
        }
        progressView.setText(value.toString());
    }

    private static int progressForStage(String stage) {
        if (stage == null) return 1;
        String lower = stage.toLowerCase(Locale.ROOT);
        if (lower.contains("video") || lower.contains("audio")) return 4;
        if (lower.contains("control") || lower.contains("input")) return 3;
        if (lower.contains("rtsp") || lower.contains("handshake")) return 2;
        return 2;
    }

    private String friendlyStage(String stage) {
        if (stage == null || stage.isEmpty()) {
            return getContext().getString(R.string.console_stream_preparing);
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

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Backdrop extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float phase;

        Backdrop(Context context, long animationEpoch) {
            super(context);
            long epoch = animationEpoch > 0L ? animationEpoch : SystemClock.uptimeMillis();
            phase = (SystemClock.uptimeMillis() - epoch) / 9000f;
        }

        @Override protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            paint.setShader(new LinearGradient(0, 0, width, height,
                    new int[]{0xFF090B14, 0xFF171633, 0xFF311A58}, null,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);
            paint.setColor(0x347C4DFF);
            canvas.drawCircle(width * (.76f + .05f * (float) Math.sin(phase)),
                    height * (.20f + .05f * (float) Math.cos(phase)), height * .44f, paint);
            paint.setColor(0x2937B5FF);
            canvas.drawCircle(width * (.20f + .04f * (float) Math.cos(phase * .8f)),
                    height * (.84f + .04f * (float) Math.sin(phase * .8f)), height * .52f, paint);
        }
    }
}
