package com.example.hotspottoggle;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class ErrorActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String report = getIntent().getStringExtra(ErrorReport.EXTRA_REPORT);
        if (report == null) {
            finish();
            return;
        }
        TextView details = new TextView(this);
        details.setText(report);
        details.setTextSize(14);
        details.setTextIsSelectable(true);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        details.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(details);
        AlertDialog dialog = new AlertDialog.Builder(this,
                android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle(R.string.error_title)
                .setView(scroll)
                .setPositiveButton(R.string.error_copy, null)
                .setNeutralButton(R.string.error_settings, null)
                .setNegativeButton(R.string.error_close, (window, button) -> finish())
                .create();
        dialog.setOnDismissListener(window -> finish());
        dialog.setOnShowListener(window -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(view -> openHotspotSettings());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    ClipboardManager clipboard = (ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(
                                getString(R.string.error_title), report));
                        Toast.makeText(this, R.string.error_copied, Toast.LENGTH_SHORT).show();
                    }
                });
        });
        dialog.show();
    }

    private void openHotspotSettings() {
        Intent[] candidates = {
                new Intent(Intent.ACTION_MAIN)
                        .setClassName("com.android.settings", "com.android.settings.TetherSettings"),
                new Intent(Settings.ACTION_WIRELESS_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS)
        };
        for (Intent candidate : candidates) {
            try {
                startActivity(candidate);
                return;
            } catch (ActivityNotFoundException | SecurityException exception) {
                Log.w("HotspotToggle", "Settings entry unavailable", exception);
            }
        }
        Toast.makeText(this, R.string.hotspot_settings_unavailable, Toast.LENGTH_LONG).show();
    }
}
