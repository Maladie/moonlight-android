package com.limelight.console;

/** Pure pre-decoder HDR eligibility based on user preference and display support. */
public final class StreamHdrDisplayPolicy {
    public enum Reason { ENABLED, DISABLED_BY_USER, ANDROID_VERSION, DISPLAY_UNSUPPORTED }

    public static final class Result {
        public final boolean enabled;
        public final Reason reason;

        private Result(boolean enabled, Reason reason) {
            this.enabled = enabled;
            this.reason = reason;
        }
    }

    private StreamHdrDisplayPolicy() { }

    public static Result evaluate(boolean requested,
                                  boolean hdrApisAvailable,
                                  int[] supportedHdrTypes,
                                  int hdr10Type) {
        if (!requested) {
            return new Result(false, Reason.DISABLED_BY_USER);
        }
        if (!hdrApisAvailable) {
            return new Result(false, Reason.ANDROID_VERSION);
        }
        if (supportedHdrTypes != null) {
            for (int type : supportedHdrTypes) {
                if (type == hdr10Type) {
                    return new Result(true, Reason.ENABLED);
                }
            }
        }
        return new Result(false, Reason.DISPLAY_UNSUPPORTED);
    }
}
