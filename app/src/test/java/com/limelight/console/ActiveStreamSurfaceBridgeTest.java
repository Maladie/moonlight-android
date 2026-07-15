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
        ActiveStreamSurfaceBridge.Snapshot snapshot = coordinator.snapshot();
        assertEquals(1, snapshot.generation);
        assertEquals(ActiveStreamSurfaceBridge.Target.CONSOLE, snapshot.target);
        assertEquals(1, snapshot.successfulTargetChanges);
        assertEquals(0, snapshot.failedTargetChanges);
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

    @Test public void foregroundStateTracksFocusLossAndRecovery() {
        ActiveStreamSurfaceBridge.Coordinator coordinator = new ActiveStreamSurfaceBridge.Coordinator();
        coordinator.setConsoleForeground(true);
        assertTrue(coordinator.snapshot().consoleForeground);
        coordinator.setConsoleForeground(false);
        assertFalse(coordinator.snapshot().consoleForeground);
        coordinator.setConsoleForeground(true);
        assertTrue(coordinator.snapshot().consoleForeground);
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
        assertEquals(ActiveStreamSurfaceBridge.Target.BACKGROUND, coordinator.snapshot().target);
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

    @Test public void failedConsoleSwitchIsVisibleInDiagnosticSnapshot() {
        ActiveStreamSurfaceBridge.Coordinator coordinator = new ActiveStreamSurfaceBridge.Coordinator();
        FakeSession session = new FakeSession();
        session.allowTargetSwitch = false;
        coordinator.attachSession(session);
        coordinator.registerConsoleSurface(surface());

        assertFalse(coordinator.prepareConsoleHandoff(session));
        ActiveStreamSurfaceBridge.Snapshot snapshot = coordinator.snapshot();
        assertEquals(ActiveStreamSurfaceBridge.Target.NONE, snapshot.target);
        assertEquals(0, snapshot.successfulTargetChanges);
        assertEquals(1, snapshot.failedTargetChanges);
        assertFalse(snapshot.diagnosticLine().contains("host"));
    }

    @Test public void decoderStartupStagesInitialTargetBeforeMediaCodecExists() {
        ActiveStreamSurfaceBridge.Coordinator coordinator =
                new ActiveStreamSurfaceBridge.Coordinator();
        FakeSession session = new FakeSession();
        session.renderTargetReady = false;
        coordinator.setConsoleForeground(true);
        coordinator.registerConsoleSurface(surface());
        coordinator.attachSession(session);

        assertTrue(coordinator.bindConsoleIfForeground(session));
        assertEquals(0, session.targetSwitches);
        assertNotNull(session.lastTarget);
        assertEquals(0, coordinator.snapshot().failedTargetChanges);
        assertEquals(ActiveStreamSurfaceBridge.Target.CONSOLE,
                coordinator.snapshot().target);
    }

    @Test public void generationAdvancesOnlyForANewSessionOwner() {
        ActiveStreamSurfaceBridge.Coordinator coordinator = new ActiveStreamSurfaceBridge.Coordinator();
        FakeSession first = new FakeSession();
        coordinator.attachSession(first);
        coordinator.attachSession(first);
        assertEquals(1, coordinator.snapshot().generation);

        coordinator.detachSession(first);
        coordinator.attachSession(new FakeSession());

        assertEquals(2, coordinator.snapshot().generation);
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
        boolean allowTargetSwitch = true;
        boolean renderTargetReady = true;

        @Override public boolean isRenderTargetSwitchReady() {
            return renderTargetReady;
        }

        @Override public void setInitialRenderTarget(SurfaceHolder renderTarget) {
            lastTarget = renderTarget;
        }

        @Override public boolean switchToRenderTarget(SurfaceHolder renderTarget) {
            lastTarget = renderTarget;
            targetSwitches++;
            return allowTargetSwitch;
        }

        @Override public boolean switchToBackgroundSurface() {
            backgroundSwitches++;
            return true;
        }

        @Override public void prepareRendererForStop() { }
    }
}
