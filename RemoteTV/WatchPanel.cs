using System.Drawing;
using Microsoft.Win32;

namespace RemoteTV;

/// <summary>
/// Pengawas: tiap N menit cek apakah aplikasi Layar CCTV di tiap TV (daftar TV yang disimpan) menjawab.
/// Kalau tidak, buka ulang aplikasinya lewat ADB dan (opsional) kabari lewat Telegram.
/// </summary>
public class WatchPanel : Panel
{
    readonly Adb adb;
    readonly AppSettings st;
    readonly Action<string> log;

    readonly CheckBox chkOn = new() { Text = "Pengawas aktif" };
    readonly CheckBox chkWake = new() { Text = "Bangunkan TV bila standby (saat membuka ulang)" };
    readonly CheckBox chkAuto = new() { Text = "Jalankan Remote TV otomatis saat Windows menyala" };
    readonly NumericUpDown numMin = new() { Minimum = 1, Maximum = 120 };
    readonly NumericUpDown numFrom = new() { Minimum = 0, Maximum = 23 };
    readonly NumericUpDown numTo = new() { Minimum = 0, Maximum = 24 };
    readonly TextBox txtToken = new();
    readonly TextBox txtChat = new();
    readonly ListBox lst = new();
    readonly Label lblInfo = new();
    readonly System.Windows.Forms.Timer timer = new();
    readonly Dictionary<string, bool> lastUp = new();
    static readonly HttpClient http = new() { Timeout = TimeSpan.FromSeconds(15) };
    bool running;
    bool loading = true;

    public WatchPanel(Adb adb, AppSettings st, Action<string> log)
    {
        this.adb = adb; this.st = st; this.log = log;
        AutoScroll = true;
        BuildUi();
        chkOn.Checked = st.WatchOn;
        numMin.Value = Math.Clamp(st.WatchMin, 1, 120);
        numFrom.Value = Math.Clamp(st.WatchFrom, 0, 23);
        numTo.Value = Math.Clamp(st.WatchTo, 0, 24);
        chkWake.Checked = st.WatchWake;
        txtToken.Text = st.WatchTgToken ?? "";
        txtChat.Text = st.WatchTgChat ?? "";
        chkAuto.Checked = IsAutoStart();
        loading = false;

        timer.Tick += async (s, e) => await CheckAllAsync();
        ApplyTimer();
        // saat Remote TV dibuka dengan pengawas aktif: langsung periksa sekali
        HandleCreated += async (s, e) => { if (st.WatchOn) { await Task.Delay(2000); await CheckAllAsync(); } };
    }

