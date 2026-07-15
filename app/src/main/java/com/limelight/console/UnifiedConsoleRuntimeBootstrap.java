package com.limelight.console;

import android.app.Activity;

import com.limelight.LimeLog;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lazily assembles the unified runtime after ComputerManager becomes available. */
final class UnifiedConsoleRuntimeBootstrap implements ConsoleStreamRuntime, AutoCloseable {
    private final Activity activity;
    private final MoonlightConsoleResolvedStreamRuntime.SessionFactory sessionFactory;
    private final UnifiedConsoleStreamRuntimeAdapter.Listener listener;
    private final ConsoleComputerManagerConnection computerManagerConnection;
    private final ExecutorService resolutionWorker = Executors.newSingleThreadExecutor(
            command -> new Thread(command, "MoonWaker host resolution"));

    private UnifiedConsoleStreamRuntimeAdapter delegate;
    private ConsoleLaunchContract.Request pendingRequest;
    private boolean unavailable;
    private boolean closed;

    UnifiedConsoleRuntimeBootstrap(
            Activity activity,
            MoonlightConsoleResolvedStreamRuntime.SessionFactory sessionFactory,
            UnifiedConsoleStreamRuntimeAdapter.Listener listener) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.listener = Objects.requireNonNull(listener, "listener");
        computerManagerConnection = new ConsoleComputerManagerConnection(activity);
        computerManagerConnection.connect(new ConsoleComputerManagerConnection.Listener() {
            @Override public void onReady(ComputerManagerStreamLaunchLoader loader) {
                LimeLog.info("Unified Console computer service ready");
                assemble(loader);
            }

            @Override public void onUnavailable() {
                LimeLog.warning("Unified Console computer service unavailable");
                failUnavailable();
            }
        });
    }

    @Override public synchronized void launch(ConsoleLaunchContract.Request request) {
        if (closed) {
            listener.onFailure(UnifiedConsoleLaunchPipeline.Failure.runtime(
                    "Unified runtime is closed"));
            return;
        }
        if (unavailable) {
            listener.onStage(UnifiedConsoleLaunchPipeline.Stage.FAILED);
            listener.onFailure(UnifiedConsoleLaunchPipeline.Failure.resolution(
                    ConsoleStreamLaunchResolutionPolicy.Error.RESOLUTION_FAILED));
            return;
        }
        if (delegate != null) {
            LimeLog.info("Unified Console launch delegated immediately");
            delegate.launch(request);
        } else {
            LimeLog.info("Unified Console launch queued for computer service");
            pendingRequest = Objects.requireNonNull(request, "request");
        }
    }

    @Override public synchronized void returnToActiveStream() {
        if (delegate != null) delegate.returnToActiveStream();
    }

    synchronized void showHome() {
        if (delegate != null) delegate.showHome();
    }

    synchronized void cancelPendingLaunch() {
        pendingRequest = null;
        if (delegate != null) delegate.cancelPendingLaunch();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        pendingRequest = null;
        if (delegate != null) {
            delegate.close();
            delegate = null;
        }
        computerManagerConnection.close();
        resolutionWorker.shutdownNow();
    }

    private synchronized void assemble(ComputerManagerStreamLaunchLoader loader) {
        if (closed || delegate != null) return;
        ConsoleStreamLaunchResolutionController resolution =
                new ConsoleStreamLaunchResolutionController(
                        loader,
                        resolutionWorker,
                        command -> activity.runOnUiThread(command));
        MoonlightConsoleResolvedStreamRuntime resolvedRuntime =
                new MoonlightConsoleResolvedStreamRuntime(sessionFactory);
        delegate = new UnifiedConsoleStreamRuntimeAdapter(
                new UnifiedConsoleLaunchPipeline(resolution, resolvedRuntime),
                listener);
        LimeLog.info("Unified Console runtime assembled");
        ConsoleLaunchContract.Request request = pendingRequest;
        pendingRequest = null;
        if (request != null) {
            delegate.launch(request);
        }
    }

    private synchronized void failUnavailable() {
        if (closed) return;
        unavailable = true;
        boolean hadPendingRequest = pendingRequest != null;
        pendingRequest = null;
        if (!hadPendingRequest) return;
        listener.onStage(UnifiedConsoleLaunchPipeline.Stage.FAILED);
        listener.onFailure(UnifiedConsoleLaunchPipeline.Failure.resolution(
                ConsoleStreamLaunchResolutionPolicy.Error.RESOLUTION_FAILED));
    }
}
