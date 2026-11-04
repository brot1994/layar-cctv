package com.agi.layarcctv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Satu "halaman" dalam rotasi: grid CCTV atau halaman web. */
public class Page {
    public static final String CCTV = "cctv";
    public static final String WEB = "web";

    public String type = CCTV;
    public String name = "";
    public int duration = 60;       // detik
    public boolean enabled = true;
    public String from = "";        // jam tayang "HH:mm", kosong = selalu
    public String to = "";

    // khusus web
    public String url = "";
    public int zoom = 0;            // persen, 0 = otomatis
    public int refreshMin = 0;      // 0 = tidak refresh otomatis
    public boolean ignoreSsl = false;

    // khusus CCTV
    public String layout = "1+5";
    public List<Camera> cameras = new ArrayList<>();

    public boolean isWeb() { return WEB.equals(type); }

    public String title() {
        if (name != null && !name.isEmpty()) return name;
        return isWeb() ? url : "CCTV";
    }

    public static Page fromJson(JSONObject o) {
        Page p = new Page();
        p.type = WEB.equals(o.optString("type", CCTV)) ? WEB : CCTV;
        p.name = o.optString("name", "");
        p.duration = o.optInt("duration", 60);
        p.enabled = o.optBoolean("enabled", true);
        p.from = o.optString("from", "");
        p.to = o.optString("to", "");
        p.url = o.optString("url", "");
        p.zoom = o.optInt("zoom", 0);
        p.refreshMin = o.optInt("refreshMin", 0);
        p.ignoreSsl = o.optBoolean("ignoreSsl", false);
        p.layout = o.optString("layout", "1+5");
        JSONArray arr = o.optJSONArray("cameras");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject co = arr.optJSONObject(i);
                if (co != null) p.cameras.add(Camera.fromJson(co));
            }
        }
        return p;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("type", type);
            o.put("name", name);
            o.put("duration", duration);
            o.put("enabled", enabled);
            o.put("from", from);
            o.put("to", to);
            if (isWeb()) {
                o.put("url", url);
                o.put("zoom", zoom);
                o.put("refreshMin", refreshMin);
                o.put("ignoreSsl", ignoreSsl);
            } else {
                o.put("layout", layout);
                JSONArray arr = new JSONArray();
                for (Camera c : cameras) arr.put(c.toJson());
                o.put("cameras", arr);
            }
        } catch (Exception ignored) {}
        return o;
    }

    /** Aktif bila dicentang dan (bila ada jam tayang) jam sekarang berada di dalamnya. */
    public boolean isActiveNow() {
        if (!enabled) return false;
        int f = parseMin(from);
        int t = parseMin(to);
        if (f < 0 || t < 0 || f == t) return true; // jam tayang tidak diisi / tidak valid
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (f < t) return now >= f && now < t;
        return now >= f || now < t; // melewati tengah malam
    }

    private static int parseMin(String s) {
        if (s == null) return -1;
        s = s.trim();
        if (s.isEmpty()) return -1;
        try {
            String[] p = s.split(":");
            int h = Integer.parseInt(p[0].trim());
            int m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }
}
