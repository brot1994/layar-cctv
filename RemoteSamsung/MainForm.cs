using System.Drawing;

namespace RemoteSamsung;

public class MainForm : Form
{
    readonly SamsungTv tv = new();
    readonly AppSettings settings = AppSettings.Load();
    TvEntry current;

    readonly ComboBox cbTv = new();
    readonly Button btnConnect = new();
    readonly Button btnSaveTv = new();
    readonly Button btnDelTv = new();
    readonly Label lblStatus = new();
    readonly TextBox txtLog = new();
    readonly TableLayoutPanel grid = new();

    public MainForm()
    {
        Text = "Remote TV Samsung";
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(440, 700);
        MinimumSize = new Size(420, 640);
        Font = new Font("Segoe UI", 10f);
        KeyPreview = true;
        BuildUi();
        foreach (var t in settings.Tvs) cbTv.Items.Add(t);
        if (!string.IsNullOrWhiteSpace(settings.LastIp))
        {
            cbTv.Text = settings.LastIp;
            var m = settings.Tvs.Find(x => x.Ip == settings.LastIp);
            if (m != null) cbTv.SelectedItem = m;
        }
        KeyDown += OnKey;
        FormClosed += (s, e) => tv.Dispose();
    }

    void Log(string s)
    {
        if (InvokeRequired) { BeginInvoke(new Action(() => Log(s))); return; }
        txtLog.AppendText("[" + DateTime.Now.ToString("HH:mm:ss") + "] " + s + "\r\n");
    }

    Button Btn(string text, string key, Color? back = null)
    {
        var b = new Button { Text = text, Dock = DockStyle.Fill, Margin = new Padding(4), Font = new Font("Segoe UI", 11f, FontStyle.Bold) };
        b.BackColor = back ?? Color.White;
        if (back != null) b.ForeColor = Color.White;
        b.FlatStyle = FlatStyle.Flat;
        b.FlatAppearance.BorderColor = Color.Silver;
        b.Click += async (s, e) => await Press(key);
        return b;
    }

