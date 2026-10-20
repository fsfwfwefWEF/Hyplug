package dev.jrpg.battle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Драгоценные камни (gems.json): виды (рубин, сапфир…) и уровни (осколок, камень, огранённый, безупречный).
 * Камень вставляется в гнездо вещи. Что он даёт, зависит от вещи: в оружии — одно, в броне (и щите) — другое,
 * в аксессуаре — третье. Прибавка к статам умножается на силу уровня (1, 2, 3, 5). Камень в рюкзаке —
 * предмет вида «Камни» с id gem_&lt;вид&gt;_&lt;уровень&gt;.
 *
 * Здесь же лут: камни и качество выпавших вещей по силе боя (обычные враги, сильная группа ★, босс).
 */
public final class Gems {
    public static final String PREFIX = "gem_";

    /** Уровень камня: сила (множитель прибавок), цвет рамки, приставка к имени. */
    public record Tier(int level, String name, int value, String color) {}

    /** Действие камня в одном виде вещей: прибавка к статам (за единицу силы), стихия удара, иммунитет. */
    public record Effect(Stats bonus, String element, String immunity, String text) {
        public static final Effect NONE = new Effect(Stats.ZERO, null, null, "");
    }

    /** Вид камня. names — имя на каждом уровне («Осколок рубина», «Рубин», …). */
    public record Type(String id, String color, String icon, List<String> names, Effect weapon, Effect armor,
                       Effect accessory) {}

    /** Камень в рюкзаке: вид и уровень (1..). */
    public record Gem(Type type, Tier tier) {
        public String id() { return itemId(type.id(), tier.level()); }

        public String name() {
            int i = tier.level() - 1;
            return i < type.names().size() ? type.names().get(i) : tier.name() + " " + type.id();
        }

        /** Что камень даёт в вещи этого слота (уже умноженное на силу уровня). */
        public Effect in(Equipment.Slot slot) {
            Effect e = slot == Equipment.Slot.WEAPON ? type.weapon()
                    : slot == Equipment.Slot.ACC1 || slot == Equipment.Slot.ACC2 ? type.accessory() : type.armor();
            if (e == null) return Effect.NONE;
            return new Effect(e.bonus().times(tier.value()), e.element(), e.immunity(), e.text());
        }
    }

    /** Что может выпасть в бою этой силы. */
    public record LootRule(double gemChance, int gems, List<Integer> tiers, double up1, double up2,
                           double bonusGearChance, Equipment.Quality bonusGearQuality) {
        static final LootRule NONE = new LootRule(0, 0, List.of(1), 0, 0, 0, Equipment.Quality.COMMON);
    }

    private Gems() {}

    public static String itemId(String type, int level) {
        return PREFIX + type + "_" + level;
    }

