package com.limelight.stream;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.diagnostics.MoonWakerDiagnostics;
import com.limelight.session.SessionEvent;
import com.limelight.session.SessionModel;
import com.limelight.session.SessionResumeManager;

/**
 * mediaPlayback foreground service kept for the whole lifetime of one StreamRuntime generation.
 * It is started once while the app is foreground; park/resume only change its notification and
 * the retention timer. It never starts a foreground service from the background.
 */
public final class BackgroundStreamService extends Service {
    public static final String EXTRA_STREAM_SESSION_ID = "stream_session_id";
    private static final String EXTRA_GENERATION = "transport_generation";
    private static final String EXTRA_TIMEOUT_MINUTES = "timeout_minutes";
    private static final String ACTION_ESTABLISH = "com.limelight.BACKGROUND_STREAM_ESTABLISH";
    private static final String ACTION_PARK = "com.limelight.BACKGROUND_STREAM_PARK";
    private static final String ACTION_RESUME = "com.limelight.BACKGROUND_STREAM_RESUME";
    private static final String ACTION_END_RUNTIME = "com.limelight.BACKGROUND_STREAM_END_RUNTIME";
    private static final String ACTION_TRANSPORT_LOST = "com.limelight.BACKGROUND_STREAM_TRANSPORT_LOST";
    private static final String ACTION_END_REQUESTED = "com.limelight.BACKGROUND_STREAM_END_REQUESTED";
    private static final String ACTION_DISMISS = "com.limelight.BACKGROUND_STREAM_DISMISS";
    private static final String CHANNEL_ID = "moonwaker_background_stream";
    private static final int NOTIFICATION_ID = 0x4D57;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BackgroundStreamCorrelation correlation = new BackgroundStreamCorrelation();
    private int timeoutMinutes = BackgroundStreamPreferences.DEFAULT_RETENTION_MINUTES;
    private Runnable expiry;

    /** The single foreground start; the caller must be foreground. False if Android refused. */
    static boolean establish(Context context, String streamSessionId, long generation) {
        try {
            Intent intent = command(context, ACTION_ESTABLISH, streamSessionId, generation);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            return true;
        } catch (IllegalStateException | SecurityException refused) {
            // ForegroundServiceStartNotAllowedException is an IllegalStateException.
            MoonWakerDiagnostics.record("WARN", "android.background-stream",
                    "service.start_refused", "stream_session_id", streamSessionId,
                    "reason", refused.getClass().getSimpleName());
            return false;
        }
    }

    static void park(Context context, String streamSessionId, long generation, int minutes) {
        send(context, command(context, ACTION_PARK, streamSessionId, generation)
                .putExtra(EXTRA_TIMEOUT_MINUTES, minutes));
    }

    static void resume(Context context, String streamSessionId, long generation) {
        send(context, command(context, ACTION_RESUME, streamSessionId, generation));
    }

    static void end(Context context, String streamSessionId, long generation,
                    boolean transportLost) {
        send(context, command(context, transportLost ? ACTION_TRANSPORT_LOST
                : ACTION_END_RUNTIME, streamSessionId, generation));
    }

    /** Removes a lost-transport notification after its reconnect record was cleared. */
    public static void dismiss(Context context, String streamSessionId) {
        send(context, command(context, ACTION_DISMISS, streamSessionId, 0L));
    }

    private static void send(Context context, Intent intent) {
        try {
            context.startService(intent);
        } catch (IllegalStateException | SecurityException refused) {
            MoonWakerDiagnostics.record("WARN", "android.background-stream",
                    "service.command_refused", "action", intent.getAction(),
                    "reason", refused.getClass().getSimpleName());
        }
    }

