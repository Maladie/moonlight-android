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
import com.limelight.utils.SessionResumeManager;

/** Keeps the MoonWaker process alive while an established transport is parked. */
public final class BackgroundStreamService extends Service {
    public static final String ACTION_EXPIRED = "com.limelight.BACKGROUND_STREAM_EXPIRED";
    public static final String ACTION_END_REQUESTED = "com.limelight.BACKGROUND_STREAM_END_REQUESTED";
    public static final String EXTRA_STREAM_SESSION_ID = "stream_session_id";
    private static final String ACTION_PARK = "com.limelight.BACKGROUND_STREAM_PARK";
    private static final String ACTION_RESUME = "com.limelight.BACKGROUND_STREAM_RESUME";
    private static final String ACTION_END = "com.limelight.BACKGROUND_STREAM_END";
    private static final String ACTION_TRANSPORT_LOST = "com.limelight.BACKGROUND_STREAM_TRANSPORT_LOST";
    private static final String EXTRA_TIMEOUT_MINUTES = "timeout_minutes";
    private static final String CHANNEL_ID = "moonwaker_background_stream";
    private static final int NOTIFICATION_ID = 0x4D57;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BackgroundStreamCorrelation correlation = new BackgroundStreamCorrelation();
    private int timeoutMinutes = 5;
    private Runnable expiry;

    public static void park(Context context, String streamSessionId, int timeoutMinutes) {
        Intent intent = command(context, ACTION_PARK, streamSessionId)
                .putExtra(EXTRA_TIMEOUT_MINUTES, timeoutMinutes);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void resumed(Context context, String streamSessionId) {
        context.startService(command(context, ACTION_RESUME, streamSessionId));
    }

    public static void transportLost(Context context, String streamSessionId) {
        context.startService(command(context, ACTION_TRANSPORT_LOST, streamSessionId));
    }

    private static Intent command(Context context, String action, String streamSessionId) {
        return new Intent(context, BackgroundStreamService.class).setAction(action)
                .putExtra(EXTRA_STREAM_SESSION_ID, streamSessionId);
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
        SessionResumeManager.PendingSession pending =
                SessionResumeManager.pendingSession(this);
        String action = intent == null ? null : intent.getAction();
        String sessionId = intent == null ? null
                : intent.getStringExtra(EXTRA_STREAM_SESSION_ID);
        if (correlation.streamSessionId().isEmpty() && pending != null
                && (intent == null || pending.matches(sessionId))) {
            correlation.park(pending.streamSessionId);
        }
        if (intent == null) {
            if (pending == null) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            startParking(pending.streamSessionId, 5);
            return START_NOT_STICKY;
        }
        if (correlation.streamSessionId().isEmpty() && !ACTION_PARK.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_PARK.equals(action)) {
            if (pending == null || !pending.matches(sessionId)) return START_NOT_STICKY;
            startParking(sessionId, intent.getIntExtra(EXTRA_TIMEOUT_MINUTES, 5));
        } else if (ACTION_RESUME.equals(action)) {
            if (!correlation.resume(sessionId)) return START_NOT_STICKY;
            cancelExpiry();
            stopForeground(true);
            stopSelf();
        } else if (ACTION_END.equals(action)) {
            endIfCurrent(sessionId);
        } else if (ACTION_TRANSPORT_LOST.equals(action)) {
            if (!correlation.transportLost(sessionId)) return START_NOT_STICKY;
            startForeground(NOTIFICATION_ID, buildNotification(sessionId));
        }
        return START_NOT_STICKY;
    }

    private void startParking(String sessionId, int minutes) {
        correlation.park(sessionId);
        timeoutMinutes = minutes;
        startForeground(NOTIFICATION_ID, buildNotification(sessionId));
        cancelExpiry();
        if (timeoutMinutes > 0) {
            String capturedId = sessionId;
            expiry = () -> expireIfCurrent(capturedId);
            handler.postDelayed(expiry, timeoutMinutes * 60L * 1000L);
        }
    }

    private void expireIfCurrent(String sessionId) {
        if (!correlation.matches(sessionId)) return;
        if (!SessionResumeManager.clearIfMatches(this, sessionId)) {
            adoptLatestPending();
            return;
        }
        correlation.expire(sessionId);
        sendBroadcast(new Intent(ACTION_EXPIRED).setPackage(getPackageName())
                .putExtra(EXTRA_STREAM_SESSION_ID, sessionId));
        stopForeground(true);
        stopSelf();
    }

    private void endIfCurrent(String sessionId) {
        if (!correlation.matches(sessionId)) return;
        if (!SessionResumeManager.clearIfMatches(this, sessionId)) {
            adoptLatestPending();
            return;
        }
        correlation.end(sessionId);
        cancelExpiry();
        sendBroadcast(new Intent(ACTION_END_REQUESTED).setPackage(getPackageName())
                .putExtra(EXTRA_STREAM_SESSION_ID, sessionId));
        stopForeground(true);
        stopSelf();
    }

    private void adoptLatestPending() {
        SessionResumeManager.PendingSession latest =
                SessionResumeManager.pendingSession(this);
        if (latest == null) return;
        correlation.park(latest.streamSessionId);
        startForeground(NOTIFICATION_ID, buildNotification(latest.streamSessionId));
    }

    private Notification buildNotification(String sessionId) {
        SessionResumeManager.PendingSession pending =
                SessionResumeManager.pendingSession(this);
        Intent resume = pending != null && pending.matches(sessionId)
                ? SessionResumeManager.buildResumeIntent(this, pending) : null;
        if (resume == null) {
            resume = new Intent(this, Game.class)
                    .putExtra(Game.EXTRA_STREAM_SESSION_ID, sessionId);
        }
        resume.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setData(Uri.parse(BackgroundStreamCorrelation.intentIdentity(
                        "resume", sessionId)));
        PendingIntent resumeIntent = PendingIntent.getActivity(this, 0, resume,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent end = command(this, ACTION_END, sessionId)
                .setData(Uri.parse(BackgroundStreamCorrelation.intentIdentity(
                        "end", sessionId)));
        PendingIntent endIntent = PendingIntent.getService(this, 1, end,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_overlay_monitor)
                .setContentTitle(getString(R.string.background_stream_title))
                .setContentText(correlation.reconnectRequired()
                        ? getString(R.string.background_stream_reconnect_summary)
                        : timeoutMinutes == BackgroundStreamPreferences.NEVER
                        ? getString(R.string.background_stream_summary_never)
                        : getString(R.string.background_stream_summary_minutes, timeoutMinutes))
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
