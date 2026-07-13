package com.limelight;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.limelight.preferences.StreamSettings;

/** Public, stable entry point for frontends that want to open Moonlight X settings. */
public final class PublicSettingsTrampoline extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, StreamSettings.class));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