    private static Intent command(Context context, String action, String streamSessionId,
                                  long generation) {
        return new Intent(context, BackgroundStreamService.class).setAction(action)
                .putExtra(EXTRA_STREAM_SESSION_ID, streamSessionId)
                .putExtra(EXTRA_GENERATION, generation);
    }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.background_stream_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        String sessionId = intent == null ? null
                : intent.getStringExtra(EXTRA_STREAM_SESSION_ID);
        long generation = intent == null ? 0L : intent.getLongExtra(EXTRA_GENERATION, 0L);
        if (ACTION_ESTABLISH.equals(action)) {
            // startForeground first: Android requires it for every startForegroundService.
            correlation.establish(sessionId, generation);
            cancelExpiry();
            startForeground(NOTIFICATION_ID, buildNotification());
            if (!StreamRuntime.serviceEstablished(sessionId, generation)) {
                MoonWakerDiagnostics.record("WARN", "android.background-stream",
                        "service.stale_establish", "stream_session_id", sessionId);
                stopService();
            }
        } else if (ACTION_PARK.equals(action)) {
            if (!correlation.park(sessionId, generation)) return START_NOT_STICKY;
            timeoutMinutes = intent.getIntExtra(EXTRA_TIMEOUT_MINUTES,
                    BackgroundStreamPreferences.readMinutes(this));
            armExpiry(sessionId, generation);
            notifyCurrent();
        } else if (ACTION_RESUME.equals(action)) {
            if (!correlation.resume(sessionId, generation)) return START_NOT_STICKY;
            cancelExpiry();
            notifyCurrent();
        } else if (ACTION_TRANSPORT_LOST.equals(action)) {
            if (!correlation.transportLost(sessionId, generation)) return START_NOT_STICKY;
            cancelExpiry();
            if (SessionModel.of(this).records().pending == null) {
                correlation.end(sessionId, generation);
                stopService();
            } else {
                notifyCurrent();
            }
        } else if (ACTION_END_RUNTIME.equals(action)) {
            if (correlation.end(sessionId, generation)) stopService();
        } else if (ACTION_END_REQUESTED.equals(action)) {
            if (!correlation.matches(sessionId, generation)) return START_NOT_STICKY;
            SessionModel.of(this).post(new SessionEvent.ReconnectCleared(sessionId));
            StreamRuntime.serviceEnded(sessionId, generation);
            correlation.end(sessionId, generation);
            stopService();
        } else if (ACTION_DISMISS.equals(action)) {
            if (StreamRuntime.forSession(sessionId) == null && correlation.dismiss(sessionId)) {
                stopService();
            }
        }
        if (correlation.streamSessionId().isEmpty()) stopSelf(startId);
        return START_NOT_STICKY;
    }

    private void armExpiry(String sessionId, long generation) {
        cancelExpiry();
        if (timeoutMinutes <= 0) return;
        expiry = () -> {
            if (!correlation.matches(sessionId, generation) || !correlation.parked()) return;
            MoonWakerDiagnostics.record("INFO", "android.background-stream",
                    "service.retention_expired", "stream_session_id", sessionId);
            SessionModel.of(this).post(new SessionEvent.ReconnectCleared(sessionId));
            StreamRuntime.serviceEnded(sessionId, generation);
            correlation.end(sessionId, generation);
            stopService();
        };
        handler.postDelayed(expiry, timeoutMinutes * 60L * 1000L);
    }

    private void stopService() {
        cancelExpiry();
        stopForeground(true);
        stopSelf();
    }

    private void notifyCurrent() {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        String sessionId = correlation.streamSessionId();
        long generation = correlation.generation();
        SessionResumeManager.PendingSession pending =
                SessionModel.of(this).records().pending;
        Intent resume = pending != null && pending.matches(sessionId)
                ? SessionResumeManager.buildResumeIntent(this, pending) : null;
        if (resume == null) {
            resume = new Intent(this, Game.class)
                    .putExtra(Game.EXTRA_STREAM_SESSION_ID, sessionId);
        }
        resume.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setData(Uri.parse(BackgroundStreamCorrelation.intentIdentity(
                        "resume", sessionId, generation)));
        PendingIntent resumeIntent = PendingIntent.getActivity(this, 0, resume,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent end = command(this, ACTION_END_REQUESTED, sessionId, generation)
                .setData(Uri.parse(BackgroundStreamCorrelation.intentIdentity(
                        "end", sessionId, generation)));
        PendingIntent endIntent = PendingIntent.getService(this, 1, end,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        String text = correlation.reconnectRequired()
                ? getString(R.string.background_stream_reconnect_summary)
                : !correlation.parked()
                ? getString(R.string.background_stream_active_summary)
                : timeoutMinutes == BackgroundStreamPreferences.NEVER
                ? getString(R.string.background_stream_summary_never)
                : getString(R.string.background_stream_summary_minutes, timeoutMinutes);
        return builder.setSmallIcon(R.drawable.ic_overlay_monitor)
                .setContentTitle(getString(R.string.background_stream_title))
                .setContentText(text)
                .setContentIntent(resumeIntent)
                .addAction(0, getString(R.string.background_stream_resume), resumeIntent)
                .addAction(0, getString(R.string.background_stream_end), endIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void cancelExpiry() {
        if (expiry != null) handler.removeCallbacks(expiry);
        expiry = null;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        cancelExpiry();
        super.onDestroy();
    }
}
