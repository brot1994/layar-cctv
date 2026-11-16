package com.agi.layarcctv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/** Cek berkala: kalau aplikasi tidak tampil di depan (mis. TV di Home), buka lagi. */
public class KeepAliveService extends Service {
    private static final String CH = "keepalive";
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            int min = 3;
            try {
                Config c = ConfigStore.load(KeepAliveService.this);
                min = Math.max(1, c.keepMin);
                boolean inter = interactive();
                boolean ovl = overlayOk();
                StatsLog.append(KeepAliveService.this, "[Jaga] cek: aktif=" + c.keepOpen
                        + " tampil=" + MainActivity.visible + " layarNyala=" + inter
                        + " izinTampilDiAtas=" + ovl + " interval=" + min + "mnt");
                if (c.keepOpen && !MainActivity.visible && inter) {
                    Intent i = new Intent(KeepAliveService.this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    StatsLog.append(KeepAliveService.this, "[Jaga] aplikasi dibuka otomatis");
                }
            } catch (Throwable t) {
                StatsLog.append(KeepAliveService.this, "[Jaga] gagal: " + t);
            }
            h.postDelayed(this, min * 60000L);
        }
    };

    static void start(Context c) {
        try {
            Intent i = new Intent(c, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable ignored) { }
    }

    private boolean overlayOk() {
        try { return Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this); }
        catch (Throwable t) { return false; }
    }

    private boolean interactive() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                nm.createNotificationChannel(new NotificationChannel(CH, "Layar CCTV", NotificationManager.IMPORTANCE_MIN));
                b = new Notification.Builder(this, CH);
            } else {
                b = new Notification.Builder(this);
            }
            Notification n = b.setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Layar CCTV aktif").build();
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(1, n);
        } catch (Throwable ignored) { }
        h.removeCallbacks(tick);
        int first = 3;
        try { first = Math.max(1, ConfigStore.load(this).keepMin); } catch (Throwable ignored) { }
        StatsLog.append(this, "[Jaga] layanan jalan, cek pertama " + first + " menit lagi");
        h.postDelayed(tick, first * 60000L);
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        h.removeCallbacks(tick);
        super.onDestroy();
    }
}
