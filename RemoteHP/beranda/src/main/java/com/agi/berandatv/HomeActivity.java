package com.agi.berandatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Beranda TV ringan: wallpaper (foto atau MP4 pendek berulang), jam, favorit otomatis
 * (paling sering dibuka), lalu semua aplikasi terpasang. Tanpa iklan / rekomendasi.
 */
public class HomeActivity extends Activity {

    /** Favorit awal sebelum ada riwayat pemakaian. */
    private static final String[] DEFAULT_FAV = {
            "com.google.android.youtube.tv", "com.netflix.ninja",
            "com.disney.disneyplus", "com.spotify.tv.android"};
    private static final int FAV_MAX = 7, COLS = 6;

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;
    private FrameLayout root;
    private ImageView wallImg;
    private TextureView wallVideo;
    private MediaPlayer player;
    private File wallFile;
    private long wallStamp = -1;
    private TextView clock, date;
    private LinearLayout favRow, allBox;
    private ScrollView scroll;
    private final Locale ID = new Locale("in", "ID");

    private static final class App {
        String pkg, label;
        Drawable banner, icon;
        Intent launch;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date d = new Date();
            clock.setText(new SimpleDateFormat("HH:mm", ID).format(d));
            date.setText(new SimpleDateFormat("EEEE, d MMMM yyyy", ID).format(d));
            h.postDelayed(this, 10000);
        }
    };

    // ============================================================ siklus

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("beranda", MODE_PRIVATE);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E1116);

        wallImg = new ImageView(this);
        wallImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wallImg, new FrameLayout.LayoutParams(-1, -1));

        wallVideo = new TextureView(this);
        wallVideo.setVisibility(View.GONE);
        wallVideo.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int hh) { startVideo(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int hh) { fitVideo(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { stopVideo(); return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
        });
        root.addView(wallVideo, new FrameLayout.LayoutParams(-1, -1));

        // gelap tipis di bawah supaya tulisan tetap terbaca
        View shade = new View(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x55000000, 0x11000000, 0xCC000000});
        shade.setBackground(g);
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(48), dp(28), dp(48), dp(40));
        scroll.addView(col);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        // jam + tombol setelan
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.TOP);
        LinearLayout clk = new LinearLayout(this);
        clk.setOrientation(LinearLayout.VERTICAL);
        clock = text("", 54, true);
        date = text("", 18, false);
        clk.addView(clock);
        clk.addView(date);
        top.addView(clk, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView set = text("⚙  Setelan", 16, true);
        set.setPadding(dp(18), dp(10), dp(18), dp(10));
        set.setBackground(round(0x66000000, 24, 0));
        makeFocusable(set, () -> {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { toastText("Setelan tidak bisa dibuka"); }
        }, null);
        top.addView(set, new LinearLayout.LayoutParams(-2, -2));
        col.addView(top);

        // ruang supaya wallpaper kelihatan
        View gap = new View(this);
        col.addView(gap, new LinearLayout.LayoutParams(1, dp(150)));

        col.addView(section("Favorit"));
        favRow = new LinearLayout(this);
        favRow.setClipChildren(false);
        col.addView(favRow);

        col.addView(section("Semua aplikasi"));
        allBox = new LinearLayout(this);
        allBox.setOrientation(LinearLayout.VERTICAL);
        allBox.setClipChildren(false);
        col.addView(allBox);
        col.setClipChildren(false);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        h.removeCallbacks(tick);
        tick.run();
        loadWallpaper(false);
        loadApps();
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
        stopVideo(); // video berhenti begitu aplikasi lain dibuka
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i != null && i.getBooleanExtra("reload", false)) wallStamp = -1;
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        scroll.smoothScrollTo(0, 0); // beranda: tombol Back tidak menutup apa-apa
        if (favRow.getChildCount() > 0) favRow.getChildAt(0).requestFocus();
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ============================================================ wallpaper

    /** Wallpaper terbaru: files/wall.* (dikirim lewat run-as) atau /sdcard/BerandaTV/wall.* (cadangan). */
    private File findWall() {
        File best = null;
        List<File> c = new ArrayList<>();
        for (String n : new String[]{"wall.jpg", "wall.png", "wall.mp4"}) {
            c.add(new File(getFilesDir(), n));
            c.add(new File(Environment.getExternalStorageDirectory(), "BerandaTV/" + n));
        }
        for (File f : c) {
            try {
                if (f.isFile() && f.canRead() && f.length() > 0 && (best == null || f.lastModified() > best.lastModified())) best = f;
            } catch (Exception ignored) { }
        }
        return best;
    }

    private void loadWallpaper(boolean force) {
        File f = findWall();
        long stamp = f == null ? 0 : f.lastModified() ^ f.length() ^ f.getAbsolutePath().hashCode();
        boolean changed = force || stamp != wallStamp;
        wallStamp = stamp;
        wallFile = f;
        if (f != null && f.getName().endsWith(".mp4")) {
            if (changed) stopVideo();
            wallImg.setImageDrawable(null);
            wallVideo.setVisibility(View.VISIBLE);
            if (wallVideo.isAvailable()) startVideo();
            return;
        }
        stopVideo();
        wallVideo.setVisibility(View.GONE);
        if (!changed) return;
        if (f == null) {
            wallImg.setImageDrawable(null);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF0B1B3A, 0xFF0E1116, 0xFF1E293B});
            wallImg.setBackground(g);
            return;
        }
        final File ff = f;
        final int sw = getResources().getDisplayMetrics().widthPixels;
        new Thread(() -> {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(ff.getAbsolutePath(), o);
                int s = 1;
                while (o.outWidth / (s * 2) >= sw) s *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = s;
                Bitmap bmp = BitmapFactory.decodeFile(ff.getAbsolutePath(), o2);
                h.post(() -> wallImg.setImageBitmap(bmp));
            } catch (Throwable ignored) { }
        }).start();
    }

    private void startVideo() {
        if (player != null || wallFile == null || !wallFile.getName().endsWith(".mp4") || !wallVideo.isAvailable()) return;
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(wallFile.getAbsolutePath());
            mp.setSurface(new Surface(wallVideo.getSurfaceTexture()));
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(p -> { fitVideo(); p.start(); });
            mp.setOnVideoSizeChangedListener((p, w, hh) -> fitVideo());
            mp.setOnErrorListener((p, w, e) -> { stopVideo(); return true; });
            mp.prepareAsync();
            player = mp;
        } catch (Exception e) {
            stopVideo();
        }
    }

    /** Potong tengah (center-crop) supaya video memenuhi layar. */
    private void fitVideo() {
        if (player == null) return;
        int vw, vh;
        try { vw = player.getVideoWidth(); vh = player.getVideoHeight(); } catch (Exception e) { return; }
        float w = wallVideo.getWidth(), hh = wallVideo.getHeight();
        if (vw <= 0 || vh <= 0 || w <= 0 || hh <= 0) return;
        float scale = Math.max(w / vw, hh / vh);
        float sx = vw * scale / w, sy = vh * scale / hh;
        Matrix m = new Matrix();
        m.setScale(sx, sy, w / 2f, hh / 2f);
        wallVideo.setTransform(m);
    }

    private void stopVideo() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }

    // ============================================================ aplikasi

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, App> map = new LinkedHashMap<>();
        Intent lb = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Intent ln = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (Intent q : new Intent[]{lb, ln}) {
            List<ResolveInfo> list;
            try { list = pm.queryIntentActivities(q, 0); } catch (Exception e) { continue; }
            for (ResolveInfo ri : list) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || map.containsKey(pkg)) continue;
                App a = new App();
                a.pkg = pkg;
                a.label = String.valueOf(ri.loadLabel(pm));
                try { a.banner = ri.activityInfo.loadBanner(pm); } catch (Exception ignored) { }
                if (a.banner == null) {
                    try { a.banner = pm.getApplicationBanner(pkg); } catch (Exception ignored) { }
                }
                try { a.icon = ri.loadIcon(pm); } catch (Exception ignored) { }
                Intent li = q == lb ? pm.getLeanbackLaunchIntentForPackage(pkg) : null;
                if (li == null) li = pm.getLaunchIntentForPackage(pkg);
                if (li == null) li = new Intent(q).setClassName(pkg, ri.activityInfo.name);
                a.launch = li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                map.put(pkg, a);
            }
        }
        List<App> all = new ArrayList<>(map.values());
        Collections.sort(all, (x, y) -> x.label.compareToIgnoreCase(y.label));

        // favorit: paling sering dibuka, sisanya diisi favorit awal
        List<App> fav = new ArrayList<>();
        List<App> used = new ArrayList<>();
        Map<String, Integer> cnt = new HashMap<>();
        for (App a : all) {
            int c = sp.getInt("n_" + a.pkg, 0);
            cnt.put(a.pkg, c);
            if (c > 0) used.add(a);
        }
        Collections.sort(used, (x, y) -> cnt.get(y.pkg) - cnt.get(x.pkg));
        for (App a : used) if (fav.size() < FAV_MAX) fav.add(a);
        for (String d : DEFAULT_FAV) {
            App a = map.get(d);
            if (a != null && !fav.contains(a) && cnt.get(d) >= 0 && fav.size() < FAV_MAX) fav.add(a);
        }

        View focused = getCurrentFocus();
        String focusedTag = focused != null && focused.getTag() instanceof String ? (String) focused.getTag() : null;

        favRow.removeAllViews();
        for (App a : fav) favRow.addView(tile(a, "f:"));
        if (fav.isEmpty()) favRow.addView(text("Belum ada aplikasi", 15, false));

        allBox.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < all.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                allBox.addView(row);
            }
            row.addView(tile(all.get(i), "a:"));
        }

        View restore = focusedTag != null ? root.findViewWithTag(focusedTag) : null;
        if (restore != null) restore.requestFocus();
        else if (favRow.getChildCount() > 0) favRow.getChildAt(0).requestFocus();
    }

    private View tile(App a, String tagPrefix) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClipChildren(false);

        FrameLayout card = new FrameLayout(this);
        card.setBackground(round(0xCC1F2633, 10, 0));
        card.setClipToOutline(true);
        if (a.banner != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(a.banner);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout in = new LinearLayout(this);
            in.setOrientation(LinearLayout.VERTICAL);
            in.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(a.icon);
            in.addView(iv, new LinearLayout.LayoutParams(dp(44), dp(44)));
            TextView t = text(a.label, 12, false);
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(6), dp(4), dp(6), 0);
            in.addView(t);
            card.addView(in, new FrameLayout.LayoutParams(-1, -1));
        }
        box.addView(card, new LinearLayout.LayoutParams(dp(128), dp(72)));

        TextView lbl = text(a.label, 12, false);
        lbl.setSingleLine(true);
        lbl.setGravity(Gravity.CENTER);
        lbl.setAlpha(0f);
        box.addView(lbl, new LinearLayout.LayoutParams(dp(128), -2));

        box.setTag(tagPrefix + a.pkg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(8), dp(10), dp(8), dp(4));
        box.setLayoutParams(lp);

        makeFocusable(box, () -> open(a), () -> options(a));
        box.setOnFocusChangeListener((v, f) -> {
            v.animate().scaleX(f ? 1.12f : 1f).scaleY(f ? 1.12f : 1f).setDuration(120).start();
            card.setBackground(round(0xCC1F2633, 10, f ? Color.WHITE : 0));
            lbl.animate().alpha(f ? 1f : 0f).setDuration(120).start();
            v.setZ(f ? 10 : 0);
        });
        return box;
    }

    private void open(App a) {
        sp.edit().putInt("n_" + a.pkg, sp.getInt("n_" + a.pkg, 0) + 1).apply();
        try { startActivity(a.launch); }
        catch (Exception e) { toastText(a.label + " tidak bisa dibuka"); }
    }

    private void options(App a) {
        new AlertDialog.Builder(this)
                .setTitle(a.label)
                .setItems(new String[]{"Buka", "Info aplikasi", "Copot pemasangan", "Hapus dari favorit"}, (d, w) -> {
                    try {
                        if (w == 0) open(a);
                        else if (w == 1) startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + a.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        else if (w == 2) startActivity(new Intent(Intent.ACTION_DELETE,
                                Uri.parse("package:" + a.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        else { sp.edit().putInt("n_" + a.pkg, -1000).apply(); loadApps(); }
                    } catch (Exception e) { toastText("Tidak bisa"); }
                })
                .show();
    }

    // ============================================================ helper

    private interface Act { void run(); }

    private void makeFocusable(View v, Act click, Act longClick) {
        v.setFocusable(true);
        v.setFocusableInTouchMode(false);
        v.setClickable(true);
        v.setOnClickListener(x -> click.run());
        if (longClick != null) v.setOnLongClickListener(x -> { longClick.run(); return true; });
        v.setOnKeyListener((x, code, e) -> {
            if (longClick != null && code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
                longClick.run();
                return true;
            }
            return false;
        });
    }

    private TextView section(String s) {
        TextView t = text(s, 17, true);
        t.setPadding(dp(8), dp(22), 0, dp(2));
        return t;
    }

    private TextView text(String s, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        t.setShadowLayer(6, 0, 2, 0xAA000000);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable round(int color, float r, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(r));
        g.setColor(color);
        if (stroke != 0) g.setStroke(dp(3), stroke);
        return g;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void toastText(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    @SuppressWarnings("unused")
    private static boolean isSystem(ApplicationInfo ai) { return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0; }
}
