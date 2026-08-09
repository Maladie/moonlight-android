package com.limelight.console;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Synchronous data layer. Callers run refresh() on their cancellable worker. */
final class PlayniteLibraryRepository {
    interface Cancellation { boolean cancelled(); }

    enum ErrorKind { OFFLINE, TIMEOUT, AUTHENTICATION, API_VERSION, INVALID_RESPONSE, SERVER }

    static final class Result {
        final PlayniteLibraryCache.Entry entry;
        final ErrorKind error;

        private Result(PlayniteLibraryCache.Entry entry, ErrorKind error) {
            this.entry = entry;
            this.error = error;
        }

        static Result success(PlayniteLibraryCache.Entry entry) {
            return new Result(entry, null);
        }

        static Result failure(ErrorKind error) { return new Result(null, error); }
    }

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 200;
    private final HostGatewayClient client;
    private final PlayniteLibraryCache cache;

    PlayniteLibraryRepository(HostGatewayClient client, PlayniteLibraryCache cache) {
        this.client = client;
        this.cache = cache;
    }

    PlayniteLibraryCache.Entry cached(String hostUuid) { return cache.read(hostUuid); }

    Result refresh(String hostUuid, HostGatewayClient.Connection connection,
                   Cancellation cancellation) {
        return refresh(hostUuid, connection, cancellation, false);
    }

    Result refresh(String hostUuid, HostGatewayClient.Connection connection,
                   Cancellation cancellation, boolean refreshSource) {
        if (connection == null) return Result.failure(ErrorKind.AUTHENTICATION);
        IOException last = null;
        boolean serverFailure = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (cancellation.cancelled() || Thread.currentThread().isInterrupted()) {
                return Result.failure(ErrorKind.OFFLINE);
            }
            try {
                if (refreshSource) {
                    String previousRevision = client.refreshPlayniteLibrary(connection);
                    if (!waitForFreshSnapshot(connection, previousRevision, cancellation)) {
                        return Result.failure(ErrorKind.TIMEOUT);
                    }
                    refreshSource = false;
                }
                PlayniteLibraryCache.Entry entry = fetchAll(connection, cancellation);
                cache.write(hostUuid, entry);
                return Result.success(entry);
            } catch (HostGatewayClient.GatewayException error) {
                if (error.statusCode == 401 || error.statusCode == 403) {
                    return Result.failure(ErrorKind.AUTHENTICATION);
                }
                if (error.statusCode >= 400 && error.statusCode < 500) {
                    return Result.failure(ErrorKind.API_VERSION);
                }
                serverFailure = error.statusCode >= 500;
                last = error;
            } catch (java.net.SocketTimeoutException error) {
                last = error;
            } catch (IOException error) {
                last = error;
                if (error.getMessage() != null && error.getMessage().contains("Invalid response")) {
                    return Result.failure(ErrorKind.INVALID_RESPONSE);
                }
            }
            if (attempt < 2 && !sleepBackoff(attempt, cancellation)) break;
        }
        return Result.failure(last instanceof java.net.SocketTimeoutException
                ? ErrorKind.TIMEOUT : serverFailure ? ErrorKind.SERVER : ErrorKind.OFFLINE);
    }

    private boolean waitForFreshSnapshot(HostGatewayClient.Connection connection,
                                         String previousRevision,
                                         Cancellation cancellation) throws IOException {
        long now = System.currentTimeMillis();
        long legacyFallbackAt = now + 750L;
        long deadline = now + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (cancellation.cancelled() || Thread.currentThread().isInterrupted()) return false;
            HostGatewayClient.PlayniteLibrary first =
                    client.getPlayniteLibrary(connection, "", 1);
            if (!first.revision.isEmpty()
                    && !first.revision.equals(previousRevision)) return true;
            // Compatibility with an older Bridge which cannot expose revisions:
            // allow it a short grace period, then read its current cache normally.
            if (first.revision.isEmpty()
                    && System.currentTimeMillis() >= legacyFallbackAt) return true;
            if (!sleep(250L, cancellation)) return false;
        }
        return false;
    }

    private PlayniteLibraryCache.Entry fetchAll(HostGatewayClient.Connection connection,
                                                 Cancellation cancellation) throws IOException {
        PlayniteLibraryPaginator.Result pages = PlayniteLibraryPaginator.fetchAll(
                (cursor, limit) -> client.getPlayniteLibrary(connection, cursor, limit),
                cancellation::cancelled, PAGE_SIZE, MAX_PAGES);
        List<PlayniteLibraryGame> games = new ArrayList<>();
        for (HostGatewayClient.PlayniteGame game : pages.games) {
            games.add(new PlayniteLibraryGame(game.id, game.name,
                    game.installed, game.installing, game.hidden, game.playtimeSeconds,
                    game.lastPlayed,
                    game.cover, game.background, game.description, game.playCount, game.source));
        }
        return new PlayniteLibraryCache.Entry(games, System.currentTimeMillis(),
                pages.revision, pages.apiVersion);
    }

    static ErrorKind classify(IOException error) {
        if (error instanceof HostGatewayClient.GatewayException) {
            int status = ((HostGatewayClient.GatewayException) error).statusCode;
            if (status == 401 || status == 403) return ErrorKind.AUTHENTICATION;
            if (status >= 500) return ErrorKind.SERVER;
            if (status >= 400) return ErrorKind.API_VERSION;
        }
        if (error instanceof java.net.SocketTimeoutException) return ErrorKind.TIMEOUT;
        return error.getMessage() != null && error.getMessage().contains("Invalid response")
                ? ErrorKind.INVALID_RESPONSE : ErrorKind.OFFLINE;
    }

    private static boolean sleepBackoff(int attempt, Cancellation cancellation) {
        return sleep(attempt == 0 ? 500L : 1_500L, cancellation);
    }

    private static boolean sleep(long duration, Cancellation cancellation) {
        long remaining = duration;
        while (remaining > 0L && !cancellation.cancelled()) {
            long slice = Math.min(remaining, 100L);
            try { Thread.sleep(slice); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= slice;
        }
        return !cancellation.cancelled();
    }
}
