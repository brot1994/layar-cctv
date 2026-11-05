package com.agi.layarcctv;

import org.json.JSONObject;

/** Satu kamera / channel yang ditampilkan di salah satu kotak. */
public class Camera {
    public String name = "";
    public String ip = "";
    public int port = 554;
    public int channel = 1;      // nomor kamera di DVR/NVR (kamera IP tunggal = 1)
    public boolean sub = false;  // true = sub stream (ringan); diturunkan dari quality
    public String quality = "main"; // main (tajam) | mid (sedang, stream ke-3) | sub (ringan)
    public String user = "";     // kosong = pakai default
    public String pass = "";     // kosong = pakai default
    public String path = "";     // override path RTSP (opsional, untuk non-Hikvision)
    public String maxRes = "1080"; // "1080" = otomatis turun stream bila resolusi > 1080p; "none" = tanpa batas

    public static Camera fromJson(JSONObject o) {
        Camera c = new Camera();
        c.name = o.optString("name", "");
        c.ip = o.optString("ip", "");
        c.port = o.optInt("port", 554);
        c.channel = o.optInt("channel", 1);
        String q = o.optString("quality", "");
        if (!q.equals("main") && !q.equals("mid") && !q.equals("sub")) q = o.optBoolean("sub", false) ? "sub" : "main";
        c.quality = q;
        c.sub = q.equals("sub");
        c.user = o.optString("user", "");
        c.pass = o.optString("pass", "");
        c.path = o.optString("path", "");
        c.maxRes = "none".equals(o.optString("maxRes", "1080")) ? "none" : "1080";
        return c;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("ip", ip);
            o.put("port", port);
            o.put("channel", channel);
            o.put("sub", quality.equals("sub"));
            o.put("quality", quality);
            o.put("user", user);
            o.put("pass", pass);
            o.put("path", path);
            o.put("maxRes", maxRes);
        } catch (Exception ignored) {}
        return o;
    }

    /** Bangun URL RTSP lengkap. Kredensial kamera menimpa default bila diisi. */
    public String url(Config cfg) {
        String u = (user != null && !user.isEmpty()) ? user : cfg.defaultUser;
        String p = (pass != null && !pass.isEmpty()) ? pass : cfg.defaultPass;
        String auth = "";
        if (u != null && !u.isEmpty()) {
            auth = enc(u) + ":" + enc(p) + "@";
        }
        String tail;
        if (path != null && !path.isEmpty()) {
            tail = path.startsWith("/") ? path : "/" + path;
        } else {
            int code = channel * 100 + ("sub".equals(quality) ? 2 : "mid".equals(quality) ? 3 : 1); // 101 main, 102 sub, 103 stream ke-3
            tail = "/Streaming/Channels/" + code;
        }
        return "rtsp://" + auth + ip + ":" + port + tail;
    }

    /** URL untuk satu tingkat kualitas: "main" (x01), "mid" (x03), "sub" (x02). */
    public String urlFor(Config cfg, String q) {
        String keep = quality;
        quality = q;
        try { return url(cfg); } finally { quality = keep; }
    }

    /** Urutan stream yang dicoba: mulai dari kualitas pilihan lalu turun (main -> mid -> sub). */
    public String[] chain(Config cfg) {
        if (path != null && !path.isEmpty()) return new String[]{url(cfg)};
        if ("sub".equals(quality)) return new String[]{urlFor(cfg, "sub")};
        if ("mid".equals(quality)) return new String[]{urlFor(cfg, "mid"), urlFor(cfg, "sub")};
        return new String[]{urlFor(cfg, "main"), urlFor(cfg, "mid"), urlFor(cfg, "sub")};
    }

    public String key() { return ip + ":" + port + ":" + channel + ":" + quality; }

    /** Percent-encode khusus bagian userinfo (user & password). */
    private static String enc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                b.append(c);
            } else {
                b.append('%').append(String.format("%02X", (int) c));
            }
        }
        return b.toString();
    }
}
