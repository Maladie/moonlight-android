package com.limelight.console;

import android.content.Context;
import android.content.Intent;

import com.limelight.Game;
import com.limelight.PublicStreamIntent;
import com.limelight.ShortcutTrampoline;

/** Single replacement boundary for the milestone-1 legacy Activity launch adapter. */
final class ConsoleLaunchContract {
    static final class Request {
        final String hostUuid;
        final int appId;
        final String appName;
        final String frontendPackage;
        final String privacyMessage;
        final boolean readinessRequired;

        private Request(String hostUuid, int appId, String appName,
                        String frontendPackage) {
            if (hostUuid == null || hostUuid.isEmpty() || appId < 0 ||
                    appName == null || appName.isEmpty() ||
                    frontendPackage == null || frontendPackage.isEmpty()) {
                throw new IllegalArgumentException("Incomplete console launch request");
            }
            this.hostUuid = hostUuid;
            this.appId = appId;
            this.appName = appName;
            this.frontendPackage = frontendPackage;
            privacyMessage = "Preparing " + appName + "…";
            readinessRequired = true;
        }
    }

    private ConsoleLaunchContract() { }

    static Request create(ConsoleDataRepository.Host host,
                          ConsoleDataRepository.App app,
                          String frontendPackage) {
        if (host == null || app == null) {
            throw new IllegalArgumentException("Host and app are required");
        }
        return new Request(host.uuid, app.id, app.name, frontendPackage);
    }

    static Intent legacyIntent(Context context, Request request,
                               HostGatewayStore gatewayStore) {
        String appId = String.valueOf(request.appId);
        Intent intent = new Intent(context, ShortcutTrampoline.class)
                .setAction(PublicStreamIntent.ACTION_STREAM)
                .putExtra(PublicStreamIntent.EXTRA_HOST_UUID, request.hostUuid)
                .putExtra(PublicStreamIntent.EXTRA_APP_ID, appId)
                .putExtra(PublicStreamIntent.EXTRA_APP_NAME, request.appName)
                .putExtra(Game.EXTRA_APP_ID, appId)
                .putExtra(Game.EXTRA_APP_NAME, request.appName)
                .putExtra(PublicStreamIntent.EXTRA_EXTERNAL_FRONTEND, true)
                .putExtra(PublicStreamIntent.EXTRA_EXTERNAL_FRONTEND_PACKAGE,
                        request.frontendPackage)
                .putExtra(PublicStreamIntent.EXTRA_EXTERNAL_FRONTEND_MESSAGE,
                        request.privacyMessage)
                .putExtra(PublicStreamIntent.EXTRA_EXTERNAL_FRONTEND_REDUCED_MOTION, false)
                .putExtra(PublicStreamIntent.EXTRA_EXTERNAL_FRONTEND_READINESS_REQUIRED,
                        request.readinessRequired)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        gatewayStore.putLaunchExtras(intent, request.hostUuid);
        return intent;
    }
}
