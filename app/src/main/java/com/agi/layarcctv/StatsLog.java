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

    static File file(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        return new File(dir, "stats.log");
    }

    static synchronized void append(Context c, String line) {
        try {
            File f = file(c);
            if (f.length() > MAX) trim(f);
            try (FileOutputStream o = new FileOutputStream(f, true)) {
                o.write((FMT.format(new Date()) + " | " + line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
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
        try { file(c).delete(); } catch (Exception ignored) {}
    }
}
