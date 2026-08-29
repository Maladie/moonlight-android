package com.limelight.console;

import java.util.ArrayList;
import java.util.List;

/** Pure game-tile projection over one resolved session snapshot. */
final class PlayniteSessionPresentation {
    enum State {
        READY,
        RESUME_ACTIVE,
        RESUME_SUSPENDED
    }

    static final class Projection {
        final String resumeGameId;
        final String suspendedGameId;
        private final SessionSnapshot snapshot;
        private final String selectedGameId;

        private Projection(SessionSnapshot snapshot, String selectedGameId) {
            this.snapshot = snapshot;
            this.selectedGameId = selectedGameId;
            this.resumeGameId = snapshot.state == SessionSnapshot.State.ACTIVE
                    || snapshot.state == SessionSnapshot.State.RECONNECT_REQUIRED
                    ? selectedGameId : "";
            this.suspendedGameId = snapshot.isSuspended()
                    ? selectedGameId : "";
        }

        State stateFor(String gameId) {
            if (selectedGameId.isEmpty()
                    || !selectedGameId.equals(SessionSnapshot.normalize(gameId))) {
                return State.READY;
            }
            if (snapshot.isSuspended()) {
                return State.RESUME_SUSPENDED;
            }
            if (snapshot.state == SessionSnapshot.State.ACTIVE
                    || snapshot.state == SessionSnapshot.State.RECONNECT_REQUIRED) {
                return State.RESUME_ACTIVE;
            }
            return State.READY;
        }

        String signature() {
            return snapshot.signature() + "|" + resumeGameId + "|" + suspendedGameId;
        }
    }

    static Projection project(SessionSnapshot snapshot,
                              List<PlayniteDashboardItem> items,
                              String previouslySelectedGameId) {
        if (!snapshot.isResumeAvailable()) return new Projection(snapshot, "");
        String selected = exactPlayniteMatch(snapshot, items);
        if (!snapshot.playniteGameId.isEmpty()) {
            return new Projection(snapshot, selected);
        }
        List<PlayniteDashboardItem> appMatches = new ArrayList<>();
        for (PlayniteDashboardItem item : items) {
            if (snapshot.hostGameAppId != 0 && item.sunshineAppId != null
                    && item.sunshineAppId == snapshot.hostGameAppId) {
                appMatches.add(item);
            }
        }
        if (appMatches.size() == 1) {
            selected = SessionSnapshot.normalize(appMatches.get(0).stableId());
        }
        return new Projection(snapshot, selected);
    }

    private static String exactPlayniteMatch(SessionSnapshot snapshot,
                                             List<PlayniteDashboardItem> items) {
        if (snapshot.playniteGameId.isEmpty()) return "";
        for (PlayniteDashboardItem item : items) {
            if (snapshot.playniteGameId.equals(
                    SessionSnapshot.normalize(item.stableId()))) {
                return SessionSnapshot.normalize(item.stableId());
            }
        }
        return "";
    }

    private PlayniteSessionPresentation() { }
}
