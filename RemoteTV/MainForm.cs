using System.Diagnostics;
using System.Drawing;
using System.Text.RegularExpressions;

namespace RemoteTV;

public class MainForm : Form
{
    readonly Adb adb = new();
    readonly AppSettings settings = AppSettings.Load();
    string serial = null; // "ip:port" TV yang sedang terhubung

    // --- kontrol ---
    readonly ComboBox cbTv = new();
    readonly Button btnConnect = new();
    readonly Button btnSaveTv = new();
    readonly Button btnDelTv = new();
    readonly Label lblStatus = new();
    readonly TabControl tabs = new();
    readonly TabPage tabRemote = new("Remote");
    readonly TabPage tabFile = new("File / APK");
    readonly TabPage tabApps = new("Aplikasi");
    readonly TabPage tabScreen = new("Layar");
    readonly TextBox txtSend = new();
    readonly CheckBox chkEnter = new();
    readonly ListBox lstApps = new();
    readonly CheckBox chkSystemApps = new();
    readonly TextBox txtLog = new();

    public MainForm()
    {
        Text = "Remote TV";
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(440, 720);
        MinimumSize = new Size(420, 640);
        Font = new Font("Segoe UI", 10f);
        KeyPreview = false;

        BuildUi();
        LoadTvList();

        Shown += async (s, e) =>
        {
            if (!string.IsNullOrWhiteSpace(settings.LastIp))
            {
                cbTv.Text = settings.LastIp;
                await ConnectAsync(auto: true);
            }
        };
    }

    // ============================== UI ==============================

    void BuildUi()
    {
        // ---- log (bawah) ----
        txtLog.Multiline = true;
        txtLog.ReadOnly = true;
        txtLog.ScrollBars = ScrollBars.Vertical;
        txtLog.Dock = DockStyle.Bottom;
        txtLog.Height = 120;
        txtLog.BackColor = Color.FromArgb(24, 26, 32);
        txtLog.ForeColor = Color.FromArgb(200, 205, 215);
        txtLog.Font = new Font("Consolas", 9f);

        // ---- baris koneksi (atas) ----
        var top = new Panel { Dock = DockStyle.Top, Height = 84, Padding = new Padding(10, 8, 10, 4) };

        var lbl = new Label { Text = "TV", AutoSize = true, Location = new Point(10, 14) };
        cbTv.DropDownStyle = ComboBoxStyle.DropDown;
        cbTv.Location = new Point(42, 10);
        cbTv.Width = 190;
        btnConnect.Text = "Hubungkan";
        btnConnect.Location = new Point(240, 8);
        btnConnect.Size = new Size(96, 30);
        btnConnect.Click += async (s, e) => await ConnectAsync();
        btnSaveTv.Text = "Simpan";
        btnSaveTv.Location = new Point(342, 8);
        btnSaveTv.Size = new Size(70, 30);
        btnSaveTv.Click += (s, e) => SaveTv();
        btnDelTv.Text = "Hapus";
        btnDelTv.Location = new Point(342, 42);
        btnDelTv.Size = new Size(70, 26);
        btnDelTv.Click += (s, e) => DeleteTv();

        lblStatus.AutoSize = false;
        lblStatus.Location = new Point(10, 46);
        lblStatus.Size = new Size(326, 24);
        lblStatus.Text = "Belum terhubung. Isi IP TV lalu klik Hubungkan.";
        lblStatus.ForeColor = Color.DimGray;

        top.Controls.AddRange(new Control[] { lbl, cbTv, btnConnect, btnSaveTv, btnDelTv, lblStatus });

        // ---- tab ----
        tabs.Dock = DockStyle.Fill;
        tabs.Controls.AddRange(new Control[] { tabRemote, tabFile, tabApps, tabScreen });
        BuildRemoteTab();
        BuildFileTab();
        BuildAppsTab();
        BuildScreenTab();

        // urutan Add: Fill dulu, lalu Top/Bottom
        Controls.Add(tabs);
        Controls.Add(txtLog);
        Controls.Add(top);
    }

