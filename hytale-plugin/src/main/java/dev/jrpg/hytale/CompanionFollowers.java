package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jrpg.battle.Progression;
import org.joml.Vector3d;

import java.util.ArrayList;
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
 * NPC (клиент сглаживает движение), а состояние движения (шаг/бег/стоит) — для анимации ходьбы.
 * На время боя компаньоны убираются (их место занимают куклы на арене) и возвращаются после.
 */
final class CompanionFollowers {
    static final String FLAG = "follow";
    private static final long TICK_MS = 50;
    /** Близко к месту — стоим (без дёрганий на месте). */
    private static final double ARRIVED = 0.35;

    private final JrpgPlugin plugin;
    private final Map<UUID, Group> groups = new ConcurrentHashMap<>();
    private final Set<UUID> paused = ConcurrentHashMap.newKeySet();

    private static final class Follower {
        final String heroId;
        final Ref<EntityStore> ref;
        final Vector3d pos;
        float yaw;
        String state = "";

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

    /** Бой закончился — компаньоны вернутся на следующем тике. */
    void resume(UUID player) {
        paused.remove(player);
    }

    /** Игрок вышел. */
    void forget(UUID player) {
        paused.remove(player);
        Group g = groups.remove(player);
        if (g != null) runIn(g.world, () -> despawn(g));
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
        Set<UUID> online = new java.util.HashSet<>();
        for (PlayerRef p : Universe.get().getPlayers()) {
            if (!p.isValid()) continue;
            UUID id = p.getUuid();
            online.add(id);
            Ref<EntityStore> ref = p.getReference();
            boolean want = cfg.enabled && ref != null && ref.isValid() && !paused.contains(id)
                    && !plugin.sessions().containsKey(id) && plugin.flag(id, FLAG, true);
            Group g = groups.get(id);
            if (!want) {
                if (g != null) forget(id);
                continue;
            }
            Store<EntityStore> store = ref.getStore();
            World world = store.getExternalData().getWorld();
            if (g != null && g.store != store) {
                forget(id); // сменился мир — пересоздадим в новом
                g = null;
            }
            Group group = g;
            runIn(world, () -> step(id, ref, store, world, group));
        }
        for (UUID id : List.copyOf(groups.keySet())) if (!online.contains(id)) forget(id);
    }

    private void step(UUID id, Ref<EntityStore> playerRef, Store<EntityStore> store, World world, Group known) {
        if (!playerRef.isValid() || paused.contains(id) || plugin.sessions().containsKey(id)) return;
        Group g = known != null ? known : groups.get(id);
        Vector3d me = Fx.position(store, playerRef);
        if (g == null) {
            g = spawn(id, store, world, me);
            if (g == null) return;
            groups.put(id, g);
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
            if (!f.ref.isValid()) {
                // Куклу убрал кто-то другой (выгрузка чанка и т.п.) — пересоздадим всю группу.
                groups.remove(id);
                despawn(g);
                return;
            }
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
                // Пришёл: стоит и смотрит туда же, куда идёт игрок (или на игрока, если тот рядом стоит).
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
            move(store, f);
            setState(store, f, run ? "run" : "walk");
        }
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
            Ref<EntityStore> ref = Fx.spawnPuppet(store, role, h.def.name(), at, yaw);
            if (ref == null) continue;
            Fx.nameplate(store, ref, h.def.name());
            g.list.add(new Follower(h.def.id(), ref, new Vector3d(at), yaw));
        }
        return g.list.isEmpty() ? null : g;
    }

    private void despawn(Group g) {
        for (Follower f : g.list) Fx.remove(g.store, f.ref);
        g.list.clear();
    }

    private static void move(Store<EntityStore> store, Follower f) {
        try {
            TransformComponent t = store.getComponent(f.ref, TransformComponent.getComponentType());
            if (t == null) return;
            t.setPosition(f.pos);
            t.getRotation().setYaw(f.yaw);
            HeadRotation head = store.getComponent(f.ref, HeadRotation.getComponentType());
            if (head != null) head.getRotation().setYaw(f.yaw);
        } catch (Throwable e) {
            Fx.warnOnce("Компаньон не двигается", e);
        }
    }

    /** Состояние движения для анимации: idle / walk / run. */
    private static void setState(Store<EntityStore> store, Follower f, String state) {
        if (state.equals(f.state)) return;
        f.state = state;
        try {
            MovementStatesComponent mc = store.getComponent(f.ref, MovementStatesComponent.getComponentType());
            if (mc == null) return;
            MovementStates ms = mc.getMovementStates();
            ms.idle = state.equals("idle");
            ms.horizontalIdle = ms.idle;
            ms.walking = state.equals("walk");
            ms.running = state.equals("run");
            ms.onGround = true;
            ms.falling = false;
        } catch (Throwable e) {
            Fx.warnOnce("Анимация ходьбы компаньона не сработала", e);
        }
    }

    private void runIn(World world, Runnable r) {
        try {
            world.execute(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    Fx.warnOnce("Ошибка компаньонов", t);
                }
            });
        } catch (Throwable t) {
            Fx.warnOnce("Мир недоступен для компаньонов", t);
        }
    }
}
