package com.limelight.console;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which library filters are active (UX_MENU_PL.md 3.5): the chips above the grid, the count on
 * the Filtry button and the marks in the Filtry panel all read this one summary.
 */
final class LibraryFilterSummary {
    final PlayniteLibraryFilter filter;
    final PlayniteLibrarySort sort;
    /** Labels of the chosen platforms in panel order; empty when every platform is shown. */
    final List<String> platforms;
    private final Set<String> selectedSources;

    LibraryFilterSummary(PlayniteLibraryFilter filter, Set<String> storedSources,
                         Map<String, String> available, PlayniteLibrarySort sort) {
        this.filter = filter;
        this.sort = sort;
        selectedSources = selection(storedSources, available.keySet());
        List<String> labels = new ArrayList<>();
        if (selectedSources != null) {
            for (Map.Entry<String, String> source : available.entrySet()) {
                if (selectedSources.contains(source.getKey())) labels.add(source.getValue());
            }
        }
        platforms = Collections.unmodifiableList(labels);
    }

    boolean stateActive() {
        return filter != PlayniteLibraryFilter.ALL;
    }

    boolean sortActive() {
        return sort != PlayniteLibrarySort.RECENT;
    }

    int activeCount() {
        return (stateActive() ? 1 : 0) + (platforms.isEmpty() ? 0 : 1) + (sortActive() ? 1 : 0);
    }

    /** True when games of {@code source} are shown. */
    boolean showsSource(String source) {
        return selectedSources == null || selectedSources.contains(source);
    }

    /**
     * The stored platform choice after toggling {@code source}: null (every platform) when the
     * choice ends up empty or complete, as the grid shows every platform for both.
     */
    static Set<String> toggleSource(Set<String> storedSources, Set<String> available,
                                    String source) {
        Set<String> selected = new LinkedHashSet<>(storedSources == null ? available : storedSources);
        selected.retainAll(available);
        if (!selected.add(source)) selected.remove(source);
        return selection(selected, available);
    }

    private static Set<String> selection(Set<String> stored, Set<String> available) {
        if (stored == null) return null;
        Set<String> selected = new LinkedHashSet<>(stored);
        selected.retainAll(available);
        return selected.isEmpty() || selected.containsAll(available) ? null : selected;
    }
}
