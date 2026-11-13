package com.agi.layarcctv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Ambil satu foto (JPEG) langsung dari kamera/DVR Hikvision lewat HTTP (ISAPI), tanpa lewat dekoder TV. */
public class Snap {

    public static Bitmap fetch(Camera cam, Config cfg) {
        String user = (cam.user != null && !cam.user.isEmpty()) ? cam.user : cfg.defaultUser;
        String pass = (cam.pass != null && !cam.pass.isEmpty()) ? cam.pass : cfg.defaultPass;
        int code = cam.channel * 100 + 1;
        String[] paths = {
                "/ISAPI/Streaming/channels/" + code + "/picture",
                "/Streaming/channels/" + code + "/picture"
        };
        String lastErr = "";
        for (String path : paths) {
            try {
                byte[] data = get("http://" + cam.ip + path, path, user, pass);
                if (data != null && data.length > 500) {
                    Bitmap b = decode(data, 1280);
                    if (b != null) return b;
                    lastErr = "bukan gambar";
                }
            } catch (Throwable t) {
                lastErr = t.toString();
            }
        }
        StatsLog.log("[Snapshot] gagal " + cam.name + " (" + cam.ip + "): " + lastErr);
        return null;
    }

    private static Bitmap decode(byte[] data, int maxW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxW) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
    }

    private static byte[] get(String url, String uriPath, String user, String pass) throws Exception {
        HttpURLConnection c = open(url, null);
        int code = c.getResponseCode();
        if (code == 401) {
            String ch = c.getHeaderField("WWW-Authenticate");
            c.disconnect();
            if (ch == null) throw new Exception("401 tanpa tantangan login");
            String auth;
            if (ch.toLowerCase(Locale.US).startsWith("basic")) {
                auth = "Basic " + android.util.Base64.encodeToString((user + ":" + pass).getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            } else {
                auth = digest(ch, user, pass, uriPath);
            }
            c = open(url, auth);
            code = c.getResponseCode();
        }
        if (code != 200) {
            c.disconnect();
            throw new Exception("HTTP " + code);
        }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bo.write(buf, 0, n);
                if (bo.size() > 4_000_000) throw new Exception("gambar terlalu besar");
            }
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String auth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestMethod("GET");
        if (auth != null) c.setRequestProperty("Authorization", auth);
        return c;
    }

    private static String md5(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    private static String digest(String header, String user, String pass, String uri) throws Exception {
        Map<String, String> p = new HashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\w+)=(\"([^\"]*)\"|([^,\\s]*))").matcher(header);
        while (m.find()) p.put(m.group(1).toLowerCase(Locale.US), m.group(3) != null ? m.group(3) : m.group(4));
        String realm = p.get("realm"), nonce = p.get("nonce"), opaque = p.get("opaque"), qop = p.get("qop");
        String ha1 = md5(user + ":" + realm + ":" + pass);
        String ha2 = md5("GET:" + uri);
        StringBuilder sb = new StringBuilder("Digest username=\"" + user + "\", realm=\"" + realm + "\", nonce=\"" + nonce
                + "\", uri=\"" + uri + "\"");
        if (qop != null && qop.contains("auth")) {
            String nc = "00000001";
            String cnonce = md5(String.valueOf(System.nanoTime())).substring(0, 16);
            String resp = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
            sb.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append("\", response=\"").append(resp).append("\"");
        } else {
            sb.append(", response=\"").append(md5(ha1 + ":" + nonce + ":" + ha2)).append("\"");
        }
        if (opaque != null) sb.append(", opaque=\"").append(opaque).append("\"");
        return sb.toString();
    }
}
