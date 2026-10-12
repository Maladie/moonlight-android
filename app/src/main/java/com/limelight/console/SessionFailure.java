package com.limelight.console;

import com.limelight.R;
import com.limelight.diagnostics.MoonWakerDiagnostics;

import java.io.IOException;

/**
 * One stable reason code per launch, reuse or close path that ends on a session-failure
 * screen. The code is shown on that screen and recorded with its correlation, so a report
 * from the TV names exactly one path.
 */
public enum SessionFailure {
    // ConsoleActivity closePreviousSession, before any path is chosen.
    CLOSE_HOST_UNAVAILABLE("close.host_unavailable",
            R.string.applist_quit_fail, R.string.console_previous_session_close_failed),
    // path=retained-live: the live retained transport reported an unsuccessful termination.
    CLOSE_RETAINED_NOT_CLOSED("close.retained_not_closed",
            R.string.applist_quit_fail, R.string.console_previous_session_close_failed),
    // path=retained-live or reconnect/direct: the stream closed, but an unverified game may
    // still run on the host; the replacement must not launch.
    CLOSE_GAME_LEFT("close.game_left",
            R.string.applist_quit_fail, R.string.console_terminate_session_game_left),
    // path=retained-live: the coordinator refused to start the termination.
    CLOSE_RETAINED_TERMINATION_UNAVAILABLE("close.retained_termination_unavailable",
            R.string.applist_quit_fail, R.string.console_previous_session_close_failed),
    CLOSE_RETAINED_INTERRUPTED("close.retained_interrupted",
            R.string.applist_quit_fail, R.string.console_previous_session_close_failed),
    // path=retained-live: no termination result within 60 s.
    CLOSE_RETAINED_TIMEOUT("close.retained_timeout",
            R.string.applist_quit_fail, R.string.console_previous_session_close_failed),
    // path=reconnect-fallback: the retained session was not awaiting reconnect any more.
    CLOSE_RETAINED_CHANGED("close.retained_changed_before_close",
            R.string.applist_quit_fail, R.string.console_previous_session_close_failed),
    // path=reconnect-fallback: the host-side control-plane termination did not confirm.
    CLOSE_RECONNECT_FALLBACK_NOT_CLOSED("close.reconnect_fallback_not_closed",
            R.string.applist_quit_fail, R.string.console_previous_session_close_failed),
    // path=direct-nonretained: the host-side control-plane termination did not confirm.
    CLOSE_DIRECT_NOT_CLOSED("close.direct_not_closed",
            R.string.applist_quit_fail, R.string.console_previous_session_close_failed),
    // Any other exception escaping closePreviousSession.
    CLOSE_UNEXPECTED_ERROR("close.unexpected_error",
            R.string.applist_quit_fail, R.string.console_previous_session_close_failed),
    // SessionOrchestrator: close returned CLOSED, but local facts still show another session
    // (live transport, suspended or reconnect record). A missing host observation is not this.
    CLOSE_SNAPSHOT_NOT_IDLE("close.snapshot_not_idle_after_close",
            R.string.applist_quit_fail, R.string.console_previous_session_close_failed),

    // Retained provider switch (closePreviousSession -> switchRetainedProviderGame).
    SWITCH_NOT_ELIGIBLE("switch.not_eligible",
            R.string.applist_quit_fail, R.string.console_previous_session_close_failed),
    SWITCH_REFUSED("switch.refused_not_eligible",
            R.string.applist_quit_fail, R.string.console_previous_session_close_failed),
    SWITCH_INTERRUPTED("switch.interrupted",
            R.string.applist_quit_fail, R.string.console_previous_session_close_failed),
    SWITCH_TIMEOUT("switch.timeout",
            R.string.applist_quit_fail, R.string.console_previous_session_close_failed),
    SWITCH_PROVIDER_STOP_TIMEOUT("switch.provider_stop_timeout",
            R.string.applist_quit_fail, R.string.console_provider_stop_waiting_for_process),
    SWITCH_FAILED("switch.failed",
            R.string.applist_quit_fail, R.string.console_previous_session_close_failed),

