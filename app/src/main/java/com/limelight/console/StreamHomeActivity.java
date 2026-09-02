package com.limelight.console;

import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;

import com.limelight.nvstream.StreamConfiguration;
import com.limelight.stream.RetainedStreamSessionCoordinator;

/** Translucent dashboard placed over Game so its stream surface remains alive. */
public final class StreamHomeActivity extends ConsoleActivity {
    private boolean initialCarouselFramePending;
    private boolean preparingHomeFramePending;
    private boolean preparingHomeFrameAccepted;
    private boolean preparingRelayOwned;
    private RetainedStreamSessionCoordinator.Snapshot preparingSnapshot;

    @Override
    protected void onCreate(Bundle state) {
        preparingHomeFrameAccepted = hasAcceptedPreparingHomeFrame();
        preparingRelayOwned = hasOwnedPreparingRelay() || hasCompletedPreparingRelay();
        preparingSnapshot = exactPreparingSnapshot();
        preparingHomeFramePending = shouldAwaitPreparingFrame(
                preparingSnapshot != null, preparingHomeFrameAccepted,
                preparingRelayOwned);
        initialCarouselFramePending = exactRetainedSnapshot() != null;
        super.onCreate(state);
        long attempt = getIntent().getLongExtra(EXTRA_WARM_UP_ATTEMPT, 0L);
        if (attempt > 0L && (preparingHomeFrameAccepted || preparingRelayOwned)) {
            initialCarouselFramePending = false;
            preparingHomeFramePending = false;
            completeInitialCarouselFrame();
            acceptPreparingHomeFrame();
            return;
        }

        if (attempt > 0L && preparingSnapshot == null) {
            RetainedStreamSessionCoordinator.State current =
                    RetainedStreamSessionCoordinator.state();
            if (current == RetainedStreamSessionCoordinator.State.NONE
                    || current == RetainedStreamSessionCoordinator.State.PREPARING) {
                initialCarouselFramePending = false;
                finish();
                return;
            }
        }
        if (!initialCarouselFramePending) return;
        View content = findViewById(android.R.id.content);
        Runnable submitted = this::onInitialCarouselFrameSubmitted;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            content.getViewTreeObserver().registerFrameCommitCallback(submitted);
        }
        content.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        if (initialCarouselFramePending
                                && (!hasResolvedInitialHostSelection()
                                || !prepareInitialCarouselFrame())) return false;
                        if (content.getViewTreeObserver().isAlive()) {
                            content.getViewTreeObserver().removeOnPreDrawListener(this);
                        }
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            content.postOnAnimation(
                                    () -> content.postOnAnimation(submitted));
                        }
                        return true;
                    }
                });
    }

    @Override
    protected boolean useTransparentWarmUpStartingWindow() {
        return preparingHomeFramePending;
    }

    @Override
    protected boolean requiresPreparedInitialCarouselFrame() {
        return initialCarouselFramePending;
    }

    static boolean shouldAwaitPreparingFrame(boolean exactPreparing,
                                             boolean frameAccepted,
                                             boolean relayOwned) {
        return exactPreparing && !frameAccepted && !relayOwned;
    }

    private boolean hasAcceptedPreparingHomeFrame() {
        return RetainedStreamSessionCoordinator.isPreparingHomeFrameAccepted(
                normalize(getIntent().getStringExtra(EXTRA_RETAINED_STREAM_SESSION_ID)),
                normalize(getIntent().getStringExtra(EXTRA_RETAINED_STREAM_HOST_ID)),
                getIntent().getIntExtra(EXTRA_RETAINED_STREAM_APP_ID,
                        StreamConfiguration.INVALID_APP_ID),
                normalize(getIntent().getStringExtra(
                        EXTRA_RETAINED_STREAM_PLAYNITE_GAME_ID)),
                normalize(getIntent().getStringExtra(EXTRA_WARM_UP_TRANSITION_ID)),
                getIntent().getLongExtra(EXTRA_WARM_UP_ATTEMPT, 0L));
    }

    private boolean hasOwnedPreparingRelay() {
        return RetainedStreamSessionCoordinator.isPreparingSwitchOwned(
                normalize(getIntent().getStringExtra(EXTRA_RETAINED_STREAM_SESSION_ID)),
                normalize(getIntent().getStringExtra(EXTRA_RETAINED_STREAM_HOST_ID)),
                getIntent().getIntExtra(EXTRA_RETAINED_STREAM_APP_ID,
                        StreamConfiguration.INVALID_APP_ID),
                normalize(getIntent().getStringExtra(EXTRA_WARM_UP_TRANSITION_ID)),
                getIntent().getLongExtra(EXTRA_WARM_UP_ATTEMPT, 0L));
    }

    private boolean hasCompletedPreparingRelay() {
        String gameId = normalize(getIntent().getStringExtra(
                EXTRA_WARM_UP_PENDING_GAME_ID));
        RetainedStreamSessionCoordinator.Snapshot retained =
                RetainedStreamSessionCoordinator.snapshot();
        return !gameId.isEmpty()
                && (retained.state == RetainedStreamSessionCoordinator.State.PREPARING
                || retained.state == RetainedStreamSessionCoordinator.State.HOME_LIVE
                || retained.state == RetainedStreamSessionCoordinator.State.PARKED_LIVE)
                && retained.streamSessionId.equals(normalize(getIntent().getStringExtra(
                EXTRA_RETAINED_STREAM_SESSION_ID)))
                && retained.hostId.equalsIgnoreCase(normalize(getIntent().getStringExtra(
                EXTRA_RETAINED_STREAM_HOST_ID)))
                && retained.appId == getIntent().getIntExtra(
                EXTRA_RETAINED_STREAM_APP_ID, StreamConfiguration.INVALID_APP_ID)
                && retained.playniteGameId.equalsIgnoreCase(gameId);
    }

    private RetainedStreamSessionCoordinator.Snapshot exactPreparingSnapshot() {
        RetainedStreamSessionCoordinator.Snapshot current =
                RetainedStreamSessionCoordinator.snapshot();
        if (current.state != RetainedStreamSessionCoordinator.State.PREPARING) return null;
        long attempt = getIntent().getLongExtra(EXTRA_WARM_UP_ATTEMPT, 0L);
        String sessionId = normalize(getIntent().getStringExtra(
                EXTRA_RETAINED_STREAM_SESSION_ID));
        String hostId = normalize(getIntent().getStringExtra(
                EXTRA_RETAINED_STREAM_HOST_ID));
        String gameId = normalize(getIntent().getStringExtra(
                EXTRA_RETAINED_STREAM_PLAYNITE_GAME_ID));
        String transitionId = normalize(getIntent().getStringExtra(
                EXTRA_WARM_UP_TRANSITION_ID));
        int appId = getIntent().getIntExtra(
                EXTRA_RETAINED_STREAM_APP_ID, StreamConfiguration.INVALID_APP_ID);
        if (!sessionId.equals(current.streamSessionId)
                || !hostId.equalsIgnoreCase(current.hostId)
                || appId != current.appId
                || !gameId.equalsIgnoreCase(current.playniteGameId)
                || !transitionId.equals(current.transitionId)
                || attempt != current.attempt) {
            return null;
        }
        return current;
    }

    private RetainedStreamSessionCoordinator.Snapshot exactRetainedSnapshot() {
        RetainedStreamSessionCoordinator.Snapshot current =
                RetainedStreamSessionCoordinator.snapshot();
        if (current.state != RetainedStreamSessionCoordinator.State.PREPARING
                && current.state != RetainedStreamSessionCoordinator.State.HOME_LIVE
                && current.state != RetainedStreamSessionCoordinator.State.PARKED_LIVE) {
            return null;
        }
        String sessionId = normalize(getIntent().getStringExtra(
                EXTRA_RETAINED_STREAM_SESSION_ID));
        String hostId = normalize(getIntent().getStringExtra(
                EXTRA_RETAINED_STREAM_HOST_ID));
        String gameId = normalize(getIntent().getStringExtra(
                EXTRA_RETAINED_STREAM_PLAYNITE_GAME_ID));
        int appId = getIntent().getIntExtra(
                EXTRA_RETAINED_STREAM_APP_ID, StreamConfiguration.INVALID_APP_ID);
        return sessionId.equals(current.streamSessionId)
                && hostId.equalsIgnoreCase(current.hostId)
                && appId == current.appId
                && gameId.equalsIgnoreCase(current.playniteGameId) ? current : null;
    }

    private void onInitialCarouselFrameSubmitted() {
        if (!initialCarouselFramePending || isFinishing() || isDestroyed()) return;
        if (preparingHomeFramePending
                && !RetainedStreamSessionCoordinator.preparingHomeFrameSubmitted(
                preparingSnapshot)) {
            finish();
            return;
        }
        initialCarouselFramePending = false;
        preparingHomeFramePending = false;
        completeInitialCarouselFrame();
        if (preparingSnapshot != null) acceptPreparingHomeFrame();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!initialCarouselFramePending) return super.dispatchKeyEvent(event);
        if (event != null && event.getAction() == KeyEvent.ACTION_UP
                && (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B)) {
            onBackPressed();
        }
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return initialCarouselFramePending || super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return initialCarouselFramePending || super.dispatchGenericMotionEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (!initialCarouselFramePending) {
            super.onBackPressed();
            return;
        }
        initialCarouselFramePending = false;
        if (preparingHomeFramePending) {
            RetainedStreamSessionCoordinator.cancelPreparing(preparingSnapshot);
        }
        preparingHomeFramePending = false;
        finish();
    }

    @Override
    public void onUserLeaveHint() {
        cancelUnsubmittedPreparingFrame();
        RetainedStreamSessionCoordinator.parkForBackground(
                RetainedStreamSessionCoordinator.snapshot().streamSessionId);
        super.onUserLeaveHint();
    }

    @Override
    protected void onStop() {
        cancelUnsubmittedPreparingFrame();
        if (!isFinishing() && !isChangingConfigurations()) {
            RetainedStreamSessionCoordinator.parkForBackground(
                    RetainedStreamSessionCoordinator.snapshot().streamSessionId);
        }
        super.onStop();
    }

    private void cancelUnsubmittedPreparingFrame() {
        if (!initialCarouselFramePending || isChangingConfigurations()) return;
        initialCarouselFramePending = false;
        if (preparingHomeFramePending) {
            RetainedStreamSessionCoordinator.cancelPreparing(preparingSnapshot);
        }
        preparingHomeFramePending = false;
        finish();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
