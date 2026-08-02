package com.messessale.app;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import java.util.List;

import static com.messessale.app.UI.*;

/**
 * Masonry (Pinterest grid) แบบ 2 คอลัมน์
 * วางการ์ดลงคอลัมน์ที่ "เตี้ยกว่า" เสมอ → ความสูงต่างกันได้ ไม่มีช่องว่างใหญ่
 */
public class Masonry {

    public interface CardBuilder {
        /** สร้าง view ของการ์ด และคืนค่าน้ำหนักความสูงโดยประมาณ (สำหรับจัดคอลัมน์) */
        View build(int index);
        int weight(int index);
    }

    /** สร้างกริด 2 คอลัมน์จากจำนวนรายการ */
    public static LinearLayout grid(Context c, int count, CardBuilder b) {
        LinearLayout row = row(c);
        row.setGravity(android.view.Gravity.TOP);

        LinearLayout left = col(c);
        LinearLayout right = col(c);

        LinearLayout.LayoutParams lp1 = lpw(1);
        lp1.rightMargin = dp(c, 4);
        LinearLayout.LayoutParams lp2 = lpw(1);
        lp2.leftMargin = dp(c, 4);

        row.addView(left, lp1);
        row.addView(right, lp2);

        int hLeft = 0, hRight = 0;
        for (int i = 0; i < count; i++) {
            View card = b.build(i);
            LinearLayout.LayoutParams cp = lp(MATCH, WRAP);
            cp.bottomMargin = dp(c, 8);
            if (hLeft <= hRight) {
                left.addView(card, cp);
                hLeft += b.weight(i);
            } else {
                right.addView(card, cp);
                hRight += b.weight(i);
            }
        }
        return row;
    }

    /** หัวข้อหมวดคั่นในกริด */
    public static View header(Context c, String title, int color) {
        android.widget.TextView t = text(c, title, 11.5f, true, color);
        t.setPadding(dp(c, 2), dp(c, 4), 0, dp(c, 7));
        return t;
    }
}
