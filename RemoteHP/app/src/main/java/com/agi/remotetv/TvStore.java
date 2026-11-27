package com.agi.remotetv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Daftar TV tersimpan (nama + IP) di SharedPreferences. */
final class TvStore {
    static final class Tv {
        String name, ip;
        Tv(String name, String ip) { this.name = name; this.ip = ip; }
        String label() { return name == null || name.trim().isEmpty() ? ip : name; }
        String host() { int i = ip.indexOf(':'); return i < 0 ? ip : ip.substring(0, i); }
        int port() {
            int i = ip.indexOf(':');
            if (i < 0) return 5555;
            try { return Integer.parseInt(ip.substring(i + 1)); } catch (Exception e) { return 5555; }
        }
    }

    private final SharedPreferences sp;

    TvStore(Context c) { sp = c.getSharedPreferences("remotetv", Context.MODE_PRIVATE); }

    List<Tv> list() {
        List<Tv> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString("tvs", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Tv(o.optString("name"), o.optString("ip")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    void save(List<Tv> tvs) {
        JSONArray a = new JSONArray();
        try {
            for (Tv t : tvs) a.put(new JSONObject().put("name", t.name).put("ip", t.ip));
        } catch (Exception ignored) { }
        sp.edit().putString("tvs", a.toString()).apply();
    }

    /** Tambah atau perbarui (berdasarkan IP). */
    void put(Tv tv) {
        List<Tv> l = list();
        boolean found = false;
        for (Tv t : l) if (t.ip.equals(tv.ip)) { t.name = tv.name; found = true; }
        if (!found) l.add(tv);
        save(l);
    }

    void remove(String ip) {
        List<Tv> l = list();
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).ip.equals(ip)) l.remove(i);
        save(l);
        if (ip.equals(lastIp())) setLastIp("");
    }

    Tv find(String ip) {
        for (Tv t : list()) if (t.ip.equals(ip)) return t;
        return null;
    }

    String lastIp() { return sp.getString("last", ""); }
    void setLastIp(String ip) { sp.edit().putString("last", ip).apply(); }
}
