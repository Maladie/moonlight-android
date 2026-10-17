package com.limelight.console;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CommunityDictationSessionTest {
    @Test public void oneRequestCapturesAndOnlyMatchesItsOriginalConversation() {
        CommunityDictationSession session = new CommunityDictationSession();

        assertTrue(session.begin(42, 7));
        assertFalse(session.begin(42, 8));
        assertTrue(session.matches(42, 7));
        assertFalse(session.matches(43, 7));
        assertFalse(session.matches(42, 8));

        session.finish();
        assertFalse(session.isActive());
        assertFalse(session.matches(42, 7));
    }

    @Test public void invalidConversationCannotStartARequest() {
        CommunityDictationSession session = new CommunityDictationSession();

        assertFalse(session.begin(0, 1));
        assertFalse(session.begin(1, 0));
    }
}
