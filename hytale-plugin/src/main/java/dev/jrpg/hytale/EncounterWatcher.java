package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jrpg.battle.Encounter;
import dev.jrpg.battle.Escape;

/**
 * Раз в секунду: если игрок вошёл в радиус доступной встречи из encounters.json или подошёл к отряду
 * из squads.json — окно «Сражаться / Сбежать» ({@link PreBattlePage}). Ручные встречи важнее отрядов.
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
            if (s != null || plugin.isPrompting(p.getUuid())) continue;
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
        if (!ref.isValid() || plugin.sessions().containsKey(p.getUuid()) || plugin.isPrompting(p.getUuid())) return;
        Vector3d pos = Fx.position(store, ref);
        long now = System.currentTimeMillis();
        boolean cooldown = plugin.onCooldown(p.getUuid());
        boolean auto = plugin.settings().proximityTrigger && !cooldown;
        if (auto) {
            for (Encounter e : plugin.data().encounters()) {
                Encounter.Location l = e.location();
                if (!l.world().equals("*") && !l.world().equalsIgnoreCase(world.getName())) continue;
                if (!plugin.progress().isAvailable(e, now) || plugin.dodged(p.getUuid(), e.id())) continue;
                double dx = pos.x - l.x(), dy = pos.y - l.y(), dz = pos.z - l.z();
                if (dx * dx + dz * dz <= l.triggerRadius() * l.triggerRadius() && Math.abs(dy) < 6) {
                    Vector3d center = new Vector3d(l.x(), l.y(), l.z());
                    double chance = Escape.chance(e, plugin.partyOf(p.getUuid()).buildParty(plugin.content()), plugin.data());
                    plugin.openPrompt(p, ref, store, new PreBattlePage.Setup(e, center, null, center,
                            l.triggerRadius(), chance, null));
                    return;
                }
            }
        }
        SquadSpawner.Squad near = plugin.squads().tick(p, store, world, pos, auto);
        if (near != null && auto && !plugin.dodged(p.getUuid(), near.id)) {
            Encounter e = plugin.squads().encounterFor(near, pos);
            Vector3d dir = new Vector3d(near.pos.x - pos.x, 0, near.pos.z - pos.z);
            double chance = Escape.chance(e, plugin.partyOf(p.getUuid()).buildParty(plugin.content()), plugin.data());
            plugin.openPrompt(p, ref, store, new PreBattlePage.Setup(e, new Vector3d(pos), dir, near.pos,
                    plugin.data().squads().triggerRadius, chance, () -> plugin.squads().remove(p.getUuid(), near, store)));
        }
    }
}
