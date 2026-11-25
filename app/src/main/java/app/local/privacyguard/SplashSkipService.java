package app.local.privacyguard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethod;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

/** Local, foreground-launch-only skip controls. No screenshots, input events, stored history or networking. */
public final class SplashSkipService extends AccessibilityService {
    static final String REQUESTED = "splash_skip_requested";
    private static final String[] QUERIES = {"跳过", "跳過", "skip"};
    private static final String[] FISH_SPLASH_IDS = {"fanti_ad_txt_skip","ksad_splash_skip_left_view", "ksad_splash_skip_right_view",
            "ksad_splash_circle_skip_left_view", "ksad_splash_circle_skip_right_view"};
    private static final String[] CAINIAO_SPLASH_IDS = {"fanti_ad_txt_skip"};
    private static final String[] TAOBAO_SPLASH_IDS = {"bootimage_ad_pop_skip"};
    private static final String[] NO_IDS = {};

    private static String[] splashIds(String pkg) {
        switch (pkg) {
            case "com.cainiao.wireless": return CAINIAO_SPLASH_IDS;
            case "com.taobao.idlefish": return FISH_SPLASH_IDS;
            case "com.taobao.taobao": return TAOBAO_SPLASH_IDS;
            default: return NO_IDS;
        }
    }
    private static volatile SplashSkipService live;
    static volatile long skipCount;
    static volatile String lastPackage = "";
    static volatile long completedTouchCount;
    private SplashTouch.Candidate firstCtrip;
    private long firstSeen;
    private String activity = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SplashRule.Launch launch = new SplashRule.Launch();
    private final Runnable scan = this::inspect;

