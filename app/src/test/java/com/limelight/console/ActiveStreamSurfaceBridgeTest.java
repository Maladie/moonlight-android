package com.limelight.console;

import android.view.SurfaceHolder;

import org.junit.Test;

import java.lang.reflect.Proxy;

import static org.junit.Assert.*;

public class ActiveStreamSurfaceBridgeTest {
    @Test public void explicitHandoffBindsConsoleBeforeItIsForeground() {
        ActiveStreamSurfaceBridge.Coordinator coordinator = new ActiveStreamSurfaceBridge.Coordinator();
        FakeSession session = new FakeSession();
        SurfaceHolder surface = surface();
        coordinator.registerConsoleSurface(surface);
        coordinator.attachSession(session);

        assertTrue(coordinator.prepareConsoleHandoff(session));
        assertSame(surface, session.lastTarget);
        assertTrue(coordinator.isConsoleRenderTargetBound(session));
    }

    @Test public void foregroundConsoleBindsWhenSurfaceAndSessionAreReady() {
        ActiveStreamSurfaceBridge.Coordinator coordinator = new ActiveStreamSurfaceBridge.Coordinator();
        FakeSession session = new FakeSession();
        coordinator.attachSession(session);
        coordinator.registerConsoleSurface(surface());
        assertEquals(0, session.targetSwitches);

        coordinator.setConsoleForeground(true);

        assertEquals(1, session.targetSwitches);
        assertTrue(coordinator.isConsoleRenderTargetBound(session));
    }

    @Test public void destroyingBoundConsoleSurfaceMovesDecoderToBackground() {
        ActiveStreamSurfaceBridge.Coordinator coordinator = new ActiveStreamSurfaceBridge.Coordinator();
        FakeSession session = new FakeSession();
        SurfaceHolder surface = surface();
        coordinator.attachSession(session);
        coordinator.registerConsoleSurface(surface);
        assertTrue(coordinator.prepareConsoleHandoff(session));

        assertTrue(coordinator.releaseConsoleSurface(surface));
        assertEquals(1, session.backgroundSwitches);
        assertFalse(coordinator.isConsoleRenderTargetBound(session));
    }

    @Test public void bindingGameSurfaceClearsConsoleOwnership() {
        ActiveStreamSurfaceBridge.Coordinator coordinator = new ActiveStreamSurfaceBridge.Coordinator();
        FakeSession session = new FakeSession();
        coordinator.attachSession(session);
        coordinator.registerConsoleSurface(surface());
        assertTrue(coordinator.prepareConsoleHandoff(session));

        coordinator.onGameRenderTargetBound(session);

        assertFalse(coordinator.isConsoleRenderTargetBound(session));
    }

    @Test(expected = IllegalStateException.class)
    public void secondSessionCannotReplaceActiveOwner() {
        ActiveStreamSurfaceBridge.Coordinator coordinator = new ActiveStreamSurfaceBridge.Coordinator();
        coordinator.attachSession(new FakeSession());
        coordinator.attachSession(new FakeSession());
    }

    private static SurfaceHolder surface() {
        return (SurfaceHolder) Proxy.newProxyInstance(
                ActiveStreamSurfaceBridgeTest.class.getClassLoader(),
                new Class<?>[] { SurfaceHolder.class },
                (proxy, method, args) -> null);
    }

    private static final class FakeSession implements StreamRenderTargetController {
        SurfaceHolder lastTarget;
        int targetSwitches;
        int backgroundSwitches;

        @Override public void setInitialRenderTarget(SurfaceHolder renderTarget) {
            lastTarget = renderTarget;
        }

        @Override public boolean switchToRenderTarget(SurfaceHolder renderTarget) {
            lastTarget = renderTarget;
            targetSwitches++;
            return true;
        }

        @Override public boolean switchToBackgroundSurface() {
            backgroundSwitches++;
            return true;
        }

        @Override public void prepareRendererForStop() { }
    }
}
