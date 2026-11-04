package com.agi.layarcctv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Seluruh pengaturan tampilan. Kamera index-0 = kotak besar. */
public class Config {
    public String layout = "1+5";   // "1+5", "1+3", "2x2"
    public String defaultUser = "admin";
    public String defaultPass = "";
    public int networkCaching = 600; // ms, makin besar makin stabil tapi makin delay
    public List<Camera> cameras = new ArrayList<>();

    public static Config fromJson(JSONObject o) {
        Config c = new Config();
        c.layout = o.optString("layout", "1+5");
        c.defaultUser = o.optString("defaultUser", "admin");
        c.defaultPass = o.optString("defaultPass", "");
        c.networkCaching = o.optInt("networkCaching", 600);
        JSONArray arr = o.optJSONArray("cameras");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject co = arr.optJSONObject(i);
                if (co != null) c.cameras.add(Camera.fromJson(co));
            }
        }
        return c;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("layout", layout);
            o.put("defaultUser", defaultUser);
            o.put("defaultPass", defaultPass);
            o.put("networkCaching", networkCaching);
            JSONArray arr = new JSONArray();
            for (Camera cam : cameras) arr.put(cam.toJson());
            o.put("cameras", arr);
        } catch (Exception ignored) {}
        return o;
    }

    /** Konfigurasi awal: 1 besar (PTZ .65) + 5 kecil. Password diisi dari HP. */
    public static Config defaultConfig() {
        Config c = new Config();
        c.layout = "1+5";
        c.defaultUser = "admin";
        c.defaultPass = "";           // isi password DVR .50 di sini lewat HP

        c.cameras.add(cam("PTZ Produksi", "10.46.10.65", 1, false, "", ""));   // KOTAK BESAR
        c.cameras.add(cam("Kamera 100",   "10.46.10.100", 1, true, "", ""));    // password sendiri
        c.cameras.add(cam("DVR Cam 1",    "10.46.10.50", 1, true, "", ""));
        c.cameras.add(cam("DVR Cam 2",    "10.46.10.50", 2, true, "", ""));
        c.cameras.add(cam("DVR Cam 3",    "10.46.10.50", 3, true, "", ""));
        c.cameras.add(cam("DVR Cam 4",    "10.46.10.50", 4, true, "", ""));
        return c;
    }

    private static Camera cam(String name, String ip, int ch, boolean sub, String u, String p) {
        Camera c = new Camera();
        c.name = name; c.ip = ip; c.channel = ch; c.sub = sub; c.user = u; c.pass = p;
        return c;
    }
}
