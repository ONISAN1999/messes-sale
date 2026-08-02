package com.messessale.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import static com.messessale.app.UI.*;

/** เพิ่ม/แก้ไขการ์ดคำที่พิมพ์บ่อย — ใส่รูปได้หลายรูป */
public class CardEditorActivity extends Activity {

    private static final int REQ_IMG = 201;

    private int index = -1;
    private Store.Card card;
    private List<Store.Card> cards;
    private EditText titleIn, textIn;
    private LinearLayout imgRow;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        cards = Store.loadCards(this);
        index = getIntent().getIntExtra("index", -1);
        card = (index >= 0 && index < cards.size()) ? cards.get(index) : new Store.Card();

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(INK);
        sv.setFillViewport(true);

        LinearLayout root = col(this);
        root.setPadding(dp(this,20), dp(this,28), dp(this,20), dp(this,28));

        TextView h = text(this, index >= 0 ? "แก้ไขการ์ด" : "เพิ่มการ์ดใหม่", 20, true, WHITE);
        root.addView(h);

        TextView l1 = text(this, "ชื่อการ์ด", 12.5f, false, WHITE_DIM);
        l1.setPadding(0, dp(this,18), 0, dp(this,5));
        root.addView(l1);
        titleIn = input(this, "เช่น เมนูกุ้งวันนี้");
        titleIn.setText(card.title);
        root.addView(titleIn, lp(MATCH, WRAP));

        TextView l2 = text(this, "ข้อความ", 12.5f, false, WHITE_DIM);
        l2.setPadding(0, dp(this,14), 0, dp(this,5));
        root.addView(l2);
        textIn = input(this, "ข้อความที่จะคัดลอกไปตอบลูกค้า");
        textIn.setText(card.text);
        textIn.setMinLines(5);
        textIn.setGravity(Gravity.TOP);
        root.addView(textIn, lp(MATCH, WRAP));

        TextView l3 = text(this, "รูป (เลือกได้หลายรูป · แตะรูปเพื่อลบ)", 12.5f, false, WHITE_DIM);
        l3.setPadding(0, dp(this,16), 0, dp(this,7));
        root.addView(l3);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        imgRow = row(this);
        hs.addView(imgRow);
        root.addView(hs, lp(MATCH, WRAP));
        refreshImages();

        TextView save = button(this, "💾   บันทึกการ์ด", green(this, 14), 16);
        LinearLayout.LayoutParams sp = lp(MATCH, WRAP); sp.topMargin = dp(this,22);
        save.setOnClickListener(v -> saveCard());
        root.addView(save, sp);

        if (index >= 0) {
            TextView del = button(this, "🗑   ลบการ์ดนี้", glass(this, 0x38F43F5E, 14, 0x66F43F5E), 15);
            LinearLayout.LayoutParams dp2 = lp(MATCH, WRAP); dp2.topMargin = dp(this,10);
            del.setOnClickListener(v -> {
                cards.remove(index);
                Store.saveCards(this, cards);
                back("ลบการ์ดแล้ว");
            });
            root.addView(del, dp2);
        }

        TextView cancel = button(this, "ยกเลิก", glass(this, GLASS, 14, STROKE), 15);
        LinearLayout.LayoutParams cp = lp(MATCH, WRAP); cp.topMargin = dp(this,10);
        cancel.setOnClickListener(v -> back(null));
        root.addView(cancel, cp);

        sv.addView(root);
        setContentView(sv);
    }

    private void refreshImages() {
        imgRow.removeAllViews();
        for (int i = 0; i < card.images.size(); i++) {
            final int idx = i;
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            try { iv.setImageURI(Uri.parse(card.images.get(i))); } catch (Exception ignored) {}
            iv.setBackground(glass(this, 0xFF2A4A6E, 10, 0));
            iv.setClipToOutline(true);
            LinearLayout.LayoutParams ip = lp(dp(this,74), dp(this,74));
            ip.rightMargin = dp(this,8);
            iv.setOnClickListener(v -> {
                card.images.remove(idx);
                refreshImages();
            });
            imgRow.addView(iv, ip);
        }
        TextView add = text(this, "＋", 24, false, WHITE_DIM);
        add.setGravity(Gravity.CENTER);
        add.setBackground(glass(this, 0x0FFFFFFF, 10, 0x52FFFFFF));
        LinearLayout.LayoutParams ap = lp(dp(this,74), dp(this,74));
        add.setOnClickListener(v -> pickImages());
        imgRow.addView(add, ap);
    }

    private void pickImages() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i, REQ_IMG);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMG || res != RESULT_OK || data == null) return;
        List<Uri> picked = new ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) picked.add(cd.getItemAt(i).getUri());
        else if (data.getData() != null) picked.add(data.getData());

        for (Uri u : picked) {
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            card.images.add(u.toString());
        }
        refreshImages();
    }

    private void saveCard() {
        String t = titleIn.getText().toString().trim();
        if (t.isEmpty()) { titleIn.requestFocus(); Toast.makeText(this, "ใส่ชื่อการ์ดก่อน", Toast.LENGTH_SHORT).show(); return; }
        card.title = t;
        card.text = textIn.getText().toString();
        if (index >= 0) cards.set(index, card); else cards.add(card);
        Store.saveCards(this, cards);
        back("บันทึกการ์ดแล้ว");
    }

    private void back(String msg) {
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        Intent i = new Intent(this, BubbleService.class);
        i.putExtra("reload", true);
        startForegroundService(i);
        finish();
    }
}
