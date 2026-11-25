package app.local.privacyguard;

import java.util.Locale;
import java.util.regex.Pattern;

/** Small, bounded rules used only during a foreground transition. */
final class SplashRule {
    static final long WINDOW_MS = 6000;
    private static final Pattern EXPLICIT = Pattern.compile("跳过广告|跳過廣告|skip (?:ad|ads|advertisement)");
    private static final Pattern SKIP = Pattern.compile("跳过|跳過|skip");
    private static final String COUNT = "(?:[0-9]|1[0-5])\\s*(?:s|秒)?";
    private static final Pattern SUFFIX = Pattern.compile("\\s*(?:[（(]\\s*" + COUNT + "\\s*[）)]|" + COUNT + ")$");
    private static final Pattern PREFIX = Pattern.compile("^(?:[（(]\\s*" + COUNT + "\\s*[）)]|" + COUNT + ")\\s*");
    private static final Pattern AD_ID = Pattern.compile(
            "(?:[a-z0-9]+_)*(?:splash|splashad|appopen|ad)_(?:[a-z0-9]+_)*skip(?:_[a-z0-9]+)*"
            + "|(?:[a-z0-9]+_)*skip_(?:splash|ad)(?:_[a-z0-9]+)*");

    static boolean skipLabel(CharSequence raw, String viewId) {
        if (raw == null || raw.length() == 0 || raw.length() > 34) return false;
        String text = raw.toString().trim().toLowerCase(Locale.ROOT);
        String label = PREFIX.matcher(SUFFIX.matcher(text).replaceFirst("")).replaceFirst("").trim();
        if (EXPLICIT.matcher(label).matches()) return true;
        return SKIP.matcher(label).matches() && (adId(viewId) || !label.equals(text));
    }

    static boolean adId(String viewId) {
        int split = viewId == null ? -1 : viewId.indexOf(":id/");
        return split >= 0 && AD_ID.matcher(viewId.substring(split + 4)).matches();
    }
    static boolean xhsLaunch(String pkg, String activity) {
        return "com.xingin.xhs".equals(pkg) &&
                ("com.xingin.advert.intersitial.ui2.view.SplashAdActivity".equals(activity) ||
                 "com.xingin.advert.intersitial.ui2.view.dynamic.SplashDynamicActivity".equals(activity));
    }
    static boolean xhsDirectLabel(CharSequence raw) {
        if (raw == null || raw.length() > 30) return false;
        String text = SUFFIX.matcher(raw.toString().trim().toLowerCase(Locale.ROOT)).replaceFirst("").trim();
        return SKIP.matcher(text).matches() || text.equals("skip the ad") || text.equals("jump over");
    }
    static boolean ctripActivity(String pkg, String activity) {
        return "ctrip.android.view".equals(pkg) &&
                ("ctrip.business.splash.CtripSplashActivity".equals(activity) ||
                 "ctrip.android.publicproduct.home.business.activity.CtripHomeActivity".equals(activity));
    }
    static int timerValue(CharSequence text) {
        if (text == null || text.length() == 0 || text.length() > 2) return -1;
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return -1;
            value = value * 10 + c - '0';
        }
        return value >= 2 && value <= 15 && text.charAt(0) != '0' ? value : -1;
    }
    static boolean corner(int left, int top, int right, int bottom, int width, int height, float density) {
        if (width <= 0 || height <= 0 || density <= 0 || left < 0 || top < 0 || right > width || bottom > height) return false;
        int w = right - left, h = bottom - top;
        if (w <= 0 || h <= 0 || w > 170 * density || w > width * .44 || h > 78 * density) return false;
        int cx = left + w / 2, cy = top + h / 2;
        return (cy < height * .25 && (cx > width * .66 || cx < width * .34)) || (cy > height * .79 && cx > width * .66);
    }

    static final class Launch {
        private String lastApp = "", target = "";
        private int window = -1;
        private long deadline;
        void home() { lastApp = ""; cancel(); }
        void cancel() { target = ""; window = -1; deadline = 0; }

        void foreground(String pkg, int id, long now) {
            if (!pkg.equals(lastApp)) {
                lastApp = target = pkg;
                deadline = now + WINDOW_MS;
                window = id;
            } else if (!target.isEmpty() && now < deadline) {
                window = id;
            } else {
                cancel();
            }
        }
        boolean accepts(String pkg, int id, long now) {
            return !target.isEmpty() && target.equals(pkg) && window == id && now < deadline;
        }
        String target() { return target; }
        int window() { return window; }
    }
    private SplashRule() {}
}
