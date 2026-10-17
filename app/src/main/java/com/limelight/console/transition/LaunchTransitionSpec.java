package com.limelight.console.transition;

import com.limelight.gateway.GatewayConnection;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class LaunchTransitionSpec {
    private static final AtomicLong SEQUENCE = new AtomicLong();

    public final String id;
    public final String hostId;
    public final String profileId;
    public final LaunchTransitionType type;
    public final int sunshineAppId;
    public final String playniteGameId;
    public final long createdAtMillis;
    public final boolean startProviderBeforeStream;

    public LaunchTransitionSpec(String id, String hostId, LaunchTransitionType type,
                                int sunshineAppId, String playniteGameId,
                                long createdAtMillis) {
        this(id, hostId, GatewayConnection.DEFAULT_PROFILE_ID, type, sunshineAppId,
                playniteGameId, createdAtMillis, false);
    }

    public LaunchTransitionSpec(String id, String hostId, LaunchTransitionType type,
                                int sunshineAppId, String playniteGameId,
                                long createdAtMillis, boolean startProviderBeforeStream) {
        this(id, hostId, GatewayConnection.DEFAULT_PROFILE_ID, type, sunshineAppId,
                playniteGameId, createdAtMillis, startProviderBeforeStream);
    }

    public LaunchTransitionSpec(String id, String hostId, String profileId,
                                LaunchTransitionType type, int sunshineAppId,
                                String playniteGameId, long createdAtMillis,
                                boolean startProviderBeforeStream) {
        this.id = Objects.requireNonNull(id, "id");
        this.hostId = Objects.requireNonNull(hostId, "hostId");
        this.profileId = GatewayConnection.normalizeProfileId(profileId);
        this.type = Objects.requireNonNull(type, "type");
        this.sunshineAppId = sunshineAppId;
        this.playniteGameId = playniteGameId == null ? "" : playniteGameId.trim();
        this.createdAtMillis = createdAtMillis;
        this.startProviderBeforeStream = startProviderBeforeStream;
    }

    public static LaunchTransitionSpec create(String hostId, LaunchTransitionType type,
                                              int sunshineAppId, String playniteGameId,
                                              long nowMillis) {
        long sequence = SEQUENCE.incrementAndGet();
        String id = hostId + ":" + type.name().toLowerCase(java.util.Locale.ROOT) + ":" +
                sunshineAppId + ":" + nowMillis + ":" + sequence;
        return new LaunchTransitionSpec(id, hostId, type, sunshineAppId,
                playniteGameId, nowMillis);
    }

    public static LaunchTransitionSpec create(String hostId, String profileId,
                                              LaunchTransitionType type,
                                              int sunshineAppId, String playniteGameId,
                                              long nowMillis) {
        return create(hostId, profileId, type, sunshineAppId, playniteGameId,
                nowMillis, false);
    }

    public static LaunchTransitionSpec create(String hostId, LaunchTransitionType type,
                                              int sunshineAppId, String playniteGameId,
                                              long nowMillis,
                                              boolean startProviderBeforeStream) {
        long sequence = SEQUENCE.incrementAndGet();
        String id = hostId + ":" + type.name().toLowerCase(java.util.Locale.ROOT) + ":" +
                sunshineAppId + ":" + nowMillis + ":" + sequence;
        return new LaunchTransitionSpec(id, hostId, type, sunshineAppId,
                playniteGameId, nowMillis, startProviderBeforeStream);
    }

    public static LaunchTransitionSpec create(String hostId, String profileId,
                                              LaunchTransitionType type,
                                              int sunshineAppId, String playniteGameId,
                                              long nowMillis,
                                              boolean startProviderBeforeStream) {
        long sequence = SEQUENCE.incrementAndGet();
        String normalizedProfile = GatewayConnection.normalizeProfileId(profileId);
        String id = hostId + ":" + normalizedProfile + ":" +
                type.name().toLowerCase(java.util.Locale.ROOT) + ":" +
                sunshineAppId + ":" + nowMillis + ":" + sequence;
        return new LaunchTransitionSpec(id, hostId, normalizedProfile, type,
                sunshineAppId, playniteGameId, nowMillis, startProviderBeforeStream);
    }
}
