using System.Text.Json;

namespace RemoteSamsung;

public class TvEntry
{
    public string Name { get; set; } = "";
    public string Ip { get; set; } = "";
    public string Token { get; set; } = "";   // token persetujuan dari TV (disimpan setelah klik Izinkan)
    public string Mac { get; set; } = "";     // untuk menyalakan TV dari kondisi mati (Wake-on-LAN)

    public override string ToString()
        => string.IsNullOrWhiteSpace(Name) ? Ip : Name + " (" + Ip + ")";
}

public class AppSettings
{
    public List<TvEntry> Tvs { get; set; } = new();
    public string LastIp { get; set; } = "";

    static string FilePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        "RemoteSamsung", "settings.json");

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath)) ?? new AppSettings();
        }
        catch { }
        return new AppSettings();
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
            File.WriteAllText(FilePath,
                JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { }
    }
}
