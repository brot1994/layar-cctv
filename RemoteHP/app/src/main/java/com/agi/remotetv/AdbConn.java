package com.agi.remotetv;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Klien ADB lewat jaringan (TCP 5555) yang ditulis langsung dengan Java, tanpa adb.exe.
 * Satu sambungan bisa membawa banyak "stream" sekaligus (tombol, gambar layar, pasang APK).
 */
final class AdbConn {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, WRTE = 0x45545257, CLSE = 0x45534c43;
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
    private static final byte[] EOF = new byte[0];

    interface AuthListener { void onNeedConfirm(); }
    interface Progress { void onProgress(long done, long total); }

    final String host;
    final int port;
    private Socket sock;
    private DataInputStream in;
    private OutputStream out;
    private int maxData = 4096;
    private volatile boolean alive;
    private final Map<Integer, Stream> streams = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    private AdbConn(String host, int port) { this.host = host; this.port = port; }

    boolean isAlive() { return alive; }

    // ------------------------------------------------------------------ paket

    private static final class Msg { int cmd, arg0, arg1; byte[] data; }

    private void send(int cmd, int a0, int a1, byte[] d) throws Exception {
        if (d == null) d = EOF;
        int crc = 0;
        for (byte b : d) crc += (b & 0xff);
        ByteBuffer bb = ByteBuffer.allocate(24 + d.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(cmd).putInt(a0).putInt(a1).putInt(d.length).putInt(crc).putInt(~cmd);
        bb.put(d);
        synchronized (this) {
            out.write(bb.array());
            out.flush();
        }
    }

    private Msg read() throws Exception {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = bb.getInt(); m.arg0 = bb.getInt(); m.arg1 = bb.getInt();
        int len = bb.getInt();
        if (len < 0 || len > (4 << 20)) throw new Exception("paket ADB rusak");
        m.data = new byte[len];
        if (len > 0) in.readFully(m.data);
        return m;
    }

    // ------------------------------------------------------------------ kunci RSA

    private static KeyPair key(Context c) throws Exception {
        File f = new File(c.getFilesDir(), "adbkey.pk8");
        if (f.exists()) {
            byte[] b = new byte[(int) f.length()];
            try (FileInputStream fi = new FileInputStream(f)) { new DataInputStream(fi).readFully(b); }
            PrivateKey pk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(b));
            RSAPrivateCrtKey crt = (RSAPrivateCrtKey) pk;
            PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            return new KeyPair(pub, pk);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(kp.getPrivate().getEncoded()); }
        return kp;
    }

