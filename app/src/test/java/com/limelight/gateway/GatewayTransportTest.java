package com.limelight.gateway;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
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
        assertFalse(headers.containsKey("Content-Type"));
        assertEquals("get-request", headers.get("X-Request-Id"));
        assertEquals(1, calls.get());
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

    @Test public void callerSuppliedRequestIdIsValidatedWithoutGeneratingAnother() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        GatewayTransport transport = new GatewayTransport(() -> {
            calls.incrementAndGet();
            return "generated";
        });
        for (String path : new String[]{
                "/api/v1/system/suspend-session",
                "/api/v1/system/session/ensure",
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

    @Test public void errorResponseWithoutMessageUsesFallback() throws Exception {
        assertGatewayException("Host gateway request failed.", 404,
                () -> GatewayTransport.decodeJsonResponse(404,
                        "{}".getBytes(StandardCharsets.UTF_8)));
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
