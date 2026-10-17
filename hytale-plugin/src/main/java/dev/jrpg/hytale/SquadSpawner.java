package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jrpg.battle.EnemyTemplate;
import dev.jrpg.battle.Encounter;
import dev.jrpg.battle.Squads;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Случайные отряды врагов вокруг игроков (настройки и шаблоны — squads.json, см. {@link Squads}).
 *
 * Раз в секунду (из {@link EncounterWatcher}, в потоке мира игрока): убираем далёкие и старые отряды,
 * иногда ставим новый на землю в minDistance..maxDistance блоков от игрока, и если игрок подошёл
 * к отряду ближе triggerRadius — возвращаем его, чтобы показать окно «Сражаться / Сбежать».
 * NPC отряда — такие же «куклы», как в бою: стоят на месте и неуязвимы.
 */
final class SquadSpawner {
    static final String ID_PREFIX = "squad:";

    /** Отряд в мире. encounter — готовая встреча для боя (центр — где стоит отряд). */
    static final class Squad {
        final String id;
        final Squads.Template template;
        final String world;
        final Vector3d pos;
        final List<Ref<EntityStore>> npcs = new ArrayList<>();
        final long bornAt = System.currentTimeMillis();

        Squad(String id, Squads.Template template, String world, Vector3d pos) {
            this.id = id;
            this.template = template;
            this.world = world;
            this.pos = pos;
        }
    }

    private final JrpgPlugin plugin;
    private final Map<UUID, List<Squad>> byPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAttempt = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private int counter;

    SquadSpawner(JrpgPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Обслуживание отрядов игрока. canSpawn — можно ли ставить новые (игрок не в бою и не сразу после боя).
     * Возвращает отряд, к которому игрок подошёл вплотную, или null.
     */
    Squad tick(PlayerRef p, Store<EntityStore> store, World world, Vector3d pos, boolean canSpawn) {
        Squads cfg = plugin.data().squads();
        List<Squad> list = byPlayer.computeIfAbsent(p.getUuid(), k -> new ArrayList<>());
        long now = System.currentTimeMillis();
        for (Squad s : List.copyOf(list)) {
            boolean gone = s.npcs.stream().noneMatch(Ref::isValid);
            boolean far = !s.world.equals(world.getName()) || dist2(s.pos, pos) > cfg.despawnDistance * cfg.despawnDistance;
            boolean old = now - s.bornAt > cfg.lifetimeMinutes * 60_000;
            if (gone || far || old || !cfg.enabled) remove(p.getUuid(), s, store);
        }
        for (Squad s : list) {
            if (s.world.equals(world.getName()) && dist2(s.pos, pos) <= cfg.triggerRadius * cfg.triggerRadius
                    && Math.abs(s.pos.y - pos.y) < 6) return s;
        }
        if (!canSpawn || !cfg.enabled || list.size() >= cfg.maxPerPlayer) return null;
        Long last = lastAttempt.get(p.getUuid());
        if (last != null && now - last < cfg.spawnEverySeconds * 1000) return null;
        lastAttempt.put(p.getUuid(), now);
        if (random.nextDouble() < cfg.spawnChance) spawnNear(p, store, world, pos, null, cfg.minDistance, cfg.maxDistance, true);
        return null;
    }

    /**
     * Поставить отряд в кольце minD..maxD вокруг pos. templateId == null — случайный по весам.
     * checkZones — соблюдать запретные зоны и расстояние до ручных встреч (команда /jrpg squad их игнорирует).
     */
    Squad spawnNear(PlayerRef p, Store<EntityStore> store, World world, Vector3d pos, String templateId,
                    double minD, double maxD, boolean checkZones) {
        Squads cfg = plugin.data().squads();
        Squads.Template t = templateId == null ? cfg.pick(random)
                : cfg.templates.stream().filter(x -> x.id().equalsIgnoreCase(templateId)).findFirst().orElse(null);
        if (t == null) return null;
        List<Encounter> manual = plugin.data().encounters();
        for (int attempt = 0; attempt < 8; attempt++) {
            double a = random.nextDouble() * Math.PI * 2, d = minD + random.nextDouble() * (maxD - minD);
            double x = pos.x + Math.cos(a) * d, z = pos.z + Math.sin(a) * d;
            if (checkZones && !cfg.allowedAt(world.getName(), x, z, manual)) continue;
            Double y = Fx.groundY(world, x, z);
            if (y == null || Math.abs(y - pos.y) > 24) continue;
            Squad s = new Squad(ID_PREFIX + (++counter) + ":" + t.id(), t, world.getName(), new Vector3d(x, y, z));
            placeNpcs(s, store, world, pos);
            if (s.npcs.isEmpty()) return null;
            byPlayer.computeIfAbsent(p.getUuid(), k -> new ArrayList<>()).add(s);
            plugin.log("Отряд «" + t.name() + "» появился у " + p.getUsername() + String.format(" (%.0f %.0f %.0f)", x, y, z));
            return s;
        }
        return null;
    }

    /** NPC отряда стоят полукругом лицом к игроку, каждый на своей высоте земли. */
    private void placeNpcs(Squad s, Store<EntityStore> store, World world, Vector3d playerPos) {
        double dx = playerPos.x - s.pos.x, dz = playerPos.z - s.pos.z, len = Math.max(0.01, Math.hypot(dx, dz));
        double fx = dx / len, fz = dz / len, rx = -fz, rz = fx;
        int n = s.template.enemies().size();
        for (int i = 0; i < n; i++) {
            double side = (i - (n - 1) / 2.0) * 2.6, back = Math.abs(side) * 0.3;
            double x = s.pos.x + rx * side - fx * back, z = s.pos.z + rz * side - fz * back;
            Double y = Fx.groundY(world, x, z);
            Vector3d at = new Vector3d(x, y == null ? s.pos.y : y, z);
            EnemyTemplate e = plugin.data().enemy(s.template.enemies().get(i));
            Ref<EntityStore> ref = Fx.spawnPuppet(store, e.role(), e.name(), at, Fx.yawTowards(at, playerPos));
            if (ref == null) continue;
            Fx.nameplate(store, ref, i == n / 2 ? s.template.name() + "\n" + e.name() : e.name());
            s.npcs.add(ref);
        }
    }

    /** Встреча для боя с отрядом: партия встаёт на месте игрока, враги — в сторону отряда. */
    Encounter encounterFor(Squad s, Vector3d playerPos) {
        return plugin.data().squads().encounter(s.template, s.id, s.world, playerPos.x, playerPos.y, playerPos.z);
    }

    void remove(UUID player, Squad s, Store<EntityStore> store) {
        List<Squad> list = byPlayer.get(player);
        if (list != null) list.remove(s);
        for (Ref<EntityStore> r : s.npcs) Fx.remove(store, r);
        s.npcs.clear();
    }

    List<Squad> squadsOf(UUID player) {
        return List.copyOf(byPlayer.getOrDefault(player, List.of()));
    }

    /** Убрать все отряды (перезагрузка данных, выключение сервера). Вызывать в потоке мира. */
    void clear(UUID player, Store<EntityStore> store) {
        for (Squad s : squadsOf(player)) remove(player, s, store);
    }

    Iterable<UUID> players() {
        return List.copyOf(byPlayer.keySet());
    }

    private static double dist2(Vector3d a, Vector3d b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return dx * dx + dz * dz;
    }
}
