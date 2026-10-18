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
    /** Журнал боя для окна слева: строки уже разбиты по ширине окна, у каждой свой цвет. */
    private final Deque<LogEntry> journal = new ArrayDeque<>();
    record LogEntry(String text, String color) {}
    /** Журнал для книги в HUD: строки целиком (книга переносит сама), без обрезки по 60. */
    private final List<LogEntry> book = new java.util.ArrayList<>();
    /** Реплика, которая сейчас печатается в окне над бойцом (id → видимая часть). */
    private final Map<String, String> speaking = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Integer> speechSeq = new java.util.concurrent.ConcurrentHashMap<>();
    /** Подпись игрока до боя (на время реплики героя её заменяет окно реплики). */
    private String heroPlate;
    /** Оружие и броня героя, выданные игроку на бой (вернуть в cleanup). */
    private GearVisuals.Loan heroLoan;
    private boolean heroPlateRead;
    private Battle battle;
    private BattleMenu menu;
    private BattleMenuPage page;
    /** HUD боя (баннер, очередь, книга) с анимациями; null — выключен (settings.json → ui.hud). */
    private BattleHud hud;
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
    /** Счётчик анимаций бойца: снимать замах, только если после него не было новой анимации. */
    private final Map<Ref<EntityStore>, Integer> animSeq = new java.util.concurrent.ConcurrentHashMap<>();

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
        // Враги растут вместе с партией: средний уровень героев (см. GameData.Difficulty).
        int level = plugin.partyLevel(player.getUuid());
        battle = plugin.data().createBattle(encounter, party,
                plugin.inventoryOf(player.getUuid()), this, System.nanoTime(), level);
        // Парирование и уклонение убраны (2026-10-18: игроку неинтересны). Код реакций в battle-core остался
        // выключенным: враги бьют без фазы ожидания ответа.
        battle.setReactions(false);
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
            if (c.id.equals("hero") && plugin.settings().heroDouble && spawnHeroDouble(c, pos, yaw)) {
                // Тело игрока стоит позади своего места и уменьшено до невидимого: героя играет кукла.
                Vector3d behind = new Vector3d(pos.x + (pos.x - enemyCenter.x) * 0.15, pos.y, pos.z + (pos.z - enemyCenter.z) * 0.15);
                Fx.teleportPlayer(store, playerEntity, behind, yaw);
            } else if (c.id.equals("hero")) {
                puppets.put(c.id, playerEntity);
                Fx.teleportPlayer(store, playerEntity, pos, yaw);
                Fx.ensureCombatText(store, playerEntity, c.name);
                heroLoan = GearVisuals.lendToPlayer(store, playerEntity, heroOf(c.id));
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
        // Перед первым ходом кто-то из врагов и кто-то из партии бросают реплику (settings.json → barks).
        later(Math.max(400, intro - plugin.settings().ms(1800)), () -> barkAny(Side.ENEMY, "start"));
        later(Math.max(900, intro - plugin.settings().ms(700)), () -> barkAny(Side.PARTY, "start"));
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
        HumanLook.Look look = c.side == Side.PARTY ? HumanLook.of(c.id, plugin.settings().companionLooks.get(c.id)) : null;
        Ref<EntityStore> ref = Fx.spawnPuppet(store, roleSpec, c.name, pos, yaw, BattleText.nameplate(c, false), look);
        if (ref == null) return;
        puppets.put(c.id, ref);
        if (c.side == Side.PARTY) GearVisuals.dressNpc(store, ref, heroOf(c.id), plugin.content());
    }

    /**
     * Двойник героя: кукла на модели игрока с его скином и снаряжением. Ходит, падает и бьёт, как компаньоны
     * (игрока сервер умеет двигать только телепортом — рывками). Тело игрока на время боя уменьшается
     * (EntityScaleComponent), в конце боя размер возвращается. false — не вышло, героем остаётся игрок.
     */
    private boolean spawnHeroDouble(Combatant c, Vector3d pos, float yaw) {
        HumanLook.Look look = HumanLook.ofPlayer(store, playerEntity);
        if (look == null) return false;
        String role = plugin.settings().companionRoles.getOrDefault("hero",
                plugin.settings().companionRoles.getOrDefault("bram", plugin.settings().fallbackRole));
        Ref<EntityStore> ref = Fx.spawnPuppet(store, role, c.name, pos, yaw, BattleText.nameplate(c, false), look);
        if (ref == null) return false;
        puppets.put(c.id, ref);
        GearVisuals.dressNpc(store, ref, heroOf(c.id), plugin.content());
        Float was = Fx.scale(store, playerEntity);
        heroScale = was == null || was < 0.2f ? 1.0f : was;
        Fx.setScale(store, playerEntity, 0.01f);
        return true;
    }

    /** Размер тела игрока до боя (если героя играет двойник), иначе null. */
    private Float heroScale;

    /** Прогресс героя партии (снаряжение) или null. */
    private dev.jrpg.battle.Progression.Hero heroOf(String id) {
        try {
            return plugin.partyOf(player.getUuid()).hero(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void despawn(String id) {
        Ref<EntityStore> ref = puppets.remove(id);
        plugin.mover().forget(ref);
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
            if (ref != null && ref != playerEntity && !speaking.containsKey(c.id)) {
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
            preTarget = null;
            try {
                battle.submit(action);
            } catch (IllegalArgumentException e) {
                menu.rejected(e.getMessage());
            }
        } else if (usePreTarget()) {
            refresh();
            return;
        }
        highlightTargets();
        refresh();
    }

    void onBack() {
        if (closed) return;
        clearInfo();
        boolean wasViewing = menu.viewed() != null;
        menu.back();
        if (wasViewing && battle.phase() == Battle.Phase.AWAIT_COMMAND) {
            camera.focus(homes.get(battle.current().id), true);
            refresh();
            return;
        }
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
            ring(t.id);
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
        // Клик по карточке своего героя в свой ход: осмотр его способностей, камера в полный рост на него.
        Combatant c = battle.byId(combatantId);
        if (c != null && c.side == Side.PARTY && battle.phase() == Battle.Phase.AWAIT_COMMAND) {
            Combatant was = menu.viewed();
            boolean viewing = menu.view(c == was ? null : combatantId);
            hoverIndex = -1;
            inspectId = viewing ? combatantId : null;
            if (viewing) camera.profile(homes.get(c.id), yawOf(c));
            else camera.focus(homes.get(battle.current().id), true);
            refresh();
            return;
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
        for (Combatant t : targets) ring(t.id);
    }

    public void openMenu() {
        if (closed) return;
        BattleMenuPage next = new BattleMenuPage(player, this);
        page = next;
        Pages.open(plugin, player, playerEntity, store, next, () -> !closed && page == next, null);
        showHud();
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
        if (closed) return;
        if (hud != null) {
            try {
                hud.refresh();
            } catch (Throwable t) {
                plugin.warn("Не удалось обновить HUD боя", t);
            }
        }
        if (page == null) return;
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
    List<LogEntry> journal() { return List.copyOf(journal); }
    /** Строки журнала для книги, начиная с номера from (по порядку добавления). */
    List<LogEntry> bookSince(int from) {
        return from >= book.size() ? List.of() : List.copyOf(book.subList(Math.max(0, from), book.size()));
    }
    String encounterName() { return encounter.name(); }
    boolean hudShown() { return hud != null; }

    /** Шаг анимации HUD в потоке мира (не выполняется после конца боя). */
    void hudLater(long ms, Runnable r) {
        later(ms, r);
    }

    private void showHud() {
        if (hud != null || closed || !plugin.settings().uiHud) return;
        try {
            Player p = store.getComponent(playerEntity, Player.getComponentType());
            if (p == null) return;
            BattleHud h = new BattleHud(player, this);
            p.getHudManager().addCustomHud(player, h);
            hud = h;
        } catch (Throwable t) {
            hud = null;
            plugin.warn("HUD боя не показался (очередь и журнал будут в окне боя)", t);
        }
    }

    private void hideHud() {
        if (hud == null) return;
        hud = null;
        try {
            if (!playerEntity.isValid()) return;
            Player p = store.getComponent(playerEntity, Player.getComponentType());
            if (p != null) p.getHudManager().removeCustomHud(player, BattleHud.KEY);
        } catch (Throwable t) {
            plugin.warn("Не удалось убрать HUD боя", t);
        }
    }

    record InsLine(String text, String color) {}

    /**
     * Кого показывать в окне «Осмотр и эффекты»: наведённый «?» → кликнутый «?» → цель под мышью на
     * экране выбора цели → тот, чей ход.
     */
    Combatant inspectTarget() {
        if (peekId != null && battle.byId(peekId) != null) return battle.byId(peekId);
        if (inspectId != null && battle.byId(inspectId) != null) return battle.byId(inspectId);
        if (hoverIndex >= 0 && hoverScreen == menu.screen() && menu.screen() == BattleMenu.Screen.TARGET
                && hoverIndex < menu.targets().size()) return menu.targets().get(hoverIndex);
        return battle.current();
    }

    /** Строки окна «Осмотр и эффекты»: статусы (польза — зелёным, вред — красным), иммунитеты, слабости. */
    List<InsLine> inspectLines(Combatant c) {
        List<InsLine> out = new java.util.ArrayList<>();
        if (c == null) return out;
        Content k = battle.content();
        if (c.statuses.isEmpty()) {
            out.add(new InsLine("Эффектов нет", LOG_DIM));
        } else {
            out.add(new InsLine("Эффекты:", LOG_GOLD));
            for (StatusInstance st : c.statuses) {
                String color = goodStatus(st) ? LOG_HEAL : st.def.kind() == dev.jrpg.battle.StatusDef.Kind.MARKER ? LOG_STATUS : LOG_ENEMY;
                String head = " " + st.def.name() + (st.stacks > 1 ? " x" + st.stacks : "") + " — ещё " + dev.jrpg.battle.Describe.turns(st.turnsLeft);
                out.add(new InsLine(head, color));
                for (String part : wrap(dev.jrpg.battle.Describe.statusShort(st.def), 46)) out.add(new InsLine("    " + part.strip(), LOG_DIM));
            }
        }
        if (!c.immunities.isEmpty()) {
            String imm = String.join(", ", c.immunities.stream().map(id -> k.status(id).name()).toList());
            for (String part : wrap("Иммунитет: " + imm, 48)) out.add(new InsLine(part, "#7fd3ff"));
        }
        boolean header = false;
        for (Combo combo : k.combos()) {
            if (c.immunities.contains(combo.requires())) continue;
            if (!header) {
                out.add(new InsLine("Слабости (комбо стихий):", LOG_GOLD));
                header = true;
            }
            boolean ready = c.status(combo.requires()) != null;
            String line = (ready ? " > " : "   ") + dev.jrpg.battle.Element.name(combo.element()) + " по «"
                    + k.status(combo.requires()).name() + "» → " + combo.name() + (ready ? " (готово!)" : "");
            for (String part : wrap(line, 48)) out.add(new InsLine(part, ready ? LOG_LOOT : LOG_SYS));
        }
        return out;
    }

    private static boolean goodStatus(StatusInstance st) {
        double p = st.def.power();
        return switch (st.def.kind()) {
            case HOT_PERCENT, TAUNT -> true;
            case SPEED_MULT, NEXT_ACTION_MULT -> p >= 1;
            case DAMAGE_TAKEN_MULT -> p < 1;
            default -> false;
        };
    }

    Info info() {
        Content k = battle.content();
        if (peekId != null) {
            Combatant c = battle.byId(peekId);
            if (c != null) {
                String hint = hintFor(c);
                return new Info(c.name, hint == null ? Describe.combatant(k, c) : hint + "\n" + Describe.combatant(k, c),
                        "portrait:" + Describe.portrait(c));
            }
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
            return new Info(c == null ? "Бой" : "Ход: " + c.name, "Что происходит — в журнале боя слева. Эффекты, иммунитеты "
                    + "и слабости бойца — в окне «Осмотр» справа: наведи мышь на «?» у его карточки.", "info");
        }
        return new Info(c.name, "[Клик по карточке союзника — его способности и вид в полный рост]\n" + Describe.combatant(k, c),
                "portrait:" + Describe.portrait(c));
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
        if (hud != null) hud.onTurn();
    }

    @Override
    public void onAwaitCommand(Combatant actor) {
        menu.reset();
    }

    @Override
    public void onStatusTick(Combatant t, StatusInstance s, int delta) {
        addLog(t.name + ": " + s.def.name() + " " + (delta > 0 ? "+" : "") + delta + " HP", delta > 0 ? LOG_HEAL : LOG_STATUS);
        pop(t, 0, (delta > 0 ? "+" : "") + delta + " " + s.def.name(), delta > 0 ? HEAL : STATUS);
    }

    @Override
    public void onStatusExpired(Combatant t, StatusInstance s) {
        addLog(t.name + ": " + s.def.name() + " прошло", LOG_DIM);
        if (s.def.id().equals(STUN) && t.isAlive()) wake(t);
    }

    @Override
    public void onTurnSkipped(Combatant actor, String reason) {
        addLog(actor.name + " пропускает ход (" + reason + ")", LOG_STATUS);
        pop(actor, 0, "Пропуск хода", STATUS);
    }

    @Override
    public void onActionExecuted(ActionResult r) {
        current = r;
        boolean healing = !r.hits().isEmpty() && r.hits().stream().allMatch(h -> h.damage() == 0 && (h.heal() > 0 || h.mpRestore() > 0));
        addLog(BattleText.describe(r), healing ? LOG_HEAL : r.actor().side == Side.PARTY ? LOG_PARTY : LOG_ENEMY);
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
            bark(actor, isBasicAttack(actor, ab) ? "attack" : "skill", false);
        } else if (isMelee(actor, ab, firstTarget, item) && actorRef != null && home != null && targetPos != null) {
            // Точка удара: перед целью (для удара по всем — перед центром врагов).
            Vector3d aim = ab.target() == TargetType.ALL_ENEMIES ? (actor.side == Side.PARTY ? enemyCenter : center) : targetPos;
            strikeAt = strikePoint(home, aim);
            long arrive = walk(actorRef, home, strikeAt, Fx.yawTowards(strikeAt, aim));
            String kind = isBasicAttack(actor, ab) ? "attack" : "skill";
            later(arrive, () -> {
                animateAs(actor, actorRef, kind);
                bark(actor, kind, false);
            });
            hitAt = arrive + 300;
        } else {
            animateAs(actor, actorRef, "cast");
            if (firstTarget != null && firstTarget.side != actor.side) bark(actor, "skill", false);
            if (ab != null && !item && (ab.magical() || ab.damagePower() <= 0)) {
                Fx.particleAt(ParticleCatalog.get("cast"), home, 1.0, playerEntity, store);
                if (plugin.settings().vfxShapes) Vfx.play(plugin, world, "cast", home, home, false);
            }
            hitAt = 450;
        }

        Vector3d from = strikeAt != null ? strikeAt : home;
        String impactFx = impactParticle(ab);
        // Эффект способности из фигур: снаряды и метеор начинают лететь раньше удара, остальное — в момент удара.
        String vfx = plugin.settings().vfxShapes
                ? Vfx.kindFor(ab, ab == null ? null : battle.content().element(ab), strikeAt != null) : "";
        long lead = Vfx.leadMs(vfx);
        if (lead > 0) {
            long startAt = strikeAt != null ? hitAt : 300;
            hitAt = startAt + lead;
            boolean aoe = ab != null && (ab.target() == TargetType.ALL_ENEMIES || ab.target() == TargetType.ALL_ALLIES);
            later(startAt, () -> {
                for (ActionResult.Hit h : r.hits()) {
                    if (h.target() == actor && h.damage() > 0) continue;
                    Vfx.play(plugin, world, vfx, home, homes.get(h.target().id), aoe || h.crit());
                }
            });
        }
        Battle.Reaction reaction = r.reaction();
        later(hitAt, () -> {
            for (ActionResult.Hit h : r.hits()) {
                Vector3d pos = homes.get(h.target().id);
                Ref<EntityStore> tRef = puppets.get(h.target().id);
                boolean answeredHit = reaction != Battle.Reaction.NONE && h.target().side == Side.PARTY;
                boolean thorns = h.combos().contains(Battle.THORNS_NOTE);
                if (lead == 0 && !vfx.isEmpty() && !thorns && pos != null) {
                    boolean aoe = ab != null && (ab.target() == TargetType.ALL_ENEMIES || ab.target() == TargetType.ALL_ALLIES);
                    Vfx.play(plugin, world, vfx, home, pos, aoe || h.crit());
                }
                if (h.damage() > 0) Fx.particleAt(impactFx, pos, 1.0, playerEntity, store);
                else if (h.heal() > 0 || h.mpRestore() > 0) Fx.particleAt(ParticleCatalog.get("heal"), pos, 0.4, playerEntity, store);
                else if (!h.statusesApplied().isEmpty() && h.target().side == actor.side) {
                    Fx.particleAt(ParticleCatalog.get("buff"), pos, 0.4, playerEntity, store);
                }
                if (h.killed()) {
                    fallen(h.target(), true);
                    bark(h.target(), "death", true);
                } else if (answeredHit) {
                    showReaction(reaction, h.target(), tRef, pos);
                } else if (h.statusesApplied().contains(STUN) && tRef != null && h.target().isAlive()) {
                    knockDown(h.target(), tRef);
                } else if (h.damage() > 0 && tRef != null && h.target() != actor) {
                    animateAs(h.target(), tRef, "hurt");
                    later(450, () -> bark(h.target(), "hurt", false));
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
            if (actorRef != null && actorRef != playerEntity && !downed.contains(actor.id) && actor.isAlive()) {
                Fx.stopAnimation(store, actorRef, com.hypixel.hytale.protocol.AnimationSlot.Action);
            }
            long settle = back != null ? walk(actorRef, back, home, yawOf(actor)) : 0;
            banner = "";
            bannerSub = "";
            later(settle, () -> {
                // Кукла уже дошла сама (NpcMover); телепорт поверх даёт рывок на клиенте — только для игрока.
                if (back != null && actorRef == playerEntity) Fx.teleport(store, actorRef, home, yawOf(actor));
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
        int seq = animSeq.merge(ref, 1, Integer::sum);
        if (kind.equals("attack") || kind.equals("skill") || kind.equals("cast") || kind.equals("parry")) {
            // Замах снимается через strikeMs, если сам не кончился (зацикленные «заряженные» и вращения),
            // и только если с тех пор боец не начал другую анимацию.
            later(an.strikeMs, () -> {
                if (animSeq.getOrDefault(ref, 0) == seq && !downed.contains(c.id) && c.isAlive()) {
                    Fx.stopAnimation(store, ref, com.hypixel.hytale.protocol.AnimationSlot.Action);
                }
            });
        }
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
        plugin.mover().stop(ref);
        // Сначала прервать замах/«больно», иначе после смерти они доигрывают, и боец будто встаёт.
        Fx.stopAnimation(store, ref, com.hypixel.hytale.protocol.AnimationSlot.Action);
        String death = fall ? Fx.animateIn(store, ref, com.hypixel.hytale.protocol.AnimationSlot.Status, ownAnims(c, "death"), an.death) : null;
        // Анимация смерти не зациклена: когда она кончится, держим позу «лежит» до исчезновения.
        later(death == null ? 0 : an.deathMs, () -> {
            if (c.isAlive()) return;
            Fx.animateIn(store, ref, com.hypixel.hytale.protocol.AnimationSlot.Status, null, an.lie);
        });
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
        // Игрока двигает только телепорт, а каждый телепорт клиент показывает скачком. Поэтому вместо бега —
        // короткий рывок: анимация «рывок вперёд/назад» и два телепорта (середина, конец) за dashMs.
        Vector3d heroHome = homes.getOrDefault("hero", from);
        boolean forward = Math.hypot(to.x - heroHome.x, to.z - heroHome.z) > Math.hypot(from.x - heroHome.x, from.z - heroHome.z);
        long dash = an.dashMs;
        float faceYaw = forward ? Fx.yawTowards(from, to) : Fx.yawTowards(to, from);
        Fx.animate(store, ref, forward ? an.dashForward : an.dashBack, List.of());
        Vector3d mid = new Vector3d((from.x + to.x) / 2, (from.y + to.y) / 2, (from.z + to.z) / 2);
        later(dash / 3, () -> Fx.teleport(store, ref, mid, faceYaw));
        later(dash * 2 / 3, () -> Fx.teleport(store, ref, to, yaw));
        return dash + 80;
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
        addLog(c.name + " повержен!", LOG_KILL);
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
        // Лежит lieMs после анимации смерти, потом медленно уходит в землю и исчезает.
        long gone = Math.max(plugin.settings().anim.deathMs + plugin.settings().anim.lieMs,
                popsFrom + got.size() * POP_STEP_MS + 400);
        later(gone, () -> {
            Ref<EntityStore> ref = puppets.get(c.id);
            if (ref != null && ref != playerEntity && pos != null) {
                plugin.mover().slide(store, world, ref, pos, new Vector3d(pos.x, pos.y - 1.6, pos.z), SINK_MS, yawOf(c));
            }
            Fx.particleAt(ParticleCatalog.get("death"), pos, 0.5, playerEntity, store);
            later(SINK_MS, () -> {
                despawn(c.id);
                refresh();
            });
        });
    }

    private static final long SINK_MS = 1500;

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
        addLog(sb.toString(), LOG_LOOT);
        // Снаряжение — отдельной строкой цветом редкости, чтобы его было видно в журнале.
        for (String item : got.keySet()) {
            if (!plugin.content().hasGear(item)) continue;
            dev.jrpg.battle.Equipment.Gear g = plugin.content().gear(item);
            addLog("Снаряжение: " + g.name() + " (" + g.quality().label + ")", g.quality().color);
        }
        return got;
    }

    @Override
    public void onSummoned(Combatant c) {
        addLog("Появляется " + c.name + "!", LOG_ENEMY);
        Vector3d pos = arena(summonSlot % 2 == 0 ? -5 : 5, 4 + summonSlot / 2 * 2);
        summonSlot++;
        homes.put(c.id, pos);
        spawnPuppet(c, plugin.data().enemy(c.templateId).role(), pos, faceParty(c.id));
    }

    @Override
    public void onDialogue(Combatant speaker, String text) {
        // Как в Final Fantasy: окно над головой говорящего и строка в журнале боя, без обычного чата.
        String line = speaker == null ? text : speaker.name + ": «" + text + "»";
        addLog(line, LOG_SPEECH);
        if (speaker != null) speech(speaker, text);
        else say(line);
    }

    @Override
    public void onPhaseChange(Combatant boss, String phaseName) {
        addLog("!!! " + boss.name + " — " + phaseName, LOG_KILL);
        banner = boss.name + ": " + phaseName;
    }

    @Override
    public void onBattleEnd(Battle.Outcome outcome) {
        if (outcome == Battle.Outcome.VICTORY) outro();
        else camera.overview();
        if (outcome == Battle.Outcome.VICTORY) {
            if (!encounter.id().startsWith(SquadSpawner.ID_PREFIX)) {
                plugin.progress().markCleared(encounter, System.currentTimeMillis());
                plugin.scheduler().execute(plugin::saveWorld);
            }
            celebrate();
            barkAny(Side.PARTY, "win");
            Map<String, Integer> loot = plugin.data().rollLoot(encounter, rnd);
            loot.forEach((item, cnt) -> battle.inventory().merge(item, cnt, Integer::sum));
            Map<String, Integer> all = new java.util.LinkedHashMap<>(drops);
            loot.forEach((item, cnt) -> all.merge(item, cnt, Integer::sum));
            StringBuilder sb = new StringBuilder("Награда: " + encounter.exp() + " опыта");
            all.forEach((item, cnt) -> sb.append(", ").append(plugin.content().item(item).name()).append(" x").append(cnt));
            if (!all.isEmpty()) sb.append(" (всё в рюкзаке: /jrpg bag)");
            addLog(sb.toString(), LOG_LOOT);
            say(sb.toString());
            for (String msg : plugin.partyOf(player.getUuid()).addExp(encounter.exp())) {
                addLog(msg, LOG_GOLD);
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

    /** Финал победы: облёт камеры на средней высоте вокруг партии, потом камера держит последний кадр. */
    private void outro() {
        int n = 48;
        long step = 110;
        for (int k = 0; k < n; k++) {
            int kk = k;
            later(400 + k * step, () -> {
                if (!closed) camera.outro(kk, n);
            });
        }
    }

    /**
     * Герои празднуют: каждому своя эмоция игры (танец, аплодисменты…), а если эмоций нет —
     * анимации победы из settings.json. Через ~4 с эмоция повторяется ещё раз, пока идёт облёт.
     */
    private void celebrate() {
        Settings.Animations an = plugin.settings().anim;
        java.util.Set<String> taken = new java.util.HashSet<>();
        int i = 0;
        for (Combatant c : battle.alive(Side.PARTY)) {
            Ref<EntityStore> ref = puppets.get(c.id);
            if (ref == null) continue;
            plugin.mover().stop(ref);
            Fx.stopAnimation(store, ref, com.hypixel.hytale.protocol.AnimationSlot.Action);
            // Каждому своё слово первым, чтобы не все танцевали одинаково.
            List<String> words = new java.util.ArrayList<>(an.victoryEmotes);
            if (!words.isEmpty()) java.util.Collections.rotate(words, -(i % words.size()));
            String emote = Fx.pickEmote(words, taken);
            if (emote == null) emote = Fx.pickEmote(words, java.util.Set.of());
            i++;
            String em = emote;
            long delay = 300L * i;
            later(delay, () -> {
                if (em == null || !Fx.emote(store, ref, em)) animateAs(c, ref, "victory");
            });
            if (em != null) {
                taken.add(em);
                later(delay + 4200, () -> {
                    if (!closed) Fx.emote(store, ref, em);
                });
            }
        }
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
        return dev.jrpg.battle.BattleText.COMBO_NAMES.getOrDefault(id, id);
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
        if (heroPlateRead) Fx.restoreNameplate(store, playerEntity, heroPlate);
        GearVisuals.restorePlayer(store, playerEntity, heroLoan);
        heroLoan = null;
        if (heroScale != null) {
            Fx.setScale(store, playerEntity, heroScale);
            Vector3d home = homes.get("hero");
            if (home != null) Fx.teleportPlayer(store, playerEntity, home, Fx.yawTowards(home, enemyCenter));
            Fx.restorePlayerLook(store, playerEntity);
            heroScale = null;
        }
        despawnPuppets();
        plugin.followers().resume(player.getUuid());
        if (camera != null) camera.reset();
        BattleMusic.stop(plugin, store, playerEntity);
        hideHud();
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

    // ---------- реплики ----------

    private static final String LOG_PARTY = "#ffd98a", LOG_ENEMY = "#ff9c8a", LOG_HEAL = "#7be38c",
            LOG_STATUS = "#c9a0ff", LOG_KILL = "#ff5d5d", LOG_LOOT = "#ffd23f", LOG_SPEECH = "#fff1a8",
            LOG_GOLD = "#e8c46a", LOG_DIM = "#9fb0c6", LOG_SYS = "#d6dde8";

    /** Реплика бойца на событие (settings.json → barks): над головой, в журнале и в чате. */
    private void bark(Combatant c, String event, boolean always) {
        if (c == null) return;
        List<String> lines = barkLines(c, event);
        if (lines.isEmpty()) return;
        if (!always && rnd.nextDouble() >= plugin.settings().barkChance) return;
        String text = lines.get(rnd.nextInt(lines.size()));
        addLog(c.name + ": «" + text + "»", LOG_SPEECH);
        speech(c, text);
    }

    /** Реплика кого-то одного со стороны side, у кого есть фразы на это событие. */
    private void barkAny(Side side, String event) {
        List<Combatant> who = new java.util.ArrayList<>();
        for (Combatant c : battle.alive(side)) if (!barkLines(c, event).isEmpty()) who.add(c);
        if (!who.isEmpty()) bark(who.get(rnd.nextInt(who.size())), event, true);
    }

    private List<String> barkLines(Combatant c, String event) {
        String key = c.side == Side.ENEMY && c.templateId != null ? c.templateId : c.id;
        Map<String, List<String>> byEvent = plugin.settings().barks.get(key);
        if (byEvent == null) return List.of();
        return byEvent.getOrDefault(event, List.of());
    }

    /**
     * Окно реплики над головой, как в Final Fantasy: подпись над бойцом на время реплики становится
     * «Имя: «текст»», буквы появляются по одной (speech.letterMs) со щелчком (speech.sound), потом
     * подпись возвращается. Новая реплика того же бойца обрывает старую.
     */
    private void speech(Combatant c, String text) {
        Ref<EntityStore> ref = puppets.get(c.id);
        if (ref == null || text == null || text.isBlank()) return;
        Settings.Speech cfg = plugin.settings().speech;
        int seq = speechSeq.merge(c.id, 1, Integer::sum);
        String head = c.name + ": «";
        String body = text.strip();
        boolean plates = !cfg.bubbles;
        if (plates && ref == playerEntity && !heroPlateRead) {
            heroPlateRead = true;
            heroPlate = Fx.nameplateText(store, playerEntity);
        }
        if (cfg.bubbles) bubbleSlot(c.id);
        // Окно в меню обновляем кусками (каждое обновление окна ждёт ответа клиента, а пока ждёт — нажатия
        // теряются), звук печати — на каждую букву.
        int step = (int) Math.max(1, Math.round(150.0 / Math.max(1, cfg.letterMs)));
        for (int i = 1; i <= body.length(); i++) {
            int k = i;
            later(cfg.letterMs * i, () -> {
                if (speechSeq.getOrDefault(c.id, 0) != seq || !ref.isValid()) return;
                String shown = body.substring(0, k);
                speaking.put(c.id, shown);
                if (plates) Fx.nameplate(store, ref, head + shown + (k == body.length() ? "»" : ""));
                else if (k % step == 0 || k == body.length()) refreshSpeech();
                char ch = body.charAt(k - 1);
                if (cfg.soundEvery > 0 && !Character.isWhitespace(ch) && (k - 1) % cfg.soundEvery == 0) {
                    TypeSound.play(player, cfg.sound, cfg.volume);
                }
            });
        }
        later(cfg.letterMs * body.length() + cfg.holdMs + 40L * body.length(), () -> {
            if (speechSeq.getOrDefault(c.id, 0) != seq) return;
            speaking.remove(c.id);
            bubbleSlots.values().remove(c.id);
            refreshSpeech();
            if (!plates || !ref.isValid()) return;
            if (ref == playerEntity) Fx.restoreNameplate(store, playerEntity, heroPlate);
            else Fx.nameplate(store, ref, BattleText.nameplate(c, c == battle.current()));
        });
    }

    /** Слоты окон реплик в меню (#SpLayer0..2): кто в каком говорит. */
    private final Map<Integer, String> bubbleSlots = new java.util.concurrent.ConcurrentHashMap<>();
    static final int BUBBLES = 3;

    private void bubbleSlot(String id) {
        if (bubbleSlots.containsValue(id)) return;
        for (int i = 0; i < BUBBLES; i++) {
            if (bubbleSlots.putIfAbsent(i, id) == null) return;
        }
        bubbleSlots.put(0, id);
    }

    /** Окно реплики для меню: имя, напечатанный текст, где на экране голова (0..1), сторона. */
    record Bubble(String name, String text, double x, double y, boolean party) {}

    Bubble bubble(int slot) {
        String id = bubbleSlots.get(slot);
        String text = id == null ? null : speaking.get(id);
        if (text == null || battle == null) return null;
        Combatant c = battle.combatants().stream().filter(x -> x.id.equals(id)).findFirst().orElse(null);
        if (c == null) return null;
        Ref<EntityStore> ref = puppets.get(id);
        Vector3d at = ref != null && ref.isValid() ? Fx.position(store, ref) : null;
        if (at == null) at = homes.get(id);
        double[] p = at == null || camera == null ? null : camera.project(new Vector3d(at.x, at.y + 2.3, at.z));
        if (p == null) p = new double[]{0.5, 0.22};
        return new Bubble(c.name, text, p[0], p[1], c.side == Side.PARTY);
    }

    /** Обновить только окна реплик (не во время замаха врага: там важен каждый клик). */
    private void refreshSpeech() {
        if (closed || page == null || battle.phase() == Battle.Phase.AWAIT_REACTION) return;
        page.refreshSpeech();
    }

    // ---------- мышь в мире ----------

    private String worldHover;

    /** Выполнить в потоке мира боя. */
    void onWorld(Runnable r) {
        world.execute(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                plugin.warn("Мышь в бою", t);
            }
        });
    }

    /** Это сущность игрока этого боя. */
    boolean isPlayer(Ref<EntityStore> ref) {
        return ref != null && ref.equals(playerEntity);
    }

    private String combatantAt(Ref<EntityStore> ref) {
        if (ref == null) return null;
        for (Map.Entry<String, Ref<EntityStore>> e : puppets.entrySet()) {
            if (ref.equals(e.getValue())) return e.getKey();
        }
        return null;
    }

    private Combatant alive(String id) {
        if (id == null) return null;
        for (Combatant c : battle.combatants()) if (c.id.equals(id) && c.isAlive()) return c;
        return null;
    }

    /** Курсор над сущностью в мире: под живым бойцом — кольцо и его описание в окне «Осмотр». */
    void onWorldHover(Ref<EntityStore> target) {
        if (closed || battle == null) return;
        String id = combatantAt(target);
        Combatant c = alive(id);
        String key = c == null ? null : c.id;
        if (java.util.Objects.equals(key, worldHover)) return;
        worldHover = key;
        if (!loggedMouse) {
            loggedMouse = true;
            plugin.log("Мышь в мире работает: наведение на " + (c == null ? "пустое место" : c.name));
        }
        hoverCombatant(c);
    }

    /** Мышь над кнопкой врага в окне боя (кнопка стоит там, где враг на экране). */
    void onHitHover(String id) {
        if (closed || battle == null) return;
        Combatant c = alive(id);
        if (c == null || c.id.equals(peekId)) return;
        if (!loggedHit) {
            loggedHit = true;
            plugin.log("Кнопки над врагами работают: наведение на " + c.name);
        }
        mouseSeq++;
        hoverCombatant(c);
    }

    private void hoverCombatant(Combatant c) {
        if (battle.phase() == Battle.Phase.AWAIT_REACTION) return;
        if (c != null) {
            ring(c.id);
            peekId = c.id;
        } else {
            peekId = null;
        }
        refresh();
    }

    /** Подсказка над описанием врага под курсором: что сделает клик. */
    private String hintFor(Combatant c) {
        if (c.side != Side.ENEMY || battle.phase() != Battle.Phase.AWAIT_COMMAND || !c.isAlive()) return null;
        return switch (menu.screen()) {
            case MAIN -> "[Клик — атаковать: " + battle.current().name + " бьёт " + c.name + "]";
            case TARGET -> menu.targets().contains(c) ? "[Клик — выбрать целью]" : null;
            default -> null;
        };
    }

    /** Кольцо под бойцом (у босса шире). */
    private void ring(String id) {
        Combatant c = battle == null ? null : battle.byId(id);
        Fx.ring(plugin.settings().targetRing, world, homes.get(id), c != null && c.boss ? 1.9 : 1.0, playerEntity, store);
    }

    /** Клик по кнопке врага в окне боя — как клик по нему в мире. */
    void onHitClick(String id) {
        if (closed || battle == null) return;
        clickCombatant(alive(id));
    }

    /** Где боец на экране: {x центра, y макушки, y ног} от 0 до 1; null — не виден. */
    double[] screenBox(String id) {
        Combatant c = alive(id);
        Vector3d at = c == null ? null : homes.get(id);
        if (at == null || camera == null) return null;
        double height = c.boss ? 2.6 : 1.7;
        double[] feet = camera.project(new Vector3d(at.x, at.y, at.z));
        double[] head = camera.project(new Vector3d(at.x, at.y + height, at.z));
        if (feet == null || head == null) return null;
        if (feet[0] < -0.05 || feet[0] > 1.05 || head[1] > 1.0 || feet[1] < 0) return null;
        return new double[]{feet[0], head[1], feet[1]};
    }

    private boolean loggedMouse, loggedHit;
    /** Враг, выбранный кликом в мире до выбора действия: «Атака» и навыки по одной цели бьют его сразу. */
    private String preTarget;

    /** Клик в мире по бойцу: на экране целей — выбрать его; в главном меню — запомнить цель и показать. */
    void onWorldClick(Ref<EntityStore> target) {
        if (closed || battle == null) return;
        Combatant c = alive(combatantAt(target));
        plugin.log("Мышь в мире: клик по " + (c == null ? "пустому месту" : c.name));
        clickCombatant(c);
    }

    private void clickCombatant(Combatant c) {
        if (c == null || battle.phase() != Battle.Phase.AWAIT_COMMAND) return;
        if (menu.screen() == BattleMenu.Screen.TARGET && menu.targets().contains(c)) {
            onInspect(c.id);
            return;
        }
        // Главное меню: клик по врагу — сразу обычная атака по нему.
        BattleAction attack = menu.screen() == BattleMenu.Screen.MAIN ? menu.attack(c.id) : null;
        if (attack != null) {
            ring(c.id);
            clearInfo();
            preTarget = null;
            peekId = null;
            try {
                battle.submit(attack);
            } catch (IllegalArgumentException e) {
                menu.rejected(e.getMessage());
            }
            refresh();
            return;
        }
        preTarget = c.side == Side.ENEMY ? c.id : null;
        inspectId = c.id;
        ring(c.id);
        refresh();
    }

    /** Меню перешло к выбору цели, а цель уже выбрана кликом в мире — сразу по ней. */
    private boolean usePreTarget() {
        if (preTarget == null || menu.screen() != BattleMenu.Screen.TARGET) return false;
        Combatant c = alive(preTarget);
        if (c == null || !menu.targets().contains(c)) return false;
        BattleAction action = menu.clickTarget(c.id);
        if (action == null) return false;
        preTarget = null;
        try {
            battle.submit(action);
        } catch (IllegalArgumentException e) {
            menu.rejected(e.getMessage());
        }
        return true;
    }

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
        addLog(line, LOG_SYS);
    }

    private void addLog(String line, String color) {
        for (String l : line.split("\n")) {
            log.addLast(l);
            while (log.size() > 7) log.removeFirst();
            book.add(new LogEntry(l.strip(), color));
            for (String part : wrap(l, LOG_WIDTH)) journal.addLast(new LogEntry(part, color));
            while (journal.size() > 60) journal.removeFirst();
        }
    }

    /** Ширина строки журнала в символах (окно слева, шрифт 11). */
    private static final int LOG_WIDTH = 48;

    /** Разбить строку по словам на куски не длиннее width; продолжения — с отступом. */
    static List<String> wrap(String text, int width) {
        List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : text.split(" ")) {
            if (cur.length() > 0 && cur.length() + 1 + w.length() > width) {
                out.add(cur.toString());
                cur = new StringBuilder("   ");
            }
            if (cur.length() > 0 && !cur.toString().isBlank()) cur.append(' ');
            cur.append(w);
        }
        if (!cur.toString().isBlank()) out.add(cur.toString());
        return out;
    }

    private void say(String text) {
        player.sendMessage(Message.raw(text));
    }
}