    // SessionOrchestrator: the canonical snapshot could not be trusted for this target.
    UNCERTAIN_RECOVERY_REQUIRED("uncertain.recovery_required",
            R.string.console_session_uncertain_title, R.string.console_session_uncertain_details),
    UNCERTAIN_PREPARING_NOT_SWITCHABLE("uncertain.preparing_not_switchable",
            R.string.console_session_uncertain_title, R.string.console_session_uncertain_details),
    UNCERTAIN_PREPARING_REFRESH_FAILED("uncertain.preparing_refresh_failed",
            R.string.console_session_uncertain_title, R.string.console_session_uncertain_details),
    UNCERTAIN_PREPARING_NOT_SWITCHABLE_AFTER_REFRESH(
            "uncertain.preparing_not_switchable_after_refresh",
            R.string.console_session_uncertain_title, R.string.console_session_uncertain_details),
    UNCERTAIN_REFRESH_FAILED("uncertain.refresh_failed",
            R.string.console_session_uncertain_title, R.string.console_session_uncertain_details),
    UNCERTAIN_AFTER_REFRESH("uncertain.still_uncertain_after_refresh",
            R.string.console_session_uncertain_title, R.string.console_session_uncertain_details),
    // The session began closing after the loading layer was raised for this request.
    UNCERTAIN_TERMINATING("uncertain.terminating",
            R.string.console_status_closing_session, R.string.console_session_uncertain_details),

    // SessionOrchestrator awaitHost: the woken host never answered within the wake bound.
    HOST_WAKE_TIMEOUT("host.wake_timeout",
            R.string.console_host_not_ready, R.string.console_host_timeout),
    // SessionOrchestrator awaitHost: the host answered, its Gateway did not within its bound.
    HOST_GATEWAY_TIMEOUT("host.gateway_timeout",
            R.string.console_host_not_ready, R.string.transition_gateway_unavailable),

    ORCHESTRATION_PASS_LIMIT("orchestration.pass_limit",
            R.string.console_host_not_ready, R.string.console_host_timeout),
    ORCHESTRATION_EXECUTOR_REJECTED("orchestration.executor_rejected",
            R.string.console_host_not_ready, R.string.console_host_timeout),

    // ConsoleActivity attachPresentationForSwitch: nothing was closed on these paths.
    ATTACH_RESUME_INTENT_MISSING("attach.resume_intent_missing",
            R.string.console_presentation_launch_failed_title,
            R.string.console_presentation_launch_failed_details),
    ATTACH_START_ACTIVITY_FAILED("attach.start_activity_failed",
            R.string.console_presentation_launch_failed_title,
            R.string.console_presentation_launch_failed_details),

    // ConsoleActivity reconnectSavedSessionForActor, same actor: the saved session could not be
    // resumed; detail names the refusal (no record, record or retained mismatch, no intent).
    RECONNECT_SAVED_SESSION_UNAVAILABLE("reconnect.saved_session_unavailable",
            R.string.console_presentation_launch_failed_title,
            R.string.console_reconnect_saved_session_unavailable),
    // ConsoleActivity reconnectSavedSessionForActor, actor handoff: failed after compensation.
    RECONNECT_ACTOR_HANDOFF_FAILED("reconnect.actor_handoff_failed",
            R.string.console_presentation_launch_failed_title,
            R.string.console_reconnect_actor_handoff_failed),

    // ConsoleActivity beginInstallationSupportStream (does not use the orchestrator).
    SUPPORT_PREVIOUS_NOT_CLOSED("support.previous_not_closed",
            R.string.applist_quit_fail, R.string.console_previous_session_close_failed),
    SUPPORT_HOST_NOT_READY("support.host_not_ready",
            R.string.console_host_not_ready, R.string.console_host_timeout),

