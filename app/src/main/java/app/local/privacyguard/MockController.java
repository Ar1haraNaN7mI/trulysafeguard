package app.local.privacyguard;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/** Main-thread controller. Each owning screen or service releases its session on lifecycle exit. */
final class MockController {
    private static final double EARTH_RADIUS_METRES = 6371008.8;
    private static final double MAX_SPEED_KMH = 300.0;
    private static final long INPUT_TIMEOUT_NS = 1_200_000_000L;
    private static final long MOVING_INTERVAL_MS = 200;
    private static final long IDLE_INTERVAL_MS = 1000;
    private static final int PREPARED = 1, CONFIRMED = 2;

    // A previous screen instance must never remove a new instance's test providers.
    private static MockController owner;

    private final Context context;
    private final LocationManager locations;
    private final AppOpsManager appOps;
    private final SharedPreferences cleanup;
    private final int bootCount;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> installed = new LinkedHashSet<>();
    private final double[] point = new double[3];
    private double lat, lon, speed, bearing;
    private double originLat, originLon, course, angle;
    private long lastStep, inputDeadline;
    private String message = "未启动模拟定位。";
    private final Runnable tick = this::onTick;

    MockController(Context context) {
        this.context = context.getApplicationContext();
        locations = this.context.getSystemService(LocationManager.class);
        appOps = this.context.getSystemService(AppOpsManager.class);
        cleanup = this.context.getSharedPreferences("mock_provider_cleanup", Context.MODE_PRIVATE);
        int boot;
        try {
            boot = Settings.Global.getInt(this.context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException unavailable) {
            boot = -1;
        }
        bootCount = boot;
        if (owner == null) recoverInterrupted();
    }

    @SuppressWarnings("deprecation")
    private boolean available() {
        try {
            return appOps != null && appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    boolean start(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            stop();
            message = "请输入有效的纬度（−90 至 90）和经度（−180 至 180）。";
            return false;
        }
        if (locations == null || !available()) {
            stop();
            message = "请先在系统开发者选项中将本应用选为模拟位置信息应用。";
            return false;
        }
        if (owner != null && owner != this) owner.stop();
        main.removeCallbacks(tick);
        if (owner != this) {
            owner = this;
            install(LocationManager.GPS_PROVIDER, true);
            install(LocationManager.NETWORK_PROVIDER, false);
        }
        lat = latitude;
        lon = longitude == 180 ? -180 : longitude;
        speed = bearing = angle = 0;
        lastStep = SystemClock.elapsedRealtimeNanos();
        inputDeadline = lastStep;
        if (!publish()) return false;
        main.postDelayed(tick, IDLE_INTERVAL_MS);
        return true;
    }

    private void install(String provider, boolean gps) {
        int previous = cleanup.getInt(provider, 0);
        // Write ahead of the binder call. A kill between returning from add and tracking
        // the provider must still leave enough information to restore the normal provider.
        if (previous == 0 && !cleanup.edit().putInt("boot", bootCount)
                .putInt(provider, PREPARED).commit()) return;
        try {
            try {
                addProvider(provider, gps);
            } catch (IllegalArgumentException duplicate) {
                // Some older frameworks reject replacing a test provider in place. Only an
                // explicit start may replace one previously confirmed for this application.
                if (previous != CONFIRMED || !available()) throw duplicate;
                locations.removeTestProvider(provider);
                if (!available()) return;
                addProvider(provider, gps);
            }
            if (!available()) return;
            installed.add(provider);
            cleanup.edit().putInt(provider, CONFIRMED).commit();
            locations.setTestProviderEnabled(provider, true);
        } catch (RuntimeException rejected) {
            if (installed.remove(provider)) removeProvider(provider);
            else if (previous == 0 && (rejected instanceof IllegalArgumentException
                    || rejected instanceof SecurityException)) cleanup.edit().remove(provider).commit();
        }
    }

    @SuppressWarnings("deprecation")
    private void addProvider(String provider, boolean gps) {
        // This overload is public from Android 8 onward.
        locations.addTestProvider(provider, false, gps, false, false, false, true, true,
                gps ? Criteria.POWER_HIGH : Criteria.POWER_LOW,
                gps ? Criteria.ACCURACY_FINE : Criteria.ACCURACY_COARSE);
    }

    private void recoverInterrupted() {
        if (!cleanup.contains(LocationManager.GPS_PROVIDER)
                && !cleanup.contains(LocationManager.NETWORK_PROVIDER)) return;
        int previousBoot = cleanup.getInt("boot", -1);
        if (previousBoot >= 0 && bootCount >= 0 && previousBoot != bootCount) {
            // System-server provider registrations cannot survive a full boot.
            cleanup.edit().clear().commit();
            return;
        }
        if (locations == null || !available()) {
            message = "上次退出时有模拟定位源待清理。请再次将本应用选为模拟位置应用，或重启手机。";
            return;
        }
        boolean gps = recoverProvider(LocationManager.GPS_PROVIDER, true);
        boolean network = recoverProvider(LocationManager.NETWORK_PROVIDER, false);
        message = gps && network ? "已清理上次异常退出时留下的模拟定位源。"
                : "系统未允许清理上次的模拟定位源。可以重新开始并停止模拟，或重启手机。";
    }

    private boolean recoverProvider(String provider, boolean gps) {
        if (!cleanup.contains(provider)) return true;
        if (!available()) return false;
        try {
            // Reclaim this recorded name under the current grant, then release it without
            // ever enabling it or publishing a fix. Never blindly remove an existing source.
            addProvider(provider, gps);
            return removeProvider(provider);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** east/north are joystick components; a vector shorter than one scales the selected speed. */
    void setMotion(double east, double north, double speedKmh) {
        if (owner != this) return;
        if (!available()) {
            permissionLost();
            return;
        }
        long now = SystemClock.elapsedRealtimeNanos();
        advance(now);
        double oldSpeed = speed;
        double magnitude = Math.hypot(east, north);
        if (!Double.isFinite(magnitude) || !Double.isFinite(speedKmh)
                || magnitude < 0.0001 || speedKmh <= 0) {
            speed = 0;
            inputDeadline = now;
        } else {
            double heading = Math.atan2(east, north);
            if (oldSpeed == 0 || Math.abs(Math.IEEEremainder(heading - course, 2 * Math.PI)) > 0.000001) {
                originLat = Math.toRadians(lat);
                originLon = Math.toRadians(lon);
                course = heading;
                angle = 0;
                bearing = degrees360(heading);
            }
            speed = Math.min(MAX_SPEED_KMH, speedKmh) * Math.min(1.0, magnitude);
            inputDeadline = now + INPUT_TIMEOUT_NS;
        }
        // Do not let high-frequency pointer updates postpone the scheduled location tick.
        if ((oldSpeed == 0) != (speed == 0)) {
            main.removeCallbacks(tick);
            if (publish()) main.postDelayed(tick, speed == 0 ? IDLE_INTERVAL_MS : MOVING_INTERVAL_MS);
        }
    }

    private void onTick() {
        if (owner != this) return;
        if (!available()) {
            permissionLost();
            return;
        }
        advance(SystemClock.elapsedRealtimeNanos());
        if (publish()) main.postDelayed(tick, speed == 0 ? IDLE_INTERVAL_MS : MOVING_INTERVAL_MS);
    }

    private void advance(long now) {
        if (speed > 0) {
            long end = Math.min(now, inputDeadline);
            long delta = Math.max(0L, end - lastStep);
            angle = (angle + (speed / 3.6) * (delta / 1e9) / EARTH_RADIUS_METRES) % (2 * Math.PI);
            pointOnCourse(originLat, originLon, course, angle, point);
            lat = point[0];
            lon = point[1];
            bearing = point[2];
            if (now >= inputDeadline) speed = 0;
        }
        lastStep = now;
    }

    /**
     * Fixed great-circle course from a starting point, maintained across touch heartbeats.
     * Vector rotation avoids division by cos(latitude) at the poles.
     */
    private static void pointOnCourse(double phi, double lambda, double heading, double arc, double[] out) {
        double sinPhi = Math.sin(phi), cosPhi = Math.cos(phi);
        double sinLon = Math.sin(lambda), cosLon = Math.cos(lambda);
        double east = Math.sin(heading), north = Math.cos(heading);
        double px = cosPhi * cosLon, py = cosPhi * sinLon, pz = sinPhi;
        double vx = -east * sinLon - north * sinPhi * cosLon;
        double vy = east * cosLon - north * sinPhi * sinLon;
        double vz = north * cosPhi;
        double distanceCos = Math.cos(arc), distanceSin = Math.sin(arc);
        double x = px * distanceCos + vx * distanceSin;
        double y = py * distanceCos + vy * distanceSin;
        double z = pz * distanceCos + vz * distanceSin;
        double newLat = Math.atan2(z, Math.hypot(x, y)), newLon = Math.atan2(y, x);
        double tx = vx * distanceCos - px * distanceSin;
        double ty = vy * distanceCos - py * distanceSin;
        double tz = vz * distanceCos - pz * distanceSin;
        double tangentEast = -tx * Math.sin(newLon) + ty * Math.cos(newLon);
        double tangentNorth = -(tx * Math.cos(newLon) + ty * Math.sin(newLon)) * Math.sin(newLat)
                + tz * Math.cos(newLat);
        out[0] = Math.toDegrees(newLat);
        out[1] = Math.toDegrees(newLon);
        if (out[1] >= 180) out[1] -= 360;
        out[2] = degrees360(Math.atan2(tangentEast, tangentNorth));
    }

    private static double degrees360(double radians) {
        return (Math.toDegrees(radians) + 360.0) % 360.0;
    }

    private boolean publish() {
        if (owner != this) return false;
        if (!available()) {
            permissionLost();
            return false;
        }
        long time = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtimeNanos();
        for (Iterator<String> it = installed.iterator(); it.hasNext();) {
            String provider = it.next();
            Location fix = new Location(provider);
            fix.setLatitude(lat);
            fix.setLongitude(lon);
            fix.setTime(time);
            fix.setElapsedRealtimeNanos(elapsed);
            fix.setAccuracy(LocationManager.GPS_PROVIDER.equals(provider) ? 5f : 25f);
            fix.setSpeed((float) (speed / 3.6));
            if (speed > 0) fix.setBearing((float) bearing);
            try {
                locations.setTestProviderLocation(provider, fix);
            } catch (RuntimeException rejected) {
                it.remove();
                removeProvider(provider);
            }
        }
        if (installed.isEmpty()) {
            owner = null;
            speed = 0;
            main.removeCallbacks(tick);
            message = "系统没有接受模拟定位源，请确认开发者选项中的选择。";
            return false;
        }
        if (installed.size() == 2) message = "正在发送 GPS 和网络模拟位置。";
        else message = installed.contains(LocationManager.GPS_PROVIDER)
                ? "正在发送 GPS 模拟位置，网络模拟源不可用。" : "正在发送网络模拟位置，GPS 模拟源不可用。";
        return true;
    }

    private boolean removeProvider(String provider) {
        if (!available()) return false;
        try {
            locations.removeTestProvider(provider);
            // AOSP may silently ignore the operation when AppOps is revoked during IPC.
            return available() && cleanup.edit().remove(provider).commit();
        } catch (RuntimeException notRemovable) {
            return false;
        }
    }

    private void permissionLost() {
        main.removeCallbacks(tick);
        installed.clear(); // The designated app may already have changed. Do not touch its providers.
        if (owner == this) owner = null;
        speed = 0;
        message = "模拟位置授权已撤销，已停止发送；系统未允许确认定位源清理。";
    }

    void stop() {
        main.removeCallbacks(tick);
        if (owner != this) {
            if (owner == null) recoverInterrupted();
            return;
        }
        boolean clean = true;
        for (String provider : installed) if (!removeProvider(provider)) clean = false;
        installed.clear();
        owner = null;
        speed = 0;
        message = clean ? "模拟定位已停止。" : "已停止发送，系统未允许确认全部定位源清理。";
    }

    boolean isActive() {
        if (owner != this || installed.isEmpty()) return false;
        if (available()) return true;
        permissionLost();
        return false;
    }

    String state() {
        boolean allowed = available();
        if (!allowed && owner == this) permissionLost();
        JSONObject data = new JSONObject();
        try {
            data.put("available", allowed);
            data.put("active", owner == this && !installed.isEmpty());
            data.put("lat", lat);
            data.put("lon", lon);
            data.put("speed", speed);
            data.put("bearing", bearing);
            data.put("message", message);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return data.toString();
    }
}
