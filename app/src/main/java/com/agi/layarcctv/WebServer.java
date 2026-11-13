package com.agi.layarcctv;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/** Web server kecil: halaman setting + kontrol rotasi, dibuka dari HP/laptop di jaringan yang sama. */
public class WebServer extends NanoHTTPD {

    /** Jembatan ke MainActivity. */
    public interface Controller {
        void onConfigChanged();
        /** Jalankan perintah (next/prev/pause/resume/show) lalu kembalikan status JSON. */
        String control(String cmd, int index);
        String status();
        /** Foto layar TV (JPEG) atau null bila gagal; memblokir sampai selesai. */
        byte[] screenshot();
    }

    private final Context ctx;
    private final Controller controller;
    private String pageCache = null;

    public WebServer(Context ctx, int port, Controller controller) {
        super(port);
        this.ctx = ctx.getApplicationContext();
        this.controller = controller;
    }

    private static Response json(String body) {
        return newFixedLengthResponse(Response.Status.OK, "application/json", body);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        if (uri.equals("/config") && Method.GET.equals(method)) {
            return json(ConfigStore.load(ctx).toJson().toString());
        }

        if (uri.equals("/log")) {
            Map<String, String> q = session.getParms();
            if ("1".equals(q.get("clear"))) StatsLog.clear(ctx);
            Response r = newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8",
                    StatsLog.tail(ctx, 400 * 1024));
            r.addHeader("Content-Disposition", "inline; filename=\"layar-tv-log.txt\"");
            return r;
        }

        if (uri.equals("/layar")) return serveShot(session.getParms());

        if (uri.equals("/status")) {
            return json(controller.status());
        }

        if (uri.equals("/ctl")) {
            Map<String, String> q = session.getParms();
            String cmd = q.get("cmd");
            int idx = -1;
            try { idx = Integer.parseInt(q.get("i")); } catch (Exception ignored) {}
            return json(controller.control(cmd == null ? "" : cmd, idx));
        }

        if (uri.equals("/save") && Method.POST.equals(method)) {
            try {
                Map<String, String> body = new HashMap<>();
                session.parseBody(body);
                String data = body.get("postData");
                if (data == null) data = "{}";
                ConfigStore.saveRaw(ctx, data);
                controller.onConfigChanged();
                return json("{\"ok\":true}");
            } catch (Exception e) {
                String m = e.getMessage() == null ? "error" : e.getMessage().replace("\"", "'");
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                        "{\"ok\":false,\"error\":\"" + m + "\"}");
            }
        }

        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page());
    }

    private long lastShot = 0;

    private static boolean same(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int d = 0;
        for (int i = 0; i < a.length(); i++) d |= a.charAt(i) ^ b.charAt(i);
        return d == 0;
    }

    private Response text(Response.Status st, String msg) {
        return newFixedLengthResponse(st, "text/html; charset=utf-8",
                "<meta name=viewport content='width=device-width'><body style='font-family:sans-serif;background:#111;color:#eee;padding:20px'>" + msg);
    }

    /** /layar?p=PASSWORD  -> halaman berisi foto;  /layar?p=PASSWORD&img=1  -> JPEG saja. */
    private Response serveShot(Map<String, String> q) {
        String want = ConfigStore.load(ctx).shotPass;
        if (want == null || want.isEmpty())
            return text(Response.Status.FORBIDDEN, "Foto layar belum aktif. Isi \"Password foto layar\" di halaman setting lalu Simpan.");
        if (!same(want, q.get("p"))) {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            return text(Response.Status.FORBIDDEN, "Password salah.");
        }
        String pe;
        try { pe = java.net.URLEncoder.encode(want, "UTF-8"); } catch (Exception e) { pe = ""; }
        if (!"1".equals(q.get("img"))) {
            return text(Response.Status.OK,
                    "<h3>Layar TV sekarang</h3>"
                    + "<p><a style='color:#8ab4f8' href='/layar?p=" + pe + "'>Muat ulang foto</a></p>"
                    + "<img style='max-width:100%;border:1px solid #444' src='/layar?p=" + pe + "&img=1&t=" + System.currentTimeMillis() + "'>");
        }
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (now - lastShot < 2000) {
                try { Thread.sleep(2000 - (now - lastShot)); } catch (InterruptedException ignored) {}
            }
            lastShot = System.currentTimeMillis();
            byte[] jpg = controller.screenshot();
            if (jpg == null)
                return text(Response.Status.SERVICE_UNAVAILABLE, "Foto gagal: aplikasi sedang tidak tampil di layar, atau pengambilan layar ditolak.");
            Response r = newFixedLengthResponse(Response.Status.OK, "image/jpeg", new java.io.ByteArrayInputStream(jpg), jpg.length);
            r.addHeader("Cache-Control", "no-store");
            return r;
        }
    }

    private String page() {
        if (pageCache != null) return pageCache;
        try (InputStream in = ctx.getAssets().open("setting.html")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            pageCache = out.toString("UTF-8");
        } catch (Exception e) {
            pageCache = "<html><body>setting.html tidak ditemukan</body></html>";
        }
        return pageCache;
    }
}