    static boolean connected() { return live != null; }
    static boolean requested(Context c) { return Prefs.prefs(c).getBoolean(REQUESTED, true); }
    static boolean granted(Context c) {
        AccessibilityManager manager = c.getSystemService(AccessibilityManager.class);
        ComponentName ours = new ComponentName(c, SplashSkipService.class);
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (ours.equals(ComponentName.unflattenFromString(info.getId()))) return true;
        }
        return false;
    }
    static void stop(Context c) {
        Prefs.set(c, REQUESTED, false);
        SplashSkipService service = live;
        if (service != null) {
            service.cancelScan();
            service.disableSelf();
        }
    }
    private void cancelScan() {
        handler.removeCallbacks(scan);
        launch.cancel();
        firstCtrip = null;
        activity = "";
    }
    @Override protected void onServiceConnected() {
        live = this;
        super.onServiceConnected();
    }
    @Override public void onInterrupt() { cancelScan(); }
    @Override public void onDestroy() {
        if (live == this) live = null;
        cancelScan();
        super.onDestroy();
    }
    @Override protected void dump(FileDescriptor fd, PrintWriter out, String[] args) {
        out.println("skipCount=" + skipCount);
        out.println("lastPackage=" + lastPackage);
        out.println("completedTouchCount=" + completedTouchCount);
    }
    private boolean locked() {
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        return keyguard == null || keyguard.isKeyguardLocked();
    }
    private static boolean sensitive(String name) {
        String simple = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return simple.contains("permission") || simple.contains("password") || simple.contains("login") ||
                simple.contains("authenticat") || simple.contains("payment") || simple.contains("checkout") ||
                simple.contains("biometric") || simple.contains("install") || simple.contains("keyguard") ||
                simple.contains("passcode") || simple.contains("consent") || simple.contains("authorize") ||
                simple.contains("verification") || simple.contains("wallet") || simple.contains("bankcard");
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        // Do not fetch event text, input, notification data or the source node.
        if (!requested(this) || locked() || event.getPackageName() == null || event.getClassName() == null) {
            cancelScan();
            return;
        }
        String pkg = event.getPackageName().toString(), cls = event.getClassName().toString();
        int id = event.getWindowId();
        try {
            PackageManager pm = getPackageManager();
            ComponentName component = new ComponentName(pkg, cls);
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg);
            if (pm.resolveActivity(home, 0) != null || cls.endsWith(".RecentsActivity")) {
                handler.removeCallbacks(scan);
                launch.home();
                return;
            }
            // Only a registered activity can open or extend the existing launch observation.
            pm.getActivityInfo(component, 0);
            ApplicationInfo app = pm.getApplicationInfo(pkg, 0);
            if (pkg.equals(getPackageName()) || (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0 ||
                    pm.getLaunchIntentForPackage(pkg) == null ||
                    !pm.queryIntentServices(new Intent(InputMethod.SERVICE_INTERFACE).setPackage(pkg), 0).isEmpty()) {
                // Going through Settings/Guard and then launching the same app counts as a new foreground transition.
                if (!sensitive(cls)) launch.home();
                cancelScan();
                return;
            }
            if (id < 0 || sensitive(cls)) { cancelScan(); return; }
            long now = SystemClock.uptimeMillis();
            if (!pkg.equals(launch.target()) || id != launch.window() || !activity.equals(cls)) firstCtrip = null;
            activity = cls;
            launch.foreground(pkg, id, now);
            if (launch.accepts(pkg, id, now) && !handler.hasMessages(SCAN_MESSAGE)) schedule(80);
        } catch (PackageManager.NameNotFoundException widgetEvent) {
            // Transient non-Activity events also arrive during legitimate warm returns. They never
            // start a scan or extend its timer. Each click checks the original window and occlusion.
        } catch (RuntimeException unavailable) {
            cancelScan();
        }
    }
    private static final int SCAN_MESSAGE = 1;
    private void schedule(long delayMs) {
        handler.removeCallbacks(scan);
        android.os.Message msg = android.os.Message.obtain(handler, scan);
        msg.what = SCAN_MESSAGE;
        handler.sendMessageDelayed(msg, delayMs);
    }
    private void inspect() {
        String pkg = launch.target();
        if (!requested(this) || locked() || !launch.accepts(pkg, launch.window(), SystemClock.uptimeMillis())) {
            cancelScan();
            return;
        }
        AccessibilityNodeInfo root = null;
        try {
            root = Build.VERSION.SDK_INT >= 33 ? getRootInActiveWindow(0) : getRootInActiveWindow();
            if (root != null && pkg.contentEquals(empty(root.getPackageName())) && root.getWindowId() == launch.window()) {
                Rect area = new Rect();
                root.getBoundsInScreen(area);
                // Direct view IDs are used only for a verified application's SDK resources.
                for (String name : splashIds(pkg)) {
                    if (tryMatches(root.findAccessibilityNodeInfosByViewId(pkg + ":id/" + name), pkg, area, true)) return;
                }
                for (String token : QUERIES) {
                    if (tryMatches(root.findAccessibilityNodeInfosByText(token), pkg, area, false)) return;
                }
                if (SplashRule.xhsLaunch(pkg, activity) &&
                        tryMatches(root.findAccessibilityNodeInfosByText("jump over"), pkg, area, false)) return;
                if (SplashRule.ctripActivity(pkg, activity) && tryCtrip(root, pkg, area)) return;
            }
        } catch (RuntimeException changedWindow) {
            cancelScan();
            return;
        } finally {
            if (root != null) root.recycle();
        }
        if (launch.accepts(pkg, launch.window(), SystemClock.uptimeMillis())) schedule(480);
    }
    private static CharSequence empty(CharSequence v) { return v == null ? "" : v; }
    private boolean corner(AccessibilityNodeInfo node, Rect area) {
        Rect box = new Rect();
        node.getBoundsInScreen(box);
        box.offset(-area.left, -area.top);
        return SplashRule.corner(box.left, box.top, box.right, box.bottom, area.width(), area.height(),
                getResources().getDisplayMetrics().density);
    }
    private static boolean nativePath(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo parent = node.getParent();
        try {
            for (int i = 0; parent != null && i < 26; i++) {
                String cls = empty(parent.getClassName()).toString().toLowerCase(Locale.ROOT);
                if (parent.isPassword() || parent.isEditable() || cls.contains("webview")) return false;
                AccessibilityNodeInfo next = parent.getParent();
                parent.recycle();
                parent = next;
            }
            return parent == null;
        } finally {
            if (parent != null) parent.recycle();
        }
    }
    private static boolean sameNodeScope(AccessibilityNodeInfo node, String pkg, int window) {
        return node.getWindowId() == window && pkg.contentEquals(empty(node.getPackageName())) &&
                node.isVisibleToUser() && node.isEnabled() && !node.isPassword() && !node.isEditable()
                && !node.isCheckable() && !node.isScrollable() && !node.isLongClickable();
    }
    private boolean tryMatches(List<AccessibilityNodeInfo> nodes, String pkg, Rect area, boolean exactId) {
        if (nodes == null) return false;
        try {
            if (nodes.size() > 8) return false;
            for (AccessibilityNodeInfo node : nodes) {
                if (!sameNodeScope(node, pkg, launch.window()) || !corner(node, area)) continue;
                String cls = empty(node.getClassName()).toString();
                if (!exactId && !cls.startsWith("android.widget.")) continue;
                String viewId = node.getViewIdResourceName();
                if (viewId != null && !viewId.startsWith(pkg + ":id/")) viewId = null;
                boolean xhsText = node.isClickable() && cls.equals("android.widget.TextView") &&
                        SplashRule.xhsLaunch(pkg, activity) && SplashRule.xhsDirectLabel(node.getText());
                if (!exactId && !xhsText && !SplashRule.skipLabel(node.getText(), viewId) &&
                        !SplashRule.skipLabel(node.getContentDescription(), viewId)) continue;
                if (!nativePath(node)) continue;
                AccessibilityNodeInfo action = node.isClickable() ? node : node.getParent();
                try {
                    if (action != node && !exactId && !SplashRule.adId(viewId)
                            && (action == null || !SplashRule.adId(action.getViewIdResourceName()))) continue;
                    if (action == null || !sameNodeScope(action, pkg, launch.window()) || !action.isClickable()
                            || !corner(action, area) || !action.refresh() || !sameNodeScope(action, pkg, launch.window())
                            || !corner(action, area) || !launch.accepts(pkg, action.getWindowId(), SystemClock.uptimeMillis())) continue;
                    Rect hit = new Rect();
                    action.getBoundsInScreen(hit);
                    if (!SplashTouch.unobscured(getWindows(), action.getWindowId(), hit)) continue;
                    boolean accepted = action.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    cancelScan(); // At most one attempted click per foreground transition.
                    if (accepted) {
                        lastPackage = pkg;
                        skipCount++;
                    }
                    return true;
                } finally {
                    if (action != null && action != node) action.recycle();
                }
            }
            return false;
        } finally {
            for (AccessibilityNodeInfo node : nodes) node.recycle();
        }
    }
    private SplashTouch.Candidate ctripTarget(AccessibilityNodeInfo root, String pkg, Rect area) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("跳过广告");
        if (nodes == null) return null;
        try {
            if (nodes.size() != 1 || !nativePath(nodes.get(0))) return null;
            return SplashTouch.match(nodes.get(0), pkg, launch.window(), area, getResources().getDisplayMetrics().density);
        } finally {
            for (AccessibilityNodeInfo node : nodes) node.recycle();
        }
    }
    private boolean tryCtrip(AccessibilityNodeInfo root, String pkg, Rect area) {
        SplashTouch.Candidate found = ctripTarget(root, pkg, area);
        if (found == null) { firstCtrip = null; return false; }
        long now = SystemClock.uptimeMillis();
        if (!found.sameBounds(firstCtrip) || found.count > firstCtrip.count || now - firstSeen > 2800) {
            firstCtrip = found; firstSeen = now; return false;
        }
        if (firstCtrip.count - found.count != 1 || now - firstSeen < 180 || !SplashTouch.verifiedBuild(this)) return false;
        int window = launch.window();
        AccessibilityNodeInfo current = Build.VERSION.SDK_INT >= 33 ? getRootInActiveWindow(0) : getRootInActiveWindow();
        try {
            if (current == null || !current.refresh() || current.getWindowId() != window ||
                    !pkg.contentEquals(empty(current.getPackageName()))) return false;
            Rect currentArea = new Rect();
            current.getBoundsInScreen(currentArea);
            SplashTouch.Candidate fresh = ctripTarget(current, pkg, currentArea);
            if (fresh == null || !fresh.sameBounds(found) || fresh.count != found.count ||
                    !SplashTouch.unobscured(getWindows(), window, fresh.button) ||
                    !requested(this) || locked() || !launch.accepts(pkg, window, SystemClock.uptimeMillis())) return false;
            Path point = new Path();
            point.moveTo(fresh.button.exactCenterX(), fresh.button.exactCenterY());
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(point, 0, 45)).build();
            cancelScan();
            dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription description) { completedTouchCount++; }
            }, handler);
            return true;
        } finally {
            if (current != null) current.recycle();
        }
    }
}
