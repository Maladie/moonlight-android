package com.limelight.ui.overlay;

import android.annotation.SuppressLint;

import org.json.JSONArray;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Small, certificate-pinned client for Discord controls exposed by Wake & Play Gateway. */
@SuppressLint({"BadHostnameVerifier", "CustomX509TrustManager"})
public final class DiscordGatewayClient {
    private static final int CONNECT_TIMEOUT_MS = 2500;
    private static final int READ_TIMEOUT_MS = 12000;

    public static final class Connection {
        final String endpoint;
        final String token;
        final String certificateSha256;
        final String profileId;

        public Connection(String endpoint, String token, String certificateSha256) {
            this(endpoint, token, certificateSha256, "default");
        }

        public Connection(String endpoint, String token, String certificateSha256,
                          String profileId) {
            String normalizedEndpoint = trimSlash(endpoint);
            String normalizedCertificate = normalizeFingerprint(certificateSha256);
            String normalizedProfile = profileId == null || profileId.isEmpty() ?
                    "default" : profileId.trim();
            if (!normalizedEndpoint.startsWith("https://") || token == null || token.isEmpty() ||
                    !normalizedCertificate.matches("[0-9a-f]{64}") ||
                    !normalizedProfile.matches("[A-Za-z0-9._-]{1,64}")) {
                throw new IllegalArgumentException("Invalid host gateway connection");
            }
            this.endpoint = normalizedEndpoint;
            this.token = token;
            this.certificateSha256 = normalizedCertificate;
            this.profileId = normalizedProfile;
        }
    }

    public static final class Participant {
        public final String id;
        public final String name;
        public final int volume;
        public final boolean muted;
        public final boolean speaking;
        public final boolean self;

        Participant(String id, String name, int volume, boolean muted,
                    boolean speaking, boolean self) {
            this.id = id;
            this.name = name;
            this.volume = volume;
            this.muted = muted;
            this.speaking = speaking;
            this.self = self;
        }
    }

    public static final class VoiceState {
        public final boolean connected;
        public final String channelName;
        public final boolean muted;
        public final boolean deafened;
        public final List<Participant> participants;

        VoiceState(boolean connected, String channelName, boolean muted, boolean deafened,
                   List<Participant> participants) {
            this.connected = connected;
            this.channelName = channelName;
            this.muted = muted;
            this.deafened = deafened;
            this.participants = Collections.unmodifiableList(participants);
        }
    }

    private static final HostnameVerifier PINNED_HOSTNAME_VERIFIER =
            new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    // The generated gateway certificate is identified by its leaf pin.
                    return true;
                }
            };

    public VoiceState getVoice(Connection connection, boolean force) throws IOException {
        JSONObject response = request(connection,
                "/api/v1/discord/voice" + (force ? "?force=true" : ""), "GET", null);
        JSONObject voice = response.optJSONObject("voice");
        if (voice == null) voice = new JSONObject();
        JSONObject channel = voice.optJSONObject("channel");
        JSONArray values = voice.optJSONArray("participants");
        List<Participant> participants = new ArrayList<>();
        if (values != null) {
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) continue;
                String id = value.optString("id", "");
                if (!id.matches("[0-9]{5,32}")) continue;
                participants.add(new Participant(
                        id,
                        value.optString("name", "Discord user"),
                        Math.max(0, Math.min(200, value.optInt("volume", 100))),
                        value.optBoolean("muted", false),
                        value.optBoolean("speaking", false),
                        value.optBoolean("is_self", false)));
            }
        }
        return new VoiceState(
                voice.optBoolean("connected", false),
                channel != null ? channel.optString("name", "") : "",
                voice.optBoolean("mute", false),
                voice.optBoolean("deafen", false),
                participants);
    }

    public void toggleMute(Connection connection) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("value", "toggle");
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        request(connection, "/api/v1/discord/mute", "POST", body);
    }

    public void leaveVoice(Connection connection) throws IOException {
        request(connection, "/api/v1/discord/leave", "POST", new JSONObject());
    }

    private JSONObject request(Connection connection, String path, String method,
                               JSONObject body) throws IOException {
        HttpsURLConnection http = null;
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{
                    new PinnedTrustManager(connection.certificateSha256)}, null);
            http = (HttpsURLConnection) new URL(connection.endpoint + path).openConnection();
            http.setSSLSocketFactory(sslContext.getSocketFactory());
            http.setHostnameVerifier(PINNED_HOSTNAME_VERIFIER);
            http.setConnectTimeout(CONNECT_TIMEOUT_MS);
            http.setReadTimeout(READ_TIMEOUT_MS);
            http.setRequestMethod(method);
            http.setRequestProperty("Accept", "application/json");
            http.setRequestProperty("Authorization", "Bearer " + connection.token);
            http.setRequestProperty("X-WakePlay-Profile", connection.profileId);
            http.setRequestProperty("Connection", "close");
            if ("POST".equals(method)) {
                byte[] payload = (body != null ? body : new JSONObject()).toString()
                        .getBytes(StandardCharsets.UTF_8);
                http.setDoOutput(true);
                http.setFixedLengthStreamingMode(payload.length);
                http.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                http.setRequestProperty("X-Request-Id", UUID.randomUUID().toString());
                try (OutputStream output = http.getOutputStream()) {
                    output.write(payload);
                }
            }

            int status = http.getResponseCode();
            InputStream input = status >= 400 ? http.getErrorStream() : http.getInputStream();
            String raw = input != null ? readUtf8(input) : "";
            JSONObject response;
            try {
                response = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
            } catch (JSONException error) {
                throw new IOException("Invalid response from Host Gateway.", error);
            }
            if (status < HttpURLConnection.HTTP_OK || status >= 300) {
                throw new IOException(response.optString("error", "Host Gateway request failed."));
            }
            return response;
        } catch (GeneralSecurityException error) {
            throw new IOException("Unable to initialize Host Gateway TLS.", error);
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            int total = 0;
            while ((read = stream.read(buffer)) >= 0) {
                total += read;
                if (total > 1024 * 1024) throw new IOException("Gateway response is too large.");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String fingerprint(X509Certificate certificate) throws CertificateException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.US, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (GeneralSecurityException error) {
            throw new CertificateException(error);
        }
    }

    private static String normalizeFingerprint(String value) {
        return value == null ? "" : value.replace(":", "").trim().toLowerCase(Locale.US);
    }

    private static String trimSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static final class PinnedTrustManager implements X509TrustManager {
        private final String expectedFingerprint;

        PinnedTrustManager(String expectedFingerprint) {
            this.expectedFingerprint = expectedFingerprint;
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
                throw new CertificateException("Missing gateway certificate.");
            }
            String actual = fingerprint(chain[0]);
            if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                    expectedFingerprint.getBytes(StandardCharsets.US_ASCII))) {
                throw new CertificateException("Host Gateway certificate changed. Pair the host again.");
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
