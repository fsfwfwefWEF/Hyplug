package dev.jrpg.hytale;

import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.jrpg.battle.*;
import it.unimi.dsi.fastutil.Pair;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Один бой одного игрока в мире Hytale: арена, NPC-«куклы», меню, камера, паузы между ходами.
 * Правила боя целиком в {@link Battle}; здесь только показ и ввод.
 *
 * Потоки: всё, что трогает мир/ECS/Battle, выполняется в потоке мира (world.execute).
 */
public final class BattleSession implements BattleListener {
    private static final float FACE_ENEMIES = 0f;
    private static final float FACE_PARTY = (float) Math.PI;
    private static final double[] PARTY_X = {0, -2.5, 2.5, -5, 5};

    private final JrpgPlugin plugin;
    private final Encounter encounter;
    private final PlayerRef player;
    private final Ref<EntityStore> playerEntity;
    private final Store<EntityStore> store;
    private final World world;
    private final Vector3d center;
    private final Map<String, Ref<EntityStore>> puppets = new HashMap<>();
    private final Map<String, Vector3d> homes = new HashMap<>();
    private final Deque<String> log = new ArrayDeque<>();
    private Battle battle;
    private BattleMenu menu;
    private BattleMenuPage page;
    private boolean closed;
    private int summonSlot;

    private BattleSession(JrpgPlugin plugin, Encounter encounter, PlayerRef player, Ref<EntityStore> playerEntity,
                          Store<EntityStore> store, World world, Vector3d center) {
        this.plugin = plugin;
        this.encounter = encounter;
        this.player = player;
        this.playerEntity = playerEntity;
        this.store = store;
        this.world = world;
        this.center = center;
    }

    /** Начать бой. Вызывать в потоке мира. Возвращает текст ошибки или null. */
    public static String start(JrpgPlugin plugin, Encounter encounter, PlayerRef player, Ref<EntityStore> ref,
                               Store<EntityStore> store, World world, Vector3d center) {
        if (plugin.sessions().containsKey(player.getUuid())) return "Ты уже в бою (/jrpg menu, /jrpg stop).";
        Vector3d c = center != null ? center : Fx.position(store, ref);
        BattleSession s = new BattleSession(plugin, encounter, player, ref, store, world, c);
        plugin.sessions().put(player.getUuid(), s);
        try {
            s.begin();
        } catch (Throwable t) {
            plugin.warn("Бой не запустился", t);
            s.cleanup();
            return "Бой не запустился: " + t + " (подробности в логе сервера)";
        }
        return null;
    }

    private void begin() {
        List<Combatant> party = Content.demoParty();
        battle = plugin.data().createBattle(encounter, party,
                plugin.inventoryOf(player.getUuid()), this, System.nanoTime());
        menu = new BattleMenu(battle);

        // Арена: партия в линию на месте игрока, враги напротив (по +Z).
        for (int i = 0; i < party.size(); i++) {
            Combatant c = party.get(i);
            Vector3d pos = new Vector3d(center.x + PARTY_X[i], center.y, center.z);
            homes.put(c.id, pos);
            if (c.id.equals("hero")) {
                puppets.put(c.id, playerEntity);
                Fx.teleportPlayer(store, playerEntity, pos, FACE_ENEMIES);
            } else {
                spawnPuppet(c, plugin.settings().companionRoles.get(c.id), pos, FACE_ENEMIES);
            }
        }
        int n = 0;
        for (Combatant c : battle.combatants()) {
            if (c.side != Side.ENEMY) continue;
            Encounter.Spot spot = encounter.enemies().get(n++);
            Vector3d pos = new Vector3d(center.x + spot.x(), center.y, center.z + spot.z());
            homes.put(c.id, pos);
            spawnPuppet(c, plugin.data().enemy(c.templateId).role(), pos, FACE_PARTY);
        }

        Fx.battleCamera(player, plugin.settings());
        say("Бой: " + encounter.name());
        openMenu();
        battle.start(Battle.Opening.NORMAL);
        refresh();
    }

    // ---------- NPC-куклы ----------

