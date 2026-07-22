package com.limelight.console;

/** A resolved dashboard action. Invisible actions never reach production UI. */
final class ConsoleAction {
    enum Context {
        GLOBAL,
        HOST,
        APPLICATION,
        DISCORD
    }

    final String id;
    final CharSequence label;
    final int icon;
    final Context context;
    final boolean visible;
    final boolean enabled;
    final CharSequence unavailableReason;
    final boolean destructive;
    final Runnable handler;

    ConsoleAction(String id, CharSequence label, int icon, Context context,
                  boolean visible, boolean enabled, CharSequence unavailableReason,
                  boolean destructive, Runnable handler) {
        this.id = id;
        this.label = label;
        this.icon = icon;
        this.context = context;
        this.visible = visible;
        this.enabled = enabled;
        this.unavailableReason = unavailableReason;
        this.destructive = destructive;
        this.handler = handler;
    }

    static ConsoleAction enabled(String id, CharSequence label, int icon,
                                 Context context, boolean destructive, Runnable handler) {
        return new ConsoleAction(id, label, icon, context, true, true, null,
                destructive, handler);
    }
}
