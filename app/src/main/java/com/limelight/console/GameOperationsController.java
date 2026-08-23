package com.limelight.console;

import com.limelight.gateway.GatewayConnection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

/** Projects host-authoritative GameOps state and correlates short-lived local requests. */
final class GameOperationsController {
    interface Gateway {
        void install(GatewayConnection connection, String gameId) throws IOException;
        void uninstall(GatewayConnection connection, String gameId) throws IOException;
        void focusInstallation(GatewayConnection connection, String gameId) throws IOException;
    }

    interface Dispatcher {
        void post(Runnable action);
    }

    interface Callback {
        void onResult(boolean success);
    }

    enum Phase {
        IDLE,
        REQUESTING_INSTALL,
        REQUESTING_UNINSTALL,
        QUEUED,
        PREPARING,
        DOWNLOADING,
        INSTALLING,
        UNINSTALLING,
        ATTENTION_REQUIRED,
        VERIFYING,
        COMPLETED,
        FAILED,
        CANCELLED,
        UNKNOWN_ACTIVE
    }

    enum Continuation {
        OPEN_INSTALLATION_DESKTOP,
        FOCUS_INSTALLATION_WINDOW
    }

    enum ObservationType {
        INSTALL_CONFIRMED_BY_SNAPSHOT,
        INSTALL_NO_LONGER_ACTIVE_AFTER_OBSERVED_ACTIVITY,
        UNINSTALL_CONFIRMED_BY_SNAPSHOT
    }

    static final class Presentation {
        final Phase phase;
        final int progress;
        final boolean installActive;
        final boolean uninstallActive;
        final boolean launchBlocked;
        final boolean attentionRequired;
        final String attentionReason;
        final String attentionWindowTitle;
        final String attentionLauncher;

        private Presentation(Phase phase, int progress, boolean installActive,
                             boolean uninstallActive, boolean attentionRequired,
                             String attentionReason, String attentionWindowTitle,
                             String attentionLauncher) {
            this.phase = phase;
            this.progress = progress;
            this.installActive = installActive;
            this.uninstallActive = uninstallActive;
            this.launchBlocked = installActive || uninstallActive;
            this.attentionRequired = attentionRequired;
            this.attentionReason = attentionReason;
            this.attentionWindowTitle = attentionWindowTitle;
            this.attentionLauncher = attentionLauncher;
        }
    }

    static final class Observation {
        final ObservationType type;
        final String hostId;
        final String gameId;
        final String gameName;
        final PlayniteLibraryGame game;

        private Observation(ObservationType type, Key key, Pending pending,
                            PlayniteLibraryGame game) {
            this.type = type;
            this.hostId = key.hostId;
            this.gameId = key.gameId;
            this.gameName = pending.gameName;
            this.game = game;
        }
    }

    private enum Kind { INSTALL, UNINSTALL }

    private static final class Pending {
        final Kind kind;
        final String gameName;
        final long token;
        boolean installActivityObserved;

        Pending(Kind kind, String gameName, long token) {
            this.kind = kind;
            this.gameName = text(gameName);
            this.token = token;
        }
    }

    private static final class Key {
        final String hostId;
        final String gameId;

