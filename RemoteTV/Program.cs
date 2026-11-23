namespace RemoteTV;

static class Program
{
    [STAThread]
    static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();
        var f = new MainForm();
        if (args.Contains("--pengawas"))
        {
            f.WindowState = FormWindowState.Minimized;
        }
        Application.Run(f);
    }
}
