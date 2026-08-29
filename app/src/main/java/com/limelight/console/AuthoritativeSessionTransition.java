package com.limelight.console;

import com.limelight.stream.RetainedStreamSessionCoordinator;

final class AuthoritativeSessionTransition {
    enum SunshineStopAction { COMPLETE, QUIT, FAILED }

    private AuthoritativeSessionTransition() { }

    static String endedSessionId(Integer previousRunningAppId, int currentRunningAppId,
                                 String previousStreamSessionId) {
        if (!ended(previousRunningAppId, currentRunningAppId)) return "";
        return previousStreamSessionId == null ? "" : previousStreamSessionId.trim();
    }

    static boolean ended(Integer previousRunningAppId, int currentRunningAppId) {
        return previousRunningAppId != null
                && previousRunningAppId != 0
                && currentRunningAppId == 0;
    }

    static boolean lostRetainedTransport(boolean hostOnline, int currentRunningAppId,
                                         boolean sameHost,
                                         RetainedStreamSessionCoordinator.State retainedState) {
        return hostOnline && currentRunningAppId == 0 && sameHost
                && retainedState == RetainedStreamSessionCoordinator.State.RECONNECT_REQUIRED;
    }

    static SunshineStopAction sunshineStopAction(int capturedRunningAppId,
                                                  Integer freshRunningAppId) {
        if (capturedRunningAppId == 0) return SunshineStopAction.COMPLETE;
        if (freshRunningAppId == null) return SunshineStopAction.FAILED;
        if (freshRunningAppId == 0) return SunshineStopAction.COMPLETE;
        return freshRunningAppId == capturedRunningAppId
                ? SunshineStopAction.QUIT : SunshineStopAction.FAILED;
    }
}
