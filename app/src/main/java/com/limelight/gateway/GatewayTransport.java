package com.limelight.gateway;

import android.annotation.SuppressLint;

import com.limelight.diagnostics.MoonWakerDiagnostics;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    public static final int NETWORK_DOWNLOAD_MAX_BYTES = 512 * 1024 * 1024;
    // This identifies only the current Android process. It is deliberately not persisted or
    // included in diagnostics; the host uses it to keep an authenticated profile lease alive
    // while this process remains active (for example while the screen is asleep).
    private static final String PROFILE_SESSION_ID = UUID.randomUUID().toString();

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
        if (!"/api/v1/system/suspend-session".equals(path)
                && !"/api/v1/system/session/ensure".equals(path)
                && !"/api/v1/system/session/switch".equals(path)
                && !"/api/v1/system/session/cancel".equals(path)
                && !path.startsWith("/api/v1/control/operation/")) {
            throw new IllegalArgumentException(
                    "Caller request IDs are only supported for session mutations");
        }
        return requestJson(connection.endpoint(), path, body != null ? body : new JSONObject(),
                readTimeoutMs, connection,
                new GatewayTrustManager(connection.certificateSha256(), false),
                requestId);
    }

    public byte[] getBinary(GatewayConnection connection, String path, String accept,
                            int readTimeoutMs) throws IOException {
        String requestId = effectiveRequestId(null);
        long startedNanos = System.nanoTime();
        HttpsURLConnection http = null;
        int status = 0;
        try {
            http = open(connection.endpoint(), path,
                    new GatewayTrustManager(connection.certificateSha256(), false), readTimeoutMs);
            http.setRequestMethod("GET");
            applyHeaders(http, buildRequestHeaders(connection, false, false, requestId));
            http.setRequestProperty("Accept", accept);
            status = http.getResponseCode();
            if (status < HttpURLConnection.HTTP_OK || status >= 300) {
                InputStream error = http.getErrorStream();
                byte[] raw = error != null ? readAndClose(error, JSON_LIMIT) : new byte[0];
                decodeJsonResponse(status, raw);
            }
            InputStream input = http.getInputStream();
            return input != null ? readAndClose(input, BINARY_LIMIT) : new byte[0];
        } catch (GeneralSecurityException error) {
            IOException wrapped = new IOException("Unable to initialize gateway TLS.", error);
            recordRequest("request.failed", "GET", path, requestId, connection,
                    status, startedNanos, wrapped);
            throw wrapped;
        } catch (IOException | RuntimeException error) {
            if (!recordCancelled("GET", path, requestId, connection, status, startedNanos,
                    error, Thread.currentThread().isInterrupted())) {
                recordRequest("request.failed", "GET", path, requestId, connection,
                        status, startedNanos, error);
            }
            throw error;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    public NetworkDownloadSample measureNetworkDownload(GatewayConnection connection,
                                                        int sizeBytes,
                                                        int readTimeoutMs) throws IOException {
        String path = networkDownloadPath(sizeBytes);
        String requestId = effectiveRequestId(null);
        long requestStartedNanos = System.nanoTime();
        HttpsURLConnection http = null;
        int status = 0;
        try {
            http = open(connection.endpoint(), path,
                    new GatewayTrustManager(connection.certificateSha256(), false), readTimeoutMs);
            http.setRequestMethod("GET");
            http.setInstanceFollowRedirects(false);
            applyHeaders(http, buildRequestHeaders(connection, false, false, requestId));
            http.setRequestProperty("Accept", "application/octet-stream");
            http.setRequestProperty("Accept-Encoding", "identity");
            status = http.getResponseCode();
            if (status < HttpURLConnection.HTTP_OK || status >= 300) {
                InputStream error = http.getErrorStream();
                byte[] raw = error != null ? readAndClose(error, JSON_LIMIT) : new byte[0];
                decodeJsonResponse(status, raw);
            }
            if (!"identity".equalsIgnoreCase(http.getHeaderField("Content-Encoding"))) {
                throw new IOException("Network download response must use identity encoding.");
            }
            if (!"application/octet-stream".equalsIgnoreCase(
                    http.getHeaderField("Content-Type"))) {
                throw new IOException("Unsupported network download response type.");
            }
            if (!Integer.toString(sizeBytes).equals(http.getHeaderField("Content-Length"))) {
                throw new IOException("Unexpected network download response length.");
            }
            try (InputStream input = http.getInputStream()) {
                return readNetworkDownload(input, sizeBytes, System::nanoTime);
            }
        } catch (GeneralSecurityException error) {
            IOException wrapped = new IOException("Unable to initialize gateway TLS.", error);
            recordRequest("request.failed", "GET", path, requestId, connection,
                    status, requestStartedNanos, wrapped);
            throw wrapped;
        } catch (IOException | RuntimeException error) {
            if (!recordCancelled("GET", path, requestId, connection, status,
                    requestStartedNanos, error, Thread.currentThread().isInterrupted())) {
                recordRequest("request.failed", "GET", path, requestId, connection,
                        status, requestStartedNanos, error);
            }
            throw error;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    public MicrophoneStream openMicrophoneStream(GatewayConnection connection,
                                                  String sessionId) throws IOException {
        String requestId = effectiveRequestId(null);
        HttpsURLConnection http = null;
        try {
            http = open(connection.endpoint(), "/api/v1/microphone/stream",
                    new GatewayTrustManager(connection.certificateSha256(), false), 6_000);
            http.setRequestMethod("POST");
            applyHeaders(http, buildRequestHeaders(connection, false, false, requestId));
            http.setRequestProperty("Content-Type",
                    "application/vnd.moonwaker.microphone-pcm;" +
                            "format=s16le;rate=48000;channels=1");
            http.setRequestProperty("X-Microphone-Session-Id", requireRequestId(sessionId));
            http.setDoOutput(true);
            http.setChunkedStreamingMode(1920);
            return new MicrophoneStream(http, http.getOutputStream());
        } catch (GeneralSecurityException error) {
            if (http != null) http.disconnect();
            throw new IOException("Unable to initialize gateway TLS.", error);
        } catch (IOException | RuntimeException error) {
            if (http != null) http.disconnect();
            throw error;
        }
    }

    public DiscordAudioStream openDiscordAudioStream(GatewayConnection connection)
            throws IOException {
        HttpsURLConnection http = null;
        try {
            http = open(connection.endpoint(), "/api/v1/discord/audio/stream",
                    new GatewayTrustManager(connection.certificateSha256(), false), 20_000);
            http.setRequestMethod("GET");
            applyHeaders(http, buildRequestHeaders(connection, false, false,
                    effectiveRequestId(null)));
            http.setRequestProperty("Accept",
                    "application/vnd.moonwaker.discord-audio-pcm;" +
                            "format=s16le;rate=48000;channels=2");
            int status = http.getResponseCode();
            if (status < HttpURLConnection.HTTP_OK || status >= 300) {
                InputStream error = http.getErrorStream();
                byte[] raw = error == null ? new byte[0] : readAndClose(error, JSON_LIMIT);
                decodeJsonResponse(status, raw);
            }
            String contentType = http.getHeaderField("Content-Type");
            if (!"application/vnd.moonwaker.discord-audio-pcm;".concat(
                    "format=s16le;rate=48000;channels=2").equalsIgnoreCase(contentType)) {
                throw new IOException("Unsupported Discord audio format.");
            }
            return new DiscordAudioStream(http, http.getInputStream());
        } catch (GeneralSecurityException error) {
            if (http != null) http.disconnect();
            throw new IOException("Unable to initialize gateway TLS.", error);
        } catch (IOException | RuntimeException error) {
            if (http != null) http.disconnect();
            throw error;
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
        return new PairingResponse(response, fingerprint);
    }

    Map<String, String> buildRequestHeaders(GatewayConnection connection, boolean post,
                                            boolean pairing) {
        return buildRequestHeaders(connection, post, pairing, effectiveRequestId(null));
    }

    Map<String, String> buildRequestHeaders(GatewayConnection connection, boolean post,
                                            boolean pairing, String requestId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Connection", "close");
        if (!pairing) {
            headers.put("Authorization", "Bearer " + connection.token());
            headers.put("X-WakePlay-Profile", connection.profileId());
            headers.put("X-MoonWaker-Profile-Session", PROFILE_SESSION_ID);
            headers.put("X-MoonWaker-Capabilities", "child_profiles_v1");
        }
        if (post) {
            headers.put("Content-Type", "application/json; charset=utf-8");
        }
        headers.put("X-Request-Id", requestId);
        return headers;
    }

    static JSONObject decodeJsonResponse(int status, byte[] raw) throws IOException {
        return requireSuccess(status, parseJson(status, raw));
    }

    static JSONObject parseJson(int status, byte[] raw) throws GatewayException {
        try {
            return raw.length == 0 ? new JSONObject() :
                    new JSONObject(new String(raw, StandardCharsets.UTF_8));
        } catch (JSONException error) {
            throw new GatewayException("Invalid response from host gateway.", status);
        }
    }

    /**
     * A known host state answered as an HTTP status, not a request failure: the Gateway
     * answers 409 on Discord routes while Discord is not ready on the host.
     */
    static boolean isDiscordNotReady(String path, int status) {
        return status == HttpURLConnection.HTTP_CONFLICT
                && diagnosticRoute(path).startsWith("/api/v1/discord/");
    }

    static JSONObject requireSuccess(int status, JSONObject response) throws GatewayException {
        if (status < HttpURLConnection.HTTP_OK || status >= 300) {
            throw new GatewayException(
                    response.optString("error", response.optString("reason",
                            "Host gateway request failed.")), status,
                    Math.max(0, response.optInt("retry_after_seconds", 0)), response);
        }
        return response;
    }

    static byte[] readBounded(InputStream input, int limit) throws IOException {
        return readBounded(input, limit, null);
    }

    static byte[] readBounded(InputStream input, int limit, int[] bytesRead)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (bytesRead != null) bytesRead[0] = total;
            if (total > limit) throw new ResponseTooLargeException(limit);
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    static NetworkDownloadSample readNetworkDownload(InputStream input, int expectedBytes,
                                                      NanoClock clock)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long startedNanos = clock.nanoTime();
        long total = 0;
        int read;
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Network download interrupted.");
            }
            read = input.read(buffer);
            if (read < 0) break;
            total += read;
            if (total > expectedBytes || total > NETWORK_DOWNLOAD_MAX_BYTES) {
                throw new ResponseTooLargeException(expectedBytes);
            }
        }
        long elapsedNanos = Math.max(1L, clock.nanoTime() - startedNanos);
        if (total != expectedBytes) {
            throw new IOException("Truncated network download response.");
        }
        return new NetworkDownloadSample(total, elapsedNanos);
    }

    interface NanoClock {
        long nanoTime();
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

    static String networkDownloadPath(int sizeBytes) {
        if (sizeBytes <= 0 || sizeBytes > NETWORK_DOWNLOAD_MAX_BYTES) {
            throw new IllegalArgumentException(
                    "Network download size must be between 1 byte and 512 MiB");
        }
        return "/api/v1/diagnostics/network/download?size=" + sizeBytes;
    }

    private JSONObject requestJson(String endpoint, String path, JSONObject body,
                                   int readTimeoutMs, GatewayConnection connection,
                                   GatewayTrustManager trustManager, String requestId)
            throws IOException {
        String effectiveRequestId = effectiveRequestId(requestId);
        boolean post = body != null;
        String method = post ? "POST" : "GET";
        long startedNanos = System.nanoTime();
        if (post) {
            recordRequest("request.started", method, path, effectiveRequestId, connection,
                    0, startedNanos, null);
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpsURLConnection http = null;
            int status = 0;
            long responseContentLength = -1;
            int[] responseBytesRead = {0};
            String phase = "open";
            try {
                http = open(endpoint, path, trustManager, readTimeoutMs);
                http.setRequestMethod(method);
                applyHeaders(http, buildRequestHeaders(
                        connection, post, connection == null, effectiveRequestId));
                if (post) {
                    byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                    http.setDoOutput(true);
                    http.setFixedLengthStreamingMode(payload.length);
                    try (OutputStream output = http.getOutputStream()) {
                        output.write(payload);
                    }
                }
                phase = "response_code";
                status = http.getResponseCode();
                responseContentLength = http.getContentLengthLong();
                phase = "response_stream";
                InputStream input = status >= 400 ? http.getErrorStream() : http.getInputStream();
                phase = "response_body";
                byte[] raw = input != null
                        ? readAndClose(input, JSON_LIMIT, responseBytesRead) : new byte[0];
                phase = "json_decode";
                JSONObject response = parseJson(status, raw);
                phase = "http_status";
                requireSuccess(status, response);
                phase = "certificate";
                if (connection == null
                        && (trustManager.seenFingerprint == null
                        || trustManager.seenFingerprint.isEmpty())) {
                    throw new GatewayException("The gateway did not present a certificate.", 0);
                }
                if (post) {
                    recordRequest("request.completed", method, path, effectiveRequestId, connection,
                            status, startedNanos, null);
                }
                if (attempt > 0) {
                    recordRequest("request.recovered", method, path, effectiveRequestId, connection,
                            status, startedNanos, null);
                }
                return response;
            } catch (GeneralSecurityException error) {
                IOException wrapped = new IOException("Unable to initialize gateway TLS.", error);
                recordRequest("request.failed", method, path, effectiveRequestId, connection,
                        status, startedNanos, wrapped);
                throw wrapped;
            } catch (IOException | RuntimeException error) {
                boolean threadInterrupted = Thread.currentThread().isInterrupted();
                if (recordCancelled(method, path, effectiveRequestId, connection, status,
                        startedNanos, error, threadInterrupted)) throw error;
                if ("http_status".equals(phase) && isDiscordNotReady(path, status)) {
                    // Closes the request.started trace at INFO: a known host state, not a failure.
                    recordRequest("request.not_ready", method, path, effectiveRequestId,
                            connection, status, startedNanos, null);
                    throw error;
                }
                if (status > 0 && ("response_stream".equals(phase)
                        || "response_body".equals(phase))) {
                    MoonWakerDiagnostics.record("ERROR", "android.gateway",
                            "response_body.failed", "method", method,
                            "route", diagnosticRoute(path),
                            "request_id", effectiveRequestId, "http_status", status,
                            "response_content_length", responseContentLength >= 0
                                    ? responseContentLength : null,
                            "response_body_bytes_read", responseBytesRead[0],
                            "error_type", error.getClass().getName());
                }
                if (attempt == 0 && canRetryTruncatedSuccess(path, body, requestId)
                        && status >= 200 && status < 300 && "response_body".equals(phase)
                        && error instanceof ProtocolException && !threadInterrupted
                        && "unexpected_eof".equals(diagnosticFailureReason(error))) {
                    MoonWakerDiagnostics.record("WARN", "android.gateway", "request.retrying",
                            "route", diagnosticRoute(path), "request_id", effectiveRequestId,
                            "http_status", status, "reason", "unexpected_eof");
                    continue;
                }
                recordRequest("request.failed", method, path, effectiveRequestId, connection,
                        status, startedNanos, error);
                MoonWakerDiagnostics.record("ERROR", "android.gateway", "request.failure_detail",
                        "route", diagnosticRoute(path), "request_id", effectiveRequestId,
                        "stage", phase, "thread_interrupted", threadInterrupted,
                        "reason", diagnosticFailureReason(error),
                        "kind", diagnosticRequestOwner(error));
                throw error;
            } finally {
                if (http != null) http.disconnect();
            }
        }
        throw new AssertionError("Unreachable gateway retry state");
    }

    static boolean canRetryTruncatedSuccess(String path, JSONObject body, String requestId) {
        if (body == null) return true;
        if (requestId == null) return false;
        if ("/api/v1/system/session/ensure".equals(path)) return true;
        return "/api/v1/control/operation/acquire".equals(path)
                && ("reauthorize".equals(body.optString("intent"))
                || "full_quit".equals(body.optString("intent")))
                && requestId.equals(body.optString("operation_id"));
    }

    private static String diagnosticFailureReason(Throwable error) {
        String message = error.getMessage();
        if (message == null) return "no_message";
        String lower = message.toLowerCase(Locale.US);
        if (lower.contains("unexpected end of stream")) return "unexpected_eof";
        if (lower.contains("unexpected status line")) return "status_line";
        if (lower.contains("interrupted")) return "interrupted";
        if (lower.contains("timed out")) return "timeout";
        if (lower.contains("closed")) return "closed";
        if (lower.contains("reset")) return "reset";
        return "other";
    }

    private static String diagnosticRequestOwner(Throwable error) {
        for (StackTraceElement frame : error.getStackTrace()) {
            String owner = frame.getClassName();
            if (owner.startsWith("com.limelight.console.ConsoleActivity")) return "console";
            if (owner.startsWith("com.limelight.console.PlayniteTransitionGateway"))
                return "transition";
            if (owner.equals("com.limelight.Game")) return "stream";
        }
        return "other";
    }

    String effectiveRequestId(String requestId) {
        return requireRequestId(requestId == null ? requestIds.get() : requestId);
    }

    static String diagnosticRoute(String path) {
        if (path == null) return "";
        int end = path.length();
        int query = path.indexOf('?');
        int fragment = path.indexOf('#');
        if (query >= 0) end = Math.min(end, query);
        if (fragment >= 0) end = Math.min(end, fragment);
        return path.substring(0, end);
    }

    /**
     * A worker interrupted by its owner's teardown cancels the request; okhttp may consume
     * the interrupt flag and report only an {@link InterruptedIOException}, so the flag is
     * restored for the caller. A read timeout is a real failure. Returns whether the request
     * was recorded (INFO) as cancelled.
     */
    static boolean recordCancelled(String method, String path, String requestId,
                                   GatewayConnection connection, int httpStatus,
                                   long startedNanos, Throwable error,
                                   boolean threadInterrupted) {
        if (!threadInterrupted && (!(error instanceof InterruptedIOException)
                || error instanceof SocketTimeoutException)) return false;
        Thread.currentThread().interrupt();
        recordRequest("request.cancelled", method, path, requestId, connection,
                httpStatus, startedNanos, error);
        return true;
    }

    private static void recordRequest(String event, String method, String path,
                                      String requestId, GatewayConnection connection,
                                      int httpStatus, long startedNanos, Throwable error) {
        MoonWakerDiagnostics.record(error == null || "request.cancelled".equals(event)
                        ? "INFO" : "ERROR",
                "android.gateway", event,
                "method", method,
                "route", diagnosticRoute(path),
                "request_id", requestId,
                "profile_id", connection == null ? null : connection.profileId(),
                "status", event.substring("request.".length()),
                "http_status", httpStatus > 0 ? httpStatus : null,
                "duration_ms", Math.max(0L, TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - startedNanos)),
                "error_type", error == null ? null : error.getClass().getName());
    }

    static String requireRequestId(String requestId) {
        String value = requestId == null ? "" : requestId.trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("Invalid gateway request ID");
        }
        return value;
    }

    static void requireMicrophoneFrame(byte[] frame) {
        if (frame == null || frame.length != 1920) {
            throw new IllegalArgumentException("A microphone frame must contain 1920 bytes");
        }
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
        return readAndClose(input, limit, null);
    }

    private static byte[] readAndClose(InputStream input, int limit, int[] bytesRead)
            throws IOException {
        try (InputStream stream = input) {
            return readBounded(stream, limit, bytesRead);
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

    public static final class NetworkDownloadSample {
        public final long bytes;
        public final long elapsedNanos;

        private NetworkDownloadSample(long bytes, long elapsedNanos) {
            this.bytes = bytes;
            this.elapsedNanos = elapsedNanos;
        }
    }

    public static final class MicrophoneStream implements Closeable {
        private final HttpsURLConnection http;
        private final OutputStream output;

        private MicrophoneStream(HttpsURLConnection http, OutputStream output) {
            this.http = http;
            this.output = output;
        }

        public void writeFrame(byte[] frame) throws IOException {
            requireMicrophoneFrame(frame);
            output.write(frame);
            output.flush();
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try { output.close(); }
            catch (IOException error) { failure = error; }
            try {
                int status = http.getResponseCode();
                InputStream response = status >= 400 ? http.getErrorStream() : http.getInputStream();
                byte[] raw = response == null ? new byte[0] : readAndClose(response, JSON_LIMIT);
                decodeJsonResponse(status, raw);
            } catch (IOException error) {
                if (failure == null) failure = error;
            } finally { http.disconnect(); }
            if (failure != null) throw failure;
        }
    }

    public static final class DiscordAudioStream implements Closeable {
        private final HttpsURLConnection http;
        private final InputStream input;
        private final AtomicBoolean closed = new AtomicBoolean();

        DiscordAudioStream(HttpsURLConnection http, InputStream input) {
            this.http = http;
            this.input = input;
        }

        public int read(byte[] frame, int offset, int length) throws IOException {
            return input.read(frame, offset, length);
        }

        @Override public void close() throws IOException {
            if (closed.compareAndSet(false, true)) http.disconnect();
        }
    }

    public static final class GatewayException extends IOException {
        private final int statusCode;
        private final int retryAfterSeconds;
        private final JSONObject responseBody;

        public GatewayException(String message, int statusCode) {
            this(message, statusCode, 0, null);
        }

        GatewayException(String message, int statusCode, int retryAfterSeconds) {
            this(message, statusCode, retryAfterSeconds, null);
        }

        GatewayException(String message, int statusCode, int retryAfterSeconds,
                         JSONObject responseBody) {
            super(message);
            this.statusCode = statusCode;
            this.retryAfterSeconds = retryAfterSeconds;
            this.responseBody = responseBody;
        }

        public int statusCode() {
            return statusCode;
        }

        public int retryAfterSeconds() {
            return retryAfterSeconds;
        }

        public JSONObject responseBody() {
            return responseBody;
        }

        public String reason() {
            return responseBody == null ? null : responseBody.optString("reason", null);
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
