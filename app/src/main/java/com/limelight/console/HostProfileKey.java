package com.limelight.console;

import com.limelight.gateway.GatewayConnection;

import java.util.Locale;
import java.util.Objects;

/** Immutable identity for state that belongs to one Windows profile on one host. */
public final class HostProfileKey {
    public final String hostId;
    public final String profileId;
    private final String normalizedHostId;

    public HostProfileKey(String hostId, String profileId) {
        this.hostId = hostId == null ? "" : hostId.trim();
        this.normalizedHostId = this.hostId.toLowerCase(Locale.ROOT);
        this.profileId = GatewayConnection.normalizeProfileId(profileId);
        if (this.hostId.isEmpty()) {
            throw new IllegalArgumentException("Host ID is required");
        }
    }

    @Override public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof HostProfileKey)) return false;
        HostProfileKey other = (HostProfileKey) value;
        return normalizedHostId.equals(other.normalizedHostId)
                && profileId.equals(other.profileId);
    }

    @Override public int hashCode() {
        return Objects.hash(normalizedHostId, profileId);
    }

    public String cacheKey() {
        return normalizedHostId + "__" + profileId;
    }
}
