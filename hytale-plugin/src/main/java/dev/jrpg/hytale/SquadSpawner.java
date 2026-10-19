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
 * NPC отряда — такие же «куклы», как в бою (неуязвимы, без своего ИИ), но не стоят столбом:
 * бродят шагом вокруг своего места (settings.json → squadWander).
 */
final class SquadSpawner {
    static final String ID_PREFIX = "squad:";
    private static final long RETRY_MS = 5000;
    /** Скорость, с которой бродят NPC отряда, блоков в секунду. */
    private static final double WANDER_SPEED = 1.4;

    /** Отряд в мире. encounter — готовая встреча для боя (центр — где стоит отряд). */
    static final class Squad {
        final String id;
        final Squads.Template template;
        final String world;
        final Vector3d pos;
        final List<Ref<EntityStore>> npcs = new ArrayList<>();
        /** Где каждый NPC появился (бродит вокруг) и до какого времени стоит, отдыхая. */
        final List<Vector3d> homes = new ArrayList<>();
        final List<Long> restUntil = new ArrayList<>();
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
        if (plugin.settings().squadWander) for (Squad s : list) if (s.world.equals(world.getName())) wander(s, store, world, now);
        for (Squad s : list) {
            if (s.world.equals(world.getName()) && dist2(s.pos, pos) <= cfg.triggerRadius * cfg.triggerRadius
                    && Math.abs(s.pos.y - pos.y) < 6) return s;
        }
        if (!canSpawn || !cfg.enabled || list.size() >= cfg.maxPerPlayer) return null;
        Long last = lastAttempt.get(p.getUuid());
        if (last != null && now - last < cfg.spawnEverySeconds * 1000) return null;
        lastAttempt.put(p.getUuid(), now);
        if (random.nextDouble() < cfg.spawnChance
                && spawnNear(p, store, world, pos, null, cfg.minDistance, cfg.maxDistance, true) == null) {
            // Не нашлось ровной земли (вода, лес, горы) — попробуем снова через 5 секунд, а не через полный период.
            lastAttempt.put(p.getUuid(), now - (long) (cfg.spawnEverySeconds * 1000) + RETRY_MS);
        }
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
        for (int attempt = 0; attempt < 16; attempt++) {
            double a = random.nextDouble() * Math.PI * 2, d = minD + random.nextDouble() * (maxD - minD);
            double x = pos.x + Math.cos(a) * d, z = pos.z + Math.sin(a) * d;
            if (checkZones && !cfg.allowedAt(world.getName(), x, z, manual)) continue;
            Double y = Fx.groundY(world, x, z, pos.y);
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
            Double y = Fx.groundY(world, x, z, s.pos.y);
            Vector3d at = new Vector3d(x, y == null || Math.abs(y - s.pos.y) > 4 ? s.pos.y : y, z);
            EnemyTemplate e = plugin.data().enemy(s.template.enemies().get(i));
            Ref<EntityStore> ref = Fx.spawnPuppet(store, e.role(), e.name(), at, Fx.yawTowards(at, playerPos),
                    i == n / 2 ? s.template.name() + "\n" + e.name() : e.name());
            if (ref == null) continue;
            s.npcs.add(ref);
            s.homes.add(at);
            s.restUntil.add(System.currentTimeMillis() + 1500 + random.nextInt(3000));
        }
    }

    /** NPC отряда бродят шагом в паре блоков от своего места, с передышками (раз в секунду из tick). */
    private void wander(Squad s, Store<EntityStore> store, World world, long now) {
        for (int i = 0; i < s.npcs.size(); i++) {
            Ref<EntityStore> r = s.npcs.get(i);
            if (!r.isValid() || plugin.mover().moving(r) || now < s.restUntil.get(i)) continue;
            if (random.nextDouble() < 0.4) {
                s.restUntil.set(i, now + 1000 + random.nextInt(2500));
                continue;
            }
            Vector3d home = s.homes.get(i);
            double a = random.nextDouble() * Math.PI * 2, d = 0.8 + random.nextDouble() * 2.4;
            double x = home.x + Math.cos(a) * d, z = home.z + Math.sin(a) * d;
            Double y = Fx.groundY(world, x, z, home.y);
            if (y == null || Math.abs(y - home.y) > 1.6) continue;
            Vector3d from = Fx.position(store, r), to = new Vector3d(x, y, z);
            double dist = Math.hypot(to.x - from.x, to.z - from.z);
            if (dist < 0.4) continue;
            long dur = Math.round(dist / WANDER_SPEED * 1000);
            plugin.mover().go(store, world, r, from, to, dur, false, true, null);
            s.restUntil.set(i, now + dur + 2500 + random.nextInt(4000));
        }
    }

    /** Встреча для боя с отрядом: партия встаёт на месте игрока, враги — в сторону отряда. */
    Encounter encounterFor(Squad s, Vector3d playerPos) {
        fought.put(s.id, new Fought(s.template, s.world, s.pos));
        return plugin.data().squads().encounter(s.template, s.id, s.world, playerPos.x, playerPos.y, playerPos.z);
    }

    /** С какими отрядами начинали бой: чтобы после поражения или бегства вернуть их на место. */
    private record Fought(Squads.Template template, String world, Vector3d pos) {}

    private final Map<String, Fought> fought = new ConcurrentHashMap<>();

    /** Отряд побеждён — возвращать его не нужно. */
    void forget(String encounterId) {
        fought.remove(encounterId);
    }

    /**
     * Поражение или бегство от отряда: через delayMs тот же отряд снова стоит на своём месте, и к нему
     * можно подойти ещё раз. false — это не отряд (ручные встречи и так остаются на месте).
     */
    boolean returnLater(PlayerRef p, String encounterId, long delayMs) {
        Fought f = fought.remove(encounterId);
        if (f == null) return false;
        plugin.scheduler().schedule(() -> {
            Ref<EntityStore> ref = p.getReference();
            if (!p.isValid() || ref == null || !ref.isValid()) return;
            Store<EntityStore> store = ref.getStore();
            World world = store.getExternalData().getWorld();
            world.execute(() -> {
                if (!ref.isValid() || !world.getName().equals(f.world())) return;
                Squad s = new Squad(ID_PREFIX + (++counter) + ":" + f.template().id(), f.template(), f.world(), f.pos());
                placeNpcs(s, store, world, Fx.position(store, ref));
                if (s.npcs.isEmpty()) return;
                byPlayer.computeIfAbsent(p.getUuid(), k -> new ArrayList<>()).add(s);
                plugin.log("Отряд «" + f.template().name() + "» вернулся на место для новой попытки");
                p.sendMessage(com.hypixel.hytale.server.core.Message.raw(
                        "[JRPG] «" + f.template().name() + "» снова на месте — подойди, чтобы сразиться ещё раз."));
            });
        }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        return true;
    }

    void remove(UUID player, Squad s, Store<EntityStore> store) {
        List<Squad> list = byPlayer.get(player);
        if (list != null) list.remove(s);
        for (Ref<EntityStore> r : s.npcs) {
            plugin.mover().stop(r);
            Fx.remove(store, r);
        }
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
