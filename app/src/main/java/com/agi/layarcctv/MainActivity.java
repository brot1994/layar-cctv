package com.agi.layarcctv;

import android.app.Activity;
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
    private boolean stopped = false; // true saat aplikasi di latar belakang (Home/screensaver/TV tidur)
    private final Runnable rotateTask = this::next;

    // ---- layar penuh satu kamera ----
    private static final long FULL_MS = 10 * 60 * 1000L; // kembali otomatis setelah 10 menit
    private boolean fullActive = false;
    private Config cctvCfg;                       // config halaman CCTV yang sedang tampil
    private final List<View> cellBorders = new ArrayList<>();
    private int selIdx = -1;                      // kotak yang disorot (navigasi remote)
    private final Runnable exitFullTask = this::exitFull;
    private final Runnable clearSelTask = () -> setSelection(-1);

    // ---- halaman web ----
    private WebView webView;
    private TextView webError;
    private Runnable refreshTask;
    private Runnable retryTask;

    /** Jembatan dari halaman setting (HP/laptop) ke aplikasi. */
    private final WebServer.Controller controller = new WebServer.Controller() {
        @Override public void onConfigChanged() { ui.post(MainActivity.this::startRotation); }
        @Override public String control(String cmd, int index) {
            final String c = cmd;
            final int i = index;
            ui.post(() -> doControl(c, i));
            return statusJson();
        }
        @Override public String status() { return statusJson(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        ArrayList<String> opts = new ArrayList<>();
        opts.add("--rtsp-tcp");
        opts.add("--network-caching=600");
        opts.add("--no-audio");
        opts.add("--avcodec-hw=any");
        libVLC = new LibVLC(this, opts);

        startWeb();
        startRotation();
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
                    VideoCell cell = new VideoCell(libVLC, videos.get(i), cam.url(pc), pc.networkCaching, ui);
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
            final String vpJs = vpJs0 + stretchJs;

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
        if (cam.path == null || cam.path.isEmpty()) cam.sub = false; // layar penuh = main stream

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

        VideoCell vc = new VideoCell(libVLC, v, cam.url(pc), pc.networkCaching, ui);
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
