package com.agi.layarcctv;

import android.net.Uri;
import android.os.Handler;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

/** Satu pemutar RTSP untuk satu kotak, dengan auto-reconnect kalau stream putus. */
public class VideoCell {
    private final LibVLC lib;
    private final VLCVideoLayout view;
    private final String[] chain;       // urutan stream: tajam -> sedang -> ringan
    private final String key;
    private final boolean limit;        // batas 1080p aktif
    private int level = 0;
    private long startedAt = 0;
    private int resTries = 0;
    private final int caching;
    private static final java.util.Map<String, Integer> LEARNED = new java.util.HashMap<>();
    private static final String[] LEVEL_NAME = {"main", "mid", "sub"};
    private final Runnable resCheck = this::checkRes;

    /** Semua stop/release libVLC dilakukan di thread ini, supaya thread UI tidak pernah menunggu jaringan. */
    private static final ExecutorService BG = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vlc-bg");
        t.setDaemon(true);
        return t;
    });
    static void bg(Runnable r) { try { BG.execute(r); } catch (Throwable ignored) {} }

    private static final java.util.Map<String, Long> LAST_PROBE = new java.util.HashMap<>();
    private int failStreak = 0;
    private long downSince = 0;
    private long createdAt = 0;
    private final Handler ui;
    private MediaPlayer mp;
    private boolean released = false;

    // ---- statistik untuk log ----
    public String name = "";
    public String display = "fit";        // fit | crop | stretch
    public volatile int stalls = 0;       // berapa kali buffering di tengah pemutaran
    public volatile int retries = 0;      // berapa kali stream putus lalu disambung ulang
    private volatile boolean playing = false;
    public Camera cam;      // untuk foto langsung dari kamera
    public Config pcfg;
    public volatile boolean baseSw = false;   // dekoder lunak untuk semua kotak (pengaturan)
    private volatile boolean forceSw = false;  // sementara lunak, untuk foto layar
    private volatile long lastSwap = 0;
    private float lastBuf = 100f;
    private long pDisp, pLost, pDec;      // nilai sebelumnya, untuk selisih
    private int pStalls, pRetries;

    public VideoCell(LibVLC lib, VLCVideoLayout view, String[] chain, String key, boolean limit,
                     int caching, Handler ui) {
        this.lib = lib;
        this.view = view;
        this.chain = chain;
        this.key = key;
        this.limit = limit && chain.length > 1;
        this.caching = caching;
        this.ui = ui;
        Integer l = LEARNED.get(key);
        if (this.limit && l != null) this.level = Math.min(l, chain.length - 1);
    }

    public void start() {
        createdAt = System.currentTimeMillis();
        mp = new MediaPlayer(lib);
        mp.attachViews(view, null, false, false);
        mp.setEventListener(event -> {
            if (event.type == MediaPlayer.Event.EncounteredError
                    || event.type == MediaPlayer.Event.EndReached) {
                playing = false;
                retries++;
                failStreak++;
                if (downSince == 0) downSince = System.currentTimeMillis();
                probe();
                // stream tingkat lebih rendah (mis. stream ke-3) mungkin tidak ada: cepat turun lagi
                if (limit && level > 0 && level < chain.length - 1
                        && System.currentTimeMillis() - startedAt < 10000) {
                    level++;
                    LEARNED.put(key, level);
                    StatsLog.log("   " + name + ": stream tidak tersedia, turun ke " + levelName());
                }
                scheduleRetry();
            } else if (event.type == MediaPlayer.Event.Vout) {
                applyScale();
            } else if (event.type == MediaPlayer.Event.Playing) {
                playing = true;
                failStreak = 0;
                downSince = 0;
                applyScale();
                if (limit) { resTries = 0; ui.removeCallbacks(resCheck); ui.postDelayed(resCheck, 3000); }
            } else if (event.type == MediaPlayer.Event.Buffering) {
                float b = event.getBuffering();
                if (playing && b < 100f && lastBuf >= 100f) stalls++;
                lastBuf = b;
            }
        });
        play();
    }

    private void play() {
        if (released) return;
        try {
            startedAt = System.currentTimeMillis();
            Media media = new Media(lib, Uri.parse(chain[Math.min(level, chain.length - 1)]));
            media.addOption(":network-caching=" + caching);
            media.addOption(":rtsp-tcp");
            media.addOption(":no-audio");
            media.addOption((baseSw || forceSw) ? ":avcodec-hw=none" : ":avcodec-hw=any");
            mp.setMedia(media);
            media.release();
            mp.play();
        } catch (Exception e) {
            scheduleRetry();
        }
    }

    /** Atur cara gambar mengisi kotak. Lewat refleksi agar aman bila nama enum beda di versi libVLC. */
    private void applyScale() {
        if (mp == null || released || "fit".equals(display)) return;
        try {
            Class<?> st = Class.forName("org.videolan.libvlc.MediaPlayer$ScaleType");
            String want = "stretch".equals(display) ? "SURFACE_FILL" : "SURFACE_FIT_SCREEN";
            Object val = null;
            for (Object k : st.getEnumConstants()) if (k.toString().equals(want)) val = k;
            if (val != null) mp.getClass().getMethod("setVideoScale", st).invoke(mp, val);
        } catch (Throwable t) {
            StatsLog.log("   " + name + ": gagal mengatur tampilan (" + t + ")");
        }
    }

    private String levelName() {
        int n = chain.length;
        // chain selalu berakhir di "sub"; tingkat awal bisa main atau mid
        int idx = LEVEL_NAME.length - n + Math.min(level, n - 1);
        return LEVEL_NAME[Math.max(0, Math.min(idx, 2))];
    }

    /** Bila resolusi melebihi 1080p, turun ke stream berikutnya dan ingat pilihannya. */
    private void checkRes() {
        if (released || !playing || mp == null) return;
        long w = -1, h = -1;
        try {
            Object tr = call(mp, "getCurrentVideoTrack");
            if (tr != null) { w = lf(tr, "width"); h = lf(tr, "height"); }
        } catch (Throwable ignored) {}
        if (w <= 0 || h <= 0) {
            if (++resTries < 5) ui.postDelayed(resCheck, 2000);
            return;
        }
        if ((w > 1920 || h > 1088) && level < chain.length - 1) {
            String from = levelName();
            level++;
            LEARNED.put(key, level);
            StatsLog.log("   " + name + ": resolusi " + w + "x" + h + " > 1080p, turun dari " + from + " ke " + levelName());
            restartStream();
        }
    }

    /** stop() di thread latar, lalu play() lagi di thread UI. */
    private void restartStream() {
        final MediaPlayer m = mp;
        if (m == null || released) return;
        bg(() -> {
            try { m.stop(); } catch (Throwable ignored) {}
            ui.post(() -> { if (!released) play(); });
        });
    }

    private void scheduleRetry() {
        if (released) return;
        // jeda makin lama bila terus gagal: 4s, 8s, 16s, lalu 30s
        long delay = Math.min(30000L, 4000L << Math.min(3, Math.max(0, failStreak - 1)));
        ui.postDelayed(() -> { if (!released) restartStream(); }, delay);
    }

    public VLCVideoLayout layout() { return view; }

    public boolean isPlayingNow() { return playing; }

    /** Jumlah gambar yang sudah ditampilkan pada media yang sedang diputar (-1 bila tidak diketahui). */
    public long displayed() {
        try {
            if (mp == null) return -1;
            Object media = call(mp, "getMedia");
            Object st = media == null ? null : call(media, "getStats");
            long d = st == null ? -1 : lf(st, "displayedPictures");
            if (media != null) { try { media.getClass().getMethod("release").invoke(media); } catch (Throwable ignored) {} }
            return d;
        } catch (Throwable t) { return -1; }
    }

    /** True bila stream sudah diputar ulang setelah penggantian dekoder DAN sudah menampilkan beberapa gambar. */
    public boolean readyAfterSwap() {
        return !released && startedAt >= lastSwap && displayed() >= 12;
    }

    /** Ganti sementara ke dekoder lunak (true) atau kembali normal (false) lalu putar ulang. Panggil di thread UI. */
    public void setForceSw(boolean sw) {
        if (released || forceSw == sw) return;
        forceSw = sw;
        lastSwap = System.currentTimeMillis();
        playing = false;
        restartStream();
    }

    /** Sudah berapa ms kotak ini tidak memutar video (0 bila sedang main). */
    public long downForMs() {
        if (playing) return 0;
        long since = downSince > 0 ? downSince : Math.max(createdAt, lastSwap);
        return since == 0 ? 0 : System.currentTimeMillis() - since;
    }

    public String host() {
        try { return Uri.parse(chain[0]).getHost(); } catch (Throwable t) { return ""; }
    }

    /** Saat gagal: uji sambungan TCP ke kamera/DVR dan catat hasilnya di log (maks. sekali per 30 detik per alamat). */
    private void probe() {
        final String host;
        final int port;
        try {
            Uri u = Uri.parse(chain[Math.min(level, chain.length - 1)]);
            host = u.getHost();
            port = u.getPort() > 0 ? u.getPort() : 554;
        } catch (Throwable t) { return; }
        if (host == null) return;
        final String k = host + ":" + port;
        synchronized (LAST_PROBE) {
            Long last = LAST_PROBE.get(k);
            long now = System.currentTimeMillis();
            if (last != null && now - last < 30000) return;
            LAST_PROBE.put(k, now);
        }
        final String who = name;
        Thread th = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            Socket s = new Socket();
            String res;
            try {
                s.connect(new InetSocketAddress(host, port), 3000);
                res = "terhubung (" + (System.currentTimeMillis() - t0) + " ms)";
            } catch (java.net.SocketTimeoutException e) {
                res = "TIMEOUT 3 detik (tidak menjawab)";
            } catch (java.net.ConnectException e) {
                res = "DITOLAK / tidak terjangkau: " + e.getMessage();
            } catch (Throwable e) {
                res = "gagal: " + e;
            } finally {
                try { s.close(); } catch (Throwable ignored) {}
            }
            StatsLog.log("   [cek sambungan] " + k + " untuk " + who + " -> " + res);
        }, "probe");
        th.setDaemon(true);
        th.start();
    }

    // ---- refleksi: aman bila versi libVLC beda; kalau gagal -> "?" ----
    private static Object call(Object o, String m) {
        try { return o.getClass().getMethod(m).invoke(o); } catch (Throwable t) { return null; }
    }
    private static long lf(Object o, String f) {
        try {
            java.lang.reflect.Field fl = o.getClass().getField(f);
            Object x = fl.get(o);
            if (x instanceof Number) return ((Number) x).longValue();
        } catch (Throwable ignored) {}
        return -1;
    }
    private static float ff(Object o, String f) {
        try {
            Object x = o.getClass().getField(f).get(o);
            if (x instanceof Number) return ((Number) x).floatValue();
        } catch (Throwable ignored) {}
        return -1f;
    }
    private static String sf(Object o, String f) {
        try {
            Object x = o.getClass().getField(f).get(o);
            if (x != null) return x.toString();
        } catch (Throwable ignored) {}
        return "?";
    }

    /** Satu baris ringkas: resolusi, codec, fps, bitrate, frame ditampilkan/dibuang, macet, sambung ulang. */
    public String snapshot() {
        if (released || mp == null) return name + " | (sudah dilepas)";
        StringBuilder s = new StringBuilder(name).append(" | ");
        try {
            Object tr = call(mp, "getCurrentVideoTrack");
            if (tr != null) {
                long w = lf(tr, "width"), h = lf(tr, "height");
                long fn = lf(tr, "frameRateNum"), fd = lf(tr, "frameRateDen");
                s.append(w).append("x").append(h).append(" ").append(sf(tr, "codec"));
                if (fn > 0 && fd > 0) s.append(" ").append(String.format(Locale.US, "%.0ffps", fn / (double) fd));
            } else {
                s.append("belum ada video");
            }
        } catch (Throwable t) { s.append("?"); }
        s.append(" | ");
        try {
            Object media = call(mp, "getMedia");
            Object st = media == null ? null : call(media, "getStats");
            if (st != null) {
                long disp = lf(st, "displayedPictures"), lost = lf(st, "lostPictures"), dec = lf(st, "decodedVideo");
                float inb = ff(st, "inputBitrate"), dem = ff(st, "demuxBitrate");
                s.append(String.format(Locale.US, "in=%.0fkbps demux=%.0fkbps", inb * 8000f, dem * 8000f));
                s.append(" | tampil+").append(disp - pDisp).append(" buang+").append(lost - pLost)
                 .append(" decode+").append(dec - pDec);
                pDisp = disp; pLost = lost; pDec = dec;
            } else {
                s.append("stats tidak tersedia");
            }
            if (media != null) { try { media.getClass().getMethod("release").invoke(media); } catch (Throwable ignored) {} }
        } catch (Throwable t) { s.append("stats?"); }
        s.append(" | stream=").append(levelName()).append(" | macet+").append(stalls - pStalls).append(" sambung-ulang+").append(retries - pRetries);
        pStalls = stalls; pRetries = retries;
        s.append(playing ? " | main" : " | TIDAK MAIN");
        return s.toString();
    }

    public void release() {
        released = true;
        ui.removeCallbacks(resCheck);
        final MediaPlayer m = mp;
        mp = null;
        if (m != null) {
            try { m.setEventListener(null); } catch (Throwable ignored) {}
            try { m.detachViews(); } catch (Throwable ignored) {}
            bg(() -> {
                try { m.stop(); } catch (Throwable ignored) {}
                try { m.release(); } catch (Throwable ignored) {}
            });
        }
    }
}
