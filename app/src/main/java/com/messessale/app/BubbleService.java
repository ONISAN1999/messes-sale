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
    // ---- Pre Order (สั่งล่วงหน้า) ----
    private boolean preOn = false;
    private int preDayOff = 1;              // 0 วันนี้ 1 พรุ่งนี้ …
    private String preTime = "17:00";
    private String preType = "ส่ง";
    private int preDeposit = 0;             // 0 = ยังไม่มัดจำ
    private boolean prePaidFull = false;
    private boolean preCustomTime = false, preCustomDep = false;
    private static final int PRE_PREP_MIN = 45;
    private final java.util.Map<String, String> prevStatus = new java.util.HashMap<>();   // ไว้จับตอน พรี → ใหม่ (POS เริ่มทำ)
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
        int preSoon = 0;
        for (PosSender.OrderStatus o : posOrders) {
            if (o.isPre()) { if (o.preAt - PosSender.now() < 3600000L) preSoon++; continue; }
            if (o.createdAt <= cutoff) continue;
            if (o.isClosed()) { if (doneUnseen.contains(o.id)) doneNew++; continue; }
            active++;
            if (o.status.equals("ใหม่")) waiting++;
            if (o.minAgo() >= OVERDUE_MIN || o.preLate()) overdue = true;
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
            default: g.setColor(WHITE); g.setStroke(dp(this, 3), preSoon > 0 ? LAVENDER : ACCENT); label = preSoon > 0 ? "📅 พรี " + preSoon : active > 0 ? active + " ออเดอร์" : "Messes"; txt = preSoon > 0 ? LAVENDER : TXT; bubbleIcon.setColorFilter(preSoon > 0 ? LAVENDER : ACCENT); break;
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
        final EditText placeStaff = input(this, "📍 จุดส่ง (แตะเพื่อเลือก)");
        placeInput = placeStaff;
        placeStaff.setText(place);
        placeStaff.setFocusable(false); placeStaff.setClickable(true);
        placeStaff.setOnClickListener(v -> openPlacePicker());
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
        staffPay = col(this);   // จะไปอยู่ในส่วนเลื่อน (ด้านบนรายการเมนู) เพื่อให้ปุ่มล่างไม่ถูกดันหลุดจอ
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
        LinearLayout scrollCol = col(this);
        LinearLayout.LayoutParams spLp = lp(MATCH, WRAP); spLp.bottomMargin = dp(this, 10);
        scrollCol.addView(staffPay, spLp);
        scrollCol.addView(bodyBox, lp(MATCH, WRAP));
        sv.addView(scrollCol);
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
                int target = Math.max(160, cur - (int) (over / getResources().getDisplayMetrics().density) - 4);
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

    /** เวลานัด (มิลลิวินาที ตามเวลาเซิร์ฟเวอร์) จากวัน+เวลาที่เลือก; 0 = ไม่ใช่พรีออเดอร์ */
    private long preAtMillis() {
        if (!preOn) return 0;
        try {
            String[] hm = preTime.trim().split("[:.]");
            int h = Integer.parseInt(hm[0].trim()), m = hm.length > 1 ? Integer.parseInt(hm[1].trim()) : 0;
            if (h < 0 || h > 23 || m < 0 || m > 59) return 0;
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.add(java.util.Calendar.DAY_OF_YEAR, preDayOff);
            c.set(java.util.Calendar.HOUR_OF_DAY, h); c.set(java.util.Calendar.MINUTE, m);
            c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
            return c.getTimeInMillis() + PosSender.offset;
        } catch (Exception e) { return 0; }
    }

    private static final String[] TH_DAY = {"อา.", "จ.", "อ.", "พ.", "พฤ.", "ศ.", "ส."};
    private static final String[] TH_MON = {"ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค."};

    /** "วันนี้" / "พรุ่งนี้ (พ. 21 ต.ค.)" / "พ. 21 ต.ค." จากเวลาเซิร์ฟเวอร์ */
    private String dayLabel(long serverMs) {
        java.util.Calendar c = java.util.Calendar.getInstance(); c.setTimeInMillis(serverMs - PosSender.offset);
        java.util.Calendar t = java.util.Calendar.getInstance();
        int diff = (int) Math.round((startOfDay(c) - startOfDay(t)) / 86400000.0);
        String d = TH_DAY[c.get(java.util.Calendar.DAY_OF_WEEK) - 1] + " " + c.get(java.util.Calendar.DAY_OF_MONTH) + " " + TH_MON[c.get(java.util.Calendar.MONTH)];
        if (diff == 0) return "วันนี้";
        if (diff == 1) return "พรุ่งนี้ (" + d + ")";
        return d;
    }
    private static long startOfDay(java.util.Calendar c) {
        java.util.Calendar x = (java.util.Calendar) c.clone();
        x.set(java.util.Calendar.HOUR_OF_DAY, 0); x.set(java.util.Calendar.MINUTE, 0); x.set(java.util.Calendar.SECOND, 0); x.set(java.util.Calendar.MILLISECOND, 0);
        return x.getTimeInMillis();
    }
    private String clockOf(long serverMs) {
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(serverMs - PosSender.offset));
    }
    /** "อีก 2 ชม. 15 น." / "เลยเวลา 10 น." */
    private String untilLabel(long serverMs) {
        long d = serverMs - PosSender.now();
        boolean late = d < 0; d = Math.abs(d) / 60000L;
        String t = d >= 60 ? (d / 60) + " ชม. " + (d % 60 > 0 ? (d % 60) + " น." : "") : d + " น.";
        return (late ? "เลยเวลา " : "อีก ") + t.trim();
    }

    /** บล็อก "สั่งล่วงหน้า" ในโหมดแจ้งพนักงาน */
    private View preBlock() {
        LinearLayout box = col(this);
        box.setBackground(glass(this, preOn ? 0xFFF7F4FF : SURFACE, 14, preOn ? LAVENDER : LINE));
        box.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        LinearLayout tog = row(this);
        TextView sw = text(this, preOn ? "●" : "○", 22, true, preOn ? LAVENDER : MUTED);
        sw.setPadding(0, 0, dp(this, 10), 0);
        tog.addView(sw);
        LinearLayout tc = col(this);
        tc.addView(text(this, "📅 สั่งล่วงหน้า (Pre Order)", 14, true, preOn ? LAVENDER : TXT));
        tc.addView(text(this, preOn ? "POS จะเริ่มเตรียมก่อนนัด " + PRE_PREP_MIN + " นาที (เตือนเสียงตอนนั้น)" : "แตะเพื่อตั้งวัน-เวลานัดรับ/ส่ง", 11, false, WHITE_DIM));
        tog.addView(tc, lpw(1));
        Fx.onTap(tog, () -> { preOn = !preOn; buildPayRows(); refreshPreview(); });
        box.addView(tog, lp(MATCH, WRAP));
        if (!preOn) return box;

        // วันที่
        box.addView(lbl("วันที่"));
        LinearLayout dr = row(this);
        android.widget.HorizontalScrollView dsv = new android.widget.HorizontalScrollView(this); dsv.setHorizontalScrollBarEnabled(false);
        for (int i = 0; i < 7; i++) {
            final int off = i;
            java.util.Calendar c = java.util.Calendar.getInstance(); c.add(java.util.Calendar.DAY_OF_YEAR, i);
            String dn = TH_DAY[c.get(java.util.Calendar.DAY_OF_WEEK) - 1] + " " + c.get(java.util.Calendar.DAY_OF_MONTH);
            String nm = i == 0 ? "วันนี้" : i == 1 ? "พรุ่งนี้ (" + dn + ")" : dn + " " + TH_MON[c.get(java.util.Calendar.MONTH)];
            TextView ch = preChip(nm, preDayOff == off);
            Fx.onTap(ch, () -> { preDayOff = off; buildPayRows(); refreshPreview(); });
            LinearLayout.LayoutParams cl = lp(WRAP, WRAP); cl.rightMargin = dp(this, 6);
            dr.addView(ch, cl);
        }
        dsv.addView(dr); box.addView(dsv, lp(MATCH, WRAP));

        // เวลา
        box.addView(lbl("เวลา"));
        LinearLayout tr = row(this);
        android.widget.HorizontalScrollView tsv = new android.widget.HorizontalScrollView(this); tsv.setHorizontalScrollBarEnabled(false);
        for (String t : new String[]{"10:00", "11:00", "12:00", "13:00", "16:00", "17:00", "18:00", "19:00", "20:00"}) {
            final String v = t;
            TextView ch = preChip(t, !preCustomTime && preTime.equals(t));
            Fx.onTap(ch, () -> { preTime = v; preCustomTime = false; buildPayRows(); refreshPreview(); });
            LinearLayout.LayoutParams cl = lp(WRAP, WRAP); cl.rightMargin = dp(this, 6);
            tr.addView(ch, cl);
        }
        TextView oth = preChip(preCustomTime ? "⏰ " + preTime : "⏰ เลือกเวลาเอง", preCustomTime);
        Fx.onTap(oth, this::openTimePicker);
        tr.addView(oth);
        tsv.addView(tr); box.addView(tsv, lp(MATCH, WRAP));

        // รับยังไง
        box.addView(lbl("รับยังไง"));
        LinearLayout yr = row(this);
        for (String t : new String[]{"🏠 รับเอง", "🛵 ส่ง"}) {
            final String v = t.contains("รับเอง") ? "รับเอง" : "ส่ง";
            TextView ch = preChip(t + (v.equals("ส่ง") && !place.isEmpty() ? " " + place : ""), preType.equals(v));
            Fx.onTap(ch, () -> { preType = v; buildPayRows(); refreshPreview(); });
            LinearLayout.LayoutParams cl = lp(WRAP, WRAP); cl.rightMargin = dp(this, 6);
            yr.addView(ch, cl);
        }
        box.addView(yr, lp(MATCH, WRAP));

        // มัดจำ
        box.addView(lbl("มัดจำ"));
        LinearLayout mr = row(this);
        TextView d0 = preChip("ยังไม่มัดจำ", preDeposit == 0 && !prePaidFull && !preCustomDep);
        Fx.onTap(d0, () -> { preDeposit = 0; prePaidFull = false; preCustomDep = false; buildPayRows(); refreshPreview(); });
        mr.addView(d0, lp(WRAP, WRAP));
        TextView d1 = preChip(preDeposit > 0 && !prePaidFull ? "มัดจำ " + preDeposit : "มัดจำ (ใส่ยอด)", (preDeposit > 0 && !prePaidFull) || preCustomDep);
        Fx.onTap(d1, () -> { preCustomDep = true; prePaidFull = false; buildPayRows(); });
        LinearLayout.LayoutParams d1l = lp(WRAP, WRAP); d1l.leftMargin = dp(this, 6); mr.addView(d1, d1l);
        TextView d2 = preChip("ชำระครบแล้ว", prePaidFull);
        Fx.onTap(d2, () -> { prePaidFull = true; preCustomDep = false; buildPayRows(); refreshPreview(); });
        LinearLayout.LayoutParams d2l = lp(WRAP, WRAP); d2l.leftMargin = dp(this, 6); mr.addView(d2, d2l);
        box.addView(mr, lp(MATCH, WRAP));
        if (preCustomDep) {
            LinearLayout qr = row(this);
            android.widget.HorizontalScrollView qsv = new android.widget.HorizontalScrollView(this); qsv.setHorizontalScrollBarEnabled(false);
            for (int amt : new int[]{100, 200, 300, 500, 1000}) {
                final int a = amt;
                TextView ch = preChip(String.valueOf(a), preDeposit == a);
                Fx.onTap(ch, () -> { preDeposit = a; buildPayRows(); refreshPreview(); });
                LinearLayout.LayoutParams cl = lp(WRAP, WRAP); cl.rightMargin = dp(this, 6);
                qr.addView(ch, cl);
            }
            qsv.addView(qr);
            LinearLayout.LayoutParams ql = lp(MATCH, WRAP); ql.topMargin = dp(this, 6);
            box.addView(qsv, ql);
            final EditText de = input(this, "หรือพิมพ์ยอดมัดจำ (บาท)");
            de.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            if (preDeposit > 0) de.setText(String.valueOf(preDeposit));
            de.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence c,int a,int b,int d) {}
                @Override public void onTextChanged(CharSequence c,int a,int b,int d) { try { preDeposit = Integer.parseInt(c.toString().trim()); } catch (Exception e) { preDeposit = 0; } refreshPreview(); }
                @Override public void afterTextChanged(Editable e) {}
            });
            watchKeyboard(de);
            LinearLayout.LayoutParams dl = lp(MATCH, WRAP); dl.topMargin = dp(this, 6);
            box.addView(de, dl);
        }
        long at = preAtMillis();
        TextView sum = text(this, at > 0 ? "นัด " + dayLabel(at) + " " + clockOf(at) + " น. · " + preType + " · POS เริ่มเตรียม " + clockOf(at - PRE_PREP_MIN * 60000L) : "⚠️ เวลาไม่ถูกต้อง (เช่น 15:30)", 12, true, at > 0 ? LAVENDER : RED);
        sum.setPadding(0, dp(this, 8), 0, 0);
        box.addView(sum);
        return box;
    }

    /** ป๊อปอัปเลือกเวลาแบบแตะ ไม่ใช้แป้นพิมพ์: ชั่วโมง + นาที */
    private View timeBox = null;
    private void openTimePicker() {
        if (timeBox != null) return;
        String[] hm = preTime.split("[:.]");
        final int[] sel = {17, 0};
        try { sel[0] = Integer.parseInt(hm[0].trim()); sel[1] = hm.length > 1 ? Integer.parseInt(hm[1].trim()) : 0; } catch (Exception ignored) {}
        sel[1] = (sel[1] / 15) * 15;
        final LinearLayout box = col(this);
        box.setBackground(glass(this, SURFACE, 18, STROKE));
        box.setPadding(dp(this,16), dp(this,14), dp(this,16), dp(this,14));
        box.setElevation(dp(this, 20));
        final TextView head = text(this, "⏰ เลือกเวลานัด", 15, true, TXT);
        box.addView(head);
        final TextView big = text(this, "", 30, true, LAVENDER);
        big.setGravity(Gravity.CENTER); big.setPadding(0, dp(this, 6), 0, dp(this, 6));
        box.addView(big, lp(MATCH, WRAP));
        // วงล้อเลื่อน: ชั่วโมง 9–22 · นาทีทีละ 5
        sel[1] = (sel[1] / 5) * 5;
        final android.widget.NumberPicker hp = new android.widget.NumberPicker(this);
        hp.setMinValue(9); hp.setMaxValue(22); hp.setValue(Math.max(9, Math.min(22, sel[0]))); hp.setWrapSelectorWheel(true);
        hp.setDescendantFocusability(android.widget.NumberPicker.FOCUS_BLOCK_DESCENDANTS);   // ไม่ให้แป้นพิมพ์เด้ง
        final String[] mv = new String[12];
        for (int i = 0; i < 12; i++) mv[i] = String.format(java.util.Locale.US, "%02d", i * 5);
        final android.widget.NumberPicker mp = new android.widget.NumberPicker(this);
        mp.setMinValue(0); mp.setMaxValue(11); mp.setDisplayedValues(mv); mp.setValue(sel[1] / 5); mp.setWrapSelectorWheel(true);
        mp.setDescendantFocusability(android.widget.NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        final Runnable upd = () -> { sel[0] = hp.getValue(); sel[1] = mp.getValue() * 5; big.setText(String.format(java.util.Locale.US, "%02d:%02d", sel[0], sel[1])); };
        hp.setOnValueChangedListener((pk, o, n) -> upd.run());
        mp.setOnValueChangedListener((pk, o, n) -> upd.run());
        upd.run();
        LinearLayout wheel = row(this);
        wheel.setGravity(Gravity.CENTER);
        LinearLayout hc = col(this); hc.setGravity(Gravity.CENTER);
        hc.addView(text(this, "ชั่วโมง", 11, true, WHITE_DIM)); hc.addView(hp, lp(dp(this, 110), dp(this, 150)));
        wheel.addView(hc);
        TextView colon = text(this, ":", 30, true, LAVENDER); colon.setPadding(dp(this, 10), dp(this, 14), dp(this, 10), 0);
        wheel.addView(colon);
        LinearLayout mc = col(this); mc.setGravity(Gravity.CENTER);
        mc.addView(text(this, "นาที", 11, true, WHITE_DIM)); mc.addView(mp, lp(dp(this, 110), dp(this, 150)));
        wheel.addView(mc);
        LinearLayout.LayoutParams wl = lp(MATCH, WRAP); wl.topMargin = dp(this, 4);
        box.addView(wheel, wl);
        LinearLayout btns = row(this);
        TextView cancel = button(this, "ยกเลิก", glass(this, SURFACE, 12, STROKE), 14);
        Fx.onTap(cancel, this::closeTimePicker);
        btns.addView(cancel, lpw(1));
        TextView ok = button(this, "✓ ใช้เวลานี้", glass(this, LAVENDER, 12, 0), 15);
        LinearLayout.LayoutParams okl = lpw(1.5f); okl.leftMargin = dp(this, 8);
        Fx.onTap(ok, () -> {
            preTime = String.format(java.util.Locale.US, "%02d:%02d", sel[0], sel[1]);
            preCustomTime = !java.util.Arrays.asList("10:00", "11:00", "12:00", "13:00", "16:00", "17:00", "18:00", "19:00", "20:00").contains(preTime);
            closeTimePicker(); buildPayRows(); refreshPreview();
        });
        btns.addView(ok, okl);
        LinearLayout.LayoutParams bl = lp(MATCH, WRAP); bl.topMargin = dp(this, 12);
        box.addView(btns, bl);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(Math.min(dm.widthPixels - dp(this, 24), dp(this, 400)), WRAP, wtype(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.CENTER;
        box.setFocusableInTouchMode(true);
        box.setOnKeyListener((v, code, ev) -> { if (code == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) { closeTimePicker(); return true; } return false; });
        timeBox = box;
        wm.addView(box, p);
        box.requestFocus();
    }
    private void closeTimePicker() { if (timeBox != null) { try { wm.removeView(timeBox); } catch (Exception ignored) {} timeBox = null; } }

    private TextView lbl(String t) { TextView v = text(this, t, 11, true, WHITE_DIM); v.setPadding(0, dp(this, 8), 0, dp(this, 5)); return v; }
    private TextView preChip(String t, boolean on) {
        TextView c = text(this, t, 12, true, on ? LAVENDER : TXT);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(this, 11), dp(this, 7), dp(this, 11), dp(this, 7));
        c.setBackground(glass(this, on ? LAVENDER_SOFT : SURFACE, 20, on ? LAVENDER : LINE));
        return c;
    }

    /** บรรทัดนัด/มัดจำ สำหรับใส่ท้ายข้อความ */
    private String preLines() {
        long at = preAtMillis();
        if (at <= 0) return "";
        StringBuilder s = new StringBuilder();
        s.append("นัด").append(preType.equals("ส่ง") ? "ส่ง" : "รับ").append(" : ").append(dayLabel(at)).append(" ").append(clockOf(at)).append(" น.");
        int tot = MsgBuilder.total(cats, shipFee);
        if (prePaidFull) s.append("\nมัดจำ : ชำระครบแล้ว");
        else if (preDeposit > 0) s.append("\nมัดจำ : ").append(preDeposit).append(" บาท (คงเหลือ ").append(Math.max(0, tot - preDeposit)).append(")");
        else s.append("\nมัดจำ : ยังไม่มัดจำ");
        return s.toString();
    }

    /** แถวชิป: ช่องทางที่ลูกค้าชำระ + สถานะชำระ */
    private void buildPayRows() {
        staffPay.removeAllViews();
        staffPay.addView(payRow("ช่องทางที่ลูกค้าชำระ", PAY_CHANNELS, true), lp(MATCH, WRAP));
        View gap = new View(this);
        staffPay.addView(gap, lp(MATCH, dp(this, 9)));
        staffPay.addView(payRow("สถานะชำระ", PAY_STATUS, false), lp(MATCH, WRAP));
        LinearLayout.LayoutParams pbl = lp(MATCH, WRAP); pbl.topMargin = dp(this, 9);
        staffPay.addView(preBlock(), pbl);

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
        closeConfirm(); closeTimePicker(); closePlacePicker();
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
            if (o.isPre() || o.status.equals("ยกเลิก")) continue;   // พรีออเดอร์ยังไม่ถึงเวลา / ยกเลิก ไม่เข้าคิวส่ง
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
    private int posSeg = 0;                 // 0 วันนี้ / 1 พรีออเดอร์ / 2 เสร็จแล้ว
    private String cancelArm = null;        // การ์ดพรีออเดอร์ที่กดยกเลิกครั้งแรกแล้ว (รอยืนยัน)
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
        List<PosSender.OrderStatus> waiting = new ArrayList<>(), doing = new ArrayList<>(), done = new ArrayList<>(), pre = new ArrayList<>();
        for (PosSender.OrderStatus o : posOrders) {
            if (o.isClosed()) done.add(o);
            else if (o.isPre()) pre.add(o);
            else if (o.status.equals("ใหม่") || o.waitingAck()) waiting.add(o);
            else doing.add(o);
        }
        java.util.Collections.sort(pre, (x, y) -> Long.compare(x.preAt, y.preAt));
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
        String[] segNames = {"วันนี้ (" + (waiting.size() + doing.size() + failed.size()) + ")", "📅 พรี (" + pre.size() + ")", "เสร็จแล้ว (" + done.size() + ")"};
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

        boolean showActive = posSeg == 0, showDone = posSeg != 1;
        if (posSeg == 1) {
            if (pre.isEmpty()) {
                TextView em = text(this, "ยังไม่มีพรีออเดอร์", 13, false, WHITE_DIM);
                em.setPadding(dp(this,2), dp(this,6), 0, dp(this,8));
                bodyBox.addView(em);
            }
            String lastDay = "";
            for (PosSender.OrderStatus o : pre) {
                String dl = dayLabel(o.preAt);
                if (!dl.equals(lastDay)) { posSection("📅 " + dl, LAVENDER, ""); lastDay = dl; }
                bodyBox.addView(statusCard(o, true), posCardLp());
            }
        }
        if (showActive) {
            // พรีออเดอร์ที่ถึงกำหนดวันนี้
            List<PosSender.OrderStatus> preToday = new ArrayList<>();
            for (PosSender.OrderStatus o : pre) if (dayLabel(o.preAt).equals("วันนี้")) preToday.add(o);
            if (!preToday.isEmpty()) {
                posSection("📅 พรีออเดอร์วันนี้ (" + preToday.size() + ")", LAVENDER, pre.size() > preToday.size() ? "ทั้งหมด " + pre.size() + " ›" : "");
                for (PosSender.OrderStatus o : preToday) bodyBox.addView(statusCard(o, true), posCardLp());
            }
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
            if (right.startsWith("ดู")) Fx.onTap(rt, () -> { posSeg = 2; rebuildBody(); });
            if (right.startsWith("ทั้งหมด")) Fx.onTap(rt, () -> { posSeg = 1; rebuildBody(); });
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
        boolean isDone = o.isClosed(), isNew = o.status.equals("ใหม่"), ack = o.waitingAck(), isPre = o.isPre();
        int m = o.minAgo();
        boolean late = !isDone && !isPre && (m >= OVERDUE_MIN || o.preLate());
        final String key = o.id;
        final boolean collapsed = isDone ? !key.equals(expandedLogId) : key.equals(expandedLogId);   // ค้าง = กางไว้ก่อน, เสร็จ = ย่อไว้ก่อน

        LinearLayout card = col(this);
        card.setBackground(glass(this, isNew ? 0xFFFFF8F4 : isPre ? 0xFFFBF9FF : SURFACE, 14, late ? RED : isNew ? ACCENT : (ack || isPre) ? LAVENDER : LINE));
        card.setPadding(dp(this,12), dp(this,10), dp(this,12), dp(this,10));
        if (isDone) card.setAlpha(0.8f);

        // ---- หัว ----
        LinearLayout top = row(this);
        LinearLayout.LayoutParams tl = lp(dp(this, isPre ? 58 : 46), dp(this,46)); tl.rightMargin = dp(this, 10);
        if (isPre) {
            LinearLayout pbx = col(this); pbx.setGravity(Gravity.CENTER);
            pbx.setBackground(glass(this, LAVENDER, 11, 0));
            TextView t1 = text(this, o.preClock(), 14, true, WHITE); t1.setGravity(Gravity.CENTER); t1.setIncludeFontPadding(false); pbx.addView(t1);
            TextView t2 = text(this, untilLabel(o.preAt).replace("อีก ", ""), 8.5f, false, WHITE); t2.setGravity(Gravity.CENTER); t2.setSingleLine(true); pbx.addView(t2);
            top.addView(pbx, tl);
        } else top.addView(minuteBox(m, isDone, isNew), tl);
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
        boolean noDep = isPre && o.deposit <= 0 && !o.paidFull;
        String tagTxt = isPre ? (noDep ? "ยังไม่มัดจำ" : "รอถึงเวลา") : late ? (o.preLate() ? "เลยเวลานัด" : "เกิน " + OVERDUE_MIN + " น.") : ack ? "รอ POS รับทราบแก้" : isNew ? "รอ POS รับ" : o.status.equals("ยกเลิก") ? "ยกเลิก" : isDone ? "เสร็จ" : "กำลังทำ";
        int tagBg = isPre ? (noDep ? AMBER_SOFT : LAVENDER_SOFT) : late ? 0xFFFEE2E2 : ack ? LAVENDER_SOFT : isNew ? ACCENT_SOFT : isDone ? 0xFFEEF0F3 : MINT_SOFT;
        int tagFg = isPre ? (noDep ? 0xFF92400E : LAVENDER) : late ? RED : ack ? LAVENDER : isNew ? ACCENT : isDone ? MUTED : MINT;
        TextView tag = text(this, tagTxt, 10.5f, true, tagFg);
        tag.setBackground(glass(this, tagBg, 20, 0));
        tag.setPadding(dp(this,8), dp(this,3), dp(this,8), dp(this,3));
        LinearLayout.LayoutParams tgl = lp(WRAP, WRAP); tgl.leftMargin = dp(this, 6);
        top.addView(tag, tgl);
        card.addView(top, lp(MATCH, WRAP));

        // ---- บรรทัดนัด (พรีออเดอร์) ----
        if (o.preAt > 0) {
            String dep = o.paidFull ? "ชำระครบ" : o.deposit > 0 ? "มัดจำ " + o.deposit + " · คงเหลือ " + Math.max(0, o.total - o.deposit) : "ยังไม่มัดจำ";
            TextView pa = text(this, "📅 นัด" + ("ส่ง".equals(o.preType) ? "ส่ง " : "รับ ") + dayLabel(o.preAt) + " " + o.preClock() + " น. · " + dep
                    + (isPre ? " · POS เริ่ม " + clockOf(o.preAt - o.prepMin * 60000L) : ""), 11.5f, true, o.preLate() ? RED : LAVENDER);
            pa.setPadding(0, dp(this, 6), 0, 0);
            card.addView(pa);
        }
        // ---- แถบ 4 ขั้น ----
        int step = isDone ? 4 : isNew ? 1 : 2;   // จำนวนขั้นที่ผ่าน (ส่งแล้ว = 1)
        LinearLayout steps = row(this);
        if (isPre) steps.setVisibility(View.GONE);
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
        if (isPre) lbls.setVisibility(View.GONE);
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
                if (isPre) {
                    // ยกเลิกพรีออเดอร์: กด 2 ครั้งภายใน 4 วิ
                    TextView cx = button(this, key.equals(cancelArm) ? "ยืนยันยกเลิก?" : "🗑 ยกเลิก", glass(this, key.equals(cancelArm) ? RED : 0xFFFEE2E2, 10, RED), 12);
                    cx.setTextColor(key.equals(cancelArm) ? WHITE : RED); cx.setPadding(dp(this,8), dp(this,8), dp(this,8), dp(this,8));
                    LinearLayout.LayoutParams cxl = lpw(1); cxl.leftMargin = dp(this, 6);
                    Fx.onTap(cx, () -> {
                        if (!key.equals(cancelArm)) { cancelArm = key; rebuildBody(); posUi.postDelayed(() -> { if (key.equals(cancelArm)) { cancelArm = null; if (panelOpen && filter.equals(F_POS)) rebuildBody(); } }, 4000); return; }
                        cancelArm = null;
                        o.status = "ยกเลิก"; rebuildBody();
                        new Thread(() -> {
                            String err = null;
                            try { PosSender.setStatus(BubbleService.this, o.id, "ยกเลิก"); } catch (Exception e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
                            final String fe = err;
                            posUi.post(() -> { if (fe == null) Toast.makeText(BubbleService.this, "ยกเลิกพรีออเดอร์แล้ว", Toast.LENGTH_SHORT).show(); else { o.status = "พรี"; posBanner = "❌ ยกเลิกไม่สำเร็จ: " + fe; posBannerAt = System.currentTimeMillis(); } fetchPosOrders(); if (panelOpen && filter.equals(F_POS)) rebuildBody(); });
                        }).start();
                    });
                    acts.addView(cx, cxl);
                }
                // กด "เสร็จแล้ว" จากมือถือได้ (เผื่อ POS ลืมกด / ส่งออกไปแล้ว)
                TextView fin = button(this, "✅ เสร็จแล้ว", glass(this, MINT, 10, 0), 12);
                if (isPre) fin.setVisibility(View.GONE);
                fin.setPadding(dp(this,8), dp(this,8), dp(this,8), dp(this,8));
                LinearLayout.LayoutParams fl = lpw(1); fl.leftMargin = dp(this, 6);
                Fx.onTap(fin, () -> {
                    final String pStatus = o.status;
                    o.status = "เสร็จ";
                    PosSender.markDoneSeen(BubbleService.this, o.id);   // กดเองไม่ต้องเด้งแจ้งเตือนตัวเอง
                    rebuildBody(); refreshBubble();
                    Toast.makeText(this, "⏳ กำลังปิดออเดอร์…", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        String err = null;
                        try { PosSender.setStatus(BubbleService.this, o.id, "เสร็จ"); } catch (Exception e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
                        final String fe = err;
                        posUi.post(() -> {
                            if (fe == null) { Toast.makeText(BubbleService.this, "✅ ปิดออเดอร์แล้ว" + (o.no.isEmpty() ? "" : " #" + o.no), Toast.LENGTH_SHORT).show(); }
                            else { o.status = pStatus; posBanner = "❌ ปิดออเดอร์ไม่สำเร็จ: เช็คเน็ตแล้วกดอีกครั้ง (" + fe + ")"; posBannerAt = System.currentTimeMillis(); }
                            fetchPosOrders();
                            if (panelOpen && filter.equals(F_POS)) rebuildBody();
                        });
                    }).start();
                });
                acts.addView(fin, fl);
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
    /* ================= ป๊อปอัปเลือกที่อยู่ส่ง (ยึดบนจอ ไม่ให้แป้นพิมพ์บัง) ================= */
    private View placeBox = null;

    private List<String> allPlaces() {
        List<String> out = new ArrayList<>(Store.loadPlaces(this));
        List<PosSender.OrderStatus> cl = new ArrayList<>(posOrders);
        java.util.Collections.sort(cl, (a, b) -> Long.compare(b.createdAt, a.createdAt));
        for (PosSender.OrderStatus o : cl) { String p = o.place.trim(); if (!p.isEmpty() && !out.contains(p)) out.add(p); }
        for (String q : new String[]{"หน้าร้าน", "รับเอง"}) if (!out.contains(q)) out.add(q);
        return out;
    }

    private void openPlacePicker() {
        if (placeBox != null) return;
        final LinearLayout box = col(this);
        box.setBackground(glass(this, SURFACE, 18, STROKE));
        box.setPadding(dp(this,14), dp(this,12), dp(this,14), dp(this,14));
        box.setElevation(dp(this, 20));
        LinearLayout hd = row(this);
        hd.addView(text(this, "📍 เลือกที่อยู่ส่ง", 15, true, TXT), lpw(1));
        TextView x = iconBtn(this, "✕");
        Fx.onTap(x, this::closePlacePicker);
        hd.addView(x);
        box.addView(hd, lp(MATCH, WRAP));

        final EditText q = input(this, "🔍 พิมพ์ค้นหา / ที่อยู่ใหม่");
        q.setText(place); q.setSelection(place.length());
        q.setTextSize(16);
        q.setSingleLine(true);
        q.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        LinearLayout.LayoutParams ql = lp(MATCH, WRAP); ql.topMargin = dp(this, 10);
        box.addView(q, ql);

        final LinearLayout list = col(this);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        box.addView(sv, lp(MATCH, (int) (dm.heightPixels * 0.42f)));

        final Runnable refresh = () -> {
            list.removeAllViews();
            String t = q.getText().toString().trim(), tl = t.toLowerCase();
            List<String> all = allPlaces();
            List<String> hit = new ArrayList<>(), rest = new ArrayList<>();
            for (String p : all) { if (!tl.isEmpty() && p.toLowerCase().contains(tl)) hit.add(p); else rest.add(p); }
            if (!t.isEmpty() && !all.contains(t)) {
                TextView use = button(this, "＋ ใช้ที่อยู่ใหม่ \"" + t + "\"", glass(this, 0xFFFFF8F4, 12, ACCENT), 14);
                use.setTextColor(ACCENT);
                Fx.onTap(use, () -> pickPlace(t));
                LinearLayout.LayoutParams ul = lp(MATCH, WRAP); ul.topMargin = dp(this, 8);
                list.addView(use, ul);
            }
            if (!hit.isEmpty()) {
                list.addView(lbl("ตรงกับที่พิมพ์ (" + hit.size() + ")"));
                list.addView(chipFlow(hit, t), lp(MATCH, WRAP));
            }
            if (!rest.isEmpty()) {
                LinearLayout lr = row(this);
                lr.addView(lbl(tl.isEmpty() ? "ใช้บ่อย / ล่าสุด" : "ที่อยู่อื่น"), lpw(1));
                TextView tip = text(this, "แตะค้างเพื่อลบ", 10.5f, false, MUTED);
                lr.addView(tip);
                list.addView(lr, lp(MATCH, WRAP));
                list.addView(chipFlow(rest, ""), lp(MATCH, WRAP));
            }
            if (all.isEmpty() && t.isEmpty()) {
                TextView em = text(this, "ยังไม่มีที่อยู่ที่จำไว้ — พิมพ์ด้านบนแล้วกด \"ใช้ที่อยู่ใหม่\"", 12.5f, false, MUTED);
                em.setPadding(0, dp(this, 10), 0, 0);
                list.addView(em);
            }
        };
        q.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c,int a,int b,int d) {}
            @Override public void onTextChanged(CharSequence c,int a,int b,int d) { refresh.run(); }
            @Override public void afterTextChanged(Editable e) {}
        });
        q.setOnEditorActionListener((tv, a, ev) -> { String t = q.getText().toString().trim(); if (!t.isEmpty()) pickPlace(t); return true; });
        refresh.run();

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(Math.min(dm.widthPixels - dp(this, 16), dp(this, 440)), WRAP, wtype(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        p.y = dp(this, 12);
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE;
        box.setFocusableInTouchMode(true);
        box.setOnKeyListener((v, code, ev) -> { if (code == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) { closePlacePicker(); return true; } return false; });
        placeBox = box;
        wm.addView(box, p);
        posUi.postDelayed(() -> {
            q.requestFocus();
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(q, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    /** ชิปที่อยู่แบบไหลหลายแถว (ไฮไลต์ส่วนที่ตรงกับคำค้น) */
    private View chipFlow(List<String> items, String hl) {
        LinearLayout col = col(this);
        LinearLayout rowv = null;
        int used = 0, maxW = Math.min(getResources().getDisplayMetrics().widthPixels - dp(this, 16), dp(this, 440)) - dp(this, 28);
        android.graphics.Paint pt = new android.graphics.Paint(); pt.setTextSize(13 * getResources().getDisplayMetrics().scaledDensity); pt.setFakeBoldText(true);
        for (final String pl : items) {
            int w = (int) pt.measureText(pl) + dp(this, 30);
            if (rowv == null || used + w > maxW) { rowv = row(this); LinearLayout.LayoutParams rl = lp(MATCH, WRAP); rl.topMargin = dp(this, 6); col.addView(rowv, rl); used = 0; }
            boolean cur = pl.equals(place);
            TextView c = text(this, pl, 13, true, cur || !hl.isEmpty() ? ACCENT : TXT);
            if (!hl.isEmpty()) {
                int i = pl.toLowerCase().indexOf(hl.toLowerCase());
                if (i >= 0) { android.text.SpannableString ss = new android.text.SpannableString(pl); ss.setSpan(new android.text.style.BackgroundColorSpan(0xFFFFD9C7), i, i + hl.length(), 0); c.setText(ss); }
            }
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(this, 13), dp(this, 9), dp(this, 13), dp(this, 9));
            c.setBackground(glass(this, cur || !hl.isEmpty() ? ACCENT_SOFT : SURFACE, 20, cur || !hl.isEmpty() ? ACCENT : LINE));
            Fx.onTap(c, () -> pickPlace(pl));
            Fx.onHold(c, () -> { Store.forgetPlace(this, pl); Toast.makeText(this, "ลบ " + pl + " แล้ว", Toast.LENGTH_SHORT).show(); closePlacePicker(); openPlacePicker(); });
            LinearLayout.LayoutParams cl = lp(WRAP, WRAP); cl.rightMargin = dp(this, 6);
            rowv.addView(c, cl);
            used += w + dp(this, 6);
        }
        return col;
    }

    private void pickPlace(String p) {
        Store.rememberPlace(this, p);
        setPlace(p, null);
        closePlacePicker();
        if (panelOpen) { rebuildBody(); refreshPreview(); }
        Toast.makeText(this, "📍 " + p, Toast.LENGTH_SHORT).show();
    }

    private void closePlacePicker() {
        if (placeBox != null) {
            try {
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(placeBox.getWindowToken(), 0);
            } catch (Exception ignored) {}
            try { wm.removeView(placeBox); } catch (Exception ignored) {}
            placeBox = null;
        }
    }

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
        final EditText placeField = input(this, "📍 ส่งที่ไหน (แตะเพื่อเลือก)");
        shipPlaceField = placeField;
        placeField.setText(place);
        placeField.setFocusable(false); placeField.setClickable(true);
        placeField.setOnClickListener(v -> openPlacePicker());
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
            String pl = preLines();
            if (!pl.isEmpty()) { if (s.length() > 0) s.append("\n"); s.append(pl); }
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
                int preTodayN = 0; StringBuilder preTodayTimes = new StringBuilder();
                for (PosSender.OrderStatus o : res) {
                    // พรีออเดอร์ → POS เริ่มทำ (พรี → ใหม่/กำลังทำ) → แจ้งเตือนมือถือ
                    String ps = prevStatus.get(o.id);
                    if (o.preAt > 0 && "พรี".equals(ps) && !o.isPre() && !o.isClosed()) notifyPreStarted(o);
                    prevStatus.put(o.id, o.status);
                    if (o.isPre()) { if (dayLabel(o.preAt).equals("วันนี้")) { preTodayN++; preTodayTimes.append(preTodayTimes.length() > 0 ? ", " : "").append(o.preClock()); } continue; }
                    if (o.createdAt <= cutoff) continue;
                    if (!o.isClosed() && (o.minAgo() >= OVERDUE_MIN || o.preLate())) overdue = true;
                    if (o.status.equals("เสร็จ") && !seen.contains(o.id) && "mobile".equals(o.by)) {
                        PosSender.markDoneSeen(BubbleService.this, o.id);
                        notifyDone(o.asLog());
                        doneUnseen.add(o.id);
                    }
                }
                morningSummary(preTodayN, preTodayTimes.toString());
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
    /** POS เริ่มเตรียมพรีออเดอร์แล้ว */
    private void notifyPreStarted(PosSender.OrderStatus o) {
        simpleNotify("📅 POS เริ่มเตรียมพรีออเดอร์" + (o.no.isEmpty() ? "" : " #" + o.no),
                (o.place.isEmpty() ? "" : o.place + " · ") + "นัด " + o.preClock() + " น. · " + untilLabel(o.preAt), 20);
    }

    /** เตือนเช้า 09:00 สรุปพรีออเดอร์วันนี้ (ครั้งเดียวต่อวัน) */
    private void morningSummary(int n, String times) {
        if (n <= 0) return;
        java.util.Calendar c = java.util.Calendar.getInstance();
        if (c.get(java.util.Calendar.HOUR_OF_DAY) < 9) return;
        String key = c.get(java.util.Calendar.YEAR) + "-" + c.get(java.util.Calendar.DAY_OF_YEAR);
        if (key.equals(Store.prefs(this).getString("pre_morning", ""))) return;
        Store.prefs(this).edit().putString("pre_morning", key).apply();
        simpleNotify("📅 วันนี้มีพรีออเดอร์ " + n + " ใบ", "เวลานัด: " + times + " — แตะเพื่อดูรายการ", 21);
    }

    private void simpleNotify(String title, String body, int id) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel("pre_ch", "พรีออเดอร์", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
            PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification nf = new Notification.Builder(this, "pre_ch").setContentTitle(title).setContentText(body)
                    .setSmallIcon(android.R.drawable.ic_menu_my_calendar).setContentIntent(pi).setAutoCancel(true).build();
            nm.notify(id, nf);
        } catch (Exception ignored) {}
        try { Toast.makeText(this, title, Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
        if (bubbleDisc != null) Fx.bounce(bubbleDisc);
    }

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
        // พรีออเดอร์: โหลดวัน/เวลา/มัดจำกลับมาด้วย
        preOn = false; preDeposit = 0; prePaidFull = false; preCustomTime = false; preCustomDep = false;
        for (PosSender.OrderStatus o : posOrders) if (o.id.equals(l.id) && o.preAt > 0 && o.isPre()) {
            preOn = true;
            java.util.Calendar c = java.util.Calendar.getInstance(); c.setTimeInMillis(o.preAt - PosSender.offset);
            java.util.Calendar t = java.util.Calendar.getInstance();
            preDayOff = (int) Math.max(0, (startOfDay(c) - startOfDay(t)) / 86400000L);
            preTime = String.format(java.util.Locale.US, "%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
            preCustomTime = !java.util.Arrays.asList("10:00", "11:00", "12:00", "13:00", "16:00", "17:00", "18:00", "19:00", "20:00").contains(preTime);
            preType = o.preType.isEmpty() ? "ส่ง" : o.preType; preDeposit = o.deposit; prePaidFull = o.paidFull;
        }
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
        final long preAtC = preAtMillis();
        box.addView(text(this, preAtC > 0 ? "📅 ตรวจสอบพรีออเดอร์" : isEdit ? "✏️ ตรวจสอบก่อนอัปเดตออเดอร์" : "🧾 ตรวจสอบก่อนส่งเข้า POS", 16, true, preAtC > 0 ? LAVENDER : TXT));
        if (preAtC > 0) {
            LinearLayout pb = col(this);
            pb.setBackground(glass(this, LAVENDER_SOFT, 12, 0));
            pb.setPadding(dp(this,12), dp(this,10), dp(this,12), dp(this,10)); pb.setGravity(Gravity.CENTER);
            pb.addView(text(this, "นัด" + (preType.equals("ส่ง") ? "ส่ง" : "รับ"), 11.5f, true, LAVENDER));
            pb.addView(text(this, dayLabel(preAtC) + " · " + clockOf(preAtC), 22, true, LAVENDER));
            pb.addView(text(this, "POS จะเริ่มเตรียม " + clockOf(preAtC - PRE_PREP_MIN * 60000L) + " · " + (preType.equals("ส่ง") ? "🛵 ส่ง " + dest : "🏠 รับเอง"), 12, false, WHITE_DIM));
            LinearLayout.LayoutParams pbl = lp(MATCH, WRAP); pbl.topMargin = dp(this, 8);
            box.addView(pb, pbl);
        }
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
        TextView ok = button(this, preAtC > 0 ? (isEdit ? "✅ ยืนยันอัปเดตพรีออเดอร์" : "✅ ยืนยันพรีออเดอร์") : isEdit ? "✅ ยืนยันอัปเดต" : "✅ ยืนยันส่งเข้า POS", glass(this, preAtC > 0 ? LAVENDER : MINT, 12, 0), 15);
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

    private PosSender.PreInfo zeroPre() { PosSender.PreInfo p = new PosSender.PreInfo(); p.preAt = 0; return p; }

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
        if (editId != null) for (PosSender.OrderStatus o : posOrders) if (o.id.equals(editId) && o.isClosed()) reopenTmp = true;
        final boolean reopen = reopenTmp;

        Toast.makeText(this, editId != null ? "กำลังอัปเดตออเดอร์…" : "กำลังส่งเข้าเครื่อง POS…", Toast.LENGTH_SHORT).show();
        sending = true;
        long preAtS = preAtMillis();
        if (preAtS > 0) {
            PosSender.PreInfo pi = new PosSender.PreInfo();
            pi.preAt = preAtS; pi.preType = preType; pi.deposit = prePaidFull ? total : preDeposit; pi.paidFull = prePaidFull; pi.prepMin = PRE_PREP_MIN;
            PosSender.pendingPre = pi;
        } else PosSender.pendingPre = (editId != null) ? zeroPre() : null;
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
                PosSender.pendingPre = null;
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
                    preOn = false; preDeposit = 0; prePaidFull = false; preCustomTime = false; preCustomDep = false; preDayOff = 1; preTime = "17:00"; preType = "ส่ง";
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
