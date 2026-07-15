package com.limelight.console;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IntegrationProfileStatusTest {
    @Test public void discordRequiresRpcAndAuthenticationForOnline() {
        assertEquals(IntegrationProfileStatus.ServiceState.ONLINE,
                profile("one", true, true, true, false, false).discordState());
        assertEquals(IntegrationProfileStatus.ServiceState.READY,
                profile("one", true, false, false, false, false).discordState());
        assertEquals(IntegrationProfileStatus.ServiceState.OFFLINE,
                profile("one", false, false, false, false, false).discordState());
    }

    @Test public void bridgeStatesRemainIndependent() {
        IntegrationProfileStatus profile = profile(
                "one", false, false, false, true, true);
        assertEquals(IntegrationProfileStatus.ServiceState.OFFLINE, profile.discordState());
        assertEquals(IntegrationProfileStatus.ServiceState.ONLINE, profile.vibepolloState());
        assertEquals(IntegrationProfileStatus.ServiceState.READY, profile.virtualHereState());
    }

    @Test public void catalogFindsSelectedAndSuggestedProfiles() {
        IntegrationProfileStatus first = profile("one", false, false, false, false, false);
        IntegrationProfileStatus second = profile("two", true, true, true, true, true);
        IntegrationProfileCatalog catalog = new IntegrationProfileCatalog(
                Arrays.asList(first, second), "two");
        assertEquals(first, catalog.find("one"));
        assertTrue(catalog.isSuggested(second));
        assertFalse(catalog.isSuggested(first));
        assertNull(catalog.find("removed"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void catalogIsImmutable() {
        IntegrationProfileCatalog catalog = new IntegrationProfileCatalog(
                Arrays.asList(profile("one", false, false, false, false, false)), "one");
        catalog.profiles.clear();
    }

    private static IntegrationProfileStatus profile(
            String id, boolean bridge, boolean rpc, boolean authenticated,
            boolean vibepollo, boolean virtualHere) {
        return new IntegrationProfileStatus(id, id.toUpperCase(java.util.Locale.ROOT),
                bridge, rpc, authenticated, vibepollo, virtualHere);
    }
}
