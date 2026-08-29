package com.limelight.computers;

import com.limelight.nvstream.http.NvApp;

import java.util.List;
import java.util.Objects;

final class AppListComparator {
    private AppListComparator() { }

    static boolean same(List<NvApp> first, List<NvApp> second) {
        if (first == second) return true;
        if (first == null || second == null || first.size() != second.size()) return false;

        for (int i = 0; i < first.size(); i++) {
            NvApp left = first.get(i);
            NvApp right = second.get(i);
            if (left.getAppId() != right.getAppId()
                    || left.isHdrSupported() != right.isHdrSupported()
                    || !Objects.equals(left.getAppName(), right.getAppName())
                    || !Objects.equals(left.getAppUuid(), right.getAppUuid())
                    || !Objects.equals(left.getArtVersion(), right.getArtVersion())) {
                return false;
            }
        }
        return true;
    }
}
