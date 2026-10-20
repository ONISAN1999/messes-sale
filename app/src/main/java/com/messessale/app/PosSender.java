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

    /** รหัสร้านลับ (ฝังมากับแอป) — กฎ Firebase อนุญาตเฉพาะ path นี้ */
    public static final String SHOP = "bf-ehiK6Xb7nOheBEIyQb71NBJn";

    public static String shop(Context c) {
        String s = Store.prefs(c).getString(K_SHOP, "").trim();
        return (s.isEmpty() || s.equals("bangfan")) ? SHOP : s;
    }

    public static boolean ready(Context c) { return !dbUrl(c).isEmpty(); }

    /** ส่วนต่างเวลาเครื่องนี้กับเซิร์ฟเวอร์ — ให้ "นาทีที่แล้ว" ตรงกับเครื่อง POS */
    public static long offset = 0;
    /** เวลาที่ต่อคลาวด์สำเร็จล่าสุด (มิลลิวินาทีเครื่อง) — ไว้โชว์จุดสถานะบนฟอง */
    public static long lastOk = 0;
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
        public int edits = 0;          // แก้ไขไปแล้วกี่ครั้ง
        public String lines = "";      // ข้อความออเดอร์ (ไว้โหลดกลับมาแก้)
        public String pay = "";

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
        logAdd(c, id, no, place, total, ok, err, "", "");
    }

    public static void logAdd(Context c, String id, String no, String place,
                              int total, boolean ok, String err, String text, String pay) {
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
            o.put("edits", 0);
            o.put("lines", text == null ? "" : text);
            o.put("pay", pay == null ? "" : pay);
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
                s.edits = o.optInt("edits", 0);
                s.lines = o.optString("lines", "");
                s.pay = o.optString("pay", "");
                out.add(s);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** อัปเดตรายการเดิมหลังแก้ไขออเดอร์ (id เดิม) */
    public static void logEdit(Context c, String id, String no, String place, int total, String text, String pay) {
        try {
            JSONArray arr = new JSONArray(Store.prefs(c).getString(K_LOG, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || !id.equals(o.optString("id", ""))) continue;
                o.put("no", no == null ? "" : no.trim());
                o.put("place", place == null ? "" : place.trim());
                o.put("total", total);
                o.put("ok", true); o.put("err", "");
                o.put("at", System.currentTimeMillis());
                o.put("edits", o.optInt("edits", 0) + 1);
                o.put("lines", text == null ? "" : text);
                o.put("pay", pay == null ? "" : pay);
            }
            Store.prefs(c).edit().putString(K_LOG, arr.toString()).apply();
        } catch (Exception ignored) {}
        // แก้ไขแล้ว → ให้แจ้งเตือน "เสร็จ" ได้อีกครั้ง
        try {
            JSONArray arr = new JSONArray(Store.prefs(c).getString(K_DONE, "[]"));
            JSONArray keep = new JSONArray();
            for (int i = 0; i < arr.length(); i++) if (!id.equals(arr.optString(i))) keep.put(arr.optString(i));
            Store.prefs(c).edit().putString(K_DONE, keep.toString()).apply();
        } catch (Exception ignored) {}
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
        public long editedAt = 0, ackEditAt = 0;
        public String text = "", pay = "", by = "";
        public int edits = 0;
        public long preAt = 0; public String preType = ""; public int deposit = 0; public boolean paidFull = false; public int prepMin = 45;

        public boolean isPre() { return status.equals("พรี"); }
        public boolean isClosed() { return status.equals("เสร็จ") || status.equals("ยกเลิก"); }
        /** เลยเวลานัดแล้วแต่ยังไม่เสร็จ */
        public boolean preLate() { return preAt > 0 && !isClosed() && now() > preAt; }

        /** แปลงเป็นรายการประวัติ (ใช้กับหน้าแก้ไข) */
        public SendLog asLog() {
            SendLog l = new SendLog();
            l.id = id; l.no = no; l.place = place; l.total = total; l.ok = true;
            l.at = createdAt - offset; l.lines = text; l.pay = pay; l.edits = edits;
            return l;
        }

        public String clock() {
            if (createdAt <= 0) return "";
            return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(createdAt - offset));
        }

        public String preClock() {
            if (preAt <= 0) return "";
            return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(preAt - offset));
        }

        /** แก้ไขไปแล้ว แต่ POS ยังไม่กดรับทราบ */
        public boolean waitingAck() { return editedAt > 0 && ackEditAt < editedAt; }

        /** นาทีที่ผ่านไปตั้งแต่ส่ง */
        public int minAgo() {
            if (createdAt <= 0) return 0;
            return (int) Math.max(0, (now() - createdAt) / 60000L);
        }

        public int color() {
            if (isPre()) return 0xFF7C3AED;
            if (waitingAck()) return 0xFFA78BFA;                 // ม่วง = แก้ไขแล้ว รอ POS รับทราบ
            if (status.equals("เสร็จ")) return 0xFF22C55E;      // เขียว = POS ทำเสร็จแล้ว
            if (status.equals("กำลังทำ")) return 0xFFF97316;    // ส้ม = POS กดรับแล้ว
            return 0xFFFACC15;                                   // เหลือง = ยังไม่มีใครกดรับ
        }

        public String label() {
            if (isPre()) return "📅 พรีออเดอร์ · นัด " + preClock();
            if (status.equals("ยกเลิก")) return "ยกเลิกแล้ว";
            if (waitingAck()) return "แก้ไขแล้ว · รอ POS รับทราบ";
            if (status.equals("เสร็จ")) return "เสร็จแล้ว";
            if (status.equals("กำลังทำ")) return "POS รับแล้ว กำลังทำ";
            return "รอ POS กดรับ";
        }
    }

    /** ดึงสถานะออเดอร์ทั้งหมด — เรียกจาก background thread เท่านั้น */
    public static java.util.List<OrderStatus> fetchAll(Context c) throws Exception { return fetchNode(c, "orders"); }
    /** ออเดอร์ที่ POS เก็บเข้าประวัติแล้ว (ย้อนหลัง 30 วัน) */
    public static java.util.List<OrderStatus> fetchArchive(Context c) throws Exception { return fetchNode(c, "archive"); }

    private static java.util.List<OrderStatus> fetchNode(Context c, String node) throws Exception {
        java.util.List<OrderStatus> out = new java.util.ArrayList<>();
        String u = dbUrl(c);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        String endpoint = u + "/shops/" + shop(c) + "/" + node + ".json";

        HttpURLConnection con = (HttpURLConnection) new URL(endpoint).openConnection();
        con.setConnectTimeout(9000);
        con.setReadTimeout(12000);
        con.setRequestMethod("GET");
        int code = con.getResponseCode(); if (code >= 200 && code < 300) lastOk = System.currentTimeMillis();
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
            s.editedAt = o.optLong("editedAt", 0);
            s.ackEditAt = o.optLong("ackEditAt", 0);
            s.text = o.optString("text", "");
            s.pay = o.optString("pay", "");
            s.by = o.optString("by", "");
            s.edits = o.optInt("edits", 0);
            s.preAt = o.optLong("preAt", 0);
            s.preType = o.optString("preType", "");
            s.deposit = o.optInt("deposit", 0);
            s.paidFull = o.optBoolean("paidFull", false);
            s.prepMin = o.optInt("prepMin", 45);
            out.add(s);
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(b.createdAt, a.createdAt)); // ใหม่สุดขึ้นก่อน
        return out;
    }

    /** ส่งออเดอร์ 1 ใบ — เรียกจาก background thread เท่านั้น */
    /** สร้าง id แบบ push-id ของ Firebase (8 ตัวแรกเป็นเวลา) — สร้างฝั่งแอปเพื่อให้ส่งซ้ำแล้วได้ออเดอร์เดิม */
    private static final String PUSH_CHARS = "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz";
    public static String newOrderId() {
        long t = now();
        char[] ts = new char[8];
        for (int i = 7; i >= 0; i--) { ts[i] = PUSH_CHARS.charAt((int) (t % 64)); t /= 64; }
        StringBuilder sb = new StringBuilder(new String(ts));
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 12; i++) sb.append(PUSH_CHARS.charAt(r.nextInt(64)));
        return sb.toString();
    }

    /** ข้อมูลสั่งล่วงหน้า (Pre Order) — ตั้งไว้ก่อนเรียก send/update แล้วจะถูกเคลียร์เอง */
    public static class PreInfo {
        public long preAt = 0;          // เวลานัด (มิลลิวินาที ตามเวลาเซิร์ฟเวอร์)
        public String preType = "";     // "รับเอง" / "ส่ง"
        public int deposit = 0;         // มัดจำ (บาท)
        public boolean paidFull = false;
        public int prepMin = 45;        // POS เริ่มเตรียมก่อนนัดกี่นาที
    }
    public static PreInfo pendingPre = null;

    private static void putPre(JSONObject o, PreInfo p) throws Exception {
        if (p == null || p.preAt <= 0) { o.put("preAt", 0); return; }
        o.put("preAt", p.preAt);
        o.put("preType", p.preType);
        o.put("deposit", p.deposit);
        o.put("paidFull", p.paidFull);
        o.put("prepMin", p.prepMin);
        // ถ้าถึงเวลาเตรียมแล้ว (หรือสั่งใกล้เวลา) ให้เข้าคิวปกติทันที
        if (p.preAt - now() > p.prepMin * 60000L) o.put("status", "พรี");
    }

    public static String send(Context c, String orderNo, String place, String text,
                              int total, String payInfo) throws Exception { return send(c, null, orderNo, place, text, total, payInfo); }

    /** ส่งออเดอร์ใหม่ — ถ้าให้ id มา จะ PUT ทับ id เดิม (กดส่งซ้ำ/ส่งใหม่หลัง timeout ไม่เกิดออเดอร์ซ้ำ) */
    public static String send(Context c, String id, String orderNo, String place, String text,
                              int total, String payInfo) throws Exception {
        String u = dbUrl(c);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        final boolean put = id != null && !id.isEmpty();
        String endpoint = u + "/shops/" + shop(c) + "/orders" + (put ? "/" + id : "") + ".json";

        JSONObject o = new JSONObject();
        o.put("no", orderNo == null ? "" : orderNo.trim());
        o.put("place", place == null ? "" : place.trim());
        o.put("text", text == null ? "" : text);
        o.put("total", total);
        o.put("pay", payInfo == null ? "" : payInfo);
        o.put("status", "ใหม่");
        o.put("by", "mobile");
        o.put("createdAt", new JSONObject().put(".sv", "timestamp"));   // ให้เซิร์ฟเวอร์ประทับเวลา กันนาฬิกามือถือ/POS ไม่ตรงกัน
        putPre(o, pendingPre); pendingPre = null;

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
        con.setRequestMethod(put ? "PUT" : "POST");
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        con.setDoOutput(true);
        OutputStream os = con.getOutputStream();
        os.write(o.toString().getBytes("UTF-8"));
        os.close();

        int code = con.getResponseCode(); if (code >= 200 && code < 300) lastOk = System.currentTimeMillis();
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
        if (put) return id;
        try { return new JSONObject(resp).optString("name", ""); } catch (Exception e) { return ""; }
    }

    /** แก้ไขออเดอร์เดิม (PATCH id เดิม) — POS จะเด้งเตือนให้กดรับทราบใหม่ */
    public static void update(Context c, String id, String orderNo, String place, String text,
                              int total, String payInfo, boolean reopen, String prevText, int edits) throws Exception {
        String u = dbUrl(c);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        String endpoint = u + "/shops/" + shop(c) + "/orders/" + id + ".json";

        JSONObject o = new JSONObject();
        o.put("no", orderNo == null ? "" : orderNo.trim());
        o.put("place", place == null ? "" : place.trim());
        o.put("text", text == null ? "" : text);
        o.put("total", total);
        o.put("pay", payInfo == null ? "" : payInfo);
        o.put("editedAt", new JSONObject().put(".sv", "timestamp"));
        o.put("edited", true);
        o.put("edits", edits);
        if (reopen) o.put("status", "ใหม่");     // เคยเสร็จแล้ว → กลับมาเป็นออเดอร์ใหม่
        if (pendingPre != null) { putPre(o, pendingPre); pendingPre = null; }
        // รายการก่อนแก้ — ให้ POS ไฮไลต์ได้ว่าแก้ตรงไหน
        JSONArray prev = new JSONArray();
        if (prevText != null) {
            for (String s : prevText.split("\n")) {
                String t = s.trim();
                if (t.isEmpty() || t.startsWith("ยอดรวม") || t.startsWith("order")) continue;
                prev.put(t);
            }
        }
        o.put("prevLines", prev);
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
        con.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        con.setDoOutput(true);
        OutputStream os = con.getOutputStream();
        os.write(o.toString().getBytes("UTF-8"));
        os.close();
        int code = con.getResponseCode(); if (code >= 200 && code < 300) lastOk = System.currentTimeMillis();
        con.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
    }

    /** เปลี่ยนสถานะออเดอร์จากมือถือ (เช่น กด "เสร็จแล้ว" แทน POS) */
    public static void setStatus(Context c, String id, String status) throws Exception {
        String u = dbUrl(c);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        String endpoint = u + "/shops/" + shop(c) + "/orders/" + id + ".json";
        JSONObject o = new JSONObject();
        o.put("status", status);
        HttpURLConnection con = (HttpURLConnection) new URL(endpoint).openConnection();
        con.setConnectTimeout(9000);
        con.setReadTimeout(12000);
        con.setRequestMethod("POST");
        con.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        con.setDoOutput(true);
        OutputStream os = con.getOutputStream();
        os.write(o.toString().getBytes("UTF-8"));
        os.close();
        int code = con.getResponseCode(); if (code >= 200 && code < 300) lastOk = System.currentTimeMillis();
        con.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
    }

    /* ============ แจ้งเตือนเก็บกุ้งจากบ่อ ============ */
    public static final String K_HARVEST_EVERY = "harvest_every";   // 0 = ปิด, 30, 60 นาที
    public static final String K_HARVEST_LAST  = "harvest_last";
    public static long harvestAt = 0, harvestAck = 0;                // สถานะล่าสุดจากคลาวด์

    public static int harvestEvery(Context c) { return Store.prefs(c).getInt(K_HARVEST_EVERY, 0); }
    public static void setHarvestEvery(Context c, int m) { Store.prefs(c).edit().putInt(K_HARVEST_EVERY, m).apply(); }
    public static long harvestLast(Context c) { return Store.prefs(c).getLong(K_HARVEST_LAST, 0); }

    private static String harvestUrl(Context c) {
        String u = dbUrl(c);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u + "/shops/" + shop(c) + "/harvest.json";
    }

    /** ส่งสัญญาณ "เก็บกุ้ง" ไปเครื่อง POS — background thread */
    public static void harvestNow(Context c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("at", new JSONObject().put(".sv", "timestamp"));
        o.put("every", harvestEvery(c));
        o.put("by", "มือถือ");
        HttpURLConnection con = (HttpURLConnection) new URL(harvestUrl(c)).openConnection();
        con.setConnectTimeout(9000); con.setReadTimeout(12000);
        con.setRequestMethod("POST");
        con.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        con.setDoOutput(true);
        OutputStream os = con.getOutputStream(); os.write(o.toString().getBytes("UTF-8")); os.close();
        int code = con.getResponseCode(); if (code >= 200 && code < 300) lastOk = System.currentTimeMillis(); con.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        Store.prefs(c).edit().putLong(K_HARVEST_LAST, System.currentTimeMillis()).apply();
    }

    /** อ่านสถานะล่าสุด (at / ack) — background thread */
    public static void harvestRefresh(Context c) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(harvestUrl(c)).openConnection();
        con.setConnectTimeout(9000); con.setReadTimeout(12000);
        con.setRequestMethod("GET");
        int code = con.getResponseCode(); if (code >= 200 && code < 300) lastOk = System.currentTimeMillis();
        java.io.InputStream in = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        String body = "";
        if (in != null) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close(); body = new String(bos.toByteArray(), "UTF-8");
        }
        con.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        if (body.isEmpty() || body.equals("null")) { harvestAt = 0; harvestAck = 0; return; }
        JSONObject o = new JSONObject(body);
        harvestAt = o.optLong("at", 0);
        harvestAck = o.optLong("ack", 0);
    }

    /* ============ โซนที่อยู่ (ลำดับระยะทาง 1 = ใกล้สุด) เก็บบนคลาวด์ ใช้ร่วมกันทุกเครื่อง ============ */
    public static final java.util.Map<String, Integer> zones = new java.util.HashMap<>();
    private static final String K_ZONES = "zones_cache";

    public static String zoneKey(String place) {
        return place.trim().replaceAll("[.#$\\[\\]/]", "_");
    }

    public static int zoneOf(Context c, String place) {
        if (zones.isEmpty()) zonesLoadCache(c);
        Integer z = zones.get(zoneKey(place));
        return z == null ? 0 : z;
    }

    private static void zonesLoadCache(Context c) {
        try {
            JSONObject o = new JSONObject(Store.prefs(c).getString(K_ZONES, "{}"));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) { String k = it.next(); zones.put(k, o.optInt(k, 0)); }
        } catch (Exception ignored) {}
    }

    private static String zonesUrl(Context c) {
        String u = dbUrl(c);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u + "/shops/" + shop(c) + "/zones.json";
    }

    /** โหลดโซนจากคลาวด์ — background thread */
    public static void zonesRefresh(Context c) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(zonesUrl(c)).openConnection();
        con.setConnectTimeout(9000); con.setReadTimeout(12000);
        con.setRequestMethod("GET");
        int code = con.getResponseCode(); if (code >= 200 && code < 300) lastOk = System.currentTimeMillis();
        java.io.InputStream in = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        String body = "";
        if (in != null) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close(); body = new String(bos.toByteArray(), "UTF-8");
        }
        con.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        if (body.isEmpty() || body.equals("null")) body = "{}";
        JSONObject o = new JSONObject(body);
        zones.clear();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) { String k = it.next(); zones.put(k, o.optInt(k, 0)); }
        Store.prefs(c).edit().putString(K_ZONES, o.toString()).apply();
    }

    /** ตั้งโซนให้ที่อยู่ (0 = ลบ) — background thread */
    public static void setZone(Context c, String place, int zone) throws Exception {
        String k = zoneKey(place);
        if (zone <= 0) zones.remove(k); else zones.put(k, zone);
        JSONObject patch = new JSONObject();
        patch.put(k, zone <= 0 ? JSONObject.NULL : zone);
        HttpURLConnection con = (HttpURLConnection) new URL(zonesUrl(c)).openConnection();
        con.setConnectTimeout(9000); con.setReadTimeout(12000);
        con.setRequestMethod("POST");
        con.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        con.setDoOutput(true);
        OutputStream os = con.getOutputStream(); os.write(patch.toString().getBytes("UTF-8")); os.close();
        int code = con.getResponseCode(); if (code >= 200 && code < 300) lastOk = System.currentTimeMillis(); con.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        Store.prefs(c).edit().putString(K_ZONES, new JSONObject(zones).toString()).apply();
    }
}
