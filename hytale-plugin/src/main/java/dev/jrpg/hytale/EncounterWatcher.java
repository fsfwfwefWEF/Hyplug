package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jrpg.battle.Encounter;

/**
 * Раз в секунду: если игрок вошёл в радиус доступной встречи из encounters.json — начинается бой.
 * Так работает «враги стоят только там, где их поставили»: никакого случайного спавна.
 */
final class EncounterWatcher {
    private final JrpgPlugin plugin;

    EncounterWatcher(JrpgPlugin plugin) {
        this.plugin = plugin;
    }

    void tickSafe() {
        try {
            tick();
        } catch (Throwable t) {
            plugin.warn("Ошибка проверки встреч", t);
        }
    }

    private void tick() {
        for (PlayerRef p : Universe.get().getPlayers()) {
            if (!p.isValid()) continue;
            BattleSession s = plugin.sessions().get(p.getUuid());
            if (s != null || plugin.onCooldown(p.getUuid())) continue;
            if (!plugin.settings().proximityTrigger) continue;
            Ref<EntityStore> ref = p.getReference();
            if (ref == null || !ref.isValid()) continue;
            Store<EntityStore> store = ref.getStore();
            World world = store.getExternalData().getWorld();
            world.execute(() -> check(p, ref, store, world));
        }
        // Игрок вышел из игры посреди боя — бой закрывается.
        for (BattleSession s : plugin.sessions().values()) {
            if (!s.player().isValid()) s.abort("игрок вышел");
        }
    }

    private void check(PlayerRef p, Ref<EntityStore> ref, Store<EntityStore> store, World world) {
        if (!ref.isValid() || plugin.sessions().containsKey(p.getUuid())) return;
        Vector3d pos = Fx.position(store, ref);
        long now = System.currentTimeMillis();
        for (Encounter e : plugin.data().encounters()) {
            Encounter.Location l = e.location();
            if (!l.world().equals("*") && !l.world().equalsIgnoreCase(world.getName())) continue;
            if (!plugin.progress().isAvailable(e, now)) continue;
            double dx = pos.x - l.x(), dy = pos.y - l.y(), dz = pos.z - l.z();
            if (dx * dx + dz * dz <= l.triggerRadius() * l.triggerRadius() && Math.abs(dy) < 6) {
                String err = BattleSession.start(plugin, e, p, ref, store, world,
                        new Vector3d(l.x(), l.y(), l.z()));
                if (err != null) plugin.warn("Автостарт " + e.id() + ": " + err, null);
                return;
            }
        }
    }
}
