package com.example.hotspottoggle;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.text.DateFormat;
import java.util.Date;

final class ErrorReport {
    static final String EXTRA_REPORT = "com.example.hotspottoggle.ERROR_REPORT";

    private ErrorReport() {
    }

    static void show(Context context, int message, Throwable failure) {
        String version;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0);
            version = info.versionName + " (" + info.versionCode + ")";
        } catch (PackageManager.NameNotFoundException exception) {
            version = context.getPackageName();
        }
        String report = context.getString(message)
                + "\n\n时间：" + DateFormat.getDateTimeInstance().format(new Date())
                + "\n应用版本：" + version
                + "\n设备：" + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid：" + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + "\n系统版本：" + Build.DISPLAY
                + "\n修改系统设置授权：" + Settings.System.canWrite(context)
                + "\n\n" + (failure == null ? context.getString(R.string.error_no_exception)
                        : Log.getStackTraceString(failure));
        Intent intent = new Intent(context, ErrorActivity.class)
                .putExtra(EXTRA_REPORT, report)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
