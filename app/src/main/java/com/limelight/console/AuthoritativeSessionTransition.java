package com.limelight.console;

final class AuthoritativeSessionTransition {
    private AuthoritativeSessionTransition() { }

    static boolean ended(Integer previousRunningAppId, int currentRunningAppId) {
        return previousRunningAppId != null
                && previousRunningAppId != 0
                && currentRunningAppId == 0;
    }
}
