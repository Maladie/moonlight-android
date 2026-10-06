package com.limelight.console;

import java.util.Objects;

/**
 * The one fact "the user has passed the profile gate for this host". Gateway pollers
 * (session observation, Discord status, Discord audio) read their connection through
 * {@link HostGatewayStore#loadForPolling}, which refuses until a host is granted here;
 * before the PIN is verified the Gateway only answers them with 403.
 */
public final class ProfileAuthorization {
    private static volatile String grantedHost = "";
    private static volatile Runnable listener = () -> { };

    private ProfileAuthorization() { }

    /** Called after every change so pollers owned elsewhere can start or stop. */
    public static void onChange(Runnable changed) {
        listener = Objects.requireNonNull(changed);
    }

    /** The host was entered: the gate was passed (no PIN, or PIN verified). */
    public static void grant(String hostUuid) {
        set(hostUuid == null ? "" : hostUuid);
    }

    /** The profile gate (or PIN entry, or host selection) is showing again. */
    public static void revoke() {
        set("");
    }

    public static boolean allows(String hostUuid) {
        return hostUuid != null && !hostUuid.isEmpty() && hostUuid.equals(grantedHost);
    }

    private static void set(String hostUuid) {
        if (hostUuid.equals(grantedHost)) return;
        grantedHost = hostUuid;
        listener.run();
    }
}
