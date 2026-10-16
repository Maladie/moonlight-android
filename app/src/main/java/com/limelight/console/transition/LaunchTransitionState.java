package com.limelight.console.transition;

public enum LaunchTransitionState {
    IDLE,
    PREPARING_SESSION,
    CONNECTING_STREAM,
    WAITING_FOR_VIDEO_SURFACE,
    PLAYNITE_STARTING,
    PLAYNITE_PROCESS_RUNNING,
    PLAYNITE_FULLSCREEN_STARTING,
    PLAYNITE_FULLSCREEN_READY,
    /** The host updates the game before it starts it: no timeout, no soft notice. */
    GAME_UPDATING,
    GAME_START_REQUESTED,
    GAME_STARTING,
    GAME_PROCESS_RUNNING,
    GAME_WINDOW_STABILIZING,
    HOST_SESSION_LOCKED,
    LAUNCHER_INTERACTION_REQUIRED,
    /** A stale earlier launch's game is running instead of the requested one. */
    PREVIOUS_GAME_RUNNING,
    GAME_READY,
    GAME_RUNNING,
    GAME_STOPPING,
    PLAYNITE_RETURNING,
    PLAYNITE_STOPPING,
    CLOSING_STREAM,
    RETURNING_TO_DASHBOARD,
    CANCELLED,
    TIMED_OUT,
    ERROR
}
