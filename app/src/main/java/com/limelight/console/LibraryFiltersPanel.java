package com.limelight.console;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import com.limelight.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one Filtry side panel of the full library (UX_MENU_PL.md 3.5): Stan as radios, Platformy
 * as check boxes, Sortuj as a value row opening its choices, and "Wyczyść filtry" last. Every
 * choice is stored and applied at once; the panel then re-renders in place, keeping focus.
 */
final class LibraryFiltersPanel {
    interface Host {
        /** Shows rows in the shared side panel; the same eyebrow and title re-render in place. */
        void showPanel(String eyebrow, String title, String details, View... rows);

        /** Returns from the sort choices to the panel they were opened from. */
        void panelBack();

        /** The stored filters changed; re-renders the grid from its first card. */
        void filtersChanged(boolean sortOnly);

        void clearFilters();

        /** Platforms of the current library, key to label, in display order. */
        Map<String, String> sources();

        int shownCount();

        int totalCount();
    }

    private final Context context;
    private final ConsolePanel panel;
    private final HostGatewayStore store;
    private final Host host;
    private String hostUuid;

    LibraryFiltersPanel(Context context, ConsolePanel panel, HostGatewayStore store, Host host) {
        this.context = context;
        this.panel = panel;
        this.store = store;
        this.host = host;
    }

    static LibraryFilterSummary summary(HostGatewayStore store, String hostUuid,
                                        Map<String, String> sources) {
        return new LibraryFilterSummary(store.playniteLibraryFilter(hostUuid),
                store.playniteLibrarySources(hostUuid), sources,
                store.playniteLibrarySort(hostUuid));
    }

    void open(String hostUuid) {
        this.hostUuid = hostUuid;
        render();
    }

    /** Renders the rows; re-rendering the same panel keeps its focused row and scroll. */
    private void render() {
        Map<String, String> sources = host.sources();
        LibraryFilterSummary summary = summary(store, hostUuid, sources);
        List<View> rows = new ArrayList<>();
        rows.add(panel.section(context.getString(R.string.playnite_filter_state)));
        for (PlayniteLibraryFilter filter : PlayniteLibraryFilter.values()) {
            TextView row = panel.choice(context.getString(filter.label),
                    filter == summary.filter, false);
            row.setOnClickListener(view -> {
                store.setPlayniteLibraryFilter(hostUuid, filter);
                changed(false);
            });
            rows.add(row);
        }
        if (!sources.isEmpty()) {
            rows.add(panel.section(context.getString(R.string.playnite_filter_platforms)));
            for (Map.Entry<String, String> source : sources.entrySet()) {
                TextView row = panel.choice(source.getValue(),
                        summary.showsSource(source.getKey()), true);
                row.setOnClickListener(view -> {
                    Set<String> next = LibraryFilterSummary.toggleSource(
                            store.playniteLibrarySources(hostUuid), sources.keySet(),
                            source.getKey());
                    if (next == null) store.clearPlayniteLibrarySources(hostUuid);
                    else store.setPlayniteLibrarySources(hostUuid, next);
                    changed(false);
                });
                rows.add(row);
            }
        }
        rows.add(panel.section(context.getString(R.string.playnite_filter_sort)));
        TextView sort = panel.navigation(context.getString(summary.sort.label));
        // Sort and state share labels ("Ostatnio grane"); focus is restored by tag.
        sort.setTag("library.filters.sort");
        sort.setOnClickListener(view -> showSortChoices(summary.sort));
        rows.add(sort);
        TextView clear = panel.destructive(panel.action(
                context.getString(R.string.playnite_filters_clear)));
        clear.setOnClickListener(view -> {
            host.clearFilters();
            render();
        });
        rows.add(clear);
        host.showPanel(eyebrow(), title(), details(), rows.toArray(new View[0]));
    }

    private void showSortChoices(PlayniteLibrarySort current) {
        List<View> rows = new ArrayList<>();
        for (PlayniteLibrarySort sort : PlayniteLibrarySort.values()) {
            TextView row = panel.choice(context.getString(sort.label), sort == current, false);
            row.setOnClickListener(view -> {
                store.setPlayniteLibrarySort(hostUuid, sort);
                host.panelBack();
                changed(true);
            });
            rows.add(row);
        }
        host.showPanel(eyebrow(), context.getString(R.string.playnite_filter_sort), "",
                rows.toArray(new View[0]));
        View chosen = rows.get(current.ordinal());
        chosen.post(chosen::requestFocus);
    }

    private void changed(boolean sortOnly) {
        host.filtersChanged(sortOnly);
        render();
    }

    private String eyebrow() {
        return context.getString(R.string.console_settings_library);
    }

    private String title() {
        return context.getString(R.string.playnite_legend_filters);
    }

    private String details() {
        int total = host.totalCount();
        return context.getResources().getQuantityString(R.plurals.playnite_filters_count,
                total, host.shownCount(), total);
    }
}
