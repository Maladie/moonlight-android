package com.limelight.console;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Debug-build lab hook: records a boundary sample of every published session projection.
 * Only callers holding android.permission.DUMP (the adb shell) can send the broadcast.
 */
public final class ProjectionSampleReceiver extends BroadcastReceiver {
    static final String ACTION = "com.limelight.debug.action.SAMPLE_SESSION_PROJECTION";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        setResultCode(SessionStateResolver.sampleAll(intent.getStringExtra("marker"),
                SystemClock.uptimeMillis()));
    }
}
