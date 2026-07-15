package com.limelight.console;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable data read for one Home render; views never query stores while composing rows. */
final class ConsoleHomeSnapshot {
    final ConsoleDataRepository.Session session;
    final List<ConsoleDataRepository.Host> hosts;
    final int selectedHostIndex;
    final ConsoleDataRepository.Host selectedHost;
    final List<ConsoleDataRepository.App> apps;
    final int selectedAppIndex;
    final HostIntegrationSummary integrations;

    private ConsoleHomeSnapshot(ConsoleDataRepository.Session session,
                                List<ConsoleDataRepository.Host> hosts,
                                int selectedHostIndex,
                                ConsoleDataRepository.Host selectedHost,
                                List<ConsoleDataRepository.App> apps,
                                int selectedAppIndex,
                                HostIntegrationSummary integrations) {
        this.session = session;
        this.hosts = Collections.unmodifiableList(new ArrayList<>(hosts));
        this.selectedHostIndex = selectedHostIndex;
        this.selectedHost = selectedHost;
        this.apps = Collections.unmodifiableList(new ArrayList<>(apps));
        this.selectedAppIndex = selectedAppIndex;
        this.integrations = integrations;
    }

    static ConsoleHomeSnapshot load(ConsoleDataRepository repository,
                                    HostGatewayStore gatewayStore,
                                    ConsoleSelectionStore selectionStore,
                                    ConsoleLaunchHistoryStore historyStore) {
        ConsoleDataRepository.Session session = repository.session();
        List<ConsoleDataRepository.Host> hosts = repository.hosts();
        int hostIndex = hostIndex(hosts, selectionStore.selectedHostUuid());
        if (hostIndex < 0) {
            return create(session, hosts, null, Collections.emptyList(), -1, null);
        }

        ConsoleDataRepository.Host host = hosts.get(hostIndex);
        List<ConsoleDataRepository.App> apps = ConsoleAppOrdering.order(
                host, repository.apps(host), historyStore::playedAt);
        return create(session, hosts, selectionStore.selectedHostUuid(), apps,
                selectionStore.selectedAppId(host.uuid),
                gatewayStore.load(host.uuid));
    }

    static ConsoleHomeSnapshot create(ConsoleDataRepository.Session session,
                                      List<ConsoleDataRepository.Host> hosts,
                                      String rememberedHostUuid,
                                      List<ConsoleDataRepository.App> apps,
                                      int rememberedAppId,
                                      GatewayConnection connection) {
        List<ConsoleDataRepository.Host> safeHosts = hosts == null ?
                Collections.emptyList() : hosts;
        int hostIndex = hostIndex(safeHosts, rememberedHostUuid);
        ConsoleDataRepository.Host host = hostIndex >= 0 ? safeHosts.get(hostIndex) : null;
        List<ConsoleDataRepository.App> safeApps = host == null || apps == null ?
                Collections.emptyList() : apps;
        int appIndex = appIndex(safeApps, rememberedAppId);
        return new ConsoleHomeSnapshot(session, safeHosts, hostIndex, host,
                safeApps, appIndex, HostIntegrationSummary.from(connection));
    }

    private static int hostIndex(List<ConsoleDataRepository.Host> hosts, String rememberedUuid) {
        List<String> ids = new ArrayList<>(hosts.size());
        for (ConsoleDataRepository.Host host : hosts) ids.add(host.uuid);
        return ConsoleSelectionPolicy.hostIndex(ids, rememberedUuid);
    }

    private static int appIndex(List<ConsoleDataRepository.App> apps, int rememberedId) {
        List<Integer> ids = new ArrayList<>(apps.size());
        for (ConsoleDataRepository.App app : apps) ids.add(app.id);
        return ConsoleSelectionPolicy.appIndex(ids, rememberedId);
    }
}
