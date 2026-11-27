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
    private View pageRemote, pageTv, pageScreen, pageInstall, current;
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
        for (View v : new View[]{pageRemote, pageTv, pageScreen, pageInstall}) {
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
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
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
        for (View v : new View[]{pageRemote, pageTv, pageScreen, pageInstall}) v.setVisibility(v == page ? View.VISIBLE : View.GONE);
        current = page;
        if (page == pageTv) refreshTvList();
        if (page == pageScreen) startScreen();
        if (page != pageRemote) closeTyping();
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
        TextView bApk = actionBtn("APK", KEY, v -> show(pageInstall));
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
        TextView power = keyBtn("⏻", K_POWER, RED, false, 24, true);
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
        int pad = Math.min(w, dp(330));
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        screenOn = false;
        dropConn();
    }

    @SuppressWarnings("unused")
    private static ViewGroup.LayoutParams wrap() { return new ViewGroup.LayoutParams(-2, -2); }
}
