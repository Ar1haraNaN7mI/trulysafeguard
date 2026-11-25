import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognizes the current standard projection, with no display, frame or recording access. */
final class ScreenPolicy {
    private static final String PACKAGE = "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+";
    private static final String TYPE = "TYPE_(?:SCREEN_CAPTURE|MIRRORING|PRESENTATION)";
    private static final Pattern LEGACY = Pattern.compile("\\((" + PACKAGE + "), uid=([0-9]{1,9})\\): " + TYPE);
    private static final Pattern MODERN_NAME = Pattern.compile("packageName: (" + PACKAGE + ")");
    private static final Pattern MODERN_UID = Pattern.compile("uid: ([0-9]{1,9})");
    private static final Map<String, Long> recentStops = new LinkedHashMap<String, Long>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) { return size() > 128; }
    };

    private ScreenPolicy() {}

    private static Exception unknown() {
        return new IllegalStateException("未能核实系统的当前投屏来源，本轮未停止应用");
    }

    static String current() throws Exception {
        GuardPhone.Result result = GuardPhone.raw("dumpsys", "media_projection");
        if (result.code != 0 || !result.err.trim().isEmpty()) throw unknown();
        return parse(result.out);
    }

    static String parse(String report) {
        if (report == null || report.length() > 524288)
            throw new IllegalStateException("系统投屏报告长度异常");
        List<String> lines = new ArrayList<>();
        for (String line : report.split("\n")) {
            String cleaned = line.trim();
            if (!cleaned.isEmpty()) lines.add(cleaned);
        }
        if (lines.size() < 3 || !lines.get(0).equals("MEDIA PROJECTION MANAGER (dumpsys media_projection)") ||
                !lines.get(1).equals("Media Projection:") || Collections.frequency(lines, "Media Projection:") != 1)
            throw new IllegalStateException("系统未返回可识别的当前投屏记录");
        String owner = lines.get(2);
        if (owner.equals("null")) return "";
        Matcher legacy = LEGACY.matcher(owner);
        if (legacy.matches()) return legacy.group(1) + "#" + Integer.parseInt(legacy.group(2));
        if (!owner.equals("Projection:") || lines.size() < 8 ||
                Collections.frequency(lines, "Projection:") != 1)
            throw new IllegalStateException("系统投屏记录格式未知");
        Matcher name = MODERN_NAME.matcher(lines.get(3));
        Matcher uid = MODERN_UID.matcher(lines.get(4));
        if (!name.matches() || !uid.matches() || !lines.get(5).equals("userHandle: UserHandle{0}") ||
                !lines.get(6).matches("type: " + TYPE) || !lines.contains("privileged: false") ||
                lines.contains("privileged: true"))
            throw new IllegalStateException("投屏来源可能为特权应用、其他用户或无法识别的会话");
        return name.group(1) + "#" + Integer.parseInt(uid.group(1));
    }

    static String enforce(String pkg, int uid, String observed) throws Exception {
        // GuardPhone also checks install identity, ordinary application status, and unique ownership of the UID.
        if (pkg == null || pkg.length() > 255 || !pkg.matches(PACKAGE) ||
                pkg.equals("app.local.privacyguard") || uid < 10000 || uid > 19999) throw unknown();
        String expected = pkg + "#" + uid;
        if (!expected.equals(observed)) {
            Long last = recentStops.get(expected);
            return last != null && System.nanoTime() - last < 60_000_000_000L
                    ? "监测中，最近一分钟已停止该应用的投屏" : "手机正在监测标准投屏";
        }
        if (!expected.equals(current())) return "投屏已变化，本轮未停止应用";
        GuardPhone.cmd("am", "force-stop", "--user", "0", pkg);
        if (expected.equals(current())) return "已请求停止应用，投屏结束尚未确认";
        recentStops.put(expected, System.nanoTime());
        return "已停止所选应用，原投屏会话已结束";
    }
}