    private void spawnPuppet(Combatant c, String role, Vector3d pos, float yaw) {
        if (role == null || role.isBlank()) return;
        try {
            NPCPlugin npcs = NPCPlugin.get();
            int roleIndex = npcs.getIndex(role);
            if (roleIndex < 0) {
                plugin.warn("Роль NPC не найдена: " + role + " (" + c.name + "), поправь enemies.json/settings.json", null);
                return;
            }
            Pair<Ref<EntityStore>, NPCEntity> spawned = npcs.spawnEntity(store, roleIndex, pos,
                    new Rotation3f(0, yaw, 0), null, null);
            if (spawned == null) return;
            Ref<EntityStore> ref = spawned.first();
            store.putComponent(ref, Frozen.getComponentType(), Frozen.get());
            store.putComponent(ref, Invulnerable.getComponentType(), Invulnerable.INSTANCE);
            puppets.put(c.id, ref);
            Fx.nameplate(store, ref, BattleText.nameplate(c, false));
        } catch (Throwable t) {
            plugin.warn("Не удалось заспавнить " + c.name + " (роль " + role + ")", t);
        }
    }

    private void despawnPuppets() {
        for (Map.Entry<String, Ref<EntityStore>> e : puppets.entrySet()) {
            Ref<EntityStore> ref = e.getValue();
            if (ref == playerEntity || ref == null || !ref.isValid()) continue;
            try {
                store.removeEntity(ref, RemoveReason.REMOVE);
            } catch (Throwable t) {
                plugin.warn("Не удалось убрать NPC " + e.getKey(), t);
            }
        }
        puppets.clear();
    }

    private void updateNameplates() {
        for (Combatant c : battle.combatants()) {
            Ref<EntityStore> ref = puppets.get(c.id);
            if (ref != null && ref != playerEntity) {
                Fx.nameplate(store, ref, BattleText.nameplate(c, c == battle.current()));
            }
        }
    }

    // ---------- ввод из меню ----------

    void onOption(int index) {
        if (closed) return;
        if (menu.screen() == BattleMenu.Screen.ENDED) {
            finish();
            return;
        }
        if (battle.phase() != Battle.Phase.AWAIT_COMMAND) return;
        BattleAction action = menu.click(index);
        if (action != null) {
            try {
                battle.submit(action);
            } catch (IllegalArgumentException e) {
                menu.rejected(e.getMessage());
            }
        }
        highlightTargets();
        refresh();
    }

    void onBack() {
        if (closed) return;
        menu.back();
        refresh();
    }

    /** На экране выбора цели — кольца под всеми возможными целями. */
    private void highlightTargets() {
        if (menu.screen() != BattleMenu.Screen.TARGET) return;
        for (Combatant t : menu.targets()) {
            Vector3d pos = homes.get(t.id);
            if (pos != null) Fx.particle(plugin.settings().targetRing, pos, playerEntity, store);
        }
    }

    public void openMenu() {
        if (closed) return;
        Player p = store.getComponent(playerEntity, Player.getComponentType());
        if (p == null) return;
        page = new BattleMenuPage(player, this);
        p.getPageManager().openCustomPage(playerEntity, store, page);
    }

    private void refresh() {
        if (page == null || closed) return;
        updateNameplates();
        try {
            page.refresh();
        } catch (Throwable t) {
            plugin.warn("Не удалось обновить меню", t);
        }
    }

    record View(String party, String enemies, String queue, String log, String title, String error,
                List<BattleMenu.Option> options, boolean canGoBack) {}

    View view() {
        return new View(BattleText.side(battle, Side.PARTY), BattleText.side(battle, Side.ENEMY),
                battle.phase() == Battle.Phase.ENDED ? "" : BattleText.queue(battle),
                String.join("\n", log), menu.title(), menu.error(), menu.options(), menu.canGoBack());
    }

    // ---------- события ядра ----------

    @Override
    public void onTurnStart(Combatant actor, List<Combatant> forecast) {
        Vector3d pos = homes.get(actor.id);
        if (pos != null) Fx.particle(plugin.settings().turnRing, pos, playerEntity, store);
    }

    @Override
    public void onAwaitCommand(Combatant actor) {
        menu.reset();
    }

    @Override
    public void onStatusTick(Combatant t, StatusInstance s, int delta) {
        addLog(t.name + ": " + s.def.name() + " " + (delta > 0 ? "+" : "") + delta + " HP");
    }

    @Override
    public void onTurnSkipped(Combatant actor, String reason) {
        addLog(actor.name + " пропускает ход (" + reason + ")");
    }

