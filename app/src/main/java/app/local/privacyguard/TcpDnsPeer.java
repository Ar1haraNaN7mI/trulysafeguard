package app.local.privacyguard;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A bounded, one-query DNS-over-TCP endpoint for truncated UDP replies. The only
 * destination accepted is our virtual DNS address, port 53. No IP forwarding.
 */
final class TcpDnsPeer {
    interface Resolver {
        void resolve(DnsPacket query, Consumer<byte[]> callback);
    }

    private static final int FIN = 1, SYN = 2, RST = 4, PUSH = 8, ACK = 16;
    private static final int MAX_CONNECTIONS = 48;
    private static final int MAX_QUERY = 8192;
    private final Map<Long, Connection> clients = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Consumer<byte[]> output;
    private final Resolver resolver;

    TcpDnsPeer(Consumer<byte[]> output, Resolver resolver) {
        this.output = output;
        this.resolver = resolver;
    }

    private static final class Connection {
        int clientIp, clientPort, clientNext, serverStart, mss = 536, window;
        int acked, sent, retries;
        boolean established, requested, clientFin, finSent;
        byte[] response;
        long lastSeen, lastSend;
        final ByteArrayOutputStream request = new ByteArrayOutputStream();
    }

    synchronized boolean hasClients() { return !clients.isEmpty(); }

    synchronized void expire(long now) {
        Iterator<Connection> iterator = clients.values().iterator();
        while (iterator.hasNext()) {
            Connection c = iterator.next();
            if (now - c.lastSeen >= 20000 || c.retries >= 3) {
                iterator.remove();
            } else if (c.response != null && (c.sent > c.acked || c.finSent) && now - c.lastSend >= 1500) {
                c.sent = c.acked;
                c.finSent = false;
                c.retries++;
                sendAvailable(c, now);
            }
        }
    }

    synchronized void accept(byte[] frame, int length, long now) {
        int h = DnsPacket.ipHeader(frame, length);
        if (h < 0 || frame[9] != 6 || h + 20 > length || DnsPacket.u16(frame, h + 2) != 53) return;
        int end = h + ((frame[h + 12] & 0xf0) >>> 2);
        if (end < h + 20 || end > length) return;
        int clientIp = DnsPacket.u32(frame, 12);
        int clientPort = DnsPacket.u16(frame, h);
        long key = ((clientIp & 0xffffffffL) << 16) | clientPort;
        int seq = DnsPacket.u32(frame, h + 4);
        int flags = frame[h + 13] & 255;
        int window = DnsPacket.u16(frame, h + 14);
        Connection c = clients.get(key);
        if ((flags & RST) != 0) {
            clients.remove(key);
            return;
        }
        if ((flags & SYN) != 0) {
            if ((flags & (ACK | FIN)) != 0) return;
            if (c == null || c.clientNext != seq + 1 || c.established) {
                if (c == null && clients.size() >= MAX_CONNECTIONS) return;
                c = new Connection();
                c.clientIp = clientIp;
                c.clientPort = clientPort;
                c.clientNext = seq + 1;
                c.serverStart = random.nextInt();
                c.mss = peerMss(frame, h + 20, end);
                clients.put(key, c);
            }
            c.lastSeen = now;
            c.window = window;
            write(c, c.serverStart - 1, SYN | ACK, null, 0, 0);
            return;
        }
        if (c == null || (flags & ACK) == 0) return;
        int ack = DnsPacket.u32(frame, h + 8) - c.serverStart;
        if (ack < 0 || ack > c.sent + (c.finSent ? 1 : 0)) return;
        c.established = true;
        c.lastSeen = now;
        c.window = window;
        boolean finAcked = c.finSent && ack == c.sent + 1;
        if (ack > c.acked && !finAcked) {
            c.acked = ack;
            c.retries = 0;
        }
        int skip = c.clientNext - seq;
        int dataLength = length - end;
        if (skip < 0 || skip > dataLength) {
            write(c, c.serverStart + c.sent + (c.finSent ? 1 : 0), ACK, null, 0, 0);
            return;
        }
        int fresh = dataLength - skip;
        if (fresh > 0) {
            if (c.requested || c.request.size() + fresh > MAX_QUERY + 2) {
                write(c, c.serverStart + c.sent, RST | ACK, null, 0, 0);
                clients.remove(key);
                return;
            }
            c.request.write(frame, end + skip, fresh);
            c.clientNext += fresh;
        }
        boolean hasFin = (flags & FIN) != 0;
        if (hasFin && !c.clientFin) {
            c.clientNext++;
            c.clientFin = true;
        }
        if (hasFin || dataLength > 0)
            write(c, c.serverStart + c.sent + (c.finSent ? 1 : 0), ACK, null, 0, 0);
        if (finAcked) {
            clients.remove(key);
            return;
        }
        if (fresh > 0 && c.request.size() >= 2) {
            byte[] data = c.request.toByteArray();
            int expected = DnsPacket.u16(data, 0);
            if (expected < 17 || expected > MAX_QUERY || data.length > expected + 2) {
                write(c, c.serverStart, RST | ACK, null, 0, 0);
                clients.remove(key);
                return;
            }
            if (data.length == expected + 2) {
                DnsPacket question = DnsPacket.question(Arrays.copyOfRange(data, 2, data.length), clientIp, clientPort);
                if (question == null) {
                    write(c, c.serverStart, RST | ACK, null, 0, 0);
                    clients.remove(key);
                    return;
                }
                c.requested = true;
                final Connection target = c;
                resolver.resolve(question, answer -> reply(key, target, answer));
            }
        }
        sendAvailable(c, now);
    }

