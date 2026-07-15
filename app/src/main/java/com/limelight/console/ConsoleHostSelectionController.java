package com.limelight.console;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Coordinates host-scoped app selection without inspecting rendered views. */
final class ConsoleHostSelectionController {
    static final class Selection {
        final ConsoleDataRepository.Host host;
        final List<ConsoleDataRepository.App> apps;
        final int focusAppIndex;

        private Selection(ConsoleDataRepository.Host host,
                          List<ConsoleDataRepository.App> apps,
                          int focusAppIndex) {
            this.host = host;
            this.apps = Collections.unmodifiableList(new ArrayList<>(apps));
            this.focusAppIndex = focusAppIndex;
        }

        static Selection create(ConsoleDataRepository.Host host,
                                List<ConsoleDataRepository.App> apps,
                                int rememberedAppId) {
            List<ConsoleDataRepository.App> safeApps = apps == null ?
                    Collections.emptyList() : apps;
            List<Integer> ids = new ArrayList<>(safeApps.size());
            for (ConsoleDataRepository.App app : safeApps) ids.add(app.id);
            return new Selection(host, safeApps,
                    ConsoleSelectionPolicy.appIndex(ids, rememberedAppId));
        }
    }

    private final ConsoleDataRepository repository;
    private final ConsoleSelectionStore store;

    ConsoleHostSelectionController(ConsoleDataRepository repository,
                                   ConsoleSelectionStore store) {
        this.repository = repository;
        this.store = store;
    }

    Selection select(ConsoleDataRepository.Host host) {
        store.rememberHost(host.uuid);
        return Selection.create(host, repository.apps(host), store.selectedAppId(host.uuid));
    }

    void rememberApp(ConsoleDataRepository.Host host, ConsoleDataRepository.App app) {
        if (host != null && app != null) store.rememberApp(host.uuid, app.id);
    }
}