    @Override
    public void onActionExecuted(ActionResult r) {
        addLog(BattleText.describe(r));
        // «Выход вперёд» к цели и удар: кукла подходит к первой цели-врагу, потом возвращается.
        Ref<EntityStore> actorRef = puppets.get(r.actor().id);
        Combatant firstTarget = r.hits().isEmpty() ? null : r.hits().get(0).target();
        boolean lunge = actorRef != null && actorRef != playerEntity && firstTarget != null
                && firstTarget.side != r.actor().side;
        if (lunge) {
            Vector3d t = homes.get(firstTarget.id);
            Vector3d home = homes.get(r.actor().id);
            if (t != null && home != null) {
                Vector3d near = new Vector3d(t.x + (home.x - t.x) * 0.3, t.y, t.z + (home.z - t.z) * 0.3);
                Fx.teleport(store, actorRef, near, r.actor().side == Side.PARTY ? FACE_ENEMIES : FACE_PARTY);
            }
        }
        for (ActionResult.Hit h : r.hits()) {
            Vector3d pos = homes.get(h.target().id);
            if (pos != null && h.damage() > 0) Fx.particle(plugin.settings().hitFx, pos, playerEntity, store);
        }
        long delay = plugin.settings().actionDelayMs;
        later(delay, () -> {
            if (lunge) {
                Fx.teleport(store, actorRef, homes.get(r.actor().id),
                        r.actor().side == Side.PARTY ? FACE_ENEMIES : FACE_PARTY);
            }
            if (battle.phase() == Battle.Phase.PRESENTING) battle.acknowledge();
            refresh();
        });
    }

    @Override
    public void onCombatantDown(Combatant c) {
        addLog(c.name + " повержен!");
        Ref<EntityStore> ref = puppets.remove(c.id);
        if (ref != null && ref != playerEntity && ref.isValid()) {
            try {
                store.removeEntity(ref, RemoveReason.REMOVE);
            } catch (Throwable t) {
                plugin.warn("Не удалось убрать павшего " + c.name, t);
            }
        }
    }

    @Override
    public void onSummoned(Combatant c) {
        addLog("Появляется " + c.name + "!");
        Vector3d pos = new Vector3d(center.x + (summonSlot % 2 == 0 ? -5 : 5), center.y,
                center.z + 4 + summonSlot / 2 * 2);
        summonSlot++;
        homes.put(c.id, pos);
        spawnPuppet(c, plugin.data().enemy(c.templateId).role(), pos, FACE_PARTY);
    }

    @Override
    public void onDialogue(Combatant speaker, String text) {
        String line = speaker == null ? text : speaker.name + ": «" + text + "»";
        addLog(line);
        say(line);
    }

    @Override
    public void onPhaseChange(Combatant boss, String phaseName) {
        addLog("!!! " + boss.name + " — " + phaseName);
    }

    @Override
    public void onBattleEnd(Battle.Outcome outcome) {
        if (outcome == Battle.Outcome.VICTORY) {
            plugin.progress().markCleared(encounter, System.currentTimeMillis());
            Map<String, Integer> loot = plugin.data().rollLoot(encounter, new java.util.Random());
            loot.forEach((item, cnt) -> battle.inventory().merge(item, cnt, Integer::sum));
            StringBuilder sb = new StringBuilder("Награда: " + encounter.exp() + " опыта");
            loot.forEach((item, cnt) -> sb.append(", ").append(plugin.content().item(item).name()).append(" x").append(cnt));
            addLog(sb.toString());
            say(sb.toString());
        }
        refresh();
    }

    // ---------- завершение ----------

    /** Кнопка «Закрыть» после конца боя. */
    void finish() {
        cleanup();
        say("Бой окончен.");
    }

    public void abort(String reason) {
        world.execute(() -> {
            if (closed) return;
            cleanup();
            say("Бой прерван: " + reason);
        });
    }

    private void cleanup() {
        closed = true;
        plugin.sessions().remove(player.getUuid(), this);
        plugin.markBattleEnded(player.getUuid());
        despawnPuppets();
        Fx.resetCamera(player);
        if (page != null) {
            try {
                page.closePage();
            } catch (Throwable t) {
                plugin.warn("Не удалось закрыть меню", t);
            }
        }
    }

    boolean isClosed() { return closed; }
    PlayerRef player() { return player; }

    // ---------- утилиты ----------

    private void later(long ms, Runnable r) {
        plugin.scheduler().schedule(() -> world.execute(() -> {
            if (closed) return;
            try {
                r.run();
            } catch (Throwable t) {
                plugin.warn("Ошибка в ходе боя", t);
            }
        }), ms, TimeUnit.MILLISECONDS);
    }

    private void addLog(String line) {
        for (String l : line.split("\n")) {
            log.addLast(l);
            while (log.size() > 9) log.removeFirst();
        }
    }

    private void say(String text) {
        player.sendMessage(Message.raw(text));
    }
}
