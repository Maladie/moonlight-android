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

import java.util.Random;

/**
 * Opaque loading surface used when Moonlight is launched by an external TV
 * frontend. It deliberately resembles Wake & Play so the hand-off remains
 * visually seamless while Moonlight owns the SurfaceView and initializes the
 * streaming pipeline.
 */
public final class ExternalFrontendLoadingView extends FrameLayout {
    private static final long MESSAGE_INTERVAL_MS = 3400;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final TextView titleView;
    private final TextView messageView;
    private final TextView statusView;
    private int lastMessageIndex = -1;
    private boolean stopped;
    private boolean revealRequested;
    private final boolean reducedMotion;

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
            "Convincing the GPU to cooperate…",
            "Teaching photons to take the shortest route…",
            "Warming up tiny digital dragons…",
            "Applying ceremonial RGB lighting…",
            "Almost ready…"
    };

    private final Runnable rotateMessage = new Runnable() {
        @Override
        public void run() {
            if (stopped || getVisibility() != VISIBLE) return;
            int next;
            do {
                next = random.nextInt(messages.length);
            } while (messages.length > 1 && next == lastMessageIndex);
            lastMessageIndex = next;
            messageView.setText(messages[next]);
            handler.postDelayed(this, MESSAGE_INTERVAL_MS);
        }
    };

    public ExternalFrontendLoadingView(Context context, String title, long animationEpoch,
                                       boolean reducedMotion) {
        super(context);
        this.reducedMotion = reducedMotion;
        setClickable(true);
        setFocusable(false);
        setBackgroundColor(Color.rgb(5, 6, 10));
        // Compose the complete loader as one opaque layer. The Sony/MediaTek TV
        // compositor can otherwise mix partial View damage with the SurfaceView
        // beneath it while the decoder is starting.
        setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // Preserve the hand-off phase from Wake & Play but keep this side static.
        // Continuous invalidation over a live SurfaceView produces torn/black
        // loader frames on some Android TV compositors.
        addView(new AnimatedBackdrop(context, animationEpoch, false), match());
        View shade = new View(context);
        shade.setBackgroundColor(0x57000000);
        addView(shade, match());

        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_HORIZONTAL);
        LayoutParams copyParams = new LayoutParams(dp(850), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        addView(copy, copyParams);

        titleView = text(title == null || title.isEmpty() ? "MOONLIGHT" : title, 38, Color.WHITE, true);
        titleView.setGravity(Gravity.CENTER);
        copy.addView(titleView, row());

        messageView = text("", 23, 0xFFE5E8F5, false);
        messageView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = row();
        messageParams.topMargin = dp(18);
        copy.addView(messageView, messageParams);

        statusView = text("Preparing Moonlight…", 14, 0xFFB8C0D9, false);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = row();
        statusParams.topMargin = dp(14);
        copy.addView(statusView, statusParams);

        TextView cancelHint = text("Press BACK to return to Wake & Play", 14, 0xBFFFFFFF, false);
        cancelHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = row();
        hintParams.topMargin = dp(34);
        copy.addView(cancelHint, hintParams);

        messageView.setText("Preparing your game...");
        if (!reducedMotion) handler.postDelayed(rotateMessage, MESSAGE_INTERVAL_MS);
    }

    public void setLoadingTitle(String title) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> setLoadingTitle(title));
            return;
        }
        if (!stopped && title != null && !title.isEmpty()) titleView.setText(title);
    }

    public void setStatus(String status) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> setStatus(status));
            return;
        }
        if (!stopped && status != null && !status.isEmpty()) statusView.setText(status);
    }

    public void setMessage(String message) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> setMessage(message));
            return;
        }
        if (!stopped && message != null && !message.isEmpty()) messageView.setText(message);
    }

    public void revealStream() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::revealStream);
            return;
        }
        if (stopped || revealRequested) return;
        revealRequested = true;
        // Let the Surface commit more than one composed frame before removing the
        // opaque hand-off layer. This avoids revealing a transient black or
        // not-yet-scaled Surface frame on Android TV compositors.
        postOnAnimation(() -> postOnAnimation(() -> postDelayed(() -> {
            if (stopped) return;
            stopAnimations();
            // A full-layer alpha fade exposes the decoder Surface while Android
            // is still reallocating its buffers, which appears as a black flash.
            // Switch atomically after multiple composed video frames instead.
            setVisibility(GONE);
        // The first decoder callback can arrive while the TV compositor still
        // presents an old-size Surface buffer. Keep the opaque, already-final
        // fullscreen geometry for several additional frames so the first
        // visible stream frame is presented at its stable size.
        }, 650L)));
    }

    public void stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::stop);
            return;
        }
        stopAnimations();
        animate().cancel();
    }

    private void stopAnimations() {
        if (stopped) return;
        stopped = true;
        handler.removeCallbacksAndMessages(null);
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return view;
    }

    private LayoutParams match() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams row() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class AnimatedBackdrop extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final long startedAt;
        private final boolean animate;

        AnimatedBackdrop(Context context, long animationEpoch, boolean animate) {
            super(context);
            this.startedAt = animationEpoch > 0L ? animationEpoch : SystemClock.uptimeMillis();
            this.animate = animate;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            float phase = animate ? (SystemClock.uptimeMillis() - startedAt) / 9000f : 0f;

            paint.setShader(new LinearGradient(0, 0, width, height,
                    new int[]{0xFF090B14, 0xFF171633, 0xFF311A58}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);

            paint.setColor(0x347C4DFF);
            canvas.drawCircle(width * (.76f + .05f * (float)Math.sin(phase)),
                    height * (.20f + .05f * (float)Math.cos(phase)), height * .44f, paint);
            paint.setColor(0x2937B5FF);
            canvas.drawCircle(width * (.20f + .04f * (float)Math.cos(phase * .8f)),
                    height * (.84f + .04f * (float)Math.sin(phase * .8f)), height * .52f, paint);
            if (animate) postInvalidateDelayed(32);
        }
    }
}
