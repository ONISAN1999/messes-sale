package com.messessale.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * ส่งออเดอร์ขึ้น Firebase Realtime Database ให้เครื่อง POS เห็น
 * (ใช้ REST ตรงๆ ไม่ต้องมี Firebase SDK)
 */
public class PosSender {

    public static final String K_URL = "pos_db_url";
    public static final String K_SHOP = "pos_shop";

    public static String dbUrl(Context c) {
        return Store.prefs(c).getString(K_URL, "").trim();
    }

    public static String shop(Context c) {
        String s = Store.prefs(c).getString(K_SHOP, "bangfan").trim();
        return s.isEmpty() ? "bangfan" : s;
    }

    public static boolean ready(Context c) { return !dbUrl(c).isEmpty(); }

    public static void save(Context c, String url, String shopCode) {
        Store.prefs(c).edit()
                .putString(K_URL, url == null ? "" : url.trim())
                .putString(K_SHOP, shopCode == null ? "" : shopCode.trim())
                .apply();
    }

    /** ส่งออเดอร์ 1 ใบ — เรียกจาก background thread เท่านั้น */
    public static void send(Context c, String orderNo, String place, String text,
                            int total, String payInfo) throws Exception {
        String u = dbUrl(c);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        String endpoint = u + "/shops/" + shop(c) + "/orders.json";

        JSONObject o = new JSONObject();
        o.put("no", orderNo == null ? "" : orderNo.trim());
        o.put("place", place == null ? "" : place.trim());
        o.put("text", text == null ? "" : text);
        o.put("total", total);
        o.put("pay", payInfo == null ? "" : payInfo);
        o.put("status", "ใหม่");
        o.put("createdAt", System.currentTimeMillis());

        JSONArray lines = new JSONArray();
        if (text != null) {
            for (String s : text.split("\n")) {
                String t = s.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("ยอดรวม") || t.startsWith("order")) continue;
                lines.put(t);
            }
        }
        o.put("lines", lines);

        HttpURLConnection con = (HttpURLConnection) new URL(endpoint).openConnection();
        con.setConnectTimeout(9000);
        con.setReadTimeout(12000);
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        con.setDoOutput(true);
        OutputStream os = con.getOutputStream();
        os.write(o.toString().getBytes("UTF-8"));
        os.close();

        int code = con.getResponseCode();
        con.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
    }
}
