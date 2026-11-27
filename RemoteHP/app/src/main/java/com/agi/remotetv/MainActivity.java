package com.agi.remotetv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

public class MainActivity extends Activity {

    // ---- warna ----
    private static final int BG = 0xFF0E1116, PANEL = 0xFF181C24, KEY = 0xFF262C38, KEY_DN = 0xFF3A4252,
            TXT = 0xFFE5E7EB, DIM = 0xFF9CA3AF, BLUE = 0xFF2563EB, RED = 0xFFB91C1C,
            GREEN = 0xFF22C55E, AMBER = 0xFFF59E0B, ERRC = 0xFFEF4444;

    // ---- kode tombol Android ----
    private static final int K_UP = 19, K_DOWN = 20, K_LEFT = 21, K_RIGHT = 22, K_OK = 23, K_BACK = 4,
            K_HOME = 3, K_MENU = 82, K_POWER = 26, K_INPUT = 178, K_VOLUP = 24, K_VOLDN = 25,
            K_MUTE = 164, K_PLAY = 85, K_DEL = 67, K_ENTER = 66;

    private static final int REQ_APK = 11;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final LinkedBlockingQueue<String> keyQ = new LinkedBlockingQueue<>();
    private final Object connLock = new Object();
    private TvStore store;
    private volatile TvStore.Tv cur;
    private AdbConn conn;
    private volatile boolean installing;

    // ---- halaman ----
    private FrameLayout root;
    private View pageRemote, pageTv, pageScreen, pageInstall, pageMore, pageHome, current;
    private TextView tvChip, statusTxt;
    private LinearLayout typePanel;
    private EditText typeEdit;
    private boolean liveTyping = true, suppressWatcher;
    private TextView typeModeBtn, typeSendBtn;

    private LinearLayout tvList, scanList;
    private TextView scanInfo;
    private volatile boolean scanning;

    private ImageView screenImg;
    private TextView screenInfo;
    private volatile boolean screenOn;
    private volatile int fullW, fullH, sample = 1;

    private Uri apkUri;
    private TextView apkName, installLog;
    private ProgressBar installBar;

    // ================================================================= siklus hidup

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new TvStore(this);
        String last = store.lastIp();
        if (!last.isEmpty()) cur = store.find(last);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        pageRemote = buildRemote();
        pageTv = buildTvPage();
        pageScreen = buildScreenPage();
        pageInstall = buildInstallPage();
        pageMore = buildMorePage();
        pageHome = buildHomePage();
        for (View v : allPages()) {
            v.setVisibility(View.GONE);
            root.addView(v, new FrameLayout.LayoutParams(-1, -1));
        }
        typePanel = buildTypePanel();
        typePanel.setVisibility(View.GONE);
        root.addView(typePanel, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        setContentView(root);

        show(cur == null ? pageTv : pageRemote);
        refreshChip();

        Thread kt = new Thread(this::keyLoop, "keys");
        kt.setDaemon(true);
        kt.start();

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        Uri u = null;
        if (Intent.ACTION_SEND.equals(i.getAction())) u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        else if (Intent.ACTION_VIEW.equals(i.getAction())) u = i.getData();
        if (u != null) {
            setApk(u);
            show(pageInstall);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        if (cur != null) bg.execute(() -> { try { conn(); } catch (Exception e) { status(ERRC, e.getMessage()); } });
        if (current == pageScreen) startScreen();
    }

    @Override
    protected void onStop() {
        super.onStop();
        screenOn = false;
        scanning = false;
        if (!installing) dropConn();
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) hideBars();
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        // hanya status bar yang disembunyikan; tombol navigasi HP (Back/Home/Recent) tetap tampil
        // dan isi aplikasi selalu berada di atasnya, tidak tertutup
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (typePanel.getVisibility() == View.VISIBLE) { closeTyping(); return; }
        if (current != pageRemote && cur != null) { show(pageRemote); return; }
        super.onBackPressed();
    }

    private void show(View page) {
        if (current == pageScreen && page != pageScreen) screenOn = false;
        if (current == pageTv && page != pageTv) scanning = false;
        for (View v : allPages()) v.setVisibility(v == page ? View.VISIBLE : View.GONE);
        current = page;
        if (page == pageTv) refreshTvList();
        if (page == pageScreen) startScreen();
        if (page == pageHome) checkHome();
        if (page != pageRemote) closeTyping();
    }

    private View[] allPages() {
        return new View[]{pageRemote, pageTv, pageScreen, pageInstall, pageMore, pageHome};
    }

    // ================================================================= koneksi

    private AdbConn conn() throws Exception {
        synchronized (connLock) {
            if (conn != null && conn.isAlive()) return conn;
            TvStore.Tv t = cur;
            if (t == null) throw new Exception("Pilih TV dulu.");
            status(DIM, "Menyambung ke " + t.label() + " …");
            AdbConn c = AdbConn.connect(this, t.host(), t.port(),
                    () -> status(AMBER, "Lihat layar TV: centang \"Selalu izinkan\" lalu pilih Izinkan / OK"));
            conn = c;
            status(GREEN, "Tersambung · " + t.label());
            // simpan alamat MAC TV untuk Wake-on-LAN
            try {
                String o = c.shell("cat /sys/class/net/wlan0/address /sys/class/net/eth0/address 2>/dev/null", 5000);
                StringBuilder macs = new StringBuilder();
                for (String line : o.split("\\s+")) {
                    String m = line.trim().toLowerCase(Locale.US);
                    if (m.matches("([0-9a-f]{2}:){5}[0-9a-f]{2}") && !m.equals("00:00:00:00:00:00") && !m.equals("02:00:00:00:00:00"))
                        macs.append(m).append(' ');
                }
                if (macs.length() > 0) store.setExtra("mac_" + t.ip, macs.toString().trim());
            } catch (Exception ignored) { }
            if (t.name == null || t.name.trim().isEmpty()) {
                try {
                    String n = c.shell("settings get global device_name", 5000);
                    if (n.isEmpty() || n.equals("null")) n = c.shell("getprop ro.product.model", 5000);
                    if (!n.isEmpty() && !n.equals("null")) {
                        t.name = n;
                        store.put(t);
                        ui.post(this::refreshChip);
                    }
                } catch (Exception ignored) { }
            }
            return c;
        }
    }

    private void dropConn() {
        new Thread(() -> {
            synchronized (connLock) {
                if (conn != null) conn.closeQuietly();
                conn = null;
            }
        }).start();
    }

    private void selectTv(TvStore.Tv t) {
        cur = t;
        store.setLastIp(t.ip);
        dropConn();
        refreshChip();
        show(pageRemote);
        bg.execute(() -> { try { conn(); } catch (Exception e) { status(ERRC, e.getMessage()); } });
    }

