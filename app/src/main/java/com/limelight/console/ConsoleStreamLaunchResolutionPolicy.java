package com.limelight.console;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.PairingManager;

/** Validates ComputerManager output before Console creates an in-Activity session. */
final class ConsoleStreamLaunchResolutionPolicy {
    enum Error {
        HOST_NOT_FOUND,
        HOST_ID_MISMATCH,
        HOST_NOT_PAIRED,
        HOST_ADDRESS_UNAVAILABLE,
        CLIENT_ID_UNAVAILABLE,
        RESOLUTION_FAILED
    }

    static final class Result {
        final StreamLaunchParameters parameters;
        final Error error;

        private Result(StreamLaunchParameters parameters, Error error) {
            this.parameters = parameters;
            this.error = error;
        }

        static Result resolved(StreamLaunchParameters parameters) {
            return new Result(parameters, null);
        }

        static Result failed(Error error) {
            return new Result(null, error);
        }

        boolean isResolved() {
            return parameters != null;
        }
    }

    private ConsoleStreamLaunchResolutionPolicy() { }

    static Result resolve(ConsoleLaunchContract.Request request,
                          ComputerDetails computer,
                          String clientUniqueId) {
        if (computer == null) {
            return Result.failed(Error.HOST_NOT_FOUND);
        }
        if (computer.uuid == null ||
                !request.hostUuid.equalsIgnoreCase(computer.uuid)) {
            return Result.failed(Error.HOST_ID_MISMATCH);
        }
        if (computer.pairState != PairingManager.PairState.PAIRED) {
            return Result.failed(Error.HOST_NOT_PAIRED);
        }
        if (computer.activeAddress == null) {
            return Result.failed(Error.HOST_ADDRESS_UNAVAILABLE);
        }
        if (clientUniqueId == null || clientUniqueId.isBlank()) {
            return Result.failed(Error.CLIENT_ID_UNAVAILABLE);
        }

        NvApp app = new NvApp(request.appName, request.appId, request.appSupportsHdr);
        return Result.resolved(StreamLaunchParameters.create(
                computer, app, clientUniqueId, null, true,
                request.runtimeBitrateKbps));
    }
}
