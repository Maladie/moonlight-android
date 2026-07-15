package com.limelight.console;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.graphics.Color;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.hardware.lights.LightsManager;
import android.hardware.lights.LightsRequest;
import android.os.Build;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.InputDevice;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ControllerActions {
    private static final String TAG = "WakeAndPlay";
    private static final int HID_HOST_PROFILE = 4;

    interface ResultCallback {
        void onResult(boolean success, String message);
    }

    private ControllerActions() {}

    static boolean canIdentify(int deviceId) {
        InputDevice device = InputDevice.getDevice(deviceId);
        if (device == null) return false;
        try {
            Vibrator vibrator = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? device.getVibratorManager().getDefaultVibrator()
                    : device.getVibrator();
            if (vibrator != null && vibrator.hasVibrator()) return true;
        } catch (RuntimeException ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                for (Light light : device.getLightsManager().getLights()) {
                    if (light.hasBrightnessControl() || light.hasRgbControl()) return true;
                }
            } catch (RuntimeException ignored) {}
        }
        return false;
    }

    static boolean canDisconnect() {
        // A public per-device disconnect API was added after the Android version
        // used by current Sony TVs. Older HID Host methods require system privileges.
        return Build.VERSION.SDK_INT >= 37;
    }

    static void identify(int deviceId, Handler handler, ResultCallback callback) {
        InputDevice device = InputDevice.getDevice(deviceId);
        if (device == null) {
            callback.onResult(false, "The controller is no longer connected.");
            return;
        }
        boolean vibrationStarted = vibrate(device);
        boolean lightsStarted = flashLights(device, handler);
        Log.i(TAG, "Identify controller id=" + deviceId + " vibration=" + vibrationStarted
                + " lights=" + lightsStarted);
        if (vibrationStarted || lightsStarted) {
            callback.onResult(true, lightsStarted ? "The controller is flashing and vibrating." : "The controller is vibrating.");
        } else {
            callback.onResult(false, "This controller does not expose vibration or LED controls to Android.");
        }
    }

    private static boolean vibrate(InputDevice device) {
        try {
            Vibrator vibrator = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? device.getVibratorManager().getDefaultVibrator()
                    : device.getVibrator();
            if (vibrator == null || !vibrator.hasVibrator()) return false;
            long[] pattern = {0, 180, 120, 180, 120, 260};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(pattern, -1);
            }
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Controller vibration failed", error);
            return false;
        }
    }

    private static boolean flashLights(InputDevice device, Handler handler) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        try {
            LightsManager manager = device.getLightsManager();
            List<Light> controllable = new ArrayList<>();
            for (Light light : manager.getLights()) {
                if (light.hasBrightnessControl() || light.hasRgbControl()) controllable.add(light);
            }
            if (controllable.isEmpty()) return false;
            LightsManager.LightsSession session = manager.openSession();
            int[] colors = {Color.WHITE, 0xFF6750A4, Color.WHITE, 0xFF6750A4};
            for (int step = 0; step < colors.length; step++) {
                final int color = colors[step];
                handler.postDelayed(() -> {
                    try {
                        LightsRequest.Builder request = new LightsRequest.Builder();
                        for (Light light : controllable) {
                            request.addLight(light, new LightState.Builder().setColor(color).build());
                        }
                        session.requestLights(request.build());
                    } catch (RuntimeException error) {
                        Log.w(TAG, "Controller LED flash step failed", error);
                    }
                }, step * 220L);
            }
            handler.postDelayed(() -> {
                try { session.close(); } catch (RuntimeException ignored) {}
            }, colors.length * 220L + 100L);
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Controller LED control failed", error);
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    static void disconnect(Context context, int deviceId, ResultCallback callback) {
        InputDevice input = InputDevice.getDevice(deviceId);
        BluetoothDevice device = resolveBluetoothDevice(input);
        if (device == null) {
            callback.onResult(false, unresolvedMessage(input));
            return;
        }
        try {
            Method publicDisconnect = BluetoothDevice.class.getMethod("disconnect");
            Object value = publicDisconnect.invoke(device);
            boolean success = !(value instanceof Boolean) || (Boolean) value;
            callback.onResult(success, success ? "The controller was powered off." : "The system rejected the power-off request.");
            return;
        } catch (NoSuchMethodException ignored) {
            // Older Android versions expose disconnect only through the hidden HID Host profile.
        } catch (Exception error) {
            Log.w(TAG, "BluetoothDevice.disconnect failed", error);
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            callback.onResult(false, "Bluetooth is not available on this device.");
            return;
        }
        boolean requested = adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                boolean success = false;
                String detail = null;
                try {
                    Method disconnect = proxy.getClass().getMethod("disconnect", BluetoothDevice.class);
                    disconnect.setAccessible(true);
                    Object value = disconnect.invoke(proxy, device);
                    success = !(value instanceof Boolean) || (Boolean) value;
                } catch (Exception error) {
                    detail = rootMessage(error);
                    Log.w(TAG, "HID Host disconnect blocked", error);
                } finally {
                    adapter.closeProfileProxy(profile, proxy);
                }
                callback.onResult(success, success
                        ? "The controller was powered off."
                        : "Sony/Android blocked the controller power-off request." + suffix(detail));
            }
            @Override public void onServiceDisconnected(int profile) {
                callback.onResult(false, "The Bluetooth service disconnected.");
            }
        }, HID_HOST_PROFILE);
        if (!requested) callback.onResult(false, "Could not connect to the Bluetooth controller service.");
    }

    @SuppressLint("MissingPermission")
    static void unpair(int deviceId, ResultCallback callback) {
        InputDevice input = InputDevice.getDevice(deviceId);
        BluetoothDevice device = resolveBluetoothDevice(input);
        if (device == null) {
            callback.onResult(false, unresolvedMessage(input));
            return;
        }
        try {
            Method removeBond = BluetoothDevice.class.getMethod("removeBond");
            removeBond.setAccessible(true);
            Object value = removeBond.invoke(device);
            boolean success = !(value instanceof Boolean) || (Boolean) value;
            callback.onResult(success, success ? "Controller unpairing started." : "Sony/Android rejected the unpair request.");
        } catch (Exception error) {
            Log.w(TAG, "Bluetooth removeBond blocked", error);
            callback.onResult(false, "Sony/Android blocked controller unpairing." + suffix(rootMessage(error)));
        }
    }

    @SuppressLint("MissingPermission")
    private static BluetoothDevice resolveBluetoothDevice(InputDevice input) {
        if (input == null) return null;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return null;
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        String descriptor = input.getDescriptor();
        if (descriptor != null) {
            for (BluetoothDevice candidate : bonded) {
                String candidateDescriptor = descriptorForBluetoothDevice(input, candidate);
                if (descriptor.equals(candidateDescriptor)) {
                    Log.i(TAG, "Mapped input device " + input.getId() + " to Bluetooth by AOSP descriptor");
                    return candidate;
                }
            }
        }

        // Safe fallback only when exactly one paired device has the same name.
        BluetoothDevice match = null;
        for (BluetoothDevice candidate : bonded) {
            String candidateName = candidate.getName();
            if (candidateName != null && namesMatch(input.getName(), candidateName)) {
                if (match != null) return null;
                match = candidate;
            }
        }
        return match;
    }

    @SuppressLint("MissingPermission")
    private static String descriptorForBluetoothDevice(InputDevice input, BluetoothDevice device) {
        try {
            String address = device.getAddress();
            if (address == null || address.isEmpty()) return null;
            String raw = String.format(Locale.US, ":%04x:%04x:uniqueId:%s",
                    input.getVendorId(), input.getProductId(), address.toLowerCase(Locale.US));
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) value.append(String.format(Locale.US, "%02x", item & 0xff));
            return value.toString();
        } catch (Exception error) {
            Log.w(TAG, "Unable to derive AOSP input descriptor for Bluetooth device", error);
            return null;
        }
    }

    private static boolean namesMatch(String input, String bluetooth) {
        if (input == null || bluetooth == null) return false;
        String a = input.toLowerCase(Locale.ROOT);
        String b = bluetooth.toLowerCase(Locale.ROOT);
        return a.equals(b) || a.contains(b) || b.contains(a)
                || (a.contains("dualsense") && b.contains("wireless controller"));
    }

    private static String unresolvedMessage(InputDevice input) {
        return input == null ? "The controller is no longer connected."
                : "The selected gamepad could not be matched unambiguously to a Bluetooth device. No other controller was changed.";
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage();
    }

    private static String suffix(String detail) {
        return detail == null || detail.isEmpty() ? "" : "\n\n" + detail;
    }
}

