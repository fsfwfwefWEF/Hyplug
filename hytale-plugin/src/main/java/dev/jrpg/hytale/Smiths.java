package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jrpg.battle.Json;
import org.joml.Vector3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Кузнецы — пока условные: человек (модель игрока со скином) с подписью «Кузнец» на месте, где игрок вызвал
 * /jrpg smith. Места хранятся в smiths.json в папке мода; сам NPC временный (не сохраняется в мир) и
 * появляется снова, когда игрок рядом. Подошёл ближе {@link #OPEN_RADIUS} блоков — открывается кузница
 * ({@link SmithPage}); чтобы открыть ещё раз, надо отойти.
 */
final class Smiths {
    static final double OPEN_RADIUS = 2.5, LEAVE_RADIUS = 4.5, SPAWN_RADIUS = 64;
    private static final String FILE = "smiths.json", NAME = "Кузнец";

    record Spot(String world, double x, double y, double z, float yaw) {}

    private final JrpgPlugin plugin;
    private final List<Spot> spots = new CopyOnWriteArrayList<>();
    private final Map<Spot, Ref<EntityStore>> npcs = new ConcurrentHashMap<>();
    /** Игроки, которые уже стоят у кузнеца (окно открыто или только что закрыто). */
    private final Set<UUID> near = ConcurrentHashMap.newKeySet();

    Smiths(JrpgPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    private Path file() {
        return plugin.getDataDirectory().resolve(FILE);
    }

    private void load() {
        try {
            if (!Files.exists(file())) return;
            Map<String, Object> root = Json.obj(Json.parse(Files.readString(file())));
            for (Object o : Json.list(root, "smiths")) {
                Map<String, Object> m = Json.obj(o);
                spots.add(new Spot(Json.str(m, "world", "default"), Json.num(m, "x", 0), Json.num(m, "y", 0),
                        Json.num(m, "z", 0), (float) Json.num(m, "yaw", 0)));
            }
            plugin.log("Кузнецов: " + spots.size());
        } catch (Exception e) {
            plugin.warn("Не удалось прочитать " + FILE, e);
        }
    }

    private void save() {
        List<Object> list = new ArrayList<>();
        for (Spot s : spots) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", s.world());
            m.put("x", s.x());
            m.put("y", s.y());
            m.put("z", s.z());
            m.put("yaw", (double) s.yaw());
            list.add(m);
        }
        Map<String, Object> root = new HashMap<>();
        root.put("smiths", list);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), Json.write(root));
        } catch (Exception e) {
            plugin.warn("Не удалось сохранить " + FILE, e);
        }
    }

    /** Поставить кузнеца в 2 блоках перед игроком, лицом к нему. */
    String place(PlayerRef p, Ref<EntityStore> ref, Store<EntityStore> store, World world) {
        Vector3d me = Fx.position(store, ref);
        HeadRotation head = store.getComponent(ref, HeadRotation.getComponentType());
        float yaw = head == null ? 0 : head.getRotation().yaw();
        // yaw сущностей в радианах; «вперёд» = (-sin, -cos), как в Fx.yawTowards
        Vector3d at = new Vector3d(me.x - Math.sin(yaw) * 2, me.y, me.z - Math.cos(yaw) * 2);
        Double gy = Fx.groundY(world, at.x, at.z, me.y);
        if (gy != null && Math.abs(gy - me.y) < 4) at.y = gy;
        Spot s = new Spot(world.getName(), at.x, at.y, at.z, Fx.yawTowards(at, me));
        spots.add(s);
        save();
        near.add(p.getUuid());
        spawn(s, store);
        return "Кузнец стоит здесь (" + Math.round(at.x) + ", " + Math.round(at.y) + ", " + Math.round(at.z)
                + "). Подойди к нему — откроется кузница. Убрать: /jrpg smithremove";
    }

    /** Убрать ближайшего кузнеца (в 6 блоках). */
    String remove(Ref<EntityStore> ref, Store<EntityStore> store, World world) {
        Vector3d me = Fx.position(store, ref);
        Spot best = null;
        double bd = 36;
        for (Spot s : spots) {
            if (!s.world().equals(world.getName())) continue;
            double d = (s.x() - me.x) * (s.x() - me.x) + (s.z() - me.z) * (s.z() - me.z);
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        if (best == null) return "Рядом нет кузнеца.";
        spots.remove(best);
        Ref<EntityStore> npc = npcs.remove(best);
        if (npc != null) Fx.remove(store, npc);
        save();
        return "Кузнец убран.";
    }

    private void spawn(Spot s, Store<EntityStore> store) {
        String role = plugin.settings().companionRoles.values().stream().findFirst().orElse("Kweebec_Seedling");
        HumanLook.Look look = HumanLook.of("smith", "male");
        Ref<EntityStore> r = Fx.spawnPuppet(store, role, NAME, new Vector3d(s.x(), s.y(), s.z()), s.yaw(), NAME, look);
        if (r != null) npcs.put(s, r);
    }

    /** Из проверки встреч (поток мира игрока): поставить пропавших кузнецов рядом, открыть кузницу вплотную. */
    boolean tick(PlayerRef p, Ref<EntityStore> ref, Store<EntityStore> store, World world, Vector3d pos) {
        boolean close = false;
        for (Spot s : spots) {
            if (!s.world().equals(world.getName())) continue;
            double dx = s.x() - pos.x, dz = s.z() - pos.z, d2 = dx * dx + dz * dz;
            if (d2 > SPAWN_RADIUS * SPAWN_RADIUS || Math.abs(s.y() - pos.y) > 24) continue;
            Ref<EntityStore> npc = npcs.get(s);
            if (npc == null || !npc.isValid()) spawn(s, store);
            if (d2 < LEAVE_RADIUS * LEAVE_RADIUS) close = true;
            if (d2 < OPEN_RADIUS * OPEN_RADIUS && near.add(p.getUuid())) {
                open(p, ref, store);
                return true;
            }
        }
        if (!close) near.remove(p.getUuid());
        return false;
    }

    void open(PlayerRef p, Ref<EntityStore> ref, Store<EntityStore> store) {
        Pages.open(plugin, p, ref, store, new SmithPage(p, plugin));
    }

    /** При выключении плагина: убрать NPC. */
    void despawnAll() {
        for (Ref<EntityStore> r : npcs.values()) {
            try {
                if (r.isValid()) {
                    Store<EntityStore> st = r.getStore();
                    st.getExternalData().getWorld().execute(() -> Fx.remove(st, r));
                }
            } catch (Throwable ignored) {
                // мир уже закрыт
            }
        }
        npcs.clear();
    }

    /** Подпись кузнеца — чтобы уборка компаньонов его не трогала. */
    static boolean isSmithName(String name) {
        return NAME.equals(name);
    }
}
