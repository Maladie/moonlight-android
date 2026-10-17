package com.limelight.console;

import com.limelight.SourceText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.limelight.R;
import com.limelight.binding.audio.UsbMicrophoneService;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ConsoleUsbMicrophoneContractTest {
    @Test public void toolbarMapsPersistentMicrophoneState() {
        assertEquals(R.string.console_usb_microphone_active,
                ConsoleActivity.microphoneStateTextResource(
                        UsbMicrophoneService.STATE_ACTIVE));
        assertEquals(R.string.console_usb_microphone_reconnecting,
                ConsoleActivity.microphoneStateTextResource(
                        UsbMicrophoneService.STATE_GATEWAY_RECONNECTING));
        assertEquals(R.string.console_usb_microphone_multiple,
                ConsoleActivity.microphoneStateTextResource(
                        UsbMicrophoneService.STATE_USB_MULTIPLE));
        assertEquals(R.string.console_usb_microphone_off,
                ConsoleActivity.microphoneStateTextResource("unknown"));
        assertEquals(R.string.console_usb_microphone_unsupported,
                ConsoleActivity.microphoneStateTextResource(
                        UsbMicrophoneService.STATE_UNSUPPORTED));
        assertTrue(ConsoleActivity.shouldShowMicrophoneRetry(
                UsbMicrophoneService.STATE_GATEWAY_RECONNECTING));
        assertTrue(ConsoleActivity.shouldShowMicrophoneRetry(
                UsbMicrophoneService.STATE_USB_UNAVAILABLE));
        assertTrue(!ConsoleActivity.shouldShowMicrophoneRetry(
                UsbMicrophoneService.STATE_ACTIVE));
        assertTrue(!ConsoleActivity.shouldShowMicrophoneRetry(
                UsbMicrophoneService.STATE_WAITING_USB));
        assertTrue(!ConsoleActivity.shouldShowMicrophoneRetry(
                UsbMicrophoneService.STATE_USB_MULTIPLE));
    }

    @Test public void toolbarIsAccessibleAndIndependentOfGame() throws IOException {
        String console = source("src/main/java/com/limelight/console/ConsoleActivity.java");
        String game = source("src/main/java/com/limelight/Game.java");
        String bar = source("src/main/java/com/limelight/console/ConsoleTopBar.java");
        int discord = bar.indexOf("global.discord");
        int microphone = bar.indexOf("global.usb_microphone", discord);
        assertTrue(discord >= 0 && microphone > discord);
        assertTrue(console.contains("R.string.console_usb_microphone_description"));
        assertTrue(bar.contains("microphone.setContentDescription(description)"));
        assertTrue(console.contains("registerOnSharedPreferenceChangeListener("));
        assertTrue(console.contains("unregisterOnSharedPreferenceChangeListener("));
        assertTrue(console.contains("microphoneStateListener"));
        assertTrue(console.contains("showUsbMicrophonePanel"));
        assertTrue(console.contains("retryFromVisibleActivity(this)"));
        assertTrue(!game.contains("UsbMicrophone"));
    }

    private static String source(String relative) throws IOException {
        Path path = Paths.get(relative);
        return SourceText.read(path);
    }
}
