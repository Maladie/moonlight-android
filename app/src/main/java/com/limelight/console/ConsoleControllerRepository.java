package com.limelight.console;

import android.hardware.BatteryState;
import android.os.Build;
import android.view.InputDevice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Read-only controller inventory matching Wake Home without taking input ownership. */
final class ConsoleControllerRepository {
    static final class Controller {
        final String name;
        final int batteryPercentage;
        final boolean charging;

        Controller(String name, int batteryPercentage, boolean charging) {
            this.name = compactName(name);
            this.batteryPercentage = batteryPercentage;
            this.charging = charging;
        }

        String batteryLabel() {
            if (batteryPercentage < 0) return "Battery unavailable";
            return (charging ? "Charging · " : "Battery · ") + batteryPercentage + "%";
        }
    }

    List<Controller> load() {
        List<InputDevice> devices = new ArrayList<>();
        Set<String> descriptors = new HashSet<>();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || !isGamepad(device) || isVirtual(device.getName())) continue;
            String descriptor = device.getDescriptor();
            if (descriptor != null && !descriptors.add(descriptor)) continue;
            devices.add(device);
        }
        Collections.sort(devices,
                (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        List<Controller> controllers = new ArrayList<>();
        for (InputDevice device : devices) {
            int percentage = -1;
            boolean charging = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BatteryState battery = device.getBatteryState();
                if (battery.isPresent()) {
                    if (!Float.isNaN(battery.getCapacity())) {
                        percentage = Math.round(battery.getCapacity() * 100f);
                    }
                    charging = battery.getStatus() == BatteryState.STATUS_CHARGING;
                }
            }
            controllers.add(new Controller(device.getName(), percentage, charging));
        }
        return controllers;
    }

    static String compactName(String name) {
        if (name == null || name.trim().isEmpty()) return "Controller";
        return name.toLowerCase(Locale.ROOT).contains("dualsense") ? "DualSense" : name.trim();
    }

    static boolean isGamepad(InputDevice device) {
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private static boolean isVirtual(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).startsWith("virtual-");
    }
}
