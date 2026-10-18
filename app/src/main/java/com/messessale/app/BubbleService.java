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
    private View bubbleView;              // หน้าต่างฟอง (โปร่ง) = วงกลม + ป้ายแดง + จุดคลาวด์
    private LinearLayout bubbleDisc;      // วงกลมขาวขอบส้ม
    private android.widget.ImageView bubbleIcon;
    private TextView bubbleEmoji;         // ใช้แทนไอคอนตอนสถานะพิเศษ (⏰ / ✅)
    private TextView bubbleCount, bubbleBadge, bubbleDot;
    private int bubbleState = 0;
    private final java.util.Set<String> doneUnseen = new java.util.HashSet<>();   // POS กดเสร็จแล้ว แต่เจ้าของยังไม่เปิดดู          // 0 ปกติ / 1 รอ POS รับ / 2 เสร็จ / 3 เกิน 50 นาที / 4 ไม่มีเน็ต
    private static final int BUB_DP = 62, BUB_PAD = 12, BUB_WIN = BUB_DP + BUB_PAD * 2;
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
    private EditText orderNoInput, placeInput, searchInput, shipPlaceField, orderNoteInput;
    private String search = "";
    private boolean syncingPlace = false;
    private MenuData.Item noteEditFor = null, priceEditFor = null;   // การ์ดที่กำลังเปิดช่องโน้ต/ราคา
    private LinearLayout editorBar;
    private boolean bubbleHidden = false;   // ซ่อนฟองชั่วคราว (แอปยังทำงาน)
    private boolean bubbleOverdue = false;  // มีออเดอร์ค้างเกิน 50 นาที → ฟองแดงกระพริบ
    private static final int OVERDUE_MIN = 50;
    private long downAt = 0;                // เวลากดฟอง (ไว้จับกดค้าง)
    private static final int MODE_CARD = 2; // โหมดการ์ดคำพูด (คัดลอกอย่างเดียว)
    private static final int MODE_ORDERS = 3; // โหมดจัดคิวส่งออเดอร์ (ไรเดอร์)
    private static final String F_ORDERS = "คิวส่ง";
    private TextView segOrders;
    private final List<String> deliverSeq = new ArrayList<>();   // ลำดับการส่งที่กดเอง (id ออเดอร์) — ว่าง = ใช้ลำดับโซนอัตโนมัติ
    private String zoneEditFor = null;                            // การ์ดที่กำลังตั้งโซน
    private List<PosSender.OrderStatus> posArchive = new ArrayList<>();   // ประวัติที่ POS เก็บแล้ว
    private boolean showArchive = false;
    private TextView segCard;
    private String editingId = null;        // กำลังแก้ไขออเดอร์ที่ส่งไปแล้ว (id บนคลาวด์)
    private String editingPrevText = "";    // ข้อความก่อนแก้ (ให้ POS ไฮไลต์ส่วนที่เปลี่ยน)
    private int editingEdits = 0;
    private String orderNote = "";          // โน้ตภาพรวมของออเดอร์ (แยกจากโน้ตต่อเมนู)
    private String expandedLogId = null;    // การ์ดประวัติที่กางรายละเอียดอยู่
    private final Runnable harvestTick = new Runnable() {
        @Override public void run() {
            int every = PosSender.harvestEvery(BubbleService.this);
            if (every > 0 && System.currentTimeMillis() - PosSender.harvestLast(BubbleService.this) >= every * 60000L)
                sendHarvest(true);
            posUi.postDelayed(this, 60000);
        }
    };
    private String editingLabel = "";
    private TextView posBtn;                                   // แถบใส่ราคา/โน้ต อยู่เหนือรายการ (ไม่โดนคีย์บอร์ดบัง)
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
            refreshBubble();
            posUi.postDelayed(this, BG_POLL_MS);
        }
    };
    private boolean posLoading = false;
    private final android.os.Handler posUi = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable posPoll = new Runnable() {
        @Override public void run() {
            if (!panelOpen || !(filter.equals(F_POS) || filter.equals(F_ORDERS))) return;
            fetchPosOrders();
            posUi.postDelayed(this, 6000);   // รีเฟรชทุก 6 วิ
        }
    };

    private String payChannel = "";
    private String payStatus = "";
    private static final String[] PAY_CHANNELS = {"เงินสด", "โอนปกติ", "คนละครึ่ง"};
    private static final String[] PAY_STATUS = {"ชำระแล้ว", "ชำระหน้าร้าน", "สแกนหน้าลูกค้า"};

    private static final String CH = "bubble_ch";
    private static final int C_SHRIMP = RED;
    private static final int C_RICE   = AMBER;
    private static final int C_ADDON  = AMBER;
    private static final int C_SHIP   = MINT_SOFT;
    private static final int C_PHRASE = SKY;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        cats = Store.loadMenu(this);
        cards = Store.loadCards(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForegroundNotif();
        addBubble();
        posUi.postDelayed(bgPoll, 4000);
        posUi.postDelayed(harvestTick, 30000);
        posUi.postDelayed(updateCheck, 20000);
    }

    /* ================= อัปเดตแอปอัตโนมัติจาก GitHub ================= */
    private int updateNotified = 0;
    private final Runnable updateCheck = new Runnable() {
        @Override public void run() {
            Updater.check(BubbleService.this, (ok, msg) -> {
                if (ok && Updater.latestCode != updateNotified) {
                    updateNotified = Updater.latestCode;
                    Updater.notify(BubbleService.this, BubbleService.class);
                    try { Toast.makeText(BubbleService.this, "🆕 " + msg + " — แตะปุ่ม 🆕 บนแผงเพื่ออัปเดต", Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
                    if (panelOpen) { closePanel(); openPanel(); }
                }
            });
            posUi.postDelayed(this, 6 * 3600000L);   // ตรวจทุก 6 ชม.
        }
    };

    private void startUpdate() {
        Toast.makeText(this, "⬇️ กำลังโหลดเวอร์ชันใหม่…", Toast.LENGTH_SHORT).show();
        if (panelOpen) closePanel();
        Runnable go = () -> Updater.install(this, (ok, msg) -> Toast.makeText(this, (ok ? "✅ " : "⚠️ ") + msg, Toast.LENGTH_LONG).show());
        if (Updater.latestApk.isEmpty()) Updater.check(this, (ok, msg) -> { if (ok) go.run(); else Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); });
        else go.run();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        bodyMaxDp = BODY_TALL;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        if (bubbleView != null && bubbleParams != null) {
            int win = dp(this, BUB_WIN);
            bubbleParams.x = Math.max(0, Math.min(bubbleParams.x, dm.widthPixels - win));
            bubbleParams.y = Math.max(0, Math.min(bubbleParams.y, dm.heightPixels - win));
            try { wm.updateViewLayout(bubbleView, bubbleParams); } catch (Exception ignored) {}
        }
        if (panelOpen) { closePanel(); openPanel(); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent == null ? null : intent.getStringExtra("act");
        if ("update".equals(act)) { startUpdate(); return START_STICKY; }
        if ("toggle".equals(act)) { setBubbleHidden(!bubbleHidden); return START_STICKY; }
        if ("quit".equals(act)) { stopSelf(); return START_NOT_STICKY; }
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
        startForeground(1, buildNotif());
    }

    /** แจ้งเตือนถาวร: มีปุ่ม ซ่อน/แสดงฟอง และ ปิดแอป */
    private Notification buildNotif() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggle = PendingIntent.getService(this, 1,
                new Intent(this, BubbleService.class).putExtra("act", "toggle"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent quit = PendingIntent.getService(this, 2,
                new Intent(this, BubbleService.class).putExtra("act", "quit"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CH)
                .setContentTitle(bubbleHidden ? "Messes Sale — ซ่อนฟองอยู่" : "Messes Sale พร้อมใช้งาน")
                .setContentText(bubbleHidden ? "กด \"แสดงฟอง\" เพื่อเรียกกลับมา" : "แตะฟองเพื่อเปิด • กดค้างที่ฟองเพื่อซ่อน")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, bubbleHidden ? "🦐 แสดงฟอง" : "👁 ซ่อนฟอง", toggle).build())
                .addAction(new Notification.Action.Builder(null, "✕ ปิดแอป", quit).build())
                .build();
    }

    private void setBubbleHidden(boolean hide) {
        bubbleHidden = hide;
        if (hide && panelOpen) closePanel();
        if (bubbleView != null) bubbleView.setVisibility(hide ? View.GONE : View.VISIBLE);
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(1, buildNotif());
        } catch (Exception ignored) {}
        try { Toast.makeText(this, hide ? "ซ่อนฟองแล้ว — เรียกกลับได้จากแถบแจ้งเตือน" : "แสดงฟองแล้ว", Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
    }

    private int wtype() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /* ================= Bubble ================= */
    private void addBubble() {
        android.widget.FrameLayout outer = new android.widget.FrameLayout(this);
        int s = dp(this, BUB_DP), win = dp(this, BUB_WIN);

        LinearLayout b = col(this);
        b.setGravity(Gravity.CENTER);
        b.setBackground(bubbleBg(this));
        b.setElevation(dp(this, 10));
        android.widget.FrameLayout.LayoutParams bl = new android.widget.FrameLayout.LayoutParams(s, s);
        bl.gravity = Gravity.CENTER;
        outer.addView(b, bl);
        bubbleDisc = b;

        bubbleIcon = new android.widget.ImageView(this);
        bubbleIcon.setImageResource(R.drawable.ic_chat);
        bubbleIcon.setColorFilter(ACCENT);
        b.addView(bubbleIcon, lp(dp(this, 26), dp(this, 26)));
        bubbleEmoji = text(this, "", 20, false, WHITE);
        bubbleEmoji.setGravity(Gravity.CENTER);
        bubbleEmoji.setVisibility(View.GONE);
        b.addView(bubbleEmoji);
        bubbleCount = text(this, "Messes", 9.5f, true, TXT);
        bubbleCount.setGravity(Gravity.CENTER);
        bubbleCount.setSingleLine(true);
        b.addView(bubbleCount);

        bubbleBadge = text(this, "", 11, true, WHITE);
        bubbleBadge.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(RED); bg.setCornerRadius(dp(this, 20)); bg.setStroke(dp(this, 2), WHITE);
        bubbleBadge.setBackground(bg);
        bubbleBadge.setMinWidth(dp(this, 22));
        bubbleBadge.setPadding(dp(this, 5), 0, dp(this, 5), 0);
        bubbleBadge.setElevation(dp(this, 14));
        bubbleBadge.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams bdl = new android.widget.FrameLayout.LayoutParams(WRAP, dp(this, 22));
        bdl.gravity = Gravity.TOP | Gravity.END;
        bdl.topMargin = dp(this, BUB_PAD - 6); bdl.rightMargin = dp(this, BUB_PAD - 6);
        outer.addView(bubbleBadge, bdl);

        bubbleDot = text(this, "", 1, false, 0);
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL); dg.setColor(0xFF94A3B8); dg.setStroke(dp(this, 2), WHITE);
        bubbleDot.setBackground(dg);
        bubbleDot.setElevation(dp(this, 14));
        int ds = dp(this, 13);
        android.widget.FrameLayout.LayoutParams ndl = new android.widget.FrameLayout.LayoutParams(ds, ds);
        ndl.gravity = Gravity.BOTTOM | Gravity.END;
        ndl.bottomMargin = dp(this, BUB_PAD + 1); ndl.rightMargin = dp(this, BUB_PAD + 3);
        outer.addView(bubbleDot, ndl);

        bubbleView = outer;
        outer.setContentDescription("ฟอง Messes Sale แตะเพื่อเปิดแผงตอบลูกค้า กดค้างเพื่อซ่อน");
        outer.setOnClickListener(v -> togglePanel());

        bubbleParams = new WindowManager.LayoutParams(win, win, wtype(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = dp(this, 4);
        bubbleParams.y = dp(this, 210);

        bubbleView.setOnTouchListener(new View.OnTouchListener() {
            int ix, iy; float tx, ty; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        ix = bubbleParams.x; iy = bubbleParams.y;
                        tx = e.getRawX(); ty = e.getRawY(); moved = false;
                        downAt = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int)(e.getRawX() - tx), dy = (int)(e.getRawY() - ty);
                        if (Math.abs(dx) > dp(BubbleService.this, 6) || Math.abs(dy) > dp(BubbleService.this, 6)) moved = true;
                        bubbleParams.x = ix + dx; bubbleParams.y = iy + dy;
                        wm.updateViewLayout(bubbleView, bubbleParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            if (System.currentTimeMillis() - downAt >= 600) { Fx.buzz(BubbleService.this); setBubbleHidden(true); }
                            else togglePanel();
                        }
                        return true;
                }
                return false;
            }
        });
        wm.addView(bubbleView, bubbleParams);
        refreshBubble();
    }

    /** อัปเดตหน้าตาฟองตามสถานะออเดอร์บนคลาวด์ */
    private void refreshBubble() {
        if (bubbleDisc == null) return;
        int active = 0, waiting = 0, doneNew = 0;
        boolean overdue = false;
        long cutoff = PosSender.now() - 12L * 3600000L;
        for (PosSender.OrderStatus o : posOrders) {
            if (o.createdAt <= cutoff) continue;
            if (o.status.equals("เสร็จ")) { if (doneUnseen.contains(o.id)) doneNew++; continue; }
            active++;
            if (o.status.equals("ใหม่")) waiting++;
            if (o.minAgo() >= OVERDUE_MIN) overdue = true;
        }
        long age = PosSender.lastOk == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - PosSender.lastOk;
        boolean net = PosSender.ready(this) && age >= 90000;
        int st = net ? 4 : overdue ? 3 : waiting > 0 ? 1 : doneNew > 0 ? 2 : 0;

        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(age < 20000 ? 0xFF22C55E : age < 90000 ? 0xFFFACC15 : RED);
        dg.setStroke(dp(this, 2), WHITE);
        bubbleDot.setBackground(dg);
        bubbleBadge.setText(String.valueOf(waiting));
        bubbleBadge.setVisibility(waiting > 0 && st != 3 && st != 4 ? View.VISIBLE : View.GONE);

        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        String label; int txt; boolean emoji = false; String em = "";
        switch (st) {
            case 1: g.setColors(new int[]{ACCENT, ACCENT_2}); g.setStroke(dp(this, 3), WHITE); label = "รอ POS รับ"; txt = WHITE; bubbleIcon.setColorFilter(WHITE); break;
            case 2: g.setColor(WHITE); g.setStroke(dp(this, 3), MINT); label = "เสร็จ " + doneNew; txt = MINT; emoji = true; em = "✅"; break;
            case 3: g.setColors(new int[]{RED, 0xFFF97316}); g.setStroke(dp(this, 3), WHITE); label = "เกิน " + OVERDUE_MIN + " น."; txt = WHITE; emoji = true; em = "⏰"; break;
            case 4: g.setColor(0xFFF1F2F5); g.setStroke(dp(this, 3), RED); label = "ไม่มีเน็ต"; txt = RED; bubbleIcon.setColorFilter(0xFF9AA1AE); break;
            default: g.setColor(WHITE); g.setStroke(dp(this, 3), ACCENT); label = active > 0 ? active + " ออเดอร์" : "Messes"; txt = TXT; bubbleIcon.setColorFilter(ACCENT); break;
        }
        bubbleDisc.setBackground(g);
        bubbleIcon.setVisibility(emoji ? View.GONE : View.VISIBLE);
        bubbleEmoji.setVisibility(emoji ? View.VISIBLE : View.GONE);
        bubbleEmoji.setText(em);
        bubbleCount.setText(label);
        bubbleCount.setTextColor(txt);

        if (st != bubbleState) {
            bubbleState = st;
            if (st == 3) {
                android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(bubbleDisc, View.ALPHA, 1f, 0.35f, 1f);
                a.setDuration(1800);
                a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                a.start();
                bubbleDisc.setTag(a);
            } else {
                if (bubbleDisc.getTag() instanceof android.animation.Animator) ((android.animation.Animator) bubbleDisc.getTag()).cancel();
                bubbleDisc.setTag(null);
                bubbleDisc.setAlpha(1f);
            }
            if (st == 1 || st == 2) Fx.bounce(bubbleDisc);
        }
    }

    /* ================= Panel ================= */
    private void togglePanel() { if (panelOpen) closePanel(); else openPanel(); }

    private GradientDrawable panelBgAlpha() {
        int a = Store.alpha(this);
        int argb = (int)(a * 2.55f) << 24 | 0xF4F5F7;
        GradientDrawable g = new GradientDrawable();
        g.setColor(argb);
        float r = dp(this, 28);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        g.setStroke(dp(this, 1), LINE);
        return g;
    }

    private void openPanel() {
        cards = Store.loadCards(this);
        doneUnseen.clear();
        refreshBubble();
        panelView = col(this);
        panelView.setBackground(panelBgAlpha());
        panelView.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 14));
        panelView.setElevation(dp(this, 16));

        // ---- ที่จับลาก (bottom-sheet) ----
        View grab = new View(this);
        grab.setBackground(glass(this, 0xFFD4D8E0, 3, 0));
        LinearLayout.LayoutParams gl = lp(dp(this, 40), dp(this, 5));
        gl.gravity = Gravity.CENTER_HORIZONTAL; gl.bottomMargin = dp(this, 8);
        panelView.addView(grab, gl);

        // ---- header ----
        LinearLayout head = row(this);
        // แถบจับ (grabber) ด้านบน
        LinearLayout titleCol = col(this);
        LinearLayout tRow = row(this);
        tRow.addView(text(this, "Messes Sale", 16, true, TXT));
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
        if (Updater.available(this)) {
            TextView up = chip(this, "🆕 v" + Updater.latestName, true);
            LinearLayout.LayoutParams upl = lp(WRAP, WRAP); upl.leftMargin = dp(this, 6);
            Fx.onTap(up, this::startUpdate);
            head.addView(up, upl);
        } else {
            TextView chk = iconBtn(this, "🔄");
            ((LinearLayout.LayoutParams) chk.getLayoutParams()).leftMargin = dp(this, 6);
            Fx.onTap(chk, () -> {
                Toast.makeText(this, "🔄 กำลังตรวจเวอร์ชันล่าสุด…", Toast.LENGTH_SHORT).show();
                Updater.check(this, (ok, msg) -> {
                    Toast.makeText(this, (ok ? "🆕 " : "") + msg, Toast.LENGTH_LONG).show();
                    if (ok && panelOpen) { closePanel(); openPanel(); }
                });
            });
            head.addView(chk);
        }
        TextView close = iconBtn(this, "✕");
        ((LinearLayout.LayoutParams) close.getLayoutParams()).leftMargin = dp(this, 6);
        Fx.onTap(close, this::closePanel);
        head.addView(close);
        panelView.addView(head, lp(MATCH, WRAP));

        // ---- segmented ----
        LinearLayout seg = row(this);
        seg.setBackground(glass(this, SURFACE, 12, LINE));
        seg.setPadding(dp(this,3), dp(this,3), dp(this,3), dp(this,3));
        segCard = text(this, "Card", 12.5f, true, TXT);
        segCustomer = text(this, "ตอบลูกค้า", 12.5f, true, TXT);
        segStaff = text(this, "แจ้งพนักงาน", 12.5f, true, TXT);
        segOrders = text(this, "ออเดอร์", 12.5f, true, TXT);
        for (TextView t : new TextView[]{segCard, segCustomer, segStaff, segOrders}) {
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(this,8), 0, dp(this,8));
        }
        Fx.onTap(segCustomer, () -> { mode = MsgBuilder.MODE_CUSTOMER; refreshSeg(); });
        Fx.onTap(segStaff, () -> { mode = MsgBuilder.MODE_STAFF; refreshSeg(); });
        Fx.onTap(segCard, () -> { mode = MODE_CARD; refreshSeg(); });
        Fx.onTap(segOrders, () -> { mode = MODE_ORDERS; refreshSeg(); });
        seg.addView(segCard, lpw(0.8f));
        seg.addView(segCustomer, lpw(1));
        seg.addView(segStaff, lpw(1));
        seg.addView(segOrders, lpw(0.9f));
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
        TextView sClear = text(this, "✕", 14, true, TXT);
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
        previewBox.setBackground(glass(this, GLASS, 13, STROKE));
        previewBox.setPadding(dp(this,12), dp(this,10), dp(this,12), dp(this,10));
        previewBox.setVisibility(View.GONE);
        LinearLayout pHead = row(this);
        pHead.addView(text(this, "พรีวิวข้อความ", 11, false, WHITE_DIM), lpw(1));
        TextView pClose = text(this, "ซ่อน", 11, true, RED);
        Fx.onTap(pClose, this::togglePreview);
        pHead.addView(pClose);
        previewBox.addView(pHead, lp(MATCH, WRAP));
        ScrollView pSv = new ScrollView(this);
        previewText = text(this, "", 11.5f, false, TXT);
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
            posUi.postDelayed(() -> { if (panelOpen) closePanel(); }, 150);   // ยุบกลับเป็นฟอง พร้อมวางในแชต
        });
        posBtn = button(this, editingId != null ? "✏️  อัปเดตออเดอร์" : "🧾  ส่งเข้า POS",
                glass(this, editingId != null ? LAVENDER_SOFT : MINT_SOFT, 16, editingId != null ? LAVENDER : MINT), 14);
        posBtn.setTextColor(editingId != null ? LAVENDER : MINT);
        Fx.onCopyTap(posBtn, this::sendToPos);
        TextView clearBtn = button(this, "ล้าง", glass(this, SURFACE_2, 16, LINE), 14);
        clearBtn.setTextColor(WHITE_DIM);
        Fx.onTap(clearBtn, () -> { MsgBuilder.clear(cats); shipFee = -1; orderNote = ""; buildPayRows(); rebuildBody(); refreshTotal(); });
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
        fitPanel();
    }

    /** ถ้าแผงสูงเกินจอ (จอเล็ก/ฟอนต์ใหญ่/แนวนอน) ให้ย่อพื้นที่รายการลงจนปุ่มล่างอยู่ในจอ */
    private int bodyMaxDp = BODY_TALL;
    private void fitPanel() {
        if (panelView == null) return;
        panelView.post(() -> {
            if (panelView == null || bodyScroll == null) return;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int over = panelView.getHeight() - (screenH - dp(this, 36));
            int cur = (int) (bodyScroll.getLayoutParams().height / getResources().getDisplayMetrics().density);
            if (over > 0) {
                int target = Math.max(110, cur - (int) (over / getResources().getDisplayMetrics().density) - 4);
                bodyMaxDp = Math.min(bodyMaxDp, target);
                setBodyHeight(target);
            }
        });
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
        if (dpH > bodyMaxDp) dpH = bodyMaxDp;
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

        // โน้ตภาพรวมออเดอร์ (แยกจากโน้ตต่อเมนู)
        final EditText on = input(this, "📝 โน้ตออเดอร์ เช่น ส่งก่อน 6 โมง / โทรก่อนถึง");
        orderNoteInput = on;
        on.setText(orderNote);
        on.setTextSize(13);
        on.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c,int a,int b,int d) {}
            @Override public void onTextChanged(CharSequence c,int a,int b,int d) { orderNote = c.toString(); refreshPreview(); }
            @Override public void afterTextChanged(Editable e) {}
        });
        watchKeyboard(on);
        LinearLayout.LayoutParams onl = lp(MATCH, WRAP); onl.topMargin = dp(this, 9);
        staffPay.addView(on, onl);
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
        closeConfirm();
        if (panelView != null) { try { wm.removeView(panelView); } catch (Exception ignored) {} panelView = null; }
        previewBox = null; previewText = null; bodyScroll = null; shipPlaceField = null; editorBar = null; posBtn = null; orderNoteInput = null;
        noteEditFor = null; priceEditFor = null;
        posUi.removeCallbacks(posPoll);
        panelOpen = false;
    }

    /* ================= body ================= */
    private void rebuildBody() {
        bodyBox.removeAllViews();
        shipPlaceField = null; // จะถูกตั้งใหม่ถ้าบล็อกค่าส่งถูกสร้าง
        refreshEditorBar();

        if (editingId != null && !filter.equals(F_POS)) {
            LinearLayout eb = row(this);
            eb.setBackground(surfaceTint(this, 14, LAVENDER));
            eb.setPadding(dp(this,12), dp(this,9), dp(this,10), dp(this,9));
            LinearLayout ec = col(this);
            ec.addView(text(this, "✏️ กำลังแก้ไข " + editingLabel, 12.5f, true, LAVENDER));
            ec.addView(text(this, "ปรับรายการแล้วกด \"อัปเดตออเดอร์\" — POS จะเด้งเตือนให้กดรับทราบใหม่", 10.5f, false, WHITE_DIM));
            eb.addView(ec, lpw(1));
            TextView back = chip(this, "📋 สถานะ", false);
            LinearLayout.LayoutParams bkl = lp(WRAP, WRAP); bkl.rightMargin = dp(this, 6);
            Fx.onTap(back, () -> { filter = F_POS; refreshFilters(); rebuildBody(); fetchPosOrders(); });
            eb.addView(back, bkl);
            TextView cancel = chip(this, "ยกเลิก", false);
            Fx.onTap(cancel, () -> { editingId = null; editingLabel = ""; editingPrevText = ""; orderNote = ""; MsgBuilder.clear(cats); shipFee = -1; buildPayRows(); refreshPosBtn(); rebuildBody(); refreshTotal(); });
            eb.addView(cancel);
            LinearLayout.LayoutParams ebl = lp(MATCH, WRAP); ebl.bottomMargin = dp(this, 8);
            bodyBox.addView(eb, ebl);
        }

        if (filter.equals(F_POS)) { buildPosStatus(); return; }
        if (filter.equals(F_ORDERS)) { buildDeliverQueue(); return; }

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
            try { PosSender.harvestRefresh(BubbleService.this); } catch (Exception ignored) {}
            if (filter.equals(F_ORDERS)) { try { PosSender.zonesRefresh(BubbleService.this); } catch (Exception ignored) {} }
            if (filter.equals(F_POS) || filter.equals(F_ORDERS)) { try { posArchive = PosSender.fetchArchive(BubbleService.this); } catch (Exception ignored) {} }
            final List<PosSender.OrderStatus> res = got;
            posUi.post(() -> {
                posLoading = false;
                if (res != null) posOrders = res;
                if (panelOpen && (filter.equals(F_POS) || filter.equals(F_ORDERS))) rebuildBody();
            });
        }).start();
    }

    /* ================= คิวส่งออเดอร์ (ให้ไรเดอร์ส่งใกล้ → ไกล) ================= */

    private static class DQ { String id, place, no, pay, time; int total; String status; int zone; long at; }

    private List<DQ> deliverable() {
        List<DQ> out = new ArrayList<>();
        long cutoff = PosSender.now() - 24L * 3600000L;   // ประวัติที่เก็บแล้ว โชว์เฉพาะ 24 ชม.
        List<PosSender.OrderStatus> list = new ArrayList<>(posOrders);
        for (PosSender.OrderStatus o : posArchive) if (o.createdAt > cutoff) list.add(o);
        java.util.Collections.sort(list, (x, y) -> Long.compare(x.createdAt, y.createdAt));   // เก่าก่อน
        for (PosSender.OrderStatus o : list) {
            DQ d = new DQ();
            d.id = o.id; d.place = o.place.isEmpty() ? "(ไม่ระบุที่อยู่)" : o.place; d.no = o.no; d.total = o.total; d.time = o.clock();
            d.status = o.status;
            d.zone = PosSender.zoneOf(this, o.place);
            d.at = o.createdAt;
            String pay = "";
            for (String ln : o.pay.split("\n")) { String t = ln.trim(); if (t.startsWith("สถานะ")) { int i = t.indexOf(":"); if (i > 0) pay = t.substring(i + 1).trim(); } }
            d.pay = pay;
            out.add(d);
        }
        return out;
    }

    /** ลำดับที่ใช้จริง: ถ้ากดเรียงเองใช้อันนั้น ไม่งั้นเรียงอัตโนมัติตามโซน (ไม่มีโซนไว้ท้าย) เฉพาะออเดอร์ที่ยังไม่เสร็จ */
    /** ลำดับอัตโนมัติ: โซนใกล้→ไกล แล้วตามเวลาสั่ง (ไม่รวมที่เสร็จแล้ว) */
    private List<String> autoSeq(List<DQ> all) {
        List<DQ> act = new ArrayList<>();
        for (DQ d : all) if (!d.status.equals("เสร็จ")) act.add(d);
        java.util.Collections.sort(act, (a, b) -> {
            int za = a.zone <= 0 ? 99 : a.zone, zb = b.zone <= 0 ? 99 : b.zone;
            if (za != zb) return za - zb;
            return Long.compare(a.at, b.at);
        });
        List<String> out = new ArrayList<>();
        for (DQ d : act) out.add(d.id);
        return out;
    }

    private String queueText(List<String> seq, List<DQ> all) {
        StringBuilder sb = new StringBuilder("🛵 คิวส่งออเดอร์ (ใกล้ → ไกล)\n");
        int n = 1;
        for (String id : seq) for (DQ d : all) if (d.id.equals(id)) {
            sb.append(n++).append(". ").append(d.place);
            if (!d.no.isEmpty()) sb.append(" (#").append(d.no).append(")");
            if (d.zone > 0) sb.append(" [โซน ").append(d.zone).append("]");
            sb.append(" · สั่ง ").append(d.time).append(" น.");
            if (d.total > 0) sb.append(" · ").append(d.total).append(" บาท");
            if (!d.pay.isEmpty()) sb.append(" · ").append(d.pay);
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private TextView copyQueueBtn(String label, List<String> seq, List<DQ> all, boolean primaryStyle) {
        TextView b = button(this, seq.isEmpty() ? "📋  ยังไม่มีออเดอร์ที่ต้องส่ง" : label,
                seq.isEmpty() || !primaryStyle ? glass(this, SURFACE_2, 16, seq.isEmpty() ? LINE : MINT) : primary(this, 16), 13.5f);
        if (!primaryStyle && !seq.isEmpty()) b.setTextColor(MINT);
        Fx.onCopyTap(b, () -> {
            if (seq.isEmpty()) return;
            copy(queueText(seq, all), "คัดลอกคิวส่งแล้ว — วางในแชตไรเดอร์ได้เลย");
            posUi.postDelayed(() -> { if (panelOpen) closePanel(); }, 150);
        });
        return b;
    }

    /** ตาราง 3 ต่อแถว; tappable = แตะเรียง/กดค้างตั้งโซนได้ */
    private void deliverGrid(List<DQ> items, List<String> seqList, boolean manual, boolean tappable) {
        LinearLayout rowv = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % 3 == 0) {
                rowv = row(this);
                rowv.setGravity(Gravity.TOP);
                LinearLayout.LayoutParams rl = lp(MATCH, WRAP); rl.bottomMargin = dp(this, 8);
                bodyBox.addView(rowv, rl);
            }
            final DQ d = items.get(i);
            int seq = seqList.indexOf(d.id);
            boolean picked = tappable && seq >= 0;
            boolean done = d.status.equals("เสร็จ");

            LinearLayout card = col(this);
            card.setBackground(surface(this, 16, picked));
            if (picked) card.setBackground(surfaceTint(this, 16, manual ? MINT : MINT));
            if (zoneEditFor != null && zoneEditFor.equals(d.id)) card.setBackground(surfaceTint(this, 16, AMBER));
            card.setPadding(dp(this,9), dp(this,8), dp(this,9), dp(this,9));
            card.setAlpha(done ? 0.6f : 1f);

            LinearLayout top = row(this);
            top.setGravity(Gravity.TOP);
            TextView pl = text(this, d.place, 12.5f, true, TXT);
            pl.setMaxLines(2);
            top.addView(pl, lpw(1));
            TextView badge = text(this, picked ? String.valueOf(seq + 1) : (done ? "✓" : ""), 13, true, picked ? (manual ? WHITE : MINT) : MUTED);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(glass(this, picked ? (manual ? MINT : MINT_SOFT) : 0x00000000, 20, picked ? (manual ? 0 : MINT) : LINE));
            int bs = dp(this, 26);
            LinearLayout.LayoutParams bl = lp(bs, bs); bl.leftMargin = dp(this, 4);
            top.addView(badge, bl);
            card.addView(top, lp(MATCH, WRAP));

            TextView meta = text(this, (d.no.isEmpty() ? "" : "#" + d.no + " · ") + d.time + " น." + (d.zone > 0 ? " · โซน " + d.zone : " · ยังไม่ตั้งโซน"), 10.5f, false, d.zone > 0 ? WHITE_DIM : AMBER);
            meta.setPadding(0, dp(this, 4), 0, 0);
            card.addView(meta);
            String stTxt = done ? "✅ เสร็จ" : d.status.equals("กำลังทำ") ? "🟠 กำลังทำ" : "🟡 รอรับ";
            TextView st = text(this, stTxt + (d.pay.isEmpty() ? "" : " · " + d.pay), 10.5f, false,
                    d.pay.contains("ชำระแล้ว") ? MINT : d.pay.isEmpty() ? WHITE_DIM : AMBER);
            st.setPadding(0, dp(this, 2), 0, 0);
            card.addView(st);

            // ---- ตัวเลือกโซน (กดค้างที่การ์ด) ----
            if (zoneEditFor != null && zoneEditFor.equals(d.id)) {
                LinearLayout zr = row(this);
                for (int z = 1; z <= 9; z++) {
                    final int zz = z;
                    TextView zc = text(this, String.valueOf(z), 12, true, d.zone == z ? WHITE : TXT);
                    zc.setGravity(Gravity.CENTER);
                    zc.setBackground(glass(this, d.zone == z ? AMBER : SURFACE, 10, d.zone == z ? 0 : LINE));
                    int zs = dp(this, 38);
                    LinearLayout.LayoutParams zl = lp(zs, zs); zl.rightMargin = dp(this, 4);
                    Fx.onTap(zc, () -> saveZone(d.place, zz));
                    zr.addView(zc, zl);
                }
                android.widget.HorizontalScrollView zsv = new android.widget.HorizontalScrollView(this);
                zsv.setHorizontalScrollBarEnabled(false);
                zsv.addView(zr);
                LinearLayout.LayoutParams zvl = lp(MATCH, WRAP); zvl.topMargin = dp(this, 6);
                card.addView(zsv, zvl);
                TextView zx = text(this, d.zone > 0 ? "ลบโซน" : "ปิด", 10.5f, true, WHITE_DIM);
                zx.setPadding(0, dp(this, 4), 0, 0);
                Fx.onTap(zx, () -> { if (d.zone > 0) saveZone(d.place, 0); else { zoneEditFor = null; rebuildBody(); } });
                card.addView(zx);
            }

            if (tappable) {
                Fx.onTap(card, () -> {
                    if (zoneEditFor != null) { zoneEditFor = null; rebuildBody(); return; }
                    if (deliverSeq.contains(d.id)) deliverSeq.remove(d.id); else deliverSeq.add(d.id);
                    rebuildBody();
                });
            }
            Fx.onHold(card, () -> { zoneEditFor = zoneEditFor != null && zoneEditFor.equals(d.id) ? null : d.id; rebuildBody(); });
            LinearLayout.LayoutParams cl = lpw(1);
            if (i % 3 != 0) cl.leftMargin = dp(this, 6);
            rowv.addView(card, cl);
        }
        if (rowv != null) for (int k = items.size() % 3; k != 0 && k < 3; k++) { View sp = new View(this); LinearLayout.LayoutParams cl = lpw(1); cl.leftMargin = dp(this, 6); rowv.addView(sp, cl); }
    }

    private void saveZone(String place, int zone) {
        zoneEditFor = null;
        new Thread(() -> {
            String m;
            try { PosSender.setZone(BubbleService.this, place, zone); m = zone > 0 ? "📍 " + place + " = โซน " + zone : "ลบโซนของ " + place + " แล้ว"; }
            catch (Exception e) { m = "❌ บันทึกโซนไม่สำเร็จ: " + e.getMessage(); }
            final String mm = m;
            posUi.post(() -> { try { Toast.makeText(BubbleService.this, mm, Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {} if (panelOpen) rebuildBody(); });
        }).start();
        rebuildBody();
    }

    private void buildDeliverQueue() {
        List<DQ> all = deliverable();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (DQ d : all) ids.add(d.id);
        deliverSeq.retainAll(ids);

        final List<String> auto = autoSeq(all);
        final boolean manual = !deliverSeq.isEmpty();

        LinearLayout h = row(this);
        h.addView(Masonry.header(this, "คิวส่งออเดอร์", MINT), lpw(1));
        TextView reset = chip(this, "รีเซ็ตที่เรียงเอง", false);
        reset.setAlpha(manual ? 1f : 0.45f);
        Fx.onTap(reset, () -> { deliverSeq.clear(); zoneEditFor = null; rebuildBody(); });
        h.addView(reset);
        bodyBox.addView(h, lp(MATCH, WRAP));
        TextView hint = text(this, "กดค้างที่การ์ดเพื่อตั้งโซน (1 = ใกล้ร้านสุด → 9 = ไกลสุด) ตั้งครั้งเดียวต่อที่อยู่ แอปจำให้ตลอด", 10.5f, false, WHITE_DIM);
        hint.setPadding(dp(this,2), 0, 0, dp(this,8));
        bodyBox.addView(hint);

        if (all.isEmpty()) {
            TextView em = text(this, posLoading ? "กำลังโหลด…" : "ยังไม่มีออเดอร์บนคลาวด์", 13, false, WHITE_DIM);
            em.setPadding(dp(this,2), dp(this,12), 0, 0);
            bodyBox.addView(em);
            return;
        }

        List<DQ> act = new ArrayList<>(), fin = new ArrayList<>();
        for (DQ d : all) { if (d.status.equals("เสร็จ")) fin.add(d); else act.add(d); }
        java.util.Collections.reverse(fin);   // เสร็จล่าสุดอยู่บน

        // ===== บน: เรียงเอง (แตะการ์ดทีละใบ) =====
        bodyBox.addView(sectionTitle("✋ เรียงเอง — แตะการ์ดตามลำดับที่จะไปส่ง", MINT, act.isEmpty() ? "" : deliverSeq.size() + "/" + act.size()));
        if (act.isEmpty()) {
            TextView em = text(this, "ไม่มีออเดอร์ค้างส่ง", 12, false, WHITE_DIM);
            em.setPadding(dp(this,2), 0, 0, dp(this,8));
            bodyBox.addView(em);
        } else {
            deliverGrid(act, deliverSeq, true, true);
            LinearLayout.LayoutParams c1 = lp(MATCH, WRAP); c1.bottomMargin = dp(this, 10);
            bodyBox.addView(copyQueueBtn("📋  คัดลอกที่เรียงเอง (" + deliverSeq.size() + " ออเดอร์)",
                    manual ? new ArrayList<>(deliverSeq) : new ArrayList<>(), all, true), c1);
        }

        // ===== กลาง: ตัวอย่างเรียงอัตโนมัติตามโซน =====
        if (!act.isEmpty()) {
            bodyBox.addView(sectionTitle("⚡ ตัวอย่างเรียงอัตโนมัติ (ตามโซน ใกล้ → ไกล)", LAVENDER, auto.size() + " ออเดอร์"));
            LinearLayout box = col(this);
            box.setBackground(surface(this, 16, false));
            box.setPadding(dp(this,12), dp(this,8), dp(this,12), dp(this,8));
            int n = 1;
            for (String id : auto) for (DQ d : act) if (d.id.equals(id)) {
                LinearLayout r = row(this);
                TextView num = text(this, String.valueOf(n++), 12, true, LAVENDER);
                num.setMinWidth(dp(this, 20));
                r.addView(num);
                TextView pl = text(this, d.place, 12, true, TXT);
                pl.setMaxLines(1);
                r.addView(pl, lpw(1));
                TextView m = text(this, (d.zone > 0 ? "โซน " + d.zone : "ไม่มีโซน") + " · " + d.time, 10.5f, false, d.zone > 0 ? WHITE_DIM : AMBER);
                r.addView(m);
                LinearLayout.LayoutParams rl = lp(MATCH, WRAP); rl.topMargin = dp(this, 3); rl.bottomMargin = dp(this, 3);
                box.addView(r, rl);
            }
            bodyBox.addView(box, lp(MATCH, WRAP));
            LinearLayout.LayoutParams c2 = lp(MATCH, WRAP); c2.topMargin = dp(this, 6); c2.bottomMargin = dp(this, 10);
            bodyBox.addView(copyQueueBtn("📋  คัดลอกแบบอัตโนมัติ (" + auto.size() + " ออเดอร์)", auto, all, !manual), c2);
        }

        // ===== ล่าง: เสร็จ/ส่งแล้ว (ดูอย่างเดียว) =====
        if (!fin.isEmpty()) {
            bodyBox.addView(sectionTitle("✅ เสร็จ/ส่งแล้ว — ย้อนหลัง 24 ชม.", WHITE_DIM, fin.size() + " ออเดอร์"));
            deliverGrid(fin, new ArrayList<>(), false, false);
        }
    }

    private View sectionTitle(String t, int color, String right) {
        LinearLayout r = row(this);
        r.addView(text(this, t, 12.5f, true, color), lpw(1));
        if (!right.isEmpty()) r.addView(text(this, right, 11, false, WHITE_DIM));
        r.setPadding(dp(this,2), dp(this,4), dp(this,2), dp(this,6));
        return r;
    }

    /** รายการออเดอร์ที่ส่งไป POS พร้อมสถานะสด */
    private int posSeg = 0;                 // 0 วันนี้ / 1 เสร็จแล้ว / 2 ทั้งหมด
    private boolean harvestExpanded = false;

    /** หน้าสถานะออเดอร์บน POS — แบบ "การ์ดสถานะ + ขั้นตอน" */
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
                    12.5f, false, AMBER);
            warn.setPadding(dp(this,2), dp(this,10), 0, 0);
            bodyBox.addView(warn);
            return;
        }
        if (!posBanner.isEmpty() && System.currentTimeMillis() - posBannerAt < 120000L) {
            boolean good = posBanner.startsWith("✅");
            TextView bn = text(this, posBanner, 12.5f, true, good ? MINT : RED);
            bn.setBackground(glass(this, good ? MINT_SOFT : 0xFFFEE2E2, 12, good ? MINT : RED));
            bn.setPadding(dp(this,12), dp(this,10), dp(this,12), dp(this,10));
            LinearLayout.LayoutParams bl = lp(MATCH, WRAP); bl.topMargin = dp(this,8); bl.bottomMargin = dp(this,6);
            bodyBox.addView(bn, bl);
        }

        // ---- แยกกลุ่ม ----
        List<PosSender.OrderStatus> waiting = new ArrayList<>(), doing = new ArrayList<>(), done = new ArrayList<>();
        for (PosSender.OrderStatus o : posOrders) {
            if (o.status.equals("เสร็จ")) done.add(o);
            else if (o.status.equals("ใหม่") || o.waitingAck()) waiting.add(o);
            else doing.add(o);
        }
        long cutoff = PosSender.now() - 24L * 3600000L;
        for (PosSender.OrderStatus o : posArchive) if (o.createdAt > cutoff) done.add(o);
        java.util.Collections.sort(waiting, (x, y) -> Long.compare(x.createdAt, y.createdAt));
        java.util.Collections.sort(doing, (x, y) -> Long.compare(x.createdAt, y.createdAt));
        java.util.Collections.sort(done, (x, y) -> Long.compare(y.createdAt, x.createdAt));
        List<PosSender.SendLog> failed = new ArrayList<>();
        for (PosSender.SendLog l : PosSender.logList(this)) if (!l.ok) failed.add(l);

        // ---- แถบเลือก ----
        LinearLayout seg = row(this);
        seg.setBackground(glass(this, SURFACE, 12, LINE));
        seg.setPadding(dp(this,3), dp(this,3), dp(this,3), dp(this,3));
        String[] segNames = {"วันนี้ (" + (waiting.size() + doing.size() + failed.size()) + ")", "เสร็จแล้ว (" + done.size() + ")", "ทั้งหมด"};
        for (int i = 0; i < 3; i++) {
            final int si = i;
            TextView t = text(this, segNames[i], 12, true, posSeg == i ? WHITE : 0xFF6B7280);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(this,7), 0, dp(this,7));
            t.setBackground(posSeg == i ? glass(this, ACCENT, 9, 0) : null);
            Fx.onTap(t, () -> { posSeg = si; rebuildBody(); });
            seg.addView(t, lpw(1));
        }
        LinearLayout.LayoutParams sl = lp(MATCH, WRAP); sl.topMargin = dp(this, 8); sl.bottomMargin = dp(this, 8);
        bodyBox.addView(seg, sl);

        // ---- เก็บกุ้ง: แถวเดียว (กดค้างเพื่อตั้งอัตโนมัติ) ----
        if (harvestExpanded) {
            View hb = harvestBox();
            Fx.onHold(hb, () -> { harvestExpanded = false; rebuildBody(); });
            LinearLayout.LayoutParams hl = lp(MATCH, WRAP); hl.bottomMargin = dp(this, 8);
            bodyBox.addView(hb, hl);
        } else {
            LinearLayout hv = row(this);
            hv.setBackground(surfaceTint(this, 12, LINE));
            hv.setPadding(dp(this,12), dp(this,8), dp(this,8), dp(this,8));
            hv.addView(text(this, "🦐 เก็บกุ้ง", 12.5f, true, TXT));
            String st;
            int stc = WHITE_DIM;
            if (PosSender.harvestAt > 0) {
                boolean acked = PosSender.harvestAck >= PosSender.harvestAt;
                String when = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(PosSender.harvestAt - PosSender.offset));
                st = (acked ? "✅ " : "🔔 รอรับทราบ ") + when;
                stc = acked ? MINT : AMBER;
            } else st = "ยังไม่เคยแจ้ง";
            int cur = PosSender.harvestEvery(this);
            TextView stv = text(this, "  " + st + (cur > 0 ? "  · ทุก " + cur + " น." : ""), 11.5f, false, stc);
            stv.setSingleLine(true); stv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            hv.addView(stv, lpw(1));
            TextView now = button(this, "แจ้งตอนนี้", primary(this, 10), 12);
            now.setPadding(dp(this,12), dp(this,7), dp(this,12), dp(this,7));
            Fx.onTap(now, () -> sendHarvest(false));
            hv.addView(now);
            Fx.onHold(hv, () -> { harvestExpanded = true; rebuildBody(); });
            Fx.onTap(stv, () -> { harvestExpanded = true; rebuildBody(); });
            LinearLayout.LayoutParams hl = lp(MATCH, WRAP); hl.bottomMargin = dp(this, 8);
            bodyBox.addView(hv, hl);
        }

        if (editingId != null) {
            LinearLayout eb = row(this);
            eb.setBackground(surfaceTint(this, 14, LAVENDER));
            eb.setPadding(dp(this,12), dp(this,9), dp(this,10), dp(this,9));
            eb.addView(text(this, "✏️ กำลังแก้ไข " + editingLabel, 12.5f, true, LAVENDER), lpw(1));
            TextView go = chip(this, "กลับไปแก้ไข", false);
            Fx.onTap(go, () -> { filter = "ทั้งหมด"; refreshFilters(); rebuildBody(); });
            eb.addView(go);
            LinearLayout.LayoutParams ebl = lp(MATCH, WRAP); ebl.bottomMargin = dp(this, 8);
            bodyBox.addView(eb, ebl);
        }

        if (posOrders.isEmpty() && failed.isEmpty() && done.isEmpty()) {
            TextView em = text(this, posLoading ? "กำลังโหลด…" : "ยังไม่มีออเดอร์บนคลาวด์", 13, false, WHITE_DIM);
            em.setPadding(dp(this,2), dp(this,12), 0, 0);
            bodyBox.addView(em);
            return;
        }

        boolean showActive = posSeg != 1, showDone = true;
        if (showActive) {
            if (!failed.isEmpty() || !waiting.isEmpty()) {
                posSection("🔴 รอ POS กดรับ (" + (waiting.size() + failed.size()) + ")", RED, failed.isEmpty() ? "" : "ส่งไม่สำเร็จ " + failed.size());
                for (PosSender.SendLog l : failed) bodyBox.addView(failCard(l), posCardLp());
                for (PosSender.OrderStatus o : waiting) bodyBox.addView(statusCard(o, true), posCardLp());
            }
            if (!doing.isEmpty()) {
                posSection("🟠 กำลังทำ (" + doing.size() + ")", ACCENT, "");
                for (PosSender.OrderStatus o : doing) bodyBox.addView(statusCard(o, true), posCardLp());
            }
            if (waiting.isEmpty() && doing.isEmpty() && failed.isEmpty()) {
                TextView em = text(this, "ไม่มีออเดอร์ค้าง 🎉", 13, false, WHITE_DIM);
                em.setPadding(dp(this,2), dp(this,6), 0, dp(this,8));
                bodyBox.addView(em);
            }
        }
        if (showDone && !done.isEmpty()) {
            int limit = posSeg == 0 ? 3 : done.size();
            posSection("✅ เสร็จแล้ว — แจ้งลูกค้า (" + done.size() + ")", MUTED, posSeg == 0 && done.size() > limit ? "ดูทั้งหมด ›" : "");
            for (int i = 0; i < Math.min(limit, done.size()); i++) bodyBox.addView(statusCard(done.get(i), false), posCardLp());
        }

        LinearLayout foot = row(this);
        TextView tip = text(this, "แตะการ์ดเพื่อย่อ/กางรายการ · กดค้างแถวเก็บกุ้งเพื่อตั้งอัตโนมัติ · อัปเดตทุก 6 วิ", 10.5f, false, MUTED);
        foot.addView(tip, lpw(1));
        if (!failed.isEmpty()) {
            TextView clr = chip(this, "ล้างที่ส่งไม่สำเร็จ", false);
            Fx.onTap(clr, () -> { PosSender.logClear(this); posBanner = ""; rebuildBody(); });
            foot.addView(clr);
        }
        LinearLayout.LayoutParams fl = lp(MATCH, WRAP); fl.topMargin = dp(this,4);
        bodyBox.addView(foot, fl);
    }

    private void posSection(String title, int color, String right) {
        LinearLayout r = row(this);
        r.addView(text(this, title, 12.5f, true, color), lpw(1));
        if (!right.isEmpty()) {
            TextView rt = text(this, right, 11.5f, false, MUTED);
            if (right.startsWith("ดู")) Fx.onTap(rt, () -> { posSeg = 1; rebuildBody(); });
            r.addView(rt);
        }
        r.setPadding(dp(this,2), dp(this,6), dp(this,2), dp(this,6));
        bodyBox.addView(r, lp(MATCH, WRAP));
    }

    private LinearLayout.LayoutParams posCardLp() {
        LinearLayout.LayoutParams cl = lp(MATCH, WRAP);
        cl.bottomMargin = dp(this, 8);
        return cl;
    }

    /** กล่องเวลา (นาทีที่รอ) สีตามระดับ */
    private LinearLayout minuteBox(int m, boolean done, boolean waiting) {
        LinearLayout b = col(this);
        b.setGravity(Gravity.CENTER);
        int c = done ? 0xFF9AA1AE : waiting ? ACCENT : m >= 50 ? RED : m >= 40 ? 0xFFF97316 : m >= 30 ? 0xFFEAB308 : 0xFF22C55E;
        b.setBackground(glass(this, c, 11, 0));
        TextView n = text(this, done ? "✓" : String.valueOf(m), 15, true, WHITE);
        n.setGravity(Gravity.CENTER); n.setIncludeFontPadding(false);
        b.addView(n);
        if (!done) { TextView u = text(this, "นาที", 9, false, WHITE); u.setGravity(Gravity.CENTER); b.addView(u); }
        return b;
    }

    /** การ์ดสถานะ 1 ออเดอร์: หัว + แถบขั้นตอน + รายการเมนู + ปุ่ม */
    private View statusCard(final PosSender.OrderStatus o, boolean active) {
        boolean isDone = o.status.equals("เสร็จ"), isNew = o.status.equals("ใหม่"), ack = o.waitingAck();
        int m = o.minAgo();
        boolean late = !isDone && m >= OVERDUE_MIN;
        final String key = o.id;
        final boolean collapsed = isDone ? !key.equals(expandedLogId) : key.equals(expandedLogId);   // ค้าง = กางไว้ก่อน, เสร็จ = ย่อไว้ก่อน

        LinearLayout card = col(this);
        card.setBackground(glass(this, isNew ? 0xFFFFF8F4 : SURFACE, 14, late ? RED : isNew ? ACCENT : ack ? LAVENDER : LINE));
        card.setPadding(dp(this,12), dp(this,10), dp(this,12), dp(this,10));
        if (isDone) card.setAlpha(0.8f);

        // ---- หัว ----
        LinearLayout top = row(this);
        LinearLayout.LayoutParams tl = lp(dp(this,46), dp(this,46)); tl.rightMargin = dp(this, 10);
        top.addView(minuteBox(m, isDone, isNew), tl);
        LinearLayout info = col(this);
        TextView pl = text(this, o.place.isEmpty() ? ("pos".equals(o.by) ? "คีย์ที่ POS" : "ออเดอร์") : o.place, 15, true, TXT);
        pl.setSingleLine(true); pl.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(pl);
        String payS = payShort(o.pay);
        String meta = (o.no.isEmpty() ? "" : "#" + o.no + " · ") + (isDone ? "เสร็จ · ส่ง " : "ส่ง ") + o.clock()
                + (o.total > 0 ? " · " + o.total + " บาท" : "") + (payS.isEmpty() ? "" : " · " + payS)
                + (o.edits > 0 ? " · แก้ " + o.edits + " ครั้ง" : "");
        TextView mt = text(this, meta, 11, false, WHITE_DIM);
        mt.setPadding(0, dp(this,2), 0, 0);
        info.addView(mt);
        top.addView(info, lpw(1));
        String tagTxt = late ? "เกิน " + OVERDUE_MIN + " น." : ack ? "รอ POS รับทราบแก้" : isNew ? "รอ POS รับ" : isDone ? "เสร็จ" : "กำลังทำ";
        int tagBg = late ? 0xFFFEE2E2 : ack ? LAVENDER_SOFT : isNew ? ACCENT_SOFT : isDone ? 0xFFEEF0F3 : MINT_SOFT;
        int tagFg = late ? RED : ack ? LAVENDER : isNew ? ACCENT : isDone ? MUTED : MINT;
        TextView tag = text(this, tagTxt, 10.5f, true, tagFg);
        tag.setBackground(glass(this, tagBg, 20, 0));
        tag.setPadding(dp(this,8), dp(this,3), dp(this,8), dp(this,3));
        LinearLayout.LayoutParams tgl = lp(WRAP, WRAP); tgl.leftMargin = dp(this, 6);
        top.addView(tag, tgl);
        card.addView(top, lp(MATCH, WRAP));

        // ---- แถบ 4 ขั้น ----
        int step = isDone ? 4 : isNew ? 1 : 2;   // จำนวนขั้นที่ผ่าน (ส่งแล้ว = 1)
        LinearLayout steps = row(this);
        String[] names = {"ส่งแล้ว", "POS รับ", "กำลังทำ", "เสร็จ"};
        for (int i = 0; i < 4; i++) {
            View bar = new View(this);
            int c = i < step ? MINT : i == step ? ACCENT : 0xFFEEF0F3;
            if (isDone) c = MINT;
            bar.setBackground(glass(this, c, 3, 0));
            LinearLayout.LayoutParams bl = lpw(1); bl.height = dp(this, 5); if (i > 0) bl.leftMargin = dp(this, 4);
            steps.addView(bar, bl);
        }
        LinearLayout.LayoutParams stl = lp(MATCH, WRAP); stl.topMargin = dp(this, 9);
        card.addView(steps, stl);
        LinearLayout lbls = row(this);
        for (int i = 0; i < 4; i++) {
            String n = names[i];
            if (i == 1 && !isNew && !isDone && o.ackEditAt == 0 && o.editedAt == 0) n = "POS รับ";
            TextView t = text(this, n, 9.5f, false, i <= step - 1 || isDone ? MINT : i == step ? ACCENT : MUTED);
            t.setGravity(i == 0 ? Gravity.START : i == 3 ? Gravity.END : Gravity.CENTER);
            lbls.addView(t, lpw(1));
        }
        LinearLayout.LayoutParams lbl = lp(MATCH, WRAP); lbl.topMargin = dp(this, 3);
        card.addView(lbls, lbl);

        // ---- รายการเมนู ----
        if (!collapsed) {
            View div = new View(this);
            div.setBackground(glass(this, LINE, 0, 0));
            LinearLayout.LayoutParams dl = lp(MATCH, dp(this, 1)); dl.topMargin = dp(this, 9); dl.bottomMargin = dp(this, 4);
            card.addView(div, dl);
            java.util.regex.Pattern linePat = java.util.regex.Pattern.compile("^(.*?)(?:\\s+[xX×]\\s*(\\d+))?\\s+(\\d[\\d,]*)\\s*บาท(?:\\s*\\((.*)\\))?\\s*$");
            String noteTxt = "";
            for (String raw : o.text.split("\n")) {
                String t = raw.trim();
                if (t.isEmpty() || t.startsWith("order") || t.startsWith("ยอดรวม") || t.startsWith("ช่องทางชำระ") || t.startsWith("สถานะ")) continue;
                if (t.startsWith("หมายเหตุ")) { int i = t.indexOf(":"); noteTxt = i > 0 ? t.substring(i + 1).trim() : t; continue; }
                boolean ship = t.startsWith("ค่าส่ง") || t.startsWith("ส่งฟรี");
                String name = t, qty = "1", price = "", note = "";
                java.util.regex.Matcher mm = linePat.matcher(t);
                if (mm.find()) { name = mm.group(1); if (mm.group(2) != null) qty = mm.group(2); price = mm.group(3); if (mm.group(4) != null) note = mm.group(4); }
                LinearLayout r = row(this);
                r.setGravity(Gravity.TOP);
                r.setPadding(0, dp(this, 3), 0, dp(this, 3));
                TextView q = text(this, ship ? "🛵" : qty + "×", 12.5f, true, ship ? MUTED : ACCENT);
                q.setMinWidth(dp(this, 28));
                r.addView(q);
                LinearLayout nc = col(this);
                nc.addView(text(this, name, 12.5f, false, ship ? MUTED : TXT));
                if (!note.isEmpty()) {
                    TextView nt = text(this, "📝 " + note, 10.5f, true, 0xFF92400E);
                    nt.setBackground(glass(this, AMBER_SOFT, 5, 0));
                    nt.setPadding(dp(this,6), dp(this,1), dp(this,6), dp(this,1));
                    LinearLayout.LayoutParams ntl = lp(WRAP, WRAP); ntl.topMargin = dp(this, 2);
                    nc.addView(nt, ntl);
                }
                r.addView(nc, lpw(1));
                if (!price.isEmpty()) r.addView(text(this, price, 12, false, MUTED));
                card.addView(r, lp(MATCH, WRAP));
            }
            if (!noteTxt.isEmpty()) {
                TextView nb = text(this, "📝 หมายเหตุ: " + noteTxt, 12, true, 0xFF92400E);
                nb.setBackground(glass(this, AMBER_SOFT, 8, 0xFFFDE68A));
                nb.setPadding(dp(this,10), dp(this,6), dp(this,10), dp(this,6));
                LinearLayout.LayoutParams nl = lp(MATCH, WRAP); nl.topMargin = dp(this, 4);
                card.addView(nb, nl);
            }
            LinearLayout tot = row(this);
            tot.setPadding(0, dp(this, 6), 0, 0);
            tot.addView(text(this, "รวม", 12.5f, true, TXT), lpw(1));
            String payAll = payAllOf(o.pay);
            tot.addView(text(this, (o.total > 0 ? o.total + " บาท" : "—") + (payAll.isEmpty() ? "" : " · " + payAll), 12.5f, true,
                    payAll.contains("ชำระแล้ว") ? MINT : TXT));
            card.addView(tot, lp(MATCH, WRAP));

            // ---- ปุ่ม ----
            LinearLayout acts = row(this);
            final PosSender.SendLog lg = o.asLog();
            if (isDone) {
                TextView msg = button(this, "💬 คัดลอก \"ออเดอร์เสร็จแล้ว\" ส่งลูกค้า", glass(this, MINT, 10, 0), 12);
                msg.setPadding(dp(this,10), dp(this,8), dp(this,10), dp(this,8));
                final String doneMsg = "ออเดอร์" + (o.no.isEmpty() ? "" : " #" + o.no) + " เสร็จเรียบร้อยแล้วค่ะ 🦐 " + (o.place.isEmpty() ? "" : "ไรเดอร์กำลังไปส่งที่ " + o.place + " นะคะ") + " ขอบคุณค่ะ 🙏";
                Fx.onCopyTap(msg, () -> { copy(doneMsg, "คัดลอกข้อความแจ้งลูกค้าแล้ว"); posUi.postDelayed(() -> { if (panelOpen) closePanel(); }, 150); });
                acts.addView(msg, lpw(1));
            } else {
                TextView ed = button(this, "✏️ แก้ไข", glass(this, LAVENDER_SOFT, 10, LAVENDER), 12);
                ed.setTextColor(LAVENDER); ed.setPadding(dp(this,8), dp(this,8), dp(this,8), dp(this,8));
                Fx.onTap(ed, () -> loadForEdit(lg));
                acts.addView(ed, lpw(1));
            }
            TextView cp = button(this, "📋 คัดลอก", glass(this, SURFACE, 10, LINE), 12);
            cp.setTextColor(TXT); cp.setPadding(dp(this,8), dp(this,8), dp(this,8), dp(this,8));
            LinearLayout.LayoutParams cpl = lpw(isDone ? 0.6f : 1); cpl.leftMargin = dp(this, 6);
            Fx.onCopyTap(cp, () -> copy(o.text, "คัดลอกออเดอร์แล้ว"));
            acts.addView(cp, cpl);
            LinearLayout.LayoutParams al = lp(MATCH, WRAP); al.topMargin = dp(this, 9);
            card.addView(acts, al);
        }

        Fx.onTap(card, () -> { expandedLogId = key.equals(expandedLogId) ? null : key; rebuildBody(); });
        return card;
    }

    /** การ์ดที่ส่งไม่สำเร็จจากเครื่องนี้ */
    private View failCard(final PosSender.SendLog l) {
        LinearLayout card = row(this);
        card.setBackground(glass(this, 0xFFFEE2E2, 14, RED));
        card.setPadding(dp(this,12), dp(this,10), dp(this,12), dp(this,10));
        TextView x = text(this, "✕", 18, true, WHITE);
        x.setGravity(Gravity.CENTER); x.setBackground(glass(this, RED, 11, 0));
        LinearLayout.LayoutParams xl = lp(dp(this,46), dp(this,46)); xl.rightMargin = dp(this, 10);
        card.addView(x, xl);
        LinearLayout info = col(this);
        info.addView(text(this, (l.place.isEmpty() ? "ออเดอร์" : l.place) + (l.no.isEmpty() ? "" : " #" + l.no), 14, true, TXT));
        info.addView(text(this, "ส่งไม่สำเร็จ " + l.clock() + (l.err.isEmpty() ? "" : " — " + l.err), 11, false, RED));
        card.addView(info, lpw(1));
        TextView retry = button(this, "โหลดกลับมาส่งใหม่", glass(this, RED, 10, 0), 11.5f);
        retry.setPadding(dp(this,10), dp(this,7), dp(this,10), dp(this,7));
        Fx.onTap(retry, () -> { loadForEdit(l); editingId = null; editingLabel = ""; editingPrevText = ""; editingEdits = 0; refreshPosBtn(); });
        card.addView(retry);
        return card;
    }

    /** "โอนปกติ ชำระแล้ว" แบบสั้น */
    private String payShort(String pay) {
        String a = payAllOf(pay);
        return a.length() > 26 ? a.substring(0, 26) + "…" : a;
    }

    private String payAllOf(String pay) {
        List<String> parts = new ArrayList<>();
        if (pay == null) return "";
        for (String pl : pay.split("\n")) {
            String q = pl.trim();
            if (q.isEmpty() || q.startsWith("หมายเหตุ")) continue;
            int i = q.indexOf(":");
            String v = i > 0 ? q.substring(i + 1).trim() : q;
            if (!v.isEmpty() && !parts.contains(v)) parts.add(v);
        }
        return String.join(" ", parts);
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
        top.addView(text(this, it.name, 13, true, TXT), lpw(1));
        if (on) {
            TextView noteBtn = text(this, "📝", 11, false, TXT);
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
            TextView badge = text(this, "×" + it.qty, 11.5f, true, TXT);
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
        TextView priceLbl = text(this, priceTxt, 13, true, accent);
        priceLbl.setPadding(dp(this, 14), 0, 0, 0);
        if (it.custom) Fx.onTap(priceLbl, () -> { priceEditFor = it; noteEditFor = null; rebuildBody(); });
        bottom.addView(priceLbl, lpw(1));

        if (on) {
            TextView minus = roundBtn("−", SURFACE, TXT);
            Fx.onTap(minus, () -> { it.qty--; rebuildBody(); refreshTotal(); });
            bottom.addView(minus);
            TextView plus2 = roundBtn("+", ACCENT, WHITE);
            ((LinearLayout.LayoutParams) plus2.getLayoutParams()).leftMargin = dp(this, 6);
            Fx.onTap(plus2, () -> addOne(it));
            bottom.addView(plus2);
        } else {
            TextView plus = roundBtn("+", SURFACE_2, TXT);
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
        int sz = dp(this, 40);
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
        editorBar.setBackground(surfaceTint(this, 16, priceMode ? AMBER : LAVENDER));
        editorBar.setPadding(dp(this,12), dp(this,9), dp(this,12), dp(this,10));

        TextView title = text(this, (priceMode ? "💰 ใส่ราคา: " : "📝 โน้ต: ") + it.name, 12, true, TXT);
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
        t.setBackground(glass(this, 0x00000000, 18, LINE));
        t.setPadding(dp(this,9), dp(this,13), dp(this,9), dp(this,13));
        t.setGravity(Gravity.CENTER);
        TextView a = text(this, "＋ เพิ่มเมนู", 11.5f, true, WHITE_DIM);
        a.setGravity(Gravity.CENTER);
        t.addView(a);
        TextView b = text(this, "กดค้างที่การ์ดเพื่อแก้ไข", 10.5f, false, MUTED);
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
            TextView row = text(this, "📍  " + p, 12.5f, false, TXT);
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
        box.setBackground(surfaceTint(this, 18, MINT));
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
            if (on) { c.setBackground(glass(this, MINT_SOFT, 11, 0)); c.setTextColor(MINT); }
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
        TextView pSave = text(this, "💾 จำ", 13, true, MINT);
        pSave.setGravity(Gravity.CENTER);
        pSave.setBackground(glass(this, MINT_SOFT, 11, 0));
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

        TextView pClear = text(this, "✕", 14, true, TXT);
        pClear.setGravity(Gravity.CENTER);
        pClear.setBackground(glass(this, GLASS, 11, MINT));
        pClear.setPadding(dp(this,13), dp(this,11), dp(this,13), dp(this,11));
        LinearLayout.LayoutParams pcp = lp(WRAP, WRAP); pcp.leftMargin = dp(this,6);
        pClear.setLayoutParams(pcp);
        pRow.addView(pClear);
        box.addView(pRow, lp(MATCH, WRAP));

        // ดรอปดาวน์แนะนำที่อยู่ใกล้เคียงกับที่พิมพ์
        final LinearLayout suggest = col(this);
        suggest.setBackground(glass(this, GLASS, 11, MINT));
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
            if (p.equals(place)) { c.setBackground(glass(this, MINT_SOFT, 10, 0)); c.setTextColor(MINT); }
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
        card.setBackground(surfaceTint(this, 18, 0xFFD8CCFA));
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
                    TextView more = text(this, "+" + (c.images.size() - 3), 12, true, TXT);
                    more.setGravity(Gravity.CENTER);
                    more.setBackground(glass(this, 0xFF9AA1AE, 7, 0));
                    LinearLayout.LayoutParams mp = lpw(0.7f);
                    mp.height = dp(this, 52); mp.leftMargin = dp(this, 3);
                    strip.addView(more, mp);
                }
                LinearLayout.LayoutParams sp = lp(MATCH, WRAP); sp.bottomMargin = dp(this, 7);
                card.addView(strip, sp);
            }
        }

        card.addView(text(this, c.title, 12.5f, true, TXT));
        String preview = c.text.length() > 70 ? c.text.substring(0, 70) + "…" : c.text;
        TextView tv = text(this, preview, 11, false, 0xFF3A4150);
        tv.setPadding(0, dp(this, 3), 0, 0);
        card.addView(tv);

        // แถบท้ายการ์ด: แตะ = คัดลอก, ปุ่มปากกามุมขวา = แก้ไข
        LinearLayout foot = row(this);
        TextView hint = text(this, c.images.isEmpty() ? "📋 แตะเพื่อคัดลอก" : "🚀 แตะเพื่อส่งพร้อมรูป",
                10.5f, true, TXT);
        hint.setGravity(Gravity.CENTER);
        hint.setBackground(glass(this, GLASS, 9, LINE));
        hint.setPadding(0, dp(this,6), 0, dp(this,6));
        foot.addView(hint, lpw(1));

        TextView pen = text(this, "✏️", 12, true, TXT);
        pen.setGravity(Gravity.CENTER);
        pen.setBackground(glass(this, GLASS, 9, LINE));
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
            // โหมด Card: คัดลอกแล้วย่อกลับเป็นฟองทันที พร้อมวางตอบลูกค้า
            if (mode == MODE_CARD) posUi.postDelayed(() -> { if (panelOpen) closePanel(); }, 150);
        });
        Fx.onHold(card, () -> openCardEditor(idx));
        return card;
    }

    private View addCardTile() {
        LinearLayout t = col(this);
        t.setBackground(glass(this, 0xFFFFF8F4, 18, 0xFFF5B79B));
        t.setPadding(dp(this,9), dp(this,14), dp(this,9), dp(this,14));
        t.setGravity(Gravity.CENTER);
        TextView a = text(this, "＋ เพิ่มการ์ดใหม่", 11.5f, true, ACCENT);
        a.setGravity(Gravity.CENTER);
        t.addView(a);
        TextView b = text(this, "ข้อความ + รูปได้หลายรูป", 10.5f, false, MUTED);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(this,4), 0, 0);
        t.addView(b);
        Fx.onTap(t, () -> openCardEditor(-1));
        return t;
    }

    private void setImg(ImageView iv, String uri) {
        try { iv.setImageURI(Uri.parse(uri)); } catch (Exception ignored) {}
        iv.setBackground(glass(this, 0xFFEEF0F3, 7, 0));
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
        box.setBackground(glass(this, SURFACE, 18, STROKE));
        box.setPadding(dp(this,16), dp(this,14), dp(this,16), dp(this,14));
        box.addView(text(this, "ความใสของแผง", 14, true, TXT));
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
        posUi.postDelayed(this::fitPanel, 80);
        boolean card = mode == MODE_CARD;
        boolean ord = mode == MODE_ORDERS;
        if (segCard != null) {
            segCard.setBackground(card ? glass(this, ACCENT, 10, 0) : null);
            segCard.setTextColor(card ? WHITE : 0xFF6B7280);
        }
        if (segOrders != null) {
            segOrders.setBackground(ord ? glass(this, ACCENT, 10, 0) : null);
            segOrders.setTextColor(ord ? WHITE : 0xFF6B7280);
        }
        segCustomer.setBackground(mode == MsgBuilder.MODE_CUSTOMER ? glass(this, ACCENT, 10, 0) : null);
        segCustomer.setTextColor(mode == MsgBuilder.MODE_CUSTOMER ? WHITE : 0xFF6B7280);
        segStaff.setBackground(mode == MsgBuilder.MODE_STAFF ? glass(this, ACCENT, 10, 0) : null);
        segStaff.setTextColor(mode == MsgBuilder.MODE_STAFF ? WHITE : 0xFF6B7280);
        int vis = mode == MsgBuilder.MODE_STAFF ? View.VISIBLE : View.GONE;
        staffRow.setVisibility(vis);
        if (staffPay != null) staffPay.setVisibility(vis);
        // โหมด Card = โชว์เฉพาะการ์ดคำพูด (แตะเพื่อคัดลอก)
        if (card && !filter.equals("คำพูด")) { filter = "คำพูด"; refreshFilters(); rebuildBody(); }
        else if (ord && !filter.equals(F_ORDERS)) {
            filter = F_ORDERS; refreshFilters(); rebuildBody();
            fetchPosOrders(); posUi.removeCallbacks(posPoll); posUi.postDelayed(posPoll, 6000);
        }
        else if (!card && !ord && (filter.equals("คำพูด") || filter.equals(F_ORDERS))) { filter = "ทั้งหมด"; refreshFilters(); rebuildBody(); }
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
            if (!orderNote.trim().isEmpty()) {
                if (s.length() > 0) s.append("\n");
                s.append("หมายเหตุ : ").append(orderNote.trim());
            }
            return s.toString();
        }
        return Store.prefs(this).getString("payline", "ชำระเงินคนละครึ่งหรือโอนธรรมดาครับ");
    }

    /** ส่งออเดอร์ปัจจุบันขึ้นคลาวด์ให้เครื่อง POS เด้งเป็นฟอง */
    /** เช็คทุก 10 วิ ว่าออเดอร์ที่ส่งจากเครื่องนี้ถูก POS กดเสร็จหรือยัง */
    private void checkDoneOrders() {
        if (!PosSender.ready(this)) return;
        final java.util.Set<String> seen = PosSender.doneSeen(this);
        new Thread(() -> {
            List<PosSender.OrderStatus> got;
            try { got = PosSender.fetchAll(BubbleService.this); } catch (Exception e) { return; }
            final List<PosSender.OrderStatus> res = got;
            posUi.post(() -> {
                posOrders = res;
                long cutoff = PosSender.now() - 12L * 3600000L;
                boolean overdue = false;
                for (PosSender.OrderStatus o : res) {
                    if (o.createdAt <= cutoff) continue;
                    if (!o.status.equals("เสร็จ") && o.minAgo() >= OVERDUE_MIN) overdue = true;
                    if (o.status.equals("เสร็จ") && !seen.contains(o.id) && "mobile".equals(o.by)) {
                        PosSender.markDoneSeen(BubbleService.this, o.id);
                        notifyDone(o.asLog());
                        doneUnseen.add(o.id);
                    }
                }
                setOverdue(overdue);
                if (panelOpen && (filter.equals(F_POS) || filter.equals(F_ORDERS))) rebuildBody();
            });
        }).start();
    }

    /** ฟองแดงกระพริบช้าๆ เมื่อมีออเดอร์ค้างเกิน 50 นาที */
    private void setOverdue(boolean on) {
        if (on != bubbleOverdue && on) {
            try { Toast.makeText(this, "⚠️ มีออเดอร์ค้างเกิน " + OVERDUE_MIN + " นาที", Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
        }
        bubbleOverdue = on;
        refreshBubble();
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
        if (bubbleDisc != null) Fx.bounce(bubbleDisc);
        posBanner = title + "  " + body;
        posBannerAt = System.currentTimeMillis();
        try { Toast.makeText(this, title, Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
    }

    /** กล่อง "แจ้งเก็บกุ้งจากบ่อ" ในหน้าสถานะ POS */
    private View harvestBox() {
        LinearLayout box = col(this);
        box.setBackground(surfaceTint(this, 16, ACCENT));
        box.setPadding(dp(this,12), dp(this,10), dp(this,12), dp(this,10));

        LinearLayout h = row(this);
        h.addView(text(this, "🦐 แจ้งพนักงานเก็บกุ้งจากบ่อ", 13, true, TXT), lpw(1));
        TextView now = button(this, "แจ้งตอนนี้", primary(this, 14), 12.5f);
        now.setPadding(dp(this,14), dp(this,8), dp(this,14), dp(this,8));
        Fx.onTap(now, () -> sendHarvest(false));
        h.addView(now);
        box.addView(h, lp(MATCH, WRAP));

        LinearLayout r = row(this);
        r.addView(text(this, "อัตโนมัติ", 11.5f, false, WHITE_DIM));
        int cur = PosSender.harvestEvery(this);
        int[] opts = {0, 30, 60};
        String[] lbl = {"ปิด", "ทุก 30 นาที", "ทุก 60 นาที"};
        for (int i = 0; i < opts.length; i++) {
            final int v = opts[i];
            TextView c = chip(this, lbl[i], cur == v);
            c.setPadding(dp(this,11), dp(this,6), dp(this,11), dp(this,6));
            LinearLayout.LayoutParams cl = lp(WRAP, WRAP); cl.leftMargin = dp(this, 6);
            Fx.onTap(c, () -> {
                PosSender.setHarvestEvery(this, v);
                if (v > 0 && PosSender.harvestLast(this) == 0) Store.prefs(this).edit().putLong(PosSender.K_HARVEST_LAST, System.currentTimeMillis()).apply();
                rebuildBody();
            });
            r.addView(c, cl);
        }
        LinearLayout.LayoutParams rl = lp(MATCH, WRAP); rl.topMargin = dp(this, 8);
        box.addView(r, rl);

        String st;
        long last = PosSender.harvestLast(this);
        if (PosSender.harvestAt > 0) {
            boolean acked = PosSender.harvestAck >= PosSender.harvestAt;
            String when = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(PosSender.harvestAt - PosSender.offset));
            st = "ล่าสุด " + when + " น.  •  " + (acked ? "✅ POS รับทราบแล้ว" : "🔔 รอ POS กดรับทราบ");
        } else if (last > 0) {
            st = "ส่งล่าสุด " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(last)) + " น.";
        } else st = "ยังไม่เคยแจ้ง";
        if (cur > 0) {
            long next = Math.max(0, (last + cur * 60000L) - System.currentTimeMillis()) / 60000L;
            st += "  •  ครั้งถัดไปอีก " + next + " นาที";
        }
        TextView stv = text(this, st, 11, false, PosSender.harvestAt > 0 && PosSender.harvestAck < PosSender.harvestAt ? AMBER : WHITE_DIM);
        stv.setPadding(0, dp(this, 6), 0, 0);
        box.addView(stv);
        return box;
    }

    private void sendHarvest(boolean auto) {
        new Thread(() -> {
            String r;
            try { PosSender.harvestNow(BubbleService.this); r = auto ? "🦐 แจ้งเก็บกุ้งอัตโนมัติแล้ว" : "🦐 แจ้ง POS ให้เก็บกุ้งแล้ว"; }
            catch (Exception e) { r = "❌ แจ้งไม่สำเร็จ: " + e.getMessage(); }
            final String m = r;
            posUi.post(() -> {
                try { Toast.makeText(BubbleService.this, m, Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
                if (panelOpen && filter.equals(F_POS)) { fetchPosOrders(); rebuildBody(); }
            });
        }).start();
    }

    private void refreshPosBtn() {
        if (posBtn == null) return;
        boolean e = editingId != null;
        posBtn.setText(e ? "✏️  อัปเดตออเดอร์" : "🧾  ส่งเข้า POS");
        posBtn.setBackground(glass(this, e ? LAVENDER_SOFT : MINT_SOFT, 16, e ? LAVENDER : MINT));
        posBtn.setTextColor(e ? LAVENDER : MINT);
    }

    /** โหลดออเดอร์ที่ส่งไปแล้วกลับเข้าตะกร้าเพื่อแก้ไข */
    private void loadForEdit(PosSender.SendLog l) {
        MsgBuilder.clear(cats);
        shipFee = -1; payChannel = ""; payStatus = ""; orderNote = "";
        editingPrevText = l.lines;
        editingEdits = l.edits;
        java.util.regex.Pattern item = java.util.regex.Pattern.compile("^(.*?)(?: x(\\d+))? (\\d+) บาท(?: \\((.*)\\))?$");
        java.util.regex.Pattern fee = java.util.regex.Pattern.compile("(\\d+) บาท$");
        int missed = 0;
        for (String raw : l.lines.split("\\n")) {
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("order") || t.startsWith("ยอดรวม")) continue;
            if (t.startsWith("ช่องทางชำระ")) { int i = t.indexOf(":"); if (i > 0) payChannel = t.substring(i + 1).trim(); continue; }
            if (t.startsWith("สถานะ")) { int i = t.indexOf(":"); if (i > 0) payStatus = t.substring(i + 1).trim(); continue; }
            if (t.startsWith("หมายเหตุ")) { int i = t.indexOf(":"); if (i > 0) orderNote = t.substring(i + 1).trim(); continue; }
            if (t.startsWith("ส่งฟรี")) { shipFee = 0; continue; }
            if (t.startsWith("ค่าส่ง")) {
                java.util.regex.Matcher fm = fee.matcher(t);
                if (fm.find()) { try { shipFee = Integer.parseInt(fm.group(1)); } catch (Exception ignored) {} }
                continue;
            }
            java.util.regex.Matcher m = item.matcher(t);
            if (!m.find()) { missed++; continue; }
            String name = m.group(1).trim();
            int qty = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            int lineTotal = Integer.parseInt(m.group(3));
            String note = m.group(4) == null ? "" : m.group(4).trim();
            MenuData.Item found = null;
            for (MenuData.Cat c : cats) {
                if (c.name.equals("ค่าส่ง")) continue;
                for (MenuData.Item it : c.items) if (it.name.trim().equals(name)) { found = it; break; }
                if (found != null) break;
            }
            if (found == null) { missed++; continue; }
            found.qty = qty;
            found.note = note;
            if (found.custom && qty > 0) found.price = lineTotal / qty;
        }
        editingId = l.id;
        editingLabel = (l.no.isEmpty() ? "ออเดอร์" : "ออเดอร์ #" + l.no) + (l.place.isEmpty() ? "" : " • " + l.place);
        mode = MsgBuilder.MODE_STAFF;
        filter = "ทั้งหมด";
        if (orderNoInput != null) orderNoInput.setText(l.no);
        setPlace(l.place, null);
        if (placeInput != null) placeInput.setText(l.place);
        refreshSeg(); refreshFilters(); buildPayRows(); refreshPosBtn();
        rebuildBody(); refreshTotal();
        posUi.removeCallbacks(posPoll);
        Toast.makeText(this, missed > 0 ? "โหลดออเดอร์แล้ว (มี " + missed + " รายการที่หาเมนูไม่เจอ)" : "โหลดออเดอร์มาแก้ไขแล้ว", Toast.LENGTH_LONG).show();
    }

    private boolean sending = false;          // กำลังส่งอยู่ — กันกดซ้ำ
    private String pendingSendId = null;      // id ที่ใช้ส่งครั้งล่าสุดที่ยังไม่สำเร็จ (ส่งใหม่จะทับ id เดิม ไม่เกิดออเดอร์ซ้ำ)

    private View confirmBox = null;

    /** กด "ส่งเข้า POS" → เปิดป๊อปอัปพรีวิวให้ตรวจก่อน แล้วค่อยส่งจริง */
    private void sendToPos() {
        if (sending) { Toast.makeText(this, "กำลังส่งอยู่… รอสักครู่", Toast.LENGTH_SHORT).show(); return; }
        if (!PosSender.ready(this)) {
            Toast.makeText(this, "ยังไม่ได้ตั้งค่าลิงก์ POS — เปิดแอป Messes Sale แล้วใส่ลิงก์ฐานข้อมูลก่อน",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (MsgBuilder.total(cats, shipFee) <= 0) {
            Toast.makeText(this, "ยังไม่ได้เลือกเมนู", Toast.LENGTH_SHORT).show();
            return;
        }
        if (confirmBox != null) return;
        final boolean isEdit = editingId != null && !editingId.isEmpty();
        final String dest = place.isEmpty() ? placeInput.getText().toString().trim() : place;
        final String no = orderNoInput.getText().toString().trim();
        final String msg = MsgBuilder.build(cats, MsgBuilder.MODE_STAFF, no, dest, payLine(), place, shipFee);
        final int total = MsgBuilder.total(cats, shipFee);

        LinearLayout box = col(this);
        box.setBackground(glass(this, SURFACE, 18, STROKE));
        box.setPadding(dp(this,16), dp(this,14), dp(this,16), dp(this,14));
        box.setElevation(dp(this, 20));
        box.addView(text(this, isEdit ? "✏️ ตรวจสอบก่อนอัปเดตออเดอร์" : "🧾 ตรวจสอบก่อนส่งเข้า POS", 16, true, TXT));
        TextView hd = text(this, (dest.isEmpty() ? "⚠️ ยังไม่ได้ใส่ที่อยู่ส่ง" : "📍 " + dest) + (no.isEmpty() ? "" : "  ·  order ที่ " + no), 14, true, dest.isEmpty() ? RED : ACCENT);
        hd.setPadding(0, dp(this, 6), 0, dp(this, 8));
        box.addView(hd);

        LinearLayout list = col(this);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        java.util.regex.Pattern linePat = java.util.regex.Pattern.compile("^(.*?)(?:\\s+[xX×]\\s*(\\d+))?\\s+(\\d[\\d,]*)\\s*บาท(?:\\s*\\((.*)\\))?\\s*$");
        int n = 0;
        for (String raw : msg.split("\n")) {
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("order") || t.startsWith("ยอดรวม")) continue;
            boolean isNote = t.startsWith("หมายเหตุ"), isPay = t.startsWith("ช่องทางชำระ") || t.startsWith("สถานะ");
            boolean ship = t.startsWith("ค่าส่ง") || t.startsWith("ส่งฟรี");
            LinearLayout r = row(this);
            r.setGravity(Gravity.TOP);
            r.setPadding(0, dp(this, 4), 0, dp(this, 4));
            if (isNote || isPay) {
                TextView tv = text(this, (isNote ? "📝 " : "💰 ") + t, 12.5f, isNote, isNote ? 0xFF92400E : MINT);
                if (isNote) { tv.setBackground(glass(this, AMBER_SOFT, 6, 0)); tv.setPadding(dp(this,8), dp(this,3), dp(this,8), dp(this,3)); }
                r.addView(tv, lpw(1));
            } else {
                String name = t, qty = "1", price = "", note = "";
                java.util.regex.Matcher m = linePat.matcher(t);
                if (m.find()) { name = m.group(1); if (m.group(2) != null) qty = m.group(2); price = m.group(3); if (m.group(4) != null) note = m.group(4); }
                TextView q = text(this, ship ? "🛵" : qty + "×", 14, true, ship ? MUTED : ACCENT);
                q.setMinWidth(dp(this, 32));
                r.addView(q);
                LinearLayout nc = col(this);
                nc.addView(text(this, name, 14, !ship, ship ? MUTED : TXT));
                if (!note.isEmpty()) { TextView nt = text(this, "📝 " + note, 11.5f, true, 0xFF92400E); nt.setBackground(glass(this, AMBER_SOFT, 5, 0)); nt.setPadding(dp(this,6), dp(this,1), dp(this,6), dp(this,1)); LinearLayout.LayoutParams ntl = lp(WRAP, WRAP); ntl.topMargin = dp(this, 2); nc.addView(nt, ntl); }
                r.addView(nc, lpw(1));
                if (!price.isEmpty()) r.addView(text(this, price, 13.5f, false, WHITE_DIM));
                if (!ship) n++;
            }
            list.addView(r, lp(MATCH, WRAP));
            View dv = new View(this); dv.setBackgroundColor(LINE); list.addView(dv, lp(MATCH, dp(this, 1)));
        }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        box.addView(sv, lp(MATCH, Math.min((int) (dm.heightPixels * 0.42f), dp(this, 340))));

        LinearLayout tot = row(this);
        tot.setPadding(0, dp(this, 10), 0, dp(this, 10));
        tot.addView(text(this, "รวม " + n + " รายการ", 13.5f, true, TXT), lpw(1));
        tot.addView(text(this, total + " บาท", 20, true, MINT));
        box.addView(tot, lp(MATCH, WRAP));

        LinearLayout btns = row(this);
        TextView cancel = button(this, "✕ กลับไปแก้", glass(this, SURFACE, 12, STROKE), 14);
        Fx.onTap(cancel, this::closeConfirm);
        btns.addView(cancel, lpw(1));
        TextView ok = button(this, isEdit ? "✅ ยืนยันอัปเดต" : "✅ ยืนยันส่งเข้า POS", glass(this, MINT, 12, 0), 15);
        LinearLayout.LayoutParams okl = lpw(1.4f); okl.leftMargin = dp(this, 8);
        Fx.onTap(ok, () -> { closeConfirm(); doSendToPos(); });
        btns.addView(ok, okl);
        box.addView(btns, lp(MATCH, WRAP));

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                Math.min(dm.widthPixels - dp(this, 24), dp(this, 420)), WRAP, wtype(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.CENTER;
        box.setFocusableInTouchMode(true);
        box.setOnKeyListener((v, code, ev) -> { if (code == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) { closeConfirm(); return true; } return false; });
        confirmBox = box;
        wm.addView(box, p);
        box.requestFocus();
    }

    private void closeConfirm() {
        if (confirmBox != null) { try { wm.removeView(confirmBox); } catch (Exception ignored) {} confirmBox = null; }
    }

    /** ส่งจริง (หลังยืนยันในป๊อปอัป) */
    private void doSendToPos() {
        if (sending) return;
        final String no = orderNoInput.getText().toString();
        final String dest = place.isEmpty() ? placeInput.getText().toString() : place;
        final String msg = MsgBuilder.build(cats, MsgBuilder.MODE_STAFF,
                no, dest, payLine(), place, shipFee);
        final int total = MsgBuilder.total(cats, shipFee);
        final String pay = payLine();

        final String editId = (editingId == null || editingId.isEmpty()) ? null : editingId;
        boolean reopenTmp = false;
        if (editId != null) for (PosSender.OrderStatus o : posOrders) if (o.id.equals(editId) && o.status.equals("เสร็จ")) reopenTmp = true;
        final boolean reopen = reopenTmp;

        Toast.makeText(this, editId != null ? "กำลังอัปเดตออเดอร์…" : "กำลังส่งเข้าเครื่อง POS…", Toast.LENGTH_SHORT).show();
        sending = true;
        if (posBtn != null) { posBtn.setEnabled(false); posBtn.setAlpha(0.6f); posBtn.setText("⏳  กำลังส่ง…"); }
        if (editId == null && pendingSendId == null) pendingSendId = PosSender.newOrderId();
        final String sendId = pendingSendId;
        new Thread(() -> {
            String result, key = "", err = "";
            boolean ok = false;
            try {
                if (editId != null) {
                    PosSender.update(BubbleService.this, editId, no, dest, msg, total, pay, reopen, editingPrevText, editingEdits + 1);
                    key = editId;
                    result = "✅ อัปเดตออเดอร์แล้ว — รอ POS กดรับทราบ";
                } else {
                    key = PosSender.send(BubbleService.this, sendId, no, dest, msg, total, pay);
                    result = "✅ ส่งเข้าเครื่อง POS แล้ว";
                }
                ok = true;
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
                String why = err.contains("timeout") || err.contains("Timeout") ? "เน็ตช้า/หลุด — เช็คเน็ตแล้วกดส่งอีกครั้งได้ ระบบกันออเดอร์ซ้ำให้แล้ว" : "เช็คเน็ตแล้วกดส่งอีกครั้ง (ระบบกันออเดอร์ซ้ำให้แล้ว)";
                result = (editId != null ? "❌ อัปเดตไม่สำเร็จ: " : "❌ ส่งไม่สำเร็จ: ") + why + "\n" + err;
            }
            if (editId != null && ok) PosSender.logEdit(BubbleService.this, editId, no, dest, total, msg, pay);
            else if (editId == null) PosSender.logAdd(BubbleService.this, key, no, dest, total, ok, err, msg, pay);
            final String r = result;
            final boolean sent = ok;
            posUi.post(() -> {
                sending = false;
                if (posBtn != null) { posBtn.setEnabled(true); posBtn.setAlpha(1f); refreshPosBtn(); }
                posBanner = r;
                posBannerAt = System.currentTimeMillis();
                if (sent) {
                    pendingSendId = null;
                    editingId = null; editingLabel = ""; editingPrevText = ""; orderNote = "";
                    MsgBuilder.clear(cats); shipFee = -1;
                    payChannel = ""; payStatus = "";
                    // ล้างช่องกรอกให้พร้อมรับออเดอร์ถัดไป
                    try {
                        if (orderNoInput != null) orderNoInput.setText("");
                        if (orderNoteInput != null) orderNoteInput.setText("");
                        setPlace("", null);
                    } catch (Exception ignored) {}
                    if (staffPay != null) buildPayRows();
                    refreshPosBtn(); refreshTotal();
                }
                try { Toast.makeText(BubbleService.this, r, Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
                if (panelOpen) {
                    if (sent) {
                        // ส่งสำเร็จ → ยุบกลับเป็นฟองทันที (ฟองจะโชว์สถานะ "รอ POS รับ" เอง)
                        filter = F_POS;
                        closePanel();
                        checkDoneOrders();
                    } else {
                        // ไม่สำเร็จ → เด้งไปหน้าสถานะให้เห็นสาเหตุ
                        filter = F_POS;
                        refreshFilters();
                        rebuildBody();
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
        posUi.removeCallbacks(harvestTick);
        posUi.removeCallbacks(updateCheck);
        closePanel();
        if (bubbleView != null) { try { wm.removeView(bubbleView); } catch (Exception ignored) {} }
    }
}
