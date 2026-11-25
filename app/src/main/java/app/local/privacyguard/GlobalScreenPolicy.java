package app.local.privacyguard;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.os.Build;
import android.os.UserManager;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Displays authoritative policy state. Changes only this admin's contribution. */
final class GlobalScreenPolicy {
    private final Activity activity;
    private final DevicePolicyManager policies;
    private final UserManager users;
    private final ComponentName admin;
    private final TextView role, captureState, contentState;
    private final Switch capture, content;
    private boolean painting;

    GlobalScreenPolicy(Activity activity, GuardUi ui, LinearLayout page) {
        this.activity = activity;
        policies = activity.getSystemService(DevicePolicyManager.class);
        users = activity.getSystemService(UserManager.class);
        admin = new ComponentName(activity, PolicyReceiver.class);
        role = ui.text("", 15, GuardUi.INK, false);
        ui.add(page, role, 6);
        capture = control(ui, page, "限制系统截图与录屏");
        captureState = ui.text("", 14, GuardUi.TEAL, true);
        ui.add(page, captureState, 0);
        ui.add(page, ui.text("Android 在受管范围内限制截图、录屏和不安全显示输出。开启后，你的按键截图也会受限。厂商私有取屏、应用自行处理的数据及无障碍读取另有独立路径。", 14, GuardUi.MUTED, false), 4);
        content = control(ui, page, "限制 Android 屏幕内容采集服务");
        contentState = ui.text("", 14, GuardUi.TEAL, true);
        ui.add(page, contentState, 0);
        ui.add(page, ui.text("限制 Content Capture 框架向系统内容服务提供界面数据，保留按键截图。设备所有者对主用户设置此策略，资料所有者对其管理的用户或资料设置。", 14, GuardUi.MUTED, false), 4);
        capture.setOnCheckedChangeListener((view, on) -> {
            if (!painting) change(true, on);
        });
        content.setOnCheckedChangeListener((view, on) -> {
            if (!painting) change(false, on);
        });
    }

    private Switch control(GuardUi ui, LinearLayout page, String label) {
        Switch control = new Switch(activity);
        control.setText(label);
        control.setMinHeight(ui.dp(48));
        ui.add(page, control, 8);
        return control;
    }

    void refresh() {
        painting = true;
        try {
            boolean device = policies != null && policies.isDeviceOwnerApp(activity.getPackageName());
            boolean profile = policies != null && policies.isProfileOwnerApp(activity.getPackageName());
            boolean managed = device || profile;
            if (device) role.setText("守护已是设备所有者。截图策略适用于设备上的所有用户。");
            else if (profile) role.setText("守护已是资料所有者。截图策略适用于当前受管用户或资料。");
            else role.setText("当前安装没有设备所有者或资料所有者权限。Android 未向守护开放全局截图限制。");
            refreshCapture(managed);
            refreshContent(managed);
        } catch (RuntimeException failure) {
            role.setText("无法核实本应用的设备管理身份。");
            capture.setEnabled(false);
            content.setEnabled(false);
            capture.setChecked(false);
            content.setChecked(false);
            captureState.setText("无法读取截图策略");
            contentState.setText("无法读取内容采集策略");
        } finally {
            painting = false;
        }
    }

    private void refreshCapture(boolean managed) {
        try {
            boolean requested = managed && policies.getScreenCaptureDisabled(admin);
            boolean enforced = policies != null && policies.getScreenCaptureDisabled(null);
            capture.setEnabled(managed);
            capture.setChecked(managed ? requested : enforced);
            if (policies == null) captureState.setText("系统没有提供设备策略服务");
            else if (requested) captureState.setText(enforced ? "系统已报告截图限制" : "已设置，等待系统报告生效");
            else if (enforced) captureState.setText("系统报告当前用户已有其他策略限制截图");
            else captureState.setText("系统未报告当前用户有截图限制");
        } catch (RuntimeException failure) {
            capture.setChecked(false);
            capture.setEnabled(false);
            captureState.setText("系统未提供可核实的截图策略状态");
        }
    }

    private void refreshContent(boolean managed) {
        content.setEnabled(false);
        content.setChecked(false);
        if (Build.VERSION.SDK_INT < 29) {
            contentState.setText("此策略需要 Android 10 或更高版本");
            return;
        }
        try {
            if (users == null) {
                contentState.setText("系统未提供用户限制状态");
                return;
            }
            boolean requested = managed && policies.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_CONTENT_CAPTURE);
            boolean enforced = users.hasUserRestriction(UserManager.DISALLOW_CONTENT_CAPTURE);
            boolean service = activity.getSystemService(android.view.contentcapture.ContentCaptureManager.class) != null;
            content.setEnabled(managed && (service || requested));
            content.setChecked(managed ? requested : enforced);
            if (requested) contentState.setText(enforced ? "系统已报告内容采集限制" : "已设置，等待系统报告生效");
            else if (enforced) contentState.setText("系统报告当前用户已有其他策略限制内容采集");
            else if (!service) contentState.setText("系统未向当前应用提供 Content Capture 服务");
            else contentState.setText("系统未报告当前用户有内容采集限制");
        } catch (RuntimeException failure) {
            content.setEnabled(false);
            content.setChecked(false);
            contentState.setText("系统未提供可核实的内容采集策略状态");
        }
    }

    private void change(boolean screen, boolean on) {
        try {
            if (screen) {
                policies.setScreenCaptureDisabled(admin, on);
            } else if (Build.VERSION.SDK_INT >= 29) {
                if (on) policies.addUserRestriction(admin, UserManager.DISALLOW_CONTENT_CAPTURE);
                else policies.clearUserRestriction(admin, UserManager.DISALLOW_CONTENT_CAPTURE);
            }
        } catch (RuntimeException failure) {
            Toast.makeText(activity, "系统未接受这次策略变更", Toast.LENGTH_LONG).show();
        }
        refresh();
    }
}
