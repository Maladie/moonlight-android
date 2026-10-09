package com.limelight.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SessionModelForgetHostTest {
    private static final String HOST = "11111111-2222-3333-4444-555555555555";
    private static final String OTHER = "99999999-8888-7777-6666-555555555555";

    private final SessionModelDriver m = new SessionModelDriver();

    @Test public void forgettingAHostResetsItsSessionAndDropsEveryRecordOfIt() {
        SessionModelTest.FakeController controller = new SessionModelTest.FakeController();
        m.enterHome(controller, "session-a", HOST, 7, "game");
        m.resume.values.put("pending", true);
        m.resume.values.put("pcUuid", HOST.toUpperCase());
        m.resume.values.put("streamSessionId", "session-a");
        m.suspended.values.put(HOST + ".profile.default", "{}");
        m.suspended.values.put("ended_at." + HOST + ".profile.default", 1L);
        m.suspended.values.put("return_to_host_selection", HOST);
        m.suspended.values.put(OTHER + ".profile.default", "{}");
        m.sleep.values.put(HOST, "1:0");
        m.sleep.values.put(OTHER, "2:0");

        assertTrue(m.call(new SessionEvent.HostForgotten(HOST.toUpperCase())));

        assertEquals(SessionModel.State.NONE, m.state());
        assertTrue(controller.disconnected);
        assertTrue(m.resume.values.isEmpty());
        assertNull(m.model.records().pending);
        assertEquals(1, m.suspended.values.size());
        assertTrue(m.suspended.values.containsKey(OTHER + ".profile.default"));
        assertEquals(1, m.sleep.values.size());
        assertTrue(m.sleep.values.containsKey(OTHER));
    }

    @Test public void anotherHostsSessionAndReconnectSurvive() {
        SessionModelTest.FakeController controller = new SessionModelTest.FakeController();
        m.enterHome(controller, "session-b", OTHER, 7, "game");
        m.resume.values.put("pending", true);
        m.resume.values.put("pcUuid", OTHER);

        m.call(new SessionEvent.HostForgotten(HOST));

        assertEquals(SessionModel.State.HOME_LIVE, m.state());
        assertFalse(controller.disconnected);
        assertEquals(OTHER, m.resume.values.get("pcUuid"));
    }
}
