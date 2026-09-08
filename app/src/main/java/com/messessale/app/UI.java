package com.messessale.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** สร้าง UI ด้วยโค้ด (ไม่ใช้ไฟล์ XML) — สไตล์ Vibrancy/กระจก */
public class UI {

    public static final int INK        = 0xFF0F1220;
    public static final int WHITE      = 0xFFFFFFFF;
    public static final int WHITE_DIM  = 0xCCFFFFFF;
    public static final int GLASS      = 0x26FFFFFF;
    public static final int GLASS_SOFT = 0x1AFFFFFF;
    public static final int STROKE     = 0x59FFFFFF;
    public static final int PANEL_BG   = 0xF6151A2B;
    public static final int OK_GREEN   = 0xFFC9FFE0;

    /* ---- Modern clean palette ---- */
    public static final int SURFACE    = 0xFF1C2236;   // การ์ด
    public static final int SURFACE_2  = 0xFF262D45;   // การ์ดที่เลือก / ปุ่มรอง
    public static final int LINE       = 0x1FFFFFFF;   // เส้นขอบบาง
    public static final int ACCENT     = 0xFFFF6B4A;   // ส้มปะการัง (สีหลัก)
    public static final int ACCENT_2   = 0xFFFF9A6C;
    public static final int MINT       = 0xFF34D399;   // เขียวมิ้นต์ (ยอดเงิน/สำเร็จ)
    public static final int AMBER      = 0xFFFFB84D;
    public static final int SKY        = 0xFF5AB0FF;
    public static final int LAVENDER   = 0xFFA78BFA;
    public static final int MUTED      = 0x99FFFFFF;

    /** สีประจำหมวดเมนู */
    public static int catAccent(String cat) {
        if (cat == null) return SKY;
        if (cat.contains("กุ้ง")) return ACCENT;
        if (cat.contains("ข้าว")) return AMBER;
        if (cat.contains("ส่ง")) return MINT;
        if (cat.contains("คำ")) return LAVENDER;
        return SKY;
    }

    /** พื้นการ์ดแบบเรียบ: ปกติ = SURFACE + ขอบบาง, เลือกแล้ว = SURFACE_2 + ขอบสีหลัก */
    public static GradientDrawable surface(Context c, float radius, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(on ? SURFACE_2 : SURFACE);
        g.setCornerRadius(dp(c, radius));
        g.setStroke(dp(c, on ? 1.5f : 1), on ? ACCENT : LINE);
        return g;
    }

    /** พื้นการ์ดเรียบพร้อมขอบสีที่กำหนด */
    public static GradientDrawable surfaceTint(Context c, float radius, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(SURFACE);
        g.setCornerRadius(dp(c, radius));
        g.setStroke(dp(c, 1), strokeColor);
        return g;
    }

    /** ปุ่มไอคอนกลม 36dp */
    public static TextView iconBtn(Context c, String glyph) {
        TextView t = text(c, glyph, 14, false, WHITE);
        t.setGravity(Gravity.CENTER);
        t.setBackground(glass(c, SURFACE_2, 20, LINE));
        int s = dp(c, 36);
        t.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        t.setMinWidth(s); t.setMinHeight(s);
        return t;
    }

    /** จุดสีเล็ก */
    public static android.view.View dot(Context c, int color, int sizeDp) {
        android.view.View v = new android.view.View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    /** พื้นหลังโปร่งแบบกระจก */
    public static GradientDrawable glass(Context c, int fill, float radius, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radius));
        if (strokeColor != 0) g.setStroke(dp(c, 1), strokeColor);
        return g;
    }

    /** พื้นหลังไล่สี (การ์ดสไตล์ Masonry สีสด) */
    public static GradientDrawable grad(Context c, int c1, int c2, float radius) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        g.setCornerRadius(dp(c, radius));
        return g;
    }

    /** จานสีการ์ด: ส้ม / ฟ้า / เขียว / ม่วง / เทา / แดง */
    public static final int[][] CARD_GRADS = {
            {0xFFF7A23B, 0xFFEF6C3A},
            {0xFF4FB3F7, 0xFF2E7FE8},
            {0xFF54C88A, 0xFF2FA36B},
            {0xFFB07BF0, 0xFF8A5BE0},
            {0xFF8A93A6, 0xFF5F6879},
            {0xFFF4736B, 0xFFE2504F},
    };

    public static GradientDrawable cardGrad(Context c, int index, float radius) {
        int[] p = CARD_GRADS[Math.abs(index) % CARD_GRADS.length];
        return grad(c, p[0], p[1], radius);
    }

    /** ปุ่มไล่สีชมพู-ส้ม */
    public static GradientDrawable primary(Context c, float radius) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT, ACCENT_2});
        g.setCornerRadius(dp(c, radius));
        return g;
    }

    public static GradientDrawable green(Context c, float radius) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFF2FBF71, 0xFF1F9A58});
        g.setCornerRadius(dp(c, radius));
        return g;
    }

    /** วงกลม bubble */
    public static GradientDrawable bubbleBg(Context c) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{ACCENT_2, ACCENT});
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(dp(c, 2.5f), 0xFFFFFFFF);
        return g;
    }

    /** พื้นหลังแผง (โค้งบน) */
    public static GradientDrawable panelBg(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(PANEL_BG);
        float r = dp(c, 28);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        g.setStroke(dp(c, 1), LINE);
        return g;
    }

    public static TextView text(Context c, String s, float size, boolean bold, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    /** ปุ่มกลม/แคปซูล กดได้ */
    public static TextView chip(Context c, String label, boolean selected) {
        TextView t = text(c, label, 12.5f, true, selected ? WHITE : WHITE_DIM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        t.setBackground(selected ? glass(c, ACCENT, 20, 0) : glass(c, SURFACE_2, 20, LINE));
        return t;
    }

    public static TextView button(Context c, String label, GradientDrawable bg, float size) {
        TextView t = text(c, label, size, true, WHITE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 14), dp(c, 13), dp(c, 14), dp(c, 13));
        t.setBackground(bg);
        return t;
    }

    public static EditText input(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(0x80FFFFFF);
        e.setTextColor(WHITE);
        e.setTextSize(14);
        e.setBackground(glass(c, SURFACE, 14, LINE));
        e.setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11));
        return e;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpw(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        return p;
    }

    public static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    public static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    /** เวอร์ชันแอปจาก build.gradle เช่น "v1.5" */
    public static String ver(Context c) {
        try {
            return "v" + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) { return ""; }
    }
}
