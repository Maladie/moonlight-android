package com.limelight;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/** Public entry point used by TV frontends to reveal an existing Game activity. */
public final class PublicReturnStreamTrampoline extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Game.bringActiveStreamToFront(this, getIntent())) {
            Toast.makeText(this, "No active Moonlight stream", Toast.LENGTH_SHORT).show();
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
