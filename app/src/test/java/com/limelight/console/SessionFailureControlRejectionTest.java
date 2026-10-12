package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

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
                SessionFailure.fromControlRejection("unmanaged_stream"));
        // Another owner's operation that is simply running: try again, no hard close offered.
        assertEquals(SessionFailure.CONTROL_REJECTED,
                SessionFailure.fromControlRejection("operation_in_progress"));
        assertNotEquals(R.string.conn_error_msg, SessionFailure.CONTROL_REJECTED.details);
    }

    @Test public void uncertainHostOperationHasItsOwnCodeAndOffersTheHardClose()
            throws Exception {
        // Every reason the Gateway gives an uncertain permit (and its reconcile refusal).
        for (String reason : new String[] {"terminal_result_timeout",
                "terminal_owner_required", "reconcile_required", "effect_result_uncertain",
                "start_abandoned", "reauthorization_fence_unconfirmed",
                "gateway_restarted_before_terminal_result"}) {
            assertEquals(reason, SessionFailure.CONTROL_OPERATION_PENDING,
                    SessionFailure.fromControlRejection(reason));
        }
        assertEquals("control.operation_pending", SessionFailure.CONTROL_OPERATION_PENDING.code);
        assertNotEquals(SessionFailure.CONTROL_REJECTED.details,
                SessionFailure.CONTROL_OPERATION_PENDING.details);
        String game = source("Game.java");
        String failed = game.substring(game.indexOf("private void stageFailed("),
                game.indexOf("private static int streamStageCode("));
        int pending = failed.indexOf("rejection == SessionFailure.CONTROL_OPERATION_PENDING");
        assertTrue(pending > 0);
        assertTrue(failed.indexOf("R.string.console_hard_terminate_session", pending) > 0);
        assertTrue(failed.indexOf("ConsoleActivity.offerHardTerminate(hostId)", pending) > 0);
        String console = source("console/ConsoleActivity.java");
        String resume = console.substring(console.indexOf("protected void onResume()"));
        String offer = resume.substring(0, resume.indexOf("refreshChildBindingStates();"))
                .replace("\r\n", "\n");
        // Honoured only at once, on that host's own screen, never over a gate or the picker.
        assertTrue(offer.contains("- hardTerminateOfferAt <= HARD_TERMINATE_OFFER_WINDOW_MS) {"));
        int shown = offer.indexOf("if (hardTerminateOffer.equalsIgnoreCase(selectedHostUuid)\n"
                + "                        && !hostSelectionVisible && profileGateHostUuid == null) {");
        assertTrue(shown > 0 && shown < offer.indexOf(
                "confirmHardTerminateSession(currentHost(hardTerminateOffer))"));
        assertTrue(console.contains("HARD_TERMINATE_OFFER_WINDOW_MS = 10_000L;"));
        assertTrue(console.contains("hardTerminateOfferAt = SystemClock.uptimeMillis();"));
        for (String strings : new String[] {"values", "values-pl"}) {
            assertTrue(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                    "src/main/res/" + strings + "/strings.xml")),
                    java.nio.charset.StandardCharsets.UTF_8)
                    .contains("<string name=\"control_operation_pending\">"));
        }
    }

    private static String source(String path) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/com/limelight/" + path)),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
