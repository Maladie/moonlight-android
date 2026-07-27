package com.limelight.console;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PlayniteLibraryPaginatorTest {
    @Test public void fetchesAllPagesAndDeduplicatesStableIds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PlayniteLibraryPaginator.Result result = PlayniteLibraryPaginator.fetchAll(
                (cursor, limit) -> calls.getAndIncrement() == 0
                        ? page(Arrays.asList(game(1), game(2)), "next", "1")
                        : page(Arrays.asList(game(2), game(3)), "", "2"),
                () -> false, 100, 10);
        assertEquals(2, calls.get());
        assertEquals(3, result.games.size());
        assertEquals("2", result.revision);
    }

    @Test public void cancellationStopsBeforeNetworkCall() {
        AtomicInteger calls = new AtomicInteger();
        try {
            PlayniteLibraryPaginator.fetchAll((cursor, limit) -> {
                calls.incrementAndGet();
                return page(Collections.emptyList(), "", "");
            }, () -> true, 100, 10);
            fail("Expected cancellation");
        } catch (IOException expected) {
            assertEquals(0, calls.get());
        }
    }

    @Test public void repeatedCursorIsRejected() {
        try {
            PlayniteLibraryPaginator.fetchAll((cursor, limit) ->
                    page(Collections.singletonList(game(1)), "next", ""),
                    () -> false, 100, 10);
            fail("Expected invalid cursor");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("cursor"));
        }
    }

    @Test public void gatewayErrorsAreClassifiedWithoutRetryingAuth() {
        assertEquals(PlayniteLibraryRepository.ErrorKind.AUTHENTICATION,
                PlayniteLibraryRepository.classify(
                        new HostGatewayClient.GatewayException("unauthorized", 401)));
        assertEquals(PlayniteLibraryRepository.ErrorKind.SERVER,
                PlayniteLibraryRepository.classify(
                        new HostGatewayClient.GatewayException("server", 503)));
        assertEquals(PlayniteLibraryRepository.ErrorKind.TIMEOUT,
                PlayniteLibraryRepository.classify(new SocketTimeoutException("timeout")));
        assertEquals(PlayniteLibraryRepository.ErrorKind.INVALID_RESPONSE,
                PlayniteLibraryRepository.classify(new IOException("Invalid response")));
    }

    private static HostGatewayClient.PlayniteLibrary page(
            java.util.List<HostGatewayClient.PlayniteGame> games,
            String cursor, String revision) {
        return new HostGatewayClient.PlayniteLibrary(games, cursor, games.size(),
                revision, "1");
    }

    private static HostGatewayClient.PlayniteGame game(int suffix) {
        String id = String.format(java.util.Locale.US,
                "%08d-0000-0000-0000-000000000000", suffix);
        return new HostGatewayClient.PlayniteGame(id, "Game " + suffix, true,
                false, false, "", "", "", "Steam", "", 0L);
    }
}
