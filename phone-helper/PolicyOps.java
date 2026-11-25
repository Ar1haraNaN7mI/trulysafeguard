import android.os.Build;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fixed operations for an ordinary, user-selected primary-user package. The caller checks UID and install identity. */
final class PolicyOps {
    private static final String OFF = "关闭";
    private static final String CHANGED = "系统设置已由其他途径更改，已保留";
    private static final String NOTIFY = "POST_NOTIFICATION";
    private static final String INSTALL = "REQUEST_INSTALL_PACKAGES";
    private static final String NOTIFY_PERMISSION = "android.permission.POST_NOTIFICATIONS";
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static String currentBoot;

    private PolicyOps() {}

    private static void validate(String pkg) {
        if (pkg == null || pkg.length() > 255 || !PACKAGE.matcher(pkg).matches()
                || "app.local.privacyguard".equals(pkg)) throw new IllegalArgumentException("无效的规则目标");
    }

    private static String forget(JSONObject journal, String key, Runnable save, String status) {
        if (journal.has(key)) {
            journal.remove(key);
            save.run();
        }
        return status;
    }

    private static String sensorState(String pkg) throws Exception {
        GuardPhone.Result r = GuardPhone.raw("cmd", "sensorservice", "get-uid-state", pkg, "--user", "0");
        String state = r.out.trim();
        // Some Android 12 builds return dprintf's byte count including the newline.
        if (r.err.trim().isEmpty() && ("idle".equals(state) || "active".equals(state))
                && (r.code == 0 || r.code == state.length() + 1)) return state;
        throw new IllegalStateException("无法核实当前传感器状态");
    }

    static String sensors(String pkg, boolean enabled, JSONObject journal, Runnable save) throws Exception {
        validate(pkg);
        String key = "sensors";
        JSONObject item = journal.optJSONObject(key);
        if (!enabled && item == null) return OFF;
        if (currentBoot == null) {
            String value = GuardPhone.cmd("settings", "get", "global", "boot_count").trim();
            if (!value.matches("[0-9]+")) throw new IllegalStateException("无法核实本次开机身份");
            currentBoot = value;
        }
        String boot = currentBoot;
        if (item != null && !boot.equals(item.getString("boot"))) {
            // SensorService drops its in-memory override table on reboot.
            forget(journal, key, save, OFF);
            item = null;
        }
        if (!enabled && item == null) return OFF;
        String state = sensorState(pkg);
        if (!enabled) {
            if ("idle".equals(state)) {
                if (!item.getBoolean("applied")) return "上次写入未确认，请重新开启后再关闭以清除覆盖";
                save.run();
                GuardPhone.cmd("cmd", "sensorservice", "reset-uid-state", pkg, "--user", "0");
                return forget(journal, key, save, "已解除本助手的传感器覆盖");
            }
            return forget(journal, key, save, CHANGED);
        }
        if (item != null && item.getBoolean("applied"))
            return "idle".equals(state) ? "已限制，传感器服务报告 idle" : CHANGED;
        if (item == null) {
            item = new JSONObject().put("boot", boot).put("applied", false);
            journal.put(key, item);
        }
        save.run();
        GuardPhone.cmd("cmd", "sensorservice", "set-uid-state", pkg, "idle", "--user", "0");
        if (!"idle".equals(sensorState(pkg))) throw new IllegalStateException("传感器命令已执行，系统尚未确认 idle");
        item.put("applied", true);
        save.run();
        return "已限制，传感器服务报告 idle";
    }

    static String notifications(String pkg, boolean enabled, JSONObject journal, Runnable save) throws Exception {
        validate(pkg);
        String key = "notifications";
        JSONObject item = journal.optJSONObject(key);
        if (!enabled && item == null) return OFF;
        String kind = item == null ? "" : item.getString("kind");
        if (item != null && !"runtime".equals(kind) && !NOTIFY.equals(kind))
            throw new IllegalStateException("原通知控制方式不明，已保留设置");
        // Restore through the same mechanism that performed the original change.
        if (!enabled && NOTIFY.equals(kind)) return appOp(pkg, false, journal, save, key, NOTIFY);
        Grant grant = findGrant(pkg);
        if (grant == null) {
            if (Build.VERSION.SDK_INT >= 33 || "runtime".equals(kind))
                throw new IllegalStateException("无法核实所选应用的通知运行时授权");
            return appOp(pkg, enabled, journal, save, key, NOTIFY);
        }
        // Some Android 12 devices backport this dangerous permission. Package-specific evidence
        // takes precedence over the SDK level, including when migrating an old AppOps journal.
        if (NOTIFY.equals(kind)) {
            appOp(pkg, false, journal, save, key, NOTIFY);
            if (journal.has(key)) throw new IllegalStateException("原通知 AppOps 设置仍待恢复");
            grant = readGrant(pkg);
        }
        return runtimeNotification(pkg, enabled, journal, save, grant);
    }

