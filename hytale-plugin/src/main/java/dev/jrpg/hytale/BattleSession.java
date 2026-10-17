package dev.jrpg.hytale;

import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
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
import org.joml.Vector3d;

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
 * Как показывается ход: начало хода — камера наезжает на того, кто ходит; герой выбирает команду
 * в меню (враг «думает» enemyThinkMs); затем кадр «атакующий + цель», баннер с названием приёма,
 * атакующий подходит к цели; через actionDelayMs всё возвращается и начинается следующий ход.
 *
 * Потоки: всё, что трогает мир/ECS/Battle, выполняется в потоке мира (world.execute).
 */
public final class BattleSession implements BattleListener {
    private static final double[] PARTY_X = {0, -3.5, 3.5, -7, 7};
    private static final long INTRO_MS = 2200;
    /** Пауза между всплывающими надписями над одним бойцом, мс. */
    private static final long POP_STEP_MS = 450;

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
    private BattleCamera camera;
    private Vector3d enemyCenter;
    private boolean closed;
    private int summonSlot;
    private String banner = "";
    /** Вторая строка баннера: итог действия («Паук: -34, Горение»). */
    private String bannerSub = "";
    /** Окно описаний: на что навели мышь в меню (номер пункта) или кого «осматривают» кнопкой «?». */
    private int hoverIndex = -1;
    private BattleMenu.Screen hoverScreen;
    private String inspectId;
    /** Через сколько мс начнётся показ текущего действия (чтобы павшие исчезали не раньше удара). */
    private long presentDelay;

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
        Content content = plugin.content();
        List<Combatant> party = plugin.partyOf(player.getUuid()).buildParty(content);
        battle = plugin.data().createBattle(encounter, party,
                plugin.inventoryOf(player.getUuid()), this, System.nanoTime());
        menu = new BattleMenu(battle);

        // Арена: партия в линию на месте игрока, враги напротив (по +Z), все лицом друг к другу.
        int n = 0;
        double ex = 0, ez = 0;
        for (Combatant c : battle.combatants()) {
            if (c.side != Side.ENEMY) continue;
            Encounter.Spot spot = encounter.enemies().get(n++);
            Vector3d pos = new Vector3d(center.x + spot.x(), center.y, center.z + spot.z());
            homes.put(c.id, pos);
            ex += pos.x;
            ez += pos.z;
        }
        enemyCenter = n == 0 ? new Vector3d(center.x, center.y, center.z + 6) : new Vector3d(ex / n, center.y, ez / n);
        for (int i = 0; i < party.size(); i++) {
            Combatant c = party.get(i);
            Vector3d pos = new Vector3d(center.x + PARTY_X[i % PARTY_X.length], center.y, center.z);
            homes.put(c.id, pos);
            float yaw = Fx.yawTowards(pos, enemyCenter);
            if (c.id.equals("hero")) {
                puppets.put(c.id, playerEntity);
                Fx.teleportPlayer(store, playerEntity, pos, yaw);
            } else {
                spawnPuppet(c, plugin.settings().companionRoles.get(c.id), pos, yaw);
            }
        }
        for (Combatant c : battle.combatants()) {
            if (c.side == Side.ENEMY) spawnPuppet(c, plugin.data().enemy(c.templateId).role(), homes.get(c.id), faceParty(c.id));
        }

