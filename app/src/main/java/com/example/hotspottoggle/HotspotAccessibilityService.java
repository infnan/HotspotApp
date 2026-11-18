package com.example.hotspottoggle;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class HotspotAccessibilityService extends AccessibilityService {
    private static volatile HotspotAccessibilityService connected;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Request pending;
    private final Runnable poll = this::inspect;

    private static final class Request {
        final CountDownLatch finished = new CountDownLatch(1);
        final long deadline = SystemClock.elapsedRealtime() + 25000;
        volatile RuntimeException error;
        boolean clicked;
        long lastNavigation;
        int navigations;
        String screen = "No settings window received";
    }

    static boolean isConnected() {
        return connected != null;
    }

    static void enableHotspot(Context context) throws InterruptedException {
        HotspotAccessibilityService service = connected;
        if (service == null) {
            throw new IllegalStateException(context.getString(R.string.accessibility_needed));
        }
        Request request = new Request();
        service.handler.post(() -> service.begin(request));
        try {
            if (!request.finished.await(27000, TimeUnit.MILLISECONDS)) {
                service.handler.post(() -> service.cancel(request));
                throw new IllegalStateException("Accessibility task did not finish");
            }
        } catch (InterruptedException exception) {
            service.handler.post(() -> service.cancel(request));
            throw exception;
        }
        if (request.error != null) {
            throw request.error;
        }
    }

    @Override
    protected void onServiceConnected() {
        connected = this;
    }

    private void begin(Request request) {
        if (pending != null) {
            request.error = new IllegalStateException("Accessibility task already running");
            request.finished.countDown();
            return;
        }
        pending = request;
        try {
            if (SystemClock.elapsedRealtime() >= request.deadline) {
                throw new IllegalStateException("Accessibility request expired");
            }
            KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if ((keyguard != null && keyguard.isKeyguardLocked())
                    || (power != null && !power.isInteractive())) {
                throw new IllegalStateException(getString(R.string.unlock_needed));
            }
            Intent[] entries = {
                    new Intent(Intent.ACTION_MAIN).setClassName("com.android.settings",
                            "com.android.settings.TetherSettings"),
                    new Intent(Settings.ACTION_WIRELESS_SETTINGS)
            };
            boolean opened = false;
            for (Intent entry : entries) {
                try {
                    startActivity(entry.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    opened = true;
                    break;
                } catch (ActivityNotFoundException | SecurityException exception) {
                    request.screen = exception.toString();
                }
            }
            if (!opened) {
                throw new IllegalStateException("Cannot open hotspot settings: " + request.screen);
            }
            handler.postDelayed(poll, 400);
        } catch (RuntimeException exception) {
            finishRequest(exception);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (pending != null) {
            handler.removeCallbacks(poll);
            handler.post(poll);
        }
    }

    private void inspect() {
        Request request = pending;
        if (request == null) {
            return;
        }
        try {
            int state = new HotspotController(this).readApState();
            if (state == 13) {
                if (!performGlobalAction(GLOBAL_ACTION_HOME)) {
                    throw new IllegalStateException("Hotspot enabled, but could not return Home");
                }
                finishRequest(null);
                return;
            }
            if (request.clicked && state == 14) {
                throw new IllegalStateException("AP failed after clicking system switch; screen="
                        + request.screen);
            }
            if (SystemClock.elapsedRealtime() >= request.deadline) {
                throw new IllegalStateException("Hotspot accessibility timeout; clicked="
                        + request.clicked + "; screen=" + request.screen);
            }
            if (!request.clicked && state != 12) {
                inspectSettings(request);
            }
            if (pending != null) {
                handler.removeCallbacks(poll);
                handler.postDelayed(poll, 350);
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            finishRequest(new IllegalStateException("Accessibility hotspot start failed", exception));
        }
    }

    private void inspectSettings(Request request) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        try {
            if (!"com.android.settings".contentEquals(
                    root.getPackageName() == null ? "" : root.getPackageName())) {
                return;
            }
            collect(root, nodes);
            StringBuilder snapshot = new StringBuilder();
            for (AccessibilityNodeInfo node : nodes) {
                if (snapshot.length() < 6000) {
                    snapshot.append(label(node)).append(" [")
                            .append(node.getViewIdResourceName()).append("] checked=")
                            .append(node.isChecked()).append("; ");
                }
            }
            request.screen = snapshot.toString();
            AccessibilityNodeInfo toggle = null;
            boolean hotspotTitle = false;
            int visibleSwitches = 0;
            boolean switchBar = false;
            AccessibilityNodeInfo onlySwitch = null;
            for (AccessibilityNodeInfo node : nodes) {
                String viewId = node.getViewIdResourceName();
                if (viewId != null && viewId.endsWith("/switch_bar")) {
                    switchBar = true;
                }
                if (hotspotLabel(label(node))) {
                    hotspotTitle = true;
                    AccessibilityNodeInfo row = node;
                    for (int depth = 0; depth < 3 && row != null; depth++) {
                        AccessibilityNodeInfo found = row.isClickable() || row.isCheckable()
                                ? findSwitch(row, 0) : null;
                        if (found != null) {
                            toggle = found;
                            break;
                        }
                        AccessibilityNodeInfo parent = row.getParent();
                        if (row != node) {
                            row.recycle();
                        }
                        row = parent;
                    }
                    if (row != null && row != node) {
                        row.recycle();
                    }
                    if (toggle != null) {
                        break;
                    }
                }
            }
            if (toggle == null && hotspotTitle) {
                for (AccessibilityNodeInfo node : nodes) {
                    String viewId = node.getViewIdResourceName();
                    if (viewId != null && viewId.endsWith("/switch_bar")) {
                        switchBar = true;
                    }
                    if (node.isCheckable() && node.isVisibleToUser()
                            && node.getClassName() != null
                            && node.getClassName().toString().endsWith("Switch")) {
                        visibleSwitches++;
                        onlySwitch = node;
                    }
                }
                if (switchBar && visibleSwitches == 1) {
                    toggle = AccessibilityNodeInfo.obtain(onlySwitch);
                }
            }
            if (toggle != null) {
                try {
                    if (toggle.isEnabled() && !toggle.isChecked()) {
                        request.clicked = click(toggle);
                    }
                } finally {
                    toggle.recycle();
                }
                return;
            }
            if (request.navigations < 4
                    && SystemClock.elapsedRealtime() - request.lastNavigation > 1000) {
                for (AccessibilityNodeInfo node : nodes) {
                    String text = label(node);
                    if (hotspotLabel(text) || text.equals("网络共享与便携式热点")
                            || text.equals("网络共享和便携式热点")
                            || text.equals("tethering & portable hotspot")
                            || text.equals("tethering & mobile hotspot")) {
                        if (click(node)) {
                            request.navigations++;
                            request.lastNavigation = SystemClock.elapsedRealtime();
                            break;
                        }
                    }
                }
            }
        } finally {
            for (AccessibilityNodeInfo node : nodes) {
                node.recycle();
            }
            root.recycle();
        }
    }

    private static boolean hotspotLabel(String text) {
        return text.equals("便携式wlan热点") || text.equals("便携式 wlan 热点")
                || text.equals("wlan热点") || text.equals("wlan 热点")
                || text.equals("wi-fi热点") || text.equals("wi-fi 热点")
                || text.equals("便携式热点") || text.equals("个人热点")
                || text.equals("移动热点") || text.equals("portable wi-fi hotspot")
                || text.equals("wi-fi hotspot") || text.equals("mobile hotspot");
    }

    private static String label(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (text == null || text.length() == 0) {
            text = node.getContentDescription();
        }
        return text == null ? "" : text.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> nodes) {
        if (nodes.size() >= 250) {
            return;
        }
        nodes.add(AccessibilityNodeInfo.obtain(node));
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) {
                collect(child, nodes);
                child.recycle();
            }
        }
    }

    private static AccessibilityNodeInfo findSwitch(AccessibilityNodeInfo node, int depth) {
        if (node.isCheckable() && node.isVisibleToUser()) {
            return AccessibilityNodeInfo.obtain(node);
        }
        if (depth >= 2) {
            return null;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) {
                AccessibilityNodeInfo found = findSwitch(child, depth + 1);
                child.recycle();
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean click(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo target = AccessibilityNodeInfo.obtain(node);
        try {
            for (int depth = 0; depth < 4 && target != null; depth++) {
                if (target.isClickable() && target.isEnabled() && target.isVisibleToUser()) {
                    return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                AccessibilityNodeInfo parent = target.getParent();
                target.recycle();
                target = parent;
            }
            return false;
        } finally {
            if (target != null) {
                target.recycle();
            }
        }
    }

    private void cancel(Request request) {
        if (pending == request) {
            finishRequest(new IllegalStateException("Accessibility request cancelled"));
        }
    }

    private void finishRequest(RuntimeException error) {
        handler.removeCallbacks(poll);
        Request request = pending;
        pending = null;
        if (request != null) {
            request.error = error;
            request.finished.countDown();
        }
    }

    @Override
    public void onInterrupt() {
        finishRequest(new IllegalStateException("Accessibility service interrupted"));
    }

    @Override
    public void onDestroy() {
        if (connected == this) {
            connected = null;
        }
        finishRequest(new IllegalStateException("Accessibility service disconnected"));
        super.onDestroy();
    }
}
