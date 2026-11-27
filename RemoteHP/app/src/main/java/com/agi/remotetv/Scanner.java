package com.agi.remotetv;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Cari perangkat di WiFi yang membuka port ADB 5555. */
final class Scanner {
    interface Listener {
        void onFound(String ip);
        void onProgress(int done, int total);
    }

    static final class Net { int base; int prefix; String ip; }

    /** Alamat IPv4 HP di WiFi (utamakan wlan). */
    static Net myNet() {
        Net best = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    byte[] b = a.getAddress();
                    int ip = ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
                    int pre = ia.getNetworkPrefixLength();
                    if (pre < 22 || pre > 30) pre = 24; // batasi pencarian maks. 1022 alamat
                    Net n = new Net();
                    n.prefix = pre;
                    n.base = ip & (0xffffffff << (32 - pre));
                    n.ip = a.getHostAddress();
                    boolean wlan = ni.getName().startsWith("wlan");
                    if (best == null || wlan) best = n;
                }
            }
        } catch (Exception ignored) { }
        return best;
    }

    static String str(int ip) {
        return ((ip >>> 24) & 0xff) + "." + ((ip >>> 16) & 0xff) + "." + ((ip >>> 8) & 0xff) + "." + (ip & 0xff);
    }

    /** Memblok sampai selesai. Kembalikan daftar IP yang port 5555-nya terbuka. */
    static List<String> scan(Net net, Listener l) {
        List<String> found = Collections.synchronizedList(new ArrayList<>());
        int count = (1 << (32 - net.prefix)) - 2;
        AtomicInteger done = new AtomicInteger();
        ExecutorService ex = Executors.newFixedThreadPool(64);
        for (int i = 1; i <= count; i++) {
            final String ip = str(net.base + i);
            ex.execute(() -> {
                if (!ip.equals(net.ip)) {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(ip, 5555), 450);
                        found.add(ip);
                        if (l != null) l.onFound(ip);
                    } catch (Exception ignored) { }
                }
                if (l != null) l.onProgress(done.incrementAndGet(), count);
            });
        }
        ex.shutdown();
        try { ex.awaitTermination(60, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return found;
    }
}
