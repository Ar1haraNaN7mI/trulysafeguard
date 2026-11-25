package app.local.privacyguard;

import android.content.Context;

public final class GuardRules {
    private static String lastText = "";
    private static DomainRules compiled = new DomainRules("");

    private GuardRules() {}

    public static synchronized boolean blocked(Context context, String host) {
        String text = Prefs.prefs(context).getString(Prefs.CUSTOM, "");
        if (text == null) text = "";
        if (!text.equals(lastText)) {
            compiled = new DomainRules(text);
            lastText = text;
        }
        return compiled.matches(host);
    }

    /** Empty result means the entry is not a supported hostname. */
    public static String normalizeRule(String value) {
        return DomainRules.normalizeRule(value);
    }
}
