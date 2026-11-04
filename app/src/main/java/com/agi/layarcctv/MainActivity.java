package com.agi.layarcctv;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

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
    private static final int WEB_PORT = 8080;
    private int gap = 4; // jarak antar kotak (px)

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
        build();
    }

    private void startWeb() {
        try {
            webServer = new WebServer(this, WEB_PORT, () -> ui.post(this::build));
            webServer.start();
        } catch (Exception ignored) {}
    }

    // ---------- bangun tampilan dari config ----------

    private void build() {
        releaseCells();
        root.removeAllViews();

        Config cfg = ConfigStore.load(this);
        if (cfg.cameras.isEmpty()) {
            showMessage("Belum ada kamera.\n\nBuka  http://" + localIp() + ":" + WEB_PORT
                    + "  di HP (WiFi yang sama) untuk setting.");
            return;
        }

        List<VLCVideoLayout> videos = buildGrid(cfg);
        for (int i = 0; i < videos.size() && i < cfg.cameras.size(); i++) {
            Camera cam = cfg.cameras.get(i);
            VideoCell cell = new VideoCell(libVLC, videos.get(i), cam.url(cfg), cfg.networkCaching, ui);
            cells.add(cell);
            cell.start();
        }

        showHint("Setting: http://" + localIp() + ":" + WEB_PORT, needsPassword(cfg));
    }

    private boolean needsPassword(Config cfg) {
        for (Camera c : cfg.cameras) {
            String u = (c.user != null && !c.user.isEmpty()) ? c.user : cfg.defaultUser;
            String p = (c.pass != null && !c.pass.isEmpty()) ? c.pass : cfg.defaultPass;
            if (!u.isEmpty() && p.isEmpty()) return true;
        }
        return false;
    }

    /** Buat kotak-kotak dan kembalikan daftar VLCVideoLayout (index 0 = kotak besar). */
    private List<VLCVideoLayout> buildGrid(Config cfg) {
        int n = cfg.cameras.size();
        String layout = cfg.layout == null ? "1+5" : cfg.layout;

        if (layout.equals("1+3")) {
            return buildBigPlusColumn(cfg, 3);
        } else if (layout.equals("2x2")) {
            return buildUniformGrid(cfg, 2, 2);
        } else {
            // default 1+5 : grid 3x3, besar span 2x2 kiri-atas
            return buildOnePlusFive(cfg);
        }
    }

    /** 1 besar di kiri (bobot 2) + kolom kanan berisi beberapa kotak kecil. */
    private List<VLCVideoLayout> buildBigPlusColumn(Config cfg, int smallCount) {
        List<VLCVideoLayout> out = new ArrayList<>();
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(h, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout big = makeCell(cfg, 0);
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

        for (int i = 1; i <= smallCount && i < cfg.cameras.size(); i++) {
            FrameLayout cell = makeCell(cfg, i);
            out.add((VLCVideoLayout) cell.getChildAt(0));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            setMargins(p);
            col.addView(cell, p);
        }
        return out;
    }

    /** 1 besar (2x2) + 5 kecil dalam grid 3x3, meniru susunan dinding CCTV. */
    private List<VLCVideoLayout> buildOnePlusFive(Config cfg) {
        List<VLCVideoLayout> out = new ArrayList<>();
        GridLayout g = new GridLayout(this);
        g.setRowCount(3);
        g.setColumnCount(3);
        root.addView(g, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // besar: baris 0-1, kolom 0-1
        addGridCell(g, cfg, out, 0, 0, 2, 2);
        // 5 kecil
        int[][] pos = {{0, 2}, {1, 2}, {2, 0}, {2, 1}, {2, 2}};
        for (int i = 0; i < pos.length && (i + 1) < cfg.cameras.size(); i++) {
            addGridCell(g, cfg, out, pos[i][0], pos[i][1], 1, 1);
        }
        return out;
    }

    private List<VLCVideoLayout> buildUniformGrid(Config cfg, int rows, int cols) {
        List<VLCVideoLayout> out = new ArrayList<>();
        GridLayout g = new GridLayout(this);
        g.setRowCount(rows);
        g.setColumnCount(cols);
        root.addView(g, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int idx = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (idx >= cfg.cameras.size()) break;
                addGridCell(g, cfg, out, r, c, 1, 1);
                idx++;
            }
        }
        return out;
    }

    private void addGridCell(GridLayout g, Config cfg, List<VLCVideoLayout> out,
                             int row, int col, int rowSpan, int colSpan) {
        int index = out.size();
        FrameLayout cell = makeCell(cfg, index);
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
    private FrameLayout makeCell(Config cfg, int index) {
        FrameLayout cell = new FrameLayout(this);
        cell.setBackgroundColor(Color.parseColor("#111111"));

        VLCVideoLayout v = new VLCVideoLayout(this);
        cell.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        String name = "";
        if (index < cfg.cameras.size()) name = cfg.cameras.get(index).name;
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

    // ---------- overlay pesan & hint ----------

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

    // ---------- util ----------

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

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onDestroy() {
        releaseCells();
        if (webServer != null) webServer.stop();
        if (libVLC != null) {
            try { libVLC.release(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
