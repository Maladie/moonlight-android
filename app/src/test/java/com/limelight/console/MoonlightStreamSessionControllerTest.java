package com.limelight.console;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class MoonlightStreamSessionControllerTest {
    @Test public void connectionCannotStartBeforeTransportInitialization() {
        MoonlightStreamSessionController controller = new MoonlightStreamSessionController(
                Runnable::run, Runnable::run, () -> { });
        try {
            controller.connect();
            fail("uninitialized transport must not start");
        } catch (IllegalStateException expected) {
            assertEquals(StreamSessionController.SessionState.IDLE, controller.state());
        }
    }

    @Test public void connectionCanStartExactlyOnce() {
        FakeTransport transport = new FakeTransport();
        MoonlightStreamSessionController controller = controller(transport, Runnable::run);
        controller.connect();
        assertEquals(1, transport.starts);
        assertEquals(StreamSessionController.SessionState.CONNECTING, controller.state());
        controller.onConnectionStarted();
        assertEquals(StreamSessionController.SessionState.CONNECTED, controller.state());
        try {
            controller.connect();
            fail("second transport start must be rejected");
        } catch (IllegalStateException expected) {
            assertEquals(1, transport.starts);
        }
    }

    @Test public void stopRunsOffCallerAndCompletionReturnsThroughCallbackExecutor() {
        FakeTransport transport = new FakeTransport();
        QueueExecutor stop = new QueueExecutor();
        QueueExecutor callbacks = new QueueExecutor();
        MoonlightStreamSessionController controller = new MoonlightStreamSessionController(
                transport, stop, callbacks, () -> { });
        controller.connect();
        AtomicInteger completions = new AtomicInteger();
        controller.disconnectTransport(completions::incrementAndGet);
        assertEquals(StreamSessionController.SessionState.DISCONNECTING, controller.state());
        assertEquals(0, transport.stops);
        stop.runNext();
        assertEquals(1, transport.stops);
        assertEquals(StreamSessionController.SessionState.IDLE, controller.state());
        assertEquals(0, completions.get());
        callbacks.runNext();
        assertEquals(1, completions.get());
    }

    @Test public void repeatedDisconnectNeverStopsTransportTwice() {
        FakeTransport transport = new FakeTransport();
        QueueExecutor stop = new QueueExecutor();
        MoonlightStreamSessionController controller = controller(transport, stop);
        controller.connect();
        controller.disconnectTransport();
        controller.disconnectTransport();
        assertEquals(1, stop.size());
        stop.runNext();
        assertEquals(1, transport.stops);
    }

    @Test public void quitHostIsSeparateFromTransportStop() {
        FakeTransport transport = new FakeTransport();
        AtomicInteger quits = new AtomicInteger();
        MoonlightStreamSessionController controller = new MoonlightStreamSessionController(
                transport, Runnable::run, Runnable::run, quits::incrementAndGet);
        controller.quitHostApplication();
        assertEquals(1, quits.get());
        assertEquals(0, transport.stops);
    }

    private static MoonlightStreamSessionController controller(FakeTransport transport, Executor stop) {
        return new MoonlightStreamSessionController(transport, stop, Runnable::run, () -> { });
    }

    private static final class FakeTransport implements MoonlightStreamSessionController.Transport {
        int starts;
        int stops;
        @Override public void start() { starts++; }
        @Override public void stop() { stops++; }
    }

    private static final class QueueExecutor implements Executor {
        private final Queue<Runnable> queue = new ArrayDeque<>();
        @Override public void execute(Runnable command) { queue.add(command); }
        void runNext() { queue.remove().run(); }
        int size() { return queue.size(); }
    }
}