    void BuildUi()
    {
        int y = 8;
        void Add(Control c, int x, int w, int h = 24) { c.Left = x; c.Top = y; c.Width = w; c.Height = h; Controls.Add(c); }

        Add(chkOn, 10, 380); y += 28;
        Add(new Label { Text = "Cek tiap (menit):", AutoSize = false }, 10, 120); Add(numMin, 135, 60); y += 30;
        Add(new Label { Text = "Aktif jam (mulai - selesai):", AutoSize = false }, 10, 170);
        Add(numFrom, 185, 55); Add(numTo, 250, 55); y += 30;
        Add(chkWake, 10, 400); y += 28;
        Add(chkAuto, 10, 400); y += 32;
        Add(new Label { Text = "Kabar lewat Telegram (opsional):", AutoSize = false, Font = new Font(Font, FontStyle.Bold) }, 10, 300); y += 24;
        Add(new Label { Text = "Token bot:", AutoSize = false }, 10, 80); Add(txtToken, 95, 300); y += 28;
        Add(new Label { Text = "ID chat:", AutoSize = false }, 10, 80); Add(txtChat, 95, 160); y += 32;

        var btn = new Button { Text = "Periksa sekarang" };
        Add(btn, 10, 140, 30);
        var btnTest = new Button { Text = "Tes Telegram" };
        Add(btnTest, 160, 110, 30);
        y += 38;
        btn.Click += async (s, e) => await CheckAllAsync(force: true);
        btnTest.Click += async (s, e) => { await Notify("Tes dari Pengawas Remote TV: Telegram tersambung."); log("Tes Telegram dikirim (bila token & ID benar, pesan masuk)."); };

        lblInfo.AutoSize = false; lblInfo.ForeColor = Color.DimGray;
        lblInfo.Text = "Mengawasi semua TV di daftar (tombol Simpan di atas). Pengawas butuh PC ini selalu menyala dan satu jaringan dengan TV.";
        Add(lblInfo, 10, 400, 52); y += 56;

        Add(new Label { Text = "Status TV:", AutoSize = false }, 10, 100); y += 22;
        lst.Left = 10; lst.Top = y; lst.Width = 400; lst.Height = 120; lst.Font = new Font("Consolas", 9f);
        Controls.Add(lst);

        chkOn.CheckedChanged += (s, e) => { if (loading) return; Save(); ApplyTimer(); if (chkOn.Checked) _ = CheckAllAsync(); };
        numMin.ValueChanged += (s, e) => { if (loading) return; Save(); ApplyTimer(); };
        numFrom.ValueChanged += (s, e) => { if (!loading) Save(); };
        numTo.ValueChanged += (s, e) => { if (!loading) Save(); };
        chkWake.CheckedChanged += (s, e) => { if (!loading) Save(); };
        txtToken.Leave += (s, e) => Save();
        txtChat.Leave += (s, e) => Save();
        chkAuto.CheckedChanged += (s, e) => { if (!loading) SetAutoStart(chkAuto.Checked); };
    }

    void Save()
    {
        st.WatchOn = chkOn.Checked;
        st.WatchMin = (int)numMin.Value;
        st.WatchFrom = (int)numFrom.Value;
        st.WatchTo = (int)numTo.Value;
        st.WatchWake = chkWake.Checked;
        st.WatchTgToken = txtToken.Text.Trim();
        st.WatchTgChat = txtChat.Text.Trim();
        st.Save();
    }

    void ApplyTimer()
    {
        timer.Stop();
        if (st.WatchOn)
        {
            timer.Interval = Math.Max(1, st.WatchMin) * 60000;
            timer.Start();
        }
    }

    // ------------------------- Windows autostart -------------------------
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    static bool IsAutoStart()
    {
        try { using var k = Registry.CurrentUser.OpenSubKey(RunKey); return k?.GetValue("RemoteTV") != null; }
        catch { return false; }
    }
    void SetAutoStart(bool on)
    {
        try
        {
            using var k = Registry.CurrentUser.OpenSubKey(RunKey, true);
            if (on) k.SetValue("RemoteTV", "\"" + Environment.ProcessPath + "\" --pengawas");
            else k.DeleteValue("RemoteTV", false);
            log(on ? "Remote TV akan jalan otomatis saat Windows menyala (minimized)." : "Autostart dimatikan.");
        }
        catch (Exception ex) { log("Gagal mengatur autostart: " + ex.Message); }
    }

    // ------------------------- pengawasan -------------------------
    bool InWindow()
    {
        int h = DateTime.Now.Hour, a = st.WatchFrom, b = st.WatchTo;
        if (a == b) return true;
        return a < b ? (h >= a && h < b) : (h >= a || h < b);
    }

    static async Task<bool> IsUp(string ip)
    {
        foreach (int port in new[] { 8090, 8081 })
        {
            try
            {
                using var r = await http.GetAsync($"http://{ip}:{port}/status");
                if (r.IsSuccessStatusCode) return true;
            }
            catch { }
        }
        return false;
    }

    // Dianggap mati hanya bila gagal 3 kali berturut-turut (jeda 20 detik), supaya gangguan sesaat tidak memicu alarm palsu.
    static async Task<bool> IsUpRetry(string ip)
    {
        for (int i = 0; i < 3; i++)
        {
            if (await IsUp(ip)) return true;
            if (i < 2) await Task.Delay(20000);
        }
        return false;
    }

