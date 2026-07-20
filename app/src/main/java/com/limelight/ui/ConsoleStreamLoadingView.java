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

    private final String[] messages = {
            "Loading content…",
            "Combobulating resources…",
            "Negotiating photons…",
            "Aligning virtual displays…",
            "Preparing controller uplink…",
            "Calibrating couch coordinates…",
            "Julification in progress…",
            "Pampering guinea pigs…",
            "Did you know the scientific name for a guinea pig is Cavia porcellus?",
            "Polishing pixels…",
            "Feeding the hamsters in the server room…",
            "Convincing the GPU to cooperate…",
            "Rolling for initiative…",
            "Untangling imaginary network cables…",
            "Teaching photons to take the shortest route…",
            "Asking packets to form an orderly queue…",
            "Warming up tiny digital dragons…",
            "Applying ceremonial RGB lighting…",
            "Almost ready…"
    };

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

        messageView = text(initialMessage == null || initialMessage.isEmpty()
                ? "Preparing your game…" : initialMessage, 23, 0xFFE5E8F5, false);
        messageView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = row();
        messageParams.topMargin = dp(18);
        copy.addView(messageView, messageParams);

        statusView = text("Initializing Moonlight streaming pipeline…", 15,
                0xFFB8C0D9, false);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = row();
        statusParams.topMargin = dp(16);
        copy.addView(statusView, statusParams);

        progressView = text("STEP 1 OF 5   ●  —  ○  —  ○  —  ○  —  ○", 13,
                0xFFB99CFF, true);
        progressView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams progressParams = row();
        progressParams.topMargin = dp(14);
        copy.addView(progressView, progressParams);

        TextView hint = text("Press BACK to cancel", 13, 0xBFFFFFFF, false);
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

    public void waitingForVideo() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::waitingForVideo);
            return;
        }
        if (stopped) return;
        statusView.setText("Waiting for the first video frame…");
        setProgress(5);
    }

    public void revealStream() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::revealStream);
            return;
        }
        if (stopped || revealRequested) return;
        revealRequested = true;
        statusView.setText("Stream ready");
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
        StringBuilder value = new StringBuilder("STEP ").append(step).append(" OF 5   ");
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

    private static String friendlyStage(String stage) {
        if (stage == null || stage.isEmpty()) return "Preparing stream…";
        String lower = stage.toLowerCase(Locale.ROOT);
        if (lower.contains("rtsp")) return "Starting RTSP handshake…";
        if (lower.contains("video")) return "Initializing video decoder…";
        if (lower.contains("audio")) return "Starting audio stream…";
        if (lower.contains("control")) return "Connecting controller uplink…";
        if (lower.contains("input")) return "Preparing input channel…";
        return "Starting " + stage + "…";
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
