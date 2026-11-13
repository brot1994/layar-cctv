package com.agi.layarcctv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.drawable.GradientDrawable;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class MainActivity extends Activity {

    private LibVLC libVLC;
    private FrameLayout root;
    private WebServer webServer;
    private final List<VideoCell> cells = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final int[] WEB_PORTS = {8080, 8081, 8090, 8888, 9090};
    private int webPort = 0; // port yang berhasil dipakai (0 = gagal)
    private int gap = 4;     // jarak antar kotak (px)

    // ---- rotasi ----
    private Config cfg;
    private List<Page> pages = new ArrayList<>();
    private volatile int cur = -1;
    private volatile boolean paused = false;
    private boolean screenShowing = false;
    private boolean hintDone = false;
    private volatile boolean stopped = false; // true saat aplikasi di latar belakang (Home/screensaver/TV tidur)
    private final Runnable rotateTask = this::next;

    // ---- layar penuh satu kamera ----
    private static final long FULL_MS = 10 * 60 * 1000L; // kembali otomatis setelah 10 menit
    private boolean fullActive = false;
    private Config cctvCfg;                       // config halaman CCTV yang sedang tampil
    private final List<View> cellBorders = new ArrayList<>();
    private int selIdx = -1;                      // kotak yang disorot (navigasi remote)
    private final Runnable exitFullTask = this::exitFull;
    private final Runnable clearSelTask = () -> setSelection(-1);

    // ---- restart otomatis ----
    private final Runnable restartTask = this::doRestart;

    private void scheduleRestart() {
        ui.removeCallbacks(restartTask);
        if (cfg != null && cfg.restartHours > 0) {
            ui.postDelayed(restartTask, cfg.restartHours * 3600000L);
        }
    }

    /** Matikan proses ini lalu nyalakan lagi (lewat proses pembantu). Boleh dipanggil dari thread mana pun. */
    private void relaunchProcess(String why) {
        StatsLog.log("=== RESTART: " + why + " ===");
        try {
            Intent i = new Intent(getApplicationContext(), RestartActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra("pid", android.os.Process.myPid());
            getApplicationContext().startActivity(i);
        } catch (Throwable t) {
            StatsLog.log("restart gagal: " + t);
        }
    }

    // ---- watchdog: bila thread UI macet, restart sendiri ----
    private volatile long beat = System.currentTimeMillis();
    private final Runnable beatTask = new Runnable() {
        @Override public void run() {
            beat = System.currentTimeMillis();
            ui.postDelayed(this, 5000);
        }
    };

    private void startWatchdog() {
        beat = System.currentTimeMillis();
        ui.post(beatTask);
        Thread t = new Thread(() -> {
            while (true) {
                try { Thread.sleep(20000); } catch (InterruptedException e) { return; }
                if (!stopped && System.currentTimeMillis() - beat > 90000) {
                    relaunchProcess("WATCHDOG: aplikasi tidak merespons > 90 detik");
                    beat = System.currentTimeMillis(); // beri waktu sebelum dicek lagi
                }
            }
        }, "watchdog");
        t.setDaemon(true);
        t.start();
    }

    // ---- pemulih: beberapa kamera satu alamat mati lama -> mulai ulang libVLC ----
    private long lastRecover = 0;
    private int recoverCount = 0;
    private long recoverWindowStart = 0;

    private void checkRecover() {
        if (stopped || cells.size() < 2 || fullActive) return;
        long now = System.currentTimeMillis();
        java.util.Map<String, Integer> downByHost = new java.util.HashMap<>();
        for (VideoCell c : cells) {
            if (c.downForMs() >= 120000) {
                String h = c.host();
                downByHost.put(h, downByHost.containsKey(h) ? downByHost.get(h) + 1 : 1);
            }
        }
        boolean bad = false;
        for (int n : downByHost.values()) if (n >= 2) bad = true;
        if (!bad || now - lastRecover < 240000) return;

        if (now - recoverWindowStart > 20 * 60000L) { recoverWindowStart = now; recoverCount = 0; }
        lastRecover = now;
        recoverCount++;
        if (recoverCount >= 3) {
            android.content.SharedPreferences sp = getSharedPreferences("recover", MODE_PRIVATE);
            long lastProc = sp.getLong("lastProc", 0);
            if (now - lastProc > 30 * 60000L) {
                sp.edit().putLong("lastProc", now).apply();
                relaunchProcess("PEMULIH: kamera tetap mati setelah 3x pemulihan");
                return;
            }
        }
        StatsLog.log("=== PEMULIH: beberapa kamera mati > 2 menit, libVLC dimulai ulang (ke-" + recoverCount + ") ===");
        try {
            final LibVLC old = libVLC;
            clearScreen();
            libVLC = newLibVLC();
            VideoCell.bg(() -> { try { old.release(); } catch (Throwable ignored) {} });
            if (cur >= 0 && cur < pages.size()) showPage(pages.get(cur));
        } catch (Throwable t) {
            StatsLog.log("pemulih gagal: " + t);
        }
    }

    private void doRestart() {
        if (stopped) { // aplikasi di latar belakang: tunda, jangan memaksa tampil
            ui.postDelayed(restartTask, 10 * 60000L);
            return;
        }
        relaunchProcess("RESTART OTOMATIS (tiap " + (cfg == null ? 0 : cfg.restartHours) + " jam)");
    }

    // ---- foto layar ----
    /** Dipanggil dari thread server. Mengambil seluruh jendela (termasuk video) lewat PixelCopy. */
    private final Object shotLock = new Object();

    private byte[] takeShot() {
        synchronized (shotLock) { return takeShotInner(); }
    }

    private byte[] takeShotInner() {
        if (stopped || android.os.Build.VERSION.SDK_INT < 26) return null;
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final int[] result = { -1 };
        final android.graphics.Bitmap[] bmp = { null };
        ui.post(() -> {
            try {
                View d = getWindow().getDecorView();
                int w = d.getWidth(), h = d.getHeight();
                if (w <= 0 || h <= 0) { latch.countDown(); return; }
                bmp[0] = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                android.view.PixelCopy.request(getWindow(), bmp[0], code -> { result[0] = code; latch.countDown(); }, ui);
            } catch (Throwable t) {
                StatsLog.log("foto layar gagal: " + t);
                latch.countDown();
            }
        });
        try {
            if (!latch.await(8, java.util.concurrent.TimeUnit.SECONDS)) return null;
        } catch (InterruptedException e) { return null; }
        if (result[0] != android.view.PixelCopy.SUCCESS || bmp[0] == null) {
            StatsLog.log("foto layar: PixelCopy kode " + result[0]);
            return null;
        }
        // Video (SurfaceView) pada dekoder keras sering hitam. Salin tiap kotak sendiri; yang hitam
        // diganti sementara ke dekoder lunak (2 kotak per putaran), disalin, lalu dikembalikan.
        final List<VideoCell> all = onUi(() -> new ArrayList<>(cells));
        List<VideoCell> bad = copyAll(all, 1, bmp[0]);
        StatsLog.log("foto layar: putaran 1 selesai, " + (all.size() - bad.size()) + " kotak langsung tampil, " + bad.size() + " hitam");
        if (!bad.isEmpty()) {
            // Kotak yang hitam: ambil foto langsung dari kamera/DVR (ISAPI) dan tempel di kotaknya.
            final List<VideoCell> toSnap = new ArrayList<>(bad);
            final android.graphics.Bitmap[] snaps = new android.graphics.Bitmap[toSnap.size()];
            Thread[] ths = new Thread[toSnap.size()];
            for (int i = 0; i < toSnap.size(); i++) {
                final int k = i;
                final VideoCell c = toSnap.get(i);
                ths[i] = new Thread(() -> {
                    if (c.cam != null && c.pcfg != null) snaps[k] = Snap.fetch(c.cam, c.pcfg);
                }, "snap" + i);
                ths[i].setDaemon(true);
                ths[i].start();
            }
            for (Thread t : ths) { try { t.join(12000); } catch (InterruptedException ignored) {} }
            final int[][] rects = onUi(() -> {
                int[][] r = new int[toSnap.size()][];
                for (int i = 0; i < toSnap.size(); i++) {
                    java.util.List<android.view.SurfaceView> one = new ArrayList<>();
                    collectSurfaces(toSnap.get(i).layout(), one);
                    if (one.isEmpty()) continue;
                    android.view.SurfaceView sv = one.get(0);
                    int[] loc = new int[2];
                    sv.getLocationInWindow(loc);
                    r[i] = new int[] { loc[0], loc[1], sv.getWidth(), sv.getHeight() };
                }
                return r;
            });
            int ok = 0;
            if (rects != null) {
                android.graphics.Canvas cv = new android.graphics.Canvas(bmp[0]);
                android.graphics.Paint black = new android.graphics.Paint();
                black.setColor(Color.BLACK);
                android.graphics.Paint bp = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
                for (int i = 0; i < toSnap.size(); i++) {
                    android.graphics.Bitmap sb = snaps[i];
                    int[] r = rects[i];
                    if (sb == null || r == null || r[2] <= 0 || r[3] <= 0) continue;
                    cv.drawRect(r[0], r[1], r[0] + r[2], r[1] + r[3], black);
                    String mode = toSnap.get(i).cam != null ? toSnap.get(i).cam.display : "fit";
                    android.graphics.Rect src = new android.graphics.Rect(0, 0, sb.getWidth(), sb.getHeight());
                    android.graphics.Rect dst = new android.graphics.Rect(r[0], r[1], r[0] + r[2], r[1] + r[3]);
                    float sa = sb.getWidth() / (float) sb.getHeight(), da = r[2] / (float) r[3];
                    if ("crop".equals(mode)) {
                        if (sa > da) { int w = Math.round(sb.getHeight() * da); src.left = (sb.getWidth() - w) / 2; src.right = src.left + w; }
                        else { int h = Math.round(sb.getWidth() / da); src.top = (sb.getHeight() - h) / 2; src.bottom = src.top + h; }
                    } else if (!"stretch".equals(mode)) {
                        if (sa > da) { int h = Math.round(r[2] / sa); dst.top = r[1] + (r[3] - h) / 2; dst.bottom = dst.top + h; }
                        else { int w = Math.round(r[3] * sa); dst.left = r[0] + (r[2] - w) / 2; dst.right = dst.left + w; }
                    }
                    cv.drawBitmap(sb, src, dst, bp);
                    ok++;
                }
            }
            for (android.graphics.Bitmap sb : snaps) if (sb != null) sb.recycle();
            StatsLog.log("foto layar: foto langsung dari kamera berhasil " + ok + " dari " + toSnap.size() + " kotak hitam");
        }
        try {
            android.graphics.Bitmap b = bmp[0];
            if (b.getWidth() > 1280) {
                int nh = Math.round(b.getHeight() * 1280f / b.getWidth());
                android.graphics.Bitmap sm = android.graphics.Bitmap.createScaledBitmap(b, 1280, nh, true);
                b.recycle();
                b = sm;
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out);
            b.recycle();
            return out.toByteArray();
        } catch (Throwable t) {
            StatsLog.log("foto layar gagal (kompres): " + t);
            return null;
        }
    }

    private interface Done { void done(boolean ok); }

    /** Jalankan di thread UI dan tunggu hasilnya. */
    private <T> T onUi(final java.util.concurrent.Callable<T> job) {
        final Object[] out = { null };
        final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
        ui.post(() -> { try { out[0] = job.call(); } catch (Throwable ignored) {} l.countDown(); });
        try { l.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        @SuppressWarnings("unchecked") T r = (T) out[0];
        return r;
    }

    /** Salin kotak-kotak ke bitmap. Mengembalikan daftar kotak yang gagal/hitam. */
    private List<VideoCell> copyAll(final List<VideoCell> list, final int tries, final android.graphics.Bitmap dst) {
        final List<VideoCell> failed = java.util.Collections.synchronizedList(new ArrayList<VideoCell>());
        if (list == null || list.isEmpty()) return failed;
        final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
        ui.post(() -> {
            try {
                final int[] left = { list.size() };
                for (int i = 0; i < list.size(); i++) {
                    final VideoCell c = list.get(i);
                    java.util.List<android.view.SurfaceView> one = new ArrayList<>();
                    collectSurfaces(c.layout(), one);
                    if (one.isEmpty()) {
                        failed.add(c);
                        if (--left[0] == 0) l.countDown();
                        continue;
                    }
                    copySurface(one.get(0), i, tries, dst, ok -> {
                        if (!ok) failed.add(c);
                        if (--left[0] == 0) l.countDown();
                    });
                }
            } catch (Throwable t) {
                StatsLog.log("foto layar (video) gagal: " + t);
                l.countDown();
            }
        });
        try { l.await(20, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return failed;
    }

    private static boolean notBlack(android.graphics.Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), hit = 0, n = 0;
        for (int y = 2; y < h; y += Math.max(1, h / 20)) {
            for (int x = 2; x < w; x += Math.max(1, w / 20)) {
                int c = b.getPixel(x, y);
                int sum = ((c >> 16) & 0xff) + ((c >> 8) & 0xff) + (c & 0xff);
                n++;
                if (sum > 45) hit++;
            }
        }
        return n > 0 && hit * 100 / n >= 3;
    }

    /** Salin satu SurfaceView ke bitmap utama; ulangi bila hasilnya hitam. Dipanggil di thread UI. */
    private void copySurface(final android.view.SurfaceView sv, final int idx, final int tries,
                             final android.graphics.Bitmap dst, final Done done) {
        final int sw = sv.getWidth(), sh = sv.getHeight();
        if (sw <= 0 || sh <= 0 || !sv.getHolder().getSurface().isValid()) {
            StatsLog.log("foto layar: video#" + idx + " tidak valid (ukuran " + sw + "x" + sh + ")");
            done.done(false);
            return;
        }
        final android.graphics.Bitmap sb = android.graphics.Bitmap.createBitmap(sw, sh, android.graphics.Bitmap.Config.ARGB_8888);
        final int[] loc = new int[2];
        sv.getLocationInWindow(loc);
        try {
            android.view.PixelCopy.request(sv, sb, code -> {
                boolean good = code == android.view.PixelCopy.SUCCESS && notBlack(sb);
                if (good || tries <= 1) {
                    if (code == android.view.PixelCopy.SUCCESS)
                        new android.graphics.Canvas(dst).drawBitmap(sb, loc[0], loc[1], null);
                    StatsLog.log("foto layar: video#" + idx + " @" + loc[0] + "," + loc[1] + " " + sw + "x" + sh
                            + " kode=" + code + (good ? " OK" : " HITAM") + " sisa-coba=" + (tries - 1));
                    sb.recycle();
                    done.done(good);
                } else {
                    sb.recycle();
                    ui.postDelayed(() -> copySurface(sv, idx, tries - 1, dst, done), 600);
                }
            }, ui);
        } catch (Throwable t) {
            StatsLog.log("foto layar: video#" + idx + " error " + t);
            done.done(false);
        }
    }

    private void collectSurfaces(View v, java.util.List<android.view.SurfaceView> out) {
        if (v instanceof android.view.SurfaceView) out.add((android.view.SurfaceView) v);
        else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectSurfaces(g.getChildAt(i), out);
        }
    }

    // ---- kirim foto layar ke Telegram ----
    private final java.util.concurrent.atomic.AtomicBoolean tgBusy = new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Dicek tiap menit; kirim bila sudah lewat interval sejak kiriman terakhir (disimpan, jadi tahan restart). */
    private final Runnable tgTask = new Runnable() {
        @Override public void run() {
            try {
                if (cfg != null && !cfg.tgToken.isEmpty() && !cfg.tgChat.isEmpty() && !stopped) {
                    android.content.SharedPreferences sp = getSharedPreferences("tg", MODE_PRIVATE);
                    long last = sp.getLong("last", 0);
                    if (System.currentTimeMillis() - last >= cfg.tgEveryMin * 60000L) sendTelegramAsync(false);
                }
            } catch (Throwable t) { StatsLog.log("[Telegram] error: " + t); }
            ui.postDelayed(this, 60000L);
        }
    };

    private void sendTelegramAsync(final boolean manual) {
        final Config c = cfg;
        if (c == null || c.tgToken.isEmpty() || c.tgChat.isEmpty()) {
            if (manual) StatsLog.log("[Telegram] token/ID chat belum diisi");
            return;
        }
        if (!tgBusy.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                byte[] jpg = takeShot();
                if (jpg == null) { StatsLog.log("[Telegram] batal: foto layar gagal/aplikasi tidak tampil"); return; }
                String cap = "Layar TV " + new java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(new java.util.Date())
                        + (manual ? " (manual)" : "");
                String err = postTelegram(c.tgToken, c.tgChat, cap, jpg);
                if (err == null) {
                    getSharedPreferences("tg", MODE_PRIVATE).edit().putLong("last", System.currentTimeMillis()).apply();
                    StatsLog.log("[Telegram] foto terkirim (" + (jpg.length / 1024) + " KB)");
                } else {
                    StatsLog.log("[Telegram] GAGAL kirim: " + err);
                }
            } catch (Throwable e) {
                StatsLog.log("[Telegram] error: " + e);
            } finally {
                tgBusy.set(false);
            }
        }, "telegram");
        t.setDaemon(true);
        t.start();
    }

    /** Kirim foto lewat Bot API. Mengembalikan null bila sukses, selain itu pesan kesalahan. */
    private static String postTelegram(String token, String chat, String caption, byte[] jpg) {
        java.net.HttpURLConnection con = null;
        try {
            String boundary = "----agi" + System.currentTimeMillis();
            con = (java.net.HttpURLConnection) new java.net.URL("https://api.telegram.org/bot" + token + "/sendPhoto").openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setDoOutput(true);
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            java.io.OutputStream os = con.getOutputStream();
            String nl = "\r\n";
            String[][] f = { { "chat_id", chat }, { "caption", caption } };
            for (String[] kv : f) {
                os.write(("--" + boundary + nl + "Content-Disposition: form-data; name=\"" + kv[0] + "\"" + nl + nl + kv[1] + nl).getBytes("UTF-8"));
            }
            os.write(("--" + boundary + nl + "Content-Disposition: form-data; name=\"photo\"; filename=\"layar.jpg\"" + nl
                    + "Content-Type: image/jpeg" + nl + nl).getBytes("UTF-8"));
            os.write(jpg);
            os.write((nl + "--" + boundary + "--" + nl).getBytes("UTF-8"));
            os.flush();
            int code = con.getResponseCode();
            if (code == 200) return null;
            java.io.InputStream es = con.getErrorStream();
            String body = "";
            if (es != null) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[1024]; int n;
                while ((n = es.read(buf)) != -1 && bo.size() < 2000) bo.write(buf, 0, n);
                body = bo.toString("UTF-8");
            }
            return "HTTP " + code + " " + body;
        } catch (Throwable e) {
            return e.toString();
        } finally {
            if (con != null) con.disconnect();
        }
    }

    // ---- pengawas Tailscale (tiap jam) ----
    private static final String TS_PKG = "com.tailscale.ipn";
    private boolean tsMissingLogged = false;

    private final Runnable tsTask = new Runnable() {
        @Override public void run() {
            try { checkTailscale(); } catch (Throwable t) { StatsLog.log("cek Tailscale error: " + t); }
            ui.postDelayed(this, 3600000L);
        }
    };

    /** IP Tailscale (100.x) bila antarmuka VPN aktif, selain itu null. */
    private String tailscaleIp() {
        try {
            for (java.util.Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e.hasMoreElements();) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp()) continue;
                for (java.util.Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements();) {
                    InetAddress ia = a.nextElement();
                    byte[] b = ia.getAddress();
                    // rentang CGNAT Tailscale 100.64.0.0/10
                    if (b.length == 4 && (b[0] & 0xff) == 100 && ((b[1] & 0xff) >= 64 && (b[1] & 0xff) <= 127))
                        return ia.getHostAddress();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void checkTailscale() {
        if (cfg != null && !cfg.tsWatch) return;
        boolean installed;
        try { getPackageManager().getPackageInfo(TS_PKG, 0); installed = true; } catch (Throwable t) { installed = false; }
        if (!installed) {
            if (!tsMissingLogged) { StatsLog.log("[Tailscale] aplikasi Tailscale belum terpasang di TV"); tsMissingLogged = true; }
            return;
        }
        String ip = tailscaleIp();
        if (ip != null) { StatsLog.log("[Tailscale] tersambung, IP " + ip); return; }

        StatsLog.log("[Tailscale] TIDAK tersambung -> mencoba menyalakan (kirim perintah sambung)");
        try {
            Intent b = new Intent("com.tailscale.ipn.CONNECT_VPN");
            b.setComponent(new android.content.ComponentName(TS_PKG, TS_PKG + ".IPNReceiver"));
            sendBroadcast(b);
        } catch (Throwable t) { StatsLog.log("[Tailscale] perintah sambung gagal: " + t); }

        ui.postDelayed(() -> {
            String ip2 = tailscaleIp();
            if (ip2 != null) { StatsLog.log("[Tailscale] berhasil tersambung lagi, IP " + ip2); return; }
            if (stopped) { StatsLog.log("[Tailscale] masih mati; aplikasi di latar, tidak dibuka paksa"); return; }
            StatsLog.log("[Tailscale] masih mati -> membuka aplikasi Tailscale sebentar");
            try {
                Intent li = getPackageManager().getLaunchIntentForPackage(TS_PKG);
                if (li == null) li = getPackageManager().getLeanbackLaunchIntentForPackage(TS_PKG);
                if (li != null) { li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(li); }
            } catch (Throwable t) { StatsLog.log("[Tailscale] gagal membuka: " + t); }
            ui.postDelayed(() -> {
                try {
                    android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                    am.moveTaskToFront(getTaskId(), 0);
                } catch (Throwable t) { StatsLog.log("[Tailscale] gagal kembali ke layar CCTV: " + t); }
                String ip3 = tailscaleIp();
                StatsLog.log(ip3 != null ? "[Tailscale] tersambung, IP " + ip3 : "[Tailscale] tetap mati (perlu dicek/login manual)");
            }, 12000L);
        }, 20000L);
    }

    // ---- log kinerja ----
    private static final long LOG_EVERY = 10000L;
    private final Runnable logTask = new Runnable() {
        @Override public void run() {
            try { logTick(); } catch (Throwable ignored) {}
            ui.postDelayed(this, LOG_EVERY);
        }
    };

    // ---- halaman web ----
    private WebView webView;
    private TextView webError;
    private Runnable refreshTask;
    private Runnable retryTask;

    /** Jembatan dari halaman setting (HP/laptop) ke aplikasi. */
    private final WebServer.Controller controller = new WebServer.Controller() {
        @Override public void onConfigChanged() { ui.post(MainActivity.this::startRotation); }
        @Override public String control(String cmd, int index) {
            if ("tg".equals(cmd)) { sendTelegramAsync(true); return statusJson(); }
            final String c = cmd;
            final int i = index;
            ui.post(() -> doControl(c, i));
            return statusJson();
        }
        @Override public String status() { return statusJson(); }
        @Override public byte[] screenshot() { return takeShot(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        libVLC = newLibVLC();

        startWeb();
        StatsLog.appCtx = getApplicationContext();
        logHeader();
        startWatchdog();
        startRotation();
        ui.postDelayed(logTask, LOG_EVERY);
        ui.postDelayed(tsTask, 60000L);
        ui.postDelayed(tgTask, 120000L);
    }

    private void logHeader() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            String soc = "";
            if (android.os.Build.VERSION.SDK_INT >= 31) soc = " soc=" + android.os.Build.SOC_MANUFACTURER + "/" + android.os.Build.SOC_MODEL;
            StatsLog.append(this, "=== APLIKASI MULAI === " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                    + " device=" + android.os.Build.DEVICE + " hw=" + android.os.Build.HARDWARE + " board=" + android.os.Build.BOARD + soc
                    + " android=" + android.os.Build.VERSION.RELEASE + " sdk=" + android.os.Build.VERSION.SDK_INT
                    + " abi=" + android.os.Build.SUPPORTED_ABIS[0]
                    + " cpu=" + Runtime.getRuntime().availableProcessors() + "core"
                    + " ramTotal=" + (mi.totalMem / 1048576) + "MB"
                    + " layar=" + getResources().getDisplayMetrics().widthPixels + "x" + getResources().getDisplayMetrics().heightPixels);
        } catch (Throwable ignored) {}
    }

    /** Dipanggil tiap 10 detik: catat keadaan tiap kotak CCTV dan memori TV. */
    private void logTick() {
        if (stopped || cells.isEmpty()) return;
        String title = (cur >= 0 && cur < pages.size()) ? pages.get(cur).title() : "-";
        String sys = "";
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            sys = "RAM kosong=" + (mi.availMem / 1048576) + "MB lowMem=" + mi.lowMemory;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                sys += " thermal=" + pm.getCurrentThermalStatus();
            }
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/loadavg"));
                String l = r.readLine(); r.close();
                if (l != null) sys += " load=" + l.split(" ")[0];
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        StatsLog.append(this, "[" + title + "] " + sys);
        for (VideoCell c : new ArrayList<>(cells)) StatsLog.append(this, "   " + c.snapshot());
        checkRecover();
    }

    private String libDecoder = "hw";

    private LibVLC newLibVLC() {
        ArrayList<String> opts = new ArrayList<>();
        opts.add("--rtsp-tcp");
        opts.add("--network-caching=600");
        opts.add("--no-audio");
        Config cc = (cfg != null) ? cfg : ConfigStore.load(this);
        libDecoder = "sw".equals(cc.decoder) ? "sw" : "hw";
        opts.add("sw".equals(libDecoder) ? "--avcodec-hw=none" : "--avcodec-hw=any");
        StatsLog.log("[Dekoder] " + ("sw".equals(libDecoder) ? "perangkat LUNAK (software)" : "perangkat KERAS (hardware)"));
        return new LibVLC(this, opts);
    }

    private void startWeb() {
        for (int port : WEB_PORTS) {
            try {
                WebServer ws = new WebServer(this, port, controller);
                ws.start();
                webServer = ws;
                webPort = port;
                return;
            } catch (Exception ignored) {
                // port dipakai aplikasi lain, coba port berikutnya
            }
        }
    }

    /** Alamat halaman setting yang benar-benar aktif, untuk ditampilkan di layar TV. */
    private String settingUrl() {
        if (webPort == 0) return "Server setting gagal start (semua port terpakai)";
        return "http://" + localIp() + ":" + webPort;
    }

    // ============================== rotasi ==============================

    /** Muat ulang config dan mulai rotasi dari awal. */
    private void startRotation() {
        ui.removeCallbacks(rotateTask);
        cfg = ConfigStore.load(this);
        pages = cfg.pages;
        if (!("sw".equals(cfg.decoder) ? "sw" : "hw").equals(libDecoder)) {
            try {
                final LibVLC old = libVLC;
                clearScreen();
                libVLC = newLibVLC();
                VideoCell.bg(() -> { try { old.release(); } catch (Throwable ignored) {} });
            } catch (Throwable t) { StatsLog.log("ganti dekoder gagal: " + t); }
        }
        scheduleRestart();
        cur = -1;
        paused = false;
        screenShowing = false;
        hintDone = false;
        if (stopped) return; // di latar belakang: tampil nanti saat aplikasi dibuka lagi
        advance(1);
    }

    private void next() { advance(1); }

    /** Pindah ke halaman aktif berikutnya (dir = 1) atau sebelumnya (dir = -1). */
    private void advance(int dir) {
        ui.removeCallbacks(rotateTask);
        if (stopped) return;
        if (fullActive) {
            fullActive = false;
            ui.removeCallbacks(exitFullTask);
            screenShowing = false;
        }
        int n = pages.size();
        if (n == 0) {
            clearScreen();
            showMessage("Belum ada halaman.\n\nBuka  " + settingUrl() + "  di HP/laptop (WiFi yang sama) untuk setting.");
            return;
        }

        int active = 0;
        for (Page p : pages) if (p.isActiveNow()) active++;

        int found = -1;
        for (int i = 1; i <= n && found < 0; i++) {
            int idx = (((cur + dir * i) % n) + n) % n;
            if (pages.get(idx).isActiveNow()) found = idx;
        }

        if (found < 0) {
            // tidak ada halaman yang aktif sekarang (mis. di luar jam tayang): cek lagi sebentar lagi
            cur = -1;
            clearScreen();
            showMessage("Tidak ada halaman yang aktif saat ini.\n\nSetting: " + settingUrl());
            if (!paused) ui.postDelayed(rotateTask, 30000);
            return;
        }

        if (found == cur && active == 1 && screenShowing) {
            // hanya satu halaman aktif dan sudah tampil: jangan digambar ulang
            scheduleNext();
            return;
        }

        cur = found;
        showPage(pages.get(cur));
        scheduleNext();
    }

    private void scheduleNext() {
        ui.removeCallbacks(rotateTask);
        if (paused || fullActive || cur < 0 || cur >= pages.size()) return;
        int active = 0;
        for (Page p : pages) if (p.isActiveNow()) active++;
        long d = Math.max(5, pages.get(cur).duration) * 1000L;
        if (active <= 1) d = Math.max(d, 30000L);
        ui.postDelayed(rotateTask, d);
    }

    private void doControl(String cmd, int idx) {
        if (stopped) return;
        switch (cmd) {
            case "next":
                advance(1);
                break;
            case "prev":
                advance(-1);
                break;
            case "pause":
                paused = true;
                ui.removeCallbacks(rotateTask);
                break;
            case "resume":
                paused = false;
                scheduleNext();
                break;
            case "unfull":
                exitFull();
                break;
            case "fullcam": {
                int pi = idx / 100, ci = idx % 100;
                if (pi >= 0 && pi < pages.size() && !pages.get(pi).isWeb()
                        && ci < pages.get(pi).cameras.size()) {
                    ui.removeCallbacks(rotateTask);
                    if (pi != cur || fullActive) {
                        fullActive = false;
                        cur = pi;
                        showPage(pages.get(cur));
                    }
                    enterFull(ci);
                }
                break;
            }
            case "show":
                if (idx >= 0 && idx < pages.size()) {
                    ui.removeCallbacks(rotateTask);
                    cur = idx;
                    showPage(pages.get(cur));
                    scheduleNext();
                }
                break;
            default:
                break;
        }
    }

    private String statusJson() {
        try {
            JSONObject o = new JSONObject();
            List<Page> ps = pages;
            int c = cur;
            o.put("count", ps.size());
            o.put("current", c);
            o.put("paused", paused);
            o.put("title", (c >= 0 && c < ps.size()) ? ps.get(c).title() : "");
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    // ============================== tampilkan satu halaman ==============================

    private void showPage(Page p) {
        clearScreen();
        boolean attention = webPort == 0;
        if (p.isWeb()) {
            showWeb(p);
        } else {
            Config pc = cfg.forPage(p);
            if (pc.cameras.isEmpty()) {
                showMessage("Halaman CCTV \"" + p.title() + "\" belum ada kamera.\n\nBuka  " + settingUrl());
            } else {
                cctvCfg = pc;
                List<VLCVideoLayout> videos = buildGrid(pc);
                for (int i = 0; i < videos.size() && i < pc.cameras.size(); i++) {
                    Camera cam = pc.cameras.get(i);
                    VideoCell cell = new VideoCell(libVLC, videos.get(i), cam.chain(pc), cam.key(), !"none".equals(cam.maxRes), pc.networkCaching, ui);
                    cell.name = cam.name + " (" + cam.quality + ")";
                    cell.display = cam.display;
                    cell.baseSw = "sw".equals(cfg.decoder);
                    cell.cam = cam;
                    cell.pcfg = pc;
                    cells.add(cell);
                    cell.start();
                }
                if (needsPassword(pc)) attention = true;
            }
        }
        screenShowing = true;
        if (!hintDone || attention) {
            showHint("Setting: " + settingUrl(), attention);
            hintDone = true;
        }
    }

    /** Hentikan semua pemutar/WebView dan kosongkan layar. */
    private void clearScreen() {
        screenShowing = false;
        fullActive = false;
        ui.removeCallbacks(exitFullTask);
        ui.removeCallbacks(clearSelTask);
        cellBorders.clear();
        selIdx = -1;
        releaseCells();
        destroyWeb();
        root.removeAllViews();
    }

    private boolean needsPassword(Config pc) {
        for (Camera c : pc.cameras) {
            String u = (c.user != null && !c.user.isEmpty()) ? c.user : pc.defaultUser;
            String p = (c.pass != null && !c.pass.isEmpty()) ? c.pass : pc.defaultPass;
            if (!u.isEmpty() && p.isEmpty()) return true;
        }
        return false;
    }

    // ============================== halaman web ==============================

    private static String normalizeUrl(String u) {
        if (u == null) return "about:blank";
        u = u.trim();
        if (u.isEmpty()) return "about:blank";
        if (!u.contains("://")) u = "http://" + u;
        return u;
    }

    private void showWeb(final Page p) {
        try {
            final WebView wv = new WebView(this);
            WebSettings s = wv.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            s.setTextZoom(100);
            wv.setBackgroundColor(Color.BLACK);
            android.util.DisplayMetrics dm0 = getResources().getDisplayMetrics();
            final String hExpr;
            if (p.viewWidth > 0) {
                double sc0 = (dm0.widthPixels / (double) dm0.density) / p.viewWidth * (p.zoom > 0 ? p.zoom / 100.0 : 1.0);
                hExpr = String.format(java.util.Locale.US, "%.1f", dm0.heightPixels / (double) dm0.density / sc0);
            } else {
                hExpr = "window.innerHeight";
            }
            final String stretchJs = !p.stretch ? "" :
                "(function(){if(window.__agiStretch)return;window.__agiStretch=1;"
              + "var busy=false;"
              + "function f(){if(busy)return;busy=true;try{var H=" + hExpr + ",best=null,ba=0;"
              + "var ts=document.getElementsByTagName('table');"
              + "for(var i=0;i<ts.length;i++){if(ts[i].getElementsByTagName('table').length)continue;var r=ts[i].getBoundingClientRect();var a=r.width*r.height;"
              + "if(a>ba){ba=a;best=ts[i];}}"
              + "if(best){var r=best.getBoundingClientRect();var top=r.top+window.pageYOffset;"
              + "var want=H-top-10;"
              + "if(want>r.height+4||(best.__agiH&&Math.abs(r.height-best.__agiH)>3&&want>0)){"
              + "best.style.setProperty('height',want+'px','important');best.__agiH=want;}}"
              + "}catch(e){}busy=false;}"
              + "f();setInterval(f,250);"
              + "try{new MutationObserver(function(){setTimeout(f,0);}).observe(document.documentElement,"
              + "{childList:true,subtree:true,attributes:true,attributeFilter:['style','class']});}catch(e){}"
              + "window.addEventListener('resize',f);})();";
            final String vpJs0;
            if (p.viewWidth > 0) {
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                double cssW = dm.widthPixels / (double) dm.density;
                double sc = cssW / p.viewWidth * (p.zoom > 0 ? p.zoom / 100.0 : 1.0);
                String sv = String.format(java.util.Locale.US, "%.4f", sc);
                vpJs0 = "(function(){var m=document.querySelector('meta[name=viewport]');"
                     + "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}"
                     + "m.content='width=" + p.viewWidth + ", initial-scale=" + sv + ", minimum-scale=" + sv
                     + ", maximum-scale=" + sv + ", user-scalable=no';})();";
            } else {
                vpJs0 = "";
                if (p.zoom > 0) wv.setInitialScale(p.zoom);
            }
            final String customJs = (p.customJs == null || p.customJs.trim().isEmpty()) ? ""
                    : "(function(){try{\n" + p.customJs + "\n}catch(e){}})();";
            final String vpJs = vpJs0 + customJs + stretchJs;

            wv.setWebViewClient(new WebViewClient() {
                @Override
                public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
                    if (p.ignoreSsl) {
                        h.proceed();
                    } else {
                        h.cancel();
                        webFailed(wv, "Sertifikat tidak valid. Centang \"Abaikan error sertifikat\" di setting bila ini situs internal.");
                    }
                }

                @SuppressWarnings("deprecation")
                @Override
                public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
                    webFailed(wv, "Gagal memuat halaman (" + desc + "). Mencoba lagi …");
                }

                @Override
                public void onPageCommitVisible(WebView v, String url) {
                    if (!vpJs.isEmpty()) v.evaluateJavascript(vpJs, null);
                }

                @Override
                public void onPageFinished(WebView v, String url) {
                    if (!vpJs.isEmpty()) v.evaluateJavascript(vpJs, null);
                    if (webError != null) {
                        root.removeView(webError);
                        webError = null;
                    }
                }
            });

            root.addView(wv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            webView = wv;
            wv.loadUrl(normalizeUrl(p.url));

            if (p.refreshMin > 0) {
                final long every = p.refreshMin * 60000L;
                refreshTask = new Runnable() {
                    @Override public void run() {
                        if (webView == wv) {
                            wv.reload();
                            ui.postDelayed(this, every);
                        }
                    }
                };
                ui.postDelayed(refreshTask, every);
            }
        } catch (Throwable t) {
            showMessage("WebView tidak tersedia di TV ini.\n\n" + t.getMessage());
        }
    }

    private void webFailed(final WebView wv, String text) {
        if (webView != wv) return;
        if (webError == null) {
            webError = new TextView(this);
            webError.setTextColor(Color.WHITE);
            webError.setTextSize(16);
            webError.setPadding(24, 12, 24, 12);
            webError.setBackgroundColor(Color.parseColor("#CC7F1D1D"));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.setMargins(0, 0, 0, 40);
            root.addView(webError, lp);
        }
        webError.setText(text);
        if (retryTask != null) ui.removeCallbacks(retryTask);
        retryTask = new Runnable() {
            @Override public void run() {
                if (webView == wv) wv.reload();
            }
        };
        ui.postDelayed(retryTask, 20000);
    }

    private void destroyWeb() {
        if (refreshTask != null) { ui.removeCallbacks(refreshTask); refreshTask = null; }
        if (retryTask != null) { ui.removeCallbacks(retryTask); retryTask = null; }
        webError = null;
        if (webView != null) {
            WebView wv = webView;
            webView = null;
            try {
                wv.stopLoading();
                wv.loadUrl("about:blank");
                ViewGroup parent = (ViewGroup) wv.getParent();
                if (parent != null) parent.removeView(wv);
                wv.destroy();
            } catch (Throwable ignored) {}
        }
    }

    // ============================== grid CCTV ==============================

    /** Buat kotak-kotak dan kembalikan daftar VLCVideoLayout (index 0 = kotak besar). */
    private List<VLCVideoLayout> buildGrid(Config c) {
        String layout = c.layout == null ? "1+5" : c.layout;
        if (layout.equals("1+3")) {
            return buildBigPlusColumn(c, 3);
        } else if (layout.equals("2x2")) {
            return buildUniformGrid(c, 2, 2);
        } else {
            return buildOnePlusFive(c);
        }
    }

    /** 1 besar di kiri (bobot 2) + kolom kanan berisi beberapa kotak kecil. */
    private List<VLCVideoLayout> buildBigPlusColumn(Config c, int smallCount) {
        List<VLCVideoLayout> out = new ArrayList<>();
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(h, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout big = makeCell(c, 0);
        out.add((VLCVideoLayout) big.getChildAt(0));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 2f);
        setMargins(bp);
        h.addView(big, bp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        h.addView(col, cp);

        for (int i = 1; i <= smallCount && i < c.cameras.size(); i++) {
            FrameLayout cell = makeCell(c, i);
            out.add((VLCVideoLayout) cell.getChildAt(0));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            setMargins(p);
            col.addView(cell, p);
        }
        return out;
    }

    /** 1 besar (2x2) + 5 kecil dalam grid 3x3, meniru susunan dinding CCTV. */
    private List<VLCVideoLayout> buildOnePlusFive(Config c) {
        List<VLCVideoLayout> out = new ArrayList<>();
        GridLayout g = new GridLayout(this);
        g.setRowCount(3);
        g.setColumnCount(3);
        root.addView(g, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // besar: baris 0-1, kolom 0-1
        addGridCell(g, c, out, 0, 0, 2, 2);
        // 5 kecil
        int[][] pos = {{0, 2}, {1, 2}, {2, 0}, {2, 1}, {2, 2}};
        for (int i = 0; i < pos.length && (i + 1) < c.cameras.size(); i++) {
            addGridCell(g, c, out, pos[i][0], pos[i][1], 1, 1);
        }
        return out;
    }

    private List<VLCVideoLayout> buildUniformGrid(Config c, int rows, int cols) {
        List<VLCVideoLayout> out = new ArrayList<>();
        GridLayout g = new GridLayout(this);
        g.setRowCount(rows);
        g.setColumnCount(cols);
        root.addView(g, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int idx = 0;
        for (int r = 0; r < rows; r++) {
            for (int col = 0; col < cols; col++) {
                if (idx >= c.cameras.size()) break;
                addGridCell(g, c, out, r, col, 1, 1);
                idx++;
            }
        }
        return out;
    }

    private void addGridCell(GridLayout g, Config c, List<VLCVideoLayout> out,
                             int row, int col, int rowSpan, int colSpan) {
        int index = out.size();
        FrameLayout cell = makeCell(c, index);
        out.add((VLCVideoLayout) cell.getChildAt(0));

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.rowSpec = GridLayout.spec(row, rowSpan, GridLayout.FILL, (float) rowSpan);
        lp.columnSpec = GridLayout.spec(col, colSpan, GridLayout.FILL, (float) colSpan);
        lp.width = 0;
        lp.height = 0;
        lp.setMargins(gap, gap, gap, gap);
        g.addView(cell, lp);
    }

    /** Satu kotak = FrameLayout berisi VLCVideoLayout + label nama di pojok. */
    private FrameLayout makeCell(Config c, int index) {
        FrameLayout cell = new FrameLayout(this);
        cell.setBackgroundColor(Color.parseColor("#111111"));

        VLCVideoLayout v = new VLCVideoLayout(this);
        cell.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // klik dobel = layar penuh; sorotan kotak untuk navigasi remote
        final int idx = index;
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { enterFull(idx); return true; }
        });
        cell.setOnTouchListener((vw, ev) -> gd.onTouchEvent(ev));
        View border = new View(this);
        GradientDrawable gdw = new GradientDrawable();
        gdw.setColor(Color.TRANSPARENT);
        gdw.setStroke(6, Color.parseColor("#FFEB3B"));
        border.setBackground(gdw);
        border.setVisibility(View.GONE);
        cell.addView(border, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cellBorders.add(border);

        String name = "";
        if (index < c.cameras.size()) name = c.cameras.get(index).name;
        if (name != null && !name.isEmpty()) {
            TextView label = new TextView(this);
            label.setText(name);
            label.setTextColor(Color.WHITE);
            label.setTextSize(13);
            label.setPadding(10, 4, 10, 4);
            label.setBackgroundColor(Color.parseColor("#99000000"));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM | Gravity.START;
            lp.setMargins(6, 6, 6, 6);
            cell.addView(label, lp);
        }
        return cell;
    }

    private void setMargins(LinearLayout.LayoutParams p) {
        p.setMargins(gap, gap, gap, gap);
    }

    // ============================== layar penuh satu kamera ==============================

    /** Tampilkan satu kamera memenuhi layar (stream main), kembali otomatis setelah 10 menit. */
    private void enterFull(int camIdx) {
        if (stopped || fullActive || cctvCfg == null) return;
        if (camIdx < 0 || camIdx >= cctvCfg.cameras.size()) return;
        final Config pc = cctvCfg;
        Camera src = pc.cameras.get(camIdx);
        Camera cam = Camera.fromJson(src.toJson());
        if (cam.path == null || cam.path.isEmpty()) cam.quality = "main"; // layar penuh = main stream

        // hentikan semua kotak, lalu gambar satu kotak penuh
        releaseCells();
        root.removeAllViews();
        cellBorders.clear();
        selIdx = -1;
        ui.removeCallbacks(rotateTask);
        ui.removeCallbacks(clearSelTask);

        FrameLayout cell = new FrameLayout(this);
        cell.setBackgroundColor(Color.BLACK);
        VLCVideoLayout v = new VLCVideoLayout(this);
        cell.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { exitFull(); return true; }
        });
        cell.setOnTouchListener((vw, ev) -> gd.onTouchEvent(ev));
        TextView label = new TextView(this);
        label.setText(cam.name + "   (klik dobel / OK / Back untuk kembali)");
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        label.setPadding(10, 4, 10, 4);
        label.setBackgroundColor(Color.parseColor("#99000000"));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.setMargins(6, 6, 6, 6);
        cell.addView(label, lp);
        root.addView(cell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        VideoCell vc = new VideoCell(libVLC, v, cam.chain(pc), cam.key(), !"none".equals(cam.maxRes), pc.networkCaching, ui);
        vc.name = cam.name + " (" + cam.quality + ", layar penuh)";
        vc.display = cam.display;
        vc.baseSw = cfg != null && "sw".equals(cfg.decoder);
        vc.cam = cam;
        vc.pcfg = pc;
        cells.add(vc);
        vc.start();

        fullActive = true;
        ui.removeCallbacks(exitFullTask);
        ui.postDelayed(exitFullTask, FULL_MS);
    }

    /** Kembali ke grid halaman CCTV yang tadi, lalu lanjutkan rotasi. */
    private void exitFull() {
        if (!fullActive || stopped) return;
        fullActive = false;
        ui.removeCallbacks(exitFullTask);
        if (cur >= 0 && cur < pages.size()) {
            showPage(pages.get(cur));
            scheduleNext();
        } else {
            advance(1);
        }
    }

    /** Sorot satu kotak (navigasi dengan tombol panah remote); -1 = tanpa sorotan. */
    private void setSelection(int idx) {
        ui.removeCallbacks(clearSelTask);
        for (int i = 0; i < cellBorders.size(); i++) {
            cellBorders.get(i).setVisibility(i == idx ? View.VISIBLE : View.GONE);
        }
        selIdx = idx;
        if (idx >= 0) ui.postDelayed(clearSelTask, 8000);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (stopped) return super.onKeyDown(keyCode, event);
        boolean enter = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (fullActive) {
            if (enter) { exitFull(); return true; }
        } else if (!cellBorders.isEmpty()) {
            int n = cellBorders.size();
            if (enter) {
                if (selIdx < 0) setSelection(0); else enterFull(selIdx);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                setSelection(selIdx < 0 ? 0 : (selIdx + 1) % n);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                setSelection(selIdx < 0 ? 0 : (selIdx - 1 + n) % n);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (fullActive) { exitFull(); return; }
        super.onBackPressed();
    }

    // ============================== overlay pesan & hint ==============================

    private void showMessage(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(20);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(40, 40, 40, 40);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(tv, lp);
    }

    private void showHint(String text, boolean keep) {
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#CCFFFFFF"));
        tv.setTextSize(12);
        tv.setPadding(12, 6, 12, 6);
        tv.setBackgroundColor(Color.parseColor("#AA000000"));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.setMargins(0, 0, 10, 0);
        root.addView(tv, lp);
        if (!keep) {
            ui.postDelayed(() -> {
                try { root.removeView(tv); } catch (Exception ignored) {}
            }, 25000);
        }
    }

    // ============================== util ==============================

    private void releaseCells() {
        for (VideoCell c : cells) c.release();
        cells.clear();
    }

    private void hideSystemUi() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** IP lokal perangkat (WiFi/Ethernet), untuk ditunjukkan ke user. */
    static String localIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }

    /** Aplikasi tidak terlihat (Home, screensaver, TV tidur): hentikan semua stream & halaman web. */
    @Override
    protected void onStop() {
        stopped = true;
        ui.removeCallbacks(rotateTask);
        clearScreen();
        super.onStop();
    }

    /** Aplikasi terlihat lagi: mulai rotasi dari awal. */
    @Override
    protected void onStart() {
        super.onStart();
        if (stopped) {
            stopped = false;
            startRotation();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(rotateTask);
        clearScreen();
        if (webServer != null) webServer.stop();
        if (libVLC != null) {
            try { libVLC.release(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
