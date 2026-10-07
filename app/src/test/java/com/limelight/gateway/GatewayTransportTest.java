package com.limelight.gateway;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.HttpsURLConnection;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GatewayTransportTest {
    private static final String FINGERPRINT =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final GatewayConnection CONNECTION = new GatewayConnection(
            "https://host:8785", "secret", FINGERPRINT, "profile-1");

    @Test public void getHeadersContainAuthenticationAndOneRequestIdWithoutPostHeaders() {
        AtomicInteger calls = new AtomicInteger();
        GatewayTransport transport = new GatewayTransport(() -> {
            calls.incrementAndGet();
            return "get-request";
        });

        Map<String, String> headers =
                transport.buildRequestHeaders(CONNECTION, false, false);

        assertEquals("application/json", headers.get("Accept"));
        assertEquals("close", headers.get("Connection"));
        assertEquals("Bearer secret", headers.get("Authorization"));
        assertEquals("profile-1", headers.get("X-WakePlay-Profile"));
        assertTrue(headers.get("X-MoonWaker-Profile-Session")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
        assertEquals("child_profiles_v1", headers.get("X-MoonWaker-Capabilities"));
        assertFalse(headers.containsKey("Content-Type"));
        assertEquals("get-request", headers.get("X-Request-Id"));
        assertEquals(1, calls.get());
    }

    @Test public void profileSessionIsStableAcrossTransportsAndSeparateFromRequestIds() {
        GatewayTransport first = new GatewayTransport(() -> "request-a");
        GatewayTransport second = new GatewayTransport(() -> "request-b");

        String firstSession = first.buildRequestHeaders(CONNECTION, false, false)
                .get("X-MoonWaker-Profile-Session");
        String secondSession = second.buildRequestHeaders(CONNECTION, false, false)
                .get("X-MoonWaker-Profile-Session");

        assertEquals(firstSession, secondSession);
        assertNotEquals("request-a", firstSession);
        assertNotEquals("request-b", secondSession);
    }

    @Test public void postHeadersContainOneDeterministicRequestId() {
        AtomicInteger calls = new AtomicInteger();
        GatewayTransport transport = new GatewayTransport(() -> {
            calls.incrementAndGet();
            return "request-1";
        });

        Map<String, String> headers =
                transport.buildRequestHeaders(CONNECTION, true, false);

        assertEquals("application/json", headers.get("Accept"));
        assertEquals("close", headers.get("Connection"));
        assertEquals("Bearer secret", headers.get("Authorization"));
        assertEquals("profile-1", headers.get("X-WakePlay-Profile"));
        assertEquals("application/json; charset=utf-8", headers.get("Content-Type"));
        assertEquals("request-1", headers.get("X-Request-Id"));
        assertEquals(1, calls.get());
    }

    @Test public void callerSuppliedRequestIdIsUsedWithoutGeneratingAnother() {
        AtomicInteger calls = new AtomicInteger();
        GatewayTransport transport = new GatewayTransport(() -> {
            calls.incrementAndGet();
            return "generated";
        });

        String requestId = transport.effectiveRequestId("suspend-123");
        Map<String, String> headers = transport.buildRequestHeaders(
                CONNECTION, true, false, requestId);

        assertEquals("suspend-123", headers.get("X-Request-Id"));
        assertEquals(0, calls.get());
    }

    @Test public void callerSuppliedRequestIdIsRestrictedToSessionMutations() throws Exception {
        GatewayTransport transport = new GatewayTransport(() -> "generated");
        try {
            transport.postJson(CONNECTION, "/api/v1/status", new JSONObject(),
                    "request-1", 1_000);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    @Test public void truncatedSuccessRetryRequiresRecoverableExactOperation() throws Exception {
        JSONObject reauthorize = new JSONObject()
                .put("intent", "reauthorize").put("operation_id", "op-1");
        assertTrue(GatewayTransport.canRetryTruncatedSuccess(
                "/api/v1/control/operation/acquire", reauthorize, "op-1"));
        assertFalse(GatewayTransport.canRetryTruncatedSuccess(
                "/api/v1/control/operation/acquire", reauthorize, "op-2"));
        assertTrue(GatewayTransport.canRetryTruncatedSuccess(
                "/api/v1/control/operation/acquire",
                new JSONObject().put("intent", "full_quit").put("operation_id", "op-1"),
                "op-1"));
        assertFalse(GatewayTransport.canRetryTruncatedSuccess(
                "/api/v1/control/operation/acquire",
                new JSONObject().put("intent", "hard_reset").put("operation_id", "op-1"),
                "op-1"));
        assertFalse(GatewayTransport.canRetryTruncatedSuccess(
                "/api/v1/control/operation/complete", reauthorize, "op-1"));
        assertTrue(GatewayTransport.canRetryTruncatedSuccess(
                "/api/v1/game/current", null, null));
    }

    @Test public void callerSuppliedRequestIdIsValidatedWithoutGeneratingAnother() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        GatewayTransport transport = new GatewayTransport(() -> {
            calls.incrementAndGet();
            return "generated";
        });
        for (String path : new String[]{
                "/api/v1/system/suspend-session",
                "/api/v1/system/session/ensure",
                "/api/v1/system/session/switch",
                "/api/v1/system/session/cancel"}) {
            try {
                transport.postJson(CONNECTION, path, new JSONObject(),
                        "bad request id", 1_000);
                fail("Expected IllegalArgumentException for " + path);
            } catch (IllegalArgumentException expected) {
                // The allowed route reached caller-ID validation without opening a connection.
            }
        }
        assertEquals(0, calls.get());
    }

    @Test public void pairingHeadersOmitAuthenticationAndProfile() {
        AtomicInteger calls = new AtomicInteger();
        GatewayTransport transport = new GatewayTransport(() -> {
            calls.incrementAndGet();
            return "pair-request";
        });

        Map<String, String> headers = transport.buildRequestHeaders(null, true, true);

        assertEquals("pair-request", headers.get("X-Request-Id"));
        assertFalse(headers.containsKey("Authorization"));
        assertFalse(headers.containsKey("X-WakePlay-Profile"));
        assertFalse(headers.containsKey("X-MoonWaker-Profile-Session"));
        assertFalse(headers.containsKey("X-MoonWaker-Capabilities"));
        assertEquals(1, calls.get());
    }

    @Test public void diagnosticRouteRemovesQueryAndFragmentWithoutExposingValues() {
        String path = "/api/v1/library?token=secret#private";

        String route = GatewayTransport.diagnosticRoute(path);

        assertEquals("/api/v1/library", route);
        assertFalse(route.contains("secret"));
        assertFalse(route.contains("private"));
    }

    @Test public void normalizedFingerprintsMatchButChangedPinDoesNot() {
        String colonized = FINGERPRINT.replaceAll("(..)(?!$)", "$1:").toUpperCase();
        assertTrue(GatewayTransport.fingerprintsMatch(FINGERPRINT, colonized));
        assertFalse(GatewayTransport.fingerprintsMatch(FINGERPRINT,
                "1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
    }

    @Test public void errorResponsePreservesMessageAndStatus() throws Exception {
        assertGatewayException("Unavailable", 503,
                () -> GatewayTransport.decodeJsonResponse(503,
                        "{\"error\":\"Unavailable\"}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void errorResponsePreservesBoundedRetryDelay() throws Exception {
        try {
            GatewayTransport.decodeJsonResponse(429,
                    "{\"error\":\"rate_limited\",\"retry_after_seconds\":7}"
                            .getBytes(StandardCharsets.UTF_8));
            fail("Expected GatewayException");
        } catch (GatewayTransport.GatewayException error) {
            assertEquals("rate_limited", error.getMessage());
            assertEquals(429, error.statusCode());
            assertEquals(7, error.retryAfterSeconds());
        }
    }

    @Test public void errorResponsePreservesStructuredReasonAndBody() throws Exception {
        try {
            GatewayTransport.decodeJsonResponse(409,
                    ("{\"error\":\"blocked\",\"reason\":\"outside_schedule\","
                            + "\"actor_profile_id\":\"profile-1\"}")
                            .getBytes(StandardCharsets.UTF_8));
            fail("Expected GatewayException");
        } catch (GatewayTransport.GatewayException error) {
            assertEquals("outside_schedule", error.reason());
            assertEquals("profile-1",
                    error.responseBody().getString("actor_profile_id"));
        }
    }

    @Test public void errorResponseWithoutMessageUsesFallback() throws Exception {
        assertGatewayException("Host gateway request failed.", 404,
                () -> GatewayTransport.decodeJsonResponse(404,
                        "{}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void discord409IsNotReadyStateWhileMalformedSuccessStaysDecodeFailure()
            throws Exception {
        assertTrue(GatewayTransport.isDiscordNotReady("/api/v1/discord/voice?force=true", 409));
        assertTrue(GatewayTransport.isDiscordNotReady("/api/v1/discord/home", 409));
        assertFalse(GatewayTransport.isDiscordNotReady("/api/v1/discord/home", 502));
        assertFalse(GatewayTransport.isDiscordNotReady("/api/v1/control/operation/acquire", 409));
        // A well-formed 409 body parses; the status check, not the JSON decode, rejects it.
        byte[] notReady = "{\"ok\":false,\"error\":\"Discord RPC authorization is required.\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertGatewayException("Discord RPC authorization is required.", 409,
                () -> GatewayTransport.requireSuccess(409,
                        GatewayTransport.parseJson(409, notReady)));
        assertGatewayException("Invalid response from host gateway.", 200,
                () -> GatewayTransport.parseJson(200,
                        "not-json".getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void malformedJsonPreservesStatus() throws Exception {
        assertGatewayException("Invalid response from host gateway.", 502,
                () -> GatewayTransport.decodeJsonResponse(502,
                        "not-json".getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void boundedReadAcceptsLimitAndRejectsLimitPlusOne() throws Exception {
        byte[] exact = new byte[]{1, 2, 3, 4};
        assertArrayEquals(exact,
                GatewayTransport.readBounded(new ByteArrayInputStream(exact), exact.length));
        try {
            GatewayTransport.readBounded(
                    new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5}), 4);
            fail("Expected ResponseTooLargeException");
        } catch (GatewayTransport.ResponseTooLargeException expected) {
            // Expected.
        }
    }

    @Test public void boundedReadKeepsPartialByteCountWhenSocketFails() throws Exception {
        int[] bytesRead = {0};
        InputStream interrupted = new FilterInputStream(
                new ByteArrayInputStream(new byte[]{1, 2, 3})) {
            private boolean delivered;

            @Override public int read(byte[] buffer, int offset, int length)
                    throws IOException {
                if (delivered) throw new IOException("unexpected_eof");
                delivered = true;
                return super.read(buffer, offset, length);
            }
        };
        try {
            GatewayTransport.readBounded(interrupted, 8, bytesRead);
            fail("Expected interrupted response body");
        } catch (IOException expected) {
            assertEquals(3, bytesRead[0]);
        }
    }

    @Test public void networkDownloadCountsBytesAndRawElapsedTimeWithoutPayloadResult()
            throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);

        GatewayTransport.NetworkDownloadSample sample =
                GatewayTransport.readNetworkDownload(
                        new ByteArrayInputStream(new byte[96_000]), 96_000,
                        () -> clock.getAndAdd(2_000_000L));

        assertEquals(96_000L, sample.bytes);
        assertEquals(2_000_000L, sample.elapsedNanos);
    }

    @Test public void networkDownloadPathIsFixedAndHardBounded() {
        assertEquals("/api/v1/diagnostics/network/download?size=536870912",
                GatewayTransport.networkDownloadPath(512 * 1024 * 1024));
        try {
            GatewayTransport.networkDownloadPath(512 * 1024 * 1024 + 1);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    @Test public void networkDownloadRejectsTruncatedAndOversizeBodies() throws Exception {
        try {
            GatewayTransport.readNetworkDownload(
                    new ByteArrayInputStream(new byte[3]), 4, System::nanoTime);
            fail("Expected truncated response failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Truncated"));
        }
        try {
            GatewayTransport.readNetworkDownload(
                    new ByteArrayInputStream(new byte[5]), 4, System::nanoTime);
            fail("Expected response bound failure");
        } catch (GatewayTransport.ResponseTooLargeException expected) {
            // Expected.
        }
    }

    @Test public void networkDownloadStopsWhenWorkerIsInterrupted() throws Exception {
        Thread.currentThread().interrupt();
        try {
            GatewayTransport.readNetworkDownload(
                    new ByteArrayInputStream(new byte[4]), 4, System::nanoTime);
            fail("Expected interrupted download failure");
        } catch (InterruptedIOException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void interruptedReadIsCancellationButTimeoutAndIoFailureAreErrors()
            throws Exception {
        java.io.File directory = java.nio.file.Files.createTempDirectory("gateway-cancel").toFile();
        diagnosticsHook("resetForTests");
        try (java.net.ServerSocket silent = new java.net.ServerSocket(0);
             java.net.ServerSocket closed = new java.net.ServerSocket(0)) {
            com.limelight.diagnostics.MoonWakerDiagnostics.initialize(directory);
            assertTrue(GatewayTransport.recordCancelled("GET", "/api/v1/capabilities",
                    "cancelled-1", CONNECTION, 0, System.nanoTime(),
                    new InterruptedIOException("interrupted"), false));
            assertTrue(Thread.interrupted());

            int closedPort = closed.getLocalPort();
            closed.close();
            GatewayTransport transport = new GatewayTransport(() -> "failed-1");
            try {
                transport.getJson(new GatewayConnection("https://127.0.0.1:" + closedPort,
                        "secret", FINGERPRINT, "profile-1"), "/api/v1/capabilities", 200);
                fail("Expected connection failure");
            } catch (IOException expected) {
                assertFalse(expected instanceof InterruptedIOException);
            }
            transport = new GatewayTransport(() -> "timeout-1");
            try {
                transport.getJson(new GatewayConnection("https://127.0.0.1:"
                        + silent.getLocalPort(), "secret", FINGERPRINT, "profile-1"),
                        "/api/v1/capabilities", 200);
                fail("Expected read timeout");
            } catch (java.net.SocketTimeoutException expected) {
                assertFalse(Thread.currentThread().isInterrupted());
            }
            assertTrue((Boolean) diagnosticsHook("flushForTests"));
            String events = new String(java.nio.file.Files.readAllBytes(
                    new java.io.File(directory, "events.jsonl").toPath()), StandardCharsets.UTF_8);
            for (String line : events.split("\r?\n")) {
                if (line.contains("\"cancelled-1\"")) {
                    assertTrue(line, line.contains("\"level\":\"INFO\"")
                            && line.contains("\"event\":\"request.cancelled\"")
                            && line.contains("\"error_type\":\"java.io.InterruptedIOException\""));
                }
                if (line.contains("\"failed-1\"") || line.contains("\"timeout-1\"")) {
                    assertTrue(line, line.contains("\"level\":\"ERROR\""));
                }
            }
            assertTrue(events.contains("\"event\":\"request.cancelled\""));
            assertTrue(events.contains("\"request_id\":\"failed-1\""));
            assertTrue(events.contains("\"request_id\":\"timeout-1\""));
        } finally {
            Thread.interrupted();
            diagnosticsHook("resetForTests");
        }
    }

    private static Object diagnosticsHook(String name) throws Exception {
        java.lang.reflect.Method method = com.limelight.diagnostics.MoonWakerDiagnostics.class
                .getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(null);
    }

    @Test public void resolvesOnlyRelativeApiPaths() throws Exception {
        assertEquals("https://host:8785/api/v1/status",
                GatewayTransport.resolve(" https://host:8785/ ", "/api/v1/status").toString());
        assertRejectedPath("https://other/api/v1/status");
        assertRejectedPath("/status");
    }

    @Test public void microphoneFramesHaveOneFixedTwentyMillisecondShape() {
        GatewayTransport.requireMicrophoneFrame(new byte[1920]);
        try {
            GatewayTransport.requireMicrophoneFrame(new byte[1919]);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    @Test public void discordAudioCloseAbortsOneConcurrentReadWithoutClosingItsInput()
            throws Exception {
        BlockingInputStream input = new BlockingInputStream();
        DisconnectingHttpsConnection connection =
                new DisconnectingHttpsConnection(input);
        GatewayTransport.DiscordAudioStream stream =
                new GatewayTransport.DiscordAudioStream(connection, input);
        Thread reader = new Thread(() -> {
            try { stream.read(new byte[1], 0, 1); }
            catch (IOException ignored) { }
        });

        reader.start();
        assertTrue(input.readStarted.await(2, TimeUnit.SECONDS));
        stream.close();
        stream.close();
        reader.join(2_000L);

        assertFalse(reader.isAlive());
        assertEquals(1, connection.disconnects.get());
        assertEquals(0, input.closes.get());
    }

    private static void assertRejectedPath(String path) throws Exception {
        try {
            GatewayTransport.resolve("https://host:8785", path);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void assertGatewayException(String message, int status,
                                               ThrowingRunnable action) throws Exception {
        try {
            action.run();
            fail("Expected GatewayException");
        } catch (GatewayTransport.GatewayException error) {
            assertEquals(message, error.getMessage());
            assertEquals(status, error.statusCode());
        }
    }

    private interface ThrowingRunnable {
        void run() throws IOException;
    }

    private static final class BlockingInputStream extends InputStream {
        final CountDownLatch readStarted = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        final AtomicInteger closes = new AtomicInteger();

        @Override public int read() throws IOException {
            readStarted.countDown();
            try {
                if (!disconnected.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("Disconnect did not abort the read");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            return -1;
        }

        @Override public void close() {
            closes.incrementAndGet();
            disconnected.countDown();
        }
    }

    private static final class DisconnectingHttpsConnection extends HttpsURLConnection {
        final BlockingInputStream input;
        final AtomicInteger disconnects = new AtomicInteger();

        DisconnectingHttpsConnection(BlockingInputStream input) throws Exception {
            super(new URL("https://host/audio"));
            this.input = input;
        }

        @Override public void disconnect() {
            disconnects.incrementAndGet();
            input.disconnected.countDown();
        }

        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
        @Override public String getCipherSuite() { return ""; }
        @Override public java.security.cert.Certificate[] getLocalCertificates() {
            return null;
        }
        @Override public java.security.cert.Certificate[] getServerCertificates() {
            return null;
        }
    }
}
