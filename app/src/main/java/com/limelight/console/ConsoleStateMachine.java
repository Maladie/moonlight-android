package com.limelight.console;

import java.util.Objects;

/** Pure state owner for console visibility, input capture, and Back routing. */
public final class ConsoleStateMachine {
    public enum State {
        HOME, CONNECTING, STREAM, CONSOLE_OVER_STREAM, OVERLAY, RECOVERY, DISCONNECTING
    }

    public enum Event {
        LAUNCH, CONNECTED, CONNECTION_FAILED, READINESS_TIMEOUT, OPEN_CONSOLE,
        RETURN_TO_STREAM, OPEN_OVERLAY, CLOSE_OVERLAY, RETRY, HOME, REVEAL_STREAM,
        DISCONNECT, DISCONNECTED, RECONNECT, BACK
    }

    public enum InputTarget { NONE, HOME, GAMEPLAY, OVERLAY, RECOVERY }

    public enum Effect { NONE, SHOW_EXIT_CONFIRMATION }

    public static final class Transition {
        public final State previous;
        public final State current;
        public final InputTarget inputTarget;
        public final Effect effect;

        private Transition(State previous, State current, Effect effect) {
            this.previous = previous;
            this.current = current;
            this.inputTarget = inputTarget(current);
            this.effect = effect;
        }

        public boolean capturesGameplayInput() {
            return inputTarget == InputTarget.GAMEPLAY;
        }
    }

    private State state;
    private boolean sessionActive;

    public ConsoleStateMachine() {
        this(State.HOME, false);
    }

    public ConsoleStateMachine(State initialState, boolean sessionActive) {
        this.state = Objects.requireNonNull(initialState, "initialState");
        this.sessionActive = sessionActive;
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized Transition dispatch(Event event) {
        Objects.requireNonNull(event, "event");
        State previous = state;
        Effect effect = Effect.NONE;

        switch (state) {
            case HOME:
                if (event == Event.LAUNCH) state = State.CONNECTING;
                else if (event == Event.CONNECTED) {
                    // Reattach to a session discovered after Activity recreation.
                    sessionActive = true;
                    state = State.CONSOLE_OVER_STREAM;
                }
                else if (event == Event.BACK) effect = Effect.SHOW_EXIT_CONFIRMATION;
                break;
            case CONNECTING:
                if (event == Event.CONNECTED) {
                    sessionActive = true;
                    state = State.STREAM;
                } else if (event == Event.CONNECTION_FAILED || event == Event.READINESS_TIMEOUT) {
                    state = State.RECOVERY;
                } else if (event == Event.BACK || event == Event.HOME) {
                    state = sessionActive ? State.CONSOLE_OVER_STREAM : State.HOME;
                } else if (event == Event.DISCONNECT) {
                    state = State.DISCONNECTING;
                }
                break;
            case STREAM:
                if (event == Event.RECONNECT) state = State.CONNECTING;
                else if (event == Event.BACK || event == Event.OPEN_CONSOLE || event == Event.HOME) {
                    state = State.CONSOLE_OVER_STREAM;
                } else if (event == Event.OPEN_OVERLAY) {
                    state = State.OVERLAY;
                } else if (event == Event.CONNECTION_FAILED) {
                    state = State.RECOVERY;
                } else if (event == Event.DISCONNECT) {
                    state = State.DISCONNECTING;
                }
                break;
            case CONSOLE_OVER_STREAM:
                if (event == Event.RECONNECT) state = State.CONNECTING;
                else if (event == Event.RETURN_TO_STREAM) state = State.STREAM;
                else if (event == Event.OPEN_OVERLAY) state = State.OVERLAY;
                else if (event == Event.DISCONNECT) state = State.DISCONNECTING;
                else if (event == Event.BACK) effect = Effect.SHOW_EXIT_CONFIRMATION;
                break;
            case OVERLAY:
                if (event == Event.RECONNECT) state = State.CONNECTING;
                else if (event == Event.CLOSE_OVERLAY || event == Event.BACK) state = State.STREAM;
                else if (event == Event.OPEN_CONSOLE || event == Event.HOME) state = State.CONSOLE_OVER_STREAM;
                else if (event == Event.DISCONNECT) state = State.DISCONNECTING;
                break;
            case RECOVERY:
                if (event == Event.RETRY) state = State.CONNECTING;
                else if (event == Event.REVEAL_STREAM && sessionActive) state = State.STREAM;
                else if (event == Event.HOME || event == Event.BACK) {
                    state = sessionActive ? State.CONSOLE_OVER_STREAM : State.HOME;
                } else if (event == Event.DISCONNECT) state = State.DISCONNECTING;
                break;
            case DISCONNECTING:
                if (event == Event.DISCONNECTED) {
                    sessionActive = false;
                    state = State.HOME;
                } else if (event == Event.CONNECTION_FAILED) {
                    state = State.RECOVERY;
                }
                break;
        }
        return new Transition(previous, state, effect);
    }

    public static InputTarget inputTarget(State state) {
        switch (state) {
            case HOME:
            case CONSOLE_OVER_STREAM: return InputTarget.HOME;
            case STREAM: return InputTarget.GAMEPLAY;
            case OVERLAY: return InputTarget.OVERLAY;
            case RECOVERY: return InputTarget.RECOVERY;
            default: return InputTarget.NONE;
        }
    }
}
