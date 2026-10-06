package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.limelight.R;

import org.junit.Test;

public class SessionFailureControlRejectionTest {
    @Test public void gatewayControlReasonsMapToTheirOwnReasonCodes() {
        assertEquals(SessionFailure.CONTROL_STREAM_CONFLICT,
                SessionFailure.fromControlRejection("managed_stream_conflict"));
        assertEquals(SessionFailure.CONTROL_PREVIOUS_GAME_UNCONFIRMED,
                SessionFailure.fromControlRejection("retained_process_unconfirmed"));
        assertEquals(SessionFailure.CONTROL_PREVIOUS_GAME_UNCONFIRMED,
                SessionFailure.fromControlRejection("retained_owner_unconfirmed"));
        assertEquals(SessionFailure.CONTROL_STATE_CHANGED,
                SessionFailure.fromControlRejection("stale_operation"));
        assertEquals(SessionFailure.CONTROL_STATE_CHANGED,
                SessionFailure.fromControlRejection("stale_session"));
    }

    @Test public void unknownOrMissingReasonIsTheGenericRejectionNotErrorZero() {
        assertEquals(SessionFailure.CONTROL_REJECTED, SessionFailure.fromControlRejection(null));
        assertEquals(SessionFailure.CONTROL_REJECTED, SessionFailure.fromControlRejection(""));
        assertEquals(SessionFailure.CONTROL_REJECTED,
                SessionFailure.fromControlRejection("operation_in_progress"));
        assertNotEquals(R.string.conn_error_msg, SessionFailure.CONTROL_REJECTED.details);
    }
}
