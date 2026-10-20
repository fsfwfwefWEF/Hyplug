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

    /** Качество вещи: цвет, число гнёзд под камни, множитель мощи и статов (от качества в equipment.json). */
    public enum Quality {
        COMMON("обычное", "#a7afbd", 0, 1.0), GOOD("хорошее", "#6fc27a", 1, 1.15), RARE("редкое", "#5b9cf0", 2, 1.3),
        EPIC("эпическое", "#b07cf0", 2, 1.5), LEGENDARY("легендарное", "#f0a040", 3, 1.75);

        public final String label;
        public final String color;
        public final int sockets;
        public final double mult;

        Quality(String label, String color, int sockets, double mult) {
            this.label = label;
            this.color = color;
            this.sockets = sockets;
            this.mult = mult;
        }

        public Quality next() {
            return this == LEGENDARY ? null : values()[ordinal() + 1];
        }

        public static Quality parse(String s) {
            if (s == null) return COMMON;
            String k = s.trim();
            if (k.equalsIgnoreCase("uncommon")) return GOOD;
            for (Quality q : values()) if (q.name().equalsIgnoreCase(k)) return q;
            return COMMON;
        }
    }

    /** Цена улучшения до качества: любые материалы и любые камни из рюкзака. */
    public record Cost(int materials, int gems) {}

    /**
     * Правила из equipment.json: навыки по качеству для типа вещи (typeSkills) и для отдельной вещи (qualitySkills),
     * цена улучшения (upgrade).
     */
    public record Rules(Map<String, Map<Quality, String>> typeSkills, Map<String, Map<Quality, String>> itemSkills,
                        Map<Quality, Cost> upgrade) {
        public static final Rules NONE = new Rules(Map.of(), Map.of(), Map.of());
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
                       List<String> immunities, List<String> skills, boolean twoHanded, String description,
                       String base, List<String> gems) {
        /** Гнёзд под камни (по качеству). */
        public int sockets() {
            return quality.sockets;
        }

        /** Камень в гнезде i или null. */
        public String gem(int i) {
            return i < gems.size() ? gems.get(i) : null;
        }

        public int freeSocket() {
            for (int i = 0; i < sockets(); i++) if (gem(i) == null) return i;
            return -1;
        }

        public int gemCount() {
            int n = 0;
            for (String g : gems) if (g != null) n++;
            return n;
        }

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
        content.gearRules(new Rules(skillTable(root.get("typeSkills")), skillTable(root.get("qualitySkills")), costs(root.get("upgrade"))));
        for (Map<Quality, String> t : content.gearRules().typeSkills().values()) for (String s : t.values()) content.ability(s);
        for (Map<Quality, String> t : content.gearRules().itemSkills().values()) for (String s : t.values()) content.ability(s);
        for (Gear g : List.copyOf(content.gearList())) {
            List<String> empty = new ArrayList<>();
            for (int i = 0; i < g.sockets(); i++) empty.add(null);
            content.add(build(content, g, g.quality(), empty, g.id()));
        }
        for (Gear g : content.gearList()) {
            for (String s : g.skills()) content.ability(s);
            for (String s : g.immunities()) content.status(s);
        }
    }

    private static Map<String, Map<Quality, String>> skillTable(Object o) {
        Map<String, Map<Quality, String>> out = new LinkedHashMap<>();
        if (!(o instanceof Map<?, ?>)) return out;
        Json.obj(o).forEach((k, v) -> {
            Map<Quality, String> t = new EnumMap<>(Quality.class);
            Json.obj(v).forEach((q, a) -> t.put(Quality.parse(q), String.valueOf(a)));
            out.put(k, t);
        });
        return out;
    }

    private static Map<Quality, Cost> costs(Object o) {
        Map<Quality, Cost> out = new EnumMap<>(Quality.class);
        if (!(o instanceof Map<?, ?>)) return out;
        Json.obj(o).forEach((q, v) -> {
            Map<String, Object> m = Json.obj(v);
            out.put(Quality.parse(q), new Cost((int) Json.num(m, "materials", 0), (int) Json.num(m, "gems", 0)));
        });
        return out;
    }

    // ---------- качество и камни: вещь-вариант ----------

    /**
     * id вещи с качеством и камнями: base@Q или base@Q:камень,-,камень («-» — пустое гнездо). Если качество как у
     * исходной вещи и камней нет — просто base (так старые сохранения остаются как есть).
     */
    public static String variantId(String base, int quality, List<String> gems) {
        boolean any = false;
        for (String g : gems) if (g != null) any = true;
        StringBuilder sb = new StringBuilder(base).append('@').append(quality);
        if (any) {
            sb.append(':');
            for (int i = 0; i < gems.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(gems.get(i) == null ? "-" : gems.get(i));
            }
        }
        return sb.toString();
    }

    /** Тот же вариант, но с другим качеством/камнями; сворачивается в base, если ничего не отличается. */
    public static String variantId(Content content, Gear g, Quality q, List<String> gems) {
        Gear b = content.gear(g.base());
        boolean any = false;
        for (String x : gems) if (x != null) any = true;
        if (q == b.quality() && !any) return b.id();
        return variantId(g.base(), q.ordinal(), gems);
    }

    /** Разбор id-варианта (см. {@link #variantId}); null — не вариант или ошибка. Вызывает Content при поиске вещи. */
    static Gear resolve(Content content, String id) {
        int at = id.indexOf('@');
        if (at <= 0) return null;
        String baseId = id.substring(0, at);
        if (!content.hasBaseGear(baseId)) return null;
        Gear b = content.gear(baseId);
        String rest = id.substring(at + 1);
        int colon = rest.indexOf(':');
        Quality q;
        try {
            int qi = Integer.parseInt(colon < 0 ? rest : rest.substring(0, colon));
            if (qi < 0 || qi >= Quality.values().length) return null;
            q = Quality.values()[qi];
        } catch (NumberFormatException e) {
            return null;
        }
        List<String> gems = new ArrayList<>();
        if (colon >= 0) {
            for (String x : rest.substring(colon + 1).split(",", -1)) {
                if (x.isEmpty() || x.equals("-")) gems.add(null);
                else if (Gems.parse(content, x) == null) return null;
                else gems.add(x);
            }
        }
        while (gems.size() > q.sockets) {
            if (gems.get(gems.size() - 1) != null) return null;
            gems.remove(gems.size() - 1);
        }
        while (gems.size() < q.sockets) gems.add(null);
        return build(content, b, q, gems, id);
    }

    private static Gear build(Content content, Gear b, Quality q, List<String> gems, String id) {
        double k = q.mult / b.quality().mult;
        Stats s = b.bonus();
        int[] st = {s.maxHp(), s.maxMp(), s.atk(), s.mag(), s.def(), s.spd()};
        for (int i = 0; i < st.length; i++) if (st[i] > 0) st[i] = (int) Math.round(st[i] * k);
        String element = b.element();
        List<String> imm = new ArrayList<>(b.immunities());
        Slot slot = b.slot() == Slot.OFFHAND && !"shield".equals(b.type()) ? Slot.WEAPON : b.slot();
        for (String gid : gems) {
            if (gid == null) continue;
            Gems.Effect e = Gems.parse(content, gid).in(slot);
            Stats eb = e.bonus();
            st[0] += eb.maxHp(); st[1] += eb.maxMp(); st[2] += eb.atk(); st[3] += eb.mag(); st[4] += eb.def(); st[5] += eb.spd();
            if (slot == Slot.WEAPON && element == null && e.element() != null) element = e.element();
            if (e.immunity() != null && !imm.contains(e.immunity())) imm.add(e.immunity());
        }
        List<String> skills = new ArrayList<>(b.skills());
        for (String sk : qualitySkills(content, b, q)) if (!skills.contains(sk)) skills.add(sk);
        String name = q == b.quality() ? b.name() : b.name() + " (" + q.label + ")";
        return new Gear(id, name, b.type(), b.slot(), q, b.model(), (int) Math.round(b.power() * k), b.scale(), element,
                new Stats(st[0], st[1], st[2], st[3], st[4], st[5]), List.copyOf(imm), List.copyOf(skills), b.twoHanded(),
                b.description(), b.id(), java.util.Collections.unmodifiableList(gems));
    }

    /** Навыки, которые вещь получает на качестве q (накопительно: эпическая получает и навыки ниже). */
    public static List<String> qualitySkills(Content content, Gear b, Quality q) {
        List<String> out = new ArrayList<>();
        Rules r = content.gearRules();
        String typeKey = b.accessory() ? "accessory" : b.slot().armor() ? "armor" : b.type();
        for (Map<Quality, String> t : java.util.Arrays.asList(r.typeSkills().get(typeKey), r.itemSkills().get(b.id()))) {
            if (t == null) continue;
            t.forEach((tq, sk) -> {
                if (tq.ordinal() <= q.ordinal() && !out.contains(sk)) out.add(sk);
            });
        }
        return out;
    }

    /** Иконка по умолчанию: тип оружия или слот брони (Describe.gearIcon); "icon" в json её заменяет. */
    private static String defaultIcon(Gear g) {
        return Describe.gearIcon(g);
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
                Json.str(m, "description", ""), id, List.of());
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
