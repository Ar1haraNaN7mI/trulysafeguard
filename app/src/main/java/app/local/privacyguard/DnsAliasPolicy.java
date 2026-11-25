package app.local.privacyguard;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** Follow answer-section aliases reachable from the original question. Glue and unrelated answers do not qualify. */
final class DnsAliasPolicy {
    private static final int CNAME = 5, SVCB = 64, HTTPS = 65;

    private DnsAliasPolicy() {}

    static boolean blocked(DnsPacket question, byte[] response, Predicate<String> blockedHost) {
        if (response.length < question.questionEnd || (response[3] & 15) != 0 || (response[2] & 2) != 0)
            return false;
        int records = DnsPacket.u16(response, 6);
        if (records == 0 || records > 256) return false;
        Map<String, String> aliases = new HashMap<>();
        int start = question.questionEnd;
        for (int i = 0; i < records; i++) {
            Name owner = name(response, start, response.length);
            if (owner == null || owner.end + 10 > response.length) return false;
            int type = DnsPacket.u16(response, owner.end);
            boolean internet = DnsPacket.u16(response, owner.end + 2) == 1;
            int length = DnsPacket.u16(response, owner.end + 8);
            int data = owner.end + 10;
            start = data + length;
            if (start > response.length) return false;
            if (!internet) continue;
            boolean service = (type == SVCB || type == HTTPS) && type == question.type;
            if (service && (length < 3 || DnsPacket.u16(response, data) != 0)) continue;
            if (type != CNAME && !service) continue;
            Name target = name(response, service ? data + 2 : data, start);
            if (target == null || type == CNAME && target.end != start) return false;
            if (target.host.isEmpty()) continue;
            String previous = aliases.put(owner.host, target.host);
            if (previous != null && !previous.equals(target.host)) return false;
        }
        String next = question.host.toLowerCase(Locale.ROOT);
        while ((next = aliases.remove(next)) != null) {
            if (blockedHost.test(next)) return true;
        }
        return false;
    }

    private static final class Name {
        final String host;
        final int end;
        Name(String host, int end) { this.host = host; this.end = end; }
    }

    /** Keep the encoded end in the RR separate from the cursor that expands compression pointers. */
    private static Name name(byte[] data, int cursor, int limit) {
        int end = -1, jumps = 0;
        StringBuilder host = new StringBuilder();
        while (cursor < limit) {
            int size = data[cursor++] & 255;
            if (size == 0) return new Name(host.toString(), end < 0 ? cursor : end);
            if ((size & 0xc0) == 0xc0) {
                if (cursor >= limit || ++jumps > 32) return null;
                int pointer = ((size & 63) << 8) | (data[cursor] & 255);
                if (pointer < 12 || pointer >= cursor - 1) return null;
                if (end < 0) end = cursor + 1;
                limit = cursor - 1;
                cursor = pointer;
            } else {
                if (size > 63 || cursor + size > limit) return null;
                if (host.length() > 0) host.append('.');
                if (host.length() + size > 253) return null;
                for (int n = 0; n < size; n++) {
                    int c = data[cursor++] & 255;
                    if (c <= 32 || c >= 127 || c == '.' || c == '\\') return null;
                    host.append((char)(c >= 'A' && c <= 'Z' ? c + ('a' - 'A') : c));
                }
            }
        }
        return null;
    }
}
