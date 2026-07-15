package com.limelight.console;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.limelight.computers.ComputerManagerService;

import java.util.Objects;

/** Lifecycle-safe, lazy binding to Moonlight's saved-computer service. */
final class ConsoleComputerManagerConnection implements AutoCloseable {
    interface Listener {
        void onReady(ComputerManagerStreamLaunchLoader loader);
        void onUnavailable();
    }

    private final Context context;
    private Listener listener;
    private boolean bound;
    private boolean closed;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            notifyReady(service);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            notifyUnavailable();
        }

        @Override public void onNullBinding(ComponentName name) {
            notifyUnavailable();
        }

        @Override public void onBindingDied(ComponentName name) {
            notifyUnavailable();
        }
    };

    ConsoleComputerManagerConnection(Context context) {
        this.context = Objects.requireNonNull(context, "context")
                .getApplicationContext();
    }

    synchronized void connect(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        if (closed) {
            listener.onUnavailable();
            return;
        }
        this.listener = listener;
        if (bound) {
            return;
        }
        bound = context.bindService(
                new Intent(context, ComputerManagerService.class),
                serviceConnection,
                Context.BIND_AUTO_CREATE);
        if (!bound) {
            notifyUnavailable();
        }
    }

    @Override public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        listener = null;
        if (bound) {
            context.unbindService(serviceConnection);
            bound = false;
        }
    }

    private synchronized void notifyUnavailable() {
        if (!closed && listener != null) {
            listener.onUnavailable();
        }
    }

    private synchronized void notifyReady(IBinder service) {
        if (closed || listener == null || !(service instanceof
                ComputerManagerService.ComputerManagerBinder)) {
            notifyUnavailable();
            return;
        }
        listener.onReady(new ComputerManagerStreamLaunchLoader(
                (ComputerManagerService.ComputerManagerBinder) service));
    }
}
