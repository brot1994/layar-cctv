package com.agi.layarcctv;

import org.json.JSONObject;

/** Satu kamera / channel yang ditampilkan di salah satu kotak. */
public class Camera {
    public String name = "";
    public String ip = "";
    public int port = 554;
    public int channel = 1;      // nomor kamera di DVR/NVR (kamera IP tunggal = 1)
    public boolean sub = false;  // false = main stream (tajam), true = sub stream (ringan)
    public String user = "";     // kosong = pakai default
    public String pass = "";     // kosong = pakai default
    public String path = "";     // override path RTSP (opsional, untuk non-Hikvision)

    public static Camera fromJson(JSONObject o) {
        Camera c = new Camera();
        c.name = o.optString("name", "");
        c.ip = o.optString("ip", "");
        c.port = o.optInt("port", 554);
        c.channel = o.optInt("channel", 1);
        c.sub = o.optBoolean("sub", false);
        c.user = o.optString("user", "");
        c.pass = o.optString("pass", "");
        c.path = o.optString("path", "");
        return c;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("ip", ip);
            o.put("port", port);
            o.put("channel", channel);
            o.put("sub", sub);
            o.put("user", user);
            o.put("pass", pass);
            o.put("path", path);
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
            int code = channel * 100 + (sub ? 2 : 1); // Hikvision: 101=cam1 main, 102=cam1 sub
            tail = "/Streaming/Channels/" + code;
        }
        return "rtsp://" + auth + ip + ":" + port + tail;
    }

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