    // Game stream start: a Gateway control operation (acquire, reconcile or complete) answered HTTP 409.
    CONTROL_STREAM_CONFLICT("control.managed_stream_conflict",
            R.string.conn_error_title, R.string.control_stream_conflict),
    CONTROL_PREVIOUS_GAME_UNCONFIRMED("control.previous_game_unconfirmed",
            R.string.conn_error_title, R.string.control_previous_game_unconfirmed),
    CONTROL_STATE_CHANGED("control.state_changed",
            R.string.conn_error_title, R.string.control_state_changed),
    // A control operation on the host is uncertain (its holder went silent, abandoned it or
    // could not confirm its effect) and the host could not end it by itself; the stream error
    // screen offers the host panel's hard close next to this message. An operation that is
    // simply running (operation_in_progress) is an ordinary "try again in a moment".
    CONTROL_OPERATION_PENDING("control.operation_pending",
            R.string.conn_error_title, R.string.control_operation_pending),
    CONTROL_REJECTED("control.rejected",
            R.string.conn_error_title, R.string.control_rejected);

    public final String code;
    public final int title;
    public final int details;

    SessionFailure(String code, int title, int details) {
        this.code = code;
        this.title = title;
        this.details = details;
    }

    /** Maps the Gateway reason of an HTTP 409 control-operation response to its failure. */
    public static SessionFailure fromControlRejection(String reason) {
        String value = reason == null ? "" : reason;
        switch (value) {
            case "managed_stream_conflict": return CONTROL_STREAM_CONFLICT;
            case "retained_process_unconfirmed":
            case "retained_owner_unconfirmed": return CONTROL_PREVIOUS_GAME_UNCONFIRMED;
            case "stale_operation":
            case "stale_session": return CONTROL_STATE_CHANGED;
            case "terminal_result_timeout":
            case "terminal_owner_required":
            case "reconcile_required":
            case "effect_result_uncertain":
            case "start_abandoned":
            case "reauthorization_fence_unconfirmed":
            case "gateway_restarted_before_terminal_result": return CONTROL_OPERATION_PENDING;
            default: return CONTROL_REJECTED;
        }
    }

    /** Carries the exact close path to the orchestrator instead of message text. */
    static final class Raised extends IOException {
        final SessionFailure reason;
        final String detail;

        Raised(SessionFailure reason, String detail) {
            super(reason.code);
            this.reason = reason;
            this.detail = detail == null ? "" : detail;
        }
    }

    Raised raise() { return new Raised(this, ""); }

    /** Classifies the failure reason reported by the attached stream for a retained switch. */
    static Raised switchFailed(String reason) {
        String value = reason == null ? "" : reason;
        SessionFailure failure = "retained_switch_not_eligible".equals(value) ? SWITCH_REFUSED
                : value.contains("game_stop_timeout") ? SWITCH_PROVIDER_STOP_TIMEOUT
                : SWITCH_FAILED;
        return new Raised(failure, value);
    }

    /** Records this failure with its correlation and the session state at that moment. */
    void record(long orchestrationId, String hostId, String profileId,
                SessionSnapshot snapshot, String detail) {
        MoonWakerDiagnostics.record("WARN", "android.session", "session.failed",
                "reason", code, "orchestration_id", orchestrationId,
                "host_id", hostId, "profile_id", profileId,
                "state", snapshot.state.name(),
                "observation_state", snapshot.observationState.name(),
                "known_live", snapshot.retainedTransport,
                "app_id", snapshot.hostGameAppId, "game_id", snapshot.playniteGameId,
                "detail", safeDetail(detail));
    }

    /** Keeps only a lowercase reason token; free text could carry identifiers or secrets. */
    public static String safeDetail(String detail) {
        return detail != null && detail.matches("[a-z_]{1,64}") ? detail : "";
    }
}
