package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jrpg.battle.Ability;
import dev.jrpg.battle.Content;
import dev.jrpg.battle.GameData;
import dev.jrpg.battle.Json;
import dev.jrpg.battle.Progression;
import dev.jrpg.battle.TargetType;
import org.joml.Vector3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Таймлайны навыков (data/sequences.json, копия для правки — в папке мода) и проверка сцены вне боя:
 * /jrpg seq &lt;id&gt;[:герой][:slow]. Перед игроком встают кастующий (кукла героя с его снаряжением) и три
 * мишени, камера играет сцену, «удар» показывает цифры и реакцию мишеней, потом всё убирается.
 * Файл перечитывается при каждом запуске — цифры можно править без перезапуска сервера.
 */
final class Sequences {
    static final String FILE = "sequences.json";
    private static final Map<UUID, SkillSequence> active = new ConcurrentHashMap<>();
    private static final Random RND = new Random();

    private Sequences() {}

    /** Все сцены из файла: id → шаги; ключи с «_» — служебные. */
    static Map<String, List<Map<String, Object>>> load(JrpgPlugin plugin) throws Exception {
        Map<String, Object> root = root(plugin);
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : root.entrySet()) {
            if (e.getKey().startsWith("_") || !(e.getValue() instanceof List<?> l)) continue;
            out.put(e.getKey(), steps(l));
        }
        return out;
    }

    private static List<Map<String, Object>> steps(List<?> l) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Object o : l) if (o instanceof Map<?, ?>) steps.add(Json.obj(o));
        return steps;
    }

    // ---------- навыки в бою ----------

    private static volatile Map<String, Object> cached;
    private static volatile long cachedStamp = Long.MIN_VALUE;

    /** Разобранный файл; перечитывается, когда файл в папке мода изменился. */
    private static Map<String, Object> root(JrpgPlugin plugin) throws Exception {
        Path f = plugin.dataDir().resolve(FILE);
        long stamp = Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : -1;
        Map<String, Object> r = cached;
        if (r != null && stamp == cachedStamp) return r;
        r = Json.obj(Json.parse(read(plugin)));
        cached = r;
        cachedStamp = Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : -1;
        return r;
    }

    /** Цвета стихий и смыслов для шаблонов (свечение, кольца, частицы). */
    private static final Map<String, String> COLORS = Map.ofEntries(
            Map.entry("fire", "#ff7a2a"), Map.entry("flame", "#ff7a2a"), Map.entry("fireball", "#ff7a2a"),
            Map.entry("meteor", "#ff7a2a"), Map.entry("ice", "#8fd8ff"), Map.entry("thunder", "#ffe45a"),
            Map.entry("lightning", "#ffe45a"), Map.entry("water", "#4aa8ff"), Map.entry("earth", "#c09a62"),
            Map.entry("poison", "#8ee04a"), Map.entry("acid", "#8ee04a"), Map.entry("heal", "#7dff9a"),
            Map.entry("buff", "#ffd27a"), Map.entry("guard", "#8fb8ff"), Map.entry("taunt", "#ff5a3a"),
            Map.entry("haste", "#9affea"), Map.entry("shadow", "#b0102a"), Map.entry("slash", "#e8ecf4"),
            Map.entry("physical", "#e8ecf4"));

    /**
     * Сцена навыка для боя: своя (ключ = id навыка), иначе шаблон из _templates — заданный в _skills или
     * подобранный по навыку. null — играть навык как раньше (обычная атака, защита, предметы, файл сломан).
     * vfx — фигура эффекта (Vfx.kindFor), lead — её полёт в мс.
     */
    static List<Map<String, Object>> forAbility(JrpgPlugin plugin, Ability ab, String element, String vfx, long lead) {
        if (ab == null || ab.id().equals(Content.ATTACK) || ab.id().equals(Content.DEFEND) || ab.id().startsWith("item")) {
            return null;
        }
        Map<String, Object> root;
        try {
            root = root(plugin);
        } catch (Exception e) {
            Fx.warnOnce("Ошибка в " + FILE + " — навыки играются без сцен", e);
            return null;
        }
        String id = ab.id();
        if (root.get(id) instanceof List<?> own) return steps(own);
        Map<String, Object> templates = root.get("_templates") instanceof Map<?, ?> m ? Json.obj(m) : Map.of();
        Map<String, Object> skill = root.get("_skills") instanceof Map<?, ?> m && m.get(id) instanceof Map<?, ?> s
                ? Json.obj(s) : Map.of();
        String name = Json.str(skill, "template", autoTemplate(ab));
        if (!(templates.get(name) instanceof List<?> tpl)) {
            Fx.warnOnce("Нет шаблона сцены «" + name + "» (" + FILE + " → _templates)", null);
            return null;
        }
        Map<String, Object> params = new java.util.HashMap<>();
        if (root.get("_defaults") instanceof Map<?, ?> d) params.putAll(Json.obj(d));
        String key = element == null || element.isEmpty() ? vfx : element;
        params.put("color", COLORS.getOrDefault(vfx, COLORS.getOrDefault(key, String.valueOf(params.getOrDefault("color", "#ffd27a")))));
        params.put("vfx", vfx == null ? "" : vfx);
        params.put("lead", Math.max(150, lead));
        params.put("fx", fxFor(ab, element));
        params.putAll(skill);
        String v = String.valueOf(params.get("vfx"));
        if (!skill.containsKey("lead")) params.put("lead", Math.max(150, Vfx.leadMs(v)));
        if (!skill.containsKey("color") && COLORS.containsKey(v) && (element == null || element.isEmpty()
                || element.equals(dev.jrpg.battle.Element.PHYSICAL))) params.put("color", COLORS.get(v));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : tpl) {
            if (o instanceof Map<?, ?> st) out.add(Json.obj(fill(st, params)));
        }
        return out;
    }

    /** Шаблон по навыку: ближний удар, удар по всем, снаряд, магия по всем, лечение, усиление. */
    static String autoTemplate(Ability ab) {
        TargetType t = ab.target();
        boolean dmg = ab.damagePower() > 0;
        boolean heal = ab.healPower() > 0 || ab.mpRestore() > 0 || !ab.cures().isEmpty();
        return switch (t) {
            case ALL_ENEMIES -> dmg && !ab.magical() ? "melee_all" : "magic_all";
            case SINGLE_ENEMY -> dmg && !ab.magical() ? "melee" : "bolt";
            case SINGLE_ALLY -> heal ? "heal" : "buff_one";
            case ALL_ALLIES -> heal ? "heal_all" : "buff_all";
            default -> "buff_self";
        };
    }

    private static String fxFor(Ability ab, String element) {
        if (ab.damagePower() > 0) {
            return element == null || element.isEmpty() ? "@physical" : "@" + element;
        }
        if (ab.healPower() > 0 || ab.mpRestore() > 0 || !ab.cures().isEmpty()) return "@heal";
        return "@buff";
    }

    /** Подстановка {имя}: строка целиком — значение как есть (число, список), внутри строки — текстом. */
    private static Object fill(Object o, Map<String, Object> params) {
        if (o instanceof String s) {
            if (s.length() > 2 && s.startsWith("{") && s.endsWith("}") && s.indexOf('{', 1) < 0) {
                String k = s.substring(1, s.length() - 1);
                if (params.containsKey(k)) return params.get(k);
            }
            String r = s;
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if (r.indexOf('{') < 0) break;
                Object v = e.getValue();
                String text = v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                        ? String.valueOf(n.longValue()) : String.valueOf(v);
                r = r.replace("{" + e.getKey() + "}", text);
            }
            return r;
        }
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object v = fill(e.getValue(), params);
                // Пустой параметр убирает действие (нет частиц, нет фигуры).
                if (v instanceof String s && s.isEmpty() && e.getValue() instanceof String raw && raw.contains("{")) continue;
                out.put(String.valueOf(e.getKey()), v);
            }
            return out;
        }
        if (o instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object x : l) out.add(fill(x, params));
            return out;
        }
        return o;
    }

    /** Для админ-меню: свои сцены и все навыки героев (их сцены — по шаблонам). id → подпись. */
    static Map<String, String> skillScenes(JrpgPlugin plugin) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (String id : load(plugin).keySet()) out.put(id, label(plugin, id) + "  ·  своя сцена");
        } catch (Exception e) {
            Fx.warnOnce("Ошибка в " + FILE, e);
        }
        try {
            if (root(plugin).get("_skills") instanceof Map<?, ?> m) {
                for (Object k : m.keySet()) {
                    String a = String.valueOf(k);
                    if (plugin.content().hasAbility(a)) out.putIfAbsent(a, label(plugin, a) + "  ·  шаблон");
                }
            }
        } catch (Exception ignored) {
            // файл сломан — выше уже предупредили
        }
        for (Progression.HeroDef h : plugin.content().heroes()) {
            for (String a : h.startAbilities()) out.putIfAbsent(a, label(plugin, a) + "  ·  " + h.name());
            for (Progression.GridNode n : h.grid()) {
                if (n.ref() != null && plugin.content().hasAbility(n.ref())) {
                    out.putIfAbsent(n.ref(), label(plugin, n.ref()) + "  ·  " + h.name());
                }
            }
        }
        return out;
    }

    private static String label(JrpgPlugin plugin, String id) {
        try {
            return plugin.content().ability(id).name();
        } catch (Exception e) {
            return id;
        }
    }

    private static String read(JrpgPlugin plugin) throws Exception {
        Path f = plugin.dataDir().resolve(FILE);
        String builtin = GameData.builtin(FILE);
        if (!Files.exists(f)) {
            Files.createDirectories(f.getParent());
            Files.writeString(f, builtin);
            return builtin;
        }
        String text = Files.readString(f);
        // Встроенный файл новее (поле _version): старую копию — в sequences.v<N>.json, на её место новую.
        int have = version(text), want = version(builtin);
        if (have < want) {
            Files.move(f, f.resolveSibling("sequences.v" + have + ".json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(f, builtin);
            plugin.log("sequences.json обновлён до версии " + want + ", старый сохранён как sequences.v" + have + ".json");
            return builtin;
        }
        return text;
    }

    private static int version(String json) {
        try {
            Object v = Json.obj(Json.parse(json)).get("_version");
            return v instanceof Number n ? n.intValue() : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    /** Кто кастует сцену по умолчанию (_casters в файле), иначе герой, у которого есть навык с таким id. */
    private static String caster(JrpgPlugin plugin, String seqId) {
        try {
            Map<String, Object> root = Json.obj(Json.parse(read(plugin)));
            Object c = root.get("_casters");
            if (c instanceof Map<?, ?> m && m.get(seqId) != null) return String.valueOf(m.get(seqId));
        } catch (Exception ignored) {
            // ниже — по навыкам
        }
        for (Progression.HeroDef h : plugin.content().heroes()) {
            if (h.startAbilities().contains(seqId)) return h.id();
            for (Progression.GridNode n : h.grid()) if (seqId.equals(n.ref())) return h.id();
        }
        return "hero";
    }

    /** Запустить сцену перед игроком. null — запущена, иначе текст ошибки. */
    static String test(JrpgPlugin plugin, PlayerRef playerRef, Ref<EntityStore> me, Store<EntityStore> store, World world,
                       String arg) {
        UUID uuid = playerRef.getUuid();
        if (plugin.sessions().containsKey(uuid)) return "Сначала закончи бой.";
        if (active.containsKey(uuid)) return "Сцена ещё идёт.";
        Map<String, List<Map<String, Object>>> all;
        try {
            all = load(plugin);
        } catch (Exception e) {
            return "Ошибка в " + FILE + ": " + e.getMessage();
        }
        String[] parts = arg.split(":");
        String id = parts[0];
        // Навык без своей сцены — по шаблону (как в бою).
        if (!all.containsKey(id) && plugin.content().hasAbility(id)) {
            Ability ab = plugin.content().ability(id);
            String element = plugin.content().element(ab);
            String vfx = Vfx.kindFor(ab, element, ab.damagePower() > 0 && !ab.magical());
            List<Map<String, Object>> st = forAbility(plugin, ab, element, vfx, Vfx.leadMs(vfx));
            if (st != null) all.put(id, st);
        }
        if (id.equals("list") || !all.containsKey(id)) {
            return (id.equals("list") ? "" : "Нет сцены «" + id + "». ") + "Сцены: " + String.join(", ", all.keySet())
                    + ". Запуск: /jrpg seq meteor, герой и замедление: /jrpg seq meteor:lyra:slow";
        }
        String who = null;
        double slow = 1;
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].equalsIgnoreCase("slow")) slow = 3;
            else who = parts[i];
        }
        if (who == null) who = caster(plugin, id);
        Progression.Hero hero;
        try {
            hero = plugin.partyOf(uuid).hero(who);
        } catch (IllegalArgumentException e) {
            hero = null;
        }

        // Расстановка: кастующий в 3 блоках перед игроком, три мишени ещё в 7 блоках дальше.
        Vector3d p = Fx.position(store, me);
        float yaw = 0;
        HeadRotation head = store.getComponent(me, HeadRotation.getComponentType());
        if (head != null) yaw = head.getRotation().yaw();
        double fx = -Math.sin(yaw), fz = -Math.cos(yaw), rx = -fz, rz = fx;
        Vector3d home = ground(world, p.x + fx * 3, p.z + fz * 3, p.y);
        List<Vector3d> spots = new ArrayList<>();
        for (int k = -1; k <= 1; k++) {
            spots.add(ground(world, home.x + fx * 7 + rx * k * 2.2, home.z + fz * 7 + rz * k * 2.2, p.y));
        }
        Vector3d tc = spots.get(1);
        float casterYaw = Fx.yawTowards(home, tc);

        var s = plugin.settings();
        boolean isHero = who.equals("hero");
        HumanLook.Look look = isHero ? HumanLook.ofPlayer(store, me) : HumanLook.of(who, s.companionLooks.get(who));
        String role = s.companionRoles.getOrDefault(who, s.companionRoles.getOrDefault("bram", s.fallbackRole));
        String name = hero != null ? hero.def.name() : who;
        // Подпись пустая: с именем компаньона кукла попадает под уборку лишних копий (CompanionFollowers.sweep).
        Ref<EntityStore> caster = Fx.spawnPuppet(store, role, name, home, casterYaw, "", look);
        if (caster == null) return "Кастующий не появился (роль " + role + "), см. лог.";
        if (hero != null) GearVisuals.dressNpc(store, caster, hero, plugin.content());

        String enemyRole;
        try {
            enemyRole = plugin.data().enemy("goblin").role();
        } catch (Exception e) {
            enemyRole = s.fallbackRole;
        }
        List<Ref<EntityStore>> targets = new ArrayList<>();
        for (Vector3d sp : spots) {
            Ref<EntityStore> t = Fx.spawnPuppet(store, enemyRole, "Мишень", sp, Fx.yawTowards(sp, home), "Мишень");
            if (t != null) targets.add(t);
        }
        if (targets.isEmpty()) {
            Fx.remove(store, caster);
            return "Мишени не появились (роль " + enemyRole + "), см. лог.";
        }
        Ref<EntityStore> main = targets.get(targets.size() / 2);

        List<String> hints = s.anim.heroes.getOrDefault(who, Map.of()).getOrDefault("weapon", List.of());
        BattleCamera camera = new BattleCamera(playerRef, s.camera, home, tc);
        camera.action(home, tc);
        List<Ref<EntityStore>> puppets = new ArrayList<>(targets);
        puppets.add(caster);
        SkillSequence seq = new SkillSequence(plugin, id, all.get(id), playerRef, me, store, world, camera,
                new SkillSequence.Cast(caster, main, List.copyOf(targets), home, casterYaw, hints), slow,
                new SkillSequence.Hooks() {
                    @Override
                    public void hit(List<Ref<EntityStore>> on) {
                        for (Ref<EntityStore> t : on) {
                            Fx.combatText(store, me, t, String.valueOf(80 + RND.nextInt(90)), "#ffd27a");
                            Fx.animate(store, t, s.anim.hurt);
                        }
                    }

                    @Override
                    public void done(SkillSequence q) {
                        plugin.scheduler().schedule(() -> {
                            try {
                                world.execute(() -> {
                                    camera.reset();
                                    for (Ref<EntityStore> r : puppets) {
                                        plugin.mover().forget(r);
                                        Fx.remove(store, r);
                                    }
                                    active.remove(uuid);
                                    playerRef.sendMessage(Message.raw("Сцена " + q.id + " закончена"
                                            + (q.missing().isEmpty() ? ", всё нашлось." : ". Не нашлось: "
                                            + String.join("; ", q.missing()))));
                                });
                            } catch (Throwable t) {
                                active.remove(uuid);
                            }
                        }, 600, java.util.concurrent.TimeUnit.MILLISECONDS);
                    }
                });
        active.put(uuid, seq);
        seq.start(700);
        playerRef.sendMessage(Message.raw("Сцена " + id + " (" + name + (slow > 1 ? ", замедление" : "")
                + "). Шаги пишутся в лог сервера."));
        return null;
    }

    /** Оборвать сцену игрока (вышел из игры). */
    static void cancel(UUID player) {
        SkillSequence s = active.remove(player);
        if (s != null) s.cancel();
    }

    private static Vector3d ground(World world, double x, double z, double nearY) {
        Double y = Fx.groundY(world, x, z, nearY);
        return new Vector3d(x, y == null || Math.abs(y - nearY) > 4 ? nearY : y, z);
    }
}
