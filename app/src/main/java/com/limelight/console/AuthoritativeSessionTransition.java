package com.limelight.console;

final class AuthoritativeSessionTransition {
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
}