    private synchronized void reply(long key, Connection c, byte[] answer) {
        if (clients.get(key) != c) return;
        c.response = new byte[answer.length + 2];
        DnsPacket.put16(c.response, 0, answer.length);
        System.arraycopy(answer, 0, c.response, 2, answer.length);
        sendAvailable(c, System.nanoTime() / 1000000L);
    }

    private void sendAvailable(Connection c, long now) {
        if (c.response == null || c.finSent) return;
        int limit = Math.min(c.response.length, c.acked + c.window);
        while (c.sent < limit) {
            int size = Math.min(c.mss, limit - c.sent);
            write(c, c.serverStart + c.sent, ACK | PUSH, c.response, c.sent, size);
            c.sent += size;
            c.lastSend = now;
        }
        if (c.sent == c.response.length && c.sent - c.acked < c.window) {
            write(c, c.serverStart + c.sent, ACK | FIN, null, 0, 0);
            c.finSent = true;
            c.lastSend = now;
        }
    }

    private static int peerMss(byte[] frame, int p, int end) {
        while (p < end) {
            int kind = frame[p++] & 255;
            if (kind == 0) break;
            if (kind == 1) continue;
            if (p >= end) break;
            int size = frame[p++] & 255;
            if (size < 2 || p + size - 2 > end) break;
            if (kind == 2 && size == 4) return Math.max(64, Math.min(1400, DnsPacket.u16(frame, p)));
            p += size - 2;
        }
        return 536;
    }

    private void write(Connection c, int seq, int flags, byte[] data, int offset, int size) {
        byte[] frame = new byte[40 + size];
        DnsPacket.initIp(frame, c.clientIp, 6);
        DnsPacket.put16(frame, 20, 53);
        DnsPacket.put16(frame, 22, c.clientPort);
        DnsPacket.put32(frame, 24, seq);
        DnsPacket.put32(frame, 28, c.clientNext);
        frame[32] = 0x50;
        frame[33] = (byte) flags;
        DnsPacket.put16(frame, 34, MAX_QUERY + 2);
        if (size != 0) System.arraycopy(data, offset, frame, 40, size);
        DnsPacket.put16(frame, 36, DnsPacket.transportChecksum(frame, 6));
        output.accept(frame);
    }
}