    private void status(int color, String msg) {
        ui.post(() -> {
            if (statusTxt == null) return;
            statusTxt.setText(msg);
            statusTxt.setTextColor(color == DIM ? DIM : color);
            if (tvChip != null) {
                GradientDrawable dot = new GradientDrawable();
                dot.setShape(GradientDrawable.OVAL);
                dot.setColor(color);
                dot.setSize(dp(10), dp(10));
                tvChip.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
            }
        });
    }

    private void refreshChip() {
        if (tvChip != null) tvChip.setText(cur == null ? "Pilih TV  ▾" : cur.label() + "  ▾");
    }

    // ================================================================= antrean tombol

    private void key(int code) { keyQ.add("K" + code); }

    private void text(String s) { if (!s.isEmpty()) keyQ.add("T" + s); }

    private void raw(String cmd) { keyQ.add("C" + cmd); }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void keyLoop() {
        while (true) {
            try {
                String first = keyQ.take();
                String cmd;
                char t = first.charAt(0);
                if (t == 'K') {
                    StringBuilder sb = new StringBuilder("input keyevent ").append(first.substring(1));
                    int n = 1;
                    while (n < 15) {
                        String nx = keyQ.peek();
                        if (nx == null || nx.charAt(0) != 'K') break;
                        keyQ.poll();
                        sb.append(' ').append(nx.substring(1));
                        n++;
                    }
                    cmd = sb.toString();
                } else if (t == 'T') {
                    StringBuilder sb = new StringBuilder(first.substring(1));
                    while (true) {
                        String nx = keyQ.peek();
                        if (nx == null || nx.charAt(0) != 'T') break;
                        keyQ.poll();
                        sb.append(nx.substring(1));
                    }
                    cmd = "input text " + shellQuote(sb.toString().replace(" ", "%s"));
                } else {
                    cmd = first.substring(1);
                }
                runCmd(cmd);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void runCmd(String cmd) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                conn().shell(cmd, 15000);
                return;
            } catch (Exception e) {
                synchronized (connLock) {
                    if (conn != null && !conn.isAlive()) conn = null;
                }
                if (attempt == 1 || cur == null) {
                    keyQ.clear();
                    status(ERRC, e.getMessage());
                }
            }
        }
    }