    private static byte[] le(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] o = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) o[i] = be[be.length - 1 - i];
        return o;
    }

    private static byte[] androidPubKey(KeyPair kp) {
        RSAPrivateCrtKey k = (RSAPrivateCrtKey) kp.getPrivate();
        BigInteger n = k.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        long n0inv = r32.subtract(n.mod(r32).modInverse(r32)).longValue();
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(64).putInt((int) n0inv).put(le(n, 256)).put(le(rr, 256)).putInt(k.getPublicExponent().intValue());
        byte[] pub = Base64.encode(bb.array(), Base64.NO_WRAP);
        byte[] tail = " remotetv@hp\0".getBytes(StandardCharsets.US_ASCII);
        byte[] o = new byte[pub.length + tail.length];
        System.arraycopy(pub, 0, o, 0, pub.length);
        System.arraycopy(tail, 0, o, pub.length, tail.length);
        return o;
    }

    // ------------------------------------------------------------------ sambung

    /** Sambung + autentikasi. Bila TV belum kenal HP ini, TV menampilkan "Izinkan debugging?" (tunggu s/d 60 dtk). */
    static AdbConn connect(Context ctx, String host, int port, AuthListener l) throws Exception {
        AdbConn c = new AdbConn(host, port);
        KeyPair kp = key(ctx);
        c.sock = new Socket();
        c.sock.setTcpNoDelay(true);
        try {
            c.sock.connect(new InetSocketAddress(host, port), 4000);
        } catch (Exception e) {
            throw new Exception("TV " + host + " tidak menjawab di port " + port
                    + ". Pastikan TV menyala, satu WiFi, dan Debugging ADB aktif.");
        }
        c.sock.setSoTimeout(65000);
        c.in = new DataInputStream(c.sock.getInputStream());
        c.out = c.sock.getOutputStream();
        try {
            c.send(CNXN, 0x01000001, 256 * 1024, "host::\0".getBytes(StandardCharsets.US_ASCII));
            boolean signed = false, sentPub = false;
            while (true) {
                Msg m = c.read();
                if (m.cmd == CNXN) {
                    c.maxData = Math.max(1024, Math.min(m.arg1, 256 * 1024));
                    break;
                }
                if (m.cmd == AUTH && m.arg0 == 1) {
                    if (!signed) {
                        Signature sg = Signature.getInstance("NONEwithRSA");
                        sg.initSign(kp.getPrivate());
                        sg.update(SHA1_PREFIX);
                        sg.update(m.data);
                        c.send(AUTH, 2, 0, sg.sign());
                        signed = true;
                    } else if (!sentPub) {
                        c.send(AUTH, 3, 0, androidPubKey(kp));
                        sentPub = true;
                        if (l != null) l.onNeedConfirm();
                    } else {
                        throw new Exception("Izin debugging ditolak di TV.");
                    }
                } else {
                    throw new Exception("Balasan ADB tak dikenal 0x" + Integer.toHexString(m.cmd));
                }
            }
        } catch (java.net.SocketTimeoutException te) {
            c.closeQuietly();
            throw new Exception("Tidak ada jawaban izin dari TV. Pilih \"Izinkan\" di layar TV lalu coba lagi.");
        } catch (Exception e) {
            c.closeQuietly();
            throw e;
        }
        c.sock.setSoTimeout(0);
        c.alive = true;
        Thread t = new Thread(c::readerLoop, "adb-reader");
        t.setDaemon(true);
        t.start();
        return c;
    }

    private void readerLoop() {
        try {
            while (alive) {
                Msg m = read();
                Stream s = streams.get(m.arg1);
                if (m.cmd == OKAY) {
                    if (s != null) {
                        if (s.remoteId == 0) { s.remoteId = m.arg0; s.opened.countDown(); }
                        else s.okay.release();
                    }
                } else if (m.cmd == WRTE) {
                    if (s != null) {
                        s.inbox.add(m.data);
                        send(OKAY, m.arg1, m.arg0, null);
                    } else {
                        send(CLSE, m.arg1, m.arg0, null);
                    }
                } else if (m.cmd == CLSE) {
                    if (s != null) s.markClosed();
                }
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly();
        }
    }

    void closeQuietly() {
        alive = false;
        try { if (sock != null) sock.close(); } catch (Exception ignored) { }
        for (Stream s : new ArrayList<>(streams.values())) s.markClosed();
        streams.clear();
    }

    // ------------------------------------------------------------------ stream

    final class Stream {
        final int localId = nextId.getAndIncrement();
        volatile int remoteId;
        volatile boolean closed;
        final CountDownLatch opened = new CountDownLatch(1);
        final Semaphore okay = new Semaphore(0);
        final LinkedBlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>();
        private byte[] cur;
        private int curPos;

        void markClosed() {
            if (closed) return;
            closed = true;
            streams.remove(localId);
            inbox.add(EOF);
            opened.countDown();
            okay.release(1000);
        }

        void write(byte[] b, int off, int len) throws Exception {
            while (len > 0) {
                if (closed) throw new Exception("Sambungan ke TV terputus.");
                int n = Math.min(len, maxData);
                byte[] chunk = new byte[n];
                System.arraycopy(b, off, chunk, 0, n);
                send(WRTE, localId, remoteId, chunk);
                if (!okay.tryAcquire(30, TimeUnit.SECONDS)) throw new Exception("TV tidak merespons.");
                if (closed) throw new Exception("Sambungan ke TV terputus.");
                off += n; len -= n;
            }
        }

        void write(byte[] b) throws Exception { write(b, 0, b.length); }

        /** Baca semua keluaran sampai stream ditutup TV. */
        byte[] readAll(long timeoutMs) throws Exception {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            long end = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) { close(); throw new Exception("Waktu habis menunggu TV."); }
                byte[] d = inbox.poll(left, TimeUnit.MILLISECONDS);
                if (d == null) continue;
                if (d == EOF) break;
                bo.write(d);
            }
            return bo.toByteArray();
        }

        /** Baca tepat n byte (dipakai protokol sync). */
        byte[] readExact(int n, long timeoutMs) throws Exception {
            byte[] o = new byte[n];
            int got = 0;
            long end = System.currentTimeMillis() + timeoutMs;
            while (got < n) {
                if (cur == null || curPos >= cur.length) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) throw new Exception("Waktu habis menunggu TV.");
                    byte[] d = inbox.poll(left, TimeUnit.MILLISECONDS);
                    if (d == null) continue;
                    if (d == EOF) throw new Exception("Sambungan ditutup TV.");
                    cur = d; curPos = 0;
                }
                int k = Math.min(n - got, cur.length - curPos);
                System.arraycopy(cur, curPos, o, got, k);
                got += k; curPos += k;
            }
            return o;
        }

        void close() {
            if (!closed) {
                try { send(CLSE, localId, remoteId, null); } catch (Exception ignored) { }
                markClosed();
            }
        }
    }

    Stream open(String service, long timeoutMs) throws Exception {
        if (!alive) throw new Exception("Belum tersambung ke TV.");
        Stream s = new Stream();
        streams.put(s.localId, s);
        send(OPEN, s.localId, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
        if (!s.opened.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            s.close();
            throw new Exception("TV tidak menjawab.");
        }
        if (s.remoteId == 0) throw new Exception("TV menolak perintah: " + service);
        return s;
    }

    // ------------------------------------------------------------------ perintah siap pakai

    String shell(String cmd, long timeoutMs) throws Exception {
        Stream s = open("shell:" + cmd, 10000);
        return new String(s.readAll(timeoutMs), StandardCharsets.UTF_8).trim();
    }

    byte[] exec(String cmd, long timeoutMs) throws Exception {
        Stream s = open("exec:" + cmd, 10000);
        return s.readAll(timeoutMs);
    }

    /** Pasang APK dengan mengalirkan isinya ke "cmd package install -S" (Android 7+). */
    String installStream(InputStream apk, long size, Progress p) throws Exception {
        Stream s = open("exec:cmd package install -r -S " + size, 10000);
        byte[] buf = new byte[64 * 1024];
        long done = 0;
        int n;
        try {
            while (done < size && (n = apk.read(buf, 0, (int) Math.min(buf.length, size - done))) > 0) {
                s.write(buf, 0, n);
                done += n;
                if (p != null) p.onProgress(done, size);
            }
        } catch (Exception e) {
            // TV menutup stream lebih awal (mis. perintah "cmd" tidak ada): ambil pesannya saja
            if (!s.closed) throw e;
        }
        return new String(s.readAll(180000), StandardCharsets.UTF_8).trim();
    }

    /** Kirim file ke TV lewat protokol sync (cadangan untuk TV lama). */
    void push(InputStream src, long size, String remotePath, Progress p) throws Exception {
        Stream s = open("sync:", 10000);
        try {
            byte[] spec = (remotePath + ",33188").getBytes(StandardCharsets.UTF_8);
            s.write(syncHdr("SEND", spec.length));
            s.write(spec);
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = src.read(buf)) > 0) {
                s.write(syncHdr("DATA", n));
                s.write(buf, 0, n);
                done += n;
                if (p != null) p.onProgress(done, size);
            }
            s.write(syncHdr("DONE", (int) (System.currentTimeMillis() / 1000)));
            byte[] r = s.readExact(8, 120000);
            String id = new String(r, 0, 4, StandardCharsets.US_ASCII);
            if (!"OKAY".equals(id)) {
                int len = ByteBuffer.wrap(r, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                String msg = len > 0 && len < 4096
                        ? new String(s.readExact(len, 10000), StandardCharsets.UTF_8) : id;
                throw new Exception("Gagal kirim file: " + msg);
            }
            s.write(syncHdr("QUIT", 0));
        } finally {
            s.close();
        }
    }

    private static byte[] syncHdr(String id, int v) {
        ByteBuffer bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(id.getBytes(StandardCharsets.US_ASCII)).putInt(v);
        return bb.array();
    }
}