        Key(String hostId, String gameId) {
            this.hostId = normalized(hostId);
            this.gameId = normalized(gameId);
        }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Key)) return false;
            Key key = (Key) value;
            return hostId.equals(key.hostId) && gameId.equals(key.gameId);
        }

        @Override public int hashCode() {
            return 31 * hostId.hashCode() + gameId.hashCode();
        }
    }

    private final Gateway gateway;
    private final Executor executor;
    private final Dispatcher dispatcher;
    private final Map<Key, Pending> pending = new LinkedHashMap<>();
    private long nextToken;
    private long latestFocusToken;
    private boolean closed;

    GameOperationsController(HostGatewayClient client, Executor executor,
                             Dispatcher dispatcher) {
        this(new Gateway() {
            @Override public void install(GatewayConnection connection, String gameId)
                    throws IOException {
                client.installPlayniteGame(connection, gameId);
            }

            @Override public void uninstall(GatewayConnection connection, String gameId)
                    throws IOException {
                client.uninstallPlayniteGame(connection, gameId);
            }

            @Override public void focusInstallation(GatewayConnection connection, String gameId)
                    throws IOException {
                client.focusPlayniteInstallation(connection, gameId);
            }
        }, executor, dispatcher);
    }

    GameOperationsController(Gateway gateway, Executor executor, Dispatcher dispatcher) {
        this.gateway = gateway;
        this.executor = executor;
        this.dispatcher = dispatcher;
    }

    Presentation requestInstall(String hostId, PlayniteLibraryGame game,
                                GatewayConnection connection, Callback callback) {
        return request(Kind.INSTALL, hostId, game, connection, callback);
    }

    Presentation requestUninstall(String hostId, PlayniteLibraryGame game,
                                  GatewayConnection connection, Callback callback) {
        return request(Kind.UNINSTALL, hostId, game, connection, callback);
    }

    void requestInstallationFocus(GatewayConnection connection, String gameId,
                                  Callback callback) {
        if (closed) return;
        final long token = ++nextToken;
        latestFocusToken = token;
        executor.execute(() -> {
            boolean success;
            try {
                gateway.focusInstallation(connection, gameId);
                success = true;
            } catch (IOException | RuntimeException error) {
                success = false;
            }
            final boolean result = success;
            dispatcher.post(() -> {
                if (!closed && token == latestFocusToken) callback.onResult(result);
            });
        });
    }

    Presentation presentation(String hostId, PlayniteLibraryGame game) {
        Key key = new Key(hostId, game.playniteGameId);
        Pending local = pending.get(key);
        String state = normalized(game.operationState);
        boolean attention = game.installRequiresAttention || "attention_required".equals(state);
        String reason = text(game.installAttentionReason);
        String title = text(game.installWindowTitle);
        String launcher = text(game.installLauncher);
        int progress = game.operationProgress < 0 ? -1 : Math.min(100, game.operationProgress);

        if (!state.isEmpty()) {
            Phase phase = phase(state);
            boolean active = isActiveHostState(state);
            boolean uninstall = active && (phase == Phase.UNINSTALLING
                    || game.uninstalling
                    || local != null && local.kind == Kind.UNINSTALL);
            return new Presentation(phase, progress, active && !uninstall, uninstall,
                    attention, reason, title, launcher);
        }
        if (attention) {
            return new Presentation(Phase.ATTENTION_REQUIRED, progress, true, false,
                    true, reason, title, launcher);
        }
        if (game.uninstalling) {
            return new Presentation(Phase.UNINSTALLING, progress, false, true,
                    false, reason, title, launcher);
        }
        if (game.installing) {
            return new Presentation(Phase.INSTALLING, progress, true, false,
                    false, reason, title, launcher);
        }
        if (local != null) {
            boolean uninstall = local.kind == Kind.UNINSTALL;
            return new Presentation(uninstall ? Phase.REQUESTING_UNINSTALL
                            : Phase.REQUESTING_INSTALL, -1, !uninstall, uninstall,
                    false, reason, title, launcher);
        }
        return new Presentation(Phase.IDLE, progress, false, false,
                false, reason, title, launcher);
    }

    boolean isInstalling(String hostId, PlayniteLibraryGame game) {
        return presentation(hostId, game).installActive;
    }

    boolean isUninstalling(String hostId, PlayniteLibraryGame game) {
        return presentation(hostId, game).uninstallActive;
    }

    boolean hasActiveOperation(String hostId, List<PlayniteLibraryGame> games) {
        String normalizedHost = normalized(hostId);
        for (Map.Entry<Key, Pending> entry : pending.entrySet()) {
            if (entry.getKey().hostId.equals(normalizedHost)) return true;
        }
        for (PlayniteLibraryGame game : games) {
            Presentation value = presentation(hostId, game);
            if (value.installActive || value.uninstallActive) return true;
        }
        return false;
    }

    boolean hasOperationAwaitingConfirmation(String hostId,
                                             List<PlayniteLibraryGame> games) {
        String normalizedHost = normalized(hostId);
        Map<Key, Pending> unmatched = new LinkedHashMap<>();
        for (Map.Entry<Key, Pending> entry : pending.entrySet()) {
            if (entry.getKey().hostId.equals(normalizedHost)) {
                unmatched.put(entry.getKey(), entry.getValue());
            }
        }
        for (PlayniteLibraryGame game : games) {
            String state = normalized(game.operationState);
            if ("preparing".equals(state) || "attention_required".equals(state)) return true;
            Pending local = unmatched.remove(new Key(hostId, game.playniteGameId));
            if (local != null && state.isEmpty()) return true;
        }
        return !unmatched.isEmpty();
    }

    List<Observation> reconcile(String hostId, List<PlayniteLibraryGame> games) {
        if (pending.isEmpty()) return Collections.emptyList();
        String normalizedHost = normalized(hostId);
        Map<String, PlayniteLibraryGame> current = new LinkedHashMap<>();
        for (PlayniteLibraryGame game : games) {
            current.put(normalized(game.playniteGameId), game);
        }
        List<Observation> observations = new ArrayList<>();
        List<Key> reconciled = new ArrayList<>();
        for (Map.Entry<Key, Pending> entry : pending.entrySet()) {
            Key key = entry.getKey();
            Pending local = entry.getValue();
            if (!key.hostId.equals(normalizedHost)) continue;
            PlayniteLibraryGame game = current.get(key.gameId);
            if (game == null) continue;
            if (local.kind == Kind.UNINSTALL) {
                if (!game.installed) {
                    observations.add(new Observation(
                            ObservationType.UNINSTALL_CONFIRMED_BY_SNAPSHOT,
                            key, local, game));
                    reconciled.add(key);
                }
                continue;
            }
            if (game.installed) {
                observations.add(new Observation(
                        ObservationType.INSTALL_CONFIRMED_BY_SNAPSHOT, key, local, game));
                reconciled.add(key);
            } else if (hostInstallActive(game, local)) {
                local.installActivityObserved = true;
            } else if (local.installActivityObserved) {
                observations.add(new Observation(
                        ObservationType.INSTALL_NO_LONGER_ACTIVE_AFTER_OBSERVED_ACTIVITY,
                        key, local, game));
                reconciled.add(key);
            }
        }
        for (Key key : reconciled) pending.remove(key);
        return observations.isEmpty() ? Collections.emptyList()
                : Collections.unmodifiableList(observations);
    }

    Continuation continuation(Presentation presentation) {
        return "secure_desktop".equals(normalized(presentation.attentionReason))
                || "epic_manual".equals(normalized(presentation.attentionReason))
                ? Continuation.OPEN_INSTALLATION_DESKTOP
                : Continuation.FOCUS_INSTALLATION_WINDOW;
    }

    void close() {
        closed = true;
        pending.clear();
    }

    private Presentation request(Kind kind, String hostId, PlayniteLibraryGame game,
                                 GatewayConnection connection, Callback callback) {
        if (closed) return presentation(hostId, game);
        Key key = new Key(hostId, game.playniteGameId);
        Pending local = new Pending(kind, game.name, ++nextToken);
        pending.put(key, local);
        executor.execute(() -> {
            boolean success;
            try {
                if (kind == Kind.INSTALL) gateway.install(connection, game.playniteGameId);
                else gateway.uninstall(connection, game.playniteGameId);
                success = true;
            } catch (IOException | RuntimeException error) {
                success = false;
            }
            final boolean result = success;
            dispatcher.post(() -> {
                if (closed || !isCurrent(key, kind, local.token)) return;
                if (!result) pending.remove(key);
                callback.onResult(result);
            });
        });
        return presentation(hostId, game);
    }

    private boolean isCurrent(Key key, Kind kind, long token) {
        Pending current = pending.get(key);
        return current != null && current.kind == kind && current.token == token;
    }

    private static Phase phase(String state) {
        switch (state) {
            case "idle": return Phase.IDLE;
            case "queued": return Phase.QUEUED;
            case "preparing": return Phase.PREPARING;
            case "downloading": return Phase.DOWNLOADING;
            case "installing": return Phase.INSTALLING;
            case "uninstalling": return Phase.UNINSTALLING;
            case "attention_required": return Phase.ATTENTION_REQUIRED;
            case "verifying": return Phase.VERIFYING;
            case "completed": return Phase.COMPLETED;
            case "failed": return Phase.FAILED;
            case "cancelled": return Phase.CANCELLED;
            default: return Phase.UNKNOWN_ACTIVE;
        }
    }

    private static boolean isActiveHostState(String state) {
        return !state.isEmpty() && !"completed".equals(state) && !"failed".equals(state)
                && !"cancelled".equals(state) && !"idle".equals(state);
    }

    private static boolean hostInstallActive(PlayniteLibraryGame game, Pending local) {
        String state = normalized(game.operationState);
        if (!state.isEmpty()) {
            return isActiveHostState(state) && !"uninstalling".equals(state)
                    && !game.uninstalling && local.kind != Kind.UNINSTALL;
        }
        return game.installRequiresAttention || game.installing;
    }

    private static String normalized(String value) {
        return text(value).toLowerCase(Locale.ROOT);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
