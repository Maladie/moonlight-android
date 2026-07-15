package com.limelight.console;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable profile catalog returned by the future pinned Gateway adapter. */
final class IntegrationProfileCatalog {
    final List<IntegrationProfileStatus> profiles;
    final String suggestedProfileId;

    IntegrationProfileCatalog(List<IntegrationProfileStatus> profiles,
                              String suggestedProfileId) {
        this.profiles = Collections.unmodifiableList(new ArrayList<>(profiles));
        this.suggestedProfileId = suggestedProfileId == null ? null :
                GatewayConnection.normalizeProfileId(suggestedProfileId);
    }

    IntegrationProfileStatus find(String profileId) {
        if (profileId == null) return null;
        for (IntegrationProfileStatus profile : profiles) {
            if (profile.id.equals(profileId)) return profile;
        }
        return null;
    }

    boolean isSuggested(IntegrationProfileStatus profile) {
        return profile != null && profile.id.equals(suggestedProfileId);
    }
}
