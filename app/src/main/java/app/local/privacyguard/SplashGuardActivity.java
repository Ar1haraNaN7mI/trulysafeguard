package app.local.privacyguard;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.*;

/** User-facing consent and live status for local splash-ad skip actions. */
public final class SplashGuardActivity extends Activity {
    private Switch toggle;
    private TextView status, recent;
    private boolean painting;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        public void run() { refresh(); handler.postDelayed(this, 1400); }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("全局开屏广告跳过");
        GuardUi ui = new GuardUi(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = ui.col();
        ui.pad(page, 20, 20);
        scroll.setBackgroundColor(GuardUi.BG);
        scroll.addView(page);
        setContentView(scroll);
        ui.add(page, ui.action("返回", v -> finish()), 0);
        ui.add(page, ui.text("全局开屏广告跳过", 26, GuardUi.INK, true), 4);
        ui.add(page, ui.text("对所有可启动的第三方应用生效。打开应用或从后台切回时，守护会在最初六秒寻找广告角落里的跳过控件。广告可能先出现；自绘画面、无按钮和无法识别的样式可能保留。", 15, GuardUi.INK, false), 12);
        toggle = new Switch(this);
        toggle.setText("自动跳过开屏广告");
        toggle.setMinHeight(ui.dp(52));
        ui.add(page, toggle, 8);
        status = ui.text("", 15, GuardUi.TEAL, true);
        recent = ui.text("", 15, GuardUi.MUTED, false);
        ui.add(page, status, 2);
        ui.add(page, recent, 6);
        ui.add(page, ui.text("请在无障碍设置中开启「隐私守护 · 开屏广告跳过」。系统允许服务查看窗口、操作控件和发送手势。在核验过的携程 8.98.1 中，只有前台出现特定“跳过广告”控件且相邻倒计时正在减少，才会在控件中心轻触一次。守护不截屏，不保存窗口正文。操作计数仅保存在内存。", 14, GuardUi.MUTED, false), 14);
        ui.add(page, ui.action("打开系统无障碍设置", v -> openSettings()), 2);
        toggle.setOnCheckedChangeListener((v, on) -> {
            if (painting) return;
            if (on) {
                Prefs.set(this, SplashSkipService.REQUESTED, true);
                if (!SplashSkipService.granted(this) && !SplashSkipService.connected()) openSettings();
            } else {
                SplashSkipService.stop(this);
            }
            refresh();
        });
    }
    private void openSettings() {
        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "请打开系统设置中的无障碍服务。", Toast.LENGTH_LONG).show();
        }
    }
    private void refresh() {
        boolean granted = SplashSkipService.granted(this), connected = SplashSkipService.connected();
        boolean on = SplashSkipService.requested(this) && (granted || connected);
        painting = true;
        toggle.setChecked(on);
        painting = false;
        status.setText(on ? (connected ? "正在监测应用打开与切回" : "已授权，等待系统连接服务") : "自动跳过已关闭");
        long count = SplashSkipService.skipCount;
        String calls = count == 0 ? "本次守护进程尚无已响应的跳过操作" :
                "本次守护进程已响应 " + count + " 次跳过操作\n最近一次来源 · " + SplashSkipService.lastPackage;
        recent.setText(calls + "\n携程已完成触摸 " + SplashSkipService.completedTouchCount + " 次（仅统计手势送达）");
    }
    @Override protected void onResume() { super.onResume(); tick.run(); }
    @Override protected void onPause() { handler.removeCallbacks(tick); super.onPause(); }
}
