package com.limelight.gateway;

import java.util.Locale;

/** Validated, secret-bearing Gateway connection data. Deliberately has no toString(). */
public final class GatewayConnection {
    public static final String DEFAULT_PROFILE_ID = "default";

    private final String endpoint;
    private final String token;
    private final String certificateSha256;
    private final String profileId;

    public GatewayConnection(String endpoint, String token, String certificateSha256,
                             String profileId) {
        this.endpoint = normalizeEndpoint(endpoint);
        this.token = token != null ? token : "";
        this.certificateSha256 = normalizeFingerprint(certificateSha256);
        this.profileId = normalizeProfileId(profileId);
        if (!this.endpoint.startsWith("https://") || this.token.isEmpty() ||
                !this.certificateSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Incomplete or unsafe Gateway connection");
        }
    }

    public String endpoint() {
        return endpoint;
    }

    public String token() {
        return token;
    }

    public String certificateSha256() {
        return certificateSha256;
    }

    public String profileId() {
        return profileId;
    }

    /** Returns request context pinned to the profile chosen for one operation. */
    public GatewayConnection forProfile(String requestedProfileId) {
        String normalized = normalizeProfileId(requestedProfileId);
        return profileId.equals(normalized) ? this
                : new GatewayConnection(endpoint, token, certificateSha256, normalized);
    }

    public static String normalizeFingerprint(String value) {
        return value == null ? "" : value.replace(":", "").trim().toLowerCase(Locale.US);
    }

    public static String normalizeProfileId(String value) {
        String normalized = value == null || value.trim().isEmpty() ?
                DEFAULT_PROFILE_ID : value.trim();
        if (!normalized.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Invalid integration profile ID");
        }
        return normalized;
    }

    private static String normalizeEndpoint(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
