package app.local.privacyguard;

import android.Manifest;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** A fixed rule export and a bounded status inbox for the USB companion. */
public final class UsbPolicyProvider extends ContentProvider {
    static final int SENSORS = 1, NOTIFICATIONS = 2, SCREEN = 4, LIMIT = 128;
    static final String[] FIELDS = {"sensors", "notifications", "screen", "sources"};
    static final String LAST_CONTACT = "lastContact";
    static final String PHONE_MODE = "phoneMode", PHONE_TIME = "phoneTime", PHONE_STATE = "phoneState";
    private static volatile long livePhoneTime;
    static long phoneTime(SharedPreferences p) {return Math.max(livePhoneTime,p.getLong(PHONE_TIME,0));}
    static long phoneAge(SharedPreferences p) {return System.currentTimeMillis()-phoneTime(p);}
    static boolean declaresInstall(Context c,String pkg) {
        try {
            String[] wanted=c.getPackageManager().getPackageInfo(pkg,PackageManager.GET_PERMISSIONS).requestedPermissions;
            return wanted!=null && java.util.Arrays.asList(wanted).contains("android.permission.REQUEST_INSTALL_PACKAGES");
        } catch(PackageManager.NameNotFoundException ex){return false;}
    }
    private static final String RULE = "rule.", INSTALL = "install.", REPORT = "report.";
    private static final Uri POLICY = Uri.parse("content://app.local.privacyguard.usb/policy");
    private static final Uri PHONE = Uri.parse("content://app.local.privacyguard.usb/phone");
    private static final int BASE64 = Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP;
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Pattern WIRE = Pattern.compile("[A-Za-z0-9_-]+");
    // A status received after an edit, but before any export of that edit, cannot acknowledge it.
    private static final Map<String, String> exported = new HashMap<>();
    private static volatile long lastContact;

    static long lastContact(SharedPreferences prefs) {
        long live = lastContact;
        return live == 0 ? prefs.getLong(LAST_CONTACT, 0) : live;
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("usb_app_rules", Context.MODE_PRIVATE);
    }

