package app.local.privacyguard;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local observations of completed, new installations. No installer or uninstall permissions. */
public final class InstallAuditJob extends JobService {
    private static final int JOB = 36115, LIMIT = 40;
    private static final String SINCE = "enabledSince", LOG = "recent";
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static BroadcastReceiver receiver;
    private JobParameters active;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("install_audit", MODE_PRIVATE);
    }

    static boolean enabled(Context c) { return prefs(c).getLong(SINCE, 0) != 0; }

    static synchronized void setEnabled(Context context, boolean enable) {
        if (enabled(context) != enable) {
            prefs(context).edit().putLong(SINCE, enable ? System.currentTimeMillis() : 0).apply();
        }
        initialize(context);
    }

    public static synchronized void initialize(Context context) {
        Context app = context.getApplicationContext();
        JobScheduler jobs = app.getSystemService(JobScheduler.class);
        if (!enabled(app)) {
            if (receiver != null) { app.unregisterReceiver(receiver); receiver = null; }
            if (jobs != null && jobs.getPendingJob(JOB) != null) jobs.cancel(JOB);
            return;
        }
        if (receiver == null) {
            final BroadcastReceiver listener = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    if (!Intent.ACTION_PACKAGE_ADDED.equals(i.getAction()) || i.getData() == null ||
                            i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                    String pkg = i.getData().getSchemeSpecificPart();
                    if (pkg == null) return;
                    final long since = prefs(c).getLong(SINCE, 0);
                    final PendingResult pending = goAsync();
                    worker.execute(() -> {
                        try {
                            if (since != 0) {
                                PackageInfo info = app.getPackageManager().getPackageInfo(pkg, 0);
                                record(app, since, Collections.singletonList(info));
                            }
                        } catch (PackageManager.NameNotFoundException | SecurityException ignored) {
                            // A short-lived package may already be gone.
                        } finally { pending.finish(); }
                    });
                }
            };
            IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            filter.addDataScheme("package");
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(listener, filter, Context.RECEIVER_EXPORTED);
            else app.registerReceiver(listener, filter);
            receiver = listener;
        }
        if (jobs != null && jobs.getPendingJob(JOB) == null) {
            JobInfo task = new JobInfo.Builder(JOB, new ComponentName(app, InstallAuditJob.class))
                    .setPeriodic(15 * 60_000L).setPersisted(true).build();
            try { jobs.schedule(task); } catch (IllegalStateException | SecurityException ignored) {
                // The screen shows whether Android accepted periodic scheduling.
            }
        }
    }

    static boolean scheduled(Context c) {
        JobScheduler jobs = c.getSystemService(JobScheduler.class);
        return jobs != null && jobs.getPendingJob(JOB) != null;
    }

    static void scanAsync(Context context, Runnable done) {
        Context app = context.getApplicationContext();
        long since = prefs(app).getLong(SINCE, 0);
        worker.execute(() -> {
            try {
                if (since != 0) record(app, since, app.getPackageManager().getInstalledPackages(0));
            } catch (SecurityException ignored) {
                // Scoped package visibility and OEM policy can reduce coverage.
            } finally { if (done != null) main.post(done); }
        });
    }

    private static List<JSONObject> entries(Context c) {
        List<JSONObject> records = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(prefs(c).getString(LOG, "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject row = list.optJSONObject(i);
                if (row != null) records.add(row);
            }
        } catch (JSONException ignored) {}
        return records;
    }

    static synchronized List<JSONObject> history(Context c) { return entries(c); }

    static synchronized void clear(Context c) {
        SharedPreferences.Editor edit = prefs(c).edit().remove(LOG);
        // A fresh cutoff prevents the next scan from restoring the erased entries.
        if (enabled(c)) edit.putLong(SINCE, System.currentTimeMillis());
        edit.apply();
    }

    private static void source(PackageManager pm, JSONObject row, String pkg) throws JSONException {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                InstallSourceInfo info = pm.getInstallSourceInfo(pkg);
                row.put("installer", info.getInstallingPackageName());
                row.put("initiator", info.getInitiatingPackageName());
            } else {
                row.put("installer", pm.getInstallerPackageName(pkg));
            }
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException | SecurityException ignored) {}
    }

    private static synchronized void record(Context c, long expectedSince, List<PackageInfo> packages) {
        if (expectedSince == 0 || prefs(c).getLong(SINCE, 0) != expectedSince) return;
        List<JSONObject> rows = entries(c);
        boolean changed = false;
        PackageManager pm = c.getPackageManager();
        for (PackageInfo info : packages) {
            if (info.firstInstallTime < expectedSince || info.packageName.equals(c.getPackageName())) continue;
            int position = 0;
            boolean known = false;
            for (JSONObject existing : rows) {
                long time = existing.optLong("time");
                if (info.firstInstallTime > time) break;
                if (info.firstInstallTime == time) {
                    int order = info.packageName.compareTo(existing.optString("pkg"));
                    if (order <= 0) { known = order == 0; break; }
                }
                position++;
            }
            // Packages outside the retained window need no repeated source lookups every 15 minutes.
            if (known || position >= LIMIT) continue;
            JSONObject row = new JSONObject();
            try {
                row.put("time", info.firstInstallTime);
                row.put("pkg", info.packageName);
                source(pm, row, info.packageName);
            } catch (JSONException ignored) { continue; }
            rows.add(position, row);
            if (rows.size() > LIMIT) rows.remove(LIMIT);
            changed = true;
        }
        if (changed) {
            JSONArray result = new JSONArray();
            for (JSONObject row : rows) result.put(row);
            String encoded = result.toString();
            if (!encoded.equals(prefs(c).getString(LOG, "[]"))) prefs(c).edit().putString(LOG, encoded).apply();
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        if (!enabled(this)) return false;
        active = params;
        scanAsync(this, () -> {
            if (active == params) { active = null; jobFinished(params, false); }
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        if (active == params) active = null;
        return enabled(this);
    }
}
