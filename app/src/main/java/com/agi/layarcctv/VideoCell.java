package com.agi.layarcctv;

import android.net.Uri;
import android.os.Handler;

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
                scheduleRetry();
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
