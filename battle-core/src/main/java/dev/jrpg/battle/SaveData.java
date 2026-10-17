package dev.jrpg.battle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Сохранение прогресса в JSON: прокачка партии и рюкзак игрока, прогресс мира (побеждённые боссы).
 * Чтение терпимое: незнакомые герои, предметы и узлы сетки пропускаются (данные мода могли поменяться).
 */
public final class SaveData {
    public static final int VERSION = 1;

    private SaveData() {}

    /** Сохранение игрока: партия, рюкзак и мелкие настройки (flags: например, «компаньоны ходят за мной»). */
    public static String writePlayer(Progression.Party party, Map<String, Integer> inventory, Map<String, Object> flags) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        Map<String, Object> heroes = new LinkedHashMap<>();
        for (Progression.Hero h : party.heroes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", h.level);
            m.put("exp", h.exp);
            m.put("points", h.points);
            m.put("unlocked", new ArrayList<>(h.unlocked));
            heroes.put(h.def.id(), m);
        }
        root.put("heroes", heroes);
        Map<String, Object> inv = new LinkedHashMap<>();
        inventory.forEach((k, v) -> { if (v != null && v > 0) inv.put(k, v); });
        root.put("inventory", inv);
        root.put("flags", flags == null ? Map.of() : flags);
        return Json.write(root);
    }

    /** Разобранное сохранение игрока. */
    public record Player(Map<String, Object> heroes, Map<String, Integer> inventory, Map<String, Object> flags) {
        public boolean flag(String k, boolean def) {
            return flags.get(k) instanceof Boolean b ? b : def;
        }
    }

    public static Player readPlayer(String json) {
        Map<String, Object> root = Json.obj(Json.parse(json));
        Map<String, Object> heroes = root.get("heroes") == null ? Map.of() : Json.obj(root.get("heroes"));
        Map<String, Integer> inv = new LinkedHashMap<>();
        if (root.get("inventory") != null) {
            Json.obj(root.get("inventory")).forEach((k, v) -> {
                if (v instanceof Number n && n.intValue() > 0) inv.put(k, n.intValue());
            });
        }
        Map<String, Object> flags = root.get("flags") == null ? Map.of() : Json.obj(root.get("flags"));
        return new Player(heroes, inv, flags);
    }

    /** Применить сохранение к свежей партии. */
    public static void applyParty(Player save, Progression.Party party) {
        for (Progression.Hero h : party.heroes()) {
            Object o = save.heroes().get(h.def.id());
            if (o == null) continue;
            Map<String, Object> m = Json.obj(o);
            h.level = Math.max(1, Math.min(Progression.MAX_LEVEL, (int) Json.num(m, "level", 1)));
            h.exp = Math.max(0, (int) Json.num(m, "exp", 0));
            h.points = Math.max(0, (int) Json.num(m, "points", Progression.START_POINTS));
            h.unlocked.clear();
            h.unlocked.add(0);
            for (Object n : Json.list(m, "unlocked")) {
                int i = ((Number) n).intValue();
                if (i >= 0 && i < Progression.Grid.SIZE) h.unlocked.add(i);
            }
        }
    }

    /** Рюкзак из сохранения: только предметы, которые есть в моде. */
    public static Map<String, Integer> inventory(Player save, Content content) {
        Map<String, Integer> out = new LinkedHashMap<>();
        save.inventory().forEach((k, v) -> { if (content.hasItem(k)) out.put(k, v); });
        return out;
    }

    public static String writeWorld(WorldProgress p) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        List<String> unique = new ArrayList<>(p.defeatedUnique);
        unique.sort(null);
        root.put("defeatedUnique", unique);
        root.put("clearedAtMillis", new LinkedHashMap<>(p.clearedAtMillis));
        return Json.write(root);
    }

    public static void readWorld(String json, WorldProgress into) {
        Map<String, Object> root = Json.obj(Json.parse(json));
        into.defeatedUnique.addAll(Json.strings(root, "defeatedUnique"));
        if (root.get("clearedAtMillis") != null) {
            Json.obj(root.get("clearedAtMillis")).forEach((k, v) -> {
                if (v instanceof Number n) into.clearedAtMillis.put(k, n.longValue());
            });
        }
    }
}
