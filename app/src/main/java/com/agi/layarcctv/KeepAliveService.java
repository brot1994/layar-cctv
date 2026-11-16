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
                if (c.keepOpen && !MainActivity.visible && interactive()) {
                    Intent i = new Intent(KeepAliveService.this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                }
            } catch (Throwable ignored) { }
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
        h.postDelayed(tick, 3 * 60000L);
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        h.removeCallbacks(tick);
        super.onDestroy();
    }
}
