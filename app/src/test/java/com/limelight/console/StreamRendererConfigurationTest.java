package com.limelight.console;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class StreamRendererConfigurationTest {
    @Test public void capturesRendererInputsWithoutAndroidViewOwnership() {
        PreferenceConfiguration preferences = new PreferenceConfiguration();
        StreamRendererConfiguration configuration = new StreamRendererConfiguration(
                preferences, 3, true, true, "Adreno");

        assertSame(preferences, configuration.preferences);
        assertEquals(3, configuration.consecutiveCrashCount);
        assertTrue(configuration.meteredData);
        assertTrue(configuration.requestedHdr);
        assertEquals("Adreno", configuration.glRenderer);
    }

    @Test public void normalizesInvalidDiagnosticInputs() {
        StreamRendererConfiguration configuration = new StreamRendererConfiguration(
                new PreferenceConfiguration(), -2, false, false, null);

        assertEquals(0, configuration.consecutiveCrashCount);
        assertFalse(configuration.meteredData);
        assertEquals("", configuration.glRenderer);
    }
}
