package com.limelight.console;

import com.limelight.stream.RetainedStreamSessionCoordinator;

/** Translucent dashboard placed over Game so its stream surface remains alive. */
public final class StreamHomeActivity extends ConsoleActivity {
    @Override
    public void onUserLeaveHint() {
        RetainedStreamSessionCoordinator.parkForBackground(
                RetainedStreamSessionCoordinator.snapshot().streamSessionId);
        super.onUserLeaveHint();
    }

    @Override
    protected void onStop() {
        if (!isFinishing() && !isChangingConfigurations()) {
            RetainedStreamSessionCoordinator.parkForBackground(
                RetainedStreamSessionCoordinator.snapshot().streamSessionId);
        }
        super.onStop();
    }
}
