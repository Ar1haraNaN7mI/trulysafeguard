package app.local.privacyguard;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

/** Matches only the native two-TextView skip layout whose tap listener was verified in Ctrip 8.98.1. */
final class SplashTouch {
    private static final String PKG = "ctrip.android.view";
    private static final byte[] SIGNER = new byte[] {
        -127,85,-36,6,68,-2,-43,23,-24,-17,107,-17,-105,24,-61,99,
        -48,-21,62,-78,-62,44,6,69,80,68,-40,-126,104,-67,-49,10
    };
    static boolean verifiedBuild(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(PKG, PackageManager.GET_SIGNATURES);
            return p.versionCode == 2140 && "8.98.1".equals(p.versionName) &&
                    p.signatures != null && p.signatures.length == 1 &&
                    Arrays.equals(SIGNER, MessageDigest.getInstance("SHA-256").digest(p.signatures[0].toByteArray()));
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException | RuntimeException unknown) {
            return false;
        }
    }
    static final class Candidate {
        final Rect button, container;
        final int count;
        Candidate(Rect button, Rect container, int count) {
            this.button = button; this.container = container; this.count = count;
        }
        boolean sameBounds(Candidate previous) {
            return previous != null && button.equals(previous.button) && container.equals(previous.container);
        }
    }
    private static boolean nativeNode(AccessibilityNodeInfo n, String pkg, int win, String type) {
        return n != null && pkg.contentEquals(n.getPackageName() == null ? "" : n.getPackageName()) &&
                type.contentEquals(n.getClassName() == null ? "" : n.getClassName()) && n.getWindowId() == win &&
                n.isVisibleToUser() && n.isEnabled() && !n.isEditable() && !n.isPassword() && !n.isScrollable() &&
                !n.isCheckable() && !n.isLongClickable() && !n.isClickable() &&
                !n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
    }
    static Candidate match(AccessibilityNodeInfo label, String pkg, int win, Rect area, float density) {
        if (label == null || !label.refresh() || !nativeNode(label, pkg, win, "android.widget.TextView") || !"跳过广告".contentEquals(
                    label.getText() == null ? "" : label.getText())) return null;
        AccessibilityNodeInfo row = label.getParent(), timer = null, second = null;
        try {
            if (row == null || !row.refresh() || !nativeNode(row, pkg, win, "android.widget.LinearLayout") || row.getChildCount() != 2) return null;
            timer = row.getChild(0);
            second = row.getChild(1);
            if (timer == null || !timer.refresh() || !nativeNode(timer, pkg, win, "android.widget.TextView") || !label.equals(second)) return null;
            int count = SplashRule.timerValue(timer.getText());
            if (count < 0) return null;
            Rect box = new Rect(), left = new Rect(), right = new Rect();
            row.getBoundsInScreen(box); timer.getBoundsInScreen(left); label.getBoundsInScreen(right);
            if (area.isEmpty() || !area.contains(box) || !box.contains(left) || !box.contains(right) ||
                    left.isEmpty() || right.isEmpty() || left.right > right.left || right.left - left.right > 10*density ||
                    Math.abs(left.centerY()-right.centerY()) > 4*density || right.width() < 16*density ||
                    box.width() < 35*density || box.width() > 150*density ||
                    box.height() < 26*density || box.height() > 38*density ||
                    box.centerX() < area.left + area.width() * .73 || box.centerY() > area.top + area.height() * .24 ||
                    area.right - box.right > 36*density) return null;
            return new Candidate(right, box, count);
        } finally {
            if (timer != null) timer.recycle();
            if (second != null) second.recycle();
            if (row != null) row.recycle();
        }
    }
    /** Uses only OS window type, bounds and layer. System bars outside the live target are permitted. */
    static boolean unobscured(List<AccessibilityWindowInfo> windows, int winId, Rect button) {
        if (windows == null) return false;
        try {
            AccessibilityWindowInfo active = null;
            for (AccessibilityWindowInfo w : windows) if (w.getId() == winId) active = w;
            if (active == null || !active.isFocused() || !active.isActive() ||
                    active.getType() != AccessibilityWindowInfo.TYPE_APPLICATION ||
                    (Build.VERSION.SDK_INT >= 30 && active.getDisplayId() != Display.DEFAULT_DISPLAY)) return false;
            Rect surface = new Rect();
            active.getBoundsInScreen(surface);
            if (!surface.contains(button)) return false;
            int x = button.centerX(), y = button.centerY();
            Region region = new Region();
            for (AccessibilityWindowInfo w : windows) {
                if (w == active || w.getLayer() < active.getLayer()) continue;
                // OEM back-gesture strips can cover a few pixels of a button's outer
                // edge. Check the actual action point against each higher window.
                if (Build.VERSION.SDK_INT >= 30) {
                    w.getRegionInScreen(region);
                    if (region.contains(x, y)) return false;
                } else {
                    w.getBoundsInScreen(surface);
                    if (surface.contains(x, y)) return false;
                }
            }
            return true;
        } finally {
            for (AccessibilityWindowInfo w : windows) w.recycle();
        }
    }
    private SplashTouch() {}
}