    Button KeyBtn(string text, int keycode, Color? color = null)
    {
        var b = new Button
        {
            Text = text,
            Dock = DockStyle.Fill,
            Margin = new Padding(4),
            Font = new Font("Segoe UI", 12f, FontStyle.Bold),
            TabStop = false,
        };
        if (color != null) { b.BackColor = color.Value; b.ForeColor = Color.White; b.FlatStyle = FlatStyle.Flat; }
        b.Click += async (s, e) => await SendKeyAsync(keycode);
        return b;
    }

    void BuildRemoteTab()
    {
        var pad = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            Height = 330,
            ColumnCount = 3,
            RowCount = 5,
            Padding = new Padding(6),
        };
        for (int i = 0; i < 3; i++) pad.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.33f));
        for (int i = 0; i < 5; i++) pad.RowStyles.Add(new RowStyle(SizeType.Percent, 20f));

        var blue = Color.FromArgb(37, 99, 235);
        var red = Color.FromArgb(185, 28, 28);

        // baris 0
        pad.Controls.Add(KeyBtn("Back", 4), 0, 0);
        pad.Controls.Add(KeyBtn("▲", 19), 1, 0);
        pad.Controls.Add(KeyBtn("Home", 3), 2, 0);
        // baris 1
        pad.Controls.Add(KeyBtn("◀", 21), 0, 1);
        pad.Controls.Add(KeyBtn("OK", 23, blue), 1, 1);
        pad.Controls.Add(KeyBtn("▶", 22), 2, 1);
        // baris 2
        pad.Controls.Add(KeyBtn("Menu", 82), 0, 2);
        pad.Controls.Add(KeyBtn("▼", 20), 1, 2);
        pad.Controls.Add(KeyBtn("⌫", 67), 2, 2);
        // baris 3
        pad.Controls.Add(KeyBtn("Vol −", 25), 0, 3);
        pad.Controls.Add(KeyBtn("Mute", 164), 1, 3);
        pad.Controls.Add(KeyBtn("Vol +", 24), 2, 3);
        // baris 4
        pad.Controls.Add(KeyBtn("⏯", 85), 0, 4);
        pad.Controls.Add(KeyBtn("Power", 26, red), 1, 4);
        pad.Controls.Add(KeyBtn("Tab", 61), 2, 4);

        var grp = new GroupBox { Text = "Ketik teks ke kolom input di TV", Dock = DockStyle.Top, Height = 92, Padding = new Padding(8, 6, 8, 6) };
        txtSend.Location = new Point(10, 28);
        txtSend.Width = 300;
        txtSend.KeyDown += async (s, e) =>
        {
            if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; await SendTextAsync(); }
        };
        var btnSend = new Button { Text = "Kirim", Location = new Point(318, 26), Size = new Size(80, 28) };
        btnSend.Click += async (s, e) => await SendTextAsync();
        chkEnter.Text = "tekan OK setelah mengetik";
        chkEnter.AutoSize = true;
        chkEnter.Location = new Point(10, 60);
        grp.Controls.AddRange(new Control[] { txtSend, btnSend, chkEnter });

        var hint = new Label
        {
            Dock = DockStyle.Top,
            Height = 44,
            Text = "Keyboard laptop: panah = navigasi, Enter = OK, Esc = Back, Home = Home, Backspace = hapus (saat kursor tidak di kolom teks).",
            ForeColor = Color.DimGray,
            Padding = new Padding(8, 4, 8, 0),
            Font = new Font("Segoe UI", 8.5f),
        };

        tabRemote.Controls.Add(grp);
        tabRemote.Controls.Add(hint);
        tabRemote.Controls.Add(pad);
        // urutan tampil: pad (atas), hint, grp
        tabRemote.Controls.SetChildIndex(pad, 2);
        tabRemote.Controls.SetChildIndex(hint, 1);
        tabRemote.Controls.SetChildIndex(grp, 0);
    }

    void BuildFileTab()
    {
        var drop = new Label
        {
            Text = "Seret file ke sini\r\n\r\nFile .apk  →  langsung dipasang di TV\r\nFile lain  →  dikirim ke folder Download TV",
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleCenter,
            BorderStyle = BorderStyle.FixedSingle,
            BackColor = Color.FromArgb(243, 246, 252),
            ForeColor = Color.FromArgb(60, 70, 90),
            AllowDrop = true,
            Margin = new Padding(10),
        };
        drop.DragEnter += (s, e) =>
        {
            if (e.Data != null && e.Data.GetDataPresent(DataFormats.FileDrop)) e.Effect = DragDropEffects.Copy;
        };
        drop.DragDrop += async (s, e) =>
        {
            if (e.Data?.GetData(DataFormats.FileDrop) is string[] files)
                foreach (var f in files) await SendFileAsync(f);
        };

        var btnPick = new Button { Text = "Pilih file…", Dock = DockStyle.Bottom, Height = 38 };
        btnPick.Click += async (s, e) =>
        {
            using var dlg = new OpenFileDialog { Multiselect = true, Title = "Pilih file untuk dikirim ke TV" };
            if (dlg.ShowDialog(this) == DialogResult.OK)
                foreach (var f in dlg.FileNames) await SendFileAsync(f);
        };

        var panel = new Panel { Dock = DockStyle.Fill, Padding = new Padding(10) };
        panel.Controls.Add(drop);
        panel.Controls.Add(btnPick);
        tabFile.Controls.Add(panel);
    }

    void BuildAppsTab()
    {
        var bar = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 78, Padding = new Padding(6) };
        var btnLoad = new Button { Text = "Muat daftar", Size = new Size(110, 30) };
        btnLoad.Click += async (s, e) => await LoadAppsAsync();
        var btnOpen = new Button { Text = "Buka", Size = new Size(70, 30) };
        btnOpen.Click += async (s, e) => { if (lstApps.SelectedItem is string p) await LaunchAsync(p); };
        var btnStop = new Button { Text = "Hentikan", Size = new Size(84, 30) };
        btnStop.Click += async (s, e) => { if (lstApps.SelectedItem is string p) await StopAppAsync(p); };
        var btnCctv = new Button { Text = "Buka Layar CCTV", Size = new Size(140, 30) };
        btnCctv.Click += async (s, e) => await LaunchAsync("com.agi.layarcctv");
        chkSystemApps.Text = "termasuk aplikasi sistem";
        chkSystemApps.AutoSize = true;
        bar.Controls.AddRange(new Control[] { btnLoad, btnOpen, btnStop, btnCctv, chkSystemApps });

        lstApps.Dock = DockStyle.Fill;
        lstApps.IntegralHeight = false;
        lstApps.DoubleClick += async (s, e) => { if (lstApps.SelectedItem is string p) await LaunchAsync(p); };

        tabApps.Controls.Add(lstApps);
        tabApps.Controls.Add(bar);
    }

    void BuildScreenTab()
    {
        var info = new Label
        {
            Dock = DockStyle.Top,
            Height = 110,
            Padding = new Padding(10, 10, 10, 0),
            Text = "Screenshot menyimpan gambar layar TV ke folder Pictures.\r\n\r\n" +
                   "Tampilkan layar membuka layar TV di jendela laptop (scrcpy) dan bisa diklik dengan mouse. " +
                   "Aplikasi ber-DRM seperti Netflix akan tampil hitam.",
            ForeColor = Color.DimGray,
        };
        var btnShot = new Button { Text = "Screenshot TV", Height = 42, Dock = DockStyle.Top };
        btnShot.Click += async (s, e) => await ScreenshotAsync();
        var gap = new Panel { Height = 8, Dock = DockStyle.Top };
        var btnMirror = new Button { Text = "Tampilkan layar TV (scrcpy)", Height = 42, Dock = DockStyle.Top };
        btnMirror.Click += (s, e) => StartScrcpy();

        var panel = new Panel { Dock = DockStyle.Fill, Padding = new Padding(10) };
        panel.Controls.Add(btnMirror);
        panel.Controls.Add(gap);
        panel.Controls.Add(btnShot);
        panel.Controls.Add(info);
        tabScreen.Controls.Add(panel);
    }

    // ============================== Keyboard laptop ==============================

    protected override bool ProcessCmdKey(ref Message msg, Keys keyData)
    {
        bool typing = ActiveControl is TextBox || ActiveControl is ComboBox || ActiveControl is ListBox;
        if (tabs.SelectedTab == tabRemote && !typing)
        {
            int code = keyData switch
            {
                Keys.Up => 19,
                Keys.Down => 20,
                Keys.Left => 21,
                Keys.Right => 22,
                Keys.Enter => 23,
                Keys.Escape => 4,
                Keys.Home => 3,
                Keys.Back => 67,
                _ => -1,
            };
            if (code >= 0)
            {
                _ = SendKeyAsync(code);
                return true;
            }
        }
        return base.ProcessCmdKey(ref msg, keyData);
    }

    // ============================== Daftar TV ==============================

    void LoadTvList()
    {
        cbTv.Items.Clear();
        foreach (var t in settings.Tvs) cbTv.Items.Add(t);
    }

    string CurrentIp()
    {
        if (cbTv.SelectedItem is TvEntry t && cbTv.Text == t.ToString()) return t.Ip;
        string txt = cbTv.Text.Trim();
        var m = Regex.Match(txt, @"\((\d{1,3}(\.\d{1,3}){3}(:\d+)?)\)\s*$");
        return m.Success ? m.Groups[1].Value : txt;
    }

    void SaveTv()
    {
        string ip = CurrentIp();
        if (string.IsNullOrWhiteSpace(ip)) { Log("Isi IP TV dulu."); return; }
        string name = Prompt("Simpan TV", "Nama untuk TV ini (misal: TV CCTV):", "");
        if (name == null) return;
        settings.Tvs.RemoveAll(t => t.Ip == ip);
        settings.Tvs.Add(new TvEntry { Name = name.Trim(), Ip = ip });
        settings.Save();
        LoadTvList();
        Log("TV disimpan: " + name + " (" + ip + ")");
    }

    void DeleteTv()
    {
        string ip = CurrentIp();
        int n = settings.Tvs.RemoveAll(t => t.Ip == ip);
        if (n > 0)
        {
            settings.Save();
            LoadTvList();
            cbTv.Text = "";
            Log("TV dihapus dari daftar: " + ip);
        }
    }

    static string Prompt(string title, string label, string initial)
    {
        using var f = new Form
        {
            Text = title,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            StartPosition = FormStartPosition.CenterParent,
            ClientSize = new Size(360, 120),
            MaximizeBox = false,
            MinimizeBox = false,
        };
        var l = new Label { Text = label, AutoSize = true, Location = new Point(12, 12) };
        var t = new TextBox { Text = initial, Location = new Point(12, 40), Width = 336 };
        var ok = new Button { Text = "OK", DialogResult = DialogResult.OK, Location = new Point(190, 78), Size = new Size(76, 28) };
        var cancel = new Button { Text = "Batal", DialogResult = DialogResult.Cancel, Location = new Point(272, 78), Size = new Size(76, 28) };
        f.Controls.AddRange(new Control[] { l, t, ok, cancel });
        f.AcceptButton = ok;
        f.CancelButton = cancel;
        return f.ShowDialog() == DialogResult.OK ? t.Text : null;
    }

    // ============================== Koneksi ==============================

    string[] Dev(params string[] args)
    {
        var list = new List<string>();
        if (serial != null) { list.Add("-s"); list.Add(serial); }
        list.AddRange(args);
        return list.ToArray();
    }

    bool Ready()
    {
        if (serial == null)
        {
            Log("Belum terhubung ke TV. Klik Hubungkan dulu.");
            SetStatus("Belum terhubung.", Color.Firebrick);
            return false;
        }
        return true;
    }

    async Task ConnectAsync(bool auto = false)
    {
        string ip = CurrentIp();
        if (string.IsNullOrWhiteSpace(ip)) { Log("Isi IP TV dulu, misalnya 192.168.68.50"); return; }
        if (!ip.Contains(':')) ip += ":5555";

        btnConnect.Enabled = false;
        SetStatus("Menghubungkan ke " + ip + " …", Color.DimGray);
        try
        {
            var r = await adb.RunAsync(new[] { "connect", ip }, 15000);
            Log("adb connect " + ip + " → " + r.Output);

            var d = await adb.RunAsync(new[] { "devices" }, 10000);
            string state = null;
            foreach (var line in d.Output.Split('\n'))
            {
                var parts = line.Trim().Split('\t');
                if (parts.Length >= 2 && parts[0] == ip) state = parts[1].Trim();
            }

            if (state == "device")
            {
                serial = ip;
                settings.LastIp = ip;
                settings.Save();
                SetStatus("Terhubung ke " + ip, Color.ForestGreen);
                ActiveControl = tabs; // supaya tombol keyboard laptop langsung berfungsi
            }
            else if (state == "unauthorized")
            {
                serial = null;
                SetStatus("Lihat layar TV: pilih \"Always allow\", lalu klik Hubungkan lagi.", Color.DarkOrange);
            }
            else
            {
                serial = null;
                SetStatus("Gagal terhubung. Pastikan Network debugging aktif di TV.", Color.Firebrick);
                if (!auto) Log("Tips: di TV buka Settings > System > Developer options > aktifkan USB/Network debugging.");
            }
        }
        finally { btnConnect.Enabled = true; }
    }

    // ============================== Aksi ==============================

    async Task SendKeyAsync(int code)
    {
        if (!Ready()) return;
        var r = await adb.RunAsync(Dev("shell", "input", "keyevent", code.ToString()), 8000);
        if (r.Code != 0) Log("Tombol gagal: " + r.Output);
    }

    async Task SendTextAsync()
    {
        if (!Ready()) return;
        string text = txtSend.Text;
        if (text.Length == 0) return;
        if (text.Any(c => c > 126))
        {
            Log("Teks berisi karakter non-ASCII (huruf khusus/emoji) yang tidak bisa dikirim lewat adb.");
            return;
        }
        var r = await adb.RunAsync(Dev("shell", "input text " + Adb.EscapeForInputText(text)), 10000);
        Log(r.Code == 0 ? "Teks terkirim (" + text.Length + " karakter)." : "Gagal kirim teks: " + r.Output);
        if (r.Code == 0 && chkEnter.Checked) await SendKeyAsync(23);
    }

    async Task SendFileAsync(string path)
    {
        if (!Ready()) return;
        if (Directory.Exists(path)) { Log("Folder tidak didukung, kirim file satu per satu: " + path); return; }
        if (!File.Exists(path)) { Log("File tidak ditemukan: " + path); return; }

        string name = Path.GetFileName(path);
        try
        {
            if (path.EndsWith(".apk", StringComparison.OrdinalIgnoreCase))
            {
                Log("Memasang " + name + " ke TV …");
                var r = await adb.RunAsync(Dev("install", "-r", path), 300000);
                Log(r.Output);
            }
            else
            {
                Log("Mengirim " + name + " ke /sdcard/Download/ …");
                var r = await adb.RunAsync(Dev("push", path, "/sdcard/Download/" + name), 600000);
                Log(r.Output);
            }
        }
        catch (Exception ex) { Log("Gagal: " + ex.Message); }
    }

    async Task LoadAppsAsync()
    {
        if (!Ready()) return;
        Log("Memuat daftar aplikasi …");
        var args = chkSystemApps.Checked
            ? Dev("shell", "pm", "list", "packages")
            : Dev("shell", "pm", "list", "packages", "-3");
        var r = await adb.RunAsync(args, 20000);
        var pkgs = r.Output.Split('\n')
            .Select(l => l.Trim())
            .Where(l => l.StartsWith("package:"))
            .Select(l => l.Substring(8))
            .OrderBy(p => p, StringComparer.OrdinalIgnoreCase)
            .ToList();
        lstApps.Items.Clear();
        foreach (var p in pkgs) lstApps.Items.Add(p);
        Log(pkgs.Count + " aplikasi. Klik dua kali untuk membuka.");
    }

    async Task LaunchAsync(string pkg)
    {
        if (!Ready()) return;
        var r = await adb.RunAsync(Dev("shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"), 15000);
        if (r.Output.Contains("No activities found"))
            r = await adb.RunAsync(Dev("shell", "monkey", "-p", pkg, "-c", "android.intent.category.LEANBACK_LAUNCHER", "1"), 15000);

        if (r.Output.Contains("No activities found") || r.Output.Contains("Error"))
            Log("Gagal membuka " + pkg + " (mungkin belum terpasang atau tidak punya layar utama).");
        else
            Log("Membuka " + pkg);
    }

    async Task StopAppAsync(string pkg)
    {
        if (!Ready()) return;
        await adb.RunAsync(Dev("shell", "am", "force-stop", pkg), 10000);
        Log("Dihentikan: " + pkg);
    }

    async Task ScreenshotAsync()
    {
        if (!Ready()) return;
        try
        {
            Log("Mengambil screenshot …");
            byte[] png = await adb.RunBinaryAsync(Dev("exec-out", "screencap", "-p"), 20000);
            if (png.Length < 100) { Log("Screenshot kosong/gagal."); return; }

            string dir = Environment.GetFolderPath(Environment.SpecialFolder.MyPictures);
            string file = Path.Combine(dir, "TV_" + DateTime.Now.ToString("yyyyMMdd_HHmmss") + ".png");
            await File.WriteAllBytesAsync(file, png);
            Log("Tersimpan: " + file);
            Process.Start(new ProcessStartInfo(file) { UseShellExecute = true });
        }
        catch (Exception ex) { Log("Screenshot gagal: " + ex.Message); }
    }

    void StartScrcpy()
    {
        if (!Ready()) return;
        if (!adb.HasScrcpy)
        {
            Log("scrcpy.exe tidak ditemukan di folder tools.");
            return;
        }
        try
        {
            var psi = new ProcessStartInfo(adb.ScrcpyPath)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                WorkingDirectory = adb.ToolsDir,
            };
            psi.ArgumentList.Add("-s");
            psi.ArgumentList.Add(serial);
            psi.ArgumentList.Add("--no-audio");
            psi.ArgumentList.Add("-m");
            psi.ArgumentList.Add("1280");
            psi.ArgumentList.Add("--window-title");
            psi.ArgumentList.Add("Layar TV " + serial);
            Process.Start(psi);
            Log("Membuka layar TV (scrcpy) …");
        }
        catch (Exception ex) { Log("Gagal membuka scrcpy: " + ex.Message); }
    }

    // ============================== Util ==============================

    void SetStatus(string text, Color color)
    {
        lblStatus.Text = text;
        lblStatus.ForeColor = color;
    }

    void Log(string text)
    {
        if (InvokeRequired) { BeginInvoke(new Action(() => Log(text))); return; }
        txtLog.AppendText("[" + DateTime.Now.ToString("HH:mm:ss") + "] " + text.Replace("\r", "").Replace("\n", "\r\n    ") + "\r\n");
    }
}
