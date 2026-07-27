package com.limelight.console.transition;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class LaunchTransitionSpec {
    private static final AtomicLong SEQUENCE = new AtomicLong();

    public final String id;
    public final String hostId;
    public final LaunchTransitionType type;
    public final int sunshineAppId;
    public final String playniteGameId;
    public final long createdAtMillis;

    public LaunchTransitionSpec(String id, String hostId, LaunchTransitionType type,
                                int sunshineAppId, String playniteGameId,
                                long createdAtMillis) {
        this.id = Objects.requireNonNull(id, "id");
        this.hostId = Objects.requireNonNull(hostId, "hostId");
        this.type = Objects.requireNonNull(type, "type");
        this.sunshineAppId = sunshineAppId;
        this.playniteGameId = playniteGameId == null
                ? "" : playniteGameId.toLowerCase(Locale.ROOT);
        this.createdAtMillis = createdAtMillis;
    }

    public static LaunchTransitionSpec create(String hostId, LaunchTransitionType type,
                                              int sunshineAppId, String playniteGameId,
                                              long nowMillis) {
        long sequence = SEQUENCE.incrementAndGet();
        String id = hostId + ":" + type.name().toLowerCase(Locale.ROOT) + ":" +
                sunshineAppId + ":" + nowMillis + ":" + sequence;
        return new LaunchTransitionSpec(id, hostId, type, sunshineAppId,
                playniteGameId, nowMillis);
    }
}
