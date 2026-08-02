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

    private static final String CH = "bubble_ch";
    private static final int C_SHRIMP = 0xFFFFB3C6;
    private static final int C_RICE   = 0xFFFFD18F;
    private static final int C_ADDON  = 0xFFFFD18F;
    private static final int C_SHIP   = 0xFF9FE1CB;
    private static final int C_PHRASE = 0xFFB5D4F4;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        cats = MenuData.build();
        cards = Store.loadCards(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForegroundNotif();
        addBubble();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra("reload", false)) {
            cards = Store.loadCards(this);
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
        opacityBtn.setOnClickListener(v -> showOpacityDialog());
        head.addView(opacityBtn);
        TextView close = chip(this, "✕", false);
        LinearLayout.LayoutParams clp = lp(WRAP, WRAP); clp.leftMargin = dp(this, 5);
        close.setLayoutParams(clp);
        close.setOnClickListener(v -> closePanel());
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
        segCustomer.setOnClickListener(v -> { mode = MsgBuilder.MODE_CUSTOMER; refreshSeg(); });
        segStaff.setOnClickListener(v -> { mode = MsgBuilder.MODE_STAFF; refreshSeg(); });
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
            c.setOnClickListener(v -> { filter = f; refreshFilters(); rebuildBody(); });
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
        copyBtn.setOnClickListener(v -> {
            String msg = MsgBuilder.build(cats, mode,
                    orderNoInput.getText().toString(), placeInput.getText().toString(), payLine(), place);
            copy(msg, "คัดลอกข้อความแล้ว");
        });
        TextView clearBtn = button(this, "ล้าง", glass(this, GLASS, 14, STROKE), 14);
        clearBtn.setOnClickListener(v -> { MsgBuilder.clear(cats); rebuildBody(); refreshTotal(); });
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
            add.setOnClickListener(v -> openCardEditor(-1));
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
                    int w = 70 + Math.min(c.text.length(), 90) / 3;
                    if (!c.images.isEmpty()) w += (c.images.size() == 1 ? 78 : 60);
                    return w;
                }
            }), lp(MATCH, WRAP));
        }

        for (MenuData.Cat cat : cats) {
            if (!filter.equals("ทั้งหมด") && !filter.equals(cat.name)) continue;
            boolean isShip = cat.name.equals("ค่าส่ง");
            int color = cat.name.equals("กุ้งเผา") ? C_SHRIMP : isShip ? C_SHIP : C_RICE;
            bodyBox.addView(Masonry.header(this, cat.name, color), lp(MATCH, WRAP));

            if (isShip) {
                bodyBox.addView(shipBlock(cat), lp(MATCH, WRAP));
            } else {
                final List<MenuData.Item> items = cat.items;
                bodyBox.addView(Masonry.grid(this, items.size(), new Masonry.CardBuilder() {
                    @Override public View build(int i) { return menuCard(items.get(i)); }
                    @Override public int weight(int i) { return 62 + Math.min(items.get(i).name.length(), 40); }
                }), lp(MATCH, WRAP));
            }
        }
    }

    /* ---- menu card ---- */
    private View menuCard(MenuData.Item it) {
        LinearLayout card = col(this);
        boolean on = it.qty > 0;
        card.setBackground(glass(this, on ? 0x24FFFFFF : 0x14FFFFFF, 12, on ? 0x40FFFFFF : 0x2EFFFFFF));
        card.setPadding(dp(this,9), dp(this,8), dp(this,9), dp(this,8));

        card.addView(text(this, it.name, 12, true, WHITE));

        LinearLayout bottom = row(this);
        bottom.addView(text(this, it.custom ? "กำหนดเอง" : (it.price + " บาท"), 11.5f,
                false, on ? OK_GREEN : WHITE_DIM), lpw(1));

        if (on) {
            TextView minus = chip(this, "−", false);
            minus.setPadding(dp(this,9), dp(this,3), dp(this,9), dp(this,3));
            minus.setOnClickListener(v -> { it.qty--; rebuildBody(); refreshTotal(); });
            bottom.addView(minus);
            TextView q = text(this, String.valueOf(it.qty), 12.5f, true, 0xFF4B1528);
            q.setGravity(Gravity.CENTER);
            q.setBackground(glass(this, 0xFFFF8A9E, 8, 0));
            q.setPadding(dp(this,8), dp(this,3), dp(this,8), dp(this,3));
            LinearLayout.LayoutParams qp = lp(WRAP, WRAP); qp.leftMargin = dp(this,5);
            bottom.addView(q, qp);
        } else {
            TextView plus = text(this, "+", 14, true, WHITE);
            plus.setGravity(Gravity.CENTER);
            plus.setBackground(glass(this, GLASS, 8, STROKE));
            plus.setPadding(dp(this,10), dp(this,2), dp(this,10), dp(this,2));
            bottom.addView(plus);
        }

        LinearLayout.LayoutParams bp = lp(MATCH, WRAP); bp.topMargin = dp(this, 6);
        card.addView(bottom, bp);

        card.setOnClickListener(v -> { it.qty++; rebuildBody(); refreshTotal(); });
        return card;
    }

    /* ---- delivery block ---- */
    private View shipBlock(MenuData.Cat cat) {
        LinearLayout box = col(this);
        box.setBackground(glass(this, 0x249FE1CB, 12, 0x669FE1CB));
        box.setPadding(dp(this,9), dp(this,9), dp(this,9), dp(this,9));

        for (MenuData.Item it : cat.items) {
            LinearLayout r = row(this);
            r.addView(text(this, it.name, 12, true, WHITE), lpw(1));
            r.addView(text(this, it.custom ? "กำหนดเอง" : (it.price + " บาท"), 11.5f, false,
                    it.qty > 0 ? OK_GREEN : WHITE_DIM));
            if (it.qty > 0) {
                TextView q = text(this, String.valueOf(it.qty), 12.5f, true, 0xFF4B1528);
                q.setGravity(Gravity.CENTER);
                q.setBackground(glass(this, 0xFFFF8A9E, 8, 0));
                q.setPadding(dp(this,8), dp(this,3), dp(this,8), dp(this,3));
                LinearLayout.LayoutParams qp = lp(WRAP, WRAP); qp.leftMargin = dp(this,7);
                r.addView(q, qp);
                TextView minus = chip(this, "−", false);
                minus.setPadding(dp(this,9), dp(this,3), dp(this,9), dp(this,3));
                LinearLayout.LayoutParams mp = lp(WRAP, WRAP); mp.leftMargin = dp(this,5);
                minus.setLayoutParams(mp);
                minus.setOnClickListener(v -> { it.qty--; rebuildBody(); refreshTotal(); });
                r.addView(minus);
            } else {
                TextView plus = text(this, "+", 14, true, WHITE);
                plus.setGravity(Gravity.CENTER);
                plus.setBackground(glass(this, GLASS, 8, STROKE));
                plus.setPadding(dp(this,10), dp(this,2), dp(this,10), dp(this,2));
                LinearLayout.LayoutParams pp = lp(WRAP, WRAP); pp.leftMargin = dp(this,7);
                r.addView(plus, pp);
            }
            r.setOnClickListener(v -> { it.qty++; rebuildBody(); refreshTotal(); });
            LinearLayout.LayoutParams rp = lp(MATCH, WRAP); rp.bottomMargin = dp(this, 6);
            box.addView(r, rp);
        }

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
            c.setOnClickListener(v -> { place = p; placeField.setText(p); rebuildBody(); });
            c.setOnLongClickListener(v -> { Store.forgetPlace(this, p); rebuildBody();
                Toast.makeText(this, "ลบ " + p + " แล้ว", Toast.LENGTH_SHORT).show(); return true; });
            placeChipRow.addView(c);
        }
        TextView save = chip(this, "＋ จำไว้", false);
        save.setPadding(dp(this,11), dp(this,5), dp(this,11), dp(this,5));
        save.setOnClickListener(v -> {
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
        card.setBackground(glass(this, 0x2A378ADD, 12, 0x7385B7EB));
        card.setPadding(dp(this,8), dp(this,8), dp(this,8), dp(this,8));

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

        card.addView(text(this, c.title, 12, true, WHITE));
        String preview = c.text.length() > 70 ? c.text.substring(0, 70) + "…" : c.text;
        TextView tv = text(this, preview, 11, false, WHITE_DIM);
        tv.setPadding(0, dp(this, 3), 0, 0);
        card.addView(tv);

        LinearLayout btns = row(this);
        if (c.images.isEmpty()) {
            TextView copyB = text(this, "📋 คัดลอก", 11, true, WHITE);
            copyB.setGravity(Gravity.CENTER);
            copyB.setBackground(glass(this, 0x29FFFFFF, 8, 0));
            copyB.setPadding(0, dp(this,6), 0, dp(this,6));
            copyB.setOnClickListener(v -> copy(c.text, "คัดลอกข้อความแล้ว"));
            btns.addView(copyB, lpw(1));
        } else {
            TextView sendB = text(this, "🚀 ส่งเลย", 11, true, 0xFF042C53);
            sendB.setGravity(Gravity.CENTER);
            sendB.setBackground(glass(this, 0xFF85B7EB, 8, 0));
            sendB.setPadding(0, dp(this,6), 0, dp(this,6));
            sendB.setOnClickListener(v -> sendCard(c));
            btns.addView(sendB, lpw(1));
            TextView copyB = text(this, "📋", 11, true, WHITE);
            copyB.setGravity(Gravity.CENTER);
            copyB.setBackground(glass(this, 0x29FFFFFF, 8, 0));
            copyB.setPadding(dp(this,9), dp(this,6), dp(this,9), dp(this,6));
            LinearLayout.LayoutParams cp2 = lp(WRAP, WRAP); cp2.leftMargin = dp(this,5);
            copyB.setLayoutParams(cp2);
            copyB.setOnClickListener(v -> copy(c.text, "คัดลอกข้อความแล้ว"));
            btns.addView(copyB);
        }
        LinearLayout.LayoutParams bp = lp(MATCH, WRAP); bp.topMargin = dp(this, 7);
        card.addView(btns, bp);

        card.setOnLongClickListener(v -> { openCardEditor(idx); return true; });
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
        t.setOnClickListener(v -> openCardEditor(-1));
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

    private void refreshTotal() { totalText.setText("รวม " + MsgBuilder.total(cats) + " บาท"); }

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
