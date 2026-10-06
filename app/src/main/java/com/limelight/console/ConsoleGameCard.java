package com.limelight.console;

import com.limelight.R;

/**
 * Game card structure (UX_MENU_PL.md section 3.4): one main action on top, then the Sesja,
 * Streaming, Rodzina and Biblioteka groups, each shown only when it has something to offer;
 * uninstall is the single destructive row and renders last.
 */
final class ConsoleGameCard {
    enum Action {
        LAUNCH, RESUME, CONTINUE_INSTALL, INSTALLING, INSTALL,
        END_GAME, CLOSE_GAME_AND_STREAM,
        STREAM_SETTINGS, OPTIMIZE_STREAMING,
        SHARE_WITH_CHILD,
        HIDE, SHOW, DETAILS, UNINSTALL
    }

    static final class Facts {
        boolean installed;
        boolean canLaunch;
        boolean canInstall;
        boolean canUninstall;
        boolean installing;
        boolean installNeedsAttention;
        /** The attention prompt can only be answered on the PC itself. */
        boolean attentionOnPc;
        /** This exact game runs on the host under the selected profile. */
        boolean gameRunning;
        /** The active stream session belongs to this game. */
        boolean sessionActive;
        boolean childSharing;
        boolean autopilotAvailable;
        boolean hidden;
    }

    static ConsoleActionMenu<Action> menu(Facts facts) {
        ConsoleActionMenu<Action> menu = new ConsoleActionMenu<>();
        if (facts.installed) {
            if (facts.sessionActive) {
                menu.add(Action.RESUME, R.string.console_resume_session, facts.canLaunch);
            } else {
                menu.add(Action.LAUNCH, R.string.console_game_launch, facts.canLaunch);
            }
        } else if (facts.installing && facts.installNeedsAttention) {
            menu.add(Action.CONTINUE_INSTALL, facts.attentionOnPc
                    ? R.string.playnite_install_confirm_on_pc
                    : R.string.playnite_install_continue, true);
        } else if (facts.installing) {
            menu.add(Action.INSTALLING, R.string.playnite_installing, false);
        } else if (facts.canInstall) {
            menu.add(Action.INSTALL, R.string.playnite_install_action_short, true);
        }
        menu.group(R.string.console_section_session);
        if (facts.installed && facts.gameRunning) {
            menu.add(Action.END_GAME, R.string.overlay_menu_end_game, true);
        }
        if (facts.installed && facts.sessionActive) {
            menu.add(Action.CLOSE_GAME_AND_STREAM, R.string.overlay_menu_quit_session, true);
        }
        menu.group(R.string.console_options_section_streaming)
                .add(Action.STREAM_SETTINGS, R.string.playnite_stream_settings, true)
                .add(Action.OPTIMIZE_STREAMING, R.string.console_streaming_autopilot_game,
                        facts.autopilotAvailable)
                .group(R.string.console_section_family);
        if (facts.childSharing) {
            menu.add(Action.SHARE_WITH_CHILD, R.string.console_child_sharing_action, true);
        }
        menu.group(R.string.console_settings_library)
                .add(facts.hidden ? Action.SHOW : Action.HIDE, facts.hidden
                        ? R.string.console_show_app : R.string.applist_menu_hide_app, true)
                .add(Action.DETAILS, R.string.applist_menu_details, true);
        if (facts.installed && facts.canUninstall) {
            menu.addDestructive(Action.UNINSTALL, R.string.playnite_uninstall, true);
        }
        return menu.done();
    }

    private ConsoleGameCard() { }
}
