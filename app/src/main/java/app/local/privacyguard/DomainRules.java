package app.local.privacyguard;

import java.net.IDN;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Hostnames only. A rule covers its name and subdomains on DNS label boundaries. */
final class DomainRules {
    private static final String[] BUILT_INS = {
            "adx-drcn.op.dbankcloud.cn", "adx-dra.op.dbankcloud.com",
            "adx-dre.op.dbankcloud.com", "gdt.qq.com", "pangolin-sdk-toutiao.com",
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "ads.mopub.com", "ads.unityads.unity3d.com", "auction.unityads.unity3d.com",
            // Dedicated ad hosts only. Provenance and the list license are in assets/ad-domain-notice.txt.
            "opehs.tanx.com", "adservice.sigmob.cn",
            "mobads.baidu.com", "mobads-pre-config.cdn.bcebos.com", "adscdn.baidu.com",
            "pangolin.snssdk.com", "pangolin-lf.snssdk.com", "pangolin-lq.snssdk.com",
            "pangolin-hl.snssdk.com", "pangolin-dsp-toutiao.com", "pangolin-dsp-toutiao-b.com",
            "pangolin-sdk-toutiao-b.com", "pangolin-sdk-toutiao1.com", "panplayable-toutiao.com",
            "panplayable-toutiao-b.com", "pglstatp-sdk-toutiao.com",
            "adnet.qq.com", "adsview.qq.com", "adsview2.qq.com", "pgdt.gtimg.cn", "ugdtimg.com",
            "open.e.kuaishou.com", "adkwai.com", "adbkwai.com", "adukwai.com",
            "ad.xiaomi.com", "ad1.xiaomi.com", "admob.xiaomi.com", "adv.sec.miui.com",
            "adv.sec.intl.miui.com", "ad.m.iqiyi.com"
    };
    private final Set<String> hosts = new HashSet<>(Arrays.asList(BUILT_INS));

    DomainRules(String additions) {
        for (String line : additions.split("\\r?\\n")) {
            String host = normalizeRule(line);
            if (!host.isEmpty()) hosts.add(host);
        }
    }

    static String normalizeRule(String input) {
        if (input == null) return "";
        String value = input.trim();
        if (value.startsWith("*.")) value = value.substring(2);
        String host = normalizeHost(value);
        // Limiting entries to multi-label hostnames prevents an accidental TLD-wide rule.
        return host.indexOf('.') > 0 && !host.matches("[0-9.]+") ? host : "";
    }

    private static String normalizeHost(String input) {
        if (input == null || input.isEmpty() || input.length() > 254) return "";
        String value = input.endsWith(".") ? input.substring(0, input.length() - 1) : input;
        try {
            String ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            return ascii.length() <= 253 && !ascii.endsWith(".") ? ascii : "";
        } catch (IllegalArgumentException invalidHost) {
            return "";
        }
    }

    boolean matches(String input) {
        String name = normalizeHost(input);
        while (!name.isEmpty()) {
            if (hosts.contains(name)) return true;
            int dot = name.indexOf('.');
            if (dot < 0) break;
            name = name.substring(dot + 1);
        }
        return false;
    }
}
