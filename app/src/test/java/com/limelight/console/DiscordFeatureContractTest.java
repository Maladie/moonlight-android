package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DiscordFeatureContractTest {
    @Test public void volumeIsClampedToBackendRange() {
        assertEquals(0, DiscordFeatureContract.snapParticipantVolume(-50));
        assertEquals(200, DiscordFeatureContract.snapParticipantVolume(250));
    }

    @Test public void volumeUsesBackendStep() {
        assertEquals(0, DiscordFeatureContract.snapParticipantVolume(4));
        assertEquals(10, DiscordFeatureContract.snapParticipantVolume(6));
        assertEquals(100, DiscordFeatureContract.snapParticipantVolume(96));
    }

    @Test public void defaultAndPresetsAreSupported() {
        assertEquals(100, DiscordFeatureContract.DEFAULT_PARTICIPANT_VOLUME);
        assertEquals(50, DiscordFeatureContract.snapParticipantVolume(50));
        assertEquals(100, DiscordFeatureContract.snapParticipantVolume(100));
    }
}