    /** The caller additionally checks that the selected target declares REQUEST_INSTALL_PACKAGES. */
    static String sources(String pkg, boolean enabled, JSONObject journal, Runnable save) throws Exception {
        validate(pkg);
        return appOp(pkg, enabled, journal, save, "sources", INSTALL);
    }

    private static class Modes {
        final Map<String,String> values = new HashMap<>();
        String scope() { return values.containsKey("uid") ? "uid" : "package"; }
        String at(String scope) {
            String mode = values.get(scope);
            if (mode == null && "package".equals(scope)) mode = values.get("default");
            return mode;
        }
        String effective() { return at(scope()); }
    }

    private static Modes readModes(String pkg, String op) throws Exception {
        String value = GuardPhone.cmd("cmd", "appops", "get", "--user", "0", pkg, op);
        String allowed = "(allow|ignore|deny|default|foreground)";
        Pattern specific = Pattern.compile("(Uid mode:\\s*)?" + op + ":\\s*" + allowed + "(?:;.*)?");
        Pattern baseline = Pattern.compile("Default mode:\\s*" + allowed);
        Modes result = new Modes();
        for (String raw : value.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || "No operations.".equals(line)) continue;
            Matcher s = specific.matcher(line), d = baseline.matcher(line);
            String scope, mode;
            if (s.matches()) {
                scope = s.group(1) == null ? "package" : "uid";
                mode = s.group(2);
            } else if (d.matches()) {
                scope = "default";
                mode = d.group(1);
            } else throw new IllegalStateException("系统返回未知 AppOps 格式，已保留设置");
            if (result.values.put(scope, mode) != null) throw new IllegalStateException("系统返回重复 AppOps 范围");
        }
        if (result.effective() == null) throw new IllegalStateException("无法核实原 AppOps 模式");
        return result;
    }

    private static void writeMode(String pkg, String op, String scope, String mode) throws Exception {
        if (!mode.matches("allow|ignore|deny|default|foreground")) throw new IllegalArgumentException("未知 AppOps 模式");
        if ("uid".equals(scope))
            GuardPhone.cmd("cmd", "appops", "set", "--user", "0", "--uid", pkg, op, mode);
        else if ("package".equals(scope))
            GuardPhone.cmd("cmd", "appops", "set", "--user", "0", pkg, op, mode);
        else throw new IllegalArgumentException("未知 AppOps 范围");
    }

    private static boolean blocked(String mode) { return "ignore".equals(mode) || "deny".equals(mode); }

    private static String appOp(String pkg, boolean enabled, JSONObject journal, Runnable save,
                                String key, String op) throws Exception {
        JSONObject item = journal.optJSONObject(key);
        if (!enabled && item == null) return OFF;
        Modes modes = readModes(pkg, op);
        String scope = modes.scope(), effective = modes.effective();
        if (item != null && !op.equals(item.optString("kind")))
            throw new IllegalStateException("系统版本或控制方式已变更，请核实原设置");
        if (!enabled) {
            String oldScope = item.getString("scope");
            if (!"ignore".equals(modes.at(oldScope))) return forget(journal, key, save, CHANGED);
            String restore = item.getString("old");
            save.run();
            writeMode(pkg, op, oldScope, restore);
            Modes restored = readModes(pkg, op);
            if (item.getBoolean("implicit")) {
                if (!item.getString("effective").equals(restored.at(oldScope)))
                    throw new IllegalStateException("已请求恢复系统默认，原模式尚未核实");
            } else if (!restore.equals(restored.at(oldScope))
                    && !("uid".equals(oldScope) && restored.at(oldScope) == null && restore.equals(restored.effective())))
                throw new IllegalStateException("原 AppOps 模式尚未核实");
            return forget(journal, key, save, "已恢复原系统模式");
        }
        if (blocked(effective)) {
            if (item != null && !item.getBoolean("applied") && "ignore".equals(modes.at(item.getString("scope")))) {
                item.put("applied", true);
                save.run();
            }
            return "系统已限制（" + effective + "）";
        }
        if (item != null && (item.getBoolean("applied") || !scope.equals(item.getString("scope"))
                || !effective.equals(item.getString("effective")))) return CHANGED;
        if (item == null) {
            boolean implicit = !modes.values.containsKey(scope);
            // A mode named "default" is a real AppOps mode. Restore the OS-reported baseline
            // (which can be "allow" for notifications), even when the original row was absent.
            item = new JSONObject().put("kind", op).put("scope", scope)
                    .put("old", effective).put("implicit", implicit)
                    .put("effective", effective).put("applied", false);
            journal.put(key, item);
        }
        save.run();
        writeMode(pkg, op, scope, "ignore");
        if (!"ignore".equals(readModes(pkg, op).effective()))
            throw new IllegalStateException("命令已执行，系统尚未确认限制");
        item.put("applied", true);
        save.run();
        return "系统已限制（ignore）";
    }

    private static final class Grant {
        final boolean allowed;
        final String flags;
        Grant(boolean allowed, String flags) { this.allowed = allowed; this.flags = flags; }
    }

    private static Grant findGrant(String pkg) throws Exception {
        String dump = GuardPhone.cmd("dumpsys", "package", pkg);
        Pattern row = Pattern.compile(Pattern.quote(NOTIFY_PERMISSION) +
                ": granted=(true|false), flags=\\[([ A-Z0-9_|]*)\\]");
        boolean packages = false, matchedUser = false;
        int packageIndent = -1;
        int userIndent = -1, permissionsIndent = -1;
        for (String line : dump.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            int indent = line.indexOf(s);
            if (indent == 0) {
                packages = s.equals("Packages:");
                packageIndent = userIndent = permissionsIndent = -1;
                continue;
            }
            if (!packages) continue;
            if (packageIndent >= 0 && indent <= packageIndent) {
                packageIndent = userIndent = permissionsIndent = -1;
            }
            if (s.startsWith("Package [" + pkg + "] ") && s.endsWith(":")) {
                packageIndent = indent;
                continue;
            }
            if (packageIndent < 0) continue;
            if (userIndent >= 0 && indent <= userIndent) { userIndent = -1; permissionsIndent = -1; }
            if (s.matches("User 0: .*\\binstalled=true\\b.*")) { matchedUser = true; userIndent = indent; continue; }
            if (userIndent < 0) continue;
            if (permissionsIndent >= 0 && indent <= permissionsIndent) permissionsIndent = -1;
            if ("runtime permissions:".equals(s)) { permissionsIndent = indent; continue; }
            if (permissionsIndent < 0 || !s.startsWith(NOTIFY_PERMISSION + ":")) continue;
            Matcher m = row.matcher(s);
            if (!m.matches()) throw new IllegalStateException("系统提供的通知授权标记格式未知");
            TreeSet<String> flags = new TreeSet<>();
            for (String flag : m.group(2).split("\\|")) if (!flag.trim().isEmpty()) flags.add(flag.trim());
            return new Grant(Boolean.parseBoolean(m.group(1)), String.join("|", flags));
        }
        if (!matchedUser) throw new IllegalStateException("无法核实所选应用及主用户的安装记录");
        return null;
    }

    private static Grant readGrant(String pkg) throws Exception {
        Grant grant = findGrant(pkg);
        if (grant == null) throw new IllegalStateException("所选应用的通知运行时授权记录已不可见");
        return grant;
    }

    private static String runtimeNotification(String pkg, boolean enabled, JSONObject journal, Runnable save, Grant grant) throws Exception {
        String key = "notifications";
        JSONObject item = journal.optJSONObject(key);
        if (!enabled && item == null) return OFF;
        if (item != null && !"runtime".equals(item.optString("kind")))
            throw new IllegalStateException("系统版本已变更，请核实通知授权");
        if (item != null && !grant.flags.equals(item.getString("flags")))
            return enabled ? CHANGED : forget(journal, key, save, CHANGED);
        if (!enabled) {
            if (grant.allowed) return forget(journal, key, save, CHANGED);
            save.run();
            GuardPhone.cmd("pm", "grant", "--user", "0", pkg, NOTIFY_PERMISSION);
            Grant after = readGrant(pkg);
            if (!after.allowed || !after.flags.equals(grant.flags)) throw new IllegalStateException("原通知授权或标记尚未恢复");
            return forget(journal, key, save, "已恢复原通知授权");
        }
        if (!grant.allowed) {
            if (item != null && !item.getBoolean("applied")) {
                item.put("applied", true); save.run();
            }
            return "系统通知授权已关闭";
        }
        if (item != null && item.getBoolean("applied")) return CHANGED;
        for (String flag : grant.flags.split("\\|")) {
            if (!flag.isEmpty() && !Arrays.asList("USER_SET", "USER_SENSITIVE_WHEN_GRANTED",
                    "USER_SENSITIVE_WHEN_DENIED").contains(flag)) return "通知含固定或未知授权标记，已保留";
        }
        if (item == null) {
            item = new JSONObject().put("kind", "runtime").put("flags", grant.flags).put("applied", false);
            journal.put(key, item);
        }
        save.run();
        GuardPhone.cmd("pm", "revoke", "--user", "0", pkg, NOTIFY_PERMISSION);
        Grant after = readGrant(pkg);
        if (after.allowed || !after.flags.equals(grant.flags)) throw new IllegalStateException("系统尚未确认通知授权及原标记");
        item.put("applied", true);
        save.run();
        return "系统通知授权已关闭";
    }
}
