package dev.jrpg.battle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Работа с вещами у кузнеца и в меню героя: вставить камень в гнездо, достать (камень не ломается),
 * улучшить качество за материалы и камни, слить 3 одинаковых камня в 1 уровнем выше.
 *
 * Вещь лежит либо в рюкзаке (inv), либо надета на героя (hero + slot). Каждая операция меняет id вещи
 * (качество и камни записаны в id, см. {@link Equipment#variantId}) и кладёт новую на то же место.
 */
public final class Forge {
    /** Где лежит вещь: hero == null — в рюкзаке. */
    public record Place(Progression.Hero hero, Equipment.Slot slot, String id) {
        public static Place bag(String id) {
            return new Place(null, null, id);
        }

        public static Place worn(Progression.Hero h, Equipment.Slot slot) {
            return new Place(h, slot, h.equipped.get(slot));
        }
    }

    /** Итог операции: error == null — получилось; id — новая вещь. */
    public record Result(String error, String id, String message) {
        static Result fail(String why) {
            return new Result(why, null, why);
        }
    }

    private Forge() {}

    /** Вставить камень gemId из рюкзака в гнездо socket (-1 — первое свободное). */
    public static Result insert(Content content, Map<String, Integer> inv, Place p, String gemId, int socket) {
        if (p.id() == null || !content.hasGear(p.id())) return Result.fail("нет вещи");
        Gems.Gem gem = Gems.parse(content, gemId);
        if (gem == null) return Result.fail("это не камень");
        Equipment.Gear g = content.gear(p.id());
        if (g.sockets() == 0) return Result.fail(g.name() + ": нет гнёзд (качество " + g.quality().label + ")");
        int i = socket >= 0 ? socket : g.freeSocket();
        if (i < 0) return Result.fail("все гнёзда заняты — сначала достаньте камень у кузнеца");
        if (i >= g.sockets()) return Result.fail("нет такого гнезда");
        if (g.gem(i) != null) return Result.fail("гнездо занято");
        synchronized (inv) {
            if (inv.getOrDefault(gemId, 0) <= 0) return Result.fail("камня нет в рюкзаке");
            List<String> gems = sockets(g);
            gems.set(i, gemId);
            String nid = Equipment.variantId(content, g, g.quality(), gems);
            if (!replace(inv, p, nid)) return Result.fail("вещи нет на месте");
            take(inv, gemId);
            return new Result(null, nid, gem.name() + " вставлен: " + Gems.text(content, gem.in(slotOf(g))));
        }
    }

    /** Достать камень из гнезда socket: камень возвращается в рюкзак целым. */
    public static Result extract(Content content, Map<String, Integer> inv, Place p, int socket) {
        if (p.id() == null || !content.hasGear(p.id())) return Result.fail("нет вещи");
        Equipment.Gear g = content.gear(p.id());
        String gemId = g.gem(socket);
        if (gemId == null) return Result.fail("гнездо пустое");
        synchronized (inv) {
            List<String> gems = sockets(g);
            gems.set(socket, null);
            String nid = Equipment.variantId(content, g, g.quality(), gems);
            if (!replace(inv, p, nid)) return Result.fail("вещи нет на месте");
            inv.merge(gemId, 1, Integer::sum);
            return new Result(null, nid, content.item(gemId).name() + " возвращён в рюкзак");
        }
    }

    /** Цена улучшения вещи до следующего качества; null — уже легендарная. */
    public static Equipment.Cost upgradeCost(Content content, Equipment.Gear g) {
        Equipment.Quality next = g.quality().next();
        if (next == null) return null;
        return content.gearRules().upgrade().getOrDefault(next, new Equipment.Cost(3 * next.ordinal(), 0));
    }

    /** Сколько у игрока любых материалов и любых камней. */
    public static int[] stock(Content content, Map<String, Integer> inv) {
        int m = 0, gm = 0;
        synchronized (inv) {
            for (Map.Entry<String, Integer> e : inv.entrySet()) {
                if (!content.hasItem(e.getKey()) || e.getValue() == null) continue;
                Item.Kind k = content.item(e.getKey()).kind();
                if (k == Item.Kind.MATERIAL) m += e.getValue();
                else if (k == Item.Kind.GEM) gm += e.getValue();
            }
        }
        return new int[] {m, gm};
    }

    /** Улучшить качество на ступень: берёт любые материалы (сначала тех, что больше) и любые камни (сначала слабые). */
    public static Result upgrade(Content content, Map<String, Integer> inv, Place p) {
        if (p.id() == null || !content.hasGear(p.id())) return Result.fail("нет вещи");
        Equipment.Gear g = content.gear(p.id());
        Equipment.Quality next = g.quality().next();
        if (next == null) return Result.fail("лучше легендарного не бывает");
        Equipment.Cost cost = upgradeCost(content, g);
        synchronized (inv) {
            int[] have = stock(content, inv);
            if (have[0] < cost.materials() || have[1] < cost.gems()) {
                return Result.fail("нужно материалов " + cost.materials() + " (есть " + have[0] + ")"
                        + (cost.gems() > 0 ? ", камней " + cost.gems() + " (есть " + have[1] + ")" : ""));
            }
            List<String> gems = sockets(g);
            while (gems.size() < next.sockets) gems.add(null);
            String nid = Equipment.variantId(content, g, next, gems);
            if (!content.hasGear(nid)) return Result.fail("не получилось собрать вещь " + nid);
            if (!replace(inv, p, nid)) return Result.fail("вещи нет на месте");
            spend(content, inv, Item.Kind.MATERIAL, cost.materials());
            spend(content, inv, Item.Kind.GEM, cost.gems());
            Equipment.Gear ng = content.gear(nid);
            String skills = "";
            List<String> added = new ArrayList<>(ng.skills());
            added.removeAll(g.skills());
            if (!added.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (String s : added) names.add(content.ability(s).name());
                skills = ", новый навык: " + String.join(", ", names);
            }
            return new Result(null, nid, g.name() + " → " + next.label + " (гнёзд " + next.sockets + skills + ")");
        }
    }

    /** Слить 3 одинаковых камня в 1 уровнем выше. */
    public static Result merge(Content content, Map<String, Integer> inv, String gemId) {
        Gems.Gem gem = Gems.parse(content, gemId);
        if (gem == null) return Result.fail("это не камень");
        Gems.Tier up = content.gemTier(gem.tier().level() + 1);
        if (up == null) return Result.fail("это уже лучший уровень");
        synchronized (inv) {
            if (inv.getOrDefault(gemId, 0) < 3) return Result.fail("нужно 3 одинаковых камня");
            for (int i = 0; i < 3; i++) take(inv, gemId);
            String nid = Gems.itemId(gem.type().id(), up.level());
            inv.merge(nid, 1, Integer::sum);
            return new Result(null, nid, "3 × " + gem.name() + " → " + content.item(nid).name());
        }
    }

    /** Слот, по которому камень выбирает действие (щит — броня, остальное во второй руке — оружие). */
    public static Equipment.Slot slotOf(Equipment.Gear g) {
        return g.slot() == Equipment.Slot.OFFHAND && !"shield".equals(g.type()) ? Equipment.Slot.WEAPON : g.slot();
    }

    private static List<String> sockets(Equipment.Gear g) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < g.sockets(); i++) out.add(g.gem(i));
        return out;
    }

    private static boolean replace(Map<String, Integer> inv, Place p, String nid) {
        if (p.hero() != null) {
            if (!p.id().equals(p.hero().equipped.get(p.slot()))) return false;
            p.hero().equipped.put(p.slot(), nid);
            return true;
        }
        if (inv.getOrDefault(p.id(), 0) <= 0) return false;
        take(inv, p.id());
        inv.merge(nid, 1, Integer::sum);
        return true;
    }

    private static void spend(Content content, Map<String, Integer> inv, Item.Kind kind, int n) {
        while (n > 0) {
            String best = null;
            int bestN = 0, bestTier = Integer.MAX_VALUE;
            for (Map.Entry<String, Integer> e : inv.entrySet()) {
                if (e.getValue() == null || e.getValue() <= 0 || !content.hasItem(e.getKey())) continue;
                if (content.item(e.getKey()).kind() != kind) continue;
                if (kind == Item.Kind.GEM) {
                    int t = Gems.parse(content, e.getKey()).tier().level();
                    if (t < bestTier) { bestTier = t; best = e.getKey(); }
                } else if (e.getValue() > bestN) {
                    bestN = e.getValue();
                    best = e.getKey();
                }
            }
            if (best == null) return;
            take(inv, best);
            n--;
        }
    }

    private static void take(Map<String, Integer> inv, String id) {
        int n = inv.getOrDefault(id, 0);
        if (n <= 1) inv.remove(id);
        else inv.put(id, n - 1);
    }
}