    static async Task<bool> PingOk(string ip)
    {
        try { using var p = new System.Net.NetworkInformation.Ping(); return (await p.SendPingAsync(ip, 2000)).Status == System.Net.NetworkInformation.IPStatus.Success; }
        catch { return false; }
    }

    async Task<bool> Revive(string ip)
    {
        string serial = ip + ":5555";
        var c = await adb.RunAsync(new[] { "connect", serial }, 15000);
        log("adb connect " + serial + " -> " + c.Output.Split('\n')[0]);
        if (st.WatchWake)
            await adb.RunAsync(new[] { "-s", serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP" }, 10000);
        var r = await adb.RunAsync(new[] { "-s", serial, "shell", "am", "start", "-n",
            "com.agi.layarcctv/com.agi.layarcctv.MainActivity" }, 15000);
        log("buka aplikasi -> " + r.Output.Replace("\n", " "));
        for (int i = 0; i < 8; i++)
        {
            await Task.Delay(5000);
            if (await IsUp(ip)) return true;
        }
        return false;
    }

    async Task Notify(string text)
    {
        string t = st.WatchTgToken?.Trim(), c = st.WatchTgChat?.Trim();
        if (string.IsNullOrEmpty(t) || string.IsNullOrEmpty(c)) return;
        try
        {
            using var r = await http.GetAsync("https://api.telegram.org/bot" + t + "/sendMessage?chat_id=" +
                Uri.EscapeDataString(c) + "&text=" + Uri.EscapeDataString(text));
        }
        catch (Exception ex) { log("Telegram gagal: " + ex.Message); }
    }

    async Task CheckAllAsync(bool force = false)
    {
        if (running) return;
        running = true;
        try
        {
            var tvs = st.Tvs.ToList();
            if (tvs.Count == 0) { log("Pengawas: daftar TV kosong (Hubungkan TV lalu Simpan)."); return; }
            bool inWin = InWindow() || force;
            var lines = new List<string>();
            foreach (var tv in tvs)
            {
                string name = string.IsNullOrWhiteSpace(tv.Name) ? tv.Ip : tv.Name;
                bool up = await IsUpRetry(tv.Ip);
                string state;
                if (up)
                {
                    state = "OK";
                    if (lastUp.TryGetValue(tv.Ip, out var was) && !was)
                        await Notify($"TV {name} ({tv.Ip}) sudah menyala lagi, aplikasi berjalan.");
                    lastUp[tv.Ip] = true;
                }
                else if (!inWin)
                {
                    state = "tidak menjawab (di luar jam pengawasan)";
                }
                else
                {
                    bool ping = await PingOk(tv.Ip);
                    log($"Pengawas: {name} ({tv.Ip}) tidak menjawab 3x (ping {(ping ? "ya" : "tidak")}), mencoba membuka aplikasi...");
                    bool ok = await Revive(tv.Ip);
                    if (ok)
                    {
                        state = "dibuka ulang";
                        lastUp[tv.Ip] = true;
                        await Notify($"TV {name} ({tv.Ip}) tidak menjawab 3x berturut-turut (ping ke TV: {(ping ? "ya, jaringan hidup" : "tidak, jaringan/TV putus")}). Aplikasi dibuka ulang otomatis dan sudah berjalan.");
                    }
                    else
                    {
                        state = "GAGAL dibuka";
                        bool wasUp = !lastUp.TryGetValue(tv.Ip, out var w) || w;
                        lastUp[tv.Ip] = false;
                        if (wasUp)
                            await Notify($"TV {name} ({tv.Ip}) mati/tidak menjawab dan gagal dibuka ulang. Perlu dicek.");
                    }
                }
                lines.Add($"{DateTime.Now:HH:mm}  {name,-18} {state}");
            }
            lst.Items.Clear();
            foreach (var l in lines) lst.Items.Add(l);
        }
        catch (Exception ex) { log("Pengawas error: " + ex.Message); }
        finally { running = false; }
    }
}
