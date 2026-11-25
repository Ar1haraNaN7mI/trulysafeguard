package app.local.privacyguard;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class GuardApp extends Application implements Application.ActivityLifecycleCallbacks,
        SharedPreferences.OnSharedPreferenceChangeListener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Activity> activities = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Window> dialogWindows = Collections.newSetFromMap(new WeakHashMap<>());

    @Override public void onCreate() {
        super.onCreate();
        InstallAuditJob.initialize(this);
        HoldLocationService.controller(this);
        registerActivityLifecycleCallbacks(this);
        Prefs.prefs(this).registerOnSharedPreferenceChangeListener(this);
    }

    void registerDialogWindow(Window window) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> registerDialogWindow(window));
            return;
        }
        dialogWindows.add(window);
        protectWindow(window, Prefs.get(this, Prefs.SECURE));
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (key != null && !Prefs.SECURE.equals(key)) return;
        if (Looper.myLooper() == Looper.getMainLooper()) refreshWindows();
        else main.post(this::refreshWindows);
    }

    private void refreshWindows() {
        boolean on = Prefs.get(this, Prefs.SECURE);
        for (Activity a : activities) {
            if (!a.isDestroyed()) protectActivity(a, on);
        }
        for (Window w : dialogWindows) protectWindow(w, on);
    }

    static void protect(Activity a) {
        protectActivity(a, Prefs.get(a,Prefs.SECURE));
    }
    private static void protectActivity(Activity a, boolean on) {
        protectWindow(a.getWindow(),on);
        if(Build.VERSION.SDK_INT>=33) a.setRecentsScreenshotEnabled(!on);
    }
    private static void protectWindow(Window w,boolean on) {
        if(on) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(Build.VERSION.SDK_INT>=31) w.setHideOverlayWindows(on);
        if(Build.VERSION.SDK_INT>=29) w.getDecorView().setImportantForContentCapture(on ? View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS : View.IMPORTANT_FOR_CONTENT_CAPTURE_AUTO);
    }
    public void onActivityCreated(Activity a,Bundle state){activities.add(a);protect(a);}
    public void onActivityResumed(Activity a){protect(a);}
    public void onActivityStarted(Activity a){}
    public void onActivityPaused(Activity a){}
    public void onActivityStopped(Activity a){}
    public void onActivitySaveInstanceState(Activity a,Bundle state){}
    public void onActivityDestroyed(Activity a){activities.remove(a);}
}
