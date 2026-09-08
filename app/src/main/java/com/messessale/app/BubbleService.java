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
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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

    private LinearLayout bodyBox, staffRow, staffPay, placeChipRow, previewBox;
    private ScrollView bodyScroll;
    private TextView totalText, segCustomer, segStaff, previewText;
    private EditText orderNoInput, placeInput, searchInput, shipPlaceField;
    private String search = "";
    private boolean syncingPlace = false;
    private MenuData.Item noteEditFor = null, priceEditFor = null;   // การ์ดที่กำลังเปิดช่องโน้ต/ราคา
    private LinearLayout editorBar;                                   // แถบใส่ราคา/โน้ต อยู่เหนือรายการ (ไม่โดนคีย์บอร์ดบัง)
    private List<TextView> filterChips = new ArrayList<>();
    private int shipFee = -1; // -1 = ยังไม่เลือก, 0 = ส่งฟรี
    private static final int[] SHIP_FEES = {0, 10, 20, 30, 40};

    /* ---- สถานะออเดอร์บนเครื่อง POS (เรียลไทม์) ---- */
    private static final String F_POS = "สถานะ POS";
    private List<PosSender.OrderStatus> posOrders = new ArrayList<>();
    private String posBanner = "";
    private long posBannerAt = 0;

    /* ---- เฝ้าดูออเดอร์ที่ส่งไป: POS กดเสร็จ → เสียงแจ้งเตือนที่มือถือ ---- */
    private static final String CH_DONE = "pos_done_ch";
    private static final long BG_POLL_MS = 10000;
    private final Runnable bgPoll = new Runnable() {
        @Override public void run() {
            checkDoneOrders();
            posUi.postDelayed(this, BG_POLL_MS);
        }
    };
    private boolean posLoading = false;
    private final android.os.Handler posUi = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable posPoll = new Runnable() {
        @Override public void run() {
            if (!panelOpen || !filter.equals(F_POS)) return;
            fetchPosOrders();
            posUi.postDelayed(this, 6000);   // รีเฟรชทุก 6 วิ
        }
    };

    private String payChannel = "";
    private String payStatus = "";
    private static final String[] PAY_CHANNELS = {"เงินสด", "โอนปกติ", "คนละครึ่ง"};
    private static final String[] PAY_STATUS = {"ชำระแล้ว", "ชำระหน้าร้าน", "สแกนหน้าลูกค้า"};

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
        posUi.postDelayed(bgPoll, 4000);
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
        TextView b = text(this, "🦐", 27, false, WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackground(bubbleBg(this));
        int s = dp(this, 62);
        b.setElevation(dp(this, 14));
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
        int argb = (int)(a * 2.55f) << 24 | 0x151A2B;
        GradientDrawable g = new GradientDrawable();
        g.setColor(argb);
        float r = dp(this, 28);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        g.setStroke(dp(this, 1), LINE);
        return g;
    }

    private void openPanel() {
        cards = Store.loadCards(this);
        panelView = col(this);
        panelView.setBackground(panelBgAlpha());
        panelView.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 14));
        panelView.setElevation(dp(this, 16));

        // ---- header ----
        LinearLayout head = row(this);
        // แถบจับ (grabber) ด้านบน
        LinearLayout titleCol = col(this);
        LinearLayout tRow = row(this);
        tRow.addView(text(this, "🦐 Messes Sale", 16, true, WHITE));
        TextView verT = text(this, ver(this), 10.5f, false, MUTED);
        verT.setPadding(dp(this, 6), 0, 0, 0);
        tRow.addView(verT);
        titleCol.addView(tRow);
        totalText = text(this, "รวม 0 บาท", 13, true, MINT);
        totalText.setPadding(0, dp(this, 1), 0, 0);
        titleCol.addView(totalText);
        head.addView(titleCol, lpw(1));

        TextView posTab = iconBtn(this, "🧾");
        if (filter.equals(F_POS)) posTab.setBackground(glass(this, ACCENT, 20, 0));
        Fx.onTap(posTab, this::openPosStatus);
        head.addView(posTab);
        TextView eyeBtn = iconBtn(this, "👁");
        ((LinearLayout.LayoutParams) eyeBtn.getLayoutParams()).leftMargin = dp(this, 6);
        Fx.onTap(eyeBtn, this::togglePreview);
        head.addView(eyeBtn);
        TextView opacityBtn = iconBtn(this, "◐");
        ((LinearLayout.LayoutParams) opacityBtn.getLayoutParams()).leftMargin = dp(this, 6);
        Fx.onTap(opacityBtn, this::showOpacityDialog);
        head.addView(opacityBtn);
        TextView close = iconBtn(this, "✕");
        ((LinearLayout.LayoutParams) close.getLayoutParams()).leftMargin = dp(this, 6);
        Fx.onTap(close, this::closePanel);
        head.addView(close);
        panelView.addView(head, lp(MATCH, WRAP));

        // ---- segmented ----
        LinearLayout seg = row(this);
        seg.setBackground(glass(this, SURFACE, 16, LINE));
        seg.setPadding(dp(this,4), dp(this,4), dp(this,4), dp(this,4));
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
        final EditText placeStaff = input(this, "จุดส่ง");
        placeInput = placeStaff;
        placeStaff.setText(place);
        placeStaff.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int a,int b,int c) {}
            @Override public void onTextChanged(CharSequence s,int a,int b,int c) {
                setPlace(s.toString(), placeStaff);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        LinearLayout.LayoutParams o1 = lpw(1); o1.rightMargin = dp(this, 7);
        staffRow.addView(orderNoInput, o1);
        staffRow.addView(placeStaff, lpw(2));
        LinearLayout.LayoutParams stLp = lp(MATCH, WRAP); stLp.topMargin = dp(this, 11);
        panelView.addView(staffRow, stLp);

        // ---- ช่องทาง/สถานะการชำระ (เฉพาะโหมดแจ้งพนักงาน) ----
        staffPay = col(this);
        LinearLayout.LayoutParams spLp = lp(MATCH, WRAP); spLp.topMargin = dp(this, 10);
        panelView.addView(staffPay, spLp);
        buildPayRows();

        // ---- filter chips ----
        HorizontalScrollView fScroll = new HorizontalScrollView(this);
        fScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout fRow = row(this);
        filterChips.clear();
        List<String> filters = new ArrayList<>();
        filters.add("ทั้งหมด");
        for (MenuData.Cat c : cats) filters.add(c.name);
        filters.add("คำพูด");
        filters.add(F_POS);
        for (String f : filters) {
            TextView c = chip(this, f, f.equals(filter));
            LinearLayout.LayoutParams cp = lp(WRAP, WRAP); cp.rightMargin = dp(this, 6);
            c.setLayoutParams(cp);
            Fx.onTap(c, () -> {
                filter = f;
                refreshFilters();
                rebuildBody();
                if (f.equals(F_POS)) { fetchPosOrders(); posUi.removeCallbacks(posPoll); posUi.postDelayed(posPoll, 6000); }
                else posUi.removeCallbacks(posPoll);
            });
            filterChips.add(c);
            fRow.addView(c);
        }
        fScroll.addView(fRow);
        LinearLayout.LayoutParams fLp = lp(MATCH, WRAP); fLp.topMargin = dp(this, 12);
        panelView.addView(fScroll, fLp);

        // ---- ค้นหาเมนู ----
        LinearLayout sRow = row(this);
        searchInput = input(this, "🔍 ค้นหาชื่อเมนู");
        searchInput.setText(search);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int a,int b,int c) {}
            @Override public void onTextChanged(CharSequence s,int a,int b,int c) {
                search = s.toString(); rebuildBody();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        sRow.addView(searchInput, lpw(1));
        TextView sClear = text(this, "✕", 14, true, WHITE);
        sClear.setGravity(Gravity.CENTER);
        sClear.setBackground(glass(this, SURFACE_2, 14, LINE));
        sClear.setPadding(dp(this,13), dp(this,11), dp(this,13), dp(this,11));
        LinearLayout.LayoutParams scp = lp(WRAP, WRAP); scp.leftMargin = dp(this,6);
        sClear.setLayoutParams(scp);
        Fx.onTap(sClear, () -> { search = ""; searchInput.setText(""); rebuildBody(); });
        sRow.addView(sClear);
        LinearLayout.LayoutParams srLp = lp(MATCH, WRAP); srLp.topMargin = dp(this, 10);
        panelView.addView(sRow, srLp);
        watchKeyboard(searchInput);

        // ---- แถบใส่ราคา / โน้ต (โชว์เฉพาะตอนกำลังแก้) ----
        editorBar = col(this);
        editorBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams ebLp = lp(MATCH, WRAP); ebLp.topMargin = dp(this, 8);
        panelView.addView(editorBar, ebLp);

        // ---- body (masonry) ----
        ScrollView sv = new ScrollView(this);
        bodyScroll = sv;
        bodyBox = col(this);
        sv.addView(bodyBox);
        LinearLayout.LayoutParams svLp = lp(MATCH, dp(this, BODY_TALL));
        svLp.topMargin = dp(this, 11);
        panelView.addView(sv, svLp);

        // เวลาแป้นพิมพ์ขึ้น ให้ย่อพื้นที่รายการ ปุ่มคัดลอกจะไม่โดนบัง
        watchKeyboard(orderNoInput);
        watchKeyboard(placeStaff);

        // ---- พรีวิวข้อความ ----
        previewBox = col(this);
        previewBox.setBackground(glass(this, 0x33000000, 13, STROKE));
        previewBox.setPadding(dp(this,12), dp(this,10), dp(this,12), dp(this,10));
        previewBox.setVisibility(View.GONE);
        LinearLayout pHead = row(this);
        pHead.addView(text(this, "พรีวิวข้อความ", 11, false, WHITE_DIM), lpw(1));
        TextView pClose = text(this, "ซ่อน", 11, true, 0xFFFFB3C6);
        Fx.onTap(pClose, this::togglePreview);
        pHead.addView(pClose);
        previewBox.addView(pHead, lp(MATCH, WRAP));
        ScrollView pSv = new ScrollView(this);
        previewText = text(this, "", 11.5f, false, WHITE);
        previewText.setPadding(0, dp(this,6), 0, 0);
        pSv.addView(previewText);
        previewBox.addView(pSv, lp(MATCH, dp(this, 130)));
        LinearLayout.LayoutParams pvLp = lp(MATCH, WRAP); pvLp.topMargin = dp(this, 10);
        panelView.addView(previewBox, pvLp);

        // ---- actions ----
        LinearLayout acts = row(this);
        TextView copyBtn = button(this, "📋  คัดลอกข้อความ", primary(this, 16), 14);
        Fx.onCopyTap(copyBtn, () -> {
            String msg = MsgBuilder.build(cats, mode,
                    orderNoInput.getText().toString(), placeInput.getText().toString(),
                    payLine(), place, shipFee);
            copy(msg, "คัดลอกข้อความแล้ว");
        });
        TextView posBtn = button(this, "🧾  ส่งเข้า POS", glass(this, 0x2634D399, 16, MINT), 14);
        posBtn.setTextColor(MINT);
        Fx.onCopyTap(posBtn, this::sendToPos);
        TextView clearBtn = button(this, "ล้าง", glass(this, SURFACE_2, 16, LINE), 14);
        clearBtn.setTextColor(WHITE_DIM);
        Fx.onTap(clearBtn, () -> { MsgBuilder.clear(cats); shipFee = -1; rebuildBody(); refreshTotal(); });
        acts.addView(copyBtn, lpw(1));
        LinearLayout.LayoutParams pbLp = lp(WRAP, WRAP); pbLp.leftMargin = dp(this, 7);
        acts.addView(posBtn, pbLp);
        LinearLayout.LayoutParams cl2 = lp(WRAP, WRAP); cl2.leftMargin = dp(this, 7);
        acts.addView(clearBtn, cl2);
        LinearLayout.LayoutParams acLp = lp(MATCH, WRAP); acLp.topMargin = dp(this, 10);
        panelView.addView(acts, acLp);

        panelParams = new WindowManager.LayoutParams(MATCH, WRAP, wtype(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.BOTTOM;
        panelParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

        // กดปุ่มย้อนกลับของมือถือ = ย่อกลับเป็น Bubble
        panelView.setFocusableInTouchMode(true);
        panelView.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) {
                closePanel();
                return true;
            }
            return false;
        });

        refreshSeg(); rebuildBody(); refreshTotal();
        wm.addView(panelView, panelParams);
        panelView.requestFocus();
        panelOpen = true;
    }

    /** เปิดหน้าสถานะออเดอร์ที่ส่งเข้า POS */
    private void openPosStatus() {
        filter = F_POS;
        refreshFilters();
        rebuildBody();
        fetchPosOrders();
        posUi.removeCallbacks(posPoll);
        posUi.postDelayed(posPoll, 6000);
    }

    /** เปิด/ปิดกล่องพรีวิวข้อความ */
    private void togglePreview() {
        if (previewBox == null) return;
        boolean show = previewBox.getVisibility() != View.VISIBLE;
        previewBox.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) { setBodyHeight(BODY_SHORT); refreshPreview(); }
        else setBodyHeight(BODY_TALL);
    }

    private void refreshPreview() {
        if (previewText == null || previewBox == null) return;
        if (previewBox.getVisibility() != View.VISIBLE) return;
        String msg = MsgBuilder.build(cats, mode,
                orderNoInput.getText().toString(), placeInput.getText().toString(),
                payLine(), place, shipFee);
        previewText.setText(msg.isEmpty() ? "— ยังไม่ได้เลือกเมนู —" : msg);
    }

    private static final int BODY_TALL = 300;
    private static final int BODY_SHORT = 150;

    /** ย่อ/ขยายพื้นที่รายการเมื่อแป้นพิมพ์ขึ้น-ลง */
    private void setBodyHeight(int dpH) {
        if (bodyScroll == null) return;
        ViewGroup.LayoutParams p = bodyScroll.getLayoutParams();
        int h = dp(this, dpH);
        if (p != null && p.height != h) { p.height = h; bodyScroll.setLayoutParams(p); }
    }

    private void watchKeyboard(EditText e) {
        e.setOnFocusChangeListener((v, has) -> setBodyHeight(has ? BODY_SHORT : BODY_TALL));
    }

    /** แถวชิป: ช่องทางที่ลูกค้าชำระ + สถานะชำระ */
    private void buildPayRows() {
        staffPay.removeAllViews();
        staffPay.addView(payRow("ช่องทางที่ลูกค้าชำระ", PAY_CHANNELS, true), lp(MATCH, WRAP));
        View gap = new View(this);
        staffPay.addView(gap, lp(MATCH, dp(this, 9)));
        staffPay.addView(payRow("สถานะชำระ", PAY_STATUS, false), lp(MATCH, WRAP));
    }

    private View payRow(String label, String[] options, boolean isChannel) {
        LinearLayout box = col(this);
        box.addView(text(this, label, 11, false, WHITE_DIM));

        HorizontalScrollView sv = new HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        LinearLayout r = row(this);
        for (String opt : options) {
            final String o = opt;
            boolean on = isChannel ? o.equals(payChannel) : o.equals(payStatus);
            TextView c = chip(this, o, on);
            if (on) { c.setBackground(glass(this, ACCENT, 20, 0)); c.setTextColor(WHITE); }
            c.setPadding(dp(this,13), dp(this,7), dp(this,13), dp(this,7));
            LinearLayout.LayoutParams cp = lp(WRAP, WRAP); cp.rightMargin = dp(this,6);
            c.setLayoutParams(cp);
            Fx.onTap(c, () -> {
                if (isChannel) payChannel = o.equals(payChannel) ? "" : o;
                else payStatus = o.equals(payStatus) ? "" : o;
                buildPayRows();
            });
            r.addView(c);
        }
        sv.addView(r);
        LinearLayout.LayoutParams sp = lp(MATCH, WRAP); sp.topMargin = dp(this, 5);
        box.addView(sv, sp);
        return box;
    }

    private void closePanel() {
        if (panelView != null) { try { wm.removeView(panelView); } catch (Exception ignored) {} panelView = null; }
        previewBox = null; previewText = null; bodyScroll = null; shipPlaceField = null; editorBar = null;
        noteEditFor = null; priceEditFor = null;
        posUi.removeCallbacks(posPoll);
        panelOpen = false;
    }

    /* ================= body ================= */
    private void rebuildBody() {
        bodyBox.removeAllViews();
        shipPlaceField = null; // จะถูกตั้งใหม่ถ้าบล็อกค่าส่งถูกสร้าง
        refreshEditorBar();

        if (filter.equals(F_POS)) { buildPosStatus(); return; }

        boolean showPhrase = filter.equals("ทั้งหมด") || filter.equals("คำพูด");
        if (showPhrase) {
            LinearLayout h = row(this);
            h.addView(Masonry.header(this, "คำที่พิมพ์บ่อย", LAVENDER), lpw(1));
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
            int color = catAccent(cat.name);

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
                final List<Integer> idxs = new ArrayList<>();
                String q = search.trim().toLowerCase();
                for (int i = 0; i < items.size(); i++)
                    if (q.isEmpty() || items.get(i).name.toLowerCase().contains(q)) idxs.add(i);
                final int n = idxs.size();
                bodyBox.addView(Masonry.grid(this, n + 1, new Masonry.CardBuilder() {
                    @Override public View build(int i) {
                        if (i >= n) return addMenuTile(catIdx);
                        int real = idxs.get(i);
                        return menuCard(items.get(real), catIdx, real);
                    }
                    @Override public int weight(int i) {
                        return (i >= n) ? 58 : 62 + Math.min(items.get(idxs.get(i)).name.length(), 40);
                    }
                }), lp(MATCH, WRAP));
            }
        }
    }

    /* ================= สถานะออเดอร์บนเครื่อง POS ================= */

    /** ดึงสถานะจากคลาวด์ (พื้นหลัง) แล้ววาดใหม่ */
    private void fetchPosOrders() {
        if (!PosSender.ready(this) || posLoading) return;
        posLoading = true;
        new Thread(() -> {
            List<PosSender.OrderStatus> got = null;
            try { got = PosSender.fetchAll(BubbleService.this); } catch (Exception ignored) {}
            final List<PosSender.OrderStatus> res = got;
            posUi.post(() -> {
                posLoading = false;
                if (res != null) posOrders = res;
                if (panelOpen && filter.equals(F_POS)) rebuildBody();
            });
        }).start();
    }

    /** รายการออเดอร์ที่ส่งไป POS พร้อมสถานะสด */
    private void buildPosStatus() {
        LinearLayout h = row(this);
        h.addView(Masonry.header(this, "สถานะออเดอร์บนเครื่อง POS", MINT), lpw(1));
        TextView refresh = chip(this, posLoading ? "⟳ …" : "⟳", false);
        Fx.onTap(refresh, this::fetchPosOrders);
        h.addView(refresh);
        bodyBox.addView(h, lp(MATCH, WRAP));

        if (!PosSender.ready(this)) {
            TextView warn = text(this,
                    "ยังไม่ได้ตั้งลิงก์ POS — เปิดแอป Messes Sale แล้วใส่ลิงก์ฐานข้อมูลที่หัวข้อ \"ส่งออเดอร์เข้าเครื่อง POS\"",
                    12.5f, false, 0xFFFFE6B8);
            warn.setPadding(dp(this,2), dp(this,10), 0, 0);
            bodyBox.addView(warn);
            return;
        }
        // แถบแจ้งผลการส่งครั้งล่าสุด (แทน Toast ที่บางเครื่องไม่เด้ง)
        if (!posBanner.isEmpty() && System.currentTimeMillis() - posBannerAt < 120000L) {
            boolean good = posBanner.startsWith("✅");
            TextView bn = text(this, posBanner, 12.5f, true, good ? 0xFF9FF0C4 : 0xFFFFB4B4);
            bn.setBackground(glass(this, good ? 0x2622C55E : 0x26EF4444, 12,
                    good ? 0x5522C55E : 0x55EF4444));
            bn.setPadding(dp(this,12), dp(this,10), dp(this,12), dp(this,10));
            LinearLayout.LayoutParams bl = lp(MATCH, WRAP);
            bl.topMargin = dp(this,8); bl.bottomMargin = dp(this,6);
            bodyBox.addView(bn, bl);
        }

        List<PosSender.SendLog> logs = PosSender.logList(this);

        if (logs.isEmpty() && posOrders.isEmpty()) {
            TextView em = text(this, posLoading ? "กำลังโหลด…" : "ยังไม่เคยส่งออเดอร์เข้า POS", 13, false, WHITE_DIM);
            em.setPadding(dp(this,2), dp(this,12), 0, 0);
            bodyBox.addView(em);
            return;
        }

        int waiting = 0, fail = 0;
        for (PosSender.OrderStatus s : posOrders) if (s.status.equals("ใหม่")) waiting++;
        for (PosSender.SendLog l : logs) if (!l.ok) fail++;
        TextView sum = text(this, "ส่งไปแล้ว " + logs.size() + " ครั้ง  •  รอ POS กดรับ " + waiting
                + (fail > 0 ? "  •  ส่งไม่สำเร็จ " + fail : ""), 11.5f, false, WHITE_DIM);
        sum.setPadding(dp(this,2), dp(this,4), 0, dp(this,8));
        bodyBox.addView(sum);

        java.util.HashSet<String> shown = new java.util.HashSet<>();
        for (PosSender.SendLog l : logs) {
            PosSender.OrderStatus cloud = null;
            if (!l.id.isEmpty()) {
                for (PosSender.OrderStatus s : posOrders)
                    if (s.id.equals(l.id)) { cloud = s; break; }
            }
            if (cloud != null) shown.add(cloud.id);

            int c = !l.ok ? 0xFFEF4444 : (cloud != null ? cloud.color() : 0xFF94A3B8);
            String stateTxt;
            if (!l.ok) stateTxt = "ส่งไม่สำเร็จ" + (l.err.isEmpty() ? "" : " — " + l.err);
            else if (cloud != null) stateTxt = "ส่งสำเร็จ · " + cloud.label();
            else stateTxt = "ส่งสำเร็จ · ไม่พบบนคลาวด์แล้ว (POS อาจล้างออกไป)";

            bodyBox.addView(posCard(c, l.no, l.place, stateTxt,
                    "ส่งเมื่อ " + l.clock() + " น. · " + l.ago()
                            + (l.total > 0 ? "  •  " + l.total + " บาท" : "")),
                    posCardLp());
        }

        boolean headed = false;
        for (PosSender.OrderStatus s : posOrders) {
            if (shown.contains(s.id)) continue;
            if (!headed) {
                TextView hh = text(this, "ออเดอร์อื่นบนคลาวด์", 11.5f, true, 0xFF8FA0BD);
                hh.setPadding(dp(this,2), dp(this,8), 0, dp(this,6));
                bodyBox.addView(hh);
                headed = true;
            }
            bodyBox.addView(posCard(s.color(), s.no, s.place, s.label(),
                    "เข้ามา " + s.minAgo() + " นาทีที่แล้ว"
                            + (s.total > 0 ? "  •  " + s.total + " บาท" : "")),
                    posCardLp());
        }

        LinearLayout foot = row(this);
        TextView tip = text(this, "🟡 รอ POS กดรับ   🟠 กำลังทำ   🟢 เสร็จแล้ว  (อัปเดตทุก 6 วิ)",
                10.5f, false, 0xFF8FA0BD);
        foot.addView(tip, lpw(1));
        if (!logs.isEmpty()) {
            TextView clr = chip(this, "ล้างประวัติ", false);
            Fx.onTap(clr, () -> { PosSender.logClear(this); posBanner = ""; rebuildBody(); });
            foot.addView(clr);
        }
        LinearLayout.LayoutParams fl = lp(MATCH, WRAP);
        fl.topMargin = dp(this,6);
        bodyBox.addView(foot, fl);
    }

    private LinearLayout.LayoutParams posCardLp() {
        LinearLayout.LayoutParams cl = lp(MATCH, WRAP);
        cl.bottomMargin = dp(this, 8);
        return cl;
    }

    /** การ์ด 1 ใบในหน้าสถานะ POS */
    private View posCard(int color, String no, String place, String state, String meta) {
        LinearLayout card = row(this);
        card.setBackground(surfaceTint(this, 16, LINE));
        card.setPadding(dp(this,12), dp(this,11), dp(this,12), dp(this,11));

        TextView dot = text(this, "●", 16, true, color);
        dot.setPadding(0, 0, dp(this,10), 0);
        card.addView(dot);

        LinearLayout info = col(this);
        String head = (no == null || no.isEmpty() ? "ออเดอร์" : "ออเดอร์ที่ " + no)
                + (place == null || place.isEmpty() ? "" : "  •  " + place);
        info.addView(text(this, head, 13.5f, true, WHITE));

        TextView st2 = text(this, state, 12, true, color);
        st2.setPadding(0, dp(this,3), 0, 0);
        info.addView(st2);

        TextView mt = text(this, meta, 11, false, WHITE_DIM);
        mt.setPadding(0, dp(this,2), 0, 0);
        info.addView(mt);

        card.addView(info, lpw(1));
        return card;
    }

    /* ---- menu card ---- */
    private View menuCard(MenuData.Item it, int catIdx, int itemIdx) {
        final int accent = catAccent(cats.get(catIdx).name);
        LinearLayout card = col(this);
        boolean on = it.qty > 0;
        card.setBackground(surface(this, 18, on));
        card.setElevation(dp(this, on ? 6 : 2));
        card.setPadding(dp(this,12), dp(this,11), dp(this,12), dp(this,11));

        // ---- แถวบน: จุดสีหมวด + ชื่อ + (โน้ต) + จำนวน ----
        LinearLayout top = row(this);
        top.setGravity(Gravity.TOP);
        android.view.View d = dot(this, accent, 7);
        ((LinearLayout.LayoutParams) d.getLayoutParams()).topMargin = dp(this, 6);
        ((LinearLayout.LayoutParams) d.getLayoutParams()).rightMargin = dp(this, 7);
        top.addView(d);
        top.addView(text(this, it.name, 13, true, WHITE), lpw(1));
        if (on) {
            TextView noteBtn = text(this, "📝", 11, false, WHITE);
            noteBtn.setGravity(Gravity.CENTER);
            noteBtn.setBackground(glass(this, it.note.isEmpty() ? SURFACE : LAVENDER, 9, it.note.isEmpty() ? LINE : 0));
            noteBtn.setPadding(dp(this,6), dp(this,2), dp(this,6), dp(this,2));
            LinearLayout.LayoutParams nbp = lp(WRAP, WRAP); nbp.leftMargin = dp(this,6);
            Fx.onTap(noteBtn, () -> {
                noteEditFor = (noteEditFor == it) ? null : it;
                priceEditFor = null;
                rebuildBody();
            });
            top.addView(noteBtn, nbp);
            TextView badge = text(this, "×" + it.qty, 11.5f, true, WHITE);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(glass(this, ACCENT, 20, 0));
            badge.setPadding(dp(this,8), dp(this,2), dp(this,8), dp(this,2));
            LinearLayout.LayoutParams bgp = lp(WRAP, WRAP); bgp.leftMargin = dp(this,6);
            top.addView(badge, bgp);
        }
        card.addView(top, lp(MATCH, WRAP));

        // โน้ตที่ใส่ไว้
        if (!it.note.isEmpty() && noteEditFor != it) {
            TextView nt = text(this, "📝 " + it.note, 10.5f, false, LAVENDER);
            nt.setPadding(dp(this, 14), dp(this,3), 0, 0);
            card.addView(nt);
        }

        // ---- แถวล่าง: ราคา + ปุ่ม − / + ----
        LinearLayout bottom = row(this);
        String priceTxt = it.custom
                ? (it.price > 0 ? it.price + " บาท ✎" : "กำหนดเอง ✎")
                : (it.price + " บาท");
        TextView priceLbl = text(this, priceTxt, 13, true, on ? WHITE : accent);
        priceLbl.setPadding(dp(this, 14), 0, 0, 0);
        if (it.custom) Fx.onTap(priceLbl, () -> { priceEditFor = it; noteEditFor = null; rebuildBody(); });
        bottom.addView(priceLbl, lpw(1));

        if (on) {
            TextView minus = roundBtn("−", SURFACE, WHITE);
            Fx.onTap(minus, () -> { it.qty--; rebuildBody(); refreshTotal(); });
            bottom.addView(minus);
            TextView plus2 = roundBtn("+", ACCENT, WHITE);
            ((LinearLayout.LayoutParams) plus2.getLayoutParams()).leftMargin = dp(this, 6);
            Fx.onTap(plus2, () -> addOne(it));
            bottom.addView(plus2);
        } else {
            TextView plus = roundBtn("+", SURFACE_2, WHITE);
            bottom.addView(plus);
        }

        LinearLayout.LayoutParams bp = lp(MATCH, WRAP); bp.topMargin = dp(this, 9);
        card.addView(bottom, bp);

        Fx.onTap(card, () -> addOne(it));
        Fx.onHold(card, () -> openMenuEditor(catIdx, itemIdx));
        return card;
    }

    /** ปุ่มกลมเล็ก 30dp สำหรับ − / + */
    private TextView roundBtn(String glyph, int bg, int fg) {
        TextView t = text(this, glyph, 16, true, fg);
        t.setGravity(Gravity.CENTER);
        t.setBackground(glass(this, bg, 15, bg == SURFACE ? LINE : 0));
        int sz = dp(this, 30);
        t.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        t.setIncludeFontPadding(false);
        return t;
    }

    /** แถบใส่ราคา/โน้ต — วางเหนือรายการเมนู จะไม่โดนคีย์บอร์ดบังและไม่ทำให้รายการหาย */
    private void refreshEditorBar() {
        if (editorBar == null) return;
        editorBar.removeAllViews();
        final MenuData.Item it = priceEditFor != null ? priceEditFor : noteEditFor;
        if (it == null) { editorBar.setVisibility(View.GONE); setBodyHeight(BODY_TALL); return; }
        final boolean priceMode = priceEditFor != null;

        editorBar.setVisibility(View.VISIBLE);
        editorBar.setBackground(surfaceTint(this, 16, priceMode ? 0x88FFB84D : 0x88A78BFA));
        editorBar.setPadding(dp(this,12), dp(this,9), dp(this,12), dp(this,10));

        TextView title = text(this, (priceMode ? "💰 ใส่ราคา: " : "📝 โน้ต: ") + it.name, 12, true, WHITE);
        editorBar.addView(title, lp(MATCH, WRAP));

        LinearLayout r = row(this);
        final EditText e = input(this, priceMode ? "ราคา (บาท)" : "เช่น ไม่ใส่ผัก / เผาสุกมาก");
        if (priceMode) {
            e.setInputType(InputType.TYPE_CLASS_NUMBER);
            if (it.price > 0) e.setText(String.valueOf(it.price));
        } else {
            e.setText(it.note);
        }
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c,int a,int b,int d) {}
            @Override public void onTextChanged(CharSequence c,int a,int b,int d) {
                if (priceMode) {
                    try { it.price = Integer.parseInt(c.toString().trim()); } catch (Exception ex) { it.price = 0; }
                    if (it.qty == 0 && it.price > 0) it.qty = 1;
                    refreshTotal();
                } else {
                    it.note = c.toString();
                }
                refreshPreview();
            }
            @Override public void afterTextChanged(Editable ed) {}
        });
        watchKeyboard(e);
        r.addView(e, lpw(1));

        TextView ok = button(this, "✓ เสร็จ", primary(this, 14), 13);
        ok.setPadding(dp(this,14), dp(this,10), dp(this,14), dp(this,10));
        LinearLayout.LayoutParams okp = lp(WRAP, WRAP); okp.leftMargin = dp(this,6);
        Fx.onTap(ok, () -> {
            if (priceMode && it.price > 0 && it.qty == 0) it.qty = 1;
            if (!priceMode) it.note = it.note.trim();
            priceEditFor = null; noteEditFor = null;
            hideKeyboard(e);
            rebuildBody(); refreshTotal();
        });
        r.addView(ok, okp);
        LinearLayout.LayoutParams rl = lp(MATCH, WRAP); rl.topMargin = dp(this, 6);
        editorBar.addView(r, rl);

        posUi.post(() -> {
            e.requestFocus();
            e.setSelection(e.getText().length());
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(e, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void hideKeyboard(View v) {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Exception ignored) {}
    }

    /** กด + / แตะการ์ด: เมนูกำหนดเองที่ยังไม่มีราคา → เปิดช่องใส่ราคาก่อน */
    private void addOne(MenuData.Item it) {
        if (it.custom && it.price <= 0) {
            priceEditFor = it; noteEditFor = null;
            rebuildBody();
            return;
        }
        it.qty++;
        rebuildBody();
        refreshTotal();
    }

    /** ไทล์ ＋ เพิ่มเมนู ท้ายแต่ละหมวด */
    private View addMenuTile(int catIdx) {
        LinearLayout t = col(this);
        t.setBackground(glass(this, 0x00000000, 18, 0x33FFFFFF));
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

    /** ตั้งค่าจุดส่ง แล้วซิงให้ทั้ง 2 ช่อง (จุดส่ง ↔ ส่งที่ไหน) ตรงกันเสมอ */
    private void setPlace(String p, EditText from) {
        if (syncingPlace) return;
        syncingPlace = true;
        place = p == null ? "" : p;
        try {
            if (placeInput != null && placeInput != from
                    && !placeInput.getText().toString().equals(place)) {
                placeInput.setText(place);
                placeInput.setSelection(place.length());
            }
            if (shipPlaceField != null && shipPlaceField != from
                    && !shipPlaceField.getText().toString().equals(place)) {
                shipPlaceField.setText(place);
                shipPlaceField.setSelection(place.length());
            }
        } catch (Exception ignored) {}
        syncingPlace = false;
        refreshTotal();
    }

    /** เติมรายการแนะนำใต้ช่อง "ส่งที่ไหน" — ไม่ rebuild ทั้งแผง คีย์บอร์ดจึงไม่หลุด */
    private void fillSuggest(LinearLayout box, EditText field, String typed) {
        box.removeAllViews();
        String q = typed == null ? "" : typed.trim().toLowerCase();
        if (q.isEmpty()) { box.setVisibility(View.GONE); return; }

        int shown = 0;
        for (String p : Store.loadPlaces(this)) {
            String lp2 = p.toLowerCase();
            if (!lp2.contains(q) || lp2.equals(q)) continue;
            final String pick = p;
            TextView row = text(this, "📍  " + p, 12.5f, false, WHITE);
            row.setPadding(dp(this,12), dp(this,10), dp(this,12), dp(this,10));
            Fx.onTap(row, () -> {
                field.setText(pick);
                field.setSelection(pick.length());
                setPlace(pick, field);
                box.setVisibility(View.GONE);
            });
            box.addView(row, lp(MATCH, WRAP));
            if (++shown >= 5) break;
        }
        box.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
    }

    /* ---- delivery block ---- */
    private View shipBlock(MenuData.Cat cat) {
        LinearLayout box = col(this);
        box.setBackground(surfaceTint(this, 18, 0x5534D399));
        box.setPadding(dp(this,12), dp(this,12), dp(this,12), dp(this,12));

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
        LinearLayout.LayoutParams fp = lp(MATCH, WRAP);
        fp.topMargin = dp(this,7); fp.bottomMargin = dp(this,11);
        box.addView(fsv, fp);

        // ช่องส่งที่ไหน + ปุ่ม ✕ ล้าง
        LinearLayout pRow = row(this);
        final EditText placeField = input(this, "ส่งที่ไหน เช่น Tara");
        shipPlaceField = placeField;
        placeField.setText(place);
        watchKeyboard(placeField);
        pRow.addView(placeField, lpw(1));

        // 💾 ปุ่มจำที่อยู่ — อยู่ติดปุ่ม ✕ มองเห็นเสมอ (เดิมชิป "＋ จำไว้" ถูกดันหลุดขอบเวลามีหอเยอะ)
        TextView pSave = text(this, "💾 จำ", 13, true, 0xFF04342C);
        pSave.setGravity(Gravity.CENTER);
        pSave.setBackground(glass(this, 0xFF9FE1CB, 11, 0));
        pSave.setPadding(dp(this,13), dp(this,11), dp(this,13), dp(this,11));
        LinearLayout.LayoutParams psp = lp(WRAP, WRAP); psp.leftMargin = dp(this,6);
        pSave.setLayoutParams(psp);
        Fx.onTap(pSave, () -> {
            String p = shipPlaceField.getText().toString().trim();
            if (p.isEmpty()) { Toast.makeText(this, "พิมพ์ชื่อจุดส่งก่อน", Toast.LENGTH_SHORT).show(); return; }
            Store.rememberPlace(this, p); setPlace(p, null); rebuildBody();
            Toast.makeText(this, "จำ " + p + " แล้ว", Toast.LENGTH_SHORT).show();
        });
        pRow.addView(pSave);

        TextView pClear = text(this, "✕", 14, true, WHITE);
        pClear.setGravity(Gravity.CENTER);
        pClear.setBackground(glass(this, 0x33000000, 11, 0x669FE1CB));
        pClear.setPadding(dp(this,13), dp(this,11), dp(this,13), dp(this,11));
        LinearLayout.LayoutParams pcp = lp(WRAP, WRAP); pcp.leftMargin = dp(this,6);
        pClear.setLayoutParams(pcp);
        pRow.addView(pClear);
        box.addView(pRow, lp(MATCH, WRAP));

        // ดรอปดาวน์แนะนำที่อยู่ใกล้เคียงกับที่พิมพ์
        final LinearLayout suggest = col(this);
        suggest.setBackground(glass(this, 0x4D000000, 11, 0x669FE1CB));
        suggest.setVisibility(View.GONE);
        LinearLayout.LayoutParams sgp = lp(MATCH, WRAP); sgp.topMargin = dp(this, 5);
        box.addView(suggest, sgp);

        placeField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int a,int b,int c) {}
            @Override public void onTextChanged(CharSequence s,int a,int b,int c) {
                setPlace(s.toString(), placeField);
                fillSuggest(suggest, placeField, place);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        Fx.onTap(pClear, () -> {
            placeField.setText("");
            setPlace("", placeField);
            suggest.setVisibility(View.GONE);
        });

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
            Fx.onTap(c, () -> { placeField.setText(p); setPlace(p, placeField); rebuildBody(); });
            Fx.onHold(c, () -> { Store.forgetPlace(this, p); rebuildBody();
                Toast.makeText(this, "ลบ " + p + " แล้ว", Toast.LENGTH_SHORT).show(); });
            placeChipRow.addView(c);
        }
        psv.addView(placeChipRow);
        LinearLayout.LayoutParams pl = lp(MATCH, WRAP); pl.topMargin = dp(this, 10);
        box.addView(psv, pl);

        return box;
    }

    /* ---- phrase card ---- */
    private View phraseCard(int idx) {
        Store.Card c = cards.get(idx);
        LinearLayout card = col(this);
        card.setBackground(surfaceTint(this, 18, 0x55A78BFA));
        card.setElevation(dp(this, 2));
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

        // แถบท้ายการ์ด: แตะ = คัดลอก, ปุ่มปากกามุมขวา = แก้ไข
        LinearLayout foot = row(this);
        TextView hint = text(this, c.images.isEmpty() ? "📋 แตะเพื่อคัดลอก" : "🚀 แตะเพื่อส่งพร้อมรูป",
                10.5f, true, 0xF2FFFFFF);
        hint.setGravity(Gravity.CENTER);
        hint.setBackground(glass(this, 0x33000000, 9, 0x40FFFFFF));
        hint.setPadding(0, dp(this,6), 0, dp(this,6));
        foot.addView(hint, lpw(1));

        TextView pen = text(this, "✏️", 12, true, WHITE);
        pen.setGravity(Gravity.CENTER);
        pen.setBackground(glass(this, 0x40000000, 9, 0x59FFFFFF));
        pen.setPadding(dp(this,10), dp(this,6), dp(this,10), dp(this,6));
        LinearLayout.LayoutParams pp = lp(WRAP, WRAP); pp.leftMargin = dp(this,6);
        pen.setLayoutParams(pp);
        Fx.onTap(pen, () -> openCardEditor(idx));
        foot.addView(pen);

        LinearLayout.LayoutParams hp = lp(MATCH, WRAP); hp.topMargin = dp(this, 9);
        card.addView(foot, hp);

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
        t.setBackground(glass(this, 0x00000000, 18, 0x33FFFFFF));
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
        segCustomer.setBackground(mode == MsgBuilder.MODE_CUSTOMER ? glass(this, WHITE, 13, 0) : null);
        segCustomer.setTextColor(mode == MsgBuilder.MODE_CUSTOMER ? INK : WHITE);
        segStaff.setBackground(mode == MsgBuilder.MODE_STAFF ? glass(this, WHITE, 13, 0) : null);
        segStaff.setTextColor(mode == MsgBuilder.MODE_STAFF ? INK : WHITE);
        int vis = mode == MsgBuilder.MODE_STAFF ? View.VISIBLE : View.GONE;
        staffRow.setVisibility(vis);
        if (staffPay != null) staffPay.setVisibility(vis);
    }

    private void refreshFilters() {
        for (TextView c : filterChips) {
            boolean on = c.getText().toString().equals(filter);
            c.setBackground(on ? glass(this, ACCENT, 20, 0) : glass(this, SURFACE_2, 20, LINE));
            c.setTextColor(on ? WHITE : WHITE_DIM);
        }
    }

    private void refreshTotal() {
        totalText.setText("รวม " + MsgBuilder.total(cats, shipFee) + " บาท");
        refreshPreview();
    }

    /* ================= helpers ================= */
    private String payLine() {
        if (mode == MsgBuilder.MODE_STAFF) {
            StringBuilder s = new StringBuilder();
            if (!payChannel.isEmpty()) s.append("ช่องทางชำระ : ").append(payChannel);
            if (!payStatus.isEmpty()) {
                if (s.length() > 0) s.append("\n");
                s.append("สถานะ : ").append(payStatus);
            }
            return s.toString();
        }
        return Store.prefs(this).getString("payline", "ชำระเงินคนละครึ่งหรือโอนธรรมดาครับ");
    }

    /** ส่งออเดอร์ปัจจุบันขึ้นคลาวด์ให้เครื่อง POS เด้งเป็นฟอง */
    /** เช็คทุก 10 วิ ว่าออเดอร์ที่ส่งจากเครื่องนี้ถูก POS กดเสร็จหรือยัง */
    private void checkDoneOrders() {
        if (!PosSender.ready(this)) return;
        final List<PosSender.SendLog> logs = PosSender.logList(this);
        final java.util.Set<String> seen = PosSender.doneSeen(this);
        boolean pending = false;
        long cutoff = System.currentTimeMillis() - 12L * 3600000L;   // ดูเฉพาะที่ส่งภายใน 12 ชม.
        for (PosSender.SendLog l : logs)
            if (l.ok && !l.id.isEmpty() && l.at > cutoff && !seen.contains(l.id)) { pending = true; break; }
        if (!pending) return;

        new Thread(() -> {
            List<PosSender.OrderStatus> got;
            try { got = PosSender.fetchAll(BubbleService.this); } catch (Exception e) { return; }
            final List<PosSender.OrderStatus> res = got;
            posUi.post(() -> {
                posOrders = res;
                for (PosSender.SendLog l : logs) {
                    if (!l.ok || l.id.isEmpty() || seen.contains(l.id)) continue;
                    for (PosSender.OrderStatus o : res) {
                        if (!o.id.equals(l.id)) continue;
                        if (o.status.equals("เสร็จ")) {
                            PosSender.markDoneSeen(BubbleService.this, l.id);
                            notifyDone(l);
                        }
                    }
                }
                if (panelOpen && filter.equals(F_POS)) rebuildBody();
            });
        }).start();
    }

    /** POS กดเสร็จแล้ว → เสียง + สั่น + แจ้งเตือน + ฟองเด้ง */
    private void notifyDone(PosSender.SendLog l) {
        String title = "✅ ออเดอร์เสร็จแล้ว" + (l.no.isEmpty() ? "" : " #" + l.no);
        String body = (l.place.isEmpty() ? "" : l.place + "  •  ") + (l.total > 0 ? l.total + " บาท" : "")
                + "  •  ส่งเมื่อ " + l.clock() + " น.";

        // เสียง (ริงโทนแจ้งเตือน + ปี๊บสั้น เผื่อเครื่องไม่มีริงโทน)
        try {
            android.net.Uri u = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION);
            android.media.Ringtone r = android.media.RingtoneManager.getRingtone(getApplicationContext(), u);
            if (r != null) r.play();
        } catch (Exception ignored) {}
        try {
            android.media.ToneGenerator tg = new android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 90);
            tg.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 220);
            posUi.postDelayed(tg::release, 800);
        } catch (Exception ignored) {}
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator())
                v.vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 120, 80, 200}, -1));
        } catch (Exception ignored) {}

        // แจ้งเตือนระบบ
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CH_DONE, "POS ทำออเดอร์เสร็จ", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("แจ้งเมื่อเครื่อง POS กดว่าออเดอร์เสร็จแล้ว");
            nm.createNotificationChannel(ch);
            PendingIntent pi = PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, CH_DONE)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify((int) (System.currentTimeMillis() % 100000) + 10, n);
        } catch (Exception ignored) {}

        // ฟองเด้ง + แถบในแผง
        if (bubbleView != null) Fx.bounce(bubbleView);
        posBanner = title + "  " + body;
        posBannerAt = System.currentTimeMillis();
        try { Toast.makeText(this, title, Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
    }

    private void sendToPos() {
        if (!PosSender.ready(this)) {
            Toast.makeText(this, "ยังไม่ได้ตั้งค่าลิงก์ POS — เปิดแอป Messes Sale แล้วใส่ลิงก์ฐานข้อมูลก่อน",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (MsgBuilder.total(cats, shipFee) <= 0) {
            Toast.makeText(this, "ยังไม่ได้เลือกเมนู", Toast.LENGTH_SHORT).show();
            return;
        }
        final String no = orderNoInput.getText().toString();
        final String dest = place.isEmpty() ? placeInput.getText().toString() : place;
        final String msg = MsgBuilder.build(cats, MsgBuilder.MODE_STAFF,
                no, dest, payLine(), place, shipFee);
        final int total = MsgBuilder.total(cats, shipFee);
        final String pay = payLine();

        Toast.makeText(this, "กำลังส่งเข้าเครื่อง POS…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String result, key = "", err = "";
            boolean ok = false;
            try {
                key = PosSender.send(BubbleService.this, no, dest, msg, total, pay);
                result = "✅ ส่งเข้าเครื่อง POS แล้ว";
                ok = true;
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
                result = "❌ ส่งไม่สำเร็จ: " + err;
            }
            PosSender.logAdd(BubbleService.this, key, no, dest, total, ok, err);
            final String r = result;
            final boolean sent = ok;
            posUi.post(() -> {
                posBanner = r;
                posBannerAt = System.currentTimeMillis();
                try { Toast.makeText(BubbleService.this, r, Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
                if (panelOpen) {
                    // เด้งไปหน้าสถานะเสมอ — สำเร็จก็ดูว่า POS กดรับยัง ไม่สำเร็จก็เห็นสาเหตุ
                    filter = F_POS;
                    refreshFilters();
                    rebuildBody();
                    if (sent) {
                        fetchPosOrders();
                        posUi.removeCallbacks(posPoll);
                        posUi.postDelayed(posPoll, 6000);
                    }
                }
            });
        }).start();
    }

    private void copy(String txt, String msg) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("messes", txt));
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override public void onDestroy() {
        super.onDestroy();
        posUi.removeCallbacks(bgPoll);
        closePanel();
        if (bubbleView != null) { try { wm.removeView(bubbleView); } catch (Exception ignored) {} }
    }
}
