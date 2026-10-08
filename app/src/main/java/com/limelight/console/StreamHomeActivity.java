package com.limelight.console;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;

import com.limelight.nvstream.StreamConfiguration;
import com.limelight.session.SessionEvent;
import com.limelight.session.SessionModel;

/** Translucent dashboard placed over Game so its stream surface remains alive. */
public final class StreamHomeActivity extends ConsoleActivity {
    private boolean initialCarouselFramePending;
    private boolean preparingHomeFramePending;
    private boolean preparingHomeFrameAccepted;
    private boolean preparingRelayOwned;
    private boolean preparingHomeFrameSubmitting;
    private SessionModel.Snapshot preparingSnapshot;
    /** The presentation epoch while this Home was the visible presenter. */
    private long presentedEpoch;

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
            SessionModel.State current = SessionModel.of(this).state();
            if (current == SessionModel.State.NONE || current == SessionModel.State.PREPARING) {
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
    protected boolean isStreamHome() {
        return true;
    }

    /** No stream remains under this Home: the plain console home takes its place. */
    @Override
    protected void onRequestedStreamClosed() {
        if (isFinishing()) return;
        startActivity(new Intent(this, ConsoleActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SUPPRESS_AUTO_WARM_UP, true));
        finish();
        overridePendingTransition(android.R.anim.fade_in, 0);
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
        return SessionModel.of(this).isPreparingHomeFrameAccepted(
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
        return SessionModel.of(this).isPreparingSwitchOwned(
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
        SessionModel.Snapshot retained = SessionModel.of(this).snapshot();
        return !gameId.isEmpty()
                && (retained.state == SessionModel.State.PREPARING
                || retained.state == SessionModel.State.HOME_LIVE
                || retained.state == SessionModel.State.PARKED_LIVE)
                && retained.streamSessionId.equals(normalize(getIntent().getStringExtra(
                EXTRA_RETAINED_STREAM_SESSION_ID)))
                && retained.hostId.equalsIgnoreCase(normalize(getIntent().getStringExtra(
                EXTRA_RETAINED_STREAM_HOST_ID)))
                && retained.appId == getIntent().getIntExtra(
                EXTRA_RETAINED_STREAM_APP_ID, StreamConfiguration.INVALID_APP_ID)
                && retained.playniteGameId.equalsIgnoreCase(gameId);
    }

    private SessionModel.Snapshot exactPreparingSnapshot() {
        SessionModel.Snapshot current = SessionModel.of(this).snapshot();
        if (current.state != SessionModel.State.PREPARING) return null;
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

    private SessionModel.Snapshot exactRetainedSnapshot() {
        SessionModel.Snapshot current = SessionModel.of(this).snapshot();
        if (current.state != SessionModel.State.PREPARING
                && current.state != SessionModel.State.HOME_LIVE
                && current.state != SessionModel.State.PARKED_LIVE) {
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
        if (!initialCarouselFramePending || isFinishing() || isDestroyed()
                || preparingHomeFrameSubmitting) return;
        if (preparingHomeFramePending) {
            // The model answers on a later main-loop turn; input stays blocked until then.
            preparingHomeFrameSubmitting = true;
            SessionModel.of(this).dispatch(new SessionEvent.HomeFrameSubmitted(
                    preparingSnapshot), accepted -> {
                preparingHomeFrameSubmitting = false;
                if (!initialCarouselFramePending || isFinishing() || isDestroyed()) return;
                if (accepted) showInitialCarouselFrame();
                else finish();
            });
            return;
        }
        showInitialCarouselFrame();
    }

    private void showInitialCarouselFrame() {
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
            SessionModel.of(this).post(
                    new SessionEvent.CancelPreparingTransport(preparingSnapshot));
        }
        preparingHomeFramePending = false;
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        presentedEpoch = SessionModel.of(this).snapshot().presentationEpoch;
    }

    @Override
    public void onUserLeaveHint() {
        cancelUnsubmittedPreparingFrame();
        parkRetainedStream();
        super.onUserLeaveHint();
    }

    @Override
    protected void onStop() {
        cancelUnsubmittedPreparingFrame();
        if (!isFinishing() && !isChangingConfigurations()) parkRetainedStream();
        super.onStop();
    }

    private void parkRetainedStream() {
        SessionModel model = SessionModel.of(this);
        // Scoped to this Home: if the game showed the stream since, the request is stale.
        model.post(new SessionEvent.ParkRequested(model.snapshot().streamSessionId,
                presentedEpoch));
    }

    private void cancelUnsubmittedPreparingFrame() {
        if (!initialCarouselFramePending || isChangingConfigurations()) return;
        initialCarouselFramePending = false;
        if (preparingHomeFramePending) {
            SessionModel.of(this).post(
                    new SessionEvent.CancelPreparingTransport(preparingSnapshot));
        }
        preparingHomeFramePending = false;
        finish();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
