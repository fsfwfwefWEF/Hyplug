package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jrpg.battle.GameData;
import dev.jrpg.battle.Json;
import dev.jrpg.battle.Progression;
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
        Map<String, Object> root = Json.obj(Json.parse(read(plugin)));
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : root.entrySet()) {
            if (e.getKey().startsWith("_") || !(e.getValue() instanceof List<?> l)) continue;
            List<Map<String, Object>> steps = new ArrayList<>();
            for (Object o : l) if (o instanceof Map<?, ?>) steps.add(Json.obj(o));
            out.put(e.getKey(), steps);
        }
        return out;
    }

    private static String read(JrpgPlugin plugin) throws Exception {
        Path f = plugin.dataDir().resolve(FILE);
        if (!Files.exists(f)) {
            Files.createDirectories(f.getParent());
            Files.writeString(f, GameData.builtin(FILE));
        }
        return Files.readString(f);
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
        Ref<EntityStore> caster = Fx.spawnPuppet(store, role, name, home, casterYaw, name, look);
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
