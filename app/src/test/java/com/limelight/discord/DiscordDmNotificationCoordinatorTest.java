package com.limelight.discord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.limelight.console.DiscordDmNotificationCoordinator;

import org.junit.Test;

import java.util.List;

public class DiscordDmNotificationCoordinatorTest {
    @Test
    public void processCoordinatorRegistersOnceWithoutTakingTheConsumerLease() {
        int observersBefore = DiscordSocialClient.messageEventObserverCountForTest();
        DiscordDmNotificationCoordinator first =
                DiscordDmNotificationCoordinator.getInstance();
        int observersAfterFirst = DiscordSocialClient.messageEventObserverCountForTest();
        DiscordDmNotificationCoordinator second =
                DiscordDmNotificationCoordinator.getInstance();
        assertSame(first, second);
        assertTrue(observersAfterFirst == observersBefore
                || observersAfterFirst == observersBefore + 1);
        assertEquals(observersAfterFirst, DiscordSocialClient.messageEventObserverCountForTest());

        DiscordSocialClient.MessageEventLease lease =
                DiscordSocialClient.tryAcquireMessageEventLease();
        assertNotNull(lease);
        try {
            DiscordSocialClient.enqueueMessageEventsForTest(
                    DiscordSocialClient.encodeMessageEventForTest("1", "OVERFLOW"));
            List<DiscordSocialClient.MessageEvent> drained =
                    DiscordSocialClient.drainMessageEvents(lease);
            assertEquals(1, drained.size());
            assertEquals(DiscordSocialClient.MessageEvent.Type.OVERFLOW, drained.get(0).type);
        } finally {
            DiscordSocialClient.releaseMessageEventLease(lease);
        }
    }
}
