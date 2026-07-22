package com.limelight.console;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;

/** Pure state rules used by every dashboard surface that exposes host/app actions. */
final class ConsoleActionCatalog {
    enum HostCapability {
        SELECT_APPS,
        PAIR,
        UNPAIR,
        WAKE,
        REFRESH,
        RESUME,
        QUIT_SESSION,
        NETWORK_TEST,
        HOST_INTEGRATIONS,
        SLEEP,
        DETAILS,
        REMOVE
    }

    enum AppCapability {
        LAUNCH,
        RESUME,
        QUIT_AND_LAUNCH,
        QUIT,
        SETTINGS,
        QUICK_ADD,
        QUICK_REMOVE,
        HIDE,
        SHOW,
        SHORTCUT,
        DETAILS
    }

    private ConsoleActionCatalog() { }

    static boolean hostActionVisible(HostCapability action, boolean online, boolean knownState,
                                     boolean paired, boolean activeSession, boolean hasMac,
                                     boolean gatewayPaired) {
        switch (action) {
            case SELECT_APPS:
                return online && paired;
            case PAIR:
                return online && !paired;
            case UNPAIR:
                return online && paired;
            case WAKE:
                return knownState && !online && hasMac;
            case REFRESH:
                return !online;
            case RESUME:
            case QUIT_SESSION:
                return online && paired && activeSession;
            case NETWORK_TEST:
                return online && paired;
            case HOST_INTEGRATIONS:
                return online;
            case SLEEP:
                return online && paired && gatewayPaired;
            case DETAILS:
            case REMOVE:
                return true;
            default:
                return false;
        }
    }

    static boolean appActionVisible(AppCapability action, boolean online, boolean paired,
                                    boolean thisAppRunning, boolean anotherAppRunning,
                                    boolean quickLaunch, boolean hidden, boolean hasArtwork,
                                    boolean shortcutsSupported) {
        switch (action) {
            case LAUNCH:
                return online && paired && !thisAppRunning && !anotherAppRunning;
            case RESUME:
            case QUIT:
                return online && paired && thisAppRunning;
            case QUIT_AND_LAUNCH:
                return online && paired && anotherAppRunning;
            case SETTINGS:
            case DETAILS:
                return true;
            case QUICK_ADD:
                return !quickLaunch;
            case QUICK_REMOVE:
                return quickLaunch;
            case HIDE:
                return !hidden && !thisAppRunning;
            case SHOW:
                return hidden;
            case SHORTCUT:
                return shortcutsSupported && hasArtwork;
            default:
                return false;
        }
    }

    static boolean isOnline(ComputerDetails host) {
        return host != null && host.state == ComputerDetails.State.ONLINE;
    }

    static boolean isPaired(ComputerDetails host) {
        return host != null && host.pairState == PairingManager.PairState.PAIRED;
    }
}