    void BuildUi()
    {
        var top = new Panel { Dock = DockStyle.Top, Height = 92 };
        var lbl = new Label { Text = "TV", Left = 10, Top = 14, AutoSize = true };
        cbTv.Left = 40; cbTv.Top = 10; cbTv.Width = 200;
        btnConnect.Text = "Hubungkan"; btnConnect.Left = 248; btnConnect.Top = 8; btnConnect.Width = 100; btnConnect.Height = 30;
        btnSaveTv.Text = "Simpan"; btnSaveTv.Left = 354; btnSaveTv.Top = 8; btnSaveTv.Width = 76; btnSaveTv.Height = 30;
        btnDelTv.Text = "Hapus"; btnDelTv.Left = 354; btnDelTv.Top = 42; btnDelTv.Width = 76; btnDelTv.Height = 30;
        lblStatus.Left = 10; lblStatus.Top = 50; lblStatus.Width = 330; lblStatus.Height = 36;
        lblStatus.Text = "Belum tersambung. Isi alamat IP TV lalu Hubungkan.";
        top.Controls.AddRange(new Control[] { lbl, cbTv, btnConnect, btnSaveTv, btnDelTv, lblStatus });
        btnConnect.Click += async (s, e) => await ConnectAsync();
        btnSaveTv.Click += (s, e) => SaveTv();
        btnDelTv.Click += (s, e) => DelTv();

        txtLog.Multiline = true; txtLog.ReadOnly = true; txtLog.ScrollBars = ScrollBars.Vertical;
        txtLog.Dock = DockStyle.Bottom; txtLog.Height = 110;
        txtLog.BackColor = Color.FromArgb(20, 20, 20); txtLog.ForeColor = Color.Lime;
        txtLog.Font = new Font("Consolas", 9f);

        grid.Dock = DockStyle.Fill;
        grid.ColumnCount = 3;
        for (int i = 0; i < 3; i++) grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.33f));
        var red = Color.FromArgb(180, 30, 30);
        var blue = Color.FromArgb(40, 100, 220);
        var rows = new (string, string, Color?)[][]
        {
            new[] { ("Power", "KEY_POWER", (Color?)red), ("Sumber", "KEY_SOURCE", null), ("Home", "KEY_HOME", null) },
            new[] { ("Back", "KEY_RETURN", (Color?)null), ("▲", "KEY_UP", null), ("Exit", "KEY_EXIT", null) },
            new[] { ("◀", "KEY_LEFT", (Color?)null), ("OK", "KEY_ENTER", blue), ("▶", "KEY_RIGHT", null) },
            new[] { ("Menu", "KEY_MENU", (Color?)null), ("▼", "KEY_DOWN", null), ("Info", "KEY_INFO", null) },
            new[] { ("Vol −", "KEY_VOLDOWN", (Color?)null), ("Mute", "KEY_MUTE", null), ("Vol +", "KEY_VOLUP", null) },
            new[] { ("CH −", "KEY_CHDOWN", (Color?)null), ("HDMI", "KEY_HDMI", null), ("CH +", "KEY_CHUP", null) },
        };
        grid.RowCount = rows.Length + 1;
        foreach (var row in rows)
        {
            grid.RowStyles.Add(new RowStyle(SizeType.Percent, 100f / (rows.Length + 1)));
            foreach (var (t, k, c) in row) grid.Controls.Add(Btn(t, k, c));
        }
        // baris terakhir: Nyalakan lewat jaringan
        grid.RowStyles.Add(new RowStyle(SizeType.Percent, 100f / (rows.Length + 1)));
        var wol = new Button { Text = "Nyalakan TV lewat jaringan (Wake-on-LAN)", Dock = DockStyle.Fill, Margin = new Padding(4) };
        wol.Click += (s, e) => Wake();
        grid.Controls.Add(wol);
        grid.SetColumnSpan(wol, 3);

        var hint = new Label
        {
            Dock = DockStyle.Bottom, Height = 38, ForeColor = Color.Gray, Font = new Font("Segoe UI", 8f),
            Text = "Keyboard laptop: panah = navigasi, Enter = OK, Esc = Back, Home = Home, PgUp/PgDn = Volume, M = Mute"
        };

        Controls.Add(grid);
        Controls.Add(hint);
        Controls.Add(txtLog);
        Controls.Add(top);
    }

    TvEntry Entry()
    {
        string ip = cbTv.Text.Trim();
        if (cbTv.SelectedItem is TvEntry te && (te.Ip == ip || cbTv.Text == te.ToString())) return te;
        // ambil IP dari "Nama (1.2.3.4)" bila perlu
        var m = System.Text.RegularExpressions.Regex.Match(ip, @"\d+\.\d+\.\d+\.\d+");
        if (m.Success) ip = m.Value;
        return settings.Tvs.Find(x => x.Ip == ip) ?? new TvEntry { Ip = ip };
    }

    async Task ConnectAsync()
    {
        var e = Entry();
        if (string.IsNullOrWhiteSpace(e.Ip)) { MessageBox.Show("Isi alamat IP TV dulu (contoh 192.168.68.50)."); return; }
        current = e;
        btnConnect.Enabled = false;
        lblStatus.ForeColor = Color.DarkOrange;
        lblStatus.Text = "Menyambung ke " + e.Ip + "... Kalau TV menampilkan 'Izinkan', pilih Izinkan dengan remote TV.";
        Log("Menyambung ke " + e.Ip);
        string err = await tv.ConnectAsync(e.Ip, e.Token, Log, CancellationToken.None);
        btnConnect.Enabled = true;
        if (err == "")
        {
            lblStatus.ForeColor = Color.Green;
            lblStatus.Text = "Terhubung ke " + e.Ip;
            Log("Terhubung.");
            bool changed = false;
            if (!string.IsNullOrEmpty(tv.Token) && tv.Token != e.Token) { e.Token = tv.Token; changed = true; }
            if (string.IsNullOrEmpty(e.Mac))
            {
                string mac = SamsungTv.FindMac(e.Ip);
                if (mac != "") { e.Mac = mac; changed = true; Log("MAC TV: " + mac); }
            }
            settings.LastIp = e.Ip;
            if (!settings.Tvs.Contains(e))
            {
                if (settings.Tvs.Find(x => x.Ip == e.Ip) == null) { settings.Tvs.Add(e); cbTv.Items.Add(e); }
            }
            if (changed || true) settings.Save();
        }
        else
        {
            lblStatus.ForeColor = Color.Firebrick;
            lblStatus.Text = "Gagal: " + err;
            Log("Gagal: " + err);
        }
    }

    async Task Press(string key)
    {
        try
        {
            if (!tv.Connected)
            {
                if (current == null) { Log("Belum tersambung."); return; }
                await ConnectAsync();
                if (!tv.Connected) return;
            }
            await tv.SendKeyAsync(key);
        }
        catch (Exception ex)
        {
            Log("Gagal kirim " + key + ": " + ex.Message);
            tv.Disconnect();
            lblStatus.ForeColor = Color.Firebrick;
            lblStatus.Text = "Sambungan terputus. Klik Hubungkan.";
        }
    }

    void SaveTv()
    {
        var e = Entry();
        if (string.IsNullOrWhiteSpace(e.Ip)) return;
        string name = Prompt("Nama untuk TV ini:", e.Name);
        if (name == null) return;
        e.Name = name;
        if (settings.Tvs.Find(x => x.Ip == e.Ip) == null) settings.Tvs.Add(e);
        settings.Save();
        cbTv.Items.Clear();
        foreach (var t in settings.Tvs) cbTv.Items.Add(t);
        cbTv.SelectedItem = e;
        Log("TV disimpan: " + e);
    }

    void DelTv()
    {
        var e = Entry();
        var m = settings.Tvs.Find(x => x.Ip == e.Ip);
        if (m == null) return;
        settings.Tvs.Remove(m);
        settings.Save();
        cbTv.Items.Remove(cbTv.SelectedItem ?? m);
        cbTv.Text = "";
        Log("TV dihapus.");
    }

    void Wake()
    {
        var e = Entry();
        if (string.IsNullOrEmpty(e.Mac))
        {
            e.Mac = SamsungTv.FindMac(e.Ip);
            if (string.IsNullOrEmpty(e.Mac))
            {
                Log("MAC TV belum diketahui. Hubungkan sekali saat TV menyala supaya MAC tercatat.");
                return;
            }
            var m = settings.Tvs.Find(x => x.Ip == e.Ip); if (m != null) m.Mac = e.Mac;
            settings.Save();
        }
        try
        {
            SamsungTv.WakeOnLan(e.Mac);
            Log("Paket Wake-on-LAN dikirim ke " + e.Mac + ". (TV harus mengaktifkan opsi menyalakan lewat jaringan/mobile; kabel LAN lebih andal daripada WiFi.)");
        }
        catch (Exception ex) { Log("Gagal: " + ex.Message); }
    }

    void OnKey(object s, KeyEventArgs e)
    {
        if (ActiveControl is ComboBox) return;
        string k = e.KeyCode switch
        {
            Keys.Up => "KEY_UP", Keys.Down => "KEY_DOWN", Keys.Left => "KEY_LEFT", Keys.Right => "KEY_RIGHT",
            Keys.Enter => "KEY_ENTER", Keys.Escape => "KEY_RETURN", Keys.Home => "KEY_HOME",
            Keys.PageUp => "KEY_VOLUP", Keys.PageDown => "KEY_VOLDOWN", Keys.M => "KEY_MUTE",
            _ => null
        };
        if (k == null) return;
        e.Handled = true; e.SuppressKeyPress = true;
        _ = Press(k);
    }

    static string Prompt(string text, string def)
    {
        using var f = new Form { Text = "Nama TV", ClientSize = new Size(320, 110), StartPosition = FormStartPosition.CenterParent, FormBorderStyle = FormBorderStyle.FixedDialog, MaximizeBox = false, MinimizeBox = false };
        var l = new Label { Text = text, Left = 10, Top = 10, AutoSize = true };
        var t = new TextBox { Left = 10, Top = 34, Width = 300, Text = def };
        var ok = new Button { Text = "OK", Left = 150, Top = 70, Width = 75, DialogResult = DialogResult.OK };
        var cancel = new Button { Text = "Batal", Left = 235, Top = 70, Width = 75, DialogResult = DialogResult.Cancel };
        f.Controls.AddRange(new Control[] { l, t, ok, cancel });
        f.AcceptButton = ok; f.CancelButton = cancel;
        return f.ShowDialog() == DialogResult.OK ? t.Text.Trim() : null;
    }
}
