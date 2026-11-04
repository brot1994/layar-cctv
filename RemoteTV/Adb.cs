using System.Diagnostics;
using System.Text;

namespace RemoteTV;

/// <summary>Pembungkus adb.exe. Perintah dijalankan satu per satu agar urutan tombol terjaga.</summary>
public class Adb
{
    public string AdbPath { get; }
    public string ToolsDir { get; }
    readonly SemaphoreSlim gate = new(1, 1);

    public Adb()
    {
        string exeDir = Path.GetDirectoryName(Environment.ProcessPath) ?? AppContext.BaseDirectory;
        ToolsDir = Path.Combine(exeDir, "tools");
        string bundled = Path.Combine(ToolsDir, "adb.exe");
        AdbPath = File.Exists(bundled) ? bundled : "adb"; // cadangan: adb di PATH
    }

    public bool HasScrcpy => File.Exists(Path.Combine(ToolsDir, "scrcpy.exe"));
    public string ScrcpyPath => Path.Combine(ToolsDir, "scrcpy.exe");

    ProcessStartInfo Make(IEnumerable<string> args)
    {
        var psi = new ProcessStartInfo(AdbPath)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);
        return psi;
    }

    public async Task<(int Code, string Output)> RunAsync(IEnumerable<string> args, int timeoutMs = 30000)
    {
        await gate.WaitAsync();
        try
        {
            using var p = new Process { StartInfo = Make(args) };
            try { p.Start(); }
            catch (Exception ex)
            {
                return (-1, "Tidak bisa menjalankan adb: " + ex.Message);
            }

            var outTask = p.StandardOutput.ReadToEndAsync();
            var errTask = p.StandardError.ReadToEndAsync();
            using var cts = new CancellationTokenSource(timeoutMs);
            try
            {
                await p.WaitForExitAsync(cts.Token);
            }
            catch (OperationCanceledException)
            {
                try { p.Kill(true); } catch { }
                return (-2, "Waktu habis (adb tidak menjawab).");
            }

            string o = (await outTask).Trim();
            string e = (await errTask).Trim();
            string text = string.IsNullOrEmpty(e) ? o : (string.IsNullOrEmpty(o) ? e : o + "\n" + e);
            return (p.ExitCode, text);
        }
        finally { gate.Release(); }
    }

    /// <summary>Untuk keluaran biner (screenshot PNG lewat exec-out).</summary>
    public async Task<byte[]> RunBinaryAsync(IEnumerable<string> args, int timeoutMs = 30000)
    {
        await gate.WaitAsync();
        try
        {
            using var p = new Process { StartInfo = Make(args) };
            p.Start();
            var ms = new MemoryStream();
            var copy = p.StandardOutput.BaseStream.CopyToAsync(ms);
            var err = p.StandardError.ReadToEndAsync();
            using var cts = new CancellationTokenSource(timeoutMs);
            try
            {
                await p.WaitForExitAsync(cts.Token);
                await copy;
            }
            catch (OperationCanceledException)
            {
                try { p.Kill(true); } catch { }
                throw new TimeoutException("Waktu habis.");
            }
            return ms.ToArray();
        }
        finally { gate.Release(); }
    }

    /// <summary>Escape teks untuk `adb shell input text` (spasi jadi %s, karakter khusus shell di-escape).</summary>
    public static string EscapeForInputText(string s)
    {
        var sb = new StringBuilder();
        foreach (char c in s)
        {
            if (c == ' ') sb.Append("%s");
            else if ("\\'\"&|;<>()$`!*?[]{}#~^".IndexOf(c) >= 0) sb.Append('\\').Append(c);
            else sb.Append(c);
        }
        return sb.ToString();
    }
}
