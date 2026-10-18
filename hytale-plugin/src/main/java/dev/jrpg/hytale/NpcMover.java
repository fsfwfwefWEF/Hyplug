package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Плавная ходьба NPC-кукол по точкам: каждые {@link #TICK_MS} мс кукла сдвигается на кусочек пути
 * (позиция через TransformComponent, клиент сглаживает), пока идёт — поза «шаг»/«бег», дошла — «стоит».
 * Так ходят бойцы на арене (подход к цели и возврат) и NPC отрядов в мире (бродят у своего места).
 * Движение по времени: за durationMs кукла доходит до цели, даже если сервер подтормаживает.
 */
final class NpcMover {
    private static final long TICK_MS = 50;

    private final JrpgPlugin plugin;
    private final Map<Ref<EntityStore>, Move> moves = new ConcurrentHashMap<>();
    /** Миры, для которых шаг уже в очереди: не копим задачи, если мир подтормаживает. */
    private final Set<World> queued = ConcurrentHashMap.newKeySet();

    private static final class Move {
        final Store<EntityStore> store;
        final World world;
        final Ref<EntityStore> ref;
        final Vector3d from, to;
        final long start, duration;
        final float yaw;
        final Float endYaw;
        /** Поза в пути: "run", "walk" или null — не трогать (отлёт от удара). */
        final String state;
        final boolean ground;

        Move(Store<EntityStore> store, World world, Ref<EntityStore> ref, Vector3d from, Vector3d to, long duration,
             String state, boolean ground, Float endYaw, Float yaw) {
            this.store = store;
            this.world = world;
            this.ref = ref;
            this.from = new Vector3d(from);
            this.to = new Vector3d(to);
            this.start = System.currentTimeMillis();
            this.duration = Math.max(1, duration);
            this.yaw = yaw != null ? yaw : Fx.yawTowards(from, to);
            this.endYaw = endYaw;
            this.state = state;
            this.ground = ground;
        }
    }

    NpcMover(JrpgPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        plugin.scheduler().scheduleAtFixedRate(this::tickSafe, 1000, TICK_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Идти из from в to за durationMs (run — бегом). ground — держаться земли по пути (в мире; на арене
     * не нужно). endYaw — куда повернуться, когда дошёл (null — как шёл). Вызывать в потоке мира.
     */
    void go(Store<EntityStore> store, World world, Ref<EntityStore> ref, Vector3d from, Vector3d to, long durationMs,
            boolean run, boolean ground, Float endYaw) {
        if (ref == null || from == null || to == null) return;
        start(new Move(store, world, ref, from, to, durationMs, run ? "run" : "walk", ground, endYaw, null));
    }

    /** Сдвинуть без шага (отлететь от удара и вернуться), глядя по yaw. */
    void slide(Store<EntityStore> store, World world, Ref<EntityStore> ref, Vector3d from, Vector3d to, long durationMs,
               float yaw) {
        if (ref == null || from == null || to == null) return;
        start(new Move(store, world, ref, from, to, durationMs, null, false, yaw, yaw));
    }

    private void start(Move m) {
        if (!m.ref.isValid()) return;
        moves.put(m.ref, m);
        if (m.state != null) Fx.setMovement(m.store, m.ref, m.state);
    }

    boolean moving(Ref<EntityStore> ref) {
        return ref != null && moves.containsKey(ref);
    }

    /** Остановить, не доводя до цели (кукла остаётся, где была). */
    void stop(Ref<EntityStore> ref) {
        if (ref != null) moves.remove(ref);
    }

    private void tickSafe() {
        try {
            if (moves.isEmpty()) return;
            Set<World> worlds = ConcurrentHashMap.newKeySet();
            for (Move m : moves.values()) worlds.add(m.world);
            for (World w : worlds) {
                if (!queued.add(w)) continue;
                try {
                    w.execute(() -> {
                        try {
                            step(w);
                        } catch (Throwable t) {
                            Fx.warnOnce("Ошибка ходьбы NPC", t);
                        } finally {
                            queued.remove(w);
                        }
                    });
                } catch (Throwable t) {
                    queued.remove(w);
                    // мир выгружен — убираем его движения
                    moves.values().removeIf(m -> m.world == w);
                }
            }
        } catch (Throwable t) {
            Fx.warnOnce("Ошибка ходьбы NPC", t);
        }
    }

    private void step(World world) {
        long now = System.currentTimeMillis();
        List<Move> done = new ArrayList<>();
        for (Move m : moves.values()) {
            if (m.world != world) continue;
            if (!m.ref.isValid()) {
                moves.remove(m.ref, m);
                continue;
            }
            double t = Math.min(1.0, (now - m.start) / (double) m.duration);
            Vector3d p = new Vector3d(m.from.x + (m.to.x - m.from.x) * t, m.from.y + (m.to.y - m.from.y) * t,
                    m.from.z + (m.to.z - m.from.z) * t);
            if (m.ground && t < 1.0) {
                Double gy = Fx.groundY(world, p.x, p.z, p.y);
                if (gy != null && Math.abs(gy - p.y) <= 1.6) p.y = gy;
            }
            if (t >= 1.0) done.add(m);
            Fx.place(m.store, m.ref, p, t >= 1.0 && m.endYaw != null ? m.endYaw : m.yaw);
        }
        for (Move m : done) {
            if (moves.remove(m.ref, m) && m.state != null) Fx.setMovement(m.store, m.ref, "idle");
        }
    }
}
