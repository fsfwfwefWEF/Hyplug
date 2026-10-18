package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jrpg.battle.Progression;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Компаньоны (Лира, Брам) ходят за игроком вне боя. Это те же NPC-куклы, что и в бою (Frozen — без своего
 * ИИ), но мод каждые {@link #TICK_MS} мс сдвигает их к месту за спиной игрока: шагом вблизи, бегом издалека,
 * телепортом, если игрок убежал совсем далеко. Позиция ставится через TransformComponent, как у обычных
 * NPC (клиент сглаживает движение), поза (идёт/бежит/стоит) — для анимации; дошёл — стоит, а не бежит на месте.
 * На время боя компаньоны убираются (их место занимают куклы на арене) и возвращаются после.
 *
 * Защита от копий (в 0.7.0 при входе в мир появлялось по 10 союзников): на игрока не больше одной задачи
 * в очереди мира, после входа и после каждого спавна — пауза, пропавшую куклу не пересоздаём сразу,
 * а раз в пару секунд убираем вокруг игрока «чужие» NPC с именем компаньона (остатки прошлых версий).
 */
final class CompanionFollowers {
    static final String FLAG = "follow";
    private static final long TICK_MS = 50;
    /** После входа в мир ждём, пока прогрузится место вокруг игрока. */
    private static final long JOIN_GRACE_MS = 3000;
    /** Не чаще одного спавна компаньонов на игрока за это время. */
    private static final long SPAWN_COOLDOWN_MS = 5000;
    private static final long SWEEP_EVERY_MS = 2000;
    private static final double SWEEP_RADIUS = 96;
    /** Близко к месту — стоим (без дёрганий на месте). */
    private static final double ARRIVED = 0.35;

    private final JrpgPlugin plugin;
    private final Map<UUID, Group> groups = new ConcurrentHashMap<>();
    private final Set<UUID> paused = ConcurrentHashMap.newKeySet();
    /** Игроки, для которых шаг уже стоит в очереди мира. */
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> firstSeen = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextSpawn = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextSweep = new ConcurrentHashMap<>();

    private static final class Follower {
        final String heroId;
        final Ref<EntityStore> ref;
        final Vector3d pos;
        float yaw;
        String state = "idle";

        Follower(String heroId, Ref<EntityStore> ref, Vector3d pos, float yaw) {
            this.heroId = heroId;
            this.ref = ref;
            this.pos = pos;
            this.yaw = yaw;
        }
    }

    private static final class Group {
        final Store<EntityStore> store;
        final World world;
        final List<Follower> list = new ArrayList<>();
        /** Куда «смотрит» колонна: направление движения игрока (стоит — прежнее). */
        double faceX = 0, faceZ = 1;
        Vector3d lastPlayer;

        Group(Store<EntityStore> store, World world) {
            this.store = store;
            this.world = world;
        }
    }

    CompanionFollowers(JrpgPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        plugin.scheduler().scheduleAtFixedRate(this::tickSafe, 1500, TICK_MS, TimeUnit.MILLISECONDS);
    }

    /** Бой начался — убрать компаньонов (вызывать в потоке мира). */
    void pause(UUID player) {
        paused.add(player);
        Group g = groups.remove(player);
        if (g != null) despawn(g);
    }

    /** Бой закончился — компаньоны вернутся на следующем тике (не раньше паузы после спавна). */
    void resume(UUID player) {
        paused.remove(player);
        nextSpawn.put(player, System.currentTimeMillis() + 800);
    }

    /** Игрок вышел. */
    void forget(UUID player) {
        paused.remove(player);
        firstSeen.remove(player);
        Group g = groups.remove(player);
        if (g != null) runIn(g.world, () -> despawn(g));
    }

    /** Снаряжение поменяли в меню — переодеть идущих за игроком компаньонов. */
    void redress(UUID player) {
        Group g = groups.get(player);
        if (g == null) return;
        runIn(g.world, () -> {
            for (Follower f : g.list) {
                try {
                    GearVisuals.dressNpc(g.store, f.ref, plugin.partyOf(player).hero(f.heroId), plugin.content());
                } catch (IllegalArgumentException ignored) {
                    // героя убрали из данных
                }
            }
        });
    }

    void clearAll() {
        for (UUID id : List.copyOf(groups.keySet())) forget(id);
    }

    private void tickSafe() {
        try {
            tick();
        } catch (Throwable t) {
            Fx.warnOnce("Ошибка компаньонов", t);
        }
    }

    private void tick() {
        Settings.Followers cfg = plugin.settings().follow;
        Set<UUID> online = new HashSet<>();
        long now = System.currentTimeMillis();
        for (PlayerRef p : Universe.get().getPlayers()) {
            if (!p.isValid()) continue;
            UUID id = p.getUuid();
            online.add(id);
            Ref<EntityStore> ref = p.getReference();
            if (ref == null || !ref.isValid()) continue; // игрок ещё входит в мир (или меняет мир)
            if (firstSeen.putIfAbsent(id, now) == null && !plugin.sessions().containsKey(id)) {
                // Бой мог оборваться (вылет сервера) с уменьшенным телом игрока — вернуть размер при входе.
                Store<EntityStore> st = ref.getStore();
                runIn(st.getExternalData().getWorld(), () -> {
                    Float sc = Fx.scale(st, ref);
                    if (sc != null && sc < 0.2f) Fx.setScale(st, ref, 1.0f);
                }, () -> { });
            }
            boolean want = cfg.enabled && !paused.contains(id) && !plugin.sessions().containsKey(id)
                    && plugin.flag(id, FLAG, true);
            Group g = groups.get(id);
            Store<EntityStore> store = ref.getStore();
            World world = store.getExternalData().getWorld();
            if (g != null && (!want || g.store != store)) {
                forget(id); // выключили, начался бой или сменился мир — уберём (в новом мире появятся заново)
                firstSeen.put(id, now);
                continue;
            }
            if (!want) continue;
            if (!queued.add(id)) continue; // прошлый шаг ещё не выполнен — не копим задачи
            runIn(world, () -> {
                try {
                    step(id, ref, store, world);
                } finally {
                    queued.remove(id);
                }
            }, () -> queued.remove(id));
        }
        for (UUID id : List.copyOf(groups.keySet())) if (!online.contains(id)) forget(id);
        firstSeen.keySet().retainAll(online);
    }

    private void step(UUID id, Ref<EntityStore> playerRef, Store<EntityStore> store, World world) {
        if (!playerRef.isValid() || paused.contains(id) || plugin.sessions().containsKey(id)) return;
        long now = System.currentTimeMillis();
        Vector3d me = Fx.position(store, playerRef);
        Group g = groups.get(id);
        if (now >= nextSweep.getOrDefault(id, 0L)) {
            nextSweep.put(id, now + SWEEP_EVERY_MS);
            sweep(store, me, g);
        }
        if (g == null) {
            if (now - firstSeen.getOrDefault(id, now) < JOIN_GRACE_MS) return;
            if (now < nextSpawn.getOrDefault(id, 0L)) return;
            nextSpawn.put(id, now + SPAWN_COOLDOWN_MS);
            g = spawn(id, store, world, me);
            if (g == null) return;
            groups.put(id, g);
        }
        // Пропавшую куклу (выгрузка чанка и т.п.) просто забываем; всю группу — заново после паузы.
        g.list.removeIf(f -> !f.ref.isValid());
        if (g.list.isEmpty()) {
            groups.remove(id, g);
            return;
        }
        Settings.Followers cfg = plugin.settings().follow;
        if (g.lastPlayer != null) {
            double mx = me.x - g.lastPlayer.x, mz = me.z - g.lastPlayer.z, len = Math.hypot(mx, mz);
            if (len > 0.02) {
                // Сглаживаем поворот колонны, чтобы компаньоны не метались при каждом шаге в сторону.
                g.faceX = g.faceX * 0.8 + mx / len * 0.2;
                g.faceZ = g.faceZ * 0.8 + mz / len * 0.2;
                double f = Math.hypot(g.faceX, g.faceZ);
                g.faceX /= f;
                g.faceZ /= f;
            }
        }
        g.lastPlayer = new Vector3d(me);
        double dt = TICK_MS / 1000.0;
        for (int i = 0; i < g.list.size(); i++) {
            Follower f = g.list.get(i);
            Vector3d slot = slot(me, g, i, g.list.size(), cfg.distance);
            double dx = slot.x - f.pos.x, dz = slot.z - f.pos.z, dist = Math.hypot(dx, dz);
            if (dist > cfg.teleportFrom || Math.abs(me.y - f.pos.y) > 8) {
                Vector3d to = ground(world, slot, me.y);
                f.pos.set(to);
                f.yaw = Fx.yawTowards(to, me);
                Fx.teleport(store, f.ref, to, f.yaw);
                setState(store, f, "idle");
                continue;
            }
            if (dist < ARRIVED) {
                setState(store, f, "idle");
                continue;
            }
            boolean run = dist > cfg.runFrom;
            double speed = run ? cfg.runSpeed : cfg.walkSpeed;
            // У самой цели замедляемся, чтобы не проскакивать и не дёргаться.
            double stepLen = Math.min(dist, speed * dt * Math.min(1.0, 0.4 + dist / 1.5));
            double nx = f.pos.x + dx / dist * stepLen, nz = f.pos.z + dz / dist * stepLen;
            Double gy = Fx.groundY(world, nx, nz, f.pos.y);
            double ny = gy != null && Math.abs(gy - f.pos.y) <= 1.6 ? gy : f.pos.y + Math.max(-0.5, Math.min(0.5, me.y - f.pos.y));
            float targetYaw = (float) Math.atan2(-dx, -dz);
            f.yaw = turn(f.yaw, targetYaw, 0.35f);
            f.pos.set(nx, ny, nz);
            Fx.place(store, f.ref, f.pos, f.yaw);
            setState(store, f, run ? "run" : "walk");
        }
    }

    /**
     * Убрать вокруг игрока NPC с именем компаньона, которые не наши: копии, оставшиеся от 0.7.0
     * (могли сохраниться в мире и проснуться без заморозки), и любые потерянные куклы.
     */
    private void sweep(Store<EntityStore> store, Vector3d me, Group g) {
        Set<String> names = companionNames();
        if (names.isEmpty()) return;
        Set<Integer> keep = new HashSet<>();
        if (g != null) for (Follower f : g.list) if (f.ref.isValid()) keep.add(f.ref.getIndex());
        List<Ref<EntityStore>> strays = new ArrayList<>();
        try {
            store.forEachChunk(Nameplate.getComponentType(), (chunk, cb) -> {
                for (int i = 0; i < chunk.size(); i++) {
                    Nameplate n = chunk.getComponent(i, Nameplate.getComponentType());
                    if (n == null || n.getText() == null || !names.contains(n.getText().trim())) continue;
                    TransformComponent t = chunk.getComponent(i, TransformComponent.getComponentType());
                    if (t == null || t.getPosition().distanceSquared(me) > SWEEP_RADIUS * SWEEP_RADIUS) continue;
                    Ref<EntityStore> r = chunk.getReferenceTo(i);
                    if (r == null || keep.contains(r.getIndex())) continue;
                    strays.add(r);
                }
            });
        } catch (Throwable t) {
            Fx.warnOnce("Не удалось проверить лишних компаньонов", t);
            return;
        }
        int removed = 0;
        for (Ref<EntityStore> r : strays) {
            if (!r.isValid() || plugin.isBattlePuppet(r)) continue;
            if (store.getComponent(r, Player.getComponentType()) != null) continue; // игрок с таким ником
            Fx.remove(store, r);
            removed++;
        }
        if (removed > 0) plugin.log("Убраны лишние копии компаньонов: " + removed);
    }

    private Set<String> companionNames() {
        Set<String> out = new HashSet<>();
        for (Progression.HeroDef d : plugin.content().heroes()) {
            if (!d.id().equals("hero")) out.add(d.name());
        }
        return out;
    }

    /** Место компаньона i за спиной игрока: полукругом позади по направлению движения. */
    private static Vector3d slot(Vector3d me, Group g, int i, int n, double distance) {
        double rx = g.faceZ, rz = -g.faceX; // «вправо» от направления движения
        double side = n <= 1 ? 0 : (i - (n - 1) / 2.0) * 1.8;
        return new Vector3d(me.x - g.faceX * distance + rx * side, me.y, me.z - g.faceZ * distance + rz * side);
    }

    private static float turn(float from, float to, float k) {
        double d = to - from;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d < -Math.PI) d += 2 * Math.PI;
        return (float) (from + d * k);
    }

    private static Vector3d ground(World world, Vector3d at, double nearY) {
        Double y = Fx.groundY(world, at.x, at.z, nearY);
        return new Vector3d(at.x, y != null && Math.abs(y - nearY) < 6 ? y : nearY, at.z);
    }

    private Group spawn(UUID id, Store<EntityStore> store, World world, Vector3d me) {
        Group g = new Group(store, world);
        List<Progression.Hero> heroes = plugin.partyOf(id).heroes().stream().filter(h -> !h.def.id().equals("hero")).toList();
        for (int i = 0; i < heroes.size(); i++) {
            Progression.Hero h = heroes.get(i);
            String role = plugin.settings().companionRoles.get(h.def.id());
            if (role == null) continue;
            Vector3d at = ground(world, slot(me, g, i, heroes.size(), plugin.settings().follow.distance), me.y);
            float yaw = Fx.yawTowards(at, me);
            HumanLook.Look look = HumanLook.of(h.def.id(), plugin.settings().companionLooks.get(h.def.id()));
            Ref<EntityStore> ref = Fx.spawnPuppet(store, role, h.def.name(), at, yaw, h.def.name(), look);
            if (ref == null) continue;
            GearVisuals.dressNpc(store, ref, h, plugin.content());
            g.list.add(new Follower(h.def.id(), ref, new Vector3d(at), yaw));
        }
        if (g.list.isEmpty()) return null;
        return g;
    }

    private void despawn(Group g) {
        for (Follower f : g.list) Fx.remove(g.store, f.ref);
        g.list.clear();
    }

    /** Поза движения для анимации: idle / walk / run (шлём только при смене). */
    private static void setState(Store<EntityStore> store, Follower f, String state) {
        if (state.equals(f.state)) return;
        f.state = state;
        Fx.setMovement(store, f.ref, state);
    }

    private void runIn(World world, Runnable r) {
        runIn(world, r, null);
    }

    private void runIn(World world, Runnable r, Runnable onFail) {
        try {
            world.execute(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    Fx.warnOnce("Ошибка компаньонов", t);
                }
            });
        } catch (Throwable t) {
            if (onFail != null) onFail.run();
            Fx.warnOnce("Мир недоступен для компаньонов", t);
        }
    }
}
