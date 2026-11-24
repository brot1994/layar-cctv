package com.agi.layarcctv;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Log kinerja sederhana: satu file teks, otomatis dipangkas, bisa dibaca lewat /log. */
public final class StatsLog {
    private static final long MAX = 3L * 1024 * 1024;   // pangkas bila lebih dari 3 MB
    private static final long KEEP = 1L * 1024 * 1024;  // sisakan 1 MB terakhir
    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private StatsLog() {}

    static volatile Context appCtx;
    static void log(String line) { if (appCtx != null) append(appCtx, line); }

    static File file(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        return new File(dir, "stats.log");
    }

    static synchronized void append(Context c, String line) {
        try {
            File f = file(c);
            if (f.length() > MAX) trim(f);
            byte[] row = (FMT.format(new Date()) + " | " + line + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream o = new FileOutputStream(f, true)) { o.write(row); }
            if (important(line)) {
                File e = eventsFile(c);
                if (e.length() > 300 * 1024) trimTo(e, 150 * 1024);
                try (FileOutputStream o = new FileOutputStream(e, true)) { o.write(row); }
            }
        } catch (Exception ignored) {}
    }

    /** Kejadian penting (mulai/restart/pengawas/Telegram/ADB), bukan statistik 10 detik. */
    private static boolean important(String line) {
        if (line.startsWith(" ")) return false;
        if (line.contains("RAM kosong") || line.contains("[Jaga] cek:") || line.contains("foto layar: putaran")) return false;
        return true;
    }

    static File eventsFile(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        return new File(dir, "events.log");
    }

    private static void trimTo(File f, int keepBytes) {
        try {
            byte[] keep = new byte[keepBytes];
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                r.seek(f.length() - keepBytes);
                r.readFully(keep);
            }
            try (FileOutputStream o = new FileOutputStream(f, false)) { o.write(keep); }
        } catch (Exception ignored) {}
    }

    /** Riwayat kejadian penting (tahan berhari-hari). */
    static synchronized String events(Context c, int maxBytes) {
        try {
            File f = eventsFile(c);
            if (!f.exists()) return "(belum ada kejadian tercatat)\n";
            long len = f.length();
            int n = (int) Math.min(len, maxBytes);
            byte[] b = new byte[n];
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) { r.seek(len - n); r.readFully(b); }
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "gagal membaca: " + e + "\n";
        }
    }

    private static void trim(File f) {
        try {
            byte[] keep = new byte[(int) KEEP];
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                r.seek(f.length() - KEEP);
                r.readFully(keep);
            }
            try (FileOutputStream o = new FileOutputStream(f, false)) {
                o.write(keep);
            }
        } catch (Exception ignored) {}
    }

    static synchronized String tail(Context c, int maxBytes) {
        try {
            File f = file(c);
            if (!f.exists()) return "(log masih kosong)\n";
            long len = f.length();
            int n = (int) Math.min(len, maxBytes);
            byte[] b = new byte[n];
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                r.seek(len - n);
                r.readFully(b);
            }
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "gagal membaca log: " + e + "\n";
        }
    }

    static synchronized void clear(Context c) {
        try { file(c).delete(); eventsFile(c).delete(); } catch (Exception ignored) {}
    }
}
