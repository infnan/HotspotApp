package com.example.hotspottoggle;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.IntConsumer;

final class HotspotController {
    private static final int AP_DISABLING = 10;
    private static final int AP_DISABLED = 11;
    private static final int AP_ENABLING = 12;
    private static final int AP_ENABLED = 13;
    private static final int AP_FAILED = 14;
    private final Context context;
    private final WifiManager wifiManager;
    private final Method getApState;

    HotspotController(Context context) throws ReflectiveOperationException {
        this.context = context.getApplicationContext();
        wifiManager = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) {
            throw new NoSuchMethodException("Wi-Fi service unavailable");
        }
        getApState = WifiManager.class.getMethod("getWifiApState");
    }

    int readApState() throws ReflectiveOperationException {
        return (Integer) invoke(getApState);
    }

    int toggle(IntConsumer onStarting) throws ReflectiveOperationException, InterruptedException {
        int state = readApState();
        if (state == AP_ENABLING || state == AP_DISABLING) {
            return R.string.hotspot_busy;
        }
        if (state != AP_ENABLED && state != AP_DISABLED && state != AP_FAILED) {
            throw new IllegalStateException("Unknown AP state: " + state);
        }
        if (state != AP_ENABLED) {
            if (!HotspotAccessibilityService.isConnected()) {
                return R.string.accessibility_needed;
            }
            onStarting.accept(R.string.hotspot_starting);
            HotspotAccessibilityService.enableHotspot(context);
            return R.string.hotspot_on;
        }
        onStarting.accept(R.string.hotspot_stopping);
        Method setApEnabled = WifiManager.class.getMethod("setWifiApEnabled",
                WifiConfiguration.class, boolean.class);
        if (!Boolean.TRUE.equals(invoke(setApEnabled, null, false))) {
            throw new IllegalStateException("setWifiApEnabled(false) returned false");
        }
        long deadline = SystemClock.elapsedRealtime() + 20000;
        do {
            state = readApState();
            if (state == AP_DISABLED) {
                return R.string.hotspot_off;
            }
            if (state == AP_FAILED) {
                throw new IllegalStateException("AP failed while disabling");
            }
            Thread.sleep(250);
        } while (SystemClock.elapsedRealtime() < deadline);
        return R.string.toggle_timeout;
    }

    private Object invoke(Method method, Object... arguments) throws ReflectiveOperationException {
        try {
            return method.invoke(wifiManager, arguments);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof SecurityException) {
                throw new SecurityException(method.getName() + ": "
                        + exception.getCause().getMessage(), exception.getCause());
            }
            throw exception;
        }
    }
}