    /** Returns an install-specific identity for eligible primary-user packages. */
    @SuppressWarnings("deprecation")
    static String identity(Context c, String pkg, boolean fromLauncherQuery) {
        if (Process.myUid() > Process.LAST_APPLICATION_UID || pkg == null || pkg.length() > 255
                || !PACKAGE.matcher(pkg).matches() || pkg.equals(c.getPackageName())) return null;
        PackageManager pm = c.getPackageManager();
        try {
            PackageInfo info = pm.getPackageInfo(pkg, 0);
            ApplicationInfo app = info.applicationInfo;
            if (app == null || !app.enabled || app.uid == Process.myUid() || info.sharedUserId != null
                    || app.uid < Process.FIRST_APPLICATION_UID || app.uid > Process.LAST_APPLICATION_UID
                    || (app.flags & ApplicationInfo.FLAG_INSTALLED) == 0
                    || (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0)
                return null;
            String[] siblings = pm.getPackagesForUid(app.uid);
            if (siblings == null || siblings.length != 1 || !pkg.equals(siblings[0])) return null;
            if (!fromLauncherQuery && pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), 0).isEmpty()) return null;
            return app.uid + "/" + info.firstInstallTime;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return null;
        }
    }

    static int rule(SharedPreferences p, String pkg, String identity) {
        return identity != null && identity.equals(p.getString(INSTALL + pkg, null))
                ? p.getInt(RULE + pkg, -1) : -1;
    }

    static JSONObject report(SharedPreferences p, String pkg, String identity) {
        if (rule(p, pkg, identity) < 0) return null;
        String value = p.getString(REPORT + pkg, null);
        if (value == null) return null;
        try { return new JSONObject(value); }
        catch (JSONException malformed) { return null; }
    }

    private static TreeSet<String> packages(SharedPreferences prefs) {
        TreeSet<String> names = new TreeSet<>();
        for (String key : prefs.getAll().keySet()) if (key.startsWith(RULE)) names.add(key.substring(RULE.length()));
        return names;
    }

    /** Saves only a locally confirmed package. A zero rule is retained for restoration. */
    static synchronized String save(Context c, String pkg, String expectedIdentity, int bits) {
        String id = identity(c, pkg, false);
        if (id == null || !id.equals(expectedIdentity) || bits < 0 || bits > 15 || ((bits & 8)!=0 && !declaresInstall(c,pkg)))
            return "应用已变更或不符合条件，请重新打开列表。";
        SharedPreferences p = prefs(c);
        int old = rule(p, pkg, id);
        if (bits == old || (old < 0 && bits == 0)) return null;
        if (!p.contains(RULE + pkg) && packages(p).size() >= LIMIT)
            return "已达到 128 个应用的上限，此应用的规则未保存。";
        exported.remove(pkg);
        if (!p.edit().putInt(RULE + pkg, bits).putString(INSTALL + pkg, id)
                .remove(REPORT + pkg).commit()) return "无法在本机保存规则，请重试。";
        return null;
    }

    private static synchronized String export(Context c,boolean phone) throws JSONException {
        SharedPreferences p = prefs(c);
        JSONArray rules = new JSONArray();
        String mode=p.getString(PHONE_MODE,"desktop");
        boolean use=phone || mode.equals("desktop");
        if (use) exported.clear();
        for (String pkg : use?packages(p):new TreeSet<String>()) {
            String id = identity(c, pkg, false);
            int bits = rule(p, pkg, id);
            if (bits < 0 || bits > 15) continue;
            JSONObject row = new JSONObject().put("pkg", pkg);
            if(phone)row.put("id",id).put("declaresInstall",declaresInstall(c,pkg));
            for (int i = 0; i < FIELDS.length; i++) row.put(FIELDS[i], (bits & (1 << i)) != 0);
            rules.put(row);
            exported.put(pkg, id + "/" + bits);
            if (rules.length() == LIMIT) break;
        }
        JSONObject body=new JSONObject().put("v",phone?2:1).put("mode",mode).put("rules",rules);
        if(phone)body.put("phoneTime",phoneTime(p)).put("phoneState",p.getString(PHONE_STATE,""))
                .put("lastUnplugged",p.getLong("lastUnplugged",0));
        String json=body.toString();
        return Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), BASE64);
    }

    private static String statusText(JSONObject row, String key) throws JSONException {
        Object raw = row.get(key);
        if (!(raw instanceof String)) throw new JSONException("Invalid status text");
        String text = (String) raw;
        if (text.length() > 256) throw new JSONException("Status field too long");
        for (int i = 0; i < text.length(); i++)
            if (Character.isISOControl(text.charAt(i))) throw new JSONException("Unexpected control character");
        return text;
    }

    private static synchronized void receive(Context c, String arg, boolean phone) throws JSONException {
        if (arg == null || arg.length() > 256 * 1024 || !WIRE.matcher(arg).matches())
            throw new IllegalArgumentException("Invalid status encoding");
        JSONObject body = new JSONObject(new String(Base64.decode(arg, BASE64), StandardCharsets.UTF_8));
        JSONArray rows = body.getJSONArray("status");
        long time = body.getLong("time");
        if (body.getInt("v") != 1 || rows.length() > LIMIT || time <= 0 || time > 253402300799L)
            throw new IllegalArgumentException("Invalid status envelope");
        SharedPreferences p = prefs(c);
        if (!phone && !"desktop".equals(p.getString(PHONE_MODE,"desktop")))return;
        long now = System.currentTimeMillis();
        long stored = p.getLong(LAST_CONTACT, 0);
        // A connected helper polls frequently. Keep unchanged heartbeats in memory and
        // checkpoint at most once per minute; persist actual changes immediately.
        boolean checkpoint = stored <= 0 || now < stored || now - stored >= 60_000;
        SharedPreferences.Editor editor = p.edit();
        boolean changed = false;
        if(phone){
            String state=statusText(body,"state");
            livePhoneTime=now;
            Intent usb=c.registerReceiver(null,new android.content.IntentFilter("android.hardware.usb.action.USB_STATE"));
            if (usb!=null && usb.hasExtra("connected") && !usb.getBooleanExtra("connected",true)
                    && now-p.getLong("lastUnplugged",0)>25000){
                editor.putLong("lastUnplugged",now);changed=true;
            }
            if (!state.equals(p.getString(PHONE_STATE,"")) || now-p.getLong(PHONE_TIME,0)>45_000 || now<p.getLong(PHONE_TIME,0)){
                editor.putString(PHONE_STATE,state).putLong(PHONE_TIME,now);changed=true;
            }
        }
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String pkg = row.getString("pkg");
            if (pkg.length() > 255 || !PACKAGE.matcher(pkg).matches() || !seen.add(pkg))
                throw new IllegalArgumentException("Invalid status package");
            JSONObject result = new JSONObject().put("time", time).put("received", now);
            for (String field : FIELDS) result.put(field, !phone && field.equals("sources") ? "需要激活手机端助手" : statusText(row, field));
            String id = p.getString(INSTALL + pkg, null);
            int bits = rule(p, pkg, id);
            if (bits >= 0 && (id + "/" + bits).equals(exported.get(pkg))) {
                JSONObject previous = report(p, pkg, id);
                boolean same = previous != null;
                if (same) for (String field : FIELDS) {
                    if (!result.getString(field).equals(previous.optString(field, null))) { same = false; break; }
                }
                if (checkpoint || !same) {
                    editor.putString(REPORT + pkg, result.toString());
                    changed = true;
                }
            }
        }
        if ((checkpoint || changed) && !editor.putLong(LAST_CONTACT, now).commit())
            throw new IllegalStateException("Could not save status");
        lastContact = now;
    }

    private void requireShell() {
        int uid = Binder.getCallingUid();
        if ((uid != Process.SHELL_UID && uid != Process.ROOT_UID)
                || getContext().checkCallingPermission(Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Shell or root with android.permission.DUMP required");
    }

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] columns, String where, String[] values, String order) {
        requireShell();
        if ((!POLICY.equals(uri) && !PHONE.equals(uri)) || where != null || values != null || order != null ||
                (columns != null && (columns.length != 1 || !"payload".equals(columns[0]))))
            throw new IllegalArgumentException("Expected /policy with payload projection");
        long token = Binder.clearCallingIdentity();
        try {
            MatrixCursor cursor = new MatrixCursor(new String[]{"payload"}, 1);
            cursor.addRow(new Object[]{export(getContext(),PHONE.equals(uri))});
            return cursor;
        } catch (JSONException invalid) {
            throw new IllegalStateException(invalid);
        } finally { Binder.restoreCallingIdentity(token); }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        requireShell();
        if (!("status".equals(method)||"phoneStatus".equals(method)||"activatePhone".equals(method)) || (extras != null && !extras.isEmpty()))
            throw new IllegalArgumentException("Only the status method is supported");
        long token = Binder.clearCallingIdentity();
        try {
            if("activatePhone".equals(method)){
                if(arg!=null)throw new IllegalArgumentException();
                if(!prefs(getContext()).edit().putString(PHONE_MODE,"run").commit())throw new IllegalStateException();
            } else receive(getContext(), arg,"phoneStatus".equals(method));
            Bundle result = new Bundle();
            result.putBoolean("ok", true);
            return result;
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("Malformed status", invalid);
        } finally { Binder.restoreCallingIdentity(token); }
    }

    @Override public String getType(Uri uri) {
        requireShell();
        if (!POLICY.equals(uri)) throw new IllegalArgumentException("Unknown path");
        return "vnd.android.cursor.item/vnd.app.local.privacyguard.usb-policy";
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}