    // ================================================================= helper tampilan

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private Drawable keyBg(int color, float radiusDp, boolean oval) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, shape(blend(color), radiusDp, oval));
        s.addState(new int[]{}, shape(color, radiusDp, oval));
        return s;
    }

    private GradientDrawable shape(int color, float radiusDp, boolean oval) {
        GradientDrawable g = new GradientDrawable();
        if (oval) g.setShape(GradientDrawable.OVAL);
        else g.setCornerRadius(dp(radiusDp));
        g.setColor(color);
        return g;
    }

    private static int blend(int c) {
        if (c == KEY) return KEY_DN;
        int r = Math.min(255, Color.red(c) + 40), g = Math.min(255, Color.green(c) + 40), b = Math.min(255, Color.blue(c) + 40);
        return Color.rgb(r, g, b);
    }

    private TextView label(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Tombol remote: kirim keycode saat ditekan; repeat = tahan untuk berulang. */
    private TextView keyBtn(String text, int code, int color, boolean repeat, float sp, boolean oval) {
        TextView b = label(text, sp, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(keyBg(color, 18, oval));
        final Runnable[] rep = new Runnable[1];
        rep[0] = () -> { key(code); ui.postDelayed(rep[0], 110); };
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    key(code);
                    if (repeat) ui.postDelayed(rep[0], 450);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    ui.removeCallbacks(rep[0]);
                    return true;
            }
            return true;
        });
        return b;
    }

    private TextView actionBtn(String text, int color, View.OnClickListener l) {
        TextView b = label(text, 16, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(12), dp(14), dp(12));
        b.setBackground(keyBg(color, 14, false));
        b.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); l.onClick(v); });
        return b;
    }

    private LinearLayout row(int heightDp, View... cells) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View c : cells) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, heightDp > 0 ? dp(heightDp) : -1, 1f);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            r.addView(c, lp);
        }
        return r;
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private View space(float weight) {
        Space s = new Space(this);
        s.setLayoutParams(new LinearLayout.LayoutParams(1, 0, weight));
        return s;
    }

    private LinearLayout header(String title) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = actionBtn("←", KEY, v -> { if (cur != null) show(pageRemote); else finish(); });
        h.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));
        TextView t = label(title, 20, TXT, true);
        t.setPadding(dp(14), 0, 0, 0);
        h.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        return h;
    }

    // ================================================================= halaman REMOTE

    private View buildRemote() {
        LinearLayout p = vbox();
        p.setPadding(dp(14), dp(14), dp(14), dp(14));

        // baris atas: pilih TV + Layar + APK
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        tvChip = label("Pilih TV  ▾", 17, TXT, true);
        tvChip.setSingleLine(true);
        tvChip.setCompoundDrawablePadding(dp(10));
        tvChip.setPadding(dp(14), dp(12), dp(14), dp(12));
        tvChip.setBackground(keyBg(PANEL, 24, false));
        tvChip.setOnClickListener(v -> show(pageTv));
        top.addView(tvChip, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView bScreen = actionBtn("Layar", KEY, v -> show(pageScreen));
        TextView bApk = actionBtn("Lainnya", KEY, v -> show(pageMore));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(-2, -2);
        lp1.leftMargin = dp(8);
        top.addView(bScreen, lp1);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-2, -2);
        lp2.leftMargin = dp(8);
        top.addView(bApk, lp2);
        p.addView(top);

        statusTxt = label("", 13, DIM, false);
        statusTxt.setPadding(dp(6), dp(8), dp(6), 0);
        statusTxt.setMaxLines(2);
        p.addView(statusTxt);

        p.addView(space(1));

        // Power + Input
        TextView power = actionBtn("⏻", RED, v -> powerPress());
        power.setBackground(keyBg(RED, 0, true));
        power.setTextSize(24);
        power.setPadding(0, 0, 0, 0);
        TextView input = keyBtn("⇄ Input", K_INPUT, KEY, false, 16, false);
        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        r1.addView(power, new LinearLayout.LayoutParams(dp(64), dp(64)));
        r1.addView(space(1), new LinearLayout.LayoutParams(0, 1, 1f));
        r1.addView(input, new LinearLayout.LayoutParams(dp(130), dp(56)));
        LinearLayout.LayoutParams r1lp = new LinearLayout.LayoutParams(-1, -2);
        r1lp.setMargins(dp(5), 0, dp(5), dp(6));
        p.addView(r1, r1lp);

        // D-pad dalam lingkaran
        int w = getResources().getDisplayMetrics().widthPixels - dp(28);
        // d-pad menyesuaikan tinggi layar supaya baris tombol paling bawah tidak terpotong
        int hAvail = getResources().getDisplayMetrics().heightPixels - dp(490);
        int pad = Math.max(dp(200), Math.min(w, Math.min(dp(300), hAvail)));
        int cell = pad / 3;
        FrameLayout ring = new FrameLayout(this);
        ring.setBackground(shape(PANEL, 0, true));
        LinearLayout grid = vbox();
        grid.addView(dpadRow(cell, null, keyBtn("▲", K_UP, PANEL, true, 26, true), null));
        grid.addView(dpadRow(cell, keyBtn("◀", K_LEFT, PANEL, true, 26, true),
                keyBtn("OK", K_OK, BLUE, false, 20, true), keyBtn("▶", K_RIGHT, PANEL, true, 26, true)));
        grid.addView(dpadRow(cell, null, keyBtn("▼", K_DOWN, PANEL, true, 26, true), null));
        ring.addView(grid, new FrameLayout.LayoutParams(cell * 3, cell * 3, Gravity.CENTER));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(cell * 3, cell * 3);
        rlp.gravity = Gravity.CENTER_HORIZONTAL;
        p.addView(ring, rlp);

        p.addView(space(1));

        p.addView(row(58, keyBtn("↩ Back", K_BACK, KEY, false, 16, false),
                keyBtn("⌂ Home", K_HOME, KEY, false, 16, false),
                keyBtn("☰ Menu", K_MENU, KEY, false, 16, false)));
        p.addView(row(58, keyBtn("🔉 −", K_VOLDN, KEY, true, 18, false),
                keyBtn("🔇", K_MUTE, KEY, false, 18, false),
                keyBtn("🔊 +", K_VOLUP, KEY, true, 18, false)));
        TextView kb = actionBtn("⌨ Ketik", BLUE, v -> openTyping());
        p.addView(row(58, keyBtn("⏯", K_PLAY, KEY, false, 20, false),
                keyBtn("⌫", K_DEL, KEY, true, 20, false), kb));
        p.addView(row(50, appBtn("YouTube", "com.google.android.youtube.tv", 0xFFC4302B),
                appBtn("Netflix", "com.netflix.ninja", 0xFF141414),
                appBtn("Disney+", "com.disney.disneyplus", 0xFF113CCF),
                appBtn("Spotify", "com.spotify.tv.android", 0xFF1DB954)));
        return p;
    }

    private LinearLayout dpadRow(int cell, View a, View b, View c) {
        LinearLayout r = new LinearLayout(this);
        View[] v = {a, b, c};
        for (View x : v) {
            View cellView = x != null ? x : new Space(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell, cell);
            int m = dp(6);
            lp.setMargins(m, m, m, m);
            lp.width = cell - 2 * m;
            lp.height = cell - 2 * m;
            r.addView(cellView, lp);
        }
        return r;
    }

    // ---- panel ketik ----

    private LinearLayout buildTypePanel() {
        LinearLayout p = vbox();
        p.setBackground(shape(PANEL, 0, false));
        p.setPadding(dp(12), dp(14), dp(12), dp(12));
        p.setElevation(dp(8));
        p.setClickable(true);

        TextView hint = label("Teks dikirim ke kolom yang sedang aktif di TV", 13, DIM, false);
        p.addView(hint);

        typeEdit = new EditText(this);
        typeEdit.setTextColor(TXT);
        typeEdit.setHintTextColor(DIM);
        typeEdit.setHint("Ketik di sini…");
        typeEdit.setTextSize(18);
        typeEdit.setSingleLine(true);
        // tanpa saran/koreksi otomatis supaya huruf yang dikirim sama persis
        typeEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        typeEdit.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        typeEdit.setOnEditorActionListener((v, id, ev) -> {
            if (liveTyping) key(K_ENTER);
            else sendTyped(true);
            return true;
        });
        typeEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!liveTyping || suppressWatcher) return;
                for (int i = 0; i < before; i++) key(K_DEL);
                if (count > 0) text(s.subSequence(start, start + count).toString());
            }
            @Override public void afterTextChanged(Editable e) { }
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.topMargin = dp(6);
        p.addView(typeEdit, elp);

        typeModeBtn = actionBtn("", KEY, v -> {
            liveTyping = !liveTyping;
            refreshTypeMode();
        });
        typeSendBtn = actionBtn("Kirim", BLUE, v -> sendTyped(false));
        TextView enter = actionBtn("↵ Enter", KEY, v -> key(K_ENTER));
        TextView close = actionBtn("Tutup", KEY, v -> closeTyping());
        p.addView(row(50, typeModeBtn, typeSendBtn, enter, close));
        refreshTypeMode();
        return p;
    }

    private void refreshTypeMode() {
        typeModeBtn.setText(liveTyping ? "Langsung" : "Sekaligus");
        typeSendBtn.setVisibility(liveTyping ? View.GONE : View.VISIBLE);
    }

    private void sendTyped(boolean thenEnter) {
        String s = typeEdit.getText().toString();
        text(s);
        if (thenEnter) key(K_ENTER);
        suppressWatcher = true;
        typeEdit.setText("");
        suppressWatcher = false;
    }

    private void openTyping() {
        suppressWatcher = true;
        typeEdit.setText("");
        suppressWatcher = false;
        typePanel.setVisibility(View.VISIBLE);
        typeEdit.requestFocus();
        ui.postDelayed(() -> {
            InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(typeEdit, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    private void closeTyping() {
        if (typePanel == null || typePanel.getVisibility() != View.VISIBLE) return;
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(typeEdit.getWindowToken(), 0);
        typePanel.setVisibility(View.GONE);
        hideBars();
    }

    // ================================================================= halaman DAFTAR TV

    private View buildTvPage() {
        ScrollView sv = new ScrollView(this);
        LinearLayout p = vbox();
        p.setPadding(dp(14), dp(14), dp(14), dp(24));
        sv.addView(p);

        p.addView(header("Pilih TV"));

        tvList = vbox();
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = dp(12);
        p.addView(tvList, tl);

        LinearLayout btns = row(54,
                actionBtn("＋ IP manual", KEY, v -> editTvDialog(null, "")),
                actionBtn("Cari TV di WiFi", BLUE, v -> startScan()));
        p.addView(btns);

        scanInfo = label("", 13, DIM, false);
        scanInfo.setPadding(dp(6), dp(6), dp(6), dp(6));
        p.addView(scanInfo);
        scanList = vbox();
        p.addView(scanList);

        TextView help = label("Syarat di TV (sekali saja):\n"
                + "1. Setelan → Sistem → Tentang → klik \"Build Android TV OS\" 7× sampai jadi pengembang.\n"
                + "2. Setelan → Sistem → Opsi pengembang → nyalakan \"USB debugging\" (dan \"Debugging jaringan/ADB\" bila ada).\n"
                + "3. HP dan TV harus di WiFi yang sama.\n"
                + "4. Saat pertama sambung, TV bertanya \"Izinkan debugging?\" → centang Selalu izinkan → OK.\n\n"
                + "Tekan lama nama TV untuk ganti nama atau hapus.", 13, DIM, false);
        help.setPadding(dp(6), dp(18), dp(6), 0);
        help.setLineSpacing(dp(2), 1f);
        p.addView(help);
        return sv;
    }

    private void refreshTvList() {
        tvList.removeAllViews();
        List<TvStore.Tv> l = store.list();
        if (l.isEmpty()) {
            TextView e = label("Belum ada TV tersimpan. Tekan \"Cari TV di WiFi\" atau isi IP manual.", 14, DIM, false);
            e.setPadding(dp(6), dp(6), dp(6), dp(12));
            tvList.addView(e);
        }
        for (TvStore.Tv t : l) {
            boolean active = cur != null && cur.ip.equals(t.ip);
            LinearLayout r = vbox();
            r.setPadding(dp(16), dp(12), dp(16), dp(12));
            r.setBackground(keyBg(active ? 0xFF1E3A8A : PANEL, 14, false));
            r.addView(label((active ? "● " : "") + t.label(), 17, TXT, true));
            r.addView(label(t.ip, 13, DIM, false));
            r.setOnClickListener(v -> selectTv(t));
            r.setOnLongClickListener(v -> { tvOptions(t); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            tvList.addView(r, lp);
        }
    }

    private void tvOptions(TvStore.Tv t) {
        new AlertDialog.Builder(this)
                .setTitle(t.label())
                .setItems(new String[]{"Ganti nama / IP", "Hapus dari daftar"}, (d, w) -> {
                    if (w == 0) editTvDialog(t, t.ip);
                    else {
                        store.remove(t.ip);
                        if (cur != null && cur.ip.equals(t.ip)) { cur = null; dropConn(); refreshChip(); }
                        refreshTvList();
                    }
                })
                .show();
    }

    private void editTvDialog(TvStore.Tv existing, String ip) {
        LinearLayout box = vbox();
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText name = new EditText(this);
        name.setHint("Nama (mis. TV CCTV)");
        name.setSingleLine(true);
        EditText ipEd = new EditText(this);
        ipEd.setHint("IP, mis. 192.168.68.50");
        ipEd.setSingleLine(true);
        ipEd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (existing != null) name.setText(existing.name);
        ipEd.setText(ip);
        box.addView(name);
        box.addView(ipEd);
        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Tambah TV" : "Ubah TV")
                .setView(box)
                .setPositiveButton("Simpan & sambung", (d, w) -> {
                    String nip = ipEd.getText().toString().trim();
                    if (nip.isEmpty()) return;
                    if (existing != null && !existing.ip.equals(nip)) store.remove(existing.ip);
                    TvStore.Tv t = new TvStore.Tv(name.getText().toString().trim(), nip);
                    store.put(t);
                    selectTv(t);
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void startScan() {
        if (scanning) return;
        Scanner.Net net = Scanner.myNet();
        scanList.removeAllViews();
        if (net == null) {
            scanInfo.setText("HP belum tersambung ke WiFi.");
            return;
        }
        scanning = true;
        scanInfo.setText("Mencari di " + Scanner.str(net.base) + "/" + net.prefix + " …");
        new Thread(() -> {
            List<String> found = Scanner.scan(net, new Scanner.Listener() {
                @Override public void onFound(String ip) { ui.post(() -> addScanRow(ip)); }
                @Override public void onProgress(int done, int total) {
                    if (done % 16 == 0 || done == total)
                        ui.post(() -> scanInfo.setText("Mencari… " + done + "/" + total));
                }
            });
            scanning = false;
            ui.post(() -> scanInfo.setText(found.isEmpty()
                    ? "Tidak ada TV dengan ADB aktif di WiFi ini. Cek langkah di bawah, atau isi IP manual."
                    : "Ditemukan " + found.size() + " perangkat. Tekan untuk menambahkan."));
        }).start();
    }

    private void addScanRow(String ip) {
        TvStore.Tv known = store.find(ip);
        TextView r = actionBtn((known != null ? "✓ " + known.label() + " · " : "＋ ") + ip, KEY, v -> {
            TvStore.Tv k = store.find(ip);
            if (k != null) selectTv(k);
            else editTvDialog(null, ip);
        });
        r.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(5), dp(4), dp(5), dp(4));
        scanList.addView(r, lp);
    }

    // ================================================================= halaman LIHAT LAYAR

    private View buildScreenPage() {
        LinearLayout p = vbox();
        p.setPadding(dp(14), dp(14), dp(14), dp(14));
        p.addView(header("Layar TV"));

        screenInfo = label("", 13, DIM, false);
        screenInfo.setPadding(dp(6), dp(8), dp(6), dp(8));
        p.addView(screenInfo);

        screenImg = new ImageView(this);
        screenImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        screenImg.setBackgroundColor(Color.BLACK);
        final float[] down = new float[2];
        screenImg.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                down[0] = e.getX(); down[1] = e.getY();
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                int[] a = mapToTv(down[0], down[1]);
                int[] b = mapToTv(e.getX(), e.getY());
                if (a != null && b != null) {
                    float dist = Math.abs(e.getX() - down[0]) + Math.abs(e.getY() - down[1]);
                    if (dist < dp(16)) raw("input tap " + a[0] + " " + a[1]);
                    else raw("input swipe " + a[0] + " " + a[1] + " " + b[0] + " " + b[1] + " 250");
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
            }
            return true;
        });
        p.addView(screenImg, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView hint = label("Ketuk gambar = ketuk di TV · geser = usap", 12, DIM, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(6), 0, dp(4));
        p.addView(hint);

        p.addView(row(54, keyBtn("↩", K_BACK, KEY, false, 18, false),
                keyBtn("▲", K_UP, KEY, true, 18, false),
                keyBtn("⌂", K_HOME, KEY, false, 18, false)));
        p.addView(row(54, keyBtn("◀", K_LEFT, KEY, true, 18, false),
                keyBtn("OK", K_OK, BLUE, false, 18, false),
                keyBtn("▶", K_RIGHT, KEY, true, 18, false)));
        p.addView(row(54, keyBtn("🔉", K_VOLDN, KEY, true, 18, false),
                keyBtn("▼", K_DOWN, KEY, true, 18, false),
                keyBtn("🔊", K_VOLUP, KEY, true, 18, false)));
        return p;
    }

    private int[] mapToTv(float x, float y) {
        Drawable d = screenImg.getDrawable();
        if (d == null || fullW <= 0) return null;
        Matrix inv = new Matrix();
        if (!screenImg.getImageMatrix().invert(inv)) return null;
        float[] pt = {x, y};
        inv.mapPoints(pt);
        float sx = (float) fullW / d.getIntrinsicWidth(), sy = (float) fullH / d.getIntrinsicHeight();
        int tx = Math.round(pt[0] * sx), ty = Math.round(pt[1] * sy);
        if (tx < 0 || ty < 0 || tx >= fullW || ty >= fullH) return null;
        return new int[]{tx, ty};
    }

    private void startScreen() {
        if (screenOn) return;
        screenOn = true;
        screenInfo.setText("Mengambil gambar layar TV…");
        Thread t = new Thread(() -> {
            long lastT = System.currentTimeMillis();
            while (screenOn) {
                try {
                    byte[] png = conn().exec("screencap -p", 20000);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(png, 0, png.length, o);
                    if (o.outWidth <= 0) throw new Exception("Gambar layar tidak terbaca (" + png.length + " byte)");
                    int s = o.outWidth > 1280 ? 2 : 1;
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = s;
                    Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length, o2);
                    final int w = o.outWidth, h = o.outHeight;
                    long now = System.currentTimeMillis();
                    final float sec = (now - lastT) / 1000f;
                    lastT = now;
                    if (!screenOn) break;
                    ui.post(() -> {
                        fullW = w; fullH = h; sample = s;
                        screenImg.setImageBitmap(bmp);
                        screenInfo.setText(String.format(Locale.US, "%d×%d · diperbarui tiap %.1f detik", w, h, sec));
                    });
                } catch (Exception e) {
                    synchronized (connLock) { if (conn != null && !conn.isAlive()) conn = null; }
                    final String m = e.getMessage();
                    ui.post(() -> screenInfo.setText("✖ " + m));
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                }
            }
        }, "screen");
        t.setDaemon(true);
        t.start();
    }

    // ================================================================= halaman PASANG APK

    private View buildInstallPage() {
        ScrollView sv = new ScrollView(this);
        LinearLayout p = vbox();
        p.setPadding(dp(14), dp(14), dp(14), dp(24));
        sv.addView(p);
        p.addView(header("Pasang aplikasi ke TV"));

        TextView info = label("Pilih file APK apa saja di HP, lalu pasang ke TV yang sedang tersambung. "
                + "Bisa juga dari File Manager / WhatsApp: Bagikan → Remote TV.", 14, DIM, false);
        info.setPadding(dp(6), dp(14), dp(6), dp(14));
        p.addView(info);

        p.addView(row(56, actionBtn("Pilih file APK", KEY, v -> pickApk())));

        apkName = label("Belum ada file dipilih.", 15, TXT, false);
        apkName.setPadding(dp(6), dp(10), dp(6), dp(10));
        p.addView(apkName);

        p.addView(row(56, actionBtn("Pasang ke TV", BLUE, v -> doInstall())));

        installBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        installBar.setMax(1000);
        installBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(14));
        bl.setMargins(dp(6), dp(12), dp(6), dp(6));
        p.addView(installBar, bl);

        installLog = label("", 14, TXT, false);
        installLog.setPadding(dp(6), dp(6), dp(6), dp(6));
        installLog.setLineSpacing(dp(2), 1f);
        p.addView(installLog);
        return sv;
    }

    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/vnd.android.package-archive", "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_APK);
        } catch (Exception e) {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.setType("*/*");
            startActivityForResult(g, REQ_APK);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_APK && res == RESULT_OK && data != null && data.getData() != null) setApk(data.getData());
        if (req == REQ_WALL && res == RESULT_OK && data != null && data.getData() != null) openWallEditor(data.getData());
    }

    private String[] nameSize(Uri u) {
        String name = u.getLastPathSegment();
        long size = -1;
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception ignored) { }
        return new String[]{name == null ? "file.apk" : name, String.valueOf(size)};
    }

    private void setApk(Uri u) {
        apkUri = u;
        String[] ns = nameSize(u);
        long size = Long.parseLong(ns[1]);
        apkName.setText(ns[0] + (size > 0 ? String.format(Locale.US, "  (%.1f MB)", size / 1048576f) : ""));
        installLog.setText("");
        installBar.setVisibility(View.GONE);
    }

    private void log(String s) {
        ui.post(() -> installLog.append(s + "\n"));
    }

    private void doInstall() {
        if (apkUri == null) { pickApk(); return; }
        if (installing) return;
        if (cur == null) { show(pageTv); return; }
        installing = true;
        installLog.setText("");
        installBar.setProgress(0);
        installBar.setVisibility(View.VISIBLE);
        final Uri u = apkUri;
        bg.execute(() -> {
            File tmp = null;
            try {
                AdbConn c = conn();
                String[] ns = nameSize(u);
                long size = Long.parseLong(ns[1]);
                if (size <= 0) {
                    // ukuran tidak diketahui: salin dulu ke cache
                    tmp = new File(getCacheDir(), "kirim.apk");
                    try (InputStream is = getContentResolver().openInputStream(u);
                         FileOutputStream fo = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = is.read(buf)) > 0) fo.write(buf, 0, n);
                    }
                    size = tmp.length();
                }
                final long total = size;
                log(String.format(Locale.US, "Mengirim %s (%.1f MB) ke %s …", ns[0], total / 1048576f, cur.label()));
                AdbConn.Progress pr = (d, t) -> ui.post(() -> installBar.setProgress((int) (d * 1000 / Math.max(1, t))));
                String out;
                try (InputStream is = tmp != null ? new FileInputStream(tmp) : getContentResolver().openInputStream(u)) {
                    out = c.installStream(is, total, pr);
                }
                if (!out.contains("Success") && !out.contains("Failure") ) {
                    log("Mencoba cara lain (TV versi lama)…");
                    try (InputStream is = tmp != null ? new FileInputStream(tmp) : getContentResolver().openInputStream(u)) {
                        c.push(is, total, "/data/local/tmp/remotetv_kirim.apk", pr);
                    }
                    log("Memasang…");
                    out = c.shell("pm install -r /data/local/tmp/remotetv_kirim.apk", 240000);
                    c.shell("rm -f /data/local/tmp/remotetv_kirim.apk", 10000);
                }
                if (out.contains("Success")) {
                    log("✔ Berhasil dipasang di TV.");
                } else {
                    log("✖ Gagal: " + out);
                    if (out.contains("UPDATE_INCOMPATIBLE"))
                        log("→ Versi lama di TV ditandatangani beda. Hapus dulu aplikasinya di TV, lalu pasang lagi.");
                    else if (out.contains("NO_MATCHING_ABIS"))
                        log("→ APK ini tidak cocok untuk prosesor TV. Cari versi armeabi-v7a / arm64.");
                    else if (out.contains("VERSION_DOWNGRADE"))
                        log("→ Di TV sudah ada versi yang lebih baru.");
                    else if (out.contains("INSUFFICIENT_STORAGE"))
                        log("→ Memori TV penuh.");
                }
            } catch (Exception e) {
                log("✖ " + e.getMessage());
            } finally {
                if (tmp != null) //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                installing = false;
                ui.post(() -> installBar.setProgress(1000));
            }
        });
    }

    // ================================================================= tombol power + Wake-on-LAN

    private void powerPress() {
        bg.execute(() -> {
            TvStore.Tv t = cur;
            if (t == null) { status(ERRC, "Pilih TV dulu."); return; }
            boolean ok;
            synchronized (connLock) { ok = conn != null && conn.isAlive(); }
            if (!ok) {
                try { conn(); ok = true; } catch (Exception ignored) { }
            }
            if (ok) { key(K_POWER); return; }
            String mac = store.getExtra("mac_" + t.ip);
            if (mac.isEmpty()) {
                status(ERRC, "TV tidak terjangkau (mati/standby). Wake-on-LAN belum siap: sambungkan HP sekali saat TV menyala supaya alamat TV tersimpan.");
                return;
            }
            try {
                for (String m : mac.split(" ")) sendWol(m, t.host());
                status(AMBER, "Sinyal bangun (Wake-on-LAN) dikirim. Bila TV mendukung, TV menyala dalam beberapa detik…");
                Thread.sleep(6000);
                conn();
            } catch (Exception e) {
                status(ERRC, "TV belum menyala. Pastikan \"Quick start / Standby jaringan\" aktif di setelan daya TV.");
            }
        });
    }

    private static void sendWol(String mac, String host) throws Exception {
        String[] hx = mac.split(":");
        byte[] m = new byte[6];
        for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(hx[i], 16);
        byte[] pkt = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) pkt[i] = (byte) 0xff;
        for (int i = 6; i < pkt.length; i += 6) System.arraycopy(m, 0, pkt, i, 6);
        String[] parts = host.split("\\.");
        String sub = parts.length == 4 ? parts[0] + "." + parts[1] + "." + parts[2] + ".255" : null;
        Scanner.Net net = Scanner.myNet();
        String netB = net != null ? Scanner.str(net.base | ((1 << (32 - net.prefix)) - 1)) : null;
        try (java.net.DatagramSocket ds = new java.net.DatagramSocket()) {
            ds.setBroadcast(true);
            for (String dst : new String[]{"255.255.255.255", netB, sub, host}) {
                if (dst == null) continue;
                for (int port : new int[]{9, 7}) {
                    try {
                        ds.send(new java.net.DatagramPacket(pkt, pkt.length, java.net.InetAddress.getByName(dst), port));
                    } catch (Exception ignored) { }
                }
            }
        }
    }

    // ================================================================= shortcut aplikasi

    private TextView appBtn(String name, String pkg, int color) {
        TextView b = actionBtn(name, color, v -> openTvApp(name, pkg));
        b.setTextSize(13);
        b.setPadding(dp(4), dp(8), dp(4), dp(8));
        b.setSingleLine(true);
        return b;
    }

    private void openTvApp(String name, String pkg) {
        bg.execute(() -> {
            try {
                AdbConn c = conn();
                String path = c.shell("pm path " + pkg, 8000);
                if (!path.contains("package:")) { status(AMBER, name + " belum terpasang di TV ini."); return; }
                String out = c.shell("monkey -p " + pkg + " -c android.intent.category.LEANBACK_LAUNCHER 1", 15000);
                if (out.contains("No activities")) c.shell("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1", 15000);
                status(GREEN, "Membuka " + name + " di " + cur.label());
            } catch (Exception e) {
                status(ERRC, e.getMessage());
            }
        });
    }

    // ================================================================= halaman LAINNYA

    private View buildMorePage() {
        LinearLayout p = vbox();
        p.setPadding(dp(14), dp(14), dp(14), dp(14));
        p.addView(header("Lainnya"));
        View gap = new View(this);
        p.addView(gap, new LinearLayout.LayoutParams(1, dp(12)));
        p.addView(menuItem("Pasang aplikasi ke TV", "Kirim file APK dari HP lalu pasang", () -> show(pageInstall)));
        p.addView(menuItem("Beranda TV", "Layar utama TV sendiri: wallpaper foto/video, tanpa iklan", () -> show(pageHome)));
        p.addView(menuItem("Pilih / cari TV", "Daftar TV, cari di WiFi, IP manual", () -> show(pageTv)));
        return p;
    }

    private View menuItem(String title, String sub, Runnable r) {
        LinearLayout b = vbox();
        b.setPadding(dp(18), dp(16), dp(18), dp(16));
        b.setBackground(keyBg(PANEL, 14, false));
        b.addView(label(title, 18, TXT, true));
        b.addView(label(sub, 13, DIM, false));
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(5), dp(6), dp(5), dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    // ================================================================= halaman BERANDA TV

    private static final String HOME_PKG = "com.agi.berandatv";
    private static final String HOME_CMP = HOME_PKG + "/.HomeActivity";
    /** Beranda bawaan Google TV / Android TV yang dikenal. */
    private static final String[] STOCK_HOMES = {
            "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher", "com.google.android.tungsten.setupwraith"};
    private static final int REQ_WALL = 12;

    private TextView homeState, homeLog;
    private ProgressBar homeBar;
    private volatile boolean homeBusy;

    private View buildHomePage() {
        ScrollView sv = new ScrollView(this);
        LinearLayout p = vbox();
        p.setPadding(dp(14), dp(14), dp(14), dp(24));
        sv.addView(p);
        p.addView(header("Beranda TV"));

        homeState = label("Memeriksa TV…", 14, DIM, false);
        homeState.setPadding(dp(6), dp(12), dp(6), dp(10));
        p.addView(homeState);

        p.addView(row(56, actionBtn("1. Pasang / perbarui Beranda TV", KEY, v -> homeInstall())));
        p.addView(row(56, actionBtn("2. Jadikan layar utama TV", BLUE, v -> homeSet(false))));
        p.addView(row(56, actionBtn("Ganti wallpaper (foto / video MP4)", KEY, v -> pickWall())));
        p.addView(row(56, actionBtn("Kembalikan beranda bawaan", KEY, v -> homeRestore())));

        homeBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        homeBar.setMax(1000);
        homeBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(14));
        bl.setMargins(dp(6), dp(12), dp(6), dp(6));
        p.addView(homeBar, bl);

        homeLog = label("", 14, TXT, false);
        homeLog.setPadding(dp(6), dp(6), dp(6), dp(6));
        homeLog.setLineSpacing(dp(2), 1f);
        p.addView(homeLog);

        TextView adv = label("Kalau tombol Home di remote TV masih ke beranda Google:", 13, DIM, false);
        adv.setPadding(dp(6), dp(18), dp(6), 0);
        p.addView(adv);
        p.addView(row(52, actionBtn("Mode kuat (matikan beranda Google)", 0xFF7C2D12, v ->
                new AlertDialog.Builder(this)
                        .setTitle("Mode kuat")
                        .setMessage("Beranda Google di TV akan dinonaktifkan (tidak dihapus) supaya tombol Home selalu ke Beranda TV. "
                                + "Bisa dikembalikan kapan saja dengan tombol \"Kembalikan beranda bawaan\". Lanjut?")
                        .setPositiveButton("Lanjut", (d, w) -> homeSet(true))
                        .setNegativeButton("Batal", null)
                        .show())));

        TextView help = label("Video wallpaper: MP4 pendek (5–30 detik) diputar berulang tanpa suara, "
                + "dan berhenti otomatis saat aplikasi lain dibuka. Foto diperkecil otomatis ke ukuran layar TV.\n"
                + "Di TV: tekan lama OK (atau tombol Menu) pada ikon untuk Info / Copot / Hapus dari favorit. "
                + "Favorit terisi sendiri dari aplikasi yang paling sering dibuka.", 13, DIM, false);
        help.setPadding(dp(6), dp(18), dp(6), 0);
        help.setLineSpacing(dp(2), 1f);
        p.addView(help);
        return sv;
    }

    private void hlog(String s) { ui.post(() -> homeLog.append(s + "\n")); }

    private void homeStart() {
        homeBusy = true;
        ui.post(() -> { homeLog.setText(""); homeBar.setVisibility(View.GONE); });
    }

    private String currentHome(AdbConn c) throws Exception {
        String o = c.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME", 10000);
        String last = "";
        for (String line : o.split("\n")) if (line.trim().contains("/")) last = line.trim();
        return last;
    }

    private void checkHome() {
        if (cur == null) { homeState.setText("Pilih TV dulu."); return; }
        homeState.setText("Memeriksa " + cur.label() + " …");
        bg.execute(() -> {
            try {
                AdbConn c = conn();
                boolean inst = c.shell("pm path " + HOME_PKG, 8000).contains("package:");
                String home = inst ? currentHome(c) : "";
                String msg = !inst ? "Beranda TV belum terpasang di " + cur.label() + "."
                        : home.startsWith(HOME_PKG) ? "✔ Beranda TV terpasang dan jadi layar utama."
                        : "Beranda TV sudah terpasang, tapi layar utama masih: " + (home.isEmpty() ? "(tidak diketahui)" : home);
                ui.post(() -> homeState.setText(msg));
            } catch (Exception e) {
                ui.post(() -> homeState.setText("✖ " + e.getMessage()));
            }
        });
    }

    private void homeInstall() {
        if (homeBusy) return;
        homeStart();
        bg.execute(() -> {
            File tmp = new File(getCacheDir(), "beranda.apk");
            try {
                try (InputStream is = getAssets().open("beranda.apk"); FileOutputStream fo = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = is.read(buf)) > 0) fo.write(buf, 0, n);
                }
                AdbConn c = conn();
                hlog("Mengirim Beranda TV ke " + cur.label() + " …");
                ui.post(() -> { homeBar.setProgress(0); homeBar.setVisibility(View.VISIBLE); });
                String out;
                try (InputStream is = new FileInputStream(tmp)) {
                    out = c.installStream(is, tmp.length(), (d, t) -> ui.post(() -> homeBar.setProgress((int) (d * 1000 / Math.max(1, t)))));
                }
                if (!out.contains("Success") && !out.contains("Failure")) {
                    try (InputStream is = new FileInputStream(tmp)) { c.push(is, tmp.length(), "/data/local/tmp/beranda.apk", null); }
                    out = c.shell("pm install -r /data/local/tmp/beranda.apk", 240000);
                    c.shell("rm -f /data/local/tmp/beranda.apk", 10000);
                }
                if (out.contains("Success")) hlog("✔ Beranda TV terpasang. Lanjut langkah 2.");
                else hlog("✖ Gagal: " + out);
            } catch (Exception e) {
                hlog("✖ " + e.getMessage());
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                homeBusy = false;
                ui.post(this::checkHome);
            }
        });
    }

    private void homeSet(boolean strong) {
        if (homeBusy) return;
        homeStart();
        bg.execute(() -> {
            try {
                AdbConn c = conn();
                if (!c.shell("pm path " + HOME_PKG, 8000).contains("package:")) {
                    hlog("Beranda TV belum terpasang. Tekan langkah 1 dulu.");
                    return;
                }
                if (strong) {
                    StringBuilder disabled = new StringBuilder();
                    for (String pkg : STOCK_HOMES) {
                        if (!c.shell("pm path " + pkg, 8000).contains("package:")) continue;
                        String o = c.shell("pm disable-user --user 0 " + pkg, 15000);
                        hlog("Nonaktifkan " + pkg + ": " + (o.contains("disabled") ? "OK" : o));
                        disabled.append(pkg).append(' ');
                    }
                    store.setExtra("disabled_" + cur.ip, disabled.toString().trim());
                }
                String o = c.shell("cmd package set-home-activity --user 0 " + HOME_CMP, 15000);
                if (o.toLowerCase(Locale.US).contains("error") || o.toLowerCase(Locale.US).contains("unknown"))
                    o = c.shell("cmd package set-home-activity " + HOME_CMP, 15000);
                hlog("Jadikan layar utama: " + (o.isEmpty() ? "OK" : o));
                c.shell("input keyevent 3", 8000);
                String home = currentHome(c);
                if (home.startsWith(HOME_PKG)) hlog("✔ Selesai. Tekan Home di remote untuk mencoba.");
                else hlog("Layar utama masih " + home + ". Coba \"Mode kuat\" di bawah.");
            } catch (Exception e) {
                hlog("✖ " + e.getMessage());
            } finally {
                homeBusy = false;
                ui.post(this::checkHome);
            }
        });
    }

    private void homeRestore() {
        if (homeBusy) return;
        homeStart();
        bg.execute(() -> {
            try {
                AdbConn c = conn();
                String saved = store.getExtra("disabled_" + cur.ip);
                java.util.LinkedHashSet<String> pk = new java.util.LinkedHashSet<>();
                for (String s : saved.split(" ")) if (!s.isEmpty()) pk.add(s);
                for (String s : STOCK_HOMES) pk.add(s);
                for (String s : pk) {
                    if (!c.shell("pm path " + s, 8000).contains("package:")) continue;
                    c.shell("pm enable " + s, 15000);
                }
                store.setExtra("disabled_" + cur.ip, "");
                // cari beranda bawaan yang tersedia
                String q = c.shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME", 10000);
                String stock = "";
                for (String line : q.split("\n")) {
                    String l = line.trim();
                    if (!l.contains("/") || l.startsWith(HOME_PKG) || l.contains("FallbackHome") || l.contains("setupwraith")) continue;
                    if (stock.isEmpty() || l.contains("launcherx")) stock = l;
                }
                if (stock.isEmpty()) {
                    hlog("Beranda bawaan tidak ditemukan. Hapus Beranda TV dari Info aplikasi di TV bila perlu.");
                } else {
                    String o = c.shell("cmd package set-home-activity --user 0 " + stock, 15000);
                    if (o.toLowerCase(Locale.US).contains("error")) c.shell("cmd package set-home-activity " + stock, 15000);
                    c.shell("input keyevent 3", 8000);
                    hlog("✔ Beranda bawaan dikembalikan: " + stock);
                }
            } catch (Exception e) {
                hlog("✖ " + e.getMessage());
            } finally {
                homeBusy = false;
                ui.post(this::checkHome);
            }
        });
    }

    private void pickWall() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/mp4"});
        try { startActivityForResult(i, REQ_WALL); }
        catch (Exception e) {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.setType("image/*");
            startActivityForResult(g, REQ_WALL);
        }
    }

    private boolean isVideo(Uri u) {
        String type = getContentResolver().getType(u);
        return (type != null && type.startsWith("video")) || nameSize(u)[0].toLowerCase(Locale.US).endsWith(".mp4");
    }

    /** Buka layar atur posisi: bingkai 16:9 seperti TV, geser (dan cubit untuk foto). */
    private void openWallEditor(Uri u) {
        hlog("Membuka gambar…");
        bg.execute(() -> {
            try {
                boolean video = isVideo(u);
                Bitmap b = video ? videoFrame(u) : loadPhoto(u);
                ui.post(() -> { homeLog.setText(""); showWallEditor(u, b, video); });
            } catch (Throwable e) {
                hlog("✖ " + e.getMessage());
            }
        });
    }

    private void showWallEditor(Uri u, Bitmap b, boolean video) {
        LinearLayout box = vbox();
        box.setPadding(dp(16), dp(8), dp(16), 0);
        TextView hint = label(video
                ? "Geser gambar untuk mengatur bagian video yang tampil di TV."
                : "Geser foto untuk mengatur posisi. Cubit dengan dua jari untuk zoom.", 14, 0xFF444444, false);
        hint.setPadding(0, 0, 0, dp(10));
        box.addView(hint);
        WallCropView cv = new WallCropView(this, b, !video);
        box.addView(cv, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("Atur posisi wallpaper")
                .setView(box)
                .setPositiveButton("Kirim ke TV", (d, w) -> {
                    if (video) {
                        float[] p = cv.pos();
                        sendWall(u, null, null, p[0], p[1]);
                    } else {
                        android.graphics.RectF r = cv.crop();
                        sendWall(u, b, r, 0.5f, 0.5f);
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private Bitmap videoFrame(Uri u) throws Exception {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(this, u);
            Bitmap f = r.getFrameAtTime(0);
            if (f == null) throw new Exception("Video tidak terbaca.");
            return f;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    /** Foto (dengan rotasi EXIF), cukup besar untuk dipotong ke 1920×1080. */
    private Bitmap loadPhoto(Uri u) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream is = getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(is, null, o); }
        if (o.outWidth <= 0) throw new Exception("File gambar tidak terbaca.");
        int s = 1;
        while (Math.min(o.outWidth, o.outHeight) / (s * 2) >= 1440) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        Bitmap b;
        try (InputStream is = getContentResolver().openInputStream(u)) { b = BitmapFactory.decodeStream(is, null, o2); }
        if (b == null) throw new Exception("File gambar tidak terbaca.");
        int rot = 0;
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            try (InputStream is = getContentResolver().openInputStream(u)) {
                android.media.ExifInterface ex = new android.media.ExifInterface(is);
                int or = ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
                rot = or == 6 ? 90 : or == 3 ? 180 : or == 8 ? 270 : 0;
            } catch (Exception ignored) { }
        }
        if (rot == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(rot);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    /** Potong bagian yang dipilih lalu jadikan JPEG 1920×1080. */
    private void writeCropJpeg(Bitmap src, android.graphics.RectF r, File out) throws Exception {
        int l = Math.max(0, Math.round(r.left)), t = Math.max(0, Math.round(r.top));
        int w = Math.min(src.getWidth() - l, Math.round(r.width())), h = Math.min(src.getHeight() - t, Math.round(r.height()));
        Bitmap c = Bitmap.createBitmap(src, l, t, Math.max(1, w), Math.max(1, h));
        Bitmap o = Bitmap.createScaledBitmap(c, 1920, 1080, true);
        try (FileOutputStream fo = new FileOutputStream(out)) { o.compress(Bitmap.CompressFormat.JPEG, 92, fo); }
    }

    private void sendWall(Uri u, Bitmap photo, android.graphics.RectF photoCrop, float px, float py) {
        if (homeBusy) return;
        homeStart();
        bg.execute(() -> {
            File tmp = null;
            try {
                boolean video = photoCrop == null;
                String ext = video ? "mp4" : "jpg";
                tmp = new File(getCacheDir(), "wall." + ext);
                if (video) {
                    try (InputStream is = getContentResolver().openInputStream(u); FileOutputStream fo = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = is.read(buf)) > 0) fo.write(buf, 0, n);
                    }
                    if (tmp.length() > 40L * 1048576) hlog("Catatan: video cukup besar (" + (tmp.length() / 1048576) + " MB). Video pendek lebih ringan untuk TV.");
                } else {
                    hlog("Menyiapkan foto…");
                    writeCropJpeg(photo, photoCrop, tmp);
                }
                AdbConn c = conn();
                if (!c.shell("pm path " + HOME_PKG, 8000).contains("package:")) {
                    hlog("Beranda TV belum terpasang. Tekan langkah 1 dulu.");
                    return;
                }
                long size = tmp.length();
                String posTxt = String.format(Locale.US, "%.4f %.4f", px, py);
                hlog(String.format(Locale.US, "Mengirim wallpaper (%.1f MB) …", size / 1048576f));
                ui.post(() -> { homeBar.setProgress(0); homeBar.setVisibility(View.VISIBLE); });
                AdbConn.Progress pr = (d, t) -> ui.post(() -> homeBar.setProgress((int) (d * 1000 / Math.max(1, t))));
                String script = "mkdir -p files && head -c " + size + " > files/wall.tmp && rm -f files/wall.jpg files/wall.png files/wall.mp4"
                        + " && echo " + posTxt + " > files/wall_pos.txt"
                        + " && mv files/wall.tmp files/wall." + ext + " && echo WALL_OK";
                String out;
                try (InputStream is = new FileInputStream(tmp)) {
                    out = c.execWithInput("run-as " + HOME_PKG + " sh -c '" + script + "'", is, size, pr);
                }
                if (!out.contains("WALL_OK")) {
                    hlog("Cara cepat tidak bisa (" + out.trim() + "), pakai cara cadangan…");
                    c.shell("mkdir -p /sdcard/BerandaTV && rm -f /sdcard/BerandaTV/wall.*", 10000);
                    c.shell("echo " + posTxt + " > /sdcard/BerandaTV/wall_pos.txt", 10000);
                    try (InputStream is = new FileInputStream(tmp)) {
                        c.push(is, size, "/sdcard/BerandaTV/wall." + ext, pr);
                    }
                    for (String perm : new String[]{"READ_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO"})
                        c.shell("pm grant " + HOME_PKG + " android.permission." + perm, 8000);
                }
                c.shell("am start -n " + HOME_CMP + " --ez reload true", 10000);
                hlog("✔ Wallpaper terpasang di " + cur.label() + ".");
            } catch (Throwable e) {
                hlog("✖ " + e.getMessage());
            } finally {
                if (tmp != null) //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                homeBusy = false;
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        screenOn = false;
        dropConn();
    }

    @SuppressWarnings("unused")
    private static ViewGroup.LayoutParams wrap() { return new ViewGroup.LayoutParams(-2, -2); }
}
