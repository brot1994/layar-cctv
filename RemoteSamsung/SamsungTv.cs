using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace RemoteSamsung;

/// <summary>Klien remote jaringan TV Samsung (Tizen) lewat WebSocket + Wake-on-LAN.</summary>
public class SamsungTv : IDisposable
{
    const string AppName = "RemoteSamsung";
    ClientWebSocket ws;
    public string Token { get; private set; } = "";

    public bool Connected => ws != null && ws.State == WebSocketState.Open;

    /// <summary>Sambung ke TV. Pertama kali TV menampilkan "Izinkan" - tekan OK di remote TV.</summary>
    public async Task<string> ConnectAsync(string ip, string token, Action<string> log, CancellationToken ct)
    {
        Disconnect();
        string name = Convert.ToBase64String(Encoding.UTF8.GetBytes(AppName));
        var urls = new List<string>
        {
            $"wss://{ip}:8002/api/v2/channels/samsung.remote.control?name={name}" + (string.IsNullOrEmpty(token) ? "" : "&token=" + token),
            $"ws://{ip}:8001/api/v2/channels/samsung.remote.control?name={name}" + (string.IsNullOrEmpty(token) ? "" : "&token=" + token),
        };
        string lastErr = "";
        foreach (var url in urls)
        {
            try
            {
                var c = new ClientWebSocket();
                c.Options.RemoteCertificateValidationCallback = (a, b, d, e) => true; // sertifikat TV swasigned
                using var to = CancellationTokenSource.CreateLinkedTokenSource(ct);
                to.CancelAfter(TimeSpan.FromSeconds(8));
                await c.ConnectAsync(new Uri(url), to.Token);
                log("Tersambung ke " + url.Split('?')[0] + ", menunggu persetujuan TV...");

                // tunggu event ms.channel.connect (maks 40 dtk: TV bisa menampilkan dialog Izinkan)
                var buf = new byte[8192];
                var deadline = DateTime.UtcNow.AddSeconds(40);
                while (DateTime.UtcNow < deadline)
                {
                    using var rto = CancellationTokenSource.CreateLinkedTokenSource(ct);
                    rto.CancelAfter(TimeSpan.FromSeconds(41));
                    var r = await c.ReceiveAsync(new ArraySegment<byte>(buf), rto.Token);
                    if (r.MessageType == WebSocketMessageType.Close) break;
                    string msg = Encoding.UTF8.GetString(buf, 0, r.Count);
                    if (msg.Contains("ms.channel.connect"))
                    {
                        try
                        {
                            using var doc = JsonDocument.Parse(msg);
                            if (doc.RootElement.TryGetProperty("data", out var d) &&
                                d.TryGetProperty("token", out var t))
                                Token = t.GetString() ?? "";
                        }
                        catch { }
                        if (string.IsNullOrEmpty(Token)) Token = token ?? "";
                        ws = c;
                        return "";
                    }
                    if (msg.Contains("ms.channel.unauthorized")) { lastErr = "TV menolak (tidak diizinkan)"; break; }
                    if (msg.Contains("ms.channel.timeOut")) { lastErr = "TV menunggu persetujuan terlalu lama"; break; }
                }
                c.Dispose();
                if (lastErr == "") lastErr = "TV tidak menjawab";
            }
            catch (Exception ex)
            {
                lastErr = ex.Message;
            }
        }
        return lastErr;
    }

    public async Task SendKeyAsync(string key, CancellationToken ct = default)
    {
        if (!Connected) throw new InvalidOperationException("Belum tersambung");
        string json = "{\"method\":\"ms.remote.control\",\"params\":{\"Cmd\":\"Click\",\"DataOfCmd\":\"" + key +
                      "\",\"Option\":\"false\",\"TypeOfRemote\":\"SendRemoteKey\"}}";
        await ws.SendAsync(new ArraySegment<byte>(Encoding.UTF8.GetBytes(json)),
            WebSocketMessageType.Text, true, ct);
    }

    public void Disconnect()
    {
        try { ws?.Dispose(); } catch { }
        ws = null;
    }

    public void Dispose() => Disconnect();

    // ------------------------- MAC & Wake-on-LAN -------------------------

    /// <summary>Cari alamat MAC TV dari tabel ARP laptop (TV harus sedang menyala/baru dikontak).</summary>
    public static string FindMac(string ip)
    {
        try
        {
            try { using var p = new Ping(); p.Send(ip, 1000); } catch { }
            var psi = new ProcessStartInfo("arp", "-a " + ip)
            { RedirectStandardOutput = true, UseShellExecute = false, CreateNoWindow = true };
            using var pr = Process.Start(psi);
            string o = pr.StandardOutput.ReadToEnd();
            pr.WaitForExit(3000);
            var m = Regex.Match(o, @"([0-9a-fA-F]{2}[-:]){5}[0-9a-fA-F]{2}");
            if (m.Success) return m.Value.Replace('-', ':').ToLowerInvariant();
        }
        catch { }
        return "";
    }

    public static void WakeOnLan(string mac)
    {
        var hex = Regex.Replace(mac, "[^0-9a-fA-F]", "");
        if (hex.Length != 12) throw new ArgumentException("MAC tidak valid");
        var mb = new byte[6];
        for (int i = 0; i < 6; i++) mb[i] = Convert.ToByte(hex.Substring(i * 2, 2), 16);
        var pkt = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) pkt[i] = 0xFF;
        for (int i = 0; i < 16; i++) Array.Copy(mb, 0, pkt, 6 + i * 6, 6);
        using var udp = new UdpClient();
        udp.EnableBroadcast = true;
        udp.Send(pkt, pkt.Length, new IPEndPoint(IPAddress.Broadcast, 9));
        udp.Send(pkt, pkt.Length, new IPEndPoint(IPAddress.Broadcast, 7));
    }
}
