package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConsoleLibraryStatusTest {
    @Test
    public void emptyRefreshTakesPriorityOverOldError() {
        assertEquals(ConsoleLibraryStatus.State.REFRESHING,
                ConsoleLibraryStatus.resolve(true, false,
                        PlayniteLibraryRepository.ErrorKind.TIMEOUT, true));
    }

    @Test
    public void authenticationDistinguishesMissingGateway() {
        assertEquals(ConsoleLibraryStatus.State.GATEWAY_NOT_CONFIGURED,
                ConsoleLibraryStatus.resolve(false, true,
                        PlayniteLibraryRepository.ErrorKind.AUTHENTICATION, false));
        assertEquals(ConsoleLibraryStatus.State.AUTHENTICATION_ERROR,
                ConsoleLibraryStatus.resolve(false, true,
                        PlayniteLibraryRepository.ErrorKind.AUTHENTICATION, true));
    }

    @Test
    public void currentIsNotAnError() {
        assertFalse(ConsoleLibraryStatus.isError(ConsoleLibraryStatus.State.CURRENT));
        assertFalse(ConsoleLibraryStatus.isError(ConsoleLibraryStatus.State.REFRESHING));
        assertTrue(ConsoleLibraryStatus.isError(ConsoleLibraryStatus.State.TIMEOUT));
    }

    @Test
    public void populatedRefreshKeepsLastKnownStatusAndReportsErrors() {
        assertEquals(ConsoleLibraryStatus.State.CURRENT,
                ConsoleLibraryStatus.resolve(true, true, null, true));
        assertEquals(ConsoleLibraryStatus.State.SERVER_ERROR,
                ConsoleLibraryStatus.resolve(true, true,
                        PlayniteLibraryRepository.ErrorKind.SERVER, true));
    }

    /** A library restored from cache on return from a stream does not push the carousel. */
    @Test
    public void cachedLibraryAwaitingRefreshShowsNoStatusLine() {
        assertFalse(ConsoleLibraryStatus.visible(
                ConsoleLibraryStatus.resolve(false, true, null, true)));
        assertFalse(ConsoleLibraryStatus.visible(
                ConsoleLibraryStatus.resolve(true, true, null, true)));
        assertTrue(ConsoleLibraryStatus.visible(
                ConsoleLibraryStatus.resolve(true, false, null, true)));
        assertTrue(ConsoleLibraryStatus.visible(ConsoleLibraryStatus.State.TIMEOUT));
    }
}
