package com.messessale.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import static com.messessale.app.UI.*;

public class MainActivity extends Activity {

    private static final int REQ_QR = 101;
    private static final int REQ_BACKUP = 102;
    private static final int REQ_RESTORE = 103;
    private EditText bank, hours, map, payCustomer, payStaff;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(INK);
        sv.setFillViewport(true);

        LinearLayout root = col(this);
        root.setPadding(dp(this, 20), dp(this, 30), dp(this, 20), dp(this, 30));

        TextView h1 = text(this, "🦐 Messes Sale", 24, true, WHITE);
        h1.setGravity(Gravity.CENTER);
        root.addView(h1);

        TextView sub = text(this, "ตัวช่วยตอบลูกค้า ร้านกุ้งเผาบังฟาน", 13, false, WHITE_DIM);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(this, 4), 0, dp(this, 20));
        root.addView(sub);

        TextView start = button(this, "▶   เปิด Bubble ลอย", primary(this, 15), 16);
        start.setOnClickListener(v -> startBubble());
        root.addView(start, lp(MATCH, WRAP));

        TextView stop = button(this, "■   ปิด Bubble", glass(this, GLASS, 15, STROKE), 15);
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, BubbleService.class));
            Toast.makeText(this, "ปิด Bubble แล้ว", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams stLp = lp(MATCH, WRAP);
        stLp.topMargin = dp(this, 10);
        root.addView(stop, stLp);

        TextView note = text(this,
                "ครั้งแรกต้องกดอนุญาต \"แสดงทับแอปอื่น\" (Display over other apps) แล้วกดเปิดอีกครั้ง",
                12, false, 0xFFFFE6B8);
        note.setPadding(0, dp(this, 12), 0, dp(this, 22));
        root.addView(note);

        root.addView(text(this, "ตั้งค่าข้อความ", 16, true, WHITE));

        SharedPreferences p = prefs();
        bank        = addField(root, "เลขบัญชี", p.getString("bank", "ธ.กสิกรไทย 123-4-56789-0 บังฟาน"));
        hours       = addField(root, "เวลาเปิด-ปิดร้าน", p.getString("hours", "ร้านเปิด 10:00 - 21:00 น. ทุกวันครับ"));
        map         = addField(root, "แผนที่ร้าน (ลิงก์)", p.getString("map", "แผนที่ร้าน: https://maps.app.goo.gl/"));
        payCustomer = addField(root, "ข้อความท้าย — ตอบลูกค้า", p.getString("payline", "ชำระเงินคนละครึ่งหรือโอนธรรมดาครับ"));
        payStaff    = addField(root, "ข้อความท้าย — แจ้งพนักงาน", p.getString("paystaff", "ลูกค้าโอนจ่ายปกติแล้ว"));

        TextView pickQr = button(this, "📱   เลือกรูป QR ชำระเงิน", glass(this, GLASS, 14, STROKE), 15);
        pickQr.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_QR);
        });
        LinearLayout.LayoutParams qrLp = lp(MATCH, WRAP);
        qrLp.topMargin = dp(this, 16);
        root.addView(pickQr, qrLp);

        TextView save = button(this, "💾   บันทึกการตั้งค่า", green(this, 14), 16);
        save.setOnClickListener(v -> {
            prefs().edit()
                    .putString("bank", bank.getText().toString())
                    .putString("hours", hours.getText().toString())
                    .putString("map", map.getText().toString())
                    .putString("payline", payCustomer.getText().toString())
                    .putString("paystaff", payStaff.getText().toString())
                    .apply();
            Toast.makeText(this, "บันทึกแล้ว", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams svLp = lp(MATCH, WRAP);
        svLp.topMargin = dp(this, 10);
        root.addView(save, svLp);

        // ---- สำรอง / กู้คืนข้อมูล ----
        TextView bkHead = text(this, "สำรองข้อมูล", 16, true, WHITE);
        bkHead.setPadding(0, dp(this, 26), 0, dp(this, 4));
        root.addView(bkHead);

        TextView bkNote = text(this,
                "เก็บชื่อหอที่จำไว้ การ์ดคำพูด เมนูที่แก้ และการตั้งค่าทั้งหมด — ควรสำรองไว้ก่อนถอนแอป",
                12, false, WHITE_DIM);
        bkNote.setPadding(0, 0, 0, dp(this, 10));
        root.addView(bkNote);

        TextView bkBtn = button(this, "⬆   สำรองข้อมูลเป็นไฟล์", glass(this, GLASS, 14, STROKE), 15);
        bkBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, "messes-backup.json");
            startActivityForResult(i, REQ_BACKUP);
        });
        root.addView(bkBtn, lp(MATCH, WRAP));

        TextView rsBtn = button(this, "⬇   กู้คืนจากไฟล์สำรอง", glass(this, GLASS, 14, STROKE), 15);
        rsBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_RESTORE);
        });
        LinearLayout.LayoutParams rsLp = lp(MATCH, WRAP);
        rsLp.topMargin = dp(this, 10);
        root.addView(rsBtn, rsLp);

        sv.addView(root);
        setContentView(sv);
    }

    /* ---------- สำรอง / กู้คืน ---------- */

    private void backupTo(Uri uri) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            for (java.util.Map.Entry<String, ?> e : prefs().getAll().entrySet()) {
                Object v = e.getValue();
                if (v instanceof Integer) o.put(e.getKey(), "i:" + v);
                else if (v instanceof Boolean) o.put(e.getKey(), "b:" + v);
                else o.put(e.getKey(), "s:" + v);
            }
            java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt");
            out.write(o.toString(2).getBytes("UTF-8"));
            out.close();
            Toast.makeText(this, "สำรองข้อมูลเรียบร้อย", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "สำรองไม่สำเร็จ: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void restoreFrom(Uri uri) {
        try {
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();

            org.json.JSONObject o = new org.json.JSONObject(new String(bos.toByteArray(), "UTF-8"));
            SharedPreferences.Editor ed = prefs().edit();
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                String raw = o.optString(k);
                if (raw.startsWith("i:")) ed.putInt(k, Integer.parseInt(raw.substring(2)));
                else if (raw.startsWith("b:")) ed.putBoolean(k, Boolean.parseBoolean(raw.substring(2)));
                else if (raw.startsWith("s:")) ed.putString(k, raw.substring(2));
                else ed.putString(k, raw);
            }
            ed.apply();

            Intent i = new Intent(this, BubbleService.class);
            i.putExtra("reload", true);
            try { startForegroundService(i); } catch (Exception ignored) {}
            Toast.makeText(this, "กู้คืนข้อมูลแล้ว — เปิดแอปใหม่อีกครั้ง", Toast.LENGTH_LONG).show();
            recreate();
        } catch (Exception e) {
            Toast.makeText(this, "กู้คืนไม่สำเร็จ: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private EditText addField(LinearLayout parent, String label, String value) {
        TextView l = text(this, label, 12.5f, false, WHITE_DIM);
        l.setPadding(0, dp(this, 12), 0, dp(this, 5));
        parent.addView(l);
        EditText e = input(this, label);
        e.setText(value);
        parent.addView(e, lp(MATCH, WRAP));
        return e;
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req == REQ_BACKUP) { backupTo(data.getData()); return; }
        if (req == REQ_RESTORE) { restoreFrom(data.getData()); return; }
        if (req == REQ_QR && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            prefs().edit().putString("qr", uri.toString()).apply();
            Toast.makeText(this, "บันทึกรูป QR แล้ว", Toast.LENGTH_SHORT).show();
        }
    }

    private void startBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "กรุณาอนุญาต \"แสดงทับแอปอื่น\" แล้วกดเปิดอีกครั้ง", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        startForegroundService(new Intent(this, BubbleService.class));
        Toast.makeText(this, "เปิด Bubble แล้ว — ออกไปแอปอื่นได้เลย", Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
    }

    private SharedPreferences prefs() { return getSharedPreferences("messes", Context.MODE_PRIVATE); }
}
