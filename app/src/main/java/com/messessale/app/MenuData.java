package com.messessale.app;

import java.util.ArrayList;
import java.util.List;

/** เมนูร้านกุ้งเผาบังฟาน — ใช้ 4 หมวด: กุ้งเผา / เมนูข้าว / Add On / ค่าส่ง */
public class MenuData {

    public static class Item {
        public final String name;
        public final int price;
        public final boolean custom; // ราคากำหนดเองตอนขาย
        public int qty = 0;

        Item(String name, int price, boolean custom) {
            this.name = name;
            this.price = price;
            this.custom = custom;
        }
    }

    public static class Cat {
        public final String name;
        public final List<Item> items = new ArrayList<>();
        Cat(String name) { this.name = name; }
    }

    public static List<Cat> build() {
        List<Cat> cats = new ArrayList<>();

        Cat shrimp = new Cat("กุ้งเผา");
        shrimp.items.add(new Item("กุ้งเผาครึ่งโล", 190, false));
        shrimp.items.add(new Item("กุ้งเผา 1 กิโล", 350, false));
        shrimp.items.add(new Item("กุ้งเผาไซส์กลาง [ ครึ่งโล ]", 240, false));
        shrimp.items.add(new Item("กุ้งเผาไซส์กลาง [ 1 โล ]", 400, false));
        shrimp.items.add(new Item("กุ้งเผาไซส์ใหญ่ [ ครึ่งกิโล ]", 260, false));
        shrimp.items.add(new Item("กุ้งเผาไซส์ใหญ่ [ 1 กิโล ]", 450, false));
        shrimp.items.add(new Item("กุ้งผ่าไซส์ใหญ่ ( ครึ่งโล )", 260, false));
        shrimp.items.add(new Item("กุ้งหัวแก้วครึ่งกิโล", 260, false));
        shrimp.items.add(new Item("กุ้งหัวแก้ว 1 กิโล", 450, false));
        shrimp.items.add(new Item("กุ้งอบเกลือครึ่งกิโลกรัม", 190, false));
        shrimp.items.add(new Item("กุ้งอบเกลือ 1 กิโล", 350, false));
        shrimp.items.add(new Item("กุ้งอบชีส ครึ่งกิโล", 250, false));
        shrimp.items.add(new Item("กุ้งชีสเสียบไม้", 175, false));
        shrimp.items.add(new Item("หอยเชลล์อบชีส 30 ตัว", 190, false));
        shrimp.items.add(new Item("กุ้งตามน้ำหนัก", 0, true));
        cats.add(shrimp);

        Cat rice = new Cat("เมนูข้าว");
        rice.items.add(new Item("ข้าวปูแกะ", 169, false));
        rice.items.add(new Item("ข้าวหน้ากุ้งแกะ L", 180, false));
        rice.items.add(new Item("ข้าวหน้ากุ้งแกะ S", 130, false));
        rice.items.add(new Item("ข้าวหน้ากุ้งผ่า L", 180, false));
        rice.items.add(new Item("ข้าวหน้ากุ้งผ่า S", 130, false));
        rice.items.add(new Item("ข้าวหน้ากุ้งเผา S", 130, false));
        rice.items.add(new Item("ข้าวหน้ากุ้งอบเกลือ", 130, false));
        rice.items.add(new Item("อกปูซอง", 40, false));
        cats.add(rice);

        Cat addon = new Cat("Add On");
        addon.items.add(new Item("ข้าว 1 ถุง", 10, false));
        addon.items.add(new Item("น้ำจิ้มถุงเล็ก", 10, false));
        addon.items.add(new Item("น้ำจิ้มซีฟู้ด 1 กระปุก", 25, false));
        addon.items.add(new Item("โค้กแก้วโอ่ง", 25, false));
        addon.items.add(new Item("โค้ก ออริจินัล (กระป๋อง)", 15, false));
        addon.items.add(new Item("มันปูซอง", 40, false));
        addon.items.add(new Item("ชีส", 40, false));
        addon.items.add(new Item("น้ำแข็ง", 0, true));
        cats.add(addon);

        Cat ship = new Cat("ค่าส่ง");
        ship.items.add(new Item("ค่าส่ง", 20, true));
        ship.items.add(new Item("ค่าส่ง tara", 20, false));
        ship.items.add(new Item("เดอะพ้อยค่าส่ง", 40, false));
        cats.add(ship);

        return cats;
    }
}
