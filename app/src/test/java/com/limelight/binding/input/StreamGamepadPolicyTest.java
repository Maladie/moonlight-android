package com.limelight.binding.input;

import com.limelight.SourceText;
import android.view.InputDevice;

import com.limelight.binding.input.StreamGamepadPolicy.DeviceKind;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static com.limelight.SourceText.assertOrdered;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class StreamGamepadPolicyTest {
    private static final int IR_REMOTE = InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD; // 0x301
    private static final int PAD = InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_GAMEPAD
            | InputDevice.SOURCE_JOYSTICK;

    /** Synthetic devices: capabilities decide, never the name or the external flag. */
    @Test public void classifiesByCapabilityNotByName() {
        // DualSense (BT), Xbox (USB HID), and a pad the platform reports as internal.
        assertEquals(DeviceKind.REAL_PAD, StreamGamepadPolicy.classify(PAD, true));
        assertEquals(DeviceKind.REAL_PAD, StreamGamepadPolicy.classify(
                InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_DPAD, true));
        // BT remote advertising SOURCE_GAMEPAD without stick axes, and built-in mtkinp_ir_events.
        assertEquals(DeviceKind.REMOTE, StreamGamepadPolicy.classify(
                IR_REMOTE | InputDevice.SOURCE_GAMEPAD, false));
        assertEquals(DeviceKind.REMOTE, StreamGamepadPolicy.classify(IR_REMOTE, false));
        // Plain keyboard.
        assertEquals(DeviceKind.KEYBOARD, StreamGamepadPolicy.classify(InputDevice.SOURCE_KEYBOARD, false));
        // DualSense touchpad and motion sensors keep upstream handling.
        assertEquals(DeviceKind.OTHER, StreamGamepadPolicy.classify(
                InputDevice.SOURCE_TOUCHPAD | InputDevice.SOURCE_MOUSE, false));
        assertEquals(DeviceKind.OTHER, StreamGamepadPolicy.classify(InputDevice.SOURCE_SENSOR, false));
    }

    /** Pads announced at start stay active until a slot is reserved; the pool owns both. */
    @Test public void initialPadsShareThePoolsMask() {
        StreamGamepadPolicy policy = new StreamGamepadPolicy(16);
        policy.setInitialMask((short) 0b110);
        assertEquals(0b110, policy.activeMask());
        assertEquals(0, policy.reserve());
        assertEquals(0b111, policy.activeMask());
        assertEquals(1, policy.reserve());
        policy.release((short) 1);
        assertEquals(0b101, policy.activeMask());
    }

    /** An air-mouse remote (keys, d-pad and a pointer, no sticks) stays local next to a pad. */
    @Test public void airMouseRemoteIsARemoteNotAStreamPad() {
        int airMouse = InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD
                | InputDevice.SOURCE_MOUSE;
        assertEquals(DeviceKind.REMOTE, StreamGamepadPolicy.classify(airMouse, false));
        assertFalse(StreamGamepadPolicy.isStreamGamepad(
                StreamGamepadPolicy.classify(airMouse, false), true));
    }

    /** A fake device context: releasing sends neutral and returns its number to the pool. */
    private static final class FakeHolder implements StreamGamepadPolicy.Holder {
        final StreamGamepadPolicy policy;
        final DeviceKind kind;
        short number = -1;
        int neutralPackets;

        FakeHolder(StreamGamepadPolicy policy, DeviceKind kind) {
            this.policy = policy;
            this.kind = kind;
        }

        /** What ControllerHandler does before a packet: suppressed or assigned a number. */
        boolean send() {
            if (!policy.isStreamGamepad(kind)) return false;
            if (number < 0) number = policy.reserve();
            return true;
        }

        @Override public DeviceKind kind() { return kind; }
        @Override public boolean assigned() { return number >= 0; }
        @Override public void releaseNeutral() {
            neutralPackets++;
            policy.release(number);
            number = -1;
        }
    }

    @Test public void realPadTakesSlotZeroFromTheRemoteWithANeutralRelease() {
        StreamGamepadPolicy policy = new StreamGamepadPolicy(16);
        FakeHolder remote = new FakeHolder(policy, DeviceKind.REMOTE);
        FakeHolder pad = new FakeHolder(policy, DeviceKind.REAL_PAD);
        policy.updateRealPadPresence(false, Arrays.asList(remote));
        assertTrue(remote.send());
        assertEquals(0, remote.number);

        policy.updateRealPadPresence(true, Arrays.asList(remote, pad));
        assertEquals(1, remote.neutralPackets);
        assertFalse(remote.assigned());
        assertTrue(pad.send());
        assertEquals(0, pad.number);

        // The pad leaves: the remote drives the stream again.
        policy.release(pad.number);
        policy.updateRealPadPresence(false, Arrays.asList(remote));
        assertTrue(remote.send());
        assertEquals(0, remote.number);
    }

    @Test public void remotePacketIsSuppressedWhileARealPadIsPresent() {
        StreamGamepadPolicy policy = new StreamGamepadPolicy(16);
        FakeHolder remote = new FakeHolder(policy, DeviceKind.REMOTE);
        FakeHolder keyboard = new FakeHolder(policy, DeviceKind.KEYBOARD);
        policy.updateRealPadPresence(true, Arrays.asList(remote, keyboard));
        assertFalse(remote.send());
        assertFalse(keyboard.send());
        assertEquals(0, policy.activeMask());
        assertEquals(0, remote.neutralPackets);
    }

    @Test public void remoteAndKeyboardAreStreamPadsOnlyWithoutARealPad() {
        for (DeviceKind kind : new DeviceKind[]{DeviceKind.REMOTE, DeviceKind.KEYBOARD}) {
            assertTrue(StreamGamepadPolicy.isStreamGamepad(kind, false));
            assertFalse(StreamGamepadPolicy.isStreamGamepad(kind, true));
        }
        for (DeviceKind kind : new DeviceKind[]{DeviceKind.REAL_PAD, DeviceKind.OTHER}) {
            assertTrue(StreamGamepadPolicy.isStreamGamepad(kind, false));
            assertTrue(StreamGamepadPolicy.isStreamGamepad(kind, true));
        }
    }

    @Test public void remoteHoldingANumberReleasesItWhenARealPadConnects() {
        assertTrue(StreamGamepadPolicy.mustRelease(DeviceKind.REMOTE, true, true));
        assertTrue(StreamGamepadPolicy.mustRelease(DeviceKind.KEYBOARD, true, true));
        assertFalse(StreamGamepadPolicy.mustRelease(DeviceKind.REMOTE, false, true));
        assertFalse(StreamGamepadPolicy.mustRelease(DeviceKind.REMOTE, true, false));
        assertFalse(StreamGamepadPolicy.mustRelease(DeviceKind.REAL_PAD, true, true));
    }

    @Test public void remoteNeverSharesControllerZeroWithThePad() {
        // Log 16:56: DualSense took 0 first; the remote must get its own slot.
        StreamGamepadPolicy policy = new StreamGamepadPolicy(16);
        short pad = policy.reserve();
        short remote = policy.reserve();
        assertEquals(0, pad);
        assertNotEquals(pad, remote);

        // Remote-only play holds 0; a pad connecting gets 0 only after the remote releases it.
        policy = new StreamGamepadPolicy(16);
        short remoteFirst = policy.reserve();
        assertEquals(0, remoteFirst);
        policy.release(remoteFirst);
        assertEquals(0, policy.reserve());
        assertEquals(1, policy.activeMask());
    }

    @Test public void controllerHandlerHasNoHardcodedSharedNumber() throws IOException {
        Path path = Paths.get("src/main/java/com/limelight/binding/input/ControllerHandler.java");
        String source = SourceText.read(path);
        assertFalse(source.contains("hardcoded as controller 0"));
        assertFalse(source.contains("currentControllers"));
        String send = source.substring(source.indexOf("private void sendControllerInputPacket("));
        assertOrdered(send, "isStreamGamepad(originalContext)",
                "assignControllerNumberIfNeeded(originalContext)");
    }
}
