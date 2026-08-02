package com.messessale.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import static com.messessale.app.UI.*;

/** Bubble ลอยทับแอปอื่น + แผงเลือกเมนู/คัดลอกข้อความ (UI สร้างด้วยโค้ด) */
public class BubbleService extends Service {

    private WindowManager wm;
    private View bubbleView;
    private LinearLayout panelView;
    private WindowManager.LayoutParams bubbleParams, panelParams;
    private boolean panelOpen = false;

    private List<MenuData.Cat> cats;
    private int mode = MsgBuilder.MODE_CUSTOMER;
    private int catIndex = 0;

    private LinearLayout listBox, chipRow, staffRow;
    private TextView totalText;
    private TextView segCustomer, segStaff;
    private TextView[] catChips;
    private EditText orderNoInput, placeInput;

    private static final String CH = "bubble_ch";

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        cats = MenuData.build();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForegroundNotif();
        addBubble();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    private void startForegroundNotif() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CH, "Messes Sale", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH)
                .setContentTitle("Messes Sale พร้อมใช้งาน")
                .setContentText("แตะฟองลอยเพื่อเปิดเมนู")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(1, n);
    }

    private int wtype() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /* ---------------- Bubble ---------------- */
    private void addBubble() {
        TextView b = text(this, "🦐", 26, false, WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackground(bubbleBg(this));
        int s = dp(this, 58);
        b.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        b.setElevation(dp(this, 10));
        bubbleView = b;

        bubbleParams = new WindowManager.LayoutParams(s, s, wtype(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = dp(this, 12);
        bubbleParams.y = dp(this, 260);

        bubbleView.setOnTouchListener(new View.OnTouchListener() {
            int ix, iy; float tx, ty; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        ix = bubbleParams.x; iy = bubbleParams.y;
                        tx = e.getRawX(); ty = e.getRawY(); moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - tx), dy = (int) (e.getRawY() - ty);
                        if (Math.abs(dx) > dp(BubbleService.this, 6) || Math.abs(dy) > dp(BubbleService.this, 6)) moved = true;
                        bubbleParams.x = ix + dx; bubbleParams.y = iy + dy;
                        wm.updateViewLayout(bubbleView, bubbleParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) togglePanel();
                        return true;
                }
                return false;
            }
        });
        wm.addView(bubbleView, bubbleParams);
    }

    /* ---------------- Panel ---------------- */
    private void togglePanel() { if (panelOpen) closePanel(); else openPanel(); }

    private void openPanel() {
        panelView = col(this);
        panelView.setBackground(panelBg(this));
        panelView.setPadding(dp(this, 14), dp(this, 14), dp(this, 14), dp(this, 16));
        panelView.setElevation(dp(this, 16));

        // ---- header ----
        LinearLayout head = row(this);
        TextView title = text(this, "🦐 Messes Sale", 16, true, WHITE);
        head.addView(title, lpw(1));
        totalText = text(this, "รวม 0 บาท", 15, true, OK_GREEN);
        totalText.setPadding(0, 0, dp(this, 10), 0);
        head.addView(totalText);
        TextView close = chip(this, "✕", false);
        close.setOnClickListener(v -> closePanel());
        head.addView(close);
        panelView.addView(head, lp(MATCH, WRAP));

        // ---- segmented control ----
        LinearLayout seg = row(this);
        seg.setBackground(glass(this, GLASS_SOFT, 16, 0x40FFFFFF));
        seg.setPadding(dp(this, 4), dp(this, 4), dp(this, 4), dp(this, 4));
        segCustomer = text(this, "ตอบลูกค้า", 13, true, WHITE);
        segStaff    = text(this, "แจ้งพนักงาน", 13, true, WHITE);
        for (TextView t : new TextView[]{segCustomer, segStaff}) {
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(this, 9), 0, dp(this, 9));
        }
        segCustomer.setOnClickListener(v -> { mode = MsgBuilder.MODE_CUSTOMER; refreshSeg(); });
        segStaff.setOnClickListener(v -> { mode = MsgBuilder.MODE_STAFF; refreshSeg(); });
        seg.addView(segCustomer, lpw(1));
        seg.addView(segStaff, lpw(1));
        LinearLayout.LayoutParams segLp = lp(MATCH, WRAP);
        segLp.topMargin = dp(this, 12);
        panelView.addView(seg, segLp);

        // ---- staff fields ----
        staffRow = row(this);
        orderNoInput = input(this, "order ที่");
        orderNoInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        placeInput = input(this, "จุดส่ง เช่น Tara");
        LinearLayout.LayoutParams o1 = lpw(1); o1.rightMargin = dp(this, 8);
        staffRow.addView(orderNoInput, o1);
        staffRow.addView(placeInput, lpw(2));
        LinearLayout.LayoutParams stLp = lp(MATCH, WRAP);
        stLp.topMargin = dp(this, 10);
        panelView.addView(staffRow, stLp);

        // ---- category chips ----
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipRow = row(this);
        catChips = new TextView[cats.size()];
        for (int i = 0; i < cats.size(); i++) {
            final int idx = i;
            TextView c = chip(this, cats.get(i).name, i == 0);
            LinearLayout.LayoutParams cp = lp(WRAP, WRAP);
            cp.rightMargin = dp(this, 6);
            c.setLayoutParams(cp);
            c.setOnClickListener(v -> { catIndex = idx; refreshChips(); refreshList(); });
            catChips[i] = c;
            chipRow.addView(c);
        }
        chipScroll.addView(chipRow);
        LinearLayout.LayoutParams chLp = lp(MATCH, WRAP);
        chLp.topMargin = dp(this, 12);
        panelView.addView(chipScroll, chLp);

        // ---- menu list ----
        ScrollView sv = new ScrollView(this);
        listBox = col(this);
        sv.addView(listBox);
        LinearLayout.LayoutParams svLp = lp(MATCH, dp(this, 230));
        svLp.topMargin = dp(this, 10);
        panelView.addView(sv, svLp);

        // ---- main actions ----
        LinearLayout acts = row(this);
        TextView copyBtn = button(this, "📋 คัดลอกข้อความ", primary(this, 14), 14);
        copyBtn.setOnClickListener(v -> {
            String msg = MsgBuilder.build(cats, mode,
                    orderNoInput.getText().toString(), placeInput.getText().toString(), payLine());
            copy(msg, "คัดลอกข้อความแล้ว");
        });
        TextView clearBtn = button(this, "ล้าง", glass(this, GLASS, 14, STROKE), 14);
        clearBtn.setOnClickListener(v -> { MsgBuilder.clear(cats); refreshList(); refreshTotal(); });
        acts.addView(copyBtn, lpw(1));
        LinearLayout.LayoutParams clLp = lp(WRAP, WRAP);
        clLp.leftMargin = dp(this, 8);
        acts.addView(clearBtn, clLp);
        LinearLayout.LayoutParams acLp = lp(MATCH, WRAP);
        acLp.topMargin = dp(this, 10);
        panelView.addView(acts, acLp);

        // ---- shortcuts ----
        HorizontalScrollView scScroll = new HorizontalScrollView(this);
        scScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout sc = row(this);
        sc.addView(shortcut("🏦 เลขบัญชี", v ->
                copy(prefs().getString("bank", "ธ.กสิกรไทย 123-4-56789-0 บังฟาน"), "คัดลอกเลขบัญชีแล้ว")));
        sc.addView(shortcut("📱 QR ชำระเงิน", v -> shareQr()));
        sc.addView(shortcut("🕐 เวลาเปิด-ปิด", v ->
                copy(prefs().getString("hours", "ร้านเปิด 10:00 - 21:00 น. ทุกวันครับ"), "คัดลอกเวลาแล้ว")));
        sc.addView(shortcut("📍 แผนที่ร้าน", v ->
                copy(prefs().getString("map", "แผนที่ร้าน: https://maps.app.goo.gl/"), "คัดลอกแผนที่แล้ว")));
        sc.addView(shortcut("📢 โปรโมทเมนู", v -> copy(MsgBuilder.promo(), "คัดลอกข้อความโปรโมทแล้ว")));
        scScroll.addView(sc);
        LinearLayout.LayoutParams scLp = lp(MATCH, WRAP);
        scLp.topMargin = dp(this, 10);
        panelView.addView(scScroll, scLp);

        panelParams = new WindowManager.LayoutParams(MATCH, WRAP, wtype(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.BOTTOM;

        refreshSeg(); refreshChips(); refreshList(); refreshTotal();
        wm.addView(panelView, panelParams);
        panelOpen = true;
    }

    private TextView shortcut(String label, View.OnClickListener cl) {
        TextView t = chip(this, label, false);
        LinearLayout.LayoutParams p = lp(WRAP, WRAP);
        p.rightMargin = dp(this, 7);
        t.setLayoutParams(p);
        t.setOnClickListener(cl);
        return t;
    }

    private void closePanel() {
        if (panelView != null) { try { wm.removeView(panelView); } catch (Exception ignored) {} panelView = null; }
        panelOpen = false;
    }

    /* ---------------- refresh ---------------- */
    private void refreshSeg() {
        segCustomer.setBackground(mode == MsgBuilder.MODE_CUSTOMER ? glass(this, WHITE, 13, 0) : null);
        segCustomer.setTextColor(mode == MsgBuilder.MODE_CUSTOMER ? INK : WHITE);
        segStaff.setBackground(mode == MsgBuilder.MODE_STAFF ? glass(this, WHITE, 13, 0) : null);
        segStaff.setTextColor(mode == MsgBuilder.MODE_STAFF ? INK : WHITE);
        staffRow.setVisibility(mode == MsgBuilder.MODE_STAFF ? View.VISIBLE : View.GONE);
    }

    private void refreshChips() {
        for (int i = 0; i < catChips.length; i++) {
            boolean on = (i == catIndex);
            catChips[i].setBackground(on ? glass(this, WHITE, 13, 0) : glass(this, GLASS, 13, STROKE));
            catChips[i].setTextColor(on ? INK : WHITE);
        }
    }

    private void refreshList() {
        listBox.removeAllViews();
        for (MenuData.Item it : cats.get(catIndex).items) {
            LinearLayout r = row(this);
            r.setBackground(glass(this, GLASS_SOFT, 14, 0x33FFFFFF));
            r.setPadding(dp(this, 10), dp(this, 10), dp(this, 10), dp(this, 10));

            LinearLayout info = col(this);
            info.addView(text(this, it.name, 13.5f, true, WHITE));
            info.addView(text(this, it.custom ? "กำหนดเอง" : (it.price + " บาท"), 12, false, WHITE_DIM));
            r.addView(info, lpw(1));

            TextView minus = chip(this, "−", false);
            TextView qty = text(this, String.valueOf(it.qty), 15, true, WHITE);
            qty.setGravity(Gravity.CENTER);
            qty.setWidth(dp(this, 32));
            TextView plus = button(this, "+", primary(this, 11), 16);
            plus.setPadding(dp(this, 12), dp(this, 6), dp(this, 12), dp(this, 6));

            minus.setOnClickListener(v -> { if (it.qty > 0) it.qty--; qty.setText(String.valueOf(it.qty)); refreshTotal(); });
            plus.setOnClickListener(v -> { it.qty++; qty.setText(String.valueOf(it.qty)); refreshTotal(); });

            r.addView(minus); r.addView(qty); r.addView(plus);

            LinearLayout.LayoutParams rp = lp(MATCH, WRAP);
            rp.bottomMargin = dp(this, 7);
            listBox.addView(r, rp);
        }
    }

    private void refreshTotal() { totalText.setText("รวม " + MsgBuilder.total(cats) + " บาท"); }

    /* ---------------- helpers ---------------- */
    private SharedPreferences prefs() { return getSharedPreferences("messes", Context.MODE_PRIVATE); }

    private String payLine() {
        return mode == MsgBuilder.MODE_STAFF
                ? prefs().getString("paystaff", "ลูกค้าโอนจ่ายปกติแล้ว")
                : prefs().getString("payline", "ชำระเงินคนละครึ่งหรือโอนธรรมดาครับ");
    }

    private void copy(String txt, String msg) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("messes", txt));
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void shareQr() {
        String uri = prefs().getString("qr", "");
        if (uri.isEmpty()) { Toast.makeText(this, "ยังไม่ได้ตั้งรูป QR (ตั้งในแอป)", Toast.LENGTH_LONG).show(); return; }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("image/*");
        send.putExtra(Intent.EXTRA_STREAM, Uri.parse(uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, "แชร์ QR ชำระเงิน");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(chooser);
    }

    @Override public void onDestroy() {
        super.onDestroy();
        closePanel();
        if (bubbleView != null) { try { wm.removeView(bubbleView); } catch (Exception ignored) {} }
    }
}
