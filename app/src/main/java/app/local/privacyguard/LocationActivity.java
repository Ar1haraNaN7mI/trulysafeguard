package app.local.privacyguard;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class LocationActivity extends Activity {
    private static final String OFFLINE_ORIGIN = "https://appassets.androidplatform.net/location/";
    private static WeakReference<LocationActivity> currentPage = new WeakReference<>(null);
    private static final String INACTIVE = "{\"available\":false,\"active\":false,\"lat\":0,\"lon\":0,\"speed\":0,\"bearing\":0,\"message\":\"页面已暂停\"}";
    private final Handler main = new Handler(Looper.getMainLooper());
    private MockController controller;
    private WebView page;
    private volatile boolean foreground;
    private volatile int session;
    private boolean askingPermission;
    private RealOrigin origin;
    private boolean waitingForOriginAccess;
    private boolean readOriginOnResume;
    private String holdMessage = "";

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        GuardApp.protect(this);
        controller = HoldLocationService.controller(this);
        origin = new RealOrigin(this);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open("location.html"), StandardCharsets.UTF_8))) {
            StringBuilder html = new StringBuilder();
            for (String line; (line = reader.readLine()) != null;) html.append(line).append('\n');
            page = new WebView(this);
            WebSettings settings = page.getSettings();
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setBlockNetworkLoads(true);
            settings.setGeolocationEnabled(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            settings.setJavaScriptEnabled(true);
            page.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                    return true;
                }
                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                }
            });
            page.addJavascriptInterface(new Controls(), "GuardLocation");
            setContentView(page);
            page.loadDataWithBaseURL(OFFLINE_ORIGIN, html.toString(), "text/html", "UTF-8", null);
        } catch (IOException | RuntimeException error) {
            TextView message = new TextView(this);
            message.setText("无法打开定位摇杆网页。请检查系统 WebView 是否可用。");
            setContentView(message);
        }
    }

    private <T> T readOnMain(Callable<T> action, T fallback) {
        int current = session;
        if (!foreground) return fallback;
        FutureTask<T> task = new FutureTask<>(() ->
                foreground && current == session && currentPage.get() == this ? action.call() : fallback);
        main.post(task);
        try {
            return task.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
            // A suspended page must not be able to replay an old command on resume.
        }
        task.cancel(false);
        return fallback;
    }

    private void postOnMain(Runnable action) {
        int current = session;
        if (foreground) main.post(() -> {
            if (foreground && current == session && currentPage.get() == this) action.run();
        });
    }

    public final class Controls {
        @JavascriptInterface public void realOrigin() { postOnMain(LocationActivity.this::requestOrigin); }
        @JavascriptInterface public String state() { return readOnMain(() -> {
            try {
                JSONObject result = new JSONObject(controller.state());
                result.put("hold", HoldLocationService.holding());
                if (!holdMessage.isEmpty()) result.put("message", holdMessage);
                return result.toString();
            } catch (JSONException error) { return INACTIVE; }
        }, INACTIVE); }
        @JavascriptInterface public boolean start(double lat, double lon) {
            return readOnMain(() -> {
                holdMessage = "";
                if (!controller.start(lat, lon)) return false;
                if (HoldLocationService.holding() && !HoldLocationService.setHold(LocationActivity.this, true))
                    holdMessage = "保持服务不可用，此次模拟仅在网页打开时生效。";
                return true;
            }, false);
        }
        @JavascriptInterface public void setMotion(double east, double north, double speedKmh) {
            postOnMain(() -> controller.setMotion(east, north, speedKmh));
        }
        @JavascriptInterface public boolean setHold(boolean enabled) {
            return readOnMain(() -> changeHold(enabled), false);
        }
        @JavascriptInterface public void stop() {
            postOnMain(() -> { holdMessage = ""; HoldLocationService.stopLocation(LocationActivity.this); });
        }
        @JavascriptInterface public void close() { postOnMain(LocationActivity.this::finish); }
        @JavascriptInterface public void settings() {
            postOnMain(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                } catch (ActivityNotFoundException | SecurityException error) {
                    Toast.makeText(LocationActivity.this, "请从系统设置中打开开发者选项。", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private void reportOrigin(android.location.Location fix, String text) {
        if (page == null || isDestroyed() || isFinishing()) return;
        if (fix != null && (!foreground || currentPage.get() != this || controller.isActive())) {
            fix = null;
            text = "页面或模拟状态已变更，请重新获取当前位置。";
        }
        String coordinates = fix == null ? "null" : ("[" + fix.getLatitude() + "," + fix.getLongitude() + "," +
                Math.round(fix.getAccuracy()) + "]");
        page.evaluateJavascript("window.guardOriginResult && window.guardOriginResult(" +
                coordinates + "," + JSONObject.quote(text) + ")", null);
    }

    private boolean canLocate() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestOrigin() {
        if (controller.isActive()) { reportOrigin(null,"请先停止手机模拟，再读取真实位置。"); return; }
        if (canLocate()) {
            origin.get(this::reportOrigin);
        } else if (!waitingForOriginAccess) {
            waitingForOriginAccess = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION}, 49);
        }
    }

    private boolean changeHold(boolean enabled) {
        holdMessage = "";
        if (enabled && Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            holdMessage = "保持最后位置需要显示可停止的通知，请允许通知后再开启。";
            if (!askingPermission) {
                askingPermission = true;
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 48);
            }
            return false;
        }
        boolean ok = HoldLocationService.setHold(this, enabled);
        if (!ok) holdMessage = "请在系统通知设置中允许“隐私守护”的“保持模拟位置”通知。";
        return ok;
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 49) {
            waitingForOriginAccess = false;
            if (canLocate()) {
                if (foreground && currentPage.get()==this) requestOrigin();
                else readOriginOnResume = true;
            } else reportOrigin(null,"未获得前台定位权限。可以重试或手动填写起点。");
        }
        if (code == 48) {
            askingPermission = false;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) changeHold(true);
            else holdMessage = "请允许本应用显示通知后再开启保持位置。";
        }
    }

    @Override protected void onResume() {
        super.onResume();
        currentPage = new WeakReference<>(this);
        foreground = true;
        if (page != null) page.onResume();
        if (readOriginOnResume) { readOriginOnResume=false; requestOrigin(); }
    }

    @Override protected void onPause() {
        foreground = false;
        session++;
        origin.cancel();
        if (!waitingForOriginAccess) reportOrigin(null, "定位读取已取消。");
        if (currentPage.get() == this) {
            controller.setMotion(0, 0, 0);
            if (!HoldLocationService.holding() && !askingPermission) controller.stop();
        }
        if (page != null) page.onPause();
        super.onPause();
    }

    @Override protected void onStop() {
        if (currentPage.get() == this && !HoldLocationService.holding()) controller.stop();
        super.onStop();
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && foreground) currentPage = new WeakReference<>(this);
    }

    @Override protected void onDestroy() {
        if (page != null) {
            page.removeJavascriptInterface("GuardLocation");
            if (page.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) page.getParent()).removeView(page);
            page.destroy();
        }
        super.onDestroy();
    }
}
