package com.messessale.app;

import java.util.List;

/** สร้างข้อความสำหรับ Copy ไปตอบลูกค้า / แจ้งพนักงาน */
public class MsgBuilder {

    public static final int MODE_CUSTOMER = 0; // ตอบลูกค้า
    public static final int MODE_STAFF    = 1; // แจ้งพนักงาน

    /**
     * @param cats     รายการเมนูทั้งหมด (อ่าน qty)
     * @param mode     MODE_CUSTOMER / MODE_STAFF
     * @param orderNo  เลขออร์เดอร์ (โหมดพนักงาน) เช่น "3"
     * @param place    จุดส่ง เช่น "Tara"
     * @param payLine  ข้อความท้าย เช่น "ชำระเงินคนละครึ่งหรือโอนธรรมดาครับ"
     */
    public static String build(List<MenuData.Cat> cats, int mode, String orderNo, String place, String payLine) {
        return build(cats, mode, orderNo, place, payLine, place);
    }

    /**
     * @param shipTo จุดส่ง — ต่อท้ายชื่อรายการค่าส่ง เช่น "ค่าส่ง Tara 20 บาท"
     */
    public static String build(List<MenuData.Cat> cats, int mode, String orderNo, String place, String payLine, String shipTo) {
        StringBuilder sb = new StringBuilder();
        String dest = (shipTo != null && !shipTo.trim().isEmpty()) ? shipTo.trim()
                : (place != null ? place.trim() : "");

        if (mode == MODE_STAFF) {
            String head = "order";
            if (orderNo != null && !orderNo.trim().isEmpty()) head += " ที่ " + orderNo.trim();
            if (!dest.isEmpty()) head += " " + dest;
            sb.append(head).append("\n\n");
        }

        int total = 0;
        for (MenuData.Cat c : cats) {
            boolean isShip = c.name.equals("ค่าส่ง");
            for (MenuData.Item it : c.items) {
                if (it.qty <= 0) continue;
                int line = it.price * it.qty;
                total += line;
                sb.append(it.name);
                if (isShip && !dest.isEmpty() && !it.name.contains(dest)) sb.append(" ").append(dest);
                if (it.qty > 1) sb.append(" x").append(it.qty);
                sb.append(" ").append(line).append(" บาท\n");
            }
        }

        sb.append("\nยอดรวมทั้งหมด ").append(total).append(" บาท\n");

        if (payLine != null && !payLine.trim().isEmpty()) {
            sb.append("\n").append(payLine.trim());
        }

        return sb.toString().trim();
    }

    public static int total(List<MenuData.Cat> cats) {
        int total = 0;
        for (MenuData.Cat c : cats)
            for (MenuData.Item it : c.items)
                if (it.qty > 0) total += it.price * it.qty;
        return total;
    }

    public static void clear(List<MenuData.Cat> cats) {
        for (MenuData.Cat c : cats)
            for (MenuData.Item it : c.items) it.qty = 0;
    }

    /** ข้อความโปรโมทเมนู (แบบที่ร้านโพสต์) */
    public static String promo() {
        return "🇹🇭รับไทยช่วยไทย🇹🇭\n" +
               "กุ้งเป็นๆเผาร้านบังฟาน\n" +
               "ย่างเตาถ่านหอมๆ ทำใหม่ทุกเมนู มีน้ำจิ้มซีฟู้ดให้ฟรีทุกเมนู\n\n" +
               "🦐🔥 กุ้งเผา (ไซส์กุ้งวันนี้)\n" +
               "📍 1 โล (20-25 ตัว) 350 บาท\n" +
               "📍 ครึ่งโล (10-12 ตัว) 190 บาท\n" +
               "• มีกุ้งสดๆ เป็นๆ ราคาเท่ากัน\n" +
               "(มีบริการผ่ากุ้งให้ฟรีบอกได้ครับ)\n\n" +
               "🐚 หอยเชลล์อบชีส 30 ฝา 190 บาท\n" +
               "🧀 กุ้งอบชีส (ครึ่งโล) 250 บาท";
    }
}
