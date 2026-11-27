package com.agi.layarcctv;

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
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * Klien ADB mini: menyambung ke adbd TV sendiri (127.0.0.1:5555) lalu menjalankan satu perintah shell.
 * Dipakai untuk memberi izin "tampil di atas aplikasi lain" ke diri sendiri dan membuka aplikasi dari latar.
 * Butuh "Debugging jaringan/ADB" aktif di TV (port 5555) dan persetujuan sekali ("Selalu izinkan").
 */
final class AdbSelf {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, WRTE = 0x45545257, CLSE = 0x45534c43;
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private AdbSelf() { }

    private static final class Msg { int cmd, arg0, arg1; byte[] data; }

    private static void send(OutputStream o, int cmd, int a0, int a1, byte[] d) throws Exception {
        if (d == null) d = new byte[0];
        int crc = 0;
        for (byte b : d) crc += (b & 0xff);
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.putInt(cmd).putInt(a0).putInt(a1).putInt(d.length).putInt(crc).putInt(cmd ^ 0xffffffff);
        o.write(bb.array());
        if (d.length > 0) o.write(d);
        o.flush();
    }

    private static Msg read(DataInputStream in) throws Exception {
        byte[] h = new byte[24];
        in.readFully(h);
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = bb.getInt(); m.arg0 = bb.getInt(); m.arg1 = bb.getInt();
        int len = bb.getInt();
        m.data = new byte[Math.max(0, Math.min(len, 1 << 20))];
        if (len > 0) in.readFully(m.data);
        return m;
    }

    private static KeyPair key(Context c) throws Exception {
        File f = new File(c.getFilesDir(), "adbkey.pk8");
        if (f.exists()) {
            byte[] b = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) { new DataInputStream(in).readFully(b); }
            PrivateKey pk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(b));
            // Kunci yang dimuat dari file bukan selalu RSAPrivateCrtKey (ClassCastException di Android TV): pakai RSAPrivateKey + eksponen publik 65537.
            java.security.interfaces.RSAPrivateKey rk = (java.security.interfaces.RSAPrivateKey) pk;
            java.security.PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(
                    new java.security.spec.RSAPublicKeySpec(rk.getModulus(), BigInteger.valueOf(65537)));
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
        byte[] out = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    private static byte[] androidPubKey(KeyPair kp) {
        java.security.interfaces.RSAPrivateKey k = (java.security.interfaces.RSAPrivateKey) kp.getPrivate();
        BigInteger n = k.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        long n0inv = r32.subtract(n.mod(r32).modInverse(r32)).longValue();
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(4 + 4 + 256 + 256 + 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.putInt(64).putInt((int) n0inv).put(le(n, 256)).put(le(rr, 256)).putInt(65537);
        byte[] pub = Base64.encode(bb.array(), Base64.NO_WRAP);
        byte[] tail = " layarcctv@tv\0".getBytes();
        byte[] out = new byte[pub.length + tail.length];
        System.arraycopy(pub, 0, out, 0, pub.length);
        System.arraycopy(tail, 0, out, pub.length, tail.length);
        return out;
    }

    /** Jalankan satu perintah shell di TV ini; kembalikan keluarannya. */
    static synchronized String run(Context ctx, String cmd) throws Exception {
        KeyPair kp = key(ctx);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 4000);
            s.setSoTimeout(120000);
            OutputStream o = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());
            send(o, CNXN, 0x01000000, 4096, "host::\0".getBytes());
            boolean signed = false;
            while (true) {
                Msg m = read(in);
                if (m.cmd == CNXN) break;
                if (m.cmd == AUTH && m.arg0 == 1) {
                    if (!signed) {
                        Signature sg = Signature.getInstance("NONEwithRSA");
                        sg.initSign(kp.getPrivate());
                        sg.update(SHA1_PREFIX);
                        sg.update(m.data);
                        send(o, AUTH, 2, 0, sg.sign());
                        signed = true;
                    } else {
                        send(o, AUTH, 3, 0, androidPubKey(kp));
                    }
                } else {
                    throw new Exception("balasan ADB tak dikenal 0x" + Integer.toHexString(m.cmd));
                }
            }
            send(o, OPEN, 1, 0, ("shell:" + cmd + "\0").getBytes());
            ByteArrayOutputStream res = new ByteArrayOutputStream();
            while (true) {
                Msg m = read(in);
                if (m.cmd == WRTE) {
                    res.write(m.data);
                    send(o, OKAY, 1, m.arg0, null);
                } else if (m.cmd == CLSE) {
                    break;
                }
            }
            return res.toString("UTF-8").trim();
        }
    }
}
