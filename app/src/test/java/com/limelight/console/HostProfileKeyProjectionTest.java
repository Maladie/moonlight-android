package com.limelight.console;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HostProfileKeyProjectionTest {
    @Test
    public void delayedParentProjectionCannotRenderOrRequestArtworkAfterChildSwitch() {
        HostProfileKey parent = new HostProfileKey("HOST-1", "parent");
        HostProfileKey child = new HostProfileKey("host-1", "child-a");
        AtomicInteger renderedTiles = new AtomicInteger();
        AtomicInteger artworkRequests = new AtomicInteger();

        if (HostProfileKey.isCurrentProjection(parent, parent, child)) {
            renderedTiles.incrementAndGet();
            artworkRequests.incrementAndGet();
        }

        assertFalse(HostProfileKey.isCurrentProjection(parent, parent, child));
        assertTrue(HostProfileKey.isCurrentProjection(child, child,
                new HostProfileKey("HOST-1", "child-a")));
        assertTrue(renderedTiles.get() == 0);
        assertTrue(artworkRequests.get() == 0);
    }

    @Test
    public void projectionMustMatchBothHostAndActorExactly() {
        HostProfileKey expected = new HostProfileKey("host-1", "child-a");

        assertFalse(HostProfileKey.isCurrentProjection(expected,
                new HostProfileKey("host-2", "child-a"), expected));
        assertFalse(HostProfileKey.isCurrentProjection(expected,
                new HostProfileKey("host-1", "parent"), expected));
        assertFalse(HostProfileKey.isCurrentProjection(expected, expected,
                new HostProfileKey("host-1", "child-b")));
    }
}
