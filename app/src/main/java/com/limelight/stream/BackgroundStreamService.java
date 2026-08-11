package com.limelight.stream;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.limelight.Game;
import com.limelight.R;

/** Keeps the MoonWaker process alive while an established transport is parked. */
public final class BackgroundStreamService extends Service {
    public static final String ACTION_EXPIRED = "com.limelight.BACKGROUND_STREAM_EXPIRED";
    private static final String ACTION_PARK = "com.limelight.BACKGROUND_STREAM_PARK";
    private static final String ACTION_RESUME = "com.limelight.BACKGROUND_STREAM_RESUME";
    private static final String EXTRA_TIMEOUT_MINUTES = "timeout_minutes";
    private static final String CHANNEL_ID = "moonwaker_background_stream";
    private static final int NOTIFICATION_ID = 0x4D57;
    private int timeoutMinutes = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable expire = () -> {
        Intent expired = new Intent(ACTION_EXPIRED).setPackage(getPackageName());
        sendBroadcast(expired);
        stopSelf();
    };

    public static void park(Context context, int timeoutMinutes) {
        Intent intent = new Intent(context, BackgroundStreamService.class).setAction(ACTION_PARK)
                .putExtra(EXTRA_TIMEOUT_MINUTES, timeoutMinutes);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void resumed(Context context) {
        context.startService(new Intent(context, BackgroundStreamService.class)
                .setAction(ACTION_RESUME));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.background_stream_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RESUME.equals(intent.getAction())) {
            handler.removeCallbacks(expire);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        timeoutMinutes = intent == null ? 5 : intent.getIntExtra(EXTRA_TIMEOUT_MINUTES, 5);
        startForeground(NOTIFICATION_ID, buildNotification());
        handler.removeCallbacks(expire);
        if (timeoutMinutes > 0) {
            handler.postDelayed(expire, timeoutMinutes * 60L * 1000L);
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        Intent resume = new Intent(this, Game.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, resume,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_overlay_monitor)
                .setContentTitle(getString(R.string.background_stream_title))
                .setContentText(timeoutMinutes == BackgroundStreamPreferences.NEVER
                        ? getString(R.string.background_stream_summary_never)
                        : getString(R.string.background_stream_summary_minutes, timeoutMinutes))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(expire);
        super.onDestroy();
    }
}
