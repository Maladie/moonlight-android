package com.limelight.console;

import java.util.HashMap;
import java.util.Map;

/**
 * The provider (Playnite) launch owner of each host: the presentation holding it, its
 * custodian, and whether its start or its cleanup is still owed. One per process, held by the
 * session model ({@code SessionModel.providerOwnership()}); every
 * {@link ConsoleStreamTransitionCoordinator} reads and changes it while holding this object's
 * monitor.
 */
public final class ProviderOwnership {
    /** Per normalized host id. */
    final Map<String, ConsoleStreamTransitionCoordinator.ProviderOwner> byHost = new HashMap<>();
}
