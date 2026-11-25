package com.agi.layarcctv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

/**
 * Berjalan di proses terpisah (":restart"): mematikan proses utama lalu menyalakan aplikasi lagi.
 * Karena activity ini sedang tampil, Android mengizinkan ia membuka MainActivity kembali.
 */
public class RestartActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final int pid = getIntent().getIntExtra("pid", 0);
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (pid > 0 && pid != Process.myPid()) {
                    try { Process.killProcess(pid); } catch (Throwable ignored) {}
                }
            }
        }, 800);
        h.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    Intent i = new Intent(RestartActivity.this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(i);
                } catch (Throwable t) {
                    try { StatsLog.append(getApplicationContext(), "[Restart] startActivity gagal: " + t); } catch (Throwable ignored) {}
                }
            }
        }, 2500);
        // Cadangan: bila aplikasi belum tampil setelah beberapa detik, buka lewat ADB diri sendiri (beberapa kali).
        final android.content.Context ctx = getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                for (int n = 0; n < 4; n++) {
                    try { Thread.sleep(n == 0 ? 8000 : 15000); } catch (InterruptedException e) { break; }
                    try {
                        String out = AdbSelf.run(ctx, "am start -n com.agi.layarcctv/com.agi.layarcctv.MainActivity");
                        StatsLog.append(ctx, "[Restart] cadangan ADB am start ke-" + (n + 1) + ": " + (out == null ? "" : out.trim()));
                    } catch (Throwable e) {
                        try { StatsLog.append(ctx, "[Restart] cadangan ADB gagal: " + e); } catch (Throwable ignored) {}
                    }
                }
                finish();
            }
        }, "restart-backup");
        t.start();
    }
}
