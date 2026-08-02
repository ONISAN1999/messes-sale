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
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import static com.messessale.app.UI.*;

/** Bubble ลอยทับแอปอื่น + แผง masonry (เมนู / การ์ดคำพูด / ค่าส่ง) */
public class BubbleService extends Service {

    private WindowManager wm;
    private View bubbleView;
    private LinearLayout panelView;
    private WindowManager.LayoutParams bubbleParams, panelParams;
    private boolean panelOpen = false;

    private List<MenuData.Cat> cats;
    private List<Store.Card> cards;
    private int mode = MsgBuilder.MODE_CUSTOMER;
    private String filter = "ทั้งหมด";
    private String place = "";

    private LinearLayout bodyBox, staffRow, placeChipRow;
    private TextView totalText, segCustomer, segStaff;
    private EditText orderNoInput, placeInput;
    private List<TextView> filterChips = new ArrayList<>();
    private int shipFee = -1; // -1 = ยังไม่เลือก, 0 = ส่งฟรี
    private static final int[] SHIP_FEES = {0, 10, 20, 30, 40};

    private static final String CH = "bubble_ch";
    private static final int C_SHRIMP = 0xFFFFB3C6;
    private static final int C_RICE   = 0xFFFFD18F;
    private static final int C_ADDON  = 0xFFFFD18F;
    private static final int C_SHIP   = 0xFF9FE1CB;
    private static final int C_PHRASE = 0xFFB5D4F4;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        cats = Store.loadMenu(this);
        cards = Store.loadCards(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForegroundNotif();
        addBubble();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra("reload", false)) {
            cards = Store.loadCards(this);
            cats = Store.loadMenu(this);
            if (panelOpen) { closePanel(); openPanel(); }
        }
        return START_STICKY;
    }

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

    /* ================= Bubble ================= */
    private void addBubble() {
        TextView b = text(this, "🦐", 26, false, WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackground(bubbleBg(this));
        int s = dp(this, 58);
        b.setElevation(dp(this, 10));
        bubbleView = b;

        bubbleParams = new WindowManager.LayoutParams(s, s, wtype(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = dp(this, 12);
        bubbleParams.y = dp(this, 220);

        bubbleView.setOnTouchListener(new View.OnTouchListener() {
            int ix, iy; float tx, ty; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        ix = bubbleParams.x; iy = bubbleParams.y;
                        tx = e.getRawX(); ty = e.getRawY(); moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int)(e.getRawX() - tx), dy = (int)(e.getRawY() - ty);
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

    /* ================= Panel ================= */
    private void togglePanel() { if (panelOpen) closePanel(); else openPanel(); }

    private GradientDrawable panelBgAlpha() {
        int a = Store.alpha(this);
        int argb = (int)(a * 2.55f) << 24 | 0x1E2134;
        GradientDrawable g = new GradientDrawable();
        g.setColor(argb);
        float r = dp(this, 26);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        g.setStroke(dp(this, 1), STROKE);
        return g;
    }

    private void openPanel() {
        cards = Store.loadCards(this);
        panelView = col(this);
        panelView.setBackground(panelBgAlpha());
        panelView.setPadding(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 14));
        panelView.setElevation(dp(this, 16));

        // ---- header ----
        LinearLayout head = row(this);
        head.addView(text(this, "🦐 Messes Sale", 15, true, WHITE), lpw(1));
        totalText = text(this, "รวม 0 บาท", 14, true, OK_GREEN);
        totalText.setPadding(0, 0, dp(this, 8), 0);
        head.addView(totalText);
        TextView opacityBtn = chip(this, "◐", false);
        Fx.onTap(opacityBtn, this::showOpacityDialog);
        head.addView(opacityBtn);
        TextView close = chip(this, "✕", false);
        LinearLayout.LayoutParams clp = lp(WRAP, WRAP); clp.leftMargin = dp(this, 5);
        close.setLayoutParams(clp);
        Fx.onTap(close, this::closePanel);
        head.addView(close);
        panelView.addView(head, lp(MATCH, WRAP));

        // ---- segmented ----
        LinearLayout seg = row(this);
        seg.setBackground(glass(this, GLASS_SOFT, 15, 0x40FFFFFF));
        seg.setPadding(dp(this,3), dp(this,3), dp(this,3), dp(this,3));
        segCustomer = text(this, "ตอบลูกค้า", 12.5f, true, WHITE);
        segStaff = text(this, "แจ้งพนักงาน", 12.5f, true, WHITE);
        for (TextView t : new TextView[]{segCustomer, segStaff}) {
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(this,8), 0, dp(this,8));
        }
        Fx.onTap(segCustomer, () -> { mode = MsgBuilder.MODE_CUSTOMER; refreshSeg(); });
        Fx.onTap(segStaff, () -> { mode = MsgBuilder.MODE_STAFF; refreshSeg(); });
        seg.addView(segCustomer, lpw(1));
        seg.addView(segStaff, lpw(1));
        LinearLayout.LayoutParams segLp = lp(MATCH, WRAP); segLp.topMargin = dp(this, 10);
        panelView.addView(seg, segLp);

        // ---- staff fields ----
        staffRow = row(this);
        orderNoInput = input(this, "order ที่");
        orderNoInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText placeStaff = input(this, "จุดส่ง");
        placeInput = placeStaff;
        LinearLayout.LayoutParams o1 = lpw(1); o1.rightMargin = dp(this, 7);
        staffRow.addView(orderNoInput, o1);
        staffRow.addView(placeStaff, lpw(2));
        LinearLayout.LayoutParams stLp = lp(MATCH, WRAP); stLp.topMargin = dp(this, 9);
        panelView.addView(staffRow, stLp);

        // ---- filter chips ----
        HorizontalScrollView fScroll = new HorizontalScrollView(this);
        fScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout fRow = row(this);
        filterChips.clear();
        List<String> filters = new ArrayList<>();
        filters.add("ทั้งหมด");
        for (MenuData.Cat c : cats) filters.add(c.name);
        filters.add("คำพูด");
        for (String f : filters) {
            TextView c = chip(this, f, f.equals(filter));
            LinearLayout.LayoutParams cp = lp(WRAP, WRAP); cp.rightMargin = dp(this, 6);
            c.setLayoutParams(cp);
            Fx.onTap(c, () -> { filter = f; refreshFilters(); rebuildBody(); });
            filterChips.add(c);
            fRow.addView(c);
        }
        fScroll.addView(fRow);
        LinearLayout.LayoutParams fLp = lp(MATCH, WRAP); fLp.topMargin = dp(this, 10);
        panelView.addView(fScroll, fLp);

        // ---- body (masonry) ----
        ScrollView sv = new ScrollView(this);
        bodyBox = col(this);
        sv.addView(bodyBox);
        LinearLayout.LayoutParams svLp = lp(MATCH, dp(this, 300));
        svLp.topMargin = dp(this, 9);
        panelView.addView(sv, svLp);

        // ---- actions ----
        LinearLayout acts = row(this);
        TextView copyBtn = button(this, "📋 คัดลอกข้อความ", primary(this, 14), 14);
        Fx.onCopyTap(copyBtn, () -> {
            String msg = MsgBuilder.build(cats, mode,
                    orderNoInput.getText().toString(), placeInput.getText().toString(),
                    payLine(), place, shipFee);
            copy(msg, "คัดลอกข้อความแล้ว");
        });
        TextView clearBtn = button(this, "ล้าง", glass(this, GLASS, 14, STROKE), 14);
        Fx.onTap(clearBtn, () -> { MsgBuilder.clear(cats); shipFee = -1; rebuildBody(); refreshTotal(); });
        acts.addView(copyBtn, lpw(1));
        LinearLayout.LayoutParams cl2 = lp(WRAP, WRAP); cl2.leftMargin = dp(this, 7);
        acts.addView(clearBtn, cl2);
        LinearLayout.LayoutParams acLp = lp(MATCH, WRAP); acLp.topMargin = dp(this, 10);
        panelView.addView(acts, acLp);

        panelParams = new WindowManager.LayoutParams(MATCH, WRAP, wtype(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.BOTTOM;

        refreshSeg(); rebuildBody(); refreshTotal();
        wm.addView(panelView, panelParams);
        panelOpen = true;
    }

    private void closePanel() {
        if (panelView != null) { try { wm.removeView(panelView); } catch (Exception ignored) {} panelView = null; }
        panelOpen = false;
    }

    /* ================= body ================= */
    private void rebuildBody() {
        bodyBox.removeAllViews();

        boolean showPhrase = filter.equals("ทั้งหมด") || filter.equals("คำพูด");
        if (showPhrase) {
            LinearLayout h = row(this);
            h.addView(Masonry.header(this, "คำที่พิมพ์บ่อย", C_PHRASE), lpw(1));
            TextView add = chip(this, "+ เพิ่ม", false);
            Fx.onTap(add, () -> openCardEditor(-1));
            h.addView(add);
            bodyBox.addView(h, lp(MATCH, WRAP));

            final int n = cards.size();
            bodyBox.addView(Masonry.grid(this, n + 1, new Masonry.CardBuilder() {
                @Override public View build(int i) {
                    return (i < n) ? phraseCard(i) : addCardTile();
                }
                @Override public int weight(int i) {
                    if (i >= n) return 60;
                    Store.Card c = cards.get(i);
                    int w = 78 + Math.min(c.text.length(), 90) / 3;
                    if (!c.images.isEmpty()) w += (c.images.size() == 1 ? 78 : 60);
                    return w;
                }
            }), lp(MATCH, WRAP));
        }

        for (int ci = 0; ci < cats.size(); ci++) {
            final int catIdx = ci;
            MenuData.Cat cat = cats.get(ci);
            if (!filter.equals("ทั้งหมด") && !filter.equals(cat.name)) continue;
            boolean isShip = cat.name.equals("ค่าส่ง");
            int color = cat.name.equals("กุ้งเผา") ? C_SHRIMP : isShip ? C_SHIP : C_RICE;

            LinearLayout ch = row(this);
            ch.addView(Masonry.header(this, cat.name, color), lpw(1));
            if (!isShip) {
                TextView addM = chip(this, "+ เพิ่มเมนู", false);
                Fx.onTap(addM, () -> openMenuEditor(catIdx, -1));
                ch.addView(addM);
            }
            bodyBox.addView(ch, lp(MATCH, WRAP));

            if (isShip) {
                bodyBox.addView(shipBlock(cat), lp(MATCH, WRAP));
            } else {
                final List<MenuData.Item> items = cat.items;
                final int n = items.size();
                bodyBox.addView(Masonry.grid(this, n + 1, new Masonry.CardBuilder() {
                    @Override public View build(int i) {
                        return (i < n) ? menuCard(items.get(i), catIdx, i) : addMenuTile(catIdx);
                    }
                    @Override public int weight(int i) {
                        return (i >= n) ? 58 : 62 + Math.min(items.get(i).name.length(), 40);
                    }
                }), lp(MATCH, WRAP));
            }
        }
    }

    /* ---- menu card ---- */
    private View menuCard(MenuData.Item it, int catIdx, int itemIdx) {
        LinearLayout card = col(this);
        boolean on = it.qty > 0;
        card.setBackground(cardGrad(this, catIdx * 3 + itemIdx, 16));
        card.setElevation(dp(this, on ? 8 : 3));
        card.setPadding(dp(this,11), dp(this,10), dp(this,11), dp(this,10));

        LinearLayout top = row(this);
        top.addView(text(this, it.name, 12.5f, true, WHITE), lpw(1));
        if (on) {
            TextView badge = text(this, String.valueOf(it.qty), 12, true, 0xFF15181F);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(glass(this, WHITE, 20, 0));
            badge.setPadding(dp(this,8), dp(this,2), dp(this,8), dp(this,2));
            LinearLayout.LayoutParams bgp = lp(WRAP, WRAP); bgp.leftMargin = dp(this,6);
            top.addView(badge, bgp);
        }
        card.addView(top, lp(MATCH, WRAP));

        LinearLayout bottom = row(this);
        bottom.addView(text(this, it.custom ? "กำหนดเอง" : (it.price + " บาท"), 11.5f,
                true, 0xF2FFFFFF), lpw(1));

        if (on) {
            TextView minus = text(this, "−", 14, true, WHITE);
            minus.setGravity(Gravity.CENTER);
            minus.setBackground(glass(this, 0x40000000, 9, 0x66FFFFFF));
            minus.setPadding(dp(this,10), dp(this,2), dp(this,10), dp(this,2));
            Fx.onTap(minus, () -> { it.qty--; rebuildBody(); refreshTotal(); });
            bottom.addView(minus);
            TextView plus2 = text(this, "+", 14, true, WHITE);
            plus2.setGravity(Gravity.CENTER);
            plus2.setBackground(glass(this, 0x33000000, 9, 0x59FFFFFF));
            plus2.setPadding(dp(this,11), dp(this,2), dp(this,11), dp(this,2));
            LinearLayout.LayoutParams p2 = lp(WRAP, WRAP); p2.leftMargin = dp(this,5);
            Fx.onTap(plus2, () -> { it.qty++; rebuildBody(); refreshTotal(); });
            bottom.addView(plus2, p2);
        } else {
            TextView plus = text(this, "+", 14, true, WHITE);
            plus.setGravity(Gravity.CENTER);
            plus.setBackground(glass(this, 0x33000000, 9, 0x59FFFFFF));
            plus.setPadding(dp(this,11), dp(this,2), dp(this,11), dp(this,2));
            bottom.addView(plus);
        }

        LinearLayout.LayoutParams bp = lp(MATCH, WRAP); bp.topMargin = dp(this, 8);
        card.addView(bottom, bp);

        Fx.onTap(card, () -> { it.qty++; rebuildBody(); refreshTotal(); });
        Fx.onHold(card, () -> openMenuEditor(catIdx, itemIdx));
        return card;
    }

    /** ไทล์ ＋ เพิ่มเมนู ท้ายแต่ละหมวด */
    private View addMenuTile(int catIdx) {
        LinearLayout t = col(this);
        t.setBackground(glass(this, 0x0FFFFFFF, 12, 0x52FFFFFF));
        t.setPadding(dp(this,9), dp(this,13), dp(this,9), dp(this,13));
        t.setGravity(Gravity.CENTER);
        TextView a = text(this, "＋ เพิ่มเมนู", 11.5f, true, WHITE_DIM);
        a.setGravity(Gravity.CENTER);
        t.addView(a);
        TextView b = text(this, "กดค้างที่การ์ดเพื่อแก้ไข", 10.5f, false, 0xFF8FA0BD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(this,4), 0, 0);
        t.addView(b);
        Fx.onTap(t, () -> openMenuEditor(catIdx, -1));
        return t;
    }

    private void openMenuEditor(int catIdx, int itemIdx) {
        closePanel();
        Intent i = new Intent(this, MenuEditorActivity.class);
        i.putExtra("cat", catIdx);
        i.putExtra("item", itemIdx);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    /* ---- delivery block ---- */
    private View shipBlock(MenuData.Cat cat) {
        LinearLayout box = col(this);
        box.setBackground(glass(this, 0x249FE1CB, 12, 0x669FE1CB));
        box.setPadding(dp(this,9), dp(this,9), dp(this,9), dp(this,9));

        // เลือกค่าส่ง: ส่งฟรี / 10 / 20 / 30 / 40
        box.addView(text(this, "เลือกค่าส่ง", 11.5f, false, WHITE_DIM));
        HorizontalScrollView fsv = new HorizontalScrollView(this);
        fsv.setHorizontalScrollBarEnabled(false);
        LinearLayout feeRow = row(this);
        for (int f : SHIP_FEES) {
            final int fee = f;
            boolean on = (shipFee == fee);
            TextView c = chip(this, fee == 0 ? "ส่งฟรี" : (fee + " บาท"), on);
            if (on) { c.setBackground(glass(this, 0xFF9FE1CB, 11, 0)); c.setTextColor(0xFF04342C); }
            c.setPadding(dp(this,13), dp(this,7), dp(this,13), dp(this,7));
            LinearLayout.LayoutParams cp = lp(WRAP, WRAP); cp.rightMargin = dp(this,6);
            c.setLayoutParams(cp);
            Fx.onTap(c, () -> { shipFee = (shipFee == fee) ? -1 : fee; rebuildBody(); refreshTotal(); });
            feeRow.addView(c);
        }
        fsv.addView(feeRow);
        LinearLayout.LayoutParams fp = lp(MATCH, WRAP); fp.topMargin = dp(this,6);
        box.addView(fsv, fp);

        // ช่องส่งที่ไหน
        EditText placeField = input(this, "ส่งที่ไหน เช่น Tara");
        placeField.setText(place);
        placeField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int a,int b,int c) {}
            @Override public void onTextChanged(CharSequence s,int a,int b,int c) { place = s.toString(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        box.addView(placeField, lp(MATCH, WRAP));

        // ชิปหอที่ใช้บ่อย
        HorizontalScrollView psv = new HorizontalScrollView(this);
        psv.setHorizontalScrollBarEnabled(false);
        placeChipRow = row(this);
        List<String> places = Store.loadPlaces(this);
        for (String p : places) {
            TextView c = chip(this, p, p.equals(place));
            if (p.equals(place)) { c.setBackground(glass(this, 0xFF9FE1CB, 10, 0)); c.setTextColor(0xFF04342C); }
            c.setPadding(dp(this,11), dp(this,5), dp(this,11), dp(this,5));
            LinearLayout.LayoutParams cp = lp(WRAP, WRAP); cp.rightMargin = dp(this,5);
            c.setLayoutParams(cp);
            Fx.onTap(c, () -> { place = p; placeField.setText(p); rebuildBody(); });
            Fx.onHold(c, () -> { Store.forgetPlace(this, p); rebuildBody();
                Toast.makeText(this, "ลบ " + p + " แล้ว", Toast.LENGTH_SHORT).show(); });
            placeChipRow.addView(c);
        }
        TextView save = chip(this, "＋ จำไว้", false);
        save.setPadding(dp(this,11), dp(this,5), dp(this,11), dp(this,5));
        Fx.onTap(save, () -> {
            String p = placeField.getText().toString().trim();
            if (p.isEmpty()) { Toast.makeText(this, "พิมพ์ชื่อจุดส่งก่อน", Toast.LENGTH_SHORT).show(); return; }
            Store.rememberPlace(this, p); place = p; rebuildBody();
            Toast.makeText(this, "จำ " + p + " แล้ว", Toast.LENGTH_SHORT).show();
        });
        placeChipRow.addView(save);
        psv.addView(placeChipRow);
        LinearLayout.LayoutParams pl = lp(MATCH, WRAP); pl.topMargin = dp(this, 7);
        box.addView(psv, pl);

        return box;
    }

    /* ---- phrase card ---- */
    private View phraseCard(int idx) {
        Store.Card c = cards.get(idx);
        LinearLayout card = col(this);
        card.setBackground(cardGrad(this, idx + 1, 16));
        card.setElevation(dp(this, 4));
        card.setPadding(dp(this,10), dp(this,10), dp(this,10), dp(this,10));

        if (!c.images.isEmpty()) {
            if (c.images.size() == 1) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                setImg(iv, c.images.get(0));
                LinearLayout.LayoutParams ip = lp(MATCH, dp(this, 70));
                ip.bottomMargin = dp(this, 7);
                card.addView(iv, ip);
            } else {
                LinearLayout strip = row(this);
                int show = Math.min(c.images.size(), 3);
                for (int i = 0; i < show; i++) {
                    ImageView iv = new ImageView(this);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    setImg(iv, c.images.get(i));
                    LinearLayout.LayoutParams ip = lpw(1);
                    ip.height = dp(this, 52);
                    if (i > 0) ip.leftMargin = dp(this, 3);
                    strip.addView(iv, ip);
                }
                if (c.images.size() > 3) {
                    TextView more = text(this, "+" + (c.images.size() - 3), 12, true, WHITE);
                    more.setGravity(Gravity.CENTER);
                    more.setBackground(glass(this, 0x5031527A, 7, 0));
                    LinearLayout.LayoutParams mp = lpw(0.7f);
                    mp.height = dp(this, 52); mp.leftMargin = dp(this, 3);
                    strip.addView(more, mp);
                }
                LinearLayout.LayoutParams sp = lp(MATCH, WRAP); sp.bottomMargin = dp(this, 7);
                card.addView(strip, sp);
            }
        }

        card.addView(text(this, c.title, 12.5f, true, WHITE));
        String preview = c.text.length() > 70 ? c.text.substring(0, 70) + "…" : c.text;
        TextView tv = text(this, preview, 11, false, 0xE6FFFFFF);
        tv.setPadding(0, dp(this, 3), 0, 0);
        card.addView(tv);

        // แถบท้ายการ์ด — บอกว่ากดแล้วคัดลอกทันที
        TextView hint = text(this, c.images.isEmpty() ? "📋 แตะเพื่อคัดลอก" : "🚀 แตะเพื่อส่งพร้อมรูป",
                10.5f, true, 0xF2FFFFFF);
        hint.setGravity(Gravity.CENTER);
        hint.setBackground(glass(this, 0x33000000, 9, 0x40FFFFFF));
        hint.setPadding(0, dp(this,5), 0, dp(this,5));
        LinearLayout.LayoutParams hp = lp(MATCH, WRAP); hp.topMargin = dp(this, 8);
        card.addView(hint, hp);

        // แตะการ์ด = คัดลอกทันที (มีรูป = คัดลอก + เปิดแชร์รูป)
        Fx.onCopyTap(card, () -> {
            if (c.images.isEmpty()) copy(c.text, "คัดลอกข้อความแล้ว");
            else sendCard(c);
        });
        Fx.onHold(card, () -> openCardEditor(idx));
        return card;
    }

    private View addCardTile() {
        LinearLayout t = col(this);
        t.setBackground(glass(this, 0x0FFFFFFF, 12, 0x52FFFFFF));
        t.setPadding(dp(this,9), dp(this,14), dp(this,9), dp(this,14));
        t.setGravity(Gravity.CENTER);
        TextView a = text(this, "＋ เพิ่มการ์ดใหม่", 11.5f, true, WHITE_DIM);
        a.setGravity(Gravity.CENTER);
        t.addView(a);
        TextView b = text(this, "ข้อความ + รูปได้หลายรูป", 10.5f, false, 0xFF8FA0BD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(this,4), 0, 0);
        t.addView(b);
        Fx.onTap(t, () -> openCardEditor(-1));
        return t;
    }

    private void setImg(ImageView iv, String uri) {
        try { iv.setImageURI(Uri.parse(uri)); } catch (Exception ignored) {}
        iv.setBackground(glass(this, 0xFF2A4A6E, 7, 0));
        iv.setClipToOutline(true);
    }

    /** ส่งเลย: ก็อปข้อความ + เปิดแชร์รูป */
    private void sendCard(Store.Card c) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("messes", c.text));

        ArrayList<Uri> uris = new ArrayList<>();
        for (String s : c.images) { try { uris.add(Uri.parse(s)); } catch (Exception ignored) {} }
        if (uris.isEmpty()) { Toast.makeText(this, "คัดลอกข้อความแล้ว", Toast.LENGTH_SHORT).show(); return; }

        Intent send;
        if (uris.size() == 1) {
            send = new Intent(Intent.ACTION_SEND);
            send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            send = new Intent(Intent.ACTION_SEND_MULTIPLE);
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        send.setType("image/*");
        send.putExtra(Intent.EXTRA_TEXT, c.text);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, "ส่งรูป + ข้อความ");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(chooser);
        Toast.makeText(this, "ข้อความคัดลอกแล้ว — วางในแชตได้เลย", Toast.LENGTH_LONG).show();
    }

    private void openCardEditor(int idx) {
        closePanel();
        Intent i = new Intent(this, CardEditorActivity.class);
        i.putExtra("index", idx);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    /* ---- opacity ---- */
    private void showOpacityDialog() {
        LinearLayout box = col(this);
        box.setBackground(glass(this, 0xF21E2134, 18, STROKE));
        box.setPadding(dp(this,16), dp(this,14), dp(this,16), dp(this,14));
        box.addView(text(this, "ความใสของแผง", 14, true, WHITE));
        TextView val = text(this, Store.alpha(this) + "%", 12, false, WHITE_DIM);
        val.setPadding(0, dp(this,3), 0, dp(this,6));
        box.addView(val);
        SeekBar sb = new SeekBar(this);
        sb.setMax(65);
        sb.setProgress(Store.alpha(this) - 35);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int a = p + 35;
                val.setText(a + "%");
                Store.setAlpha(BubbleService.this, a);
                if (panelView != null) panelView.setBackground(panelBgAlpha());
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(sb, lp(MATCH, WRAP));
        TextView ok = button(this, "เสร็จ", primary(this, 12), 13);
        LinearLayout.LayoutParams op = lp(MATCH, WRAP); op.topMargin = dp(this, 8);
        box.addView(ok, op);

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                dp(this, 280), WRAP, wtype(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.CENTER;
        wm.addView(box, p);
        ok.setOnClickListener(v -> { try { wm.removeView(box); } catch (Exception ignored) {} });
    }

    /* ================= refresh ================= */
    private void refreshSeg() {
        segCustomer.setBackground(mode == MsgBuilder.MODE_CUSTOMER ? glass(this, WHITE, 12, 0) : null);
        segCustomer.setTextColor(mode == MsgBuilder.MODE_CUSTOMER ? INK : WHITE);
        segStaff.setBackground(mode == MsgBuilder.MODE_STAFF ? glass(this, WHITE, 12, 0) : null);
        segStaff.setTextColor(mode == MsgBuilder.MODE_STAFF ? INK : WHITE);
        staffRow.setVisibility(mode == MsgBuilder.MODE_STAFF ? View.VISIBLE : View.GONE);
    }

    private void refreshFilters() {
        for (TextView c : filterChips) {
            boolean on = c.getText().toString().equals(filter);
            c.setBackground(on ? glass(this, WHITE, 12, 0) : glass(this, GLASS, 12, STROKE));
            c.setTextColor(on ? INK : WHITE);
        }
    }

    private void refreshTotal() {
        totalText.setText("รวม " + MsgBuilder.total(cats, shipFee) + " บาท");
    }

    /* ================= helpers ================= */
    private String payLine() {
        return mode == MsgBuilder.MODE_STAFF
                ? Store.prefs(this).getString("paystaff", "ลูกค้าโอนจ่ายปกติแล้ว")
                : Store.prefs(this).getString("payline", "ชำระเงินคนละครึ่งหรือโอนธรรมดาครับ");
    }

    private void copy(String txt, String msg) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("messes", txt));
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override public void onDestroy() {
        super.onDestroy();
        closePanel();
        if (bubbleView != null) { try { wm.removeView(bubbleView); } catch (Exception ignored) {} }
    }
}
