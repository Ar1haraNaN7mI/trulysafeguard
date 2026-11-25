package app.local.privacyguard;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.util.Arrays;

/** A single standard IN question arriving at our virtual IPv4 DNS address. */
final class DnsPacket {
    static final int DNS_ADDRESS = 0xc0000235;
    static final int MTU = 16384;
    final byte[] query;
    final String host;
    final int type;
    final int questionEnd;
    final int clientAddress;
    final int clientPort;
    final int udpLimit;

    private DnsPacket(byte[] query, String host, int type, int end,
                      int address, int port, int udpLimit) {
        this.query = query;
        this.host = host;
        this.type = type;
        this.questionEnd = end;
        this.clientAddress = address;
        this.clientPort = port;
        this.udpLimit = udpLimit;
    }

    static int u16(byte[] data, int at) {
        return ((data[at] & 255) << 8) | (data[at + 1] & 255);
    }

    static int u32(byte[] data, int at) {
        return (u16(data, at) << 16) | u16(data, at + 2);
    }

    static void put16(byte[] data, int at, int value) {
        data[at] = (byte) (value >>> 8);
        data[at + 1] = (byte) value;
    }

    static void put32(byte[] data, int at, int value) {
        put16(data, at, value >>> 16);
        put16(data, at + 2, value);
    }

    /** IPv4 header size if this is an unfragmented packet to our one host route. */
    static int ipHeader(byte[] frame, int length) {
        if (length < 20 || length > frame.length || (frame[0] >>> 4) != 4) return -1;
        int header = (frame[0] & 15) * 4;
        if (header < 20 || header > length || u16(frame, 2) != length) return -1;
        if ((u16(frame, 6) & 0x3fff) != 0 || u32(frame, 16) != DNS_ADDRESS) return -1;
        return header;
    }

    static DnsPacket udp(byte[] frame, int length) {
        int h = ipHeader(frame, length);
        if (h < 0 || frame[9] != 17 || length < h + 8 || u16(frame, h + 2) != 53) return null;
        int udpLength = u16(frame, h + 4);
        if (udpLength != length - h || udpLength < 20) return null;
        return question(Arrays.copyOfRange(frame, h + 8, length), u32(frame, 12), u16(frame, h));
    }

    static DnsPacket question(byte[] query, int address, int port) {
        // This local resolver accepts one ordinary query and an optional EDNS OPT record.
        if (query.length < 17 || query.length > 8192 || (u16(query, 2) & ~0x0130) != 0
                || u16(query, 4) != 1 || u16(query, 6) != 0 || u16(query, 8) != 0) return null;
        StringBuilder name = new StringBuilder();
        int pos = 12;
        while (pos < query.length) {
            int label = query[pos++] & 255;
            if (label == 0) break;
            if (label > 63 || pos + label >= query.length || pos + label - 12 > 254) return null;
            if (name.length() != 0) name.append('.');
            for (int i = 0; i < label; i++) {
                int c = query[pos++] & 255;
                if (c <= 32 || c >= 127 || c == '.' || c == '\\') return null;
                name.append((char) c);
            }
        }
        if (query[pos - 1] != 0 || pos + 4 > query.length || u16(query, pos + 2) != 1) return null;
        int type = u16(query, pos);
        if (type == 0 || type == 41 || (type >= 249 && type <= 254)) return null;
        int end = pos + 4;
        int limit = 512;
        int extra = u16(query, 10);
        if (extra == 0 && end != query.length) return null;
        if (extra > 0) {
            if (extra != 1 || end + 11 > query.length || query[end] != 0
                    || u16(query, end + 1) != 41 || end + 11 + u16(query, end + 9) != query.length) return null;
            limit = Math.max(512, Math.min(MTU - 28, u16(query, end + 3)));
        }
        return new DnsPacket(query, name.toString(), type, end, address, port, limit);
    }

    byte[] status(int code) {
        byte[] data = Arrays.copyOf(query, questionEnd);
        put16(data, 2, 0x8080 | (u16(query, 2) & 0x0110) | (code & 15));
        put16(data, 6, 0);
        put16(data, 8, 0);
        put16(data, 10, 0);
        return data;
    }

    byte[] validatedAnswer(byte[] data) {
        if (data == null || data.length < questionEnd || data.length > 65200
                || (data[2] & 0x80) == 0 || u16(data, 4) != 1) return status(2);
        for (int i = 12; i < questionEnd; i++) {
            int got = data[i] & 255;
            int want = query[i] & 255;
            // DNS servers may change the case of the question's label characters.
            if (i < questionEnd - 4) {
                if (got >= 'A' && got <= 'Z') got += 32;
                if (want >= 'A' && want <= 'Z') want += 32;
            }
            if (want != got) return status(2);
        }
        data[0] = query[0];
        data[1] = query[1];
        return data;
    }

    /** Used by Android 8/9's network-bound system resolver when private DNS is active. */
    byte[] addresses(InetAddress[] values) {
        byte[] header = status(0);
        ByteArrayOutputStream records = new ByteArrayOutputStream();
        int count = 0;
        for (InetAddress value : values) {
            byte[] address = value.getAddress();
            if ((type != 1 || address.length != 4) && (type != 28 || address.length != 16)) continue;
            byte[] record = new byte[12 + address.length];
            put16(record, 0, 0xc00c);
            put16(record, 2, type);
            put16(record, 4, 1);
            // This API does not expose the original TTL. Do not invent a long cache lifetime.
            put16(record, 10, address.length);
            System.arraycopy(address, 0, record, 12, address.length);
            records.write(record, 0, record.length);
            count++;
        }
        put16(header, 6, count);
        byte[] result = Arrays.copyOf(header, header.length + records.size());
        System.arraycopy(records.toByteArray(), 0, result, header.length, records.size());
        return result;
    }

    byte[] udpReply(byte[] response) {
        if (response.length > udpLimit) {
            byte[] truncated = status(0);
            put16(truncated, 2, u16(response, 2) | 0x0200);
            response = truncated;
        }
        byte[] frame = new byte[28 + response.length];
        initIp(frame, clientAddress, 17);
        put16(frame, 20, 53);
        put16(frame, 22, clientPort);
        put16(frame, 24, frame.length - 20);
        System.arraycopy(response, 0, frame, 28, response.length);
        int sum = transportChecksum(frame, 17);
        put16(frame, 26, sum == 0 ? 65535 : sum);
        return frame;
    }

    static void initIp(byte[] frame, int target, int proto) {
        frame[0] = 0x45;
        put16(frame, 2, frame.length);
        frame[8] = 64;
        frame[9] = (byte) proto;
        put32(frame, 12, DNS_ADDRESS);
        put32(frame, 16, target);
        put16(frame, 10, checksum(frame, 0, 20, 0));
    }

    static int transportChecksum(byte[] frame, int proto) {
        long sum = proto + frame.length - 20;
        for (int i = 12; i < 20; i += 2) sum += u16(frame, i);
        return checksum(frame, 20, frame.length, sum);
    }

    private static int checksum(byte[] data, int start, int end, long sum) {
        for (int i = start; i < end; i += 2) {
            sum += (data[i] & 255) << 8;
            if (i + 1 < end) sum += data[i + 1] & 255;
        }
        while ((sum >>> 16) != 0) sum = (sum & 65535) + (sum >>> 16);
        return (int) ~sum & 65535;
    }
}
