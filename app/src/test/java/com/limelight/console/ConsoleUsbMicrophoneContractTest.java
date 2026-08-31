package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.limelight.R;
import com.limelight.binding.audio.UsbMicrophoneService;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        int discord = console.indexOf("global.discord");
        int microphone = console.indexOf("global.usb_microphone", discord);
        assertTrue(discord >= 0 && microphone > discord);
        assertTrue(console.contains("setContentDescription(getString("));
        assertTrue(console.contains("button.setTooltipText(description)"));
        assertTrue(console.contains("registerOnSharedPreferenceChangeListener("));
        assertTrue(console.contains("unregisterOnSharedPreferenceChangeListener("));
        assertTrue(console.contains("microphoneStateListener"));
        assertTrue(console.contains("showUsbMicrophonePanel"));
        assertTrue(console.contains("retryFromVisibleActivity(this)"));
        assertTrue(!game.contains("UsbMicrophone"));
    }

    private static String source(String relative) throws IOException {
        Path path = Paths.get(relative);
        if (!Files.exists(path)) path = Paths.get("app").resolve(relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
