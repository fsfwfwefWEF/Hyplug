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
            if (s != null || plugin.isPrompting(p.getUuid()) || Cutscene.playing(p.getUuid())) continue;
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

    /**
     * Катсцена перед встречей (cutscenes.json → before: id встречи), если игрок её ещё не видел.
     * После неё — сразу бой ("then": "battle", по умолчанию) или окно «Сражаться / Сбежать» ("prompt").
     */
    private boolean playBefore(PlayerRef p, Ref<EntityStore> ref, Store<EntityStore> store, World world, Encounter e,
                               Vector3d center, PreBattlePage.Setup setup) {
        Cutscene.Def cut = Cutscene.before(plugin, p.getUuid(), e.id());
        if (cut == null) return false;
        Runnable after = switch (cut.then()) {
            case "prompt" -> () -> plugin.openPrompt(p, ref, store, setup);
            case "none" -> null;
            default -> () -> {
                String err = BattleSession.start(plugin, e, p, ref, store, world, center, dev.jrpg.battle.Battle.Opening.NORMAL, null);
                if (err != null) plugin.warn("Бой после катсцены " + cut.id() + ": " + err, null);
            };
        };
        String err = Cutscene.play(plugin, p, ref, store, world, cut, Cutscene.placeFor(cut, store, ref, e, center), after);
        if (err != null) {
            plugin.warn("Катсцена " + cut.id() + ": " + err, null);
            return false;
        }
        return true;
    }

    private void check(PlayerRef p, Ref<EntityStore> ref, Store<EntityStore> store, World world) {
        if (!ref.isValid() || plugin.sessions().containsKey(p.getUuid()) || plugin.isPrompting(p.getUuid())
                || Cutscene.playing(p.getUuid())) return;
        Vector3d pos = Fx.position(store, ref);
        if (plugin.smiths() != null && plugin.smiths().tick(p, ref, store, world, pos)) return;
        long now = System.currentTimeMillis();
        boolean cooldown = plugin.onCooldown(p.getUuid());
        boolean auto = plugin.settings().proximityTrigger && !cooldown;
        // Катсцена по зоне (cutscenes.json → zone): играет один раз, сама по себе (не перед боем).
        Cutscene.Def zone = Cutscene.zoneAt(plugin, p.getUuid(), world.getName(), pos);
        if (zone != null && zone.before() == null) {
            String err = Cutscene.play(plugin, p, ref, store, world, zone,
                    Cutscene.placeFor(zone, store, ref, null, null), null);
            if (err != null) plugin.warn("Катсцена " + zone.id() + ": " + err, null);
            return;
        }
        if (auto) {
            for (Encounter e : plugin.data().encounters()) {
                Encounter.Location l = e.location();
                if (!l.world().equals("*") && !l.world().equalsIgnoreCase(world.getName())) continue;
                if (!plugin.progress().isAvailable(e, now) || plugin.dodged(p.getUuid(), e.id())) continue;
                double dx = pos.x - l.x(), dy = pos.y - l.y(), dz = pos.z - l.z();
                if (dx * dx + dz * dz <= l.triggerRadius() * l.triggerRadius() && Math.abs(dy) < 6) {
                    Vector3d center = new Vector3d(l.x(), l.y(), l.z());
                    double chance = Escape.chance(e, plugin.partyOf(p.getUuid()).buildParty(plugin.content()), plugin.data());
                    PreBattlePage.Setup setup = new PreBattlePage.Setup(e, center, null, center, l.triggerRadius(), chance, null);
                    if (playBefore(p, ref, store, world, e, center, setup)) return;
                    plugin.openPrompt(p, ref, store, setup);
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
