package com.messessale.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** เก็บข้อมูลถาวร: การ์ดคำที่พิมพ์บ่อย, ชื่อหอที่ใช้บ่อย, ความใส */
public class Store {

    /** การ์ดคำพูด 1 ใบ: ชื่อ + ข้อความ + รูป (0..n) */
    public static class Card {
        public String title = "";
        public String text = "";
        public List<String> images = new ArrayList<>();

        public Card() {}
        public Card(String title, String text) { this.title = title; this.text = text; }
    }

    private static final String PREF = "messes";
    private static final String K_CARDS = "cards_v2";
    private static final String K_PLACES = "places";
    private static final String K_ALPHA = "panel_alpha";

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /* ---------------- การ์ดคำพูด ---------------- */

    public static List<Card> loadCards(Context c) {
        String raw = prefs(c).getString(K_CARDS, null);
        if (raw == null) return defaultCards(c);
        List<Card> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Card card = new Card(o.optString("title"), o.optString("text"));
                JSONArray imgs = o.optJSONArray("images");
                if (imgs != null)
                    for (int j = 0; j < imgs.length(); j++) card.images.add(imgs.getString(j));
                out.add(card);
            }
        } catch (Exception e) { return defaultCards(c); }
        return out;
    }

    public static void saveCards(Context c, List<Card> cards) {
        JSONArray arr = new JSONArray();
        try {
            for (Card card : cards) {
                JSONObject o = new JSONObject();
                o.put("title", card.title);
                o.put("text", card.text);
                JSONArray imgs = new JSONArray();
                for (String u : card.images) imgs.put(u);
                o.put("images", imgs);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        prefs(c).edit().putString(K_CARDS, arr.toString()).apply();
    }

    private static List<Card> defaultCards(Context c) {
        List<Card> out = new ArrayList<>();
        out.add(new Card("Map ร้าน", "แผนที่ร้าน: https://maps.app.goo.gl/"));
        out.add(new Card("เมนูกุ้งวันนี้", MsgBuilder.promo()));
        out.add(new Card("เวลาเปิด-ปิด", "ร้านเปิด 10:00 - 21:00 น. ทุกวันครับ"));
        out.add(new Card("เลขบัญชี", "ธ.กสิกรไทย 123-4-56789-0 บังฟาน"));
        return out;
    }

    /* ---------------- ชื่อหอที่ใช้บ่อย ---------------- */

    public static List<String> loadPlaces(Context c) {
        List<String> out = new ArrayList<>();
        String raw = prefs(c).getString(K_PLACES, "");
        if (raw.isEmpty()) return out;
        for (String s : raw.split("\\|")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    public static void savePlaces(Context c, List<String> places) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < places.size(); i++) {
            if (i > 0) sb.append("|");
            sb.append(places.get(i));
        }
        prefs(c).edit().putString(K_PLACES, sb.toString()).apply();
    }

    /** จำชื่อหอที่พิมพ์ (ใหม่สุดขึ้นก่อน, เก็บสูงสุด 8) */
    public static void rememberPlace(Context c, String name) {
        if (name == null || name.trim().isEmpty()) return;
        String n = name.trim();
        List<String> places = loadPlaces(c);
        places.remove(n);
        places.add(0, n);
        while (places.size() > 8) places.remove(places.size() - 1);
        savePlaces(c, places);
    }

    public static void forgetPlace(Context c, String name) {
        List<String> places = loadPlaces(c);
        places.remove(name);
        savePlaces(c, places);
    }

    /* ---------------- ความใสของแผง ---------------- */

    public static int alpha(Context c) { return prefs(c).getInt(K_ALPHA, 92); }

    public static void setAlpha(Context c, int a) {
        prefs(c).edit().putInt(K_ALPHA, Math.max(35, Math.min(100, a))).apply();
    }
}
