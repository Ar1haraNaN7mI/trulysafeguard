package app.local.privacyguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class NotificationRulesActivity extends Activity {
    private static final int TEAL = 0xff006658, INK = 0xff132f2b, MUTED = 0xff47635d;
    private static final String[] MODES = {"不过滤", "仅过滤促销关键词", "静默可清除通知"};
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final List<App> apps = new ArrayList<>();
    private final List<App> visible = new ArrayList<>();
    private EditText search;
    private TextView status;
    private AppAdapter adapter;
    private boolean loaded;

    public static int ruleCount(Context context) {
        int count = 0;
        for (Map.Entry<String, ?> entry : Prefs.prefs(context).getAll().entrySet()) {
            if (entry.getKey().startsWith(NoticePolicy.PREFIX) && entry.getValue() instanceof String
                    && NoticePolicy.enabledMode((String) entry.getValue())) count++;
        }
        return count;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xfff6f9f7);
        page.setPadding(dp(20), dp(10), dp(20), 0);
        setContentView(page);

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        Button back = button("返回");
        back.setOnClickListener(v -> finish());
        nav.addView(back);
        View spacer = new View(this);
        nav.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        Button add = button("按包名添加");
        add.setOnClickListener(v -> addPackage());
        nav.addView(add);
        Button help = button("过滤说明");
        help.setOnClickListener(v -> explain());
        nav.addView(help);

        TextView title = text("通知过滤规则", 26, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, dp(8), 0, dp(4));
        page.addView(title);
        TextView intro = text("点选应用设置规则。默认保留所有通知。", 15, MUTED);
        page.addView(intro);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setHint("搜索应用名称或包名");
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(10);
        page.addView(search, sp);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        status = text("正在读取应用列表…", 13, MUTED);
        actions.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        Button keywords = button("促销关键词");
        keywords.setOnClickListener(v -> editKeywords());
        actions.addView(keywords);
        page.addView(actions);

        ListView list = new ListView(this);
        list.setDividerHeight(dp(1));
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> choose(visible.get(position)));
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter(); }
            @Override public void afterTextChanged(Editable editable) {}
        });
        if (state != null) search.setText(state.getString("search", ""));
        // Present the full list on entry without automatically opening the keyboard.
        page.setFocusableInTouchMode(true);
        page.requestFocus();
        loader.execute(this::loadApplications);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("search", search.getText().toString());
    }

    @Override protected void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }

    private void loadApplications() {
        Map<String, App> found = new LinkedHashMap<>();
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo info : pm.queryIntentActivities(intent, 0)) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (getPackageName().equals(pkg) || found.containsKey(pkg)) continue;
            CharSequence label = info.activityInfo.applicationInfo.loadLabel(pm);
            found.put(pkg, new App(pkg, label == null ? pkg : label.toString()));
        }
        for (Map.Entry<String, ?> entry : Prefs.prefs(this).getAll().entrySet()) {
            if (!entry.getKey().startsWith(NoticePolicy.PREFIX) || !(entry.getValue() instanceof String)
                    || !NoticePolicy.enabledMode((String) entry.getValue())) continue;
            String pkg = entry.getKey().substring(NoticePolicy.PREFIX.length());
            if (!found.containsKey(pkg)) found.put(pkg, new App(pkg, pkg));
        }
        Collator collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
        List<App> sorted = new ArrayList<>(found.values());
        sorted.sort((a, b) -> {
            int comparison = collator.compare(a.label, b.label);
            return comparison != 0 ? comparison : a.pkg.compareTo(b.pkg);
        });
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            apps.clear();
            apps.addAll(sorted);
            loaded = true;
            filter();
        });
    }

    private void filter() {
        if (!loaded) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        visible.clear();
        for (App app : apps) {
            if (app.label.toLowerCase(Locale.ROOT).contains(query) || app.pkg.toLowerCase(Locale.ROOT).contains(query)) {
                visible.add(app);
            }
        }
        status.setText((visible.isEmpty() ? "没有匹配的应用" : visible.size() + " 个应用")
                + " · 已选 " + ruleCount(this));
        adapter.notifyDataSetChanged();
    }

    private int currentMode(String pkg) {
        String value = Prefs.prefs(this).getString(NoticePolicy.PREFIX + pkg, "");
        return NoticePolicy.MUTE.equals(value) ? 2 : NoticePolicy.PROMOTIONS.equals(value) ? 1 : 0;
    }

    private void choose(App app) {
        String[] items = {MODES[0], MODES[1] + "（保留可辨认的消息及交易提醒）",
                MODES[2] + "（也可能移除私信、验证码和支付提醒）"};
        new GuardDialog(this).setTitle(app.label)
                .setSingleChoiceItems(items, currentMode(app.pkg), (dialog, which) -> {
                    SharedPreferences.Editor editor = Prefs.prefs(this).edit();
                    String key = NoticePolicy.PREFIX + app.pkg;
                    if (which == 0) editor.remove(key);
                    else editor.putString(key, which == 1 ? NoticePolicy.PROMOTIONS : NoticePolicy.MUTE);
                    editor.apply();
                    dialog.dismiss();
                    if (which != 0 && !containsPackage(app.pkg)) apps.add(0, app);
                    filter();
                }).setNegativeButton("取消", null).show();
    }

    private boolean containsPackage(String name) {
        for (App app : apps) if (app.pkg.equals(name)) return true;
        return false;
    }

    private void addPackage() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(12), dp(24), 0);
        form.addView(text("给没有桌面入口的应用设置规则。请输入通知来源的完整包名，系统应用也可以。", 14, MUTED));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint("例如 com.example.app");
        form.addView(input);
        AlertDialog dialog = new GuardDialog(this).setTitle("按包名添加")
                .setView(form).setNegativeButton("取消", null).setPositiveButton("选择规则", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pkg = input.getText().toString().trim();
            if (pkg.length() > 255 || !(pkg.equals("android") || pkg.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))) {
                input.setError("请输入完整应用包名");
                return;
            }
            if (getPackageName().equals(pkg)) {
                input.setError("本应用的防护状态通知始终保留");
                return;
            }
            App target = new App(pkg, pkg);
            for (App app : apps) if (app.pkg.equals(pkg)) { target = app; break; }
            dialog.dismiss();
            choose(target);
        }));
        dialog.show();
    }

    private void explain() {
        new GuardDialog(this).setTitle("通知如何过滤")
                .setMessage("在首页开启通知过滤并授予系统通知使用权后，规则只处理新发布或更新的通知。系统先接收通知，本应用再请求移除；提示可能短暂显示、发声或振动。来源应用仍可能查询移除状态。\n\n"
                        + "仅促销模式读取所选应用通知的标题、正文和展开文字，在本机匹配关键词。系统标记为消息、社交或邮件的通知，以及含验证码、扣款、支付、私信等保护词的通知会保留。不同应用的标记和文案可能不同。\n\n"
                        + "静默模式会移除该应用其他可清除通知，包括普通消息。两种模式都保留不可清除、持续运行、带全屏提醒、通话、闹钟、紧急警报和分组摘要。列表显示有桌面入口的应用及已有规则。")
                .setPositiveButton("知道了", null).show();
    }

    private void editKeywords() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(12), dp(24), 0);
        TextView hint = text("内置匹配含「优惠券」「限时折扣」「秒杀」等词。其他关键词每行一个，只用于仅促销模式。", 14, MUTED);
        form.addView(hint);
        EditText value = new EditText(this);
        value.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        value.setGravity(Gravity.TOP | Gravity.START);
        value.setMinLines(3);
        value.setMaxLines(6);
        value.setHint("每行一个词");
        value.setText(Prefs.prefs(this).getString(NoticePolicy.KEYWORDS_KEY, ""));
        form.addView(value);
        AlertDialog dialog = new GuardDialog(this).setTitle("自定义促销关键词")
                .setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<String> terms = NoticePolicy.customTerms(value.getText().toString());
            String error = NoticePolicy.validate(terms);
            if (error != null) { value.setError(error); return; }
            Prefs.prefs(this).edit().putString(NoticePolicy.KEYWORDS_KEY, android.text.TextUtils.join("\n", terms)).apply();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public App getItem(int index) { return visible.get(index); }
        @Override public long getItemId(int index) { return index; }
        @Override public View getView(int index, View old, ViewGroup parent) {
            LinearLayout row = (LinearLayout) old;
            if (row == null) {
                row = new LinearLayout(NotificationRulesActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(6), dp(10), dp(6), dp(10));
                row.addView(text("", 17, INK));
                row.addView(text("", 13, MUTED));
                row.addView(text("", 13, TEAL));
            }
            App app = getItem(index);
            int mode = currentMode(app.pkg);
            ((TextView) row.getChildAt(0)).setText(app.label);
            ((TextView) row.getChildAt(1)).setText(app.pkg);
            TextView modeView = (TextView) row.getChildAt(2);
            modeView.setText(MODES[mode]);
            modeView.setVisibility(mode == 0 ? View.GONE : View.VISIBLE);
            return row;
        }
    }

    private static final class App {
        final String pkg;
        final String label;
        App(String pkg, String label) { this.pkg = pkg; this.label = label; }
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private Button button(String label) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setTextColor(TEAL);
        button.setAllCaps(false);
        return button;
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
}
