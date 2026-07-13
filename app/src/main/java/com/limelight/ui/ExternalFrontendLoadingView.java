package com.limelight.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
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
            messageView.animate().alpha(0f).setDuration(160).withEndAction(() -> {
                if (stopped) return;
                int next;
                do {
                    next = random.nextInt(messages.length);
                } while (messages.length > 1 && next == lastMessageIndex);
                lastMessageIndex = next;
                messageView.setText(messages[next]);
                messageView.animate().alpha(1f).setDuration(240).start();
            }).start();
            handler.postDelayed(this, MESSAGE_INTERVAL_MS);
        }
    };

    public ExternalFrontendLoadingView(Context context, String title) {
        super(context);
        setClickable(true);
        setFocusable(false);
        setBackgroundColor(Color.rgb(5, 6, 10));

        addView(new AnimatedBackdrop(context), match());
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

        rotateMessage.run();
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

    public void revealStream() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::revealStream);
            return;
        }
        if (stopped) return;
        stopAnimations();
        animate().alpha(0f).setDuration(220).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setVisibility(GONE);
            }
        }).start();
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
        messageView.animate().cancel();
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
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
        private final long startedAt = SystemClock.uptimeMillis();

        AnimatedBackdrop(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            float phase = (SystemClock.uptimeMillis() - startedAt) / 9000f;

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
            postInvalidateDelayed(32);
        }
    }
}
