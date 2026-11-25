package app.local.privacyguard;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;

/** Routes long-lived OEM permission changes to the settings UI that owns the rule. */
public final class MotionGuardActivity extends Activity {
    private static final String HUAWEI_CENTER = "com.huawei.security.privacycenter";
    private static final String HUAWEI_PERMISSION_MANAGER = "huawei.intent.action.HPC_PERMISSION_MANAGER";
    private GuardUi ui;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("摇一摇与设备方向");
        ui = new GuardUi(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(GuardUi.BG);
        LinearLayout page = ui.col();
        ui.pad(page, 20, 16);
        scroll.addView(page);
        setContentView(scroll);
        ui.add(page, ui.action("返回守护面板", v -> finish()), 0);
        ui.add(page, ui.text("摇一摇与设备方向", 25, GuardUi.INK, true), 6);
        boolean huawei = "HUAWEI".equalsIgnoreCase(Build.MANUFACTURER);
        String help = huawei
                ? "打开华为权限管理，点“应用获取设备方向”，再关闭想限制的应用。设置保存在手机上，拔线后继续生效。"
                : "部分系统提供按应用设置的设备方向权限。可在系统隐私设置或对应应用的系统详情中查找。";
        ui.add(page, ui.text(help, 16, GuardUi.INK, false), 12);
        ui.add(page, ui.action(huawei ? "打开华为权限管理" : "打开系统隐私设置",
                v -> openPermissions(huawei)), 8);
        ui.add(page, ui.text("实际允许名单和开关状态请以系统页面为准。限制方向数据也可能影响游戏体感和应用内自动旋转，广告画面仍可能显示。", 14, GuardUi.MUTED, false), 10);
        ui.add(page, ui.action("查看指定应用的系统详情", v -> chooseApp()), 8);
    }

    private boolean launch(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
    }

    private void openPermissions(boolean huawei) {
        if (huawei && launch(new Intent(HUAWEI_PERMISSION_MANAGER).setPackage(HUAWEI_CENTER))) return;
        if (launch(new Intent(Settings.ACTION_PRIVACY_SETTINGS))) return;
        if (launch(new Intent(Settings.ACTION_SETTINGS))) return;
        new GuardDialog(this).setMessage("系统设置入口不可用，请在手机设置中打开权限管理。")
                .setPositiveButton("知道了", null).show();
    }

    private static final class App {
        final String name, pkg;
        App(String name, String pkg) { this.name = name; this.pkg = pkg; }
    }

    private void chooseApp() {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        ArrayList<App> apps = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (ResolveInfo item : getPackageManager().queryIntentActivities(launcher, 0)) {
            if (item.activityInfo == null) continue;
            String pkg = item.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            CharSequence label = item.activityInfo.applicationInfo.loadLabel(getPackageManager());
            apps.add(new App(label == null ? pkg : label.toString(), pkg));
        }
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> {
            int order = collator.compare(a.name, b.name);
            return order == 0 ? a.pkg.compareTo(b.pkg) : order;
        });
        if (apps.isEmpty()) {
            launch(new Intent(Settings.ACTION_APPLICATION_SETTINGS));
            return;
        }
        String[] choices = new String[apps.size()];
        for (int i = 0; i < choices.length; i++) choices[i] = apps.get(i).name + "\n" + apps.get(i).pkg;
        new GuardDialog(this).setTitle("选择应用")
                .setItems(choices, (dialog, index) -> {
                    Intent detail = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", apps.get(index).pkg, null));
                    if (!launch(detail)) openPermissions("HUAWEI".equalsIgnoreCase(Build.MANUFACTURER));
                }).setNegativeButton("取消", null).show();
    }
}
