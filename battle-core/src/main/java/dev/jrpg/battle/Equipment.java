package dev.jrpg.battle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Снаряжение героев (equipment.json): 8 слотов, вещи со статами, мощь оружия с классами S–E по статам,
 * стихия оружия, навыки оружия и иммунитеты. Вещи лежат в рюкзаке (вкладка «Снаряжение»), надетые
 * хранятся у героя ({@link Progression.Hero#equipped}) и в сохранении.
 *
 * Урон: сила навыка × (стат героя + мощь оружия × класс). Класс физического удара = ATK + половина SPD,
 * магического = MAG (см. {@link #physBonus}, {@link #magBonus}).
 */
public final class Equipment {
    /** Слоты героя. Четыре слота брони совпадают со слотами Hytale (Head/Chest/Hands/Legs). */
    public enum Slot {
        WEAPON("Оружие"), OFFHAND("Вторая рука"), HEAD("Голова"), CHEST("Тело"), HANDS("Руки"), LEGS("Ноги"),
        ACC1("Аксессуар 1"), ACC2("Аксессуар 2");

        public final String label;

        Slot(String label) {
            this.label = label;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean armor() {
            return this == HEAD || this == CHEST || this == HANDS || this == LEGS;
        }

        /** "weapon", "offhand", "head"…; "accessory"/"acc" — аксессуар (ACC1). null — не слот. */
        public static Slot parse(String s) {
            if (s == null) return null;
            String k = s.trim().toLowerCase(Locale.ROOT);
            if (k.equals("accessory") || k.equals("acc") || k.equals("ring") || k.equals("amulet")) return ACC1;
            if (k.equals("shield") || k.equals("off")) return OFFHAND;
            if (k.equals("body")) return CHEST;
            if (k.equals("feet") || k.equals("boots")) return LEGS;
            for (Slot sl : values()) if (sl.key().equals(k)) return sl;
            return null;
        }
    }

    public enum Quality {
        COMMON("обычное", "#c9ced8"), RARE("редкое", "#6fdc8c"), EPIC("эпическое", "#7fb2ff"), LEGENDARY("легендарное", "#ffb84a");

        public final String label;
        public final String color;

        Quality(String label, String color) {
            this.label = label;
            this.color = color;
        }

        public static Quality parse(String s) {
            if (s == null) return COMMON;
            for (Quality q : values()) if (q.name().equalsIgnoreCase(s.trim())) return q;
            return COMMON;
        }
    }

    /**
     * Вещь. slot ACC1 означает «аксессуар» (подходит в оба слота аксессуаров).
     *
     * @param type   тип оружия/вещи: sword, axe, mace, spear, staff, daggers, bow, shield, armor, accessory
     * @param model  предмет Hytale, который видно на модели: точные id или слова для поиска (первое найденное)
     * @param power  мощь оружия (0 у брони и аксессуаров)
     * @param scale  классы по статам: atk/mag/spd → S, A, B, C, D, E
     * @param bonus  прибавка к статам героя (может быть отрицательной: тяжёлая броня снижает SPD)
     */
    public record Gear(String id, String name, String type, Slot slot, Quality quality, List<String> model,
                       int power, Map<String, String> scale, String element, Stats bonus,
                       List<String> immunities, List<String> skills, boolean twoHanded, String description) {
        public boolean accessory() {
            return slot == Slot.ACC1 || slot == Slot.ACC2;
        }

        public boolean fits(Slot s) {
            return accessory() ? (s == Slot.ACC1 || s == Slot.ACC2) : slot == s;
        }

        public String grade(String stat) {
            String g = scale.get(stat);
            return g == null ? "-" : g;
        }

        public double scaleOf(String stat) {
            return Equipment.grade(grade(stat));
        }
    }

    private static final Map<String, Double> GRADES = new LinkedHashMap<>(Map.of(
            "S", 1.0, "A", 0.8, "B", 0.6, "C", 0.4, "D", 0.25, "E", 0.1));

    private Equipment() {}

    public static double grade(String letter) {
        if (letter == null) return 0;
        Double g = GRADES.get(letter.trim().toUpperCase(Locale.ROOT));
        return g == null ? 0 : g;
    }

    /** Прибавка оружия к физической силе: мощь × (класс ATK + ½ класса SPD). */
    public static double physBonus(Gear w) {
        return w == null ? 0 : w.power() * (w.scaleOf("atk") + 0.5 * w.scaleOf("spd"));
    }

    /** Прибавка оружия к магической силе: мощь × класс MAG. */
    public static double magBonus(Gear w) {
        return w == null ? 0 : w.power() * w.scaleOf("mag");
    }

    // ---------- загрузка ----------

    /** Читает equipment.json в content: вещи (и как предметы рюкзака), стартовое снаряжение, стартовый набор. */
    public static void load(Content content, String json) {
        Map<String, Object> root = Json.obj(Json.parse(json));
        if (root.get("grades") != null) {
            Json.obj(root.get("grades")).forEach((k, v) -> {
                if (v instanceof Number n) GRADES.put(k.toUpperCase(Locale.ROOT), n.doubleValue());
            });
        }
        content.clearGear();
        for (Object o : Json.list(root, "items")) {
            Map<String, Object> m = Json.obj(o);
            Gear g = parse(m);
            content.add(g);
            content.icon(g.id(), Json.str(m, "icon", defaultIcon(g)));
        }
        if (root.get("start") != null) {
            Map<String, Object> start = Json.obj(root.get("start"));
            for (String hero : start.keySet()) {
                Map<Slot, String> eq = new EnumMap<>(Slot.class);
                Json.obj(start.get(hero)).forEach((k, v) -> {
                    Slot s = Slot.parse(k);
                    if (s == null || !(v instanceof String id)) throw new IllegalArgumentException("equipment.json start." + hero + ": " + k);
                    if (!content.gear(id).fits(s)) throw new IllegalArgumentException(id + " не подходит в слот " + k);
                    eq.put(s, id);
                });
                content.startGear(hero, eq);
            }
        }
        Map<String, Integer> bag = new LinkedHashMap<>();
        if (root.get("starterBag") != null) {
            Json.obj(root.get("starterBag")).forEach((k, v) -> {
                content.gear(k);
                bag.put(k, v instanceof Number n ? n.intValue() : 1);
            });
        }
        content.starterBag(bag);
        for (Gear g : content.gearList()) {
            for (String s : g.skills()) content.ability(s);
            for (String s : g.immunities()) content.status(s);
        }
    }

    private static String defaultIcon(Gear g) {
        return switch (g.type()) {
            case "axe", "mace", "hammer", "battleaxe" -> "cleave";
            case "daggers" -> "bleed";
            case "staff" -> "magic";
            case "shield", "armor" -> "shield";
            case "accessory" -> "star";
            default -> "sword";
        };
    }

    private static Gear parse(Map<String, Object> m) {
        String id = Json.str(m, "id", null);
        if (id == null) throw new IllegalArgumentException("equipment.json: вещь без id");
        Slot slot = Slot.parse(Json.str(m, "slot", null));
        if (slot == null) throw new IllegalArgumentException("equipment.json " + id + ": неизвестный слот");
        Map<String, String> scale = new LinkedHashMap<>();
        if (m.get("scale") != null) Json.obj(m.get("scale")).forEach((k, v) -> scale.put(k.toLowerCase(Locale.ROOT), String.valueOf(v)));
        Map<String, Object> st = m.get("stats") == null ? Map.of() : Json.obj(m.get("stats"));
        Stats bonus = new Stats((int) Json.num(st, "hp", 0), (int) Json.num(st, "mp", 0), (int) Json.num(st, "atk", 0),
                (int) Json.num(st, "mag", 0), (int) Json.num(st, "def", 0), (int) Json.num(st, "spd", 0));
        List<String> model = m.get("model") instanceof String s ? List.of(s) : Json.strings(m, "model");
        String type = Json.str(m, "type", slot.armor() ? "armor" : slot == Slot.ACC1 ? "accessory" : "sword");
        return new Gear(id, Json.str(m, "name", id), type, slot, Quality.parse(Json.str(m, "quality", null)), model,
                (int) Json.num(m, "power", 0), scale, Json.str(m, "element", null), bonus,
                Json.strings(m, "immunities"), Json.strings(m, "skills"), Json.bool(m, "twoHanded", false),
                Json.str(m, "description", ""));
    }

    // ---------- надеть / снять ----------

    /** Надеть вещь из рюкзака. null — получилось, иначе причина. Снятая вещь возвращается в рюкзак. */
    public static String equip(Content content, Progression.Hero h, Slot slot, String gearId, Map<String, Integer> inv) {
        if (!content.hasGear(gearId)) return "нет такой вещи";
        Gear g = content.gear(gearId);
        if (!g.fits(slot)) return g.name() + " не подходит в слот «" + slot.label + "»";
        if (slot == Slot.OFFHAND) {
            Gear w = h.gear(Slot.WEAPON);
            if (w != null && w.twoHanded()) return w.name() + " — двуручное, вторая рука занята";
        }
        synchronized (inv) {
            int n = inv.getOrDefault(gearId, 0);
            if (n <= 0) return "этой вещи нет в рюкзаке";
            take(inv, gearId);
            String old = h.equipped.put(slot, gearId);
            if (old != null) give(inv, old);
            if (slot == Slot.WEAPON && g.twoHanded()) {
                String off = h.equipped.remove(Slot.OFFHAND);
                if (off != null) give(inv, off);
            }
        }
        return null;
    }

    /** Снять вещь в рюкзак. Оружие снять нельзя, только заменить (без оружия герою нечем бить). */
    public static String unequip(Progression.Hero h, Slot slot, Map<String, Integer> inv) {
        if (slot == Slot.WEAPON) return "оружие можно только заменить";
        synchronized (inv) {
            String old = h.equipped.remove(slot);
            if (old == null) return "слот пуст";
            give(inv, old);
        }
        return null;
    }

    /** Вещи из рюкзака, которые подходят в слот: сначала лучшее качество, потом по имени. */
    public static List<Gear> candidates(Content content, Map<String, Integer> inv, Slot slot) {
        List<Gear> out = new ArrayList<>();
        synchronized (inv) {
            inv.forEach((id, n) -> {
                if (n != null && n > 0 && content.hasGear(id) && content.gear(id).fits(slot)) out.add(content.gear(id));
            });
        }
        out.sort(Comparator.<Gear, Integer>comparing(g -> -g.quality().ordinal()).thenComparing(Gear::name));
        return out;
    }

    /** Строка сравнения характеристик. better: 1 лучше, -1 хуже, 0 без изменений. */
    public record Diff(String label, String before, String after, int better) {}

    /** Что изменится у героя, если надеть gearId в slot (null — снять). */
    public static List<Diff> compare(Content content, Progression.Hero h, Slot slot, String gearId) {
        Map<Slot, String> now = new EnumMap<>(Slot.class);
        now.putAll(h.equipped);
        Map<Slot, String> next = new EnumMap<>(Slot.class);
        next.putAll(h.equipped);
        if (gearId == null) next.remove(slot);
        else {
            next.put(slot, gearId);
            if (slot == Slot.WEAPON && content.gear(gearId).twoHanded()) next.remove(Slot.OFFHAND);
        }
        Stats a = h.statsFor(now), b = h.statsFor(next);
        Gear wa = gearOf(content, now.get(Slot.WEAPON)), wb = gearOf(content, next.get(Slot.WEAPON));
        List<Diff> out = new ArrayList<>();
        num(out, "Сила удара", (int) Math.round(a.atk() + physBonus(wa)), (int) Math.round(b.atk() + physBonus(wb)));
        num(out, "Сила магии", (int) Math.round(a.mag() + magBonus(wa)), (int) Math.round(b.mag() + magBonus(wb)));
        num(out, "Здоровье", a.maxHp(), b.maxHp());
        num(out, "Мана", a.maxMp(), b.maxMp());
        num(out, "Атака", a.atk(), b.atk());
        num(out, "Магия", a.mag(), b.mag());
        num(out, "Защита", a.def(), b.def());
        num(out, "Скорость", a.spd(), b.spd());
        String ea = wa == null || wa.element() == null ? "нет" : Element.name(wa.element());
        String eb = wb == null || wb.element() == null ? "нет" : Element.name(wb.element());
        if (!ea.equals(eb)) out.add(new Diff("Стихия удара", ea, eb, 0));
        List<String> sa = skills(content, now), sb = skills(content, next);
        for (String s : sb) if (!sa.contains(s)) out.add(new Diff("Новый навык", "", content.ability(s).name(), 1));
        for (String s : sa) if (!sb.contains(s)) out.add(new Diff("Пропадёт навык", content.ability(s).name(), "", -1));
        List<String> ia = immunities(content, now), ib = immunities(content, next);
        for (String s : ib) if (!ia.contains(s)) out.add(new Diff("Иммунитет", "", content.status(s).name(), 1));
        for (String s : ia) if (!ib.contains(s)) out.add(new Diff("Пропадёт иммунитет", content.status(s).name(), "", -1));
        return out;
    }

    private static void num(List<Diff> out, String label, int a, int b) {
        out.add(new Diff(label, String.valueOf(a), String.valueOf(b), Integer.compare(b, a)));
    }

    private static Gear gearOf(Content content, String id) {
        return id == null || !content.hasGear(id) ? null : content.gear(id);
    }

    /** Навыки всех надетых вещей (без повторов). */
    public static List<String> skills(Content content, Map<Slot, String> eq) {
        List<String> out = new ArrayList<>();
        for (String id : eq.values()) {
            Gear g = gearOf(content, id);
            if (g != null) for (String s : g.skills()) if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    public static List<String> immunities(Content content, Map<Slot, String> eq) {
        List<String> out = new ArrayList<>();
        for (String id : eq.values()) {
            Gear g = gearOf(content, id);
            if (g != null) for (String s : g.immunities()) if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    private static void take(Map<String, Integer> inv, String id) {
        int n = inv.getOrDefault(id, 0);
        if (n <= 1) inv.remove(id);
        else inv.put(id, n - 1);
    }

    private static void give(Map<String, Integer> inv, String id) {
        inv.merge(id, 1, Integer::sum);
    }
}