    /** Разбор id камня; null — не камень (или неизвестный вид/уровень). */
    public static Gem parse(Content content, String id) {
        if (id == null || !id.startsWith(PREFIX)) return null;
        int us = id.lastIndexOf('_');
        if (us <= PREFIX.length()) return null;
        Type t = content.gemType(id.substring(PREFIX.length(), us));
        if (t == null) return null;
        int level;
        try {
            level = Integer.parseInt(id.substring(us + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        Tier tier = content.gemTier(level);
        return tier == null ? null : new Gem(t, tier);
    }

    // ---------- загрузка ----------

    public static void load(Content content, String json) {
        Map<String, Object> root = Json.obj(Json.parse(json));
        content.clearGems();
        List<Tier> tiers = new ArrayList<>();
        int lv = 0;
        for (Object o : Json.list(root, "tiers")) {
            Map<String, Object> m = Json.obj(o);
            tiers.add(new Tier(++lv, Json.str(m, "name", "Камень"), (int) Json.num(m, "value", lv),
                    Json.str(m, "color", "#c9ced8")));
        }
        if (tiers.isEmpty()) throw new IllegalArgumentException("gems.json: нет tiers");
        content.gemTiers(tiers);
        Map<String, Object> types = Json.obj(root.get("gems"));
        for (Map.Entry<String, Object> e : types.entrySet()) {
            Map<String, Object> m = Json.obj(e.getValue());
            Type t = new Type(e.getKey(), Json.str(m, "color", "#ffffff"), Json.str(m, "icon", "gem_" + e.getKey()),
                    Json.strings(m, "names"), effect(m.get("weapon")), effect(m.get("armor")), effect(m.get("accessory")));
            content.add(t);
            for (Effect ef : List.of(t.weapon(), t.armor(), t.accessory())) if (ef.immunity() != null) content.status(ef.immunity());
            for (Tier tier : tiers) {
                Gem g = new Gem(t, tier);
                content.add(new Item(g.id(), g.name(), null, Item.Kind.GEM, describe(content, g)));
                content.icon(g.id(), t.icon());
            }
        }
        Map<String, LootRule> loot = new LinkedHashMap<>();
        if (root.get("loot") instanceof Map<?, ?> lm) {
            Json.obj(lm).forEach((k, v) -> {
                Map<String, Object> r = Json.obj(v);
                List<Integer> ts = new ArrayList<>();
                for (Object x : Json.list(r, "tiers")) ts.add(((Number) x).intValue());
                if (ts.isEmpty()) ts.add(1);
                List<?> up = r.get("upgrade") instanceof List<?> l ? l : List.of();
                loot.put(k, new LootRule(Json.num(r, "gemChance", 0), (int) Json.num(r, "gems", 1), ts,
                        up.size() > 0 ? ((Number) up.get(0)).doubleValue() : 0,
                        up.size() > 1 ? ((Number) up.get(1)).doubleValue() : 0,
                        Json.num(r, "bonusGear", 0), Equipment.Quality.parse(Json.str(r, "bonusQuality", "rare"))));
            });
        }
        content.gemLoot(loot);
    }

    private static Effect effect(Object o) {
        if (!(o instanceof Map<?, ?>)) return Effect.NONE;
        Map<String, Object> m = Json.obj(o);
        Stats s = new Stats((int) Json.num(m, "hp", 0), (int) Json.num(m, "mp", 0), (int) Json.num(m, "atk", 0),
                (int) Json.num(m, "mag", 0), (int) Json.num(m, "def", 0), (int) Json.num(m, "spd", 0));
        return new Effect(s, Json.str(m, "element", null), Json.str(m, "immunity", null), Json.str(m, "text", ""));
    }

    /** Описание камня для рюкзака: что он даёт в оружии, броне и аксессуаре. */
    public static String describe(Content content, Gem g) {
        return "Уровень " + g.tier().level() + " (" + g.tier().name().toLowerCase(java.util.Locale.ROOT) + ", сила "
                + g.tier().value() + ").\nВ оружии: " + text(content, g.in(Equipment.Slot.WEAPON))
                + "\nВ броне и щите: " + text(content, g.in(Equipment.Slot.CHEST))
                + "\nВ аксессуаре: " + text(content, g.in(Equipment.Slot.ACC1))
                + "\nВставить: меню героя или кузнец. Достать без потери — у кузнеца. 3 одинаковых — у кузнеца в 1 уровнем выше.";
    }

    /** «+2 атаки, удар огнём». */
    public static String text(Content content, Effect e) {
        List<String> parts = new ArrayList<>();
        String b = Describe.bonus(e.bonus());
        if (!b.isEmpty()) parts.add(b);
        if (e.element() != null) parts.add("удар: " + Element.name(e.element()).toLowerCase(java.util.Locale.ROOT));
        if (e.immunity() != null) parts.add("иммунитет: " + (content != null && content.hasStatus(e.immunity())
                ? content.status(e.immunity()).name().toLowerCase(java.util.Locale.ROOT) : e.immunity()));
        if (!e.text().isEmpty()) parts.add(e.text());
        return parts.isEmpty() ? "ничего" : String.join(", ", parts);
    }

    // ---------- лут ----------

    /**
     * Доработать выпавшее: вещи получают качество (шанс поднять на 1–2 ступени), добавляются камни и, для сильных
     * боёв, вещь выбранного качества. rule — ключ из gems.json → loot: "normal", "elite", "boss".
     */
    public static Map<String, Integer> enrich(Content content, Map<String, Integer> got, String rule, Random rnd) {
        return enrich(content, got, rule, rnd, true);
    }

    /** extras == false — только качество выпавших вещей, без камней и лишней вещи. */
    public static Map<String, Integer> enrich(Content content, Map<String, Integer> got, String rule, Random rnd,
                                              boolean extras) {
        LootRule r = content.gemLoot().getOrDefault(rule, LootRule.NONE);
        Map<String, Integer> out = new LinkedHashMap<>();
        got.forEach((id, n) -> {
            if (!content.hasGear(id) || id.indexOf('@') >= 0) {
                out.merge(id, n, Integer::sum);
                return;
            }
            for (int k = 0; k < n; k++) {
                Equipment.Gear g = content.gear(id);
                int q = g.quality().ordinal();
                double x = rnd.nextDouble();
                if (x < r.up2()) q += 2;
                else if (x < r.up1() + r.up2()) q += 1;
                out.merge(Equipment.variantId(g.base(), Math.min(q, Equipment.Quality.values().length - 1), List.of()), 1,
                        Integer::sum);
            }
        });
        if (extras && r.gemChance() > 0 && !content.gemTypes().isEmpty()) {
            List<Type> types = new ArrayList<>(content.gemTypes());
            for (int k = 0; k < Math.max(1, r.gems()); k++) {
                if (rnd.nextDouble() >= r.gemChance()) continue;
                Type t = types.get(rnd.nextInt(types.size()));
                int level = r.tiers().get(rnd.nextInt(r.tiers().size()));
                if (content.gemTier(level) == null) continue;
                out.merge(itemId(t.id(), level), 1, Integer::sum);
            }
        }
        if (extras && r.bonusGearChance() > 0 && rnd.nextDouble() < r.bonusGearChance()) {
            List<Equipment.Gear> all = new ArrayList<>(content.gearList());
            if (!all.isEmpty()) {
                Equipment.Gear g = all.get(rnd.nextInt(all.size()));
                int q = Math.max(g.quality().ordinal(), r.bonusGearQuality().ordinal());
                out.merge(Equipment.variantId(g.base(), q, List.of()), 1, Integer::sum);
            }
        }
        return out;
    }
}
