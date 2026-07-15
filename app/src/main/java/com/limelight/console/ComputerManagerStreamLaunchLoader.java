package com.limelight.console;

import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;

import java.util.Objects;

/** Adapts Moonlight's saved-computer service to Console launch resolution. */
final class ComputerManagerStreamLaunchLoader implements
        ConsoleStreamLaunchResolutionController.Loader {
    interface Source {
        void awaitReady();
        ComputerDetails findComputer(String uuid);
        String clientUniqueId();
    }

    private static final class BinderSource implements Source {
        private final ComputerManagerService.ComputerManagerBinder binder;

        BinderSource(ComputerManagerService.ComputerManagerBinder binder) {
            this.binder = Objects.requireNonNull(binder, "binder");
        }

        @Override public void awaitReady() {
            binder.waitForReady();
        }

        @Override public ComputerDetails findComputer(String uuid) {
            return binder.getComputer(uuid);
        }

        @Override public String clientUniqueId() {
            return binder.getUniqueId();
        }
    }

    private final Source source;

    ComputerManagerStreamLaunchLoader(
            ComputerManagerService.ComputerManagerBinder binder) {
        this(new BinderSource(binder));
    }

    ComputerManagerStreamLaunchLoader(Source source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override public ConsoleStreamLaunchResolutionPolicy.Result load(
            ConsoleLaunchContract.Request request) {
        source.awaitReady();
        return ConsoleStreamLaunchResolutionPolicy.resolve(
                request, source.findComputer(request.hostUuid), source.clientUniqueId());
    }
}
