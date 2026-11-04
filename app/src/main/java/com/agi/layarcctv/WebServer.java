package com.agi.layarcctv;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/** Web server kecil: halaman setting yang dibuka dari HP di jaringan yang sama. */
public class WebServer extends NanoHTTPD {

    public interface OnChanged { void changed(); }

    private final Context ctx;
    private final OnChanged onChanged;
    private String pageCache = null;

    public WebServer(Context ctx, int port, OnChanged onChanged) {
        super(port);
        this.ctx = ctx.getApplicationContext();
        this.onChanged = onChanged;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        if (uri.equals("/config") && Method.GET.equals(method)) {
            Config cfg = ConfigStore.load(ctx);
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                    cfg.toJson().toString());
        }

        if (uri.equals("/save") && Method.POST.equals(method)) {
            try {
                Map<String, String> body = new HashMap<>();
                session.parseBody(body);
                String json = body.get("postData");
                if (json == null) json = "{}";
                ConfigStore.saveRaw(ctx, json);
                if (onChanged != null) onChanged.changed();
                return newFixedLengthResponse(Response.Status.OK, "application/json",
                        "{\"ok\":true}");
            } catch (Exception e) {
                String m = e.getMessage() == null ? "error" : e.getMessage().replace("\"", "'");
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                        "{\"ok\":false,\"error\":\"" + m + "\"}");
            }
        }

        if (uri.equals("/reload") && Method.POST.equals(method)) {
            if (onChanged != null) onChanged.changed();
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}");
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
