package com.limelight.binding.audio;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import com.limelight.R;

public final class UsbMicrophonePermissionActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 7104;

    public static void request(Activity owner) {
        owner.startActivity(new Intent(owner, UsbMicrophonePermissionActivity.class));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            deny();
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            finish();
        } else if (state == null) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            finish();
            return;
        }
        deny();
    }

    private void deny() {
        boolean unsupported = Build.VERSION.SDK_INT < Build.VERSION_CODES.M;
        UsbMicrophoneService.disableWithState(this,
                unsupported ? UsbMicrophoneService.STATE_UNSUPPORTED
                        : UsbMicrophoneService.STATE_PERMISSION_REQUIRED);
        Toast.makeText(this, unsupported ? R.string.console_usb_microphone_unsupported
                        : R.string.usb_microphone_permission_required,
                Toast.LENGTH_LONG).show();
        finish();
    }
}
