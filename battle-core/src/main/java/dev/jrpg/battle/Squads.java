package dev.jrpg.battle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Случайные отряды по миру (squads.json). Работают рядом с ручными встречами и не мешают им:
 * не появляются ближе {@link #keepAwayFromEncounters} блоков к встречам из encounters.json и в зонах noSpawnZones,
 * их можно выключить целиком ({@link #enabled}) или для отдельных миров ({@link #worlds}).
 *
 * Отряд в мире — несколько NPC, которые стоят на месте. Подошёл ближе triggerRadius — появляется окно
 * «Сражаться / Сбежать», а бой собирается из шаблона как обычная встреча ({@link #encounter}).
 */
public final class Squads {
    public record Template(String id, String name, List<String> enemies, double weight, int exp,
                           List<Encounter.Loot> loot) {}

    public record Zone(String world, double x, double z, double radius) {}

    public boolean enabled = true;
    public double spawnEverySeconds = 40, spawnChance = 0.6;
    public int maxPerPlayer = 2;
    public double minDistance = 22, maxDistance = 40, despawnDistance = 90, lifetimeMinutes = 10;
    public double triggerRadius = 7, keepAwayFromEncounters = 40;
    public final List<String> worlds = new ArrayList<>();
    public final List<Zone> noSpawnZones = new ArrayList<>();
    public final List<Template> templates = new ArrayList<>();

    public static Squads parse(String json) {
        Map<String, Object> m = Json.obj(Json.parse(json));
        Squads s = new Squads();
        s.enabled = Json.bool(m, "enabled", s.enabled);
        s.spawnEverySeconds = Json.num(m, "spawnEverySeconds", s.spawnEverySeconds);
        s.spawnChance = Json.num(m, "spawnChance", s.spawnChance);
        s.maxPerPlayer = (int) Json.num(m, "maxPerPlayer", s.maxPerPlayer);
        s.minDistance = Json.num(m, "minDistance", s.minDistance);
        s.maxDistance = Math.max(s.minDistance + 1, Json.num(m, "maxDistance", s.maxDistance));
        s.despawnDistance = Json.num(m, "despawnDistance", s.despawnDistance);
        s.lifetimeMinutes = Json.num(m, "lifetimeMinutes", s.lifetimeMinutes);
        s.triggerRadius = Json.num(m, "triggerRadius", s.triggerRadius);
        s.keepAwayFromEncounters = Json.num(m, "keepAwayFromEncounters", s.keepAwayFromEncounters);
        s.worlds.addAll(m.containsKey("worlds") ? Json.strings(m, "worlds") : List.of("*"));
        for (Object o : Json.list(m, "noSpawnZones")) {
            Map<String, Object> z = Json.obj(o);
            s.noSpawnZones.add(new Zone(Json.str(z, "world", "*"), Json.num(z, "x", 0), Json.num(z, "z", 0),
                    Json.num(z, "radius", 32)));
        }
        for (Object o : Json.list(m, "squads")) {
            Map<String, Object> t = Json.obj(o);
            List<Encounter.Loot> loot = new ArrayList<>();
            for (Object lo : Json.list(t, "loot")) {
                Map<String, Object> l = Json.obj(lo);
                loot.add(new Encounter.Loot(Json.str(l, "item", null), (int) Json.num(l, "count", 1),
                        Json.num(l, "chance", 1)));
            }
            s.templates.add(new Template(Json.str(t, "id", "squad"), Json.str(t, "name", "Отряд"),
                    Json.strings(t, "enemies"), Json.num(t, "weight", 1), (int) Json.num(t, "exp", 20), loot));
        }
        return s;
    }

    /** Случайный шаблон с учётом weight; null — шаблонов нет. */
    public Template pick(Random random) {
        double total = templates.stream().mapToDouble(Template::weight).sum();
        if (total <= 0) return null;
        double r = random.nextDouble() * total;
        for (Template t : templates) {
            r -= t.weight();
            if (r <= 0) return t;
        }
        return templates.get(templates.size() - 1);
    }

    /** Можно ли поставить отряд в точку: мир разрешён, не в запретной зоне и не рядом с ручной встречей. */
    public boolean allowedAt(String world, double x, double z, List<Encounter> manual) {
        if (!enabled) return false;
        if (worlds.stream().noneMatch(w -> w.equals("*") || w.equalsIgnoreCase(world))) return false;
        for (Zone zone : noSpawnZones) {
            if (!zone.world().equals("*") && !zone.world().equalsIgnoreCase(world)) continue;
            if (Math.hypot(x - zone.x(), z - zone.z()) < zone.radius()) return false;
        }
        for (Encounter e : manual) {
            Encounter.Location l = e.location();
            if (!l.world().equals("*") && !l.world().equalsIgnoreCase(world)) continue;
            if (Math.hypot(x - l.x(), z - l.z()) < keepAwayFromEncounters) return false;
        }
        return true;
    }

    /**
     * Встреча для боя с отрядом. (x, y, z) — где стоит партия; враги, как и во встречах из encounters.json,
     * встают на арене напротив (z от 9 до 10), полукругом.
     */
    public Encounter encounter(Template t, String instanceId, String world, double x, double y, double z) {
        List<Encounter.Spot> spots = new ArrayList<>();
        int n = t.enemies().size();
        for (int i = 0; i < n; i++) {
            double sx = (i - (n - 1) / 2.0) * 4.0;
            spots.add(new Encounter.Spot(t.enemies().get(i), sx, 10 - Math.abs(sx) * 0.15));
        }
        return new Encounter(instanceId, t.name(), new Encounter.Location(world, x, y, z, triggerRadius),
                false, 0, true, spots, List.of(), List.of(), null, t.loot(), t.exp());
    }

    /** Ловим опечатки в squads.json при загрузке. */
    void validate(GameData data) {
        for (Template t : templates) {
            if (t.enemies().isEmpty()) throw new IllegalArgumentException("Отряд " + t.id() + ": нет врагов");
            if (t.enemies().size() > 5) throw new IllegalArgumentException("Отряд " + t.id() + ": больше 5 врагов");
            t.enemies().forEach(data::enemy);
            t.loot().forEach(l -> data.content().item(l.item()));
        }
    }
}
