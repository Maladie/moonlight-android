package com.limelight.console;

import java.util.Locale;

/** Validated, secret-bearing Gateway launch data. Deliberately has no toString(). */
final class GatewayConnection {
    static final String DEFAULT_PROFILE_ID = "default";

    final String endpoint;
    final String token;
    final String certificateSha256;
    final String profileId;

    GatewayConnection(String endpoint, String token, String certificateSha256,
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

    static String normalizeProfileId(String value) {
        String normalized = value == null || value.trim().isEmpty() ?
                DEFAULT_PROFILE_ID : value.trim();
        if (!normalized.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Invalid integration profile ID");
        }
        return normalized;
    }

    static String normalizeFingerprint(String value) {
        return value == null ? "" : value.replace(":", "").trim().toLowerCase(Locale.US);
    }

    private static String normalizeEndpoint(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
