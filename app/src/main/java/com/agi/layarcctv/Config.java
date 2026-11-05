package com.agi.layarcctv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Seluruh pengaturan. Yang disimpan: user/password default, buffer, dan daftar halaman.
 * Field layout & cameras hanya "pandangan" untuk satu halaman CCTV yang sedang digambar
 * (lihat forPage) dan tidak ikut disimpan.
 */
public class Config {
    public String defaultUser = "admin";
    public String defaultPass = "";
    public int networkCaching = 600; // ms, makin besar makin stabil tapi makin delay
    public List<Page> pages = new ArrayList<>();

    // pandangan satu halaman CCTV (dipakai kode penggambar grid)
    public String layout = "1+5";
    public List<Camera> cameras = new ArrayList<>();

    public static Config fromJson(JSONObject o) {
        Config c = new Config();
        c.defaultUser = o.optString("defaultUser", "admin");
        c.defaultPass = o.optString("defaultPass", "");
        c.networkCaching = o.optInt("networkCaching", 600);

        JSONArray pa = o.optJSONArray("pages");
        if (pa != null) {
            for (int i = 0; i < pa.length(); i++) {
                JSONObject po = pa.optJSONObject(i);
                if (po != null) c.pages.add(Page.fromJson(po));
            }
        } else {
            // format lama (sebelum ada rotasi): satu layout + daftar kamera -> jadi satu halaman CCTV
            Page p = new Page();
            p.type = Page.CCTV;
            p.name = "CCTV";
            p.layout = o.optString("layout", "1+5");
            JSONArray arr = o.optJSONArray("cameras");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject co = arr.optJSONObject(i);
                    if (co != null) p.cameras.add(Camera.fromJson(co));
                }
            }
            c.pages.add(p);
        }
        return c;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("defaultUser", defaultUser);
            o.put("defaultPass", defaultPass);
            o.put("networkCaching", networkCaching);
            JSONArray arr = new JSONArray();
            for (Page p : pages) arr.put(p.toJson());
            o.put("pages", arr);
        } catch (Exception ignored) {}
        return o;
    }

    /** Salinan ringan untuk menggambar satu halaman CCTV. */
    public Config forPage(Page p) {
        Config c = new Config();
        c.defaultUser = defaultUser;
        c.defaultPass = defaultPass;
        c.networkCaching = networkCaching;
        c.layout = p.layout;
        c.cameras = p.cameras;
        return c;
    }

    /** Konfigurasi awal: satu halaman CCTV 1 besar + 5 kecil. Password diisi dari halaman setting. */
    public static Config defaultConfig() {
        Config c = new Config();
        Page p = new Page();
        p.type = Page.CCTV;
        p.name = "CCTV";
        p.layout = "1+5";
        p.cameras.add(cam("PTZ Produksi", "10.46.10.65", 1, false));   // KOTAK BESAR
        p.cameras.add(cam("Kamera 100",   "10.46.10.100", 1, true));
        p.cameras.add(cam("DVR Cam 1",    "10.46.10.50", 1, true));
        p.cameras.add(cam("DVR Cam 2",    "10.46.10.50", 2, true));
        p.cameras.add(cam("DVR Cam 3",    "10.46.10.50", 3, true));
        p.cameras.add(cam("DVR Cam 4",    "10.46.10.50", 4, true));
        c.pages.add(p);
        return c;
    }

    private static Camera cam(String name, String ip, int ch, boolean sub) {
        Camera c = new Camera();
        c.name = name; c.ip = ip; c.channel = ch; c.sub = sub; c.quality = sub ? "sub" : "main";
        return c;
    }
}
