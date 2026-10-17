package dev.jrpg.battle;

import dev.jrpg.battle.scripts.SpiderQueenScript;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Данные мира: шаблоны врагов (enemies.json) и встречи (encounters.json).
 * Читаются из папки, если она задана (в Hytale — папка данных плагина, чтобы править без пересборки),
 * иначе из ресурсов jar ({@code /data/}).
 */
public final class GameData {
    /** Эксклюзивные Java-скрипты боссов: id из encounters.json → фабрика. Новый босс = новая строка. */
    private static final Map<String, Supplier<BattleScript>> BOSS_SCRIPTS = Map.of(
            SpiderQueenScript.ID, SpiderQueenScript::new);

    private final Content content;
    private final Map<String, EnemyTemplate> enemies = new LinkedHashMap<>();
    private final Map<String, Encounter> encounters = new LinkedHashMap<>();

    private GameData(Content content) {
        this.content = content;
    }

    /** dataDir == null → встроенные данные из jar. */
    public static GameData load(Content content, Path dataDir) throws IOException {
        GameData d = new GameData(content);
        for (Object o : (List<?>) Json.parse(read(dataDir, "enemies.json"))) {
            EnemyTemplate t = parseEnemy(Json.obj(o));
            d.enemies.put(t.id(), t);
        }
        for (Object o : (List<?>) Json.parse(read(dataDir, "encounters.json"))) {
            Encounter e = parseEncounter(Json.obj(o));
            d.encounters.put(e.id(), e);
        }
        d.validate();
        return d;
    }

