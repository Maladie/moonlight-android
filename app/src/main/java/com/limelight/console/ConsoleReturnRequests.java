package com.limelight.console;

/**
 * What an Intent returning to the console asks of it: skip the next automatic warm-up and
 * open a host's hard-close confirmation. Taken from a fresh launch or a delivered Intent; a
 * recreation from saved state (configuration change or process death) keeps only its saved
 * pending suppression and never replays the Intent, whose extras the system re-delivers.
 */
final class ConsoleReturnRequests {
    private boolean suppressAutoWarmUp;
    /** The host whose hard-close confirmation opens at the next resume; "" when none. */
    private String hardTerminateHostId = "";

    void created(boolean restored, boolean savedSuppress, boolean suppressExtra,
                 String hardTerminateExtra) {
        if (restored) suppressAutoWarmUp = savedSuppress;
        else delivered(suppressExtra, hardTerminateExtra);
    }

    /** An Intent delivered to the running console (CLEAR_TOP | SINGLE_TOP). */
    void delivered(boolean suppressExtra, String hardTerminateExtra) {
        if (suppressExtra) suppressAutoWarmUp = true;
        if (hardTerminateExtra != null && !hardTerminateExtra.isEmpty()) {
            hardTerminateHostId = hardTerminateExtra;
        }
    }

    boolean suppressesAutoWarmUp() {
        return suppressAutoWarmUp;
    }

    boolean takeSuppressAutoWarmUp() {
        boolean taken = suppressAutoWarmUp;
        suppressAutoWarmUp = false;
        return taken;
    }

    String takeHardTerminateOffer() {
        String taken = hardTerminateHostId;
        hardTerminateHostId = "";
        return taken;
    }
}
