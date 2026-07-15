package com.limelight.console;

import org.junit.Test;
import static org.junit.Assert.*;

public class InputRouterTest {
    @Test public void consoleRegionsReleaseGameplayCapture() {
        InputRouter router = new InputRouter(InputRouter.Region.GAMEPLAY);
        assertTrue(router.routeTo(InputRouter.Region.HOME));
        assertFalse(router.isGameplayCaptured());
        assertFalse(router.routeTo(InputRouter.Region.OVERLAY));
        assertEquals(InputRouter.Region.OVERLAY, router.region());
    }

    @Test public void returningToGameplayChangesCaptureOnce() {
        InputRouter router = new InputRouter(InputRouter.Region.HOME);
        assertTrue(router.routeTo(InputRouter.Region.GAMEPLAY));
        assertFalse(router.routeTo(InputRouter.Region.GAMEPLAY));
        assertTrue(router.isGameplayCaptured());
    }
}
