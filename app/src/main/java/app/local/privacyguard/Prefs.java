package app.local.privacyguard;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    public static final String LINKS="links", DNS="dns", NOTICES="notices", SECURE="shield_private_windows", CUSTOM="custom";
    private Prefs() {}
    static final String DENY_WEB="deny_all_dispatched_web";
    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("guard_local", Context.MODE_PRIVATE); }
    public static boolean get(Context c,String key) { return prefs(c).getBoolean(key, false); }
    public static void set(Context c,String key,boolean value) { prefs(c).edit().putBoolean(key,value).apply(); }
}
