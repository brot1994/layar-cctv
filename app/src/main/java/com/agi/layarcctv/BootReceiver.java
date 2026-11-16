package com.agi.layarcctv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/** Jalankan aplikasi otomatis setelah TV menyala (dicoba beberapa kali). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {
        String a = intent.getAction();
        if (a == null) return;
        if (!(Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || a.endsWith("QUICKBOOT_POWERON"))) return;
        final Context app = context.getApplicationContext();
        final PendingResult pr = goAsync();
        final Handler h = new Handler(Looper.getMainLooper());
        final int[] n = {0};
        Runnable r = new Runnable() {
            @Override public void run() {
                try {
                    Intent i = new Intent(app, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    app.startActivity(i);
                } catch (Throwable ignored) { }
                if (++n[0] < 3) h.postDelayed(this, 3000);
                else pr.finish();
            }
        };
        h.post(r);
    }
}
