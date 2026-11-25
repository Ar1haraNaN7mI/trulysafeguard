package app.local.privacyguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Shares one mock session between the foreground page and its optional stop notification. */
public final class HoldLocationService extends Service {
    private static final String CHANNEL = "held-mock-location";
    private static final String STOP = "app.local.privacyguard.END_MOCK_LOCATION";
    private static final String RELEASE = "app.local.privacyguard.RELEASE_MOCK_SERVICE";
    private static final int ID = 2309;
    private static MockController mock;
    private static boolean hold;
    private static boolean requested;
    private static long generation;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long mine;

    static MockController controller(Context context) {
        if (mock == null) mock = new MockController(context.getApplicationContext());
        return mock;
    }

    static boolean holding() { return hold; }

    static boolean notificationAvailable(Context context) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications == null) return false;
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "保持模拟位置", NotificationManager.IMPORTANCE_LOW));
        return notifications.areNotificationsEnabled() &&
                notifications.getNotificationChannel(CHANNEL).getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    static boolean setHold(Context context, boolean enabled) {
        if (enabled && !notificationAvailable(context)) {
            hold = false;
            release(context);
            return false;
        }
        hold = enabled;
        if (!enabled) release(context);
        else if (controller(context).isActive() && !requested) {
            requested = true;
            try {
                context.startForegroundService(new Intent(context, HoldLocationService.class)
                        .putExtra("session", ++generation));
            } catch (RuntimeException e) {
                hold = requested = false;
                return false;
            }
        }
        return true;
    }

    private static void release(Context context) {
        if (requested) {
            requested = false;
            ++generation;
            try {
                context.startService(new Intent(context, HoldLocationService.class).setAction(RELEASE));
            } catch (RuntimeException e) {
                context.stopService(new Intent(context, HoldLocationService.class));
            }
        }
    }

    static void stopLocation(Context context) {
        controller(context).stop();
        hold = false;
        release(context);
    }

    private final Runnable check = new Runnable() {
        @Override public void run() {
            if (!requested || mine != generation) return;
            if (!hold || !controller(HoldLocationService.this).isActive() ||
                    !notificationAvailable(HoldLocationService.this)) {
                stopLocation(HoldLocationService.this);
                stopSelf();
            } else handler.postDelayed(this, 1200);
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && RELEASE.equals(intent.getAction())) {
            if (!requested) {
                handler.removeCallbacks(check);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelfResult(startId);
            }
            return START_NOT_STICKY;
        }
        if (intent != null && STOP.equals(intent.getAction())) {
            if (intent.getLongExtra("session", -1) == generation) stopLocation(this);
            if (!requested) stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        mine = intent == null ? -1 : intent.getLongExtra("session", -1);
        try {
            startForeground(ID, notification());
        } catch (RuntimeException e) {
            if (mine == generation) stopLocation(this);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (mine != generation || !requested) {
            stopSelfResult(startId);
        } else {
            handler.removeCallbacks(check);
            handler.post(check);
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        int options = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent page = PendingIntent.getActivity(this, ID,
                new Intent(this, LocationActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), options);
        PendingIntent end = PendingIntent.getService(this, ID, new Intent(this, HoldLocationService.class)
                .setAction(STOP).putExtra("session", mine), options);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(app.local.privacyguard.R.drawable.guard_icon).setContentTitle("模拟定位已开启")
                .setContentText("切出摇杆网页会保持最后位置。点此返回，或点停止。")
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(page).setOngoing(true)
                .setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null, "停止模拟", end).build());
        if (android.os.Build.VERSION.SDK_INT >= 31)
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        handler.removeCallbacks(check);
        if (requested && mine == generation) {
            requested = hold = false;
            controller(this).stop();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
