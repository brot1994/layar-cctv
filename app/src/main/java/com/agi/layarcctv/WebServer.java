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
