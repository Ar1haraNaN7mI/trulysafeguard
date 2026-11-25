package app.local.privacyguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.Collator;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App selection is local. Actual operating-system changes are made by the connected companion. */
public final class UsbPolicyActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String[] TITLES = {
            "限制运动传感器", "拦截该应用的普通通知", "检测屏幕共享后停止该应用", "禁止该来源请求安装"
    };
    private static final String[] SHORT = {"运动传感器", "普通通知", "屏幕共享", "安装来源"};
    private static final String[] HELP = {
            "限制该应用通过系统传感器接口接收运动数据。可影响摇一摇、计步或游戏控制，效果取决于手机系统。",
            "限制所选应用发布普通通知，包括消息和验证码。来源应用可以查询授权。通话、媒体会话等系统豁免按设备规则处理。需要保留日常提醒时可用首页的关键词过滤。",
            "发现该应用正在使用系统屏幕共享接口时，会强行停止整个应用，可能中断通话或未保存的操作。检测前的画面可能已传出。仅对可识别的第三方投屏有效。你仍然可以按键截屏。",
            "在系统安装来源检查处拒绝该应用发起的普通安装请求。只有声明了请求安装权限的来源可选。系统商店、调试安装和特权安装器另有授权路径。"
    };
    private static final String DISCONNECT = "规则在手机上执行。部分华为系统会在 USB 连接变化时结束调试进程；失去回报后，已写入的限制可能继续生效，但新规则需重新激活后同步。华为设备方向权限、关键词过滤、安装记录和独立投屏监测另有手机系统入口。";

    private static final class App {
        final String pkg, label, id;
        App(String pkg, String label, String id) { this.pkg = pkg; this.label = label; this.id = id; }
    }

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<App> apps = new ArrayList<>(), shown = new ArrayList<>();
    private final Runnable refresh = this::refreshStatus;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            refreshConnection();
            main.postDelayed(this, 5000);
        }
    };
    private SharedPreferences prefs;
    private GuardUi ui;
    private EditText search;
    private TextView connection, count, empty;
    private android.widget.Button pause, end;
    private AppAdapter adapter;
    private boolean loading = true;
    private int loadNumber;
    private App detail;
    private TextView[] detailStatus;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        GuardApp.protect(this);
        prefs = UsbPolicyProvider.prefs(this);
        ui = new GuardUi(this);
        LinearLayout page = ui.col();
        page.setBackgroundColor(GuardUi.BG);
        ui.pad(page, 18, 8);
        setContentView(page);
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.addView(ui.action("返回", v -> finish()));
        nav.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        nav.addView(ui.action("规则说明", v -> help()));
        page.addView(nav);
        page.addView(ui.text("本机规则助手", 25, GuardUi.INK, true));
        connection = ui.text("", 14, GuardUi.MUTED, false);
        ui.add(page, connection, 3);
        LinearLayout controls=new LinearLayout(this);
        pause=ui.action("暂停并恢复设置",v->{
            String current=prefs.getString(UsbPolicyProvider.PHONE_MODE,"desktop");
            prefs.edit().putString(UsbPolicyProvider.PHONE_MODE,"pause".equals(current)?"run":"pause").apply();
            refreshConnection();
        });
        end=ui.action("结束手机助手",v->new GuardDialog(this).setTitle("结束手机助手？").setMessage("先尝试恢复它改动的设置，再结束本机进程。下次运行需用电脑重新激活。")
                .setNegativeButton("取消",null).setPositiveButton("结束",(dlg,which)->prefs.edit().putString(UsbPolicyProvider.PHONE_MODE,"exit").apply()).show());
        controls.addView(pause);controls.addView(end);page.addView(controls);
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setHint("搜索应用名称或包名");
        ui.add(page, search, 6);
        count = ui.text("", 13, GuardUi.MUTED, false);
        page.addView(count);
        ListView list = new ListView(this);
        list.setDividerHeight(ui.dp(1));
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, position, row) -> edit(shown.get(position)));
        empty = ui.text("", 15, GuardUi.MUTED, false);
        empty.setGravity(Gravity.CENTER);
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        page.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
        list.setEmptyView(empty);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int n, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int n) { filter(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        page.setFocusableInTouchMode(true);
        page.requestFocus();
        refreshStatus();
        filter();
    }

    @Override protected void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(this);
        refreshStatus();
        main.postDelayed(heartbeat, 5000);
        int sequence = ++loadNumber;
        loader.execute(() -> {
            List<App> result = new ArrayList<>();
            try {
                Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                HashSet<String> seen = new HashSet<>();
                for (ResolveInfo item : getPackageManager().queryIntentActivities(launcher, 0)) {
                    if (Thread.currentThread().isInterrupted()) return;
                    if (item.activityInfo == null) continue;
                    String pkg = item.activityInfo.packageName;
                    if (!seen.add(pkg)) continue;
                    String id = UsbPolicyProvider.identity(this, pkg, true);
                    if (id == null) continue;
                    CharSequence name = item.activityInfo.applicationInfo.loadLabel(getPackageManager());
                    result.add(new App(pkg, name == null ? pkg : name.toString(), id));
                }
                Collator collator = Collator.getInstance();
                result.sort((a, b) -> collator.compare(a.label, b.label));
                main.post(() -> {
                    if (sequence != loadNumber || isDestroyed()) return;
                    apps.clear(); apps.addAll(result); loading = false;
                    filter();
                });
            } catch (RuntimeException error) {
                main.post(() -> {
                    if (sequence != loadNumber || isDestroyed()) return;
                    loading = false;
                    filter();
                    Toast.makeText(this, "系统未能读取应用列表。重新打开页面可以重试。", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override protected void onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        main.removeCallbacks(refresh);
        main.removeCallbacks(heartbeat);
        super.onPause();
    }

    @Override protected void onDestroy() {
        loadNumber++;
        loader.shutdownNow();
        main.removeCallbacks(refresh);
        super.onDestroy();
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        main.removeCallbacks(refresh);
        main.post(refresh);
    }

    private static String date(long millis) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date(millis));
    }

    private void refreshConnection() {
        long last = UsbPolicyProvider.lastContact(prefs);
        String mode=prefs.getString(UsbPolicyProvider.PHONE_MODE,"desktop");
        long age=UsbPolicyProvider.phoneAge(prefs);
        String state=prefs.getString(UsbPolicyProvider.PHONE_STATE,"");
        boolean recent=age>=0 && age<45000 && !"desktop".equals(mode);
        boolean control=recent && !"exit".equals(mode);
        pause.setEnabled(control);end.setEnabled(control);
        pause.setText("pause".equals(mode)?"恢复规则运行":"暂停并恢复设置");
        long unplugged=prefs.getLong("lastUnplugged",0);
        if(recent)connection.setText("手机助手上次回报 · "+state+"\n回报时间 "+date(UsbPolicyProvider.phoneTime(prefs))+
                (unplugged>0?"\n最近一次 USB 断开期间回报 "+date(unplugged):""));
        else if(!"desktop".equals(mode))connection.setText("未收到近期回报。拔线、重启或主动结束后，可能需要重新激活。当前修改仅保存为待同步规则。");
        else connection.setText("尚未激活手机助手。请在电脑上运行一次激活工具。"+(last>0?" 上次电脑同步 "+date(last):""));
    }

    private void refreshStatus() {
        refreshConnection();
        adapter.notifyDataSetChanged();
        if (detail != null) {
            JSONObject report = UsbPolicyProvider.report(prefs, detail.pkg, detail.id);
            boolean pending = UsbPolicyProvider.rule(prefs, detail.pkg, detail.id) >= 0;
            for (int i = 0; i < detailStatus.length; i++) {
                String value = report == null ? (pending ? "等待助手回报" : "尚未设置")
                        : report.optString(UsbPolicyProvider.FIELDS[i], "无此项回报");
                detailStatus[i].setText(report == null ? value :
                        "助手回报（" + date(report.optLong("received")) + "）\n" + value);
            }
        }
    }

    private void filter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (App app : apps) if (app.label.toLowerCase(Locale.ROOT).contains(q)
                || app.pkg.toLowerCase(Locale.ROOT).contains(q)) shown.add(app);
        count.setText(loading ? "正在读取应用…" : "可管理 " + apps.size() + " 个应用 · 当前显示 " + shown.size() + " 个");
        if (Process.myUid() > Process.LAST_APPLICATION_UID) empty.setText("USB 应用管理仅支持主用户。");
        else empty.setText(loading ? "正在读取应用…" : q.isEmpty() ? "没有符合条件的可启动用户应用。" : "没有匹配的应用。");
        adapter.notifyDataSetChanged();
    }

    private void help() {
        StringBuilder text = new StringBuilder("仅列出手机主用户安装的可启动应用。系统组件、共享 UID 的应用和隐私守护本身均不在列表中。每台手机最多记录 128 个应用。");
        for (int i = 0; i < TITLES.length; i++) text.append("\n\n").append(TITLES[i]).append("\n").append(HELP[i]);
        text.append("\n\n").append(DISCONNECT);
        new GuardDialog(this).setTitle("USB 规则说明").setMessage(text).setPositiveButton("知道了", null).show();
    }

    private void edit(App app) {
        int current = Math.max(0, UsbPolicyProvider.rule(prefs, app.pkg, app.id));
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = ui.col(); ui.pad(form, 20, 8); scroll.addView(form);
        form.addView(ui.text(app.pkg, 13, GuardUi.MUTED, false));
        Switch[] options = new Switch[TITLES.length];
        detail = app; detailStatus = new TextView[TITLES.length];
        for (int i = 0; i < TITLES.length; i++) {
            Switch sw = new Switch(this); options[i] = sw;
            sw.setText(TITLES[i]); sw.setTextSize(16); sw.setTextColor(GuardUi.INK);
            sw.setMinHeight(ui.dp(46)); sw.setSwitchPadding(ui.dp(8));
            sw.setChecked((current & (1 << i)) != 0);
            if(i==3 && !UsbPolicyProvider.declaresInstall(this,app.pkg))sw.setEnabled(false);
            ui.add(form, sw, 8);
            form.addView(ui.text(HELP[i], 14, GuardUi.MUTED, false));
            detailStatus[i] = ui.text("", 13, GuardUi.TEAL, true);
            ui.add(form, detailStatus[i], 4);
        }
        ui.add(form, ui.text(DISCONNECT, 13, GuardUi.MUTED, false), 12);
        refreshStatus();
        AlertDialog dialog = new GuardDialog(this).setTitle(app.label).setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int bits = 0;
            for (int i = 0; i < options.length; i++) if (options[i].isChecked()) bits |= 1 << i;
            String error = UsbPolicyProvider.save(this, app.pkg, app.id, bits);
            if (error == null) dialog.dismiss();
            else Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        }));
        dialog.setOnDismissListener(d -> { detail = null; detailStatus = null; });
        dialog.show();
    }

    private final class AppAdapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public App getItem(int position) { return shown.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View old, ViewGroup parent) {
            LinearLayout row = (LinearLayout) old;
            if (row == null) {
                row = ui.col(); ui.pad(row, 2, 10);
                row.addView(ui.text("", 17, GuardUi.INK, true));
                row.addView(ui.text("", 13, GuardUi.MUTED, false));
                row.addView(ui.text("", 13, GuardUi.TEAL, false));
            }
            App app = getItem(position);
            ((TextView) row.getChildAt(0)).setText(app.label);
            ((TextView) row.getChildAt(1)).setText(app.pkg);
            TextView summary = (TextView) row.getChildAt(2);
            int bits = UsbPolicyProvider.rule(prefs, app.pkg, app.id);
            summary.setVisibility(bits < 0 ? View.GONE : View.VISIBLE);
            if (bits >= 0) {
                List<String> names = new ArrayList<>();
                for (int i = 0; i < SHORT.length; i++) if ((bits & (1 << i)) != 0) names.add(SHORT[i]);
                String settings = names.isEmpty() ? "全部关闭" : String.join("、", names);
                summary.setText(settings + (UsbPolicyProvider.report(prefs, app.pkg, app.id) == null
                        ? " · 等待助手回报" : " · 查看助手回报"));
            }
            return row;
        }
    }
}
