package com.limelight.console;

/** Android-side view of the participant volume contract exposed by Host Gateway. */
final class DiscordFeatureContract {
    static final int MIN_PARTICIPANT_VOLUME = 0;
    static final int MAX_PARTICIPANT_VOLUME = 200;
    static final int PARTICIPANT_VOLUME_STEP = 10;
    static final int DEFAULT_PARTICIPANT_VOLUME = 100;
    static final long VOLUME_DEBOUNCE_MS = 220L;

    private DiscordFeatureContract() { }

    static int snapParticipantVolume(int value) {
        int clamped = Math.max(MIN_PARTICIPANT_VOLUME,
                Math.min(MAX_PARTICIPANT_VOLUME, value));
        return Math.round(clamped / (float) PARTICIPANT_VOLUME_STEP)
                * PARTICIPANT_VOLUME_STEP;
    }
}
