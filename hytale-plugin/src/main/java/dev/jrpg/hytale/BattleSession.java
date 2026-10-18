package dev.jrpg.hytale;

import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager;
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
    /** Облёт арены перед боем: кадров и пауза между ними, мс. */
    private static final int FLYBY_FRAMES = 6;
    private static final long FLYBY_STEP_MS = 650;
    /** Пауза между всплывающими надписями над одним бойцом, мс. */
    private static final long POP_STEP_MS = 450;
    /** Через сколько вернуть меню, закрытое Esc, мс. */
    private static final long REOPEN_MS = 350;
    /** Сколько ждать, не наведут ли мышь на соседнюю кнопку, прежде чем гасить подсветку, мс. */
    private static final long UNHOVER_MS = 150;

    private final JrpgPlugin plugin;
    private final Encounter encounter;
    private final PlayerRef player;
    private final Ref<EntityStore> playerEntity;
    private final Store<EntityStore> store;
    private final World world;
    private final Vector3d center;
    private final Map<String, Ref<EntityStore>> puppets = new java.util.concurrent.ConcurrentHashMap<>();
    /** Кто сейчас лежит, сбитый с ног оглушением (встанет, когда оглушение пройдёт). */
    private final java.util.Set<String> downed = new java.util.HashSet<>();
    /** Добыча поверженных врагов, пока её надписи не показаны. */
    private final Map<String, Map<String, Integer>> dropsOf = new HashMap<>();
    /** Последнее выполненное действие (кого оно убило, покажет present). */
    private ActionResult current;
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
    /** Над чьей кнопкой «?» сейчас мышь (подсветка карточки и описание, без выбора цели). */
    private String peekId;
    /** Номер последнего события мыши: уход с кнопки гасит подсветку, только если за ним не было нового наведения. */
    private int mouseSeq;
    private boolean escHintShown;
    /** Через сколько мс начнётся показ текущего действия (чтобы павшие исчезали не раньше удара). */
    private long presentDelay;
    private Battle.Opening opening = Battle.Opening.NORMAL;
    /** Замах врага, на который можно ответить уклонением или парированием (фаза AWAIT_REACTION). */
    private Windup windup;
    /** Ход врага после ответа: он уже стоит у цели и замахнулся, present() не повторяет подход. */
    private Windup answered;
    /** Добыча с поверженных врагов за этот бой (для итога победы). */
    private final Map<String, Integer> drops = new java.util.LinkedHashMap<>();
    private final java.util.Random rnd = new java.util.Random();

    /** Замах врага: кто, чем, по кому, где стоит для удара и когда удар (System.currentTimeMillis). */
    private static final class Windup {
        final Combatant actor;
        final Ability ability;
        final List<Combatant> targets;
        Vector3d strikeAt;
        long impactAt;
        /** Ответ игрока: null — ещё не нажимал, NONE — нажал не вовремя. */
        Battle.Reaction attempt;
        boolean resolved;

        Windup(Combatant actor, Ability ability, List<Combatant> targets) {
            this.actor = actor;
            this.ability = ability;
            this.targets = targets;
        }
    }
    /** Направление «партия → враги» по XZ (единичный вектор). */
    private double dirX = 0, dirZ = 1;

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
        return start(plugin, encounter, player, ref, store, world, center, Battle.Opening.NORMAL);
    }

    public static String start(JrpgPlugin plugin, Encounter encounter, PlayerRef player, Ref<EntityStore> ref,
                               Store<EntityStore> store, World world, Vector3d center, Battle.Opening opening) {
        return start(plugin, encounter, player, ref, store, world, center, opening, null);
    }

    /**
     * opening: AMBUSH — враги ходят первыми (не удалось сбежать из окна перед боем).
     * dir: куда смотрит партия (null — по +Z, как у встреч из encounters.json).
     */
    public static String start(JrpgPlugin plugin, Encounter encounter, PlayerRef player, Ref<EntityStore> ref,
                               Store<EntityStore> store, World world, Vector3d center, Battle.Opening opening,
                               Vector3d dir) {
        if (plugin.sessions().containsKey(player.getUuid())) return "Ты уже в бою (/jrpg menu, /jrpg stop).";
        Vector3d c = center != null ? center : Fx.position(store, ref);
        BattleSession s = new BattleSession(plugin, encounter, player, ref, store, world, c);
        s.opening = opening == null ? Battle.Opening.NORMAL : opening;
        if (dir != null && Math.hypot(dir.x, dir.z) > 1e-3) {
            double len = Math.hypot(dir.x, dir.z);
            s.dirX = dir.x / len;
            s.dirZ = dir.z / len;
        }
        plugin.sessions().put(player.getUuid(), s);
        try {
            s.begin();
            BattleMusic.start(plugin, encounter, store, ref);
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
        battle.setReactions(plugin.settings().reactions.enabled);
        menu = new BattleMenu(battle);
        // Компаньоны, ходившие за игроком, уходят: в бою их изображают куклы на арене.
        plugin.followers().pause(player.getUuid());

        // Арена: партия в линию на месте игрока, враги напротив (по +Z), все лицом друг к другу.
        int n = 0;
        double ex = 0, ez = 0;
        for (Combatant c : battle.combatants()) {
            if (c.side != Side.ENEMY) continue;
            Encounter.Spot spot = encounter.enemies().get(n++);
            Vector3d pos = onGround(arena(spot.x(), spot.z()));
            homes.put(c.id, pos);
            ex += pos.x;
            ez += pos.z;
        }
        enemyCenter = n == 0 ? arena(0, 6) : new Vector3d(ex / n, center.y, ez / n);
        for (int i = 0; i < party.size(); i++) {
            Combatant c = party.get(i);
            Vector3d pos = onGround(arena(PARTY_X[i % PARTY_X.length], 0));
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
        banner = encounter.name();
        bannerSub = opening == Battle.Opening.AMBUSH ? "Засада! Враги ходят первыми" : "";
        say("Бой: " + encounter.name());
        openMenu();
        // Заставка: облёт арены (или просто общий план), потом первый ход.
        long intro = plugin.settings().ms(INTRO_MS);
        if (plugin.settings().introFlyby) {
            for (int k = 0; k < FLYBY_FRAMES; k++) {
                int frame = k;
                later(plugin.settings().ms(FLYBY_STEP_MS) * k, () -> camera.flyby(frame, FLYBY_FRAMES));
            }
            intro = plugin.settings().ms(FLYBY_STEP_MS) * FLYBY_FRAMES + plugin.settings().ms(600);
        } else {
            camera.overview();
        }
        later(intro, () -> {
            camera.overview();
            banner = "";
            bannerSub = "";
            battle.start(opening);
            refresh();
        });
    }

    /**
     * Точка арены: (dx, dz) в координатах арены (партия на z = 0, враги на z > 0), повёрнутых так,
     * что +z арены смотрит по направлению dir (у ручных встреч — по +Z мира, у отрядов — на отряд).
     */
    private Vector3d arena(double dx, double dz) {
        // «Вправо» от направления (dirX, dirZ) — (dirZ, -dirX)... подобрано так, чтобы при dir = +Z было x = dx.
        double rx = dirZ, rz = -dirX;
        return new Vector3d(center.x + rx * dx + dirX * dz, center.y, center.z + rz * dx + dirZ * dz);
    }

    /** Точка арены на земле, если земля рядом с высотой арены (склон, ступенька), иначе как есть. */
    private Vector3d onGround(Vector3d pos) {
        Double y = Fx.groundY(world, pos.x, pos.z, center.y);
        return y == null || Math.abs(y - center.y) > 4 ? pos : new Vector3d(pos.x, y, pos.z);
    }

    private float faceParty(String id) {
        Vector3d pos = homes.get(id);
        return pos == null ? 0f : Fx.yawTowards(pos, center);
    }

    // ---------- NPC-куклы ----------

    private void spawnPuppet(Combatant c, String roleSpec, Vector3d pos, float yaw) {
        Ref<EntityStore> ref = Fx.spawnPuppet(store, roleSpec, c.name, pos, yaw, BattleText.nameplate(c, false));
        if (ref == null) return;
        puppets.put(c.id, ref);
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
            if (index == 1) {
                // Окно прокачки сразу заменяет меню боя: закрыть и тут же открыть другое окно нельзя.
                cleanup(false);
                say("Бой окончен.");
                plugin.openProgression(player, playerEntity, store, null);
            } else {
                finish();
            }
            return;
        }
        if (battle.phase() == Battle.Phase.AWAIT_REACTION) {
            onReact(index);
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
        mouseSeq++;
        hoverIndex = index;
        hoverScreen = menu.screen();
        inspectId = null;
        // Во время замаха окно не обновляем: пока клиент не подтвердит обновление, сервер не примет нажатие.
        if (hoverScreen == BattleMenu.Screen.REACTION) return;
        // Камера при наведении больше не дёргается (в 0.4 она прыгала от цели к цели).
        if (hoverScreen == BattleMenu.Screen.TARGET && index >= 0 && index < menu.targets().size()) {
            Combatant t = menu.targets().get(index);
            Fx.particle(plugin.settings().targetRing, homes.get(t.id), playerEntity, store);
        }
        refresh();
    }

    /** Мышь ушла с пункта меню — гасим подсветку. */
    void onUnhover(int index) {
        if (closed || hoverIndex != index) return;
        hoverIndex = -1;
        if (menu.screen() == BattleMenu.Screen.REACTION) return;
        refreshUnlessHovered();
    }

    /** Мышь над «?» на карточке: описание бойца и подсветка, но цель не выбирается. */
    void onPeek(String combatantId) {
        if (closed || combatantId == null || combatantId.equals(peekId)) return;
        mouseSeq++;
        peekId = combatantId;
        refresh();
    }

    void onUnpeek() {
        if (closed || peekId == null) return;
        peekId = null;
        refreshUnlessHovered();
    }

    /**
     * Мышь ушла с кнопки: обычно следом она наводится на соседнюю, и одно обновление окна вместо двух.
     * Каждое обновление окна ждёт ответа клиента, и пока ответа нет, сервер не принимает нажатия.
     */
    private void refreshUnlessHovered() {
        int seq = ++mouseSeq;
        later(UNHOVER_MS, () -> {
            if (seq == mouseSeq) refresh();
        });
    }

    int hoverIndex() { return hoverScreen == menu.screen() ? hoverIndex : -1; }
    String peekId() { return peekId; }

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
        peekId = null;
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
        BattleMenuPage next = new BattleMenuPage(player, this);
        page = next;
        Pages.open(plugin, player, playerEntity, store, next, () -> !closed && page == next, null);
    }

    /**
     * Меню закрыли (Esc) или его сменило другое окно. Бой идёт дальше; если через миг никакого
     * окна нет — меню возвращается. Окно прокачки поверх боя не трогаем: после него меню вернётся само.
     */
    void onMenuDismissed(BattleMenuPage dismissed) {
        if (closed || dismissed != page) return;
        later(REOPEN_MS, () -> {
            if (page != dismissed || !reopenMenuIfFree() || escHintShown) return;
            escHintShown = true;
            say("Меню боя не закрывается. Уйти из боя: «Бегство» или /jrpg stop.");
        });
    }

    /** Вернуть меню боя, если у игрока сейчас не открыто другое окно. */
    boolean reopenMenuIfFree() {
        if (closed) return false;
        PageManager pm = Pages.manager(store, playerEntity);
        if (pm == null || pm.getCustomPage() != null) return false;
        openMenu();
        return true;
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
        if (peekId != null) {
            Combatant c = battle.byId(peekId);
            if (c != null) return new Info(c.name, Describe.combatant(k, c), "portrait:" + Describe.portrait(c));
        }
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
        if (battle.phase() == Battle.Phase.AWAIT_REACTION) {
            Settings.Reactions rs = plugin.settings().reactions;
            return new Info("Защищайся!", c.name + " бьёт. Жми кнопку, когда удар достигает цели (над врагом мелькнёт «!»).\n"
                    + "Уклониться: окно " + (rs.dodgeBefore + rs.dodgeAfter) + " мс, урон вдвое меньше, статусы не липнут.\n"
                    + "Парировать: окно " + (rs.parryBefore + rs.parryAfter) + " мс, урона нет, и ответный удар.\n"
                    + "Нажал рано — попытка сгорает.", "parry");
        }
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
        if (s.def.id().equals(STUN) && t.isAlive()) wake(t);
    }

    @Override
    public void onTurnSkipped(Combatant actor, String reason) {
        addLog(actor.name + " пропускает ход (" + reason + ")");
        pop(actor, 0, "Пропуск хода", STATUS);
    }

    @Override
    public void onActionExecuted(ActionResult r) {
        current = r;
        addLog(BattleText.describe(r));
        boolean afterWindup = answered != null && answered.actor == r.actor();
        presentDelay = r.actor().side == Side.ENEMY && !afterWindup ? plugin.settings().ms(plugin.settings().enemyThinkMs) : 0;
        if (presentDelay > 0) {
            refresh();
            later(presentDelay, () -> present(r));
        } else {
            present(r);
        }
    }

    /**
     * Показ действия: баннер, кадр камеры, анимация. Ближний бой — подход к цели маленькими шагами,
     * удар, цель вздрагивает и отлетает на полблока; магия и предметы — каст на месте.
     * Потом возврат на место и следующий ход.
     */
    private void present(ActionResult r) {
        Combatant actor = r.actor();
        Settings.Animations an = plugin.settings().anim;
        Windup w = answered != null && answered.actor == actor ? answered : null;
        answered = null;
        banner = actor.name + ": " + r.actionName();
        bannerSub = summary(r);
        Ref<EntityStore> actorRef = puppets.get(actor.id);
        Combatant firstTarget = r.hits().isEmpty() ? null : r.hits().get(0).target();
        Vector3d home = homes.get(actor.id);
        Vector3d targetPos = firstTarget == null ? null : homes.get(firstTarget.id);
        if (w == null) {
            if (firstTarget == null || firstTarget == actor) camera.focus(home, actor.side == Side.PARTY);
            else camera.action(home, targetPos);
        }

        Ability ab = abilityOf(r);
        boolean item = r.sequenceId() != null && r.sequenceId().startsWith("item");
        Vector3d strikeAt = null;
        long hitAt;
        if (w != null) {
            // Враг уже подошёл и замахнулся (окно ответа) — удар сразу.
            strikeAt = w.strikeAt;
            hitAt = 0;
        } else if (isMelee(actor, ab, firstTarget, item) && actorRef != null && home != null && targetPos != null) {
            // Точка удара: перед целью (для удара по всем — перед центром врагов).
            Vector3d aim = ab.target() == TargetType.ALL_ENEMIES ? (actor.side == Side.PARTY ? enemyCenter : center) : targetPos;
            strikeAt = strikePoint(home, aim);
            long arrive = walk(actorRef, home, strikeAt, Fx.yawTowards(strikeAt, aim));
            String kind = isBasicAttack(actor, ab) ? "attack" : "skill";
            later(arrive, () -> animateAs(actor, actorRef, kind));
            hitAt = arrive + 300;
        } else {
            animateAs(actor, actorRef, "cast");
            if (ab != null && !item && (ab.magical() || ab.damagePower() <= 0)) {
                Fx.particleAt(ParticleCatalog.get("cast"), home, 1.0, playerEntity, store);
            }
            hitAt = 450;
        }

        Vector3d from = strikeAt != null ? strikeAt : home;
        String impactFx = impactParticle(ab);
        Battle.Reaction reaction = r.reaction();
        later(hitAt, () -> {
            for (ActionResult.Hit h : r.hits()) {
                Vector3d pos = homes.get(h.target().id);
                Ref<EntityStore> tRef = puppets.get(h.target().id);
                boolean answeredHit = reaction != Battle.Reaction.NONE && h.target().side == Side.PARTY;
                if (h.damage() > 0) Fx.particleAt(impactFx, pos, 1.0, playerEntity, store);
                else if (h.heal() > 0 || h.mpRestore() > 0) Fx.particleAt(ParticleCatalog.get("heal"), pos, 0.4, playerEntity, store);
                else if (!h.statusesApplied().isEmpty() && h.target().side == actor.side) {
                    Fx.particleAt(ParticleCatalog.get("buff"), pos, 0.4, playerEntity, store);
                }
                if (h.killed()) {
                    fallen(h.target(), true);
                } else if (answeredHit) {
                    showReaction(reaction, h.target(), tRef, pos);
                } else if (h.statusesApplied().contains(STUN) && tRef != null && h.target().isAlive()) {
                    knockDown(h.target(), tRef);
                } else if (h.damage() > 0 && tRef != null && h.target() != actor) {
                    animateAs(h.target(), tRef, "hurt");
                    if (pos != null && from != null) knockback(h.target(), tRef, pos, from);
                }
                if (!(answeredHit && reaction == Battle.Reaction.PARRY)) popHit(h, 0);
            }
            if (r.counter() != null) counterAttack(r, actorRef, home);
            if (r.fleeAttempt()) pop(actor, 0, r.fled() ? "Сбежали!" : "Не вышло", STATUS);
        });
        refresh();
        Vector3d back = strikeAt;
        long returnAt = Math.max(plugin.settings().ms(plugin.settings().actionDelayMs), hitAt + 900);
        later(returnAt, () -> {
            long settle = back != null ? walk(actorRef, back, home, yawOf(actor)) : 0;
            banner = "";
            bannerSub = "";
            later(settle, () -> {
                if (back != null) Fx.teleport(store, actorRef, home, yawOf(actor));
                if (battle.phase() == Battle.Phase.PRESENTING) battle.acknowledge();
                refresh();
            });
        });
    }

    private static boolean isMelee(Combatant actor, Ability ab, Combatant firstTarget, boolean item) {
        return firstTarget != null && firstTarget.side != actor.side && ab != null && ab.damagePower() > 0
                && !ab.magical() && !item;
    }

    private static Vector3d strikePoint(Vector3d home, Vector3d aim) {
        return new Vector3d(aim.x + (home.x - aim.x) * 0.25, aim.y, aim.z + (home.z - aim.z) * 0.25);
    }

    /**
     * Частица попадания: стихия способности (огонь, лёд…). У обычных ударов оружием частиц нет
     * (в 0.7.0 сюда подбиралась «ледяная» вспышка) — только если задать settings.json → particles.physical.
     */
    private String impactParticle(Ability ab) {
        String element = ab == null ? null : battle.content().element(ab);
        if (element == null || element.equals(Element.PHYSICAL)) {
            return plugin.settings().hitFx.isBlank() ? ParticleCatalog.get("physical") : plugin.settings().hitFx;
        }
        return ParticleCatalog.element(element);
    }

    /** Обычная «Атака» — простой замах; физические приёмы — «сильный» удар (другие анимации оружия). */
    private static boolean isBasicAttack(Combatant actor, Ability ab) {
        return ab == null || ab.id().equals(Content.ATTACK);
    }

    /**
     * Анимация бойца. Удары (attack/skill), магия и блок — сначала ванильная анимация оружия (если у бойца
     * задано "weapon" или в руке оружие), потом свои имена модели (enemies.json "anim", settings.json
     * animations.heroes), потом общие имена из settings.json.
     */
    private void animateAs(Combatant c, Ref<EntityStore> ref, String kind) {
        Settings.Animations an = plugin.settings().anim;
        if (!an.enabled || ref == null) return;
        String modelKind = kind.equals("skill") ? "attack" : kind;
        List<String> own = ownAnims(c, kind);
        if (own.isEmpty() && !modelKind.equals(kind)) own = ownAnims(c, modelKind);
        List<String> weaponWords = switch (kind) {
            case "attack" -> an.weaponAttack;
            case "skill" -> an.weaponSkill;
            case "cast" -> an.weaponCast;
            case "parry" -> an.weaponParry;
            default -> List.of();
        };
        List<String> weapon = ownAnims(c, "weapon");
        boolean useWeapon = !weaponWords.isEmpty() && (!weapon.isEmpty()
                || (own.isEmpty() && !kind.equals("cast") && WeaponAnims.holdsWeapon(store, ref)));
        if (useWeapon && WeaponAnims.play(store, ref, weapon, weaponWords)) return;
        List<String> common = switch (modelKind) {
            case "attack" -> an.attack;
            case "cast" -> an.cast;
            case "hurt" -> an.hurt;
            case "death" -> an.death;
            case "victory" -> an.victory;
            case "dodge" -> an.dodge;
            case "parry" -> an.parry;
            default -> List.of();
        };
        if (Fx.animate(store, ref, own, common)) return;
        // У модели нет своих ударов (скелет, трорк) — пробуем оружие в руке.
        if (!useWeapon && !weaponWords.isEmpty()) WeaponAnims.play(store, ref, weapon, weaponWords);
    }

    /** Свои имена анимаций бойца вида kind: enemies.json → anim, для героев settings.json → animations.heroes. */
    private List<String> ownAnims(Combatant c, String kind) {
        Settings.Animations an = plugin.settings().anim;
        try {
            if (c.side == Side.ENEMY && c.templateId != null) return plugin.data().enemy(c.templateId).anim(kind);
            if (an.heroes.containsKey(c.id)) return an.heroes.get(c.id).getOrDefault(kind, List.of());
        } catch (RuntimeException ignored) {
            // неизвестный шаблон — только общие имена
        }
        return List.of();
    }

    // ---------- сбит с ног / повержен ----------

    private static final String STUN = "stun";

    /**
     * Поверженный боец падает и лежит (поза в слоте Status держится, пока её не снимут): враг — пока не
     * исчезнет, герой — до конца боя. fall — сначала проиграть падение (смерть от удара).
     */
    private void lieDown(Combatant c, Ref<EntityStore> ref, boolean fall) {
        Settings.Animations an = plugin.settings().anim;
        if (!an.enabled || ref == null) return;
        downed.remove(c.id);
        if (Fx.animateIn(store, ref, com.hypixel.hytale.protocol.AnimationSlot.Status, ownAnims(c, "death"), an.death) == null && fall) {
            animateAs(c, ref, "hurt");
        }
    }

    /** Оглушили ударом: падает и лежит, пока оглушение не пройдёт (тогда {@link #wake}). */
    private void knockDown(Combatant c, Ref<EntityStore> ref) {
        Settings.Animations an = plugin.settings().anim;
        if (!an.enabled || ref == null || !downed.add(c.id)) return;
        Fx.animate(store, ref, ownAnims(c, "knockdown"), an.knockdown);
        pop(c, 0, "Сбит с ног!", STATUS);
        later(550, () -> {
            if (downed.contains(c.id) && c.isAlive()) {
                Fx.animateIn(store, ref, com.hypixel.hytale.protocol.AnimationSlot.Status, ownAnims(c, "down"), an.down);
            }
        });
    }

    /** Встать после оглушения. */
    private void wake(Combatant c) {
        if (!downed.remove(c.id)) return;
        Ref<EntityStore> ref = puppets.get(c.id);
        if (ref == null) return;
        Fx.stopAnimation(store, ref, com.hypixel.hytale.protocol.AnimationSlot.Status);
        Fx.animate(store, ref, ownAnims(c, "wake"), plugin.settings().anim.wake);
        pop(c, 0, "Встаёт", STATUS);
    }

    // ---------- уклонение и парирование ----------

    private static final String REACT = "#7fe3ff", WARN = "#ffd23f";

    /**
     * Враг замахнулся на партию: показать замах и ждать нажатия. Сначала «раздумье» (камера на враге),
     * потом подход и анимация удара; над врагом мелькает «!» в начале окна уклонения. Удар засчитывается
     * в момент impactAt, или сразу, если игрок нажал уже после него.
     */
    @Override
    public void onReactionWindow(Combatant actor, Ability ability, List<Combatant> targets) {
        Windup w = new Windup(actor, ability, targets);
        windup = w;
        banner = actor.name + ": " + ability.name();
        bannerSub = "Жми «Уклониться» или «Парировать» в момент удара!";
        clearInfo();
        refresh();
        later(plugin.settings().ms(plugin.settings().enemyThinkMs), () -> windupStart(w));
    }

    private void windupStart(Windup w) {
        if (windup != w || w.resolved) return;
        Settings.Animations an = plugin.settings().anim;
        Settings.Reactions rs = plugin.settings().reactions;
        Combatant actor = w.actor;
        Ref<EntityStore> actorRef = puppets.get(actor.id);
        Vector3d home = homes.get(actor.id);
        Vector3d targetPos = homes.get(w.targets.get(0).id);
        camera.action(home, targetPos);
        long impactIn;
        if (!w.ability.magical() && actorRef != null && home != null && targetPos != null) {
            Vector3d aim = w.targets.size() > 1 ? center : targetPos;
            w.strikeAt = strikePoint(home, aim);
            long arrive = walk(actorRef, home, w.strikeAt, Fx.yawTowards(w.strikeAt, aim));
            String kind = isBasicAttack(actor, w.ability) ? "attack" : "skill";
            later(arrive, () -> animateAs(actor, actorRef, kind));
            impactIn = arrive + 300;
        } else {
            animateAs(actor, actorRef, "cast");
            Fx.particleAt(ParticleCatalog.get("cast"), home, 1.0, playerEntity, store);
            impactIn = 450;
        }
        // Окно должно успеть открыться после начала замаха.
        impactIn = Math.max(impactIn, rs.dodgeBefore + 120);
        w.impactAt = System.currentTimeMillis() + impactIn;
        later(impactIn - rs.dodgeBefore, () -> {
            if (windup == w && !w.resolved && w.attempt == null) pop(actor, 0, "!", WARN);
        });
        later(impactIn, () -> {
            if (windup == w && w.attempt != null) resolve(w);
        });
        later(impactIn + Math.max(rs.dodgeAfter, rs.parryAfter) + 20, () -> {
            if (windup == w) resolve(w);
        });
    }

    /** Нажатие «Уклониться» (0) или «Парировать» (1) во время замаха. Одна попытка на удар. */
    private void onReact(int index) {
        Windup w = windup;
        if (w == null || w.resolved || w.attempt != null || index < 0 || index > 1) return;
        Battle.Reaction want = index == 0 ? Battle.Reaction.DODGE : Battle.Reaction.PARRY;
        Settings.Reactions rs = plugin.settings().reactions;
        long before = want == Battle.Reaction.DODGE ? rs.dodgeBefore : rs.parryBefore;
        long after = want == Battle.Reaction.DODGE ? rs.dodgeAfter : rs.parryAfter;
        long delta = w.impactAt == 0 ? Long.MIN_VALUE : System.currentTimeMillis() - w.impactAt;
        if (delta < -before) {
            w.attempt = Battle.Reaction.NONE;
            pop(w.actor, 0, "Рано!", WARN);
        } else if (delta > after) {
            w.attempt = Battle.Reaction.NONE;
            pop(w.actor, 0, "Поздно!", WARN);
        } else {
            w.attempt = want;
        }
        if (delta >= 0) resolve(w);
    }

    private void resolve(Windup w) {
        if (w.resolved || battle.phase() != Battle.Phase.AWAIT_REACTION) return;
        w.resolved = true;
        windup = null;
        answered = w;
        battle.react(w.attempt == null ? Battle.Reaction.NONE : w.attempt);
        refresh();
    }

    /** Удачный ответ: перекат или блок у цели, частицы и надпись. */
    private void showReaction(Battle.Reaction reaction, Combatant target, Ref<EntityStore> ref, Vector3d pos) {
        if (reaction == Battle.Reaction.DODGE) {
            animateAs(target, ref, "dodge");
            Fx.particleAt(ParticleCatalog.get("dodge"), pos, 0.2, playerEntity, store);
            pop(target, 0, "Уклонение!", REACT);
        } else {
            animateAs(target, ref, "parry");
            Fx.particleAt(ParticleCatalog.get("parry"), pos, 1.2, playerEntity, store);
            pop(target, 0, "Парирование!", REACT);
        }
    }

    /** Ответный удар после парирования: враг вздрагивает и отлетает (или падает). */
    private void counterAttack(ActionResult r, Ref<EntityStore> enemyRef, Vector3d enemyHome) {
        ActionResult.Hit c = r.counter();
        Combatant enemy = c.target();
        Vector3d by = r.counterBy() == null ? null : homes.get(r.counterBy().id);
        later(380, () -> {
            Ref<EntityStore> heroRef = r.counterBy() == null ? null : puppets.get(r.counterBy().id);
            if (heroRef != null) animateAs(r.counterBy(), heroRef, "attack");
            Ref<EntityStore> ref = puppets.get(enemy.id);
            Vector3d at = ref == null ? enemyHome : Fx.position(store, ref);
            Fx.particleAt(ParticleCatalog.get("physical"), at, 1.0, playerEntity, store);
            if (c.killed()) fallen(enemy, true);
            else animateAs(enemy, ref, "hurt");
            if (!c.killed() && ref != null && by != null) {
                // Отбросить от героя на полблока, дальше враг всё равно пойдёт домой.
                double dx = at.x - by.x, dz = at.z - by.z, len = Math.max(0.01, Math.hypot(dx, dz));
                Fx.teleport(store, ref, new Vector3d(at.x + dx / len * 0.6, at.y, at.z + dz / len * 0.6), yawOf(enemy));
            }
            pop(enemy, 0, "Ответный удар -" + c.damage(), CRIT);
        });
    }

    private Ability abilityOf(ActionResult r) {
        try {
            return r.sequenceId() == null ? null : battle.content().ability(r.sequenceId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Подойти из from в to бегом. NPC идут плавно (поза «бег», дошёл — «стоит»), игрок — мелкими телепортами.
     * Возвращает, через сколько мс боец будет на месте.
     */
    private long walk(Ref<EntityStore> ref, Vector3d from, Vector3d to, float yaw) {
        if (ref == null || from == null || to == null) return 0;
        Settings.Animations an = plugin.settings().anim;
        double dist = Math.hypot(to.x - from.x, to.z - from.z);
        long dur = Math.max(250, Math.min(1600, Math.round(dist / an.runSpeed * 1000)));
        if (ref != playerEntity) {
            plugin.mover().go(store, world, ref, from, to, dur, dist > 1.5, false, yaw);
            return dur;
        }
        int n = (int) Math.max(an.stepCount, dur / Math.max(10, an.stepMs));
        for (int i = 1; i <= n; i++) {
            double t = i / (double) n;
            Vector3d p = new Vector3d(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t, from.z + (to.z - from.z) * t);
            later(Math.round(dur * (i - 1) / (double) n), () -> Fx.teleport(store, ref, p, yaw));
        }
        return dur;
    }

    /** Цель отлетает на полблока от удара и через мгновение возвращается (NPC — плавно, игрок — телепортом). */
    private void knockback(Combatant target, Ref<EntityStore> ref, Vector3d pos, Vector3d from) {
        double dx = pos.x - from.x, dz = pos.z - from.z, len = Math.max(0.01, Math.hypot(dx, dz));
        Vector3d pushed = new Vector3d(pos.x + dx / len * 0.5, pos.y, pos.z + dz / len * 0.5);
        if (ref != playerEntity) {
            plugin.mover().slide(store, world, ref, pos, pushed, 120, yawOf(target));
            later(320, () -> {
                if (target.isAlive() && !downed.contains(target.id)) plugin.mover().slide(store, world, ref, pushed, pos, 260, yawOf(target));
            });
            return;
        }
        Fx.teleport(store, ref, pushed, yawOf(target));
        later(220, () -> {
            if (target.isAlive()) Fx.teleport(store, ref, pos, yawOf(target));
        });
    }

    @Override
    public void onCombatantDown(Combatant c) {
        addLog(c.name + " повержен!");
        if (c.side == Side.ENEMY) dropsOf.put(c.id, rollDrops(c));
        downed.remove(c.id);
        ActionResult r = current;
        boolean shownByHit = r != null && (r.hits().stream().anyMatch(h -> h.killed() && h.target() == c)
                || (r.counter() != null && r.counter().killed() && r.counter().target() == c));
        // Убит ударом — упадёт в момент удара (present/counterAttack); иначе (яд, горение) — сейчас.
        if (!shownByHit) fallen(c, true);
    }

    /**
     * Боец повержен на глазах: падает и лежит. Враг лежит lieMs, над ним всплывает добыча, потом он
     * исчезает; павший герой лежит до конца боя.
     */
    private void fallen(Combatant c, boolean fall) {
        lieDown(c, puppets.get(c.id), fall);
        if (c.side != Side.ENEMY) return;
        Map<String, Integer> got = dropsOf.getOrDefault(c.id, Map.of());
        Vector3d pos = homes.get(c.id);
        long popsFrom = 600;
        if (!got.isEmpty()) {
            later(popsFrom, () -> Fx.particleAt(ParticleCatalog.get("loot"), pos, 0.6, playerEntity, store));
            long at = popsFrom;
            for (Map.Entry<String, Integer> e : got.entrySet()) {
                pop(c, at, "+" + plugin.content().item(e.getKey()).name() + (e.getValue() > 1 ? " x" + e.getValue() : ""), LOOT);
                at += POP_STEP_MS;
            }
        }
        long gone = Math.max(plugin.settings().anim.lieMs, popsFrom + got.size() * POP_STEP_MS + 400);
        later(gone, () -> {
            Fx.particleAt(ParticleCatalog.get("death"), pos, 0.5, playerEntity, store);
            despawn(c.id);
            refresh();
        });
    }

    /** Добыча с врага (loot в enemies.json): сразу в рюкзак партии. */
    private Map<String, Integer> rollDrops(Combatant c) {
        if (c.templateId == null) return Map.of();
        Map<String, Integer> got;
        try {
            got = plugin.data().rollLoot(plugin.data().enemy(c.templateId), rnd);
        } catch (RuntimeException e) {
            return Map.of();
        }
        if (got.isEmpty()) return got;
        StringBuilder sb = new StringBuilder("Добыча: ");
        got.forEach((item, n) -> {
            battle.inventory().merge(item, n, Integer::sum);
            drops.merge(item, n, Integer::sum);
            if (sb.length() > 8) sb.append(", ");
            sb.append(plugin.content().item(item).name()).append(n > 1 ? " x" + n : "");
        });
        addLog(sb.toString());
        return got;
    }

    @Override
    public void onSummoned(Combatant c) {
        addLog("Появляется " + c.name + "!");
        Vector3d pos = arena(summonSlot % 2 == 0 ? -5 : 5, 4 + summonSlot / 2 * 2);
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
            if (!encounter.id().startsWith(SquadSpawner.ID_PREFIX)) {
                plugin.progress().markCleared(encounter, System.currentTimeMillis());
                plugin.scheduler().execute(plugin::saveWorld);
            }
            for (Combatant c : battle.alive(Side.PARTY)) animateAs(c, puppets.get(c.id), "victory");
            Map<String, Integer> loot = plugin.data().rollLoot(encounter, rnd);
            loot.forEach((item, cnt) -> battle.inventory().merge(item, cnt, Integer::sum));
            Map<String, Integer> all = new java.util.LinkedHashMap<>(drops);
            loot.forEach((item, cnt) -> all.merge(item, cnt, Integer::sum));
            StringBuilder sb = new StringBuilder("Награда: " + encounter.exp() + " опыта");
            all.forEach((item, cnt) -> sb.append(", ").append(plugin.content().item(item).name()).append(" x").append(cnt));
            if (!all.isEmpty()) sb.append(" (всё в рюкзаке: /jrpg bag)");
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
        // Опыт, добыча и потраченные зелья — сразу в сохранение.
        plugin.savePlayer(player.getUuid());
        refresh();
    }

    // ---------- всплывающие надписи ----------

    private static final String DAMAGE = "#ffffff", CRIT = "#ffd23f", HEAL = "#6be37a", MANA = "#6aa8ff",
            STATUS = "#c58cff", COMBO = "#ffa040", CURE = "#9fe0ff", LOOT = "#ffe28a";

    /** Цифры и эффекты одного попадания: урон сразу, потом статусы и комбо по очереди. */
    private void popHit(ActionResult.Hit h, long base) {
        Combatant t = h.target();
        Content k = battle.content();
        long at = base;
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
        String tail = switch (r.reaction()) {
            case DODGE -> " · Уклонение!";
            case PARRY -> " · Парирование!" + (r.counter() != null ? " Ответный удар -" + r.counter().damage() : "");
            default -> "";
        };
        if (parts.size() > 3) return parts.size() + " целей: " + String.join("; ", parts.subList(0, 3)) + "…" + tail;
        return String.join("; ", parts) + tail;
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
        cleanup(true);
    }

    /** closeMenu = false — меню сейчас заменит другое окно, закрывать его отдельно не надо. */
    private void cleanup(boolean closeMenu) {
        closed = true;
        plugin.sessions().remove(player.getUuid(), this);
        plugin.markBattleEnded(player.getUuid());
        // Игрок мог «лежать» (повержен или оглушён) — вернуть обычную позу.
        Fx.stopAnimation(store, playerEntity, com.hypixel.hytale.protocol.AnimationSlot.Status);
        despawnPuppets();
        plugin.followers().resume(player.getUuid());
        if (camera != null) camera.reset();
        BattleMusic.stop(plugin, store, playerEntity);
        if (closeMenu && page != null) {
            try {
                page.closePage();
            } catch (Throwable t) {
                plugin.warn("Не удалось закрыть меню", t);
            }
        }
    }

    boolean isClosed() { return closed; }

    /** Это кукла (или игрок) этого боя. */
    boolean owns(Ref<EntityStore> ref) {
        if (ref == null) return false;
        for (Ref<EntityStore> r : puppets.values()) if (r == ref || (r.isValid() && ref.isValid() && r.getIndex() == ref.getIndex())) return true;
        return false;
    }
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
