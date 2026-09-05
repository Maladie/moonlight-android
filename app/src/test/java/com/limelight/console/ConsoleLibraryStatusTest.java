package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConsoleLibraryStatusTest {
    @Test
    public void emptyRefreshTakesPriorityOverOldError() {
        assertEquals(ConsoleLibraryStatus.State.REFRESHING,
                ConsoleLibraryStatus.resolve(true, false, false,
                        PlayniteLibraryRepository.ErrorKind.TIMEOUT, true));
    }

    @Test
    public void authenticationDistinguishesMissingGateway() {
        assertEquals(ConsoleLibraryStatus.State.GATEWAY_NOT_CONFIGURED,
                ConsoleLibraryStatus.resolve(false, true, true,
                        PlayniteLibraryRepository.ErrorKind.AUTHENTICATION, false));
        assertEquals(ConsoleLibraryStatus.State.AUTHENTICATION_ERROR,
                ConsoleLibraryStatus.resolve(false, true, true,
                        PlayniteLibraryRepository.ErrorKind.AUTHENTICATION, true));
    }

    @Test
    public void cachedAndCurrentAreNotErrors() {
        ConsoleLibraryStatus.State cached = ConsoleLibraryStatus.resolve(
                false, true, true, null, true);
        assertEquals(ConsoleLibraryStatus.State.CACHED, cached);
        assertFalse(ConsoleLibraryStatus.isError(cached));
        assertTrue(ConsoleLibraryStatus.isError(ConsoleLibraryStatus.State.TIMEOUT));
    }

    @Test
    public void populatedRefreshKeepsLastKnownStatusAndReportsErrors() {
        assertEquals(ConsoleLibraryStatus.State.CURRENT,
                ConsoleLibraryStatus.resolve(true, true, false, null, true));
        assertEquals(ConsoleLibraryStatus.State.CACHED,
                ConsoleLibraryStatus.resolve(true, true, true, null, true));
        assertEquals(ConsoleLibraryStatus.State.SERVER_ERROR,
                ConsoleLibraryStatus.resolve(true, true, false,
                        PlayniteLibraryRepository.ErrorKind.SERVER, true));
    }
}
