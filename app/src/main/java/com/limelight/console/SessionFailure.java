package com.limelight.console;

import com.limelight.R;
import com.limelight.diagnostics.MoonWakerDiagnostics;

import java.io.IOException;

/**
 * One stable reason code per launch, reuse or close path that ends on a session-failure
 * screen. The code is shown on that screen and recorded with its correlation, so a report
 * from the TV names exactly one path.
 */
enum SessionFailure {
    // ConsoleActivity closePreviousSession, before any path is chosen.
    CLOSE_HOST_UNAVAILABLE("close.host_unavailable",
            R.string.applist_quit_fail, R.string.console_previous_session_close_failed),
    // path=retained-live: the live retained transport reported an unsuccessful termination.
    CLOSE_RETAINED_NOT_CLOSED("close.retained_not_closed",
            R.string.applist_quit_fail, R.string.console_previous_session_close_failed),
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
    // ConsoleActivity activatePlayniteItem: no launch target could be chosen for the game.
    LAUNCH_TARGET_UNRESOLVED("launch.target_unresolved",
            R.string.console_session_uncertain_title, R.string.console_session_uncertain_details),

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

    // ConsoleActivity beginInstallationSupportStream (does not use the orchestrator).
    SUPPORT_PREVIOUS_NOT_CLOSED("support.previous_not_closed",
            R.string.applist_quit_fail, R.string.console_previous_session_close_failed),
    SUPPORT_HOST_NOT_READY("support.host_not_ready",
            R.string.console_host_not_ready, R.string.console_host_timeout);

    final String code;
    final int title;
    final int details;

    SessionFailure(String code, int title, int details) {
        this.code = code;
        this.title = title;
        this.details = details;
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
    static String safeDetail(String detail) {
        return detail != null && detail.matches("[a-z_]{1,64}") ? detail : "";
    }
}
