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

    /** สถานะออเดอร์ที่อ่านกลับมาจากเครื่อง POS */
    public static class OrderStatus {
        public String id = "", no = "", place = "", status = "ใหม่";
        public int total = 0;
        public long createdAt = 0;

        /** นาทีที่ผ่านไปตั้งแต่ส่ง */
        public int minAgo() {
            if (createdAt <= 0) return 0;
            return (int) ((System.currentTimeMillis() - createdAt) / 60000L);
        }

        public int color() {
            if (status.equals("เสร็จ")) return 0xFF22C55E;      // เขียว = POS ทำเสร็จแล้ว
            if (status.equals("กำลังทำ")) return 0xFFF97316;    // ส้ม = POS กดรับแล้ว
            return 0xFFFACC15;                                   // เหลือง = ยังไม่มีใครกดรับ
        }

        public String label() {
            if (status.equals("เสร็จ")) return "เสร็จแล้ว";
            if (status.equals("กำลังทำ")) return "POS รับแล้ว กำลังทำ";
            return "รอ POS กดรับ";
        }
    }

    /** ดึงสถานะออเดอร์ทั้งหมด — เรียกจาก background thread เท่านั้น */
    public static java.util.List<OrderStatus> fetchAll(Context c) throws Exception {
        java.util.List<OrderStatus> out = new java.util.ArrayList<>();
        String u = dbUrl(c);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        String endpoint = u + "/shops/" + shop(c) + "/orders.json";

        HttpURLConnection con = (HttpURLConnection) new URL(endpoint).openConnection();
        con.setConnectTimeout(9000);
        con.setReadTimeout(12000);
        con.setRequestMethod("GET");
        int code = con.getResponseCode();
        java.io.InputStream in = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        String body = "";
        if (in != null) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            body = new String(bos.toByteArray(), "UTF-8");
        }
        con.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        if (body.isEmpty() || body.equals("null")) return out;

        JSONObject root = new JSONObject(body);
        java.util.Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            JSONObject o = root.optJSONObject(k);
            if (o == null) continue;
            OrderStatus s = new OrderStatus();
            s.id = k;
            s.no = o.optString("no", "");
            s.place = o.optString("place", "");
            s.status = o.optString("status", "ใหม่");
            s.total = o.optInt("total", 0);
            s.createdAt = o.optLong("createdAt", 0);
            out.add(s);
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(b.createdAt, a.createdAt)); // ใหม่สุดขึ้นก่อน
        return out;
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
