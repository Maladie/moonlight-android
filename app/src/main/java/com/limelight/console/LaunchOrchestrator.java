package com.limelight.console;

/** Versioned neutral boundary for current launch and a future profile Bridge. */
public interface LaunchOrchestrator {
    int CONTRACT_VERSION = 1;

    final class Request {
        public final String hostUuid;
        public final String integrationProfileId;
        public final String appId;
        public final String playniteGameGuid;

        public Request(String hostUuid, String integrationProfileId, String appId,
                       String playniteGameGuid) {
            this.hostUuid = hostUuid;
            this.integrationProfileId = integrationProfileId;
            this.appId = appId;
            this.playniteGameGuid = playniteGameGuid;
        }
    }

    interface Listener {
        void onStarting();
        void onRunning(ReadinessSample sample);
        void onStopped();
        void onFailure(String safeMessage);
    }

    final class ReadinessSample {
        public final boolean visibleForegroundWindow;
        public final boolean onStreamedDisplay;
        public final boolean finalGeometry;
        public final int consecutiveStableSamples;

        public ReadinessSample(boolean visibleForegroundWindow, boolean onStreamedDisplay,
                               boolean finalGeometry, int consecutiveStableSamples) {
            this.visibleForegroundWindow = visibleForegroundWindow;
            this.onStreamedDisplay = onStreamedDisplay;
            this.finalGeometry = finalGeometry;
            this.consecutiveStableSamples = consecutiveStableSamples;
        }
    }

    void launch(Request request, Listener listener);
}
