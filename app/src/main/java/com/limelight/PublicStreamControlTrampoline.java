package com.limelight;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/** Signature-protected entry point for controlling the currently active stream. */
public final class PublicStreamControlTrampoline extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Game.controlActiveStream(getIntent().getAction())) {
            Toast.makeText(this, "No active Moonlight stream", Toast.LENGTH_SHORT).show();
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
