package app.local.privacyguard;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** The host shown, checked against rules and sent to the browser must be identical. */
final class LinkControl {
    final String host;
    final String origin;
    final String url;

    private LinkControl(String host, String origin, String url) {
        this.host = host;
        this.origin = origin;
        this.url = url;
    }

    static LinkControl parse(String input) throws URISyntaxException {
        URI uri = new URI(input);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw invalid();
        }
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty() || authority.indexOf('@') >= 0
                || authority.indexOf('%') >= 0) {
            throw invalid();
        }

        String rawHost;
        String portSuffix;
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            if (end < 0 || uri.getHost() == null) throw invalid();
            rawHost = authority.substring(0, end + 1).toLowerCase(Locale.ROOT);
            portSuffix = authority.substring(end + 1);
        } else {
            int colon = authority.indexOf(':');
            String value = colon < 0 ? authority : authority.substring(0, colon);
            portSuffix = colon < 0 ? "" : authority.substring(colon);
            try {
                rawHost = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException malformed) {
                throw invalid();
            }
            if (rawHost.endsWith(".")) rawHost = rawHost.substring(0, rawHost.length() - 1);
            if (rawHost.isEmpty() || rawHost.length() > 253) throw invalid();
            // Browsers reinterpret short, octal and hex IPv4 addresses. The prompt
            // should never display one host while the browser visits a different one.
            String[] labels = rawHost.split("\\.");
            if (labels[labels.length - 1].matches("[0-9]+|0x[0-9a-f]*")) {
                if (labels.length != 4) throw invalid();
                for (String part : labels) {
                    if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) {
                        throw invalid();
                    }
                }
            }
        }

        if (!portSuffix.isEmpty()) {
            if (!portSuffix.matches(":[0-9]{1,5}")) throw invalid();
            int port = Integer.parseInt(portSuffix.substring(1));
            if (port < 1 || port > 65535) throw invalid();
            portSuffix = ":" + port;
        }
        String visibleOrigin = scheme.toLowerCase(Locale.ROOT) + "://" + rawHost + portSuffix;
        StringBuilder clean = new StringBuilder(visibleOrigin);
        clean.append(uri.getRawPath());
        if (uri.getRawQuery() != null) clean.append('?').append(uri.getRawQuery());
        if (uri.getRawFragment() != null) clean.append('#').append(uri.getRawFragment());
        return new LinkControl(rawHost, visibleOrigin, clean.toString());
    }

    private static URISyntaxException invalid() {
        // Exception messages can appear in diagnostics; do not include the URL.
        return new URISyntaxException("", "Invalid web address");
    }
}
