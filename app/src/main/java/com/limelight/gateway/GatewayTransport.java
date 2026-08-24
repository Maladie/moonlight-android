package com.limelight.gateway;

import android.annotation.SuppressLint;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

@SuppressLint({"BadHostnameVerifier", "CustomX509TrustManager"})
public final class GatewayTransport {
    private static final int CONNECT_TIMEOUT_MS = 2_500;
    private static final int JSON_LIMIT = 1024 * 1024;
    private static final int BINARY_LIMIT = 8 * 1024 * 1024;

    private static final HostnameVerifier PINNED_HOSTNAME_VERIFIER = (hostname, session) -> {
        // Identity is verified by the pinned certificate independently of a DHCP address
        // that may change later.
        return true;
    };

    private final Supplier<String> requestIds;

    public GatewayTransport() {
        this(() -> UUID.randomUUID().toString());
    }

    GatewayTransport(Supplier<String> requestIds) {
        if (requestIds == null) throw new NullPointerException("requestIds");
        this.requestIds = requestIds;
    }

    public JSONObject getJson(GatewayConnection connection, String path, int readTimeoutMs)
            throws IOException {
        return requestJson(connection.endpoint(), path, null, readTimeoutMs,
                connection, new GatewayTrustManager(connection.certificateSha256(), false), null);
    }

    public JSONObject postJson(GatewayConnection connection, String path, JSONObject body,
                               int readTimeoutMs) throws IOException {
        return requestJson(connection.endpoint(), path, body != null ? body : new JSONObject(),
                readTimeoutMs, connection,
                new GatewayTrustManager(connection.certificateSha256(), false), null);
    }

    public JSONObject postJson(GatewayConnection connection, String path, JSONObject body,
                               String requestId, int readTimeoutMs) throws IOException {
        if (!"/api/v1/system/suspend-session".equals(path)) {
            throw new IllegalArgumentException(
                    "Caller request IDs are only supported for session suspend");
        }
        return requestJson(connection.endpoint(), path, body != null ? body : new JSONObject(),
                readTimeoutMs, connection,
                new GatewayTrustManager(connection.certificateSha256(), false),
                requireRequestId(requestId));
    }

    public byte[] getBinary(GatewayConnection connection, String path, String accept,
                            int readTimeoutMs) throws IOException {
        HttpsURLConnection http = null;
        try {
            http = open(connection.endpoint(), path,
                    new GatewayTrustManager(connection.certificateSha256(), false), readTimeoutMs);
            http.setRequestMethod("GET");
            applyHeaders(http, buildRequestHeaders(connection, false, false));
            http.setRequestProperty("Accept", accept);
            int status = http.getResponseCode();
            if (status < HttpURLConnection.HTTP_OK || status >= 300) {
                InputStream error = http.getErrorStream();
                byte[] raw = error != null ? readAndClose(error, JSON_LIMIT) : new byte[0];
                decodeJsonResponse(status, raw);
            }
            InputStream input = http.getInputStream();
            return input != null ? readAndClose(input, BINARY_LIMIT) : new byte[0];
        } catch (GeneralSecurityException error) {
            throw new IOException("Unable to initialize gateway TLS.", error);
        } finally {
            if (http != null) http.disconnect();
        }
    }

    public PairingResponse postPairingJson(String endpoint, String path, JSONObject body,
                                           int readTimeoutMs) throws IOException {
        if (!"/api/v1/pair".equals(path)) {
            throw new IllegalArgumentException("Pairing is only available at /api/v1/pair");
        }
        GatewayTrustManager trustManager = new GatewayTrustManager("", true);
        JSONObject response = requestJson(endpoint, path, body != null ? body : new JSONObject(),
                readTimeoutMs, null, trustManager, null);
        String fingerprint = trustManager.seenFingerprint;
        if (fingerprint == null || fingerprint.isEmpty()) {
            throw new GatewayException("The gateway did not present a certificate.", 0);
        }
        return new PairingResponse(response, fingerprint);
    }

    Map<String, String> buildRequestHeaders(GatewayConnection connection, boolean post,
                                            boolean pairing) {
        return buildRequestHeaders(connection, post, pairing, null);
    }

    Map<String, String> buildRequestHeaders(GatewayConnection connection, boolean post,
                                            boolean pairing, String requestId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Connection", "close");
        if (!pairing) {
            headers.put("Authorization", "Bearer " + connection.token());
            headers.put("X-WakePlay-Profile", connection.profileId());
        }
        if (post) {
            headers.put("Content-Type", "application/json; charset=utf-8");
            headers.put("X-Request-Id", requestId == null
                    ? requestIds.get() : requireRequestId(requestId));
        }
        return headers;
    }

    static JSONObject decodeJsonResponse(int status, byte[] raw) throws IOException {
        JSONObject response;
        try {
            response = raw.length == 0 ? new JSONObject() :
                    new JSONObject(new String(raw, StandardCharsets.UTF_8));
        } catch (JSONException error) {
            throw new GatewayException("Invalid response from host gateway.", status);
        }
        if (status < HttpURLConnection.HTTP_OK || status >= 300) {
            throw new GatewayException(
                    response.optString("error", "Host gateway request failed."), status);
        }
        return response;
    }