        camera = new BattleCamera(player, plugin.settings().camera, center, enemyCenter);
        camera.overview();
        banner = encounter.name();
        say("Бой: " + encounter.name());
        openMenu();
        // Небольшая заставка общим планом, потом первый ход.
        later(plugin.settings().ms(INTRO_MS), () -> {
            banner = "";
            battle.start(Battle.Opening.NORMAL);
            refresh();
        });
    }

    private float faceParty(String id) {
        Vector3d pos = homes.get(id);
        return pos == null ? 0f : Fx.yawTowards(pos, center);
    }

    // ---------- NPC-куклы ----------

    /** Роль можно задать списком через «|»: берётся первая, которая есть в игре, иначе fallbackRole. */
    private String resolveRole(String spec, Combatant c) {
        if (spec == null || spec.isBlank()) return null;
        NPCPlugin npcs = NPCPlugin.get();
        for (String r : spec.split("\\|")) {
            String role = r.trim();
            if (!role.isEmpty() && npcs.getIndex(role) >= 0) return role;
        }
        String fb = plugin.settings().fallbackRole;
        Fx.warnOnce("Роль NPC не найдена: " + spec + " (" + c.name + "). Показываю как " + fb
                + ". Список ролей: /jrpg roles <часть имени>", null);
        return fb != null && !fb.isBlank() && npcs.getIndex(fb) >= 0 ? fb : null;
    }

    private void spawnPuppet(Combatant c, String roleSpec, Vector3d pos, float yaw) {
        try {
            String role = resolveRole(roleSpec, c);
            if (role == null || pos == null) return;
            NPCPlugin npcs = NPCPlugin.get();
            Pair<Ref<EntityStore>, NPCEntity> spawned = npcs.spawnEntity(store, npcs.getIndex(role), pos,
                    new Rotation3f(0, yaw, 0), null, null);
            if (spawned == null) return;
            Ref<EntityStore> ref = spawned.first();
            store.putComponent(ref, Frozen.getComponentType(), Frozen.get());
            store.putComponent(ref, Invulnerable.getComponentType(), Invulnerable.INSTANCE);
            puppets.put(c.id, ref);
            Fx.nameplate(store, ref, BattleText.nameplate(c, false));
            // Поворот ещё раз телепортом: при спавне NPC иногда разворачивается сам.
            Fx.teleport(store, ref, pos, yaw);
        } catch (Throwable t) {
            plugin.warn("Не удалось заспавнить " + c.name + " (роль " + roleSpec + ")", t);
        }
    }

    private void despawn(String id) {
        Ref<EntityStore> ref = puppets.remove(id);
        if (ref == null || ref == playerEntity || !ref.isValid()) return;
        try {
            store.removeEntity(ref, RemoveReason.REMOVE);
        } catch (Throwable t) {
            plugin.warn("Не удалось убрать NPC " + id, t);
        }
    }

    private void despawnPuppets() {
        for (String id : List.copyOf(puppets.keySet())) despawn(id);
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

    private float yawOf(Combatant c) {
        Vector3d pos = homes.get(c.id);
        if (pos == null) return 0f;
        return c.side == Side.PARTY ? Fx.yawTowards(pos, enemyCenter) : Fx.yawTowards(pos, center);
    }

    // ---------- ввод из меню ----------

    void onOption(int index) {
        if (closed) return;
        if (menu.screen() == BattleMenu.Screen.ENDED) {
            finish();
            if (index == 1) plugin.openProgression(player, playerEntity, store, null);
            return;
        }
        if (battle.phase() != Battle.Phase.AWAIT_COMMAND) return;
        clearInfo();
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
        clearInfo();
        menu.back();
        if (battle.phase() == Battle.Phase.AWAIT_COMMAND) camera.focus(homes.get(battle.current().id), true);
        refresh();
    }

    /** Мышь над пунктом меню: описание в окне описаний; на экране целей — камера на цель. */
    void onHover(int index) {
        if (closed) return;
        hoverIndex = index;
        hoverScreen = menu.screen();
        inspectId = null;
        // Камера при наведении больше не дёргается (в 0.4 она прыгала от цели к цели).
        if (hoverScreen == BattleMenu.Screen.TARGET && index >= 0 && index < menu.targets().size()) {
            Combatant t = menu.targets().get(index);
            Fx.particle(plugin.settings().targetRing, homes.get(t.id), playerEntity, store);
        }
        refresh();
    }

    /**
     * Клик по карточке бойца: на экране выбора цели — выбрать его целью, иначе показать его
     * характеристики, статусы и способности.
     */
    void onInspect(String combatantId) {
        if (closed || combatantId == null) return;
        if (battle.phase() == Battle.Phase.AWAIT_COMMAND && menu.screen() == BattleMenu.Screen.TARGET) {
            BattleAction action = menu.clickTarget(combatantId);
            if (action != null) {
                clearInfo();
                try {
                    battle.submit(action);
                } catch (IllegalArgumentException e) {
                    menu.rejected(e.getMessage());
                }
                refresh();
                return;
            }
        }
        inspectId = combatantId;
        hoverIndex = -1;
        refresh();
    }

    private void clearInfo() {
        hoverIndex = -1;
        inspectId = null;
    }

    /** На экране выбора цели — кольца под всеми возможными целями и общий план. */
    private void highlightTargets() {
        if (menu.screen() != BattleMenu.Screen.TARGET) return;
        List<Combatant> targets = menu.targets();
        camera.targets(targets.isEmpty() || targets.get(0).side == Side.ENEMY);
        for (Combatant t : targets) {
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

    // ---------- данные для меню ----------

    record Info(String title, String text, String icon) {}

    Battle battle() { return battle; }
    BattleMenu menu() { return menu; }
    Settings settings() { return plugin.settings(); }
    String banner() { return banner; }
    String bannerSub() { return bannerSub; }
    List<String> logLines() { return List.copyOf(log); }

    Info info() {
        Content k = battle.content();
        if (inspectId != null) {
            Combatant c = battle.byId(inspectId);
            if (c != null) return new Info(c.name, Describe.combatant(k, c), "portrait:" + Describe.portrait(c));
        }
        List<BattleMenu.Option> opts = menu.options();
        if (hoverIndex >= 0 && hoverScreen == menu.screen() && hoverIndex < opts.size()) {
            BattleMenu.Option o = opts.get(hoverIndex);
            if (!o.info().isEmpty()) return new Info(o.label(), o.info(), o.icon());
        }
        Combatant c = battle.current();
        if (battle.phase() == Battle.Phase.ENDED) {
            return new Info(menu.title(), String.join("\n", log), "levelup");
        }
        // Пока ходит враг или идёт анимация — последние строки журнала, в свой ход — карточка героя.
        if (c == null || c.side != Side.PARTY || battle.phase() != Battle.Phase.AWAIT_COMMAND) {
            return new Info("Журнал боя", log.isEmpty() ? "Наведи мышь на команду или нажми «?» на карточке, "
                    + "чтобы прочитать описание." : String.join("\n", log), "info");
        }
        return new Info(c.name, Describe.combatant(k, c), "portrait:" + Describe.portrait(c));
    }

    // ---------- события ядра ----------

    @Override
    public void onTurnStart(Combatant actor, List<Combatant> forecast) {
        Vector3d pos = homes.get(actor.id);
        camera.focus(pos, actor.side == Side.PARTY);
        banner = "Ход: " + actor.name;
        bannerSub = "";
        if (pos != null) Fx.particle(plugin.settings().turnRing, pos, playerEntity, store);
        clearInfo();
    }

    @Override
    public void onAwaitCommand(Combatant actor) {
        menu.reset();
    }

    @Override
    public void onStatusTick(Combatant t, StatusInstance s, int delta) {
        addLog(t.name + ": " + s.def.name() + " " + (delta > 0 ? "+" : "") + delta + " HP");
        pop(t, 0, (delta > 0 ? "+" : "") + delta + " " + s.def.name(), delta > 0 ? HEAL : STATUS);
    }

    @Override
    public void onStatusExpired(Combatant t, StatusInstance s) {
        addLog(t.name + ": " + s.def.name() + " прошло");
    }

    @Override
    public void onTurnSkipped(Combatant actor, String reason) {
        addLog(actor.name + " пропускает ход (" + reason + ")");
        pop(actor, 0, "Пропуск хода", STATUS);
    }

    @Override
    public void onActionExecuted(ActionResult r) {
        addLog(BattleText.describe(r));
        presentDelay = r.actor().side == Side.ENEMY ? plugin.settings().ms(plugin.settings().enemyThinkMs) : 0;
        if (presentDelay > 0) {
            refresh();
            later(presentDelay, () -> present(r));
        } else {
            present(r);
        }
    }

    /** Показ действия: баннер, кадр камеры, выход к цели, частицы; потом возврат и следующий ход. */
    private void present(ActionResult r) {
        Combatant actor = r.actor();
        banner = actor.name + ": " + r.actionName();
        bannerSub = summary(r);
        Ref<EntityStore> actorRef = puppets.get(actor.id);
        Combatant firstTarget = r.hits().isEmpty() ? null : r.hits().get(0).target();
        Vector3d home = homes.get(actor.id);
        Vector3d targetPos = firstTarget == null ? null : homes.get(firstTarget.id);
        if (firstTarget == null || firstTarget == actor) camera.focus(home, actor.side == Side.PARTY);
        else camera.action(home, targetPos);

        boolean lunge = actorRef != null && firstTarget != null && firstTarget.side != actor.side
                && home != null && targetPos != null;
        if (lunge) {
            Vector3d near = new Vector3d(targetPos.x + (home.x - targetPos.x) * 0.3, targetPos.y,
                    targetPos.z + (home.z - targetPos.z) * 0.3);
            Fx.teleport(store, actorRef, near, Fx.yawTowards(near, targetPos));
        }
        for (ActionResult.Hit h : r.hits()) {
            Vector3d pos = homes.get(h.target().id);
            if (pos != null && h.damage() > 0) Fx.particle(plugin.settings().hitFx, pos, playerEntity, store);
            popHit(h);
        }
        if (r.fleeAttempt()) pop(actor, 0, r.fled() ? "Сбежали!" : "Не вышло", STATUS);
        refresh();
        later(plugin.settings().ms(plugin.settings().actionDelayMs), () -> {
            if (lunge) Fx.teleport(store, actorRef, home, yawOf(actor));
            banner = "";
            bannerSub = "";
            if (battle.phase() == Battle.Phase.PRESENTING) battle.acknowledge();
            refresh();
        });
    }

    @Override
    public void onCombatantDown(Combatant c) {
        addLog(c.name + " повержен!");
        // Убираем куклу, когда удар уже показан.
        later(presentDelay + plugin.settings().ms(plugin.settings().actionDelayMs) / 2, () -> {
            despawn(c.id);
            refresh();
        });
    }

    @Override
    public void onSummoned(Combatant c) {
        addLog("Появляется " + c.name + "!");
        Vector3d pos = new Vector3d(center.x + (summonSlot % 2 == 0 ? -5 : 5), center.y,
                center.z + 4 + summonSlot / 2 * 2);
        summonSlot++;
        homes.put(c.id, pos);
        spawnPuppet(c, plugin.data().enemy(c.templateId).role(), pos, faceParty(c.id));
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
        banner = boss.name + ": " + phaseName;
    }

    @Override
    public void onBattleEnd(Battle.Outcome outcome) {
        camera.overview();
        if (outcome == Battle.Outcome.VICTORY) {
            plugin.progress().markCleared(encounter, System.currentTimeMillis());
            Map<String, Integer> loot = plugin.data().rollLoot(encounter, new java.util.Random());
            loot.forEach((item, cnt) -> battle.inventory().merge(item, cnt, Integer::sum));
            StringBuilder sb = new StringBuilder("Награда: " + encounter.exp() + " опыта");
            loot.forEach((item, cnt) -> sb.append(", ").append(plugin.content().item(item).name()).append(" x").append(cnt));
            addLog(sb.toString());
            say(sb.toString());
            for (String msg : plugin.partyOf(player.getUuid()).addExp(encounter.exp())) {
                addLog(msg);
                say(msg + " Открой прокачку: /jrpg party");
            }
            banner = "ПОБЕДА!";
            bannerSub = "Награда: " + encounter.exp() + " опыта";
        } else {
            banner = outcome == Battle.Outcome.FLED ? "Партия сбежала" : "Поражение…";
        }
        refresh();
    }

    // ---------- всплывающие надписи ----------

    private static final String DAMAGE = "#ffffff", CRIT = "#ffd23f", HEAL = "#6be37a", MANA = "#6aa8ff",
            STATUS = "#c58cff", COMBO = "#ffa040", CURE = "#9fe0ff";

    /** Цифры и эффекты одного попадания: урон сразу, потом статусы и комбо по очереди. */
    private void popHit(ActionResult.Hit h) {
        Combatant t = h.target();
        Content k = battle.content();
        long at = 250;
        if (h.damage() > 0) pop(t, at, (h.crit() ? "Крит! -" : "-") + h.damage(), h.crit() ? CRIT : DAMAGE);
        if (h.heal() > 0) pop(t, at, "+" + h.heal(), HEAL);
        if (h.mpRestore() > 0) pop(t, at += POP_STEP_MS, "+" + h.mpRestore() + " MP", MANA);
        for (String id : h.combos()) pop(t, at += POP_STEP_MS, comboName(id) + "!", COMBO);
        for (String id : h.statusesApplied()) pop(t, at += POP_STEP_MS, k.status(id).name(), STATUS);
        for (String id : h.statusesCured()) pop(t, at += POP_STEP_MS, "Снято: " + k.status(id).name(), CURE);
        if (h.damage() == 0 && h.heal() == 0 && h.statusesApplied().isEmpty() && h.combos().isEmpty()
                && h.mpRestore() == 0 && h.statusesCured().isEmpty() && t.side != battle.current().side) {
            pop(t, at, "Нет эффекта", CURE);
        }
    }

    private void pop(Combatant c, long delayMs, String text, String color) {
        if (!plugin.settings().combatText) return;
        Runnable show = () -> {
            Ref<EntityStore> ref = puppets.get(c.id);
            if (ref != null) Fx.combatText(store, playerEntity, ref, text, color);
        };
        if (delayMs <= 0) show.run();
        else later(delayMs, show);
    }

    private String comboName(String id) {
        for (Combo k : battle.content().combos()) if (k.id().equals(id)) return k.name();
        return id;
    }

    /** Короткий итог действия для второй строки баннера. */
    private String summary(ActionResult r) {
        Content k = battle.content();
        List<String> parts = new java.util.ArrayList<>();
        for (ActionResult.Hit h : r.hits()) {
            List<String> bits = new java.util.ArrayList<>();
            if (h.damage() > 0) bits.add("-" + h.damage() + (h.crit() ? " крит" : ""));
            if (h.heal() > 0) bits.add("+" + h.heal() + " HP");
            for (String id : h.combos()) bits.add(comboName(id) + "!");
            for (String id : h.statusesApplied()) bits.add(k.status(id).name());
            if (h.killed()) bits.add("повержен");
            if (!bits.isEmpty()) parts.add(h.target().name + ": " + String.join(", ", bits));
        }
        if (parts.size() > 3) return parts.size() + " целей: " + String.join("; ", parts.subList(0, 3)) + "…";
        return String.join("; ", parts);
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
        if (camera != null) camera.reset();
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
        }), Math.max(0, ms), TimeUnit.MILLISECONDS);
    }

    private void addLog(String line) {
        for (String l : line.split("\n")) {
            log.addLast(l);
            while (log.size() > 7) log.removeFirst();
        }
    }

    private void say(String text) {
        player.sendMessage(Message.raw(text));
    }
}
