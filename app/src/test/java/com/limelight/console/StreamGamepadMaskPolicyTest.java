package com.limelight.console;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class StreamGamepadMaskPolicyTest {
    @Test public void preservesAttachedControllersInMultipleControllerMode() {
        assertEquals(0b1010, StreamGamepadMaskPolicy.evaluate(0b1010, true, false));
    }

    @Test public void advertisesPrimaryControllerWhenMultipleControllersAreDisabled() {
        assertEquals(1, StreamGamepadMaskPolicy.evaluate(0b1010, false, false));
        assertEquals(1, StreamGamepadMaskPolicy.evaluate(0, false, false));
    }

    @Test public void onscreenControllerAddsPrimaryController() {
        assertEquals(0b1011, StreamGamepadMaskPolicy.evaluate(0b1010, true, true));
    }

    @Test public void emptyMultipleControllerMaskRemainsEmptyWithoutOnscreenController() {
        assertEquals(0, StreamGamepadMaskPolicy.evaluate(0, true, false));
    }
}
