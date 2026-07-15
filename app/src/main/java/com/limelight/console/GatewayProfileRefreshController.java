package com.limelight.console;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Latest-only asynchronous profile refresh with lifecycle cancellation. */
final class GatewayProfileRefreshController {
    interface Loader {
        IntegrationProfileCatalog load(GatewayConnection connection) throws IOException;
    }

    interface Dispatcher {
        void dispatch(Runnable action);
    }

    interface Callback {
        void onLoaded(IntegrationProfileCatalog catalog);
        void onUnavailable();
    }

    private final Loader loader;
    private final Executor worker;
    private final Dispatcher main;
    private final Runnable shutdown;
    private int generation;
    private boolean destroyed;

    GatewayProfileRefreshController() {
        GatewayProfileAdapter adapter = new GatewayProfileAdapter();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        loader = adapter::load;
        worker = executor;
        main = action -> handler.post(action);
        shutdown = executor::shutdownNow;
    }

    GatewayProfileRefreshController(Loader loader, Executor worker,
                                    Dispatcher main, Runnable shutdown) {
        this.loader = loader;
        this.worker = worker;
        this.main = main;
        this.shutdown = shutdown;
    }

    synchronized int refresh(GatewayConnection connection, Callback callback) {
        if (destroyed) return -1;
        int request = ++generation;
        worker.execute(() -> {
            IntegrationProfileCatalog catalog = null;
            try {
                catalog = loader.load(connection);
            }
            catch (IOException | IllegalArgumentException unavailable) {
                // UI receives only an availability state, never credential-bearing errors.
            }
            IntegrationProfileCatalog result = catalog;
            main.dispatch(() -> deliver(request, result, callback));
        });
        return request;
    }

    synchronized void cancel() {
        generation++;
    }

    synchronized void destroy() {
        if (destroyed) return;
        destroyed = true;
        generation++;
        shutdown.run();
    }

    private synchronized void deliver(int request, IntegrationProfileCatalog catalog,
                                      Callback callback) {
        if (destroyed || request != generation) return;
        if (catalog != null) callback.onLoaded(catalog);
        else callback.onUnavailable();
    }
}
