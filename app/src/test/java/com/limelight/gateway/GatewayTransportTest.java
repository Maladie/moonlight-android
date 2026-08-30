package com.limelight.gateway;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test public void callerSuppliedRequestIdIsRestrictedToSuspendEndpoint() throws Exception {
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
        try {
            transport.postJson(CONNECTION, "/api/v1/system/suspend-session", new JSONObject(),
                    "bad request id", 1_000);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
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

    @Test public void resolvesOnlyRelativeApiPaths() throws Exception {
        assertEquals("https://host:8785/api/v1/status",
                GatewayTransport.resolve(" https://host:8785/ ", "/api/v1/status").toString());
        assertRejectedPath("https://other/api/v1/status");
        assertRejectedPath("/status");
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
}
