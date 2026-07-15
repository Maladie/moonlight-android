package com.limelight.console;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;

import com.limelight.PublicReturnStreamTrampoline;
import com.limelight.Game;
import com.limelight.PublicStreamIntent;

/** Milestone-1 adapter retained until the in-Activity runtime passes the TV P0 gate. */
final class LegacyConsoleStreamRuntime implements ConsoleStreamRuntime {
    private final Activity activity;
    private final HostGatewayStore gatewayStore;

    LegacyConsoleStreamRuntime(Activity activity, HostGatewayStore gatewayStore) {
        this.activity = activity;
        this.gatewayStore = gatewayStore;
    }

    @Override public void launch(ConsoleLaunchContract.Request request) {
        Intent intent = ConsoleLaunchContract.legacyIntent(activity, request, gatewayStore);
        activity.startActivity(intent,
                ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle());
        activity.overridePendingTransition(0, 0);
    }

    @Override public void returnToActiveStream() {
        Intent intent = new Intent(activity, PublicReturnStreamTrampoline.class)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        activity.startActivity(intent,
                ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle());
        activity.overridePendingTransition(0, 0);
    }

    @Override public void disconnectTransport() {
        Game.controlActiveStream(PublicStreamIntent.ACTION_DISCONNECT_STREAM);
    }

    @Override public void quitHostApplication() {
        Game.controlActiveStream(PublicStreamIntent.ACTION_QUIT_STREAM_APP);
    }
}
