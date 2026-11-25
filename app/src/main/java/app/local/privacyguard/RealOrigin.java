package app.local.privacyguard;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Arrays;
import java.util.ArrayList;

/** A short, explicitly requested, foreground position fix. No coordinates are persisted. */
final class RealOrigin implements LocationListener {
    interface Callback { void done(Location location, String message); }
    private final LocationManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Callback callback;
    private final Runnable timeout = () -> finish(null, "未取得近期的真实定位。请检查系统定位开关，或到开阔处重试。");

    RealOrigin(Context context) { manager = context.getSystemService(LocationManager.class); }

    void get(Callback result) {
        cancel();
        callback = result;
        boolean listening = false;
        Location candidate = null;
        ArrayList<String> sources = new ArrayList<>();
        if (manager != null) {
            for (String source : Arrays.asList(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    if (!manager.isProviderEnabled(source)) continue;
                    sources.add(source);
                    Location last = manager.getLastKnownLocation(source);
                    if (usable(last) && (candidate == null || last.getAccuracy() < candidate.getAccuracy())) candidate = last;
                } catch (SecurityException | IllegalArgumentException ignored) {}
            }
            if (candidate != null) {
                finish(candidate, "");
                return;
            }
            for (String source : sources) {
                try {
                    manager.requestLocationUpdates(source, 0L, 0f, this, Looper.getMainLooper());
                    listening = true;
                } catch (SecurityException | IllegalArgumentException ignored) {
                    // Approximate-only grants and manufacturer builds can limit individual providers.
                }
            }
        }
        if (listening) main.postDelayed(timeout, 22000);
        else finish(null, "系统没有可用的定位来源。请检查前台定位权限和系统定位开关。");
    }

    @SuppressWarnings("deprecation")
    private boolean usable(Location fix) {
        if (fix == null || !fix.hasAccuracy() || !Float.isFinite(fix.getAccuracy()) ||
                !Double.isFinite(fix.getLatitude()) || !Double.isFinite(fix.getLongitude()) ||
                Math.abs(fix.getLatitude()) > 90 || Math.abs(fix.getLongitude()) > 180) return false;
        if (Build.VERSION.SDK_INT >= 31 ? fix.isMock() : fix.isFromMockProvider()) return false;
        long age = SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos();
        return age >= 0 && age <= 30_000_000_000L;
    }

    @Override public void onLocationChanged(Location fix) { if (usable(fix)) finish(fix, ""); }
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    private void finish(Location location, String message) {
        Callback notify = callback;
        cancel();
        if (notify != null) notify.done(location, message);
    }
    void cancel() {
        main.removeCallbacks(timeout);
        if (callback != null && manager != null) {
            try { manager.removeUpdates(this); } catch (SecurityException | IllegalArgumentException ignored) {}
        }
        callback = null;
    }
}