    /** Текст встроенного файла данных (чтобы плагин мог выложить копию в свою папку для правки). */
    public static String builtin(String name) throws IOException {
        try (InputStream in = GameData.class.getResourceAsStream("/data/" + name)) {
            if (in == null) throw new IOException("Нет файла данных " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String read(Path dir, String name) throws IOException {
        if (dir != null && Files.exists(dir.resolve(name))) return Files.readString(dir.resolve(name));
        return builtin(name);
    }

    private static EnemyTemplate parseEnemy(Map<String, Object> m) {
        Map<String, Object> st = Json.obj(m.get("stats"));
        Stats stats = new Stats((int) Json.num(st, "maxHp", 50), (int) Json.num(st, "maxMp", 0),
                (int) Json.num(st, "atk", 10), (int) Json.num(st, "mag", 0), (int) Json.num(st, "def", 5),
                (int) Json.num(st, "spd", 10));
        return new EnemyTemplate(Json.str(m, "id", null), Json.str(m, "name", "?"), stats,
                Json.strings(m, "abilities"), Json.bool(m, "boss", false), Json.strings(m, "immunities"),
                Json.str(m, "model", null), Json.str(m, "role", null));
    }

    private static Encounter parseEncounter(Map<String, Object> m) {
        Map<String, Object> loc = Json.obj(m.get("location"));
        Encounter.Location location = new Encounter.Location(Json.str(loc, "world", "default"),
                Json.num(loc, "x", 0), Json.num(loc, "y", 0), Json.num(loc, "z", 0),
                Json.num(loc, "triggerRadius", 5));
        List<Encounter.Spot> spots = new ArrayList<>();
        for (Object o : Json.list(m, "enemies")) {
            Map<String, Object> e = Json.obj(o);
            spots.add(new Encounter.Spot(Json.str(e, "template", null), Json.num(e, "x", 0), Json.num(e, "z", 5)));
        }
        List<Encounter.Line> intro = new ArrayList<>();
        for (Object o : Json.list(m, "intro")) {
            Map<String, Object> l = Json.obj(o);
            intro.add(new Encounter.Line(Json.str(l, "speaker", null), Json.str(l, "text", "")));
        }
        List<PhaseScript.Phase> phases = new ArrayList<>();
        for (Object o : Json.list(m, "phases")) {
            Map<String, Object> p = Json.obj(o);
            List<StatusApply> self = new ArrayList<>();
            for (Object so : Json.list(p, "selfStatuses")) {
                Map<String, Object> sm = Json.obj(so);
                self.add(new StatusApply(Json.str(sm, "statusId", null), Json.num(sm, "chance", 1),
                        (int) Json.num(sm, "turns", 3)));
            }
            phases.add(new PhaseScript.Phase(Json.str(p, "boss", null), Json.num(p, "hpBelow", 0.5),
                    Json.str(p, "name", ""), Json.str(p, "say", null), Json.strings(p, "addAbilities"),
                    Json.strings(p, "removeAbilities"), self, Json.strings(p, "summon")));
        }
        List<Encounter.Loot> loot = new ArrayList<>();
        for (Object o : Json.list(m, "loot")) {
            Map<String, Object> l = Json.obj(o);
            loot.add(new Encounter.Loot(Json.str(l, "item", null), (int) Json.num(l, "count", 1),
                    Json.num(l, "chance", 1)));
        }
        return new Encounter(Json.str(m, "id", null), Json.str(m, "name", "?"), location,
                Json.bool(m, "unique", false), (int) Json.num(m, "respawnMinutes", 0),
                Json.bool(m, "canFlee", true), spots, intro, phases, Json.str(m, "script", null), loot,
                (int) Json.num(m, "exp", 0));
    }

    /** Ловим опечатки в JSON сразу при загрузке, а не посреди боя. */
    private void validate() {
        for (EnemyTemplate t : enemies.values()) {
            for (String a : t.abilities()) content.ability(a);
        }
        for (Encounter e : encounters.values()) {
            for (Encounter.Spot s : e.enemies()) enemy(s.template());
            for (PhaseScript.Phase p : e.phases()) {
                enemy(p.boss());
                if (p.addAbilities() != null) p.addAbilities().forEach(content::ability);
                if (p.summon() != null) p.summon().forEach(this::enemy);
                if (p.selfStatuses() != null) p.selfStatuses().forEach(sa -> content.status(sa.statusId()));
            }
            for (Encounter.Loot l : e.loot()) content.item(l.item());
            if (e.script() != null && !BOSS_SCRIPTS.containsKey(e.script()))
                throw new IllegalArgumentException("Встреча " + e.id() + ": неизвестный script " + e.script());
        }
    }

    public Content content() { return content; }

    public EnemyTemplate enemy(String id) {
        EnemyTemplate t = enemies.get(id);
        if (t == null) throw new IllegalArgumentException("Неизвестный враг: " + id);
        return t;
    }

    public Encounter encounter(String id) {
        Encounter e = encounters.get(id);
        if (e == null) throw new IllegalArgumentException("Неизвестная встреча: " + id);
        return e;
    }

    public List<Encounter> encounters() { return List.copyOf(encounters.values()); }

    /** Собирает бой для встречи: враги по шаблонам, фазы, эксклюзивный скрипт, реплики, запрет бегства. */
    public Battle createBattle(Encounter e, List<Combatant> party, Map<String, Integer> inventory,
                               BattleListener listener, long seed) {
        List<Combatant> all = new ArrayList<>(party);
        int n = 0;
        for (Encounter.Spot s : e.enemies()) all.add(enemy(s.template()).create(s.template() + "#" + (++n)));
        Battle battle = new Battle(content, all, inventory, listener, seed);
        battle.setFleeAllowed(e.canFlee());

        List<BattleScript> scripts = new ArrayList<>();
        if (!e.intro().isEmpty()) {
            scripts.add(new BattleScript() {
                @Override
                public void onBattleStart(Battle b) {
                    for (Encounter.Line l : e.intro()) {
                        Combatant speaker = b.combatants().stream()
                                .filter(c -> l.speaker() != null && l.speaker().equals(c.templateId))
                                .findFirst().orElse(null);
                        b.say(speaker, l.text());
                    }
                }
            });
        }
        if (!e.phases().isEmpty()) scripts.add(new PhaseScript(e.phases(), this));
        if (e.script() != null) scripts.add(BOSS_SCRIPTS.get(e.script()).get());
        battle.setScript(BattleScript.combine(scripts.toArray(BattleScript[]::new)));
        return battle;
    }

    /** Награда за победу: опыт и предметы по шансам из loot. */
    public Map<String, Integer> rollLoot(Encounter e, Random random) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Encounter.Loot l : e.loot()) {
            if (random.nextDouble() < l.chance()) out.merge(l.item(), l.count(), Integer::sum);
        }
        return out;
    }
}
