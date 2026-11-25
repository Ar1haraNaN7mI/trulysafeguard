package app.local.privacyguard;

import android.net.Uri;
import java.util.Locale;

/** Looks only at recognizable file names in HTTP(S) paths and download parameters. */
final class PackageLink {
    private PackageLink() {}
    static boolean matches(Uri uri) {
        if (fileName(uri.getLastPathSegment())) return true;
        for (String key : uri.getQueryParameterNames()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (!(lower.equals("filename") || lower.equals("file") || lower.equals("download")
                    || lower.equals("name") || lower.equals("url"))) continue;
            for (String value : uri.getQueryParameters(key)) {
                int suffix=value.indexOf('?');
                if (fileName(suffix<0?value:value.substring(0,suffix))) return true;
            }
        }
        return false;
    }
    private static boolean fileName(String raw) {
        if (raw == null) return false;
        String name=raw.trim().toLowerCase(Locale.ROOT);
        return name.endsWith(".apk") || name.endsWith(".apks") || name.endsWith(".xapk") || name.endsWith(".apkm");
    }
}
