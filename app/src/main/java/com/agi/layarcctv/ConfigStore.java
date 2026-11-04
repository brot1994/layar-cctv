package com.agi.layarcctv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Simpan/muat Config sebagai JSON di SharedPreferences. */
public class ConfigStore {
    private static final String PREF = "layarcctv";
    private static final String KEY = "config";

    public static Config load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String json = sp.getString(KEY, null);
        if (json == null) {
            return Config.defaultConfig();
        }
        try {
            return Config.fromJson(new JSONObject(json));
        } catch (Exception e) {
            return Config.defaultConfig();
        }
    }

    public static void save(Context ctx, Config cfg) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().putString(KEY, cfg.toJson().toString()).apply();
    }

    public static void saveRaw(Context ctx, String json) throws Exception {
        // validasi dulu
        Config cfg = Config.fromJson(new JSONObject(json));
        save(ctx, cfg);
    }
}
