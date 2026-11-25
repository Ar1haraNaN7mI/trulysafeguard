package app.local.privacyguard;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.BadParcelableException;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Collections;
import java.util.List;

public final class AdNotificationService extends NotificationListenerService
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    public static final String ACTION_STATE_CHANGED = "app.local.privacyguard.NOTICE_STATE_CHANGED";
    private static volatile AdNotificationService connected;
    private volatile boolean unbinding;
    private List<String> extraTerms = Collections.emptyList();

    public static boolean hasAccess(Context context) {
        ComponentName name = new ComponentName(context, AdNotificationService.class);
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            return manager != null && manager.isNotificationListenerAccessGranted(name);
        }
        String setting = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        if (setting == null) return false;
        for (String entry : setting.split(":")) {
            if (name.equals(ComponentName.unflattenFromString(entry))) return true;
        }
        return false;
    }

    public static boolean isConnected() {
        AdNotificationService service = connected;
        return service != null && !service.unbinding;
    }

    public static void applyEnabled(Context context) {
        final Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            AdNotificationService service = connected;
            if (!Prefs.get(app, Prefs.NOTICES)) {
                if (service != null) service.disconnect();
            } else if (service == null && hasAccess(app)) {
                try {
                    requestRebind(new ComponentName(app, AdNotificationService.class));
                } catch (SecurityException ignored) {
                    // Permission can be revoked between the access check and binding.
                }
            }
        });
    }

    @Override public void onCreate() {
        super.onCreate();
        SharedPreferences prefs = Prefs.prefs(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        refreshKeywords(prefs);
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        connected = this;
        unbinding = false;
        if (!Prefs.get(this, Prefs.NOTICES)) disconnect();
        sendStateChanged();
    }

    private void disconnect() {
        if (unbinding) return;
        unbinding = true;
        try {
            requestUnbind();
        } catch (IllegalStateException | SecurityException ignored) {
            // The system may already have severed the connection. The main switch
            // is also checked on every event and no filtering happens when it is off.
            unbinding = false;
        }
        sendStateChanged();
    }

    @Override public void onListenerDisconnected() {
        boolean stoppedByToggle = unbinding;
        if (connected == this) connected = null;
        unbinding = false;
        super.onListenerDisconnected();
        sendStateChanged();
        if (stoppedByToggle && Prefs.get(this, Prefs.NOTICES)) applyEnabled(this);
    }

    @Override public void onDestroy() {
        Prefs.prefs(this).unregisterOnSharedPreferenceChangeListener(this);
        boolean reenabledWhileStopping = unbinding && Prefs.get(this, Prefs.NOTICES);
        if (connected == this) connected = null;
        sendStateChanged();
        super.onDestroy();
        if (reenabledWhileStopping) applyEnabled(this);
    }

    private void sendStateChanged() {
        sendBroadcast(new Intent(ACTION_STATE_CHANGED).setPackage(getPackageName()));
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (key == null || NoticePolicy.KEYWORDS_KEY.equals(key)) refreshKeywords(prefs);
        if (key == null || Prefs.NOTICES.equals(key)) applyEnabled(this);
    }

    private void refreshKeywords(SharedPreferences prefs) {
        extraTerms = NoticePolicy.customTerms(prefs.getString(NoticePolicy.KEYWORDS_KEY, ""));
    }

    @Override public void onNotificationPosted(StatusBarNotification posted) {
        ScreenWatchService.observeNotification(this,posted);
        if (posted == null || unbinding || !Prefs.get(this, Prefs.NOTICES)) return;
        if (!Process.myUserHandle().equals(posted.getUser()) || getPackageName().equals(posted.getPackageName())) return;

        String mode = Prefs.prefs(this).getString(NoticePolicy.PREFIX + posted.getPackageName(), "");
        if (!NoticePolicy.enabledMode(mode) || !posted.isClearable() || posted.isOngoing()) return;
        Notification notification = posted.getNotification();
        if (notification == null || NoticePolicy.criticalCategory(notification.category)) return;
        int keepFlags = Notification.FLAG_GROUP_SUMMARY | Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_ONGOING_EVENT;
        if ((notification.flags & keepFlags) != 0 || notification.fullScreenIntent != null) return;
        try {
            if (NoticePolicy.PROMOTIONS.equals(mode) && !isPromotion(notification)) return;
            cancelNotification(posted.getKey());
        } catch (BadParcelableException | ClassCastException | IllegalStateException | SecurityException ignored) {
            // Keep notifications with a malformed payload or a connection that just closed.
        }
    }

    private boolean isPromotion(Notification notification) {
        if (NoticePolicy.messageCategory(notification.category)) return false;
        Bundle extras = notification.extras;
        if (extras == null || extras.containsKey(Notification.EXTRA_MESSAGES)) return false;
        StringBuilder text = new StringBuilder();
        appendText(text, extras.getCharSequence(Notification.EXTRA_TITLE));
        appendText(text, extras.getCharSequence(Notification.EXTRA_TEXT));
        appendText(text, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        return NoticePolicy.matchesPromotion(text.toString(), extraTerms);
    }

    private static void appendText(StringBuilder destination, CharSequence source) {
        if (source != null) destination.append(source, 0, Math.min(4096, source.length())).append('\n');
    }
}
