package app.local.privacyguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.DnsResolver;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.UnknownHostException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Local split-tunnel VPN: the only routed destination is the synthetic DNS address. */
public final class AdDnsService extends VpnService {
    private static final String CHANNEL = "ad-dns";
    private static final String STOP = "app.local.privacyguard.STOP_DNS";
    private static final String COMMAND = "command";
    private static final String ERROR_KEY = "dns_last_error";
    private static final int NOTIFICATION_ID = 2308;
    private static volatile boolean running;
    private static volatile boolean startPending;
    private static volatile long latestCommand;
    private static volatile AtomicLong displayedCount = new AtomicLong();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;

    public static boolean isRunning() { return running; }
    public static long blockedCount() { return displayedCount.get(); }

    @Override protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("running=" + running);
        writer.println("blocked_queries=" + blockedCount());
    }

    public static String lastError(Context context) {
        return Prefs.prefs(context).getString(ERROR_KEY, "");
    }

    /** An unclean process death can leave the user's old enabled flag on disk. */
    public static void reconcileOnOpen(Context context) {
        if (Prefs.get(context, Prefs.DNS) && !running && !startPending)
            fail(context, "上一次本地 VPN 已结束，请重新开启。");
    }

    public static synchronized void start(Context context) {
        long command = ++latestCommand;
        startPending = true;
        Prefs.prefs(context).edit().remove(ERROR_KEY).apply();
        Prefs.set(context, Prefs.DNS, true);
        try {
            context.startForegroundService(new Intent(context, AdDnsService.class).putExtra(COMMAND, command));
        } catch (RuntimeException error) {
            fail(context, "系统未允许启动本地 VPN，请从应用首页重新启用。");
        }
    }

    public static synchronized void stop(Context context) {
        long command = ++latestCommand;
        Prefs.set(context, Prefs.DNS, false);
        if (running || startPending) {
            try {
                context.startService(new Intent(context, AdDnsService.class).setAction(STOP).putExtra(COMMAND, command));
            } catch (RuntimeException unavailable) {
                context.stopService(new Intent(context, AdDnsService.class));
            }
        }
        startPending = false;
    }

    private static void fail(Context context, String reason) {
        startPending = false;
        Prefs.prefs(context).edit().putString(ERROR_KEY, reason).apply();
        Prefs.set(context, Prefs.DNS, false);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // A quick off/on gesture can queue both commands before either service delivery.
        if (intent != null && latestCommand != 0 && intent.hasExtra(COMMAND)
                && intent.getLongExtra(COMMAND, latestCommand) != latestCommand) {
            if (!STOP.equals(intent.getAction())) {
                try { startForeground(NOTIFICATION_ID, notification()); } catch (RuntimeException ignored) { }
            }
            return START_NOT_STICKY;
        }
        if (intent == null || STOP.equals(intent.getAction())) {
            Prefs.set(this, Prefs.DNS, false);
            halt();
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        ParcelFileDescriptor tunnel = null;
        try {
            startForeground(NOTIFICATION_ID, notification());
            startPending = false;
            if (!Prefs.get(this, Prefs.DNS)) {
                halt();
                stopSelfResult(startId);
                return START_NOT_STICKY;
            }
            if (session != null) return START_NOT_STICKY;
            if (VpnService.prepare(this) != null) {
                fail(this, "本地 VPN 尚未获准或授权已被其他 VPN 替换。");
                stopSelfResult(startId);
                return START_NOT_STICKY;
            }
            Builder builder = new Builder()
                    .setSession("广告域名过滤")
                    .setMtu(DnsPacket.MTU)
                    .addAddress("192.0.2.2", 32)
                    .addRoute("192.0.2.53", 32)
                    .addDnsServer("192.0.2.53")
                    .allowFamily(OsConstants.AF_INET6);
            // Resolver calls made by this process use the actual default network.
            builder.addDisallowedApplication(getPackageName());
            tunnel = builder.establish();
            if (tunnel == null) throw new IOException("VPN consent unavailable");
            session = new Session(tunnel);
            tunnel = null;
            displayedCount = session.blockedQueries;
            running = true;
            session.start();
        } catch (IOException | ErrnoException | PackageManager.NameNotFoundException | RuntimeException error) {
            if (tunnel != null) try { tunnel.close(); } catch (IOException ignored) { }
            fail(this, "系统未能建立本地 DNS 过滤连接，请检查系统 VPN 状态。");
            halt();
            stopSelfResult(startId);
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "广告域名过滤", NotificationManager.IMPORTANCE_LOW));
        Notification.Builder notice = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("广告域名过滤已开启")
                .setContentText("本地处理系统 DNS 查询")
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) notice.setContentIntent(PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE));
        Intent stop = new Intent(this, AdDnsService.class).setAction(STOP);
        PendingIntent tap = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_IMMUTABLE);
        notice.addAction(new Notification.Action.Builder(null, "关闭过滤", tap).build());
        return notice.build();
    }

    private void halt() {
        running = false;
        if (session != null) {
            session.requestClose();
            session = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    @Override public void onRevoke() {
        main.post(() -> {
            fail(this, "VPN 授权已撤销，广告域名过滤已关闭。");
            halt();
            stopSelf();
        });
    }

    @Override public void onDestroy() {
        halt();
        if (!startPending && Prefs.get(this, Prefs.DNS))
            fail(this, "本地 VPN 已结束，请在需要时重新开启。");
        super.onDestroy();
    }

    private final class Session extends Thread {
        private final ParcelFileDescriptor tun;
        private final FileDescriptor[] wake;
        private final Object ioLock = new Object();
        private final AtomicLong blockedQueries = new AtomicLong();
        private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 6, 20, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(48));
        private final TcpDnsPeer tcp = new TcpDnsPeer(this::writePacket, this::resolve);
        private volatile boolean stopping;

        Session(ParcelFileDescriptor tun) throws ErrnoException {
            super("Local DNS tunnel");
            this.tun = tun;
            wake = Os.pipe();
        }

        void requestClose() {
            synchronized (ioLock) {
                if (stopping) return;
                stopping = true;
                try { Os.write(wake[1], new byte[]{1}, 0, 1); } catch (Exception ignored) { }
            }
            workers.shutdownNow();
        }

        @Override public void run() {
            try {
                byte[] packet = new byte[DnsPacket.MTU];
                StructPollfd data = pollFd(tun.getFileDescriptor());
                StructPollfd stop = pollFd(wake[0]);
                StructPollfd[] watched = {data, stop};
                while (!stopping) {
                    Os.poll(watched, tcp.hasClients() ? 1000 : -1);
                    long now = System.nanoTime() / 1000000L;
                    if (stopping || stop.revents != 0) break;
                    if ((data.revents & (OsConstants.POLLHUP | OsConstants.POLLERR | OsConstants.POLLNVAL)) != 0) break;
                    tcp.expire(now);
                    if ((data.revents & OsConstants.POLLIN) == 0) continue;
                    int count = Os.read(tun.getFileDescriptor(), packet, 0, packet.length);
                    if (count <= 0) break;
                    if (count >= 20 && packet[9] == 6) {
                        tcp.accept(packet, count, now);
                    } else {
                        DnsPacket query = DnsPacket.udp(packet, count);
                        if (query != null) resolve(query, answer -> writePacket(query.udpReply(answer)));
                    }
                }
            } catch (IOException | ErrnoException | RuntimeException error) {
                // The owning service reports failure below unless shutdown was requested.
            } finally {
                boolean lost = !stopping;
                requestClose();
                synchronized (ioLock) {
                    try { tun.close(); } catch (IOException ignored) { }
                    for (FileDescriptor fd : wake) try { Os.close(fd); } catch (ErrnoException ignored) { }
                }
                if (lost) main.post(() -> {
                    if (session != this) return;
                    fail(AdDnsService.this, "本地 VPN 已断开，请在需要时重新开启。");
                    halt();
                    stopSelf();
                });
            }
        }

        private StructPollfd pollFd(FileDescriptor fd) {
            StructPollfd watched = new StructPollfd();
            watched.fd = fd;
            watched.events = (short) OsConstants.POLLIN;
            return watched;
        }

        private void writePacket(byte[] frame) {
            synchronized (ioLock) {
                if (stopping) return;
                try {
                    Os.write(tun.getFileDescriptor(), frame, 0, frame.length);
                } catch (IOException | ErrnoException ignored) {
                    // A full nonblocking TUN queue drops this packet. DNS/TCP may retry.
                }
            }
        }

        private void resolve(DnsPacket query, Consumer<byte[]> callback) {
            if (stopping) return;
            if (GuardRules.blocked(AdDnsService.this, query.host)) {
                blockedQueries.incrementAndGet();
                callback.accept(query.status(3)); // NXDOMAIN
                return;
            }
            try {
                workers.execute(() -> {
                    byte[] answer;
                    try {
                        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
                        Network network = cm.getActiveNetwork();
                        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                        if (network == null || caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
                            answer = query.status(2);
                        else if (Build.VERSION.SDK_INT >= 29) {
                            answer = ModernDns.lookup(network, query);
                            if (!stopping && DnsAliasPolicy.blocked(query, answer, host -> GuardRules.blocked(AdDnsService.this, host))) {
                                blockedQueries.incrementAndGet();
                                answer = query.status(3);
                            }
                        }
                        else if (query.type == 1 || query.type == 28) answer = query.addresses(network.getAllByName(query.host));
                        else answer = query.status(4); // Android 8/9 provides only address queries here.
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (UnknownHostException | RuntimeException error) {
                        answer = query.status(2);
                    }
                    if (!stopping) callback.accept(answer);
                });
            } catch (RejectedExecutionException busy) {
                if (!stopping) callback.accept(query.status(2));
            }
        }
    }

    @android.annotation.TargetApi(29)
    private static final class ModernDns {
        static byte[] lookup(Network network, DnsPacket query) throws InterruptedException {
            CountDownLatch ready = new CountDownLatch(1);
            CancellationSignal cancel = new CancellationSignal();
            AtomicReference<byte[]> value = new AtomicReference<>();
            try {
                // Android chooses the physical network's configured resolver, including Private DNS.
                DnsResolver.getInstance().rawQuery(network, query.query, DnsResolver.FLAG_EMPTY,
                        Runnable::run, cancel, new DnsResolver.Callback<byte[]>() {
                            @Override public void onAnswer(byte[] answer, int rcode) {
                                value.set(answer);
                                ready.countDown();
                            }
                            @Override public void onError(DnsResolver.DnsException error) { ready.countDown(); }
                        });
                if (!ready.await(6, TimeUnit.SECONDS)) return query.status(2);
                return query.validatedAnswer(value.get());
            } finally {
                cancel.cancel();
            }
        }
    }
}
