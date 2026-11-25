package app.local.privacyguard;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class InstallGuardActivity extends Activity {
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final List<Source> sources = new ArrayList<>();
    private final List<String> privileged = new ArrayList<>();
    private GuardUi ui;
    private TextView sourceSummary, policyStatus, auditStatus, history;
    private Switch ownerSwitch, auditSwitch, linkSwitch;
    private boolean painting, loaded;
    private int scanId;

    private static final class Source {
        final String pkg, display, state;
        final boolean allowed;
        Source(String packageName, String label, String mode, boolean allowed) {
            this.pkg = packageName; this.display = label; this.state = mode; this.allowed = allowed;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new GuardUi(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(GuardUi.BG);
        LinearLayout panel = ui.col(); ui.pad(panel,20,20); scroll.addView(panel); setContentView(scroll);
        ui.add(panel, ui.action("返回守护首页", v -> finish()), 0);
        ui.add(panel, ui.text("安装来源防护", 28, GuardUi.INK, true), 0);

        LinearLayout source = card(panel, "安装前限制来源");
        linkSwitch = new Switch(this);
        linkSwitch.setText("拦截安装包网页直链");
        linkSwitch.setMinHeight(ui.dp(48)); source.addView(linkSwitch);
        linkSwitch.setOnCheckedChangeListener((button, on) -> {
            if (!painting) Prefs.set(this, "block_installer_links", on);
        });
        paragraph(source, "守护接收到 HTTP(S) 链接时，按安装包文件名和常见下载参数拦截。浏览器或应用内部发起的下载以及安装文件内容超出此项检查范围。");
        paragraph(source, "在系统页面关闭浏览器、文件管理器等应用的“允许安装未知应用”。系统会在后续安装请求时执行所选设置。网页上的下载文件仍可能保留。");
        sourceSummary = paragraph(source, "正在检查当前用户的安装来源…");
        ui.add(source, ui.action("按应用管理安装来源", v -> chooseSource()), 0);
        ui.add(source, ui.action("打开系统安装来源总表", v -> openSource(null)), 0);
        ui.add(source, ui.action("按应用设置安装请求拦截", v -> startActivity(new Intent(this, UsbPolicyActivity.class))), 0);
        ui.add(source, ui.action("查看持有系统安装权限的应用", v -> showPrivileged()), 0);

        policyStatus = paragraph(source, "");
        ownerSwitch = new Switch(this);
        ownerSwitch.setText("本管理员禁止未知来源安装");
        ownerSwitch.setMinHeight(ui.dp(48)); source.addView(ownerSwitch);
        ownerSwitch.setOnCheckedChangeListener((button, on) -> {
            if (painting) return;
            DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
            ComponentName admin = new ComponentName(this, PolicyReceiver.class);
            try {
                if (on) dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
                else dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
            } catch (IllegalArgumentException | SecurityException | IllegalStateException ex) {
                message("系统未接受这次安装策略变更。");
            }
            updatePolicy();
        });

        LinearLayout audit = card(panel, "安装后核对");
        auditSwitch = new Switch(this);
        auditSwitch.setText("记录新装应用");
        auditSwitch.setMinHeight(ui.dp(48)); audit.addView(auditSwitch);
        paragraph(audit, "仅在手机保留最近 40 条新装应用的包名、安装时间和系统提供的来源。系统巡检可能延迟，两次检查间已经卸载的应用可能漏记。");
        auditStatus = paragraph(audit, "");
        auditSwitch.setOnCheckedChangeListener((button, on) -> {
            if (painting) return;
            InstallAuditJob.setEnabled(this, on);
            refreshAudit();
        });
        ui.add(audit, ui.action("立即核对安装记录", v -> refreshAudit()), 0);
        ui.add(audit, ui.action("清空本地记录", v -> new GuardDialog(this).setTitle("清空安装记录？")
                .setMessage("已记录的包名、来源及时间将从本机清除。")
                .setNegativeButton("保留", null).setPositiveButton("清空", (d,w) -> {
                    InstallAuditJob.clear(this); refreshAudit();
                }).show()), 0);
        history = paragraph(audit, "");
    }

    private LinearLayout card(LinearLayout panel, String title) {
        LinearLayout box = ui.col(); ui.pad(box,16,14);
        box.setBackground(ui.shape(0xffffffff,16,0xffdae3dd)); ui.add(panel,box,12);
        ui.add(box,ui.text(title,19,GuardUi.INK,true),0);
        return box;
    }

    private TextView paragraph(LinearLayout box, String text) {
        TextView t = ui.text(text,15,GuardUi.MUTED,false); ui.add(box,t,6); return t;
    }

    private void message(String text) {
        new GuardDialog(this).setMessage(text).setPositiveButton("知道了",null).show();
    }

    private void updatePolicy() {
        painting = true;
        try {
            DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
            UserManager um = getSystemService(UserManager.class);
            boolean managed = dpm != null &&
                    (dpm.isDeviceOwnerApp(getPackageName()) || dpm.isProfileOwnerApp(getPackageName()));
            boolean restricted = um != null && (um.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) ||
                    (Build.VERSION.SDK_INT >= 29 && um.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)));
            ownerSwitch.setVisibility(managed ? android.view.View.VISIBLE : android.view.View.GONE);
            if (managed) {
                ComponentName admin = new ComponentName(this, PolicyReceiver.class);
                ownerSwitch.setChecked(dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES));
                ownerSwitch.setEnabled(true);
            }
            policyStatus.setText((restricted ? "当前用户或资料已有未知来源限制。" : "当前用户或资料未报告统一的未知来源限制。") +
                    (managed ? " 开关仅更改本管理员在此用户或资料设置的限制。" : " 可按来源单独关闭安装授权。") +
                    " ADB、受信任商店及系统特权安装另受系统控制。");
        } catch (SecurityException | IllegalStateException ex) {
            ownerSwitch.setEnabled(false); policyStatus.setText("系统未向本应用提供管理策略状态。");
        } finally { painting = false; }
    }

    private void refreshAudit() {
        painting = true;
        boolean enabled = InstallAuditJob.enabled(this);
        auditSwitch.setChecked(enabled); painting = false;
        if (!enabled) { auditStatus.setText("新增记录已关闭。"); renderHistory(); return; }
        auditStatus.setText("正在核对当前用户的新装应用…");
        InstallAuditJob.scanAsync(this, () -> {
            if (isDestroyed() || isFinishing() || !InstallAuditJob.enabled(this)) return;
            auditStatus.setText(InstallAuditJob.scheduled(this) ? "本地记录已开启，系统已接受后台巡检。" :
                    "本地记录已开启。系统暂未接受后台巡检，可在此页立即核对。");
            renderHistory();
        });
    }

    private void renderHistory() {
        List<JSONObject> rows = InstallAuditJob.history(this);
        if (rows.isEmpty()) { history.setText("暂无本地安装记录。"); return; }
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        StringBuilder text = new StringBuilder();
        for (JSONObject row : rows) {
            if (text.length() > 0) text.append("\n\n");
            String installer = row.optString("installer");
            String initiator = row.optString("initiator");
            text.append(format.format(new Date(row.optLong("time")))).append("  ").append(row.optString("pkg"));
            text.append("\n安装方  ").append(installer.isEmpty() ? "系统未提供" : installer);
            if (!initiator.isEmpty() && !initiator.equals(installer)) text.append("\n发起方  ").append(initiator);
        }
        history.setText(text);
    }

    private void loadSources() {
        int ticket = ++scanId;
        loaded = false;
        sourceSummary.setText("正在检查当前用户的安装来源…");
        loader.execute(() -> {
            List<Source> found = new ArrayList<>(); List<String> trusted = new ArrayList<>();
            PackageManager pm = getPackageManager();
            AppOpsManager ops = getSystemService(AppOpsManager.class);
            String op = AppOpsManager.permissionToOp(Manifest.permission.REQUEST_INSTALL_PACKAGES);
            try {
                for (PackageInfo info : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
                    if (info.requestedPermissions == null || info.applicationInfo == null) continue;
                    boolean request = false, canInstall = false;
                    for (int i = 0; i < info.requestedPermissions.length; i++) {
                        String permission = info.requestedPermissions[i];
                        if (Manifest.permission.REQUEST_INSTALL_PACKAGES.equals(permission)) request = true;
                        if (Manifest.permission.INSTALL_PACKAGES.equals(permission) && info.requestedPermissionsFlags != null &&
                                (info.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) canInstall = true;
                    }
                    if (!canInstall && !request) continue;
                    String label = info.applicationInfo.loadLabel(pm).toString().replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").trim();
                    if (canInstall) trusted.add(label + "\n" + info.packageName);
                    if (request) found.add(source(info.applicationInfo, label, ops, op));
                }
            } catch (RuntimeException ex) {
                runOnUiThread(() -> {
                    if (ticket == scanId && !isDestroyed()) sourceSummary.setText("无法读取完整来源清单，请打开系统安装来源总表。");
                });
                return;
            }
            found.sort((a, b) -> a.allowed == b.allowed ? a.display.compareToIgnoreCase(b.display) : a.allowed ? -1 : 1);
            Collections.sort(trusted);
            runOnUiThread(() -> {
                if (ticket != scanId || isDestroyed() || isFinishing()) return;
                sources.clear(); sources.addAll(found); privileged.clear(); privileged.addAll(trusted); loaded = true;
                int allowed = 0; for (Source s : found) if (s.allowed) allowed++;
                sourceSummary.setText("可见应用中 " + sources.size() + " 个声明安装请求权限，系统明确返回允许的有 " + allowed + " 个。授权详情以系统来源页面为准。");
            });
        });
    }

    private Source source(ApplicationInfo app, String label, AppOpsManager ops, String op) {
        String mode = "状态请在系统中核对"; boolean allowed = false;
        if (ops != null && op != null) {
            try {
                int state = Build.VERSION.SDK_INT >= 29 ? ops.unsafeCheckOpNoThrow(op, app.uid, app.packageName)
                        : ops.checkOpNoThrow(op, app.uid, app.packageName);
                if (state == AppOpsManager.MODE_ALLOWED) { mode = "来源开关允许"; allowed = true; }
                else if (state == AppOpsManager.MODE_IGNORED) mode = "系统返回忽略";
                else if (state == AppOpsManager.MODE_DEFAULT) mode = "系统默认";
            } catch (SecurityException | IllegalArgumentException ignored) {}
        }
        return new Source(app.packageName, label, mode, allowed);
    }

    private void chooseSource() {
        if (!loaded || sources.isEmpty()) { openSource(null); return; }
        List<Source> shown = new ArrayList<>(sources);
        String[] labels = new String[shown.size()];
        for (int i = 0; i < labels.length; i++) {
            Source s = shown.get(i); labels[i] = s.display + " · " + s.state + "\n" + s.pkg;
        }
        new GuardDialog(this).setTitle("选择安装来源").setItems(labels,(d,i) -> openSource(shown.get(i).pkg))
                .setNegativeButton("返回",null).show();
    }

    private void showPrivileged() {
        if (!loaded) { message("正在核对，请稍后重试。"); return; }
        String text = "系统授予下列应用 INSTALL_PACKAGES 权限，可包括安装器和应用商店。实际安装记录可在安装后核对。\n\n" +
                (privileged.isEmpty() ? "本次清单中未发现持有方。" : android.text.TextUtils.join("\n\n",privileged));
        message(text);
    }

    private boolean launchSystem(Intent intent) {
        PackageManager pm = getPackageManager();
        int flags = PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_SYSTEM_ONLY;
        List<ResolveInfo> targets = pm.queryIntentActivities(intent, flags);
        for (ResolveInfo target : targets) {
            if (target.activityInfo == null || !target.activityInfo.exported) continue;
            Intent explicit = new Intent(intent).setComponent(new ComponentName(target.activityInfo.packageName, target.activityInfo.name));
            try { startActivity(explicit); return true; }
            catch (ActivityNotFoundException | SecurityException ignored) {}
        }
        return false;
    }

    private void openSource(String pkg) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        if (pkg != null && launchSystem(new Intent(intent).setData(Uri.fromParts("package",pkg,null)))) return;
        if (launchSystem(intent) || launchSystem(new Intent(Settings.ACTION_SECURITY_SETTINGS))) return;
        message("手机未提供可打开的设置页。请在系统设置中搜索“安装未知应用”或“安装外部来源应用”。");
    }

    @Override protected void onResume() {
        super.onResume();
        painting = true; linkSwitch.setChecked(Prefs.get(this, "block_installer_links")); painting = false;
        updatePolicy(); refreshAudit(); loadSources();
    }

    @Override protected void onDestroy() {
        scanId++; loader.shutdownNow(); super.onDestroy();
    }
}