    static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > limit) throw new ResponseTooLargeException(limit);
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    static boolean fingerprintsMatch(String expected, String actual) {
        byte[] expectedBytes = GatewayConnection.normalizeFingerprint(expected)
                .getBytes(StandardCharsets.US_ASCII);
        byte[] actualBytes = GatewayConnection.normalizeFingerprint(actual)
                .getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    static URL resolve(String endpoint, String path) throws IOException {
        if (path == null || !path.startsWith("/api/v1/")) {
            throw new IllegalArgumentException("Gateway path must start with /api/v1/");
        }
        String normalizedEndpoint = endpoint == null ? "" : endpoint.trim();
        while (normalizedEndpoint.endsWith("/")) {
            normalizedEndpoint = normalizedEndpoint.substring(0, normalizedEndpoint.length() - 1);
        }
        if (!normalizedEndpoint.startsWith("https://")) {
            throw new IllegalArgumentException("Gateway endpoint must use HTTPS");
        }
        return new URL(normalizedEndpoint + path);
    }

    private JSONObject requestJson(String endpoint, String path, JSONObject body,
                                   int readTimeoutMs, GatewayConnection connection,
                                   GatewayTrustManager trustManager, String requestId)
            throws IOException {
        HttpsURLConnection http = null;
        try {
            http = open(endpoint, path, trustManager, readTimeoutMs);
            boolean post = body != null;
            http.setRequestMethod(post ? "POST" : "GET");
            applyHeaders(http, buildRequestHeaders(
                    connection, post, connection == null, requestId));
            if (post) {
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                http.setDoOutput(true);
                http.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = http.getOutputStream()) {
                    output.write(payload);
                }
            }
            int status = http.getResponseCode();
            InputStream input = status >= 400 ? http.getErrorStream() : http.getInputStream();
            byte[] raw = input != null ? readAndClose(input, JSON_LIMIT) : new byte[0];
            return decodeJsonResponse(status, raw);
        } catch (GeneralSecurityException error) {
            throw new IOException("Unable to initialize gateway TLS.", error);
        } finally {
            if (http != null) http.disconnect();
        }
    }

    static String requireRequestId(String requestId) {
        String value = requestId == null ? "" : requestId.trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("Invalid gateway request ID");
        }
        return value;
    }

    private static HttpsURLConnection open(String endpoint, String path,
                                           GatewayTrustManager trustManager, int readTimeoutMs)
            throws IOException, GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trustManager}, null);
        HttpsURLConnection http = (HttpsURLConnection) resolve(endpoint, path).openConnection();
        http.setSSLSocketFactory(context.getSocketFactory());
        http.setHostnameVerifier(PINNED_HOSTNAME_VERIFIER);
        http.setConnectTimeout(CONNECT_TIMEOUT_MS);
        http.setReadTimeout(readTimeoutMs);
        return http;
    }

    private static void applyHeaders(HttpsURLConnection http, Map<String, String> headers) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            http.setRequestProperty(header.getKey(), header.getValue());
        }
    }

    private static byte[] readAndClose(InputStream input, int limit) throws IOException {
        try (InputStream stream = input) {
            return readBounded(stream, limit);
        }
    }

    private static String fingerprint(X509Certificate certificate) throws CertificateException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                value.append(String.format(Locale.US, "%02x", item & 0xff));
            }
            return value.toString();
        } catch (GeneralSecurityException error) {
            throw new CertificateException(error);
        }
    }

    public static final class PairingResponse {
        private final JSONObject response;
        private final String certificateSha256;

        private PairingResponse(JSONObject response, String certificateSha256) {
            this.response = response;
            this.certificateSha256 = certificateSha256;
        }

        public JSONObject response() {
            return response;
        }

        public String certificateSha256() {
            return certificateSha256;
        }
    }

    public static final class GatewayException extends IOException {
        private final int statusCode;

        public GatewayException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    public static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException(int limit) {
            super("Gateway response exceeded the " + limit + " byte limit.");
        }
    }

    private static final class GatewayTrustManager implements X509TrustManager {
        private final String expectedFingerprint;
        private final boolean trustOnFirstUse;
        private volatile String seenFingerprint;

        GatewayTrustManager(String expectedFingerprint, boolean trustOnFirstUse) {
            this.expectedFingerprint = GatewayConnection.normalizeFingerprint(expectedFingerprint);
            this.trustOnFirstUse = trustOnFirstUse;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException("Client certificates are not supported.");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("Missing server certificate.");
            }
            String actual = fingerprint(chain[0]);
            seenFingerprint = actual;
            if (!trustOnFirstUse && !fingerprintsMatch(expectedFingerprint, actual)) {
                throw new CertificateException(
                        "Host gateway certificate changed. Pair the host again.");
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
