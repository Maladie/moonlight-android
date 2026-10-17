package com.limelight.console;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.limelight.diagnostics.MoonWakerDiagnostics;
import com.limelight.session.SessionEvent;
import com.limelight.session.SessionModel;

/**
 * Debug-build lab hook: records a boundary sample of every session projection the session
 * model holds, then drains the diagnostic writer and records the post-drain loss count under
 * the same marker. Only callers holding android.permission.DUMP (the adb shell) can send it.
 */
public final class ProjectionSampleReceiver extends BroadcastReceiver {
    static final String ACTION = "com.limelight.debug.action.SAMPLE_SESSION_PROJECTION";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        String marker = intent.getStringExtra("marker");
        SessionModel model = SessionModel.of(context);
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                int queued = model.await(new SessionEvent.ProjectionsSampled(marker));
                boolean confirmed = queued >= 0 && SessionStateResolver.confirmSamples(
                        MoonWakerDiagnostics::record, marker);
                pending.setResultCode(confirmed ? queued : -1);
            } finally {
                pending.finish();
            }
        }, "ProjectionSampleDrain").start();
    }
}