package com.example.hotspottoggle;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

public final class ToggleActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            if (Build.VERSION.SDK_INT < 24 || Build.VERSION.SDK_INT > 25) {
                ErrorReport.show(this, R.string.unsupported_android, null);
            } else if (!Settings.System.canWrite(this)) {
                requestSettingsPermission();
            } else {
                int state = new HotspotController(this).readApState();
                if ((state == 11 || state == 14)
                        && !HotspotAccessibilityService.isConnected()) {
                    Toast.makeText(this, R.string.accessibility_needed, Toast.LENGTH_LONG).show();
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } else {
                    startService(new Intent(this, HotspotToggleService.class));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            ErrorReport.show(this, R.string.toggle_failed, exception);
        } finally {
            finish();
        }
    }

    private void requestSettingsPermission() {
        Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show();
        Intent permissionIntent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(permissionIntent);
        } catch (ActivityNotFoundException | SecurityException exception) {
            ErrorReport.show(this, R.string.permission_unavailable, exception);
        }
    }
}
