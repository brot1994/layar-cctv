package com.agi.layarcctv;

import android.net.Uri;
import android.os.Handler;

import java.util.Locale;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

/** Satu pemutar RTSP untuk satu kotak, dengan auto-reconnect kalau stream putus. */
public class VideoCell {
    private final LibVLC lib;
    private final VLCVideoLayout view;
    private final String url;
    private final int caching;
    private final Handler ui;
    private MediaPlayer mp;
    private boolean released = false;

    // ---- statistik untuk log ----
    public String name = "";
    public volatile int stalls = 0;       // berapa kali buffering di tengah pemutaran
    public volatile int retries = 0;      // berapa kali stream putus lalu disambung ulang
    private boolean playing = false;
    private float lastBuf = 100f;
    private long pDisp, pLost, pDec;      // nilai sebelumnya, untuk selisih
    private int pStalls, pRetries;

    public VideoCell(LibVLC lib, VLCVideoLayout view, String url, int caching, Handler ui) {
        this.lib = lib;
        this.view = view;
        this.url = url;
        this.caching = caching;
        this.ui = ui;
    }

    public void start() {
        mp = new MediaPlayer(lib);
        mp.attachViews(view, null, false, false);
        mp.setEventListener(event -> {
            if (event.type == MediaPlayer.Event.EncounteredError
                    || event.type == MediaPlayer.Event.EndReached) {
                playing = false;
                retries++;
                scheduleRetry();
            } else if (event.type == MediaPlayer.Event.Playing) {
                playing = true;
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
            Media media = new Media(lib, Uri.parse(url));
            media.addOption(":network-caching=" + caching);
            media.addOption(":rtsp-tcp");
            media.addOption(":no-audio");
            media.addOption(":avcodec-hw=any");
            mp.setMedia(media);
            media.release();
            mp.play();
        } catch (Exception e) {
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        if (released) return;
        ui.postDelayed(() -> {
            if (released) return;
            try { mp.stop(); } catch (Exception ignored) {}
            play();
        }, 4000);
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
        s.append(" | macet+").append(stalls - pStalls).append(" sambung-ulang+").append(retries - pRetries);
        pStalls = stalls; pRetries = retries;
        s.append(playing ? " | main" : " | TIDAK MAIN");
        return s.toString();
    }

    public void release() {
        released = true;
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) {}
            try { mp.detachViews(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
            mp = null;
        }
    }
}
