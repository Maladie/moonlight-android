package com.limelight.binding.audio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class UsbMicrophoneServiceContractTest {
    @Test public void repeatedStartKeepsExistingCapture() {
        assertTrue(UsbMicrophoneService.shouldReloadOnStart(false, false, false));
        assertFalse(UsbMicrophoneService.shouldReloadOnStart(true, false, false));
        assertFalse(UsbMicrophoneService.shouldReloadOnStart(false, true, false));
        assertFalse(UsbMicrophoneService.shouldReloadOnStart(false, false, true));
    }

    @Test public void transportFailuresBackOffSeparatelyFromLocalAttention() {
        assertTrue(UsbMicrophoneService.isRetryableState("error"));
        assertTrue(UsbMicrophoneService.isRetryableState("unavailable"));
        assertFalse(UsbMicrophoneService.isRetryableState("usb_missing"));
        assertFalse(UsbMicrophoneService.isRetryableState("usb_unavailable"));
        assertFalse(UsbMicrophoneService.isRetryableState("usb_removed"));
        assertFalse(UsbMicrophoneService.isRetryableState("permission_revoked"));
        assertTrue(UsbMicrophoneService.nextRetryDelay(1_000) == 2_000);
        assertTrue(UsbMicrophoneService.nextRetryDelay(16_000) == 30_000);
        assertTrue(UsbMicrophoneService.nextRetryDelay(30_000) == 30_000);
    }

    @Test public void usbCountPolicyWaitsUntilExactlyOneInput() {
        assertEquals(UsbMicrophoneService.STATE_WAITING_USB,
                UsbMicrophoneService.usbWaitStateForCount(0));
        assertNull(UsbMicrophoneService.usbWaitStateForCount(1));
        assertEquals(UsbMicrophoneService.STATE_USB_MULTIPLE,
                UsbMicrophoneService.usbWaitStateForCount(2));
    }

    @Test public void gatewayChangesAreScopedToSelectedHost() {
        assertTrue(UsbMicrophoneService.isGatewayKeyForSelectedHost(
                "host-a", "host-a.paired"));
        assertFalse(UsbMicrophoneService.isGatewayKeyForSelectedHost(
                "host-a", "host-b.paired"));
        assertFalse(UsbMicrophoneService.isGatewayKeyForSelectedHost(
                "host-a", "host-a.discord.default.last_channel_id"));
        assertFalse(UsbMicrophoneService.isGatewayKeyForSelectedHost(
                "host-a", "host-a.playnite_library_filter"));
        assertFalse(UsbMicrophoneService.isGatewayKeyForSelectedHost("", "host-a.paired"));
    }

    @Test public void foregroundServiceOwnsCaptureWithoutGameLifecycle() throws IOException {
        String service = source("src/main/java/com/limelight/binding/audio/UsbMicrophoneService.java");
        String application = source("src/main/java/com/limelight/MoonWakerApplication.java");
        String game = source("src/main/java/com/limelight/Game.java");
        String manifest = source("src/main/AndroidManifest.xml");
        String permission = source(
                "src/main/java/com/limelight/binding/audio/UsbMicrophonePermissionActivity.java");

        assertTrue(service.contains("new UsbMicrophoneCapture(this, gateway"));
        assertTrue(service.contains("getString(SELECTED_HOST"));
        assertTrue(service.contains("loadForHost(host, null)"));
        assertTrue(service.contains("usb_microphone_notification_reconnecting"));
        assertTrue(service.contains("onAudioDevicesAdded"));
        assertTrue(service.contains("onAudioDevicesRemoved"));
        assertTrue(service.indexOf("startAsForeground(R.string.usb_microphone_notification_connecting)")
                < service.indexOf("reloadCapture();"));
        assertFalse(service.contains("surfaceCreated"));
        assertFalse(service.contains("streamEverRevealed"));
        assertFalse(game.contains("UsbMicrophone"));

        assertTrue(application.contains("ActivityLifecycleCallbacks"));
        assertTrue(application.contains("OnSharedPreferenceChangeListener"));
        assertTrue(application.contains("startFromVisibleActivity(activity)"));
        assertTrue(application.contains("UsbMicrophonePermissionActivity.request(activity)"));
        assertTrue(application.contains("activity instanceof UsbMicrophonePermissionActivity"));
        assertTrue(application.contains("isGatewayKeyForSelectedHost("));
        assertTrue(service.contains("setSmallIcon(R.drawable.ic_overlay_microphone)"));
        assertTrue(permission.contains("onRequestPermissionsResult"));
        assertTrue(permission.contains("STATE_PERMISSION_REQUIRED"));
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"));
        assertTrue(manifest.contains("android.permission.POST_NOTIFICATIONS"));
        assertTrue(manifest.contains("android:name=\".binding.audio.UsbMicrophoneService\""));
        assertTrue(manifest.contains("android:foregroundServiceType=\"microphone\""));
        assertTrue(manifest.contains("android:exported=\"false\""));
    }

    private static String source(String relative) throws IOException {
        Path path = Paths.get(relative);
        if (!Files.exists(path)) path = Paths.get("app").resolve(relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
