package com.example.hotspottoggle;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HotspotToggleService extends Service {
    private static final String TAG = "HotspotToggle";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean running;
    private int latestStartId;
    private volatile boolean destroyed;
    private Throwable failure;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (running) {
            Toast.makeText(this, R.string.hotspot_busy, Toast.LENGTH_SHORT).show();
            return START_NOT_STICKY;
        }
        running = true;
        failure = null;
        worker.execute(() -> {
            int result = performToggle();
            mainHandler.post(() -> {
                if (!destroyed) {
                    try {
                        if (result == R.string.hotspot_on || result == R.string.hotspot_off
                                || result == R.string.hotspot_busy) {
                            Toast.makeText(getApplicationContext(), result, Toast.LENGTH_LONG).show();
                        } else {
                            ErrorReport.show(this, result, failure);
                        }
                    } catch (RuntimeException exception) {
                        Log.e(TAG, "Could not display error report", exception);
                        Toast.makeText(getApplicationContext(), result, Toast.LENGTH_LONG).show();
                    } finally {
                        running = false;
                        stopSelf(latestStartId);
                    }
                }
            });
        });
        return START_NOT_STICKY;
    }

    private int performToggle() {
        PowerManager.WakeLock wakeLock = null;
        try {
            if (!Settings.System.canWrite(this)) {
                return R.string.permission_needed;
            }
            Log.i(TAG, "Device: " + Build.MANUFACTURER + " " + Build.MODEL
                    + ", Android " + Build.VERSION.RELEASE + ", build " + Build.DISPLAY
                    + ", WRITE_SETTINGS allowed");
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "HotspotToggle:Switch");
                wakeLock.acquire(35000);
            }
            return new HotspotController(this).toggle(message -> mainHandler.post(() -> {
                if (!destroyed) {
                    Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
                }
            }));
        } catch (SecurityException exception) {
            Log.w(TAG, "System denied hotspot access", exception);
            failure = exception;
            String reason = exception.getMessage();
            if (reason != null && (reason.contains("MANAGE_USERS")
                    || reason.contains("CREATE_USERS"))) {
                return R.string.user_query_denied;
            }
            return R.string.permission_denied;
        } catch (NoSuchMethodException exception) {
            failure = exception;
            Log.w(TAG, "Hotspot API unavailable", exception);
            return R.string.unsupported_device;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            failure = exception;
            Log.w(TAG, "Hotspot toggle failed", exception);
            return R.string.toggle_failed;
        } catch (InterruptedException exception) {
            failure = exception;
            Thread.currentThread().interrupt();
            return R.string.toggle_failed;
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }
}
