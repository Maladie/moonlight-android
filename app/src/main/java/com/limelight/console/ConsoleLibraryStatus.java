package com.limelight.console;

/** Maps repository state to one stable UI status without depending on Android views. */
final class ConsoleLibraryStatus {
    enum State {
        REFRESHING,
        GATEWAY_NOT_CONFIGURED,
        AUTHENTICATION_ERROR,
        TIMEOUT,
        INVALID_RESPONSE,
        SERVER_ERROR,
        GATEWAY_UNAVAILABLE,
        CACHED,
        CURRENT
    }

    private ConsoleLibraryStatus() { }

    static State resolve(boolean refreshing, boolean hasGames, boolean cached,
                         PlayniteLibraryRepository.ErrorKind error,
                         boolean gatewayConfigured) {
        if (refreshing && !hasGames) return State.REFRESHING;
        if (error == PlayniteLibraryRepository.ErrorKind.AUTHENTICATION) {
            return gatewayConfigured ? State.AUTHENTICATION_ERROR
                    : State.GATEWAY_NOT_CONFIGURED;
        }
        if (error == PlayniteLibraryRepository.ErrorKind.TIMEOUT) return State.TIMEOUT;
        if (error == PlayniteLibraryRepository.ErrorKind.INVALID_RESPONSE
                || error == PlayniteLibraryRepository.ErrorKind.API_VERSION) {
            return State.INVALID_RESPONSE;
        }
        if (error == PlayniteLibraryRepository.ErrorKind.SERVER) return State.SERVER_ERROR;
        if (error != null) return State.GATEWAY_UNAVAILABLE;
        if (cached) return State.CACHED;
        if (refreshing) return State.REFRESHING;
        return State.CURRENT;
    }

    static boolean isError(State state) {
        return state != State.REFRESHING && state != State.CACHED && state != State.CURRENT;
    }
}
