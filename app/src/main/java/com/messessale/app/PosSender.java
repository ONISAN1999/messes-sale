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

    /** ลิงก์ฐานข้อมูลของร้าน (ฝังมากับแอป — ไม่ต้องตั้งค่า) */
    public static final String DEFAULT_URL = "https://gungpao-bangfan-default-rtdb.asia-southeast1.firebasedatabase.app";

    public static String dbUrl(Context c) {
        String u = Store.prefs(c).getString(K_URL, "").trim();
        return u.isEmpty() ? DEFAULT_URL : u;
    }

    public static String shop(Context c) {
        String s = Store.prefs(c).getString(K_SHOP, "bangfan").trim();
        return s.isEmpty() ? "bangfan" : s;
    }

    public static boolean ready(Context c) { return !dbUrl(c).isEmpty(); }

    /** ส่วนต่างเวลาเครื่องนี้กับเซิร์ฟเวอร์ — ให้ "นาทีที่แล้ว" ตรงกับเครื่อง POS */
    public static long offset = 0;
    public static long now() { return System.currentTimeMillis() + offset; }

    public static void save(Context c, String url, String shopCode) {
        Store.prefs(c).edit()
                .putString(K_URL, url == null ? "" : url.trim())
                .putString(K_SHOP, shopCode == null ? "" : shopCode.trim())
                .apply();
    }

    /* ============ ประวัติการส่ง (เก็บในเครื่อง) ============ */

    public static final String K_LOG = "pos_log";
    private static final int LOG_MAX = 40;

    /** 1 บรรทัดของประวัติการส่งออเดอร์เข้า POS */
    public static class SendLog {
        public String id = "", no = "", place = "", err = "";
        public int total = 0;
        public long at = 0;
        public boolean ok = false;

        public int minAgo() {
            if (at <= 0) return 0;
            return (int) ((System.currentTimeMillis() - at) / 60000L);
        }

        public String clock() {
            if (at <= 0) return "";
            return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                    .format(new java.util.Date(at));
        }

        public String ago() {
            int m = minAgo();
            if (m < 1) return "เมื่อครู่นี้";
            if (m < 60) return m + " นาทีที่แล้ว";
            return (m / 60) + " ชม. " + (m % 60) + " นาทีที่แล้ว";
        }
    }

    /** บันทึกผลการส่ง 1 ครั้ง */
    public static void logAdd(Context c, String id, String no, String place,
                              int total, boolean ok, String err) {
        try {
            JSONArray arr = new JSONArray(Store.prefs(c).getString(K_LOG, "[]"));
            JSONObject o = new JSONObject();
            o.put("id", id == null ? "" : id);
            o.put("no", no == null ? "" : no.trim());
            o.put("place", place == null ? "" : place.trim());
            o.put("total", total);
            o.put("ok", ok);
            o.put("err", err == null ? "" : err);
            o.put("at", System.currentTimeMillis());
            arr.put(o);
            while (arr.length() > LOG_MAX) arr.remove(0);
            Store.prefs(c).edit().putString(K_LOG, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** อ่านประวัติ — ใหม่สุดขึ้นก่อน */
    public static java.util.List<SendLog> logList(Context c) {
        java.util.List<SendLog> out = new java.util.ArrayList<>();
        try {
            JSONArray arr = new JSONArray(Store.prefs(c).getString(K_LOG, "[]"));
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                SendLog s = new SendLog();
                s.id = o.optString("id", "");
                s.no = o.optString("no", "");
                s.place = o.optString("place", "");
                s.total = o.optInt("total", 0);
                s.ok = o.optBoolean("ok", false);
                s.err = o.optString("err", "");
                s.at = o.optLong("at", 0);
                out.add(s);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void logClear(Context c) {
        Store.prefs(c).edit().putString(K_LOG, "[]").apply();
    }

    /* ============ ออเดอร์ที่แจ้ง "เสร็จ" ไปแล้ว (กันเตือนซ้ำ) ============ */
    public static final String K_DONE = "pos_done_seen";

    public static java.util.Set<String> doneSeen(Context c) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            JSONArray arr = new JSONArray(Store.prefs(c).getString(K_DONE, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i));
        } catch (Exception ignored) {}
        return out;
    }

    public static void markDoneSeen(Context c, String id) {
        try {
            JSONArray arr = new JSONArray(Store.prefs(c).getString(K_DONE, "[]"));
            arr.put(id);
            while (arr.length() > 80) arr.remove(0);
            Store.prefs(c).edit().putString(K_DONE, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** สถานะออเดอร์ที่อ่านกลับมาจากเครื่อง POS */
    public static class OrderStatus {
        public String id = "", no = "", place = "", status = "ใหม่";
        public int total = 0;
        public long createdAt = 0;

        /** นาทีที่ผ่านไปตั้งแต่ส่ง */
        public int minAgo() {
            if (createdAt <= 0) return 0;
            return (int) Math.max(0, (now() - createdAt) / 60000L);
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
        try { long srv = con.getHeaderFieldDate("Date", 0); if (srv > 0) offset = srv - System.currentTimeMillis(); } catch (Exception ignored) {}
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
    public static String send(Context c, String orderNo, String place, String text,
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
        o.put("createdAt", new JSONObject().put(".sv", "timestamp"));   // ให้เซิร์ฟเวอร์ประทับเวลา กันนาฬิกามือถือ/POS ไม่ตรงกัน

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
        java.io.InputStream rin = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        String resp = "";
        if (rin != null) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = rin.read(buf)) > 0) bos.write(buf, 0, n);
            rin.close();
            resp = new String(bos.toByteArray(), "UTF-8");
        }
        con.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + " " + resp);
        try { return new JSONObject(resp).optString("name", ""); } catch (Exception e) { return ""; }
    }
}
