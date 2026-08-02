package com.messessale.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import static com.messessale.app.UI.*;

/** แก้ไข/เพิ่ม/ลบ รายการเมนู */
public class MenuEditorActivity extends Activity {

    private List<MenuData.Cat> cats;
    private int catIdx, itemIdx;
    private MenuData.Item item;
    private EditText nameIn, priceIn;
    private TextView customToggle;
    private boolean custom;
    private int pickedCat;
    private final java.util.List<TextView> catChips = new java.util.ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        cats = Store.loadMenu(this);
        catIdx = Math.max(0, Math.min(getIntent().getIntExtra("cat", 0), cats.size() - 1));
        itemIdx = getIntent().getIntExtra("item", -1);
        if (itemIdx >= cats.get(catIdx).items.size()) itemIdx = -1;
        pickedCat = catIdx;

        boolean isNew = itemIdx < 0;
        item = isNew ? MenuData.newItem("", 0, false) : cats.get(catIdx).items.get(itemIdx);
        custom = item.custom;

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(INK);
        sv.setFillViewport(true);

        LinearLayout root = col(this);
        root.setPadding(dp(this,20), dp(this,28), dp(this,20), dp(this,28));

        root.addView(text(this, isNew ? "เพิ่มเมนูใหม่" : "แก้ไขเมนู", 20, true, WHITE));

        TextView l1 = text(this, "ชื่อเมนู", 12.5f, false, WHITE_DIM);
        l1.setPadding(0, dp(this,18), 0, dp(this,5));
        root.addView(l1);
        nameIn = input(this, "เช่น กุ้งเผา 1 กิโล");
        nameIn.setText(item.name);
        root.addView(nameIn, lp(MATCH, WRAP));

        TextView l2 = text(this, "ราคา (บาท)", 12.5f, false, WHITE_DIM);
        l2.setPadding(0, dp(this,14), 0, dp(this,5));
        root.addView(l2);
        priceIn = input(this, "เช่น 350");
        priceIn.setInputType(InputType.TYPE_CLASS_NUMBER);
        priceIn.setText(item.price > 0 ? String.valueOf(item.price) : "");
        root.addView(priceIn, lp(MATCH, WRAP));

        customToggle = text(this, "", 13, true, WHITE);
        customToggle.setPadding(dp(this,14), dp(this,13), dp(this,14), dp(this,13));
        refreshCustom();
        Fx.onTap(customToggle, () -> { custom = !custom; refreshCustom(); });
        LinearLayout.LayoutParams ctp = lp(MATCH, WRAP); ctp.topMargin = dp(this,14);
        root.addView(customToggle, ctp);

        TextView l3 = text(this, "หมวด", 12.5f, false, WHITE_DIM);
        l3.setPadding(0, dp(this,16), 0, dp(this,7));
        root.addView(l3);
        LinearLayout catRow = col(this);
        for (int i = 0; i < cats.size(); i++) {
            final int idx = i;
            TextView c = chip(this, cats.get(i).name, i == pickedCat);
            c.setPadding(dp(this,14), dp(this,10), dp(this,14), dp(this,10));
            LinearLayout.LayoutParams cp = lp(MATCH, WRAP); cp.bottomMargin = dp(this,6);
            c.setLayoutParams(cp);
            catChips.add(c);
            Fx.onTap(c, () -> { pickedCat = idx; refreshCats(); });
            catRow.addView(c);
        }
        root.addView(catRow, lp(MATCH, WRAP));

        TextView save = button(this, "💾   บันทึก", green(this, 14), 16);
        LinearLayout.LayoutParams sp = lp(MATCH, WRAP); sp.topMargin = dp(this,20);
        Fx.onTap(save, this::saveItem);
        root.addView(save, sp);

        if (!isNew) {
            TextView del = button(this, "🗑   ลบเมนูนี้", glass(this, 0x38F43F5E, 14, 0x66F43F5E), 15);
            LinearLayout.LayoutParams dp2 = lp(MATCH, WRAP); dp2.topMargin = dp(this,10);
            Fx.onTap(del, () -> {
                cats.get(catIdx).items.remove(itemIdx);
                Store.saveMenu(this, cats);
                back("ลบเมนูแล้ว");
            });
            root.addView(del, dp2);
        }

        TextView reset = button(this, "↺   คืนค่าเมนูตั้งต้นทั้งหมด", glass(this, GLASS, 14, STROKE), 14);
        LinearLayout.LayoutParams rp = lp(MATCH, WRAP); rp.topMargin = dp(this,10);
        Fx.onTap(reset, () -> {
            Store.saveMenu(this, MenuData.build());
            back("คืนค่าเมนูตั้งต้นแล้ว");
        });
        root.addView(reset, rp);

        TextView cancel = button(this, "ยกเลิก", glass(this, GLASS, 14, STROKE), 15);
        LinearLayout.LayoutParams cp2 = lp(MATCH, WRAP); cp2.topMargin = dp(this,10);
        Fx.onTap(cancel, () -> back(null));
        root.addView(cancel, cp2);

        sv.addView(root);
        setContentView(sv);
    }

    private void refreshCats() {
        for (int i = 0; i < catChips.size(); i++) {
            boolean on = (i == pickedCat);
            TextView c = catChips.get(i);
            c.setBackground(on ? glass(this, WHITE, 13, 0) : glass(this, GLASS, 13, STROKE));
            c.setTextColor(on ? INK : WHITE);
        }
    }

    private void refreshCustom() {
        customToggle.setText(custom ? "✓  ราคากำหนดเองตอนขาย" : "○  ราคากำหนดเองตอนขาย");
        customToggle.setBackground(custom
                ? glass(this, 0x38FF9D6C, 13, 0x66FF9D6C)
                : glass(this, GLASS, 13, STROKE));
    }

    private void saveItem() {
        String n = nameIn.getText().toString().trim();
        if (n.isEmpty()) { nameIn.requestFocus(); Toast.makeText(this, "ใส่ชื่อเมนูก่อน", Toast.LENGTH_SHORT).show(); return; }
        int p = 0;
        try { p = Integer.parseInt(priceIn.getText().toString().trim()); } catch (Exception ignored) {}

        item.name = n;
        item.price = p;
        item.custom = custom;

        if (itemIdx < 0) cats.get(pickedCat).items.add(item);
        else if (pickedCat != catIdx) {
            cats.get(catIdx).items.remove(itemIdx);
            cats.get(pickedCat).items.add(item);
        }
        Store.saveMenu(this, cats);
        back("บันทึกเมนูแล้ว");
    }

    private void back(String msg) {
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        Intent i = new Intent(this, BubbleService.class);
        i.putExtra("reload", true);
        startForegroundService(i);
        finish();
    }
}
