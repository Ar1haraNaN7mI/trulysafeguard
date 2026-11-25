package app.local.privacyguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Handles only web links dispatched by Android to this component. */
public final class LinkGuardActivity extends Activity {
    private static final String LAST_BROWSER = "last_chosen_browser";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent incoming = getIntent();
        if (!Intent.ACTION_VIEW.equals(incoming.getAction()) || incoming.getDataString() == null) {
            finish();
            return;
        }
        final LinkControl link;
        try {
            link = LinkControl.parse(incoming.getDataString());
        } catch (URISyntaxException malformed) {
            showMessage("链接未打开", "地址格式无效，或包含容易混淆的主机信息。");
            return;
        }
        if (shouldDrop(link)) {
            finish();
            return;
        }
        // Some Android versions omit other web handlers when a default browser exists.
        // Package-scoped discovery on a background thread handles this case.
        new Thread(() -> {
            List<Browser> browsers = findBrowsers(Uri.parse(link.url).getScheme());
            runOnUiThread(() -> {
                if (!isDestroyed() && !isFinishing()) present(link, browsers);
            });
        }, "browser-discovery").start();
    }

    private boolean shouldDrop(LinkControl link) {
        if (Prefs.get(this,"block_installer_links") && PackageLink.matches(android.net.Uri.parse(link.url))) return true;
        return Prefs.get(this, Prefs.LINKS) &&
                (Prefs.get(this, Prefs.DENY_WEB) || GuardRules.blocked(this, link.host));
    }

    private List<Browser> findBrowsers(String scheme) {
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse(scheme + "://browser-check.invalid/"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        Map<String, Browser> found = new LinkedHashMap<>();
        int flags = PackageManager.MATCH_ALL | PackageManager.MATCH_DEFAULT_ONLY
                | PackageManager.GET_RESOLVED_FILTER;
        addBrowsers(found, pm.queryIntentActivities(probe, flags), scheme);
        if (found.isEmpty()) {
            for (ApplicationInfo app : pm.getInstalledApplications(0)) {
                if (getPackageName().equals(app.packageName)) continue;
                probe.setPackage(app.packageName);
                addBrowsers(found, pm.queryIntentActivities(probe, flags), scheme);
            }
        }
        List<Browser> browsers = new ArrayList<>(found.values());
        browsers.sort((a, b) -> a.display.compareToIgnoreCase(b.display));
        return browsers;
    }

    private void addBrowsers(Map<String, Browser> found, List<ResolveInfo> matches, String scheme) {
        for (ResolveInfo item : matches) {
            ActivityInfo activity = item.activityInfo;
            IntentFilter filter = item.filter;
            if (activity == null || !activity.exported || filter == null
                    || getPackageName().equals(activity.packageName)
                    || found.containsKey(activity.packageName)
                    || !filter.hasCategory(Intent.CATEGORY_BROWSABLE)
                    || !filter.hasDataScheme(scheme) || filter.countDataAuthorities() != 0
                    || filter.countDataSchemeSpecificParts() != 0) {
                continue;
            }
            if (activity.permission != null && checkSelfPermission(activity.permission)
                    != PackageManager.PERMISSION_GRANTED) continue;
            String label = activity.applicationInfo.loadLabel(getPackageManager()).toString();
            label = label.replaceAll("[\\p{Cntrl}\\p{Cf}]+", " ").trim();
            if (label.length() > 64) label = label.substring(0, 64);
            ComponentName target = new ComponentName(activity.packageName, activity.name);
            found.put(activity.packageName, new Browser(target, label + "\n" + activity.packageName));
        }
    }

    private void present(LinkControl link, List<Browser> browsers) {
        boolean enabled = Prefs.get(this, Prefs.LINKS);
        if (shouldDrop(link)) {
            finish();
            return;
        }
        if (browsers.isEmpty()) {
            showMessage("没有可用浏览器", "请安装或启用一个能打开网页的浏览器，然后重试。");
            return;
        }
        if (!enabled && browsers.size() > 1) {
            String last = getPreferences(MODE_PRIVATE).getString(LAST_BROWSER, null);
            for (Browser browser : browsers) {
                if (browser.target.getPackageName().equals(last)) {
                    open(link, browser);
                    return;
                }
            }
        }
        if (browsers.size() == 1) {
            Browser browser = browsers.get(0);
            if (!enabled) {
                open(link, browser);
                return;
            }
            new GuardDialog(this).setTitle("允许打开此网站？")
                    .setMessage(link.origin + "\n\n使用浏览器\n" + browser.display)
                    .setPositiveButton("打开", (dialog, which) -> open(link, browser))
                    .setNegativeButton("取消", (dialog, which) -> finish())
                    .setOnCancelListener(dialog -> finish()).show();
            return;
        }
        String[] choices = new String[browsers.size()];
        for (int i = 0; i < browsers.size(); i++) choices[i] = browsers.get(i).display;
        // Platform dialog titles can ellipsize the end of a long hostname.
        TextView targetView = new TextView(this);
        int margin = Math.round(20 * getResources().getDisplayMetrics().density);
        targetView.setPadding(margin, margin, margin, margin / 2);
        targetView.setTextSize(17);
        targetView.setText("选择浏览器打开\n" + link.origin);
        new GuardDialog(this)
                .setCustomTitle(targetView)
                .setItems(choices, (dialog, index) -> open(link, browsers.get(index)))
                .setNegativeButton("取消", (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish()).show();
    }

    private void open(LinkControl link, Browser chosen) {
        if (shouldDrop(link)) {
            finish();
            return;
        }
        Intent outbound = new Intent(Intent.ACTION_VIEW, Uri.parse(link.url))
                .addCategory(Intent.CATEGORY_BROWSABLE).setComponent(chosen.target);
        try {
            startActivity(outbound);
            getPreferences(MODE_PRIVATE).edit()
                    .putString(LAST_BROWSER, chosen.target.getPackageName()).apply();
            finish();
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            showMessage("无法打开浏览器", "浏览器目前不可用，请返回并重新选择。");
        }
    }

    private void showMessage(String title, String text) {
        new GuardDialog(this).setTitle(title).setMessage(text)
                .setPositiveButton("关闭", (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish()).show();
    }

    private static final class Browser {
        final ComponentName target;
        final String display;

        Browser(ComponentName target, String display) {
            this.target = target;
            this.display = display;
        }
    }
}
