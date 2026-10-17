package com.limelight.console;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
 * A grouped action list (UX_MENU_PL.md sections 3.3 and 3.4) as plain data, so the grouping
 * rules stay testable without views; {@link #rows} turns it into {@link ConsolePanel} rows.
 */
final class ConsoleActionMenu<A> {
    static final class Item<A> {
        /** String resource of a section header, or 0 for an action row. */
        final int section;
        final A action;
        final int label;
        final boolean enabled;
        final boolean destructive;

        private Item(int section, A action, int label, boolean enabled, boolean destructive) {
            this.section = section;
            this.action = action;
            this.label = label;
            this.enabled = enabled;
            this.destructive = destructive;
        }
    }

    final List<Item<A>> items = new ArrayList<>();
    private final List<Item<A>> pending = new ArrayList<>();
    private int pendingSection;

    /** Starts a group; its header is emitted only if the group gets at least one action. */
    ConsoleActionMenu<A> group(int section) {
        flush();
        pendingSection = section;
        return this;
    }

    ConsoleActionMenu<A> add(A action, int label, boolean enabled) {
        pending.add(new Item<>(0, action, label, enabled, false));
        return this;
    }

    ConsoleActionMenu<A> addDestructive(A action, int label, boolean enabled) {
        pending.add(new Item<>(0, action, label, enabled, true));
        return this;
    }

    ConsoleActionMenu<A> done() {
        flush();
        return this;
    }

    /** Actions in display order, headers excluded; used by tests and callers alike. */
    List<A> actions() {
        List<A> actions = new ArrayList<>();
        for (Item<A> item : items) if (item.section == 0) actions.add(item.action);
        return actions;
    }

    View[] rows(Context context, ConsolePanel panel, ToIntFunction<A> icon, Consumer<A> onAction) {
        List<View> rows = new ArrayList<>();
        for (Item<A> item : items) {
            if (item.section != 0) {
                rows.add(panel.section(context.getString(item.section)));
                continue;
            }
            TextView row = panel.action(context.getString(item.label), item.enabled);
            if (item.destructive) panel.destructive(row);
            panel.icon(row, icon.applyAsInt(item.action));
            row.setOnClickListener(view -> onAction.accept(item.action));
            rows.add(row);
        }
        return rows.toArray(new View[0]);
    }

    private void flush() {
        boolean actions = false;
        for (Item<A> item : pending) actions |= !item.destructive;
        // Destructive rows render last behind the divider, so they never claim a header.
        if (pendingSection != 0 && actions) {
            items.add(new Item<>(pendingSection, null, 0, false, false));
        }
        items.addAll(pending);
        pending.clear();
        pendingSection = 0;
    }
}
