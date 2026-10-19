package dev.jrpg.hytale;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jrpg.battle.Battle;
import dev.jrpg.battle.BattleMenu;
import dev.jrpg.battle.Combatant;
import dev.jrpg.battle.Describe;
import dev.jrpg.battle.Side;
import dev.jrpg.battle.StatusInstance;
import dev.jrpg.battle.TurnQueue;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Боевое меню: Common/UI/Custom/JrpgBattle/BattleMenu.ui (собирается tools/gen_battle_ui.py).
 *
 * Сверху карточки врагов (#E0..#E4) и баннер хода (#Banner/#BannerSub), слева очередь ходов (#Q0..#Q7),
 * внизу одной полосой партия (#P0..#P2), команды (#Opt0..#Opt5 + #Back) и окно описаний (#Info...).
 * Наведение мыши на команду или кнопку «?» на карточке показывает описание в окне описаний.
 * Иконки — маленькие документы Icons/&lt;ключ&gt;_&lt;размер&gt;.ui, вставляются в пустые слоты.
 */
public final class BattleMenuPage extends InteractiveCustomUIPage<BattleMenuPage.Click> implements AnimatedPage {
    public static final String LAYOUT = "JrpgBattle/BattleMenu.ui";
    public static final String ICONS = "JrpgBattle/Icons/";
    public static final int BUTTONS = 6, QUEUE = 8, ENEMIES = 5, PARTY = 3;
    private static final int BAR_CARD = 220, BAR_ENEMY = 150;
    private static final String PARTY_COLOR = "#a9d8ff", ENEMY_COLOR = "#ffb3a6";

    private final BattleSession session;
    /** Что сейчас вставлено в каждый слот иконки (чтобы не пересылать одно и то же). */
    private final Map<String, String> shownIcons = new HashMap<>();
    /** id бойцов в карточках врагов и партии (для кнопок «?»). */
    private final String[] enemyIds = new String[ENEMIES];
    private final String[] partyIds = new String[PARTY];

    // ---------- анимации окна (settings.json → ui.animations) ----------
    // Геометрия из tools/gen_battle_ui.py: очередь (QW_*), карточки, панель команд.
    private static final int QW_LEFT = 11, QW_W = 214, QW_CUR_H = 46, QW_H = 34, QW_GAP = 3, QW_TOP = 36;
    private static final int ENEMY_W = 214, ENEMY_H = 84, PARTY_W = 448, PARTY_H = 72;
    private static final int MENU_W = 350, MENU_H = 280, MENU_HIDDEN = 300, ROW_W = 324, ROW_H = 36;
    private static final int QUEUE_PANEL_W = 236;

    private final UiAnim anim = new UiAnim();
    private boolean ticking;
    /** Когда окно собрано и когда ушёл последний кадр анимации (для ClickBuffer). */
    private volatile long builtAt, lastFrameAt;
    /** Кто сейчас показан в очереди (id): по смене первого — анимация сдвига. */
    private List<String> shownQueue = List.of();
    private boolean queueAnimating;
    private String shownTurn;
    private Boolean menuShown;
    private String menuSig = "";
    /** Ширина полосок HP/MP на экране и куда она едет. */
    private final Map<String, Integer> barShown = new HashMap<>(), barTarget = new HashMap<>();
    private int logSeen = -1, logFlashFrom = Integer.MAX_VALUE;

    public BattleMenuPage(@Nonnull PlayerRef playerRef, BattleSession session) {
        super(playerRef, CustomPageLifetime.CanDismiss, Click.CODEC);
        this.session = session;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(LAYOUT);
        shownIcons.clear();
        for (int i = 0; i < BUTTONS; i++) {
            bind(evt, CustomUIEventBindingType.Activating, "#Opt" + i, "opt", i);
            bind(evt, CustomUIEventBindingType.MouseEntered, "#Opt" + i, "hover", i);
            bind(evt, CustomUIEventBindingType.MouseExited, "#Opt" + i, "unhover", i);
        }
        // Наведение на «?» только показывает описание и подсвечивает карточку; клик — выбор цели или осмотр.
        for (int i = 0; i < ENEMIES; i++) {
            bind(evt, CustomUIEventBindingType.Activating, "#E" + i + "Info", "enemy", i);
            bind(evt, CustomUIEventBindingType.MouseEntered, "#E" + i + "Info", "peekEnemy", i);
            bind(evt, CustomUIEventBindingType.MouseExited, "#E" + i + "Info", "unpeek", i);
        }
        // Невидимые кнопки над врагами в мире: наведение — кольцо и описание, клик — выбор цели.
        for (int i = 0; i < ENEMIES; i++) {
            bind(evt, CustomUIEventBindingType.Activating, "#Hit" + i, "hitClick", i);
            bind(evt, CustomUIEventBindingType.MouseEntered, "#Hit" + i, "hitHover", i);
            bind(evt, CustomUIEventBindingType.MouseExited, "#Hit" + i, "unpeek", i);
        }
        for (int i = 0; i < PARTY; i++) {
            bind(evt, CustomUIEventBindingType.Activating, "#P" + i + "Info", "ally", i);
            bind(evt, CustomUIEventBindingType.MouseEntered, "#P" + i + "Info", "peekAlly", i);
            bind(evt, CustomUIEventBindingType.MouseExited, "#P" + i + "Info", "unpeek", i);
        }
        bind(evt, CustomUIEventBindingType.Activating, "#Back", "back", -1);
        bind(evt, CustomUIEventBindingType.Activating, "#RewardOk", "reward", 0);
        bind(evt, CustomUIEventBindingType.Activating, "#RewardGrid", "reward", 1);
        style(cmd);
        rewardOn = false;
        anim.clear();
        ticking = false;
        shownQueue = List.of();
        queueAnimating = false;
        shownTurn = null;
        menuShown = null;
        menuSig = "";
        barShown.clear();
        barTarget.clear();
        logSeen = -1;
        logFlashFrom = Integer.MAX_VALUE;
        logTyped = Integer.MAX_VALUE;
        logHot = false;
        builtAt = System.currentTimeMillis();
        fill(cmd);
        if (animated()) opening();
        anim.tick(cmd, System.currentTimeMillis());
        session.hudLater(UiAnim.FRAME_MS, this::kick);
    }

    private boolean animated() {
        return session.settings().uiAnimations;
    }

    /** Какие анимации уже запускались (в лог по разу на запуск сервера — проверка, что они срабатывают). */
    private static final java.util.Set<String> NOTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void note(String what) {
        if (NOTED.add(what)) session.plugin().log("Анимация окна боя: " + what);
    }

    /** Окно появляется: очередь выезжает слева, враги падают сверху, партия выезжает слева, баннер сверху. */
    private void opening() {
        note("появление окна (очередь, враги, партия, баннер)");
        anim.play("queueIn", 0, 340, (c, t) -> UiAnim.box(c, "#QueuePanel",
                UiAnim.ilerp(-QUEUE_PANEL_W - 20, 0, UiAnim.easeOut(t)), 0, QUEUE_PANEL_W, queuePanelH()));
        for (int i = 0; i < ENEMIES; i++) {
            String id = "#E" + i + "Move";
            anim.play("enemyIn" + i, 60L * i, 320, (c, t) -> UiAnim.box(c, id, 0,
                    UiAnim.ilerp(-ENEMY_H - 30, 0, UiAnim.easeOutBack(t)), ENEMY_W, ENEMY_H));
        }
        for (int i = 0; i < PARTY; i++) {
            String id = "#P" + i + "Move";
            anim.play("partyIn" + i, 90L * i, 340, (c, t) -> UiAnim.box(c, id,
                    UiAnim.ilerp(-PARTY_W - 40, 0, UiAnim.easeOut(t)), 0, PARTY_W, PARTY_H));
        }
        bannerDrop(120);
    }

    private static int queuePanelH() {
        return qTop(QUEUE - 1) + QW_H + 12;
    }

    private static int qTop(int i) {
        return i == 0 ? QW_TOP : QW_TOP + QW_CUR_H + QW_GAP + (i - 1) * (QW_H + QW_GAP);
    }

    private void bannerDrop(long delay) {
        anim.play("banner", delay, 360, (c, t) -> UiAnim.row(c, "#BannerSlide",
                UiAnim.ilerp(-70, 0, UiAnim.easeOutBack(t)), 62));
    }

    /** Запустить кадры, если есть что анимировать. Поток мира. */
    private void kick() {
        if (ticking || !anim.active()) return;
        ticking = true;
        session.hudLater(UiAnim.FRAME_MS, this::frame);
    }

    private void frame() {
        ticking = false;
        if (!Pages.isOpen(this, playerRef)) {
            anim.clear();
            queueAnimating = false;
            return;
        }
        try {
            UICommandBuilder cmd = new UICommandBuilder();
            anim.tick(cmd, System.currentTimeMillis());
            RuntimeException err = anim.takeError();
            if (err != null) session.plugin().warn("Анимация окна боя сломалась и снята", err);
            if (cmd.getCommands().length > 0) {
                lastFrameAt = System.currentTimeMillis();
                if (Pages.update(this, playerRef, cmd)) note("кадры анимации уходят клиенту");
            }
        } finally {
            kick();
        }
    }

    /** Окно сейчас анимируется (кадры шли недавно) и собрано не только что — для ClickBuffer. */
    @Override
    public boolean animatingNow() {
        long now = System.currentTimeMillis();
        return now - lastFrameAt < 400 && now - builtAt > 400;
    }

    /** Шрифт заголовков и кнопки на своих текстурах (settings.json → ui.titleFont, ui.skinButtons). */
    private void style(UICommandBuilder cmd) {
        Settings s = session.settings();
        // Style.FontName из плагина клиент запрещает и рвёт соединение (краш 0.9.0), шрифт только в разметке .ui.
        if (s.uiSkinButtons) {
            List<String> buttons = new ArrayList<>();
            for (int i = 0; i < BUTTONS; i++) buttons.add("#Opt" + i);
            for (int i = 0; i < ENEMIES; i++) buttons.add("#E" + i + "Info");
            for (int i = 0; i < PARTY; i++) buttons.add("#P" + i + "Info");
            buttons.add("#Back");
            for (String id : buttons) skinButton(cmd, id);
        }
    }

    private static void skinButton(UICommandBuilder cmd, String id) {
        cmd.setObject(id + ".Style.Default.Background", patch("jr_btn.png"));
        cmd.setObject(id + ".Style.Hovered.Background", patch("jr_btn_hover.png"));
        cmd.setObject(id + ".Style.Pressed.Background", patch("jr_btn_press.png"));
        cmd.setObject(id + ".Style.Disabled.Background", patch("jr_btn_off.png"));
        cmd.set(id + ".Style.Default.LabelStyle.TextColor", "#f3e7c9");
        cmd.set(id + ".Style.Hovered.LabelStyle.TextColor", "#fff4d0");
    }

    private static PatchStyle patch(String texture) {
        return new PatchStyle(Value.of(texture), Value.of(12));
    }

    private static void bind(UIEventBuilder evt, CustomUIEventBindingType type, String selector, String action, int index) {
        evt.addEventBinding(type, selector,
                new EventData().append("Action", action).append("Index", String.valueOf(index)), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Click data) {
        int i = parse(data.index);
        switch (data.action == null ? "" : data.action) {
            case "back" -> session.onBack();
            case "reward" -> session.onReward(i);
            case "opt" -> {
                press(i);
                session.onOption(i);
            }
            case "hover" -> session.onHover(i);
            case "unhover" -> session.onUnhover(i);
            case "peekEnemy" -> { if (i >= 0 && i < ENEMIES) session.onPeek(enemyIds[i]); }
            case "peekAlly" -> { if (i >= 0 && i < PARTY) session.onPeek(partyIds[i]); }
            case "unpeek" -> session.onUnpeek();
            case "enemy" -> { if (i >= 0 && i < ENEMIES) session.onInspect(enemyIds[i]); }
            case "hitHover" -> { if (i >= 0 && i < ENEMIES) session.onHitHover(enemyIds[i]); }
            case "hitClick" -> { if (i >= 0 && i < ENEMIES) session.onHitClick(enemyIds[i]); }
            case "ally" -> { if (i >= 0 && i < PARTY) session.onInspect(partyIds[i]); }
            default -> { }
        }
    }

    /** Обновить содержимое без пересборки страницы. */
    public void refresh() {
        if (!Pages.isOpen(this, playerRef)) return;
        UICommandBuilder cmd = new UICommandBuilder();
        fill(cmd);
        anim.tick(cmd, System.currentTimeMillis());
        Pages.update(this, playerRef, cmd);
        kick();
    }

    /** Нажатие команды: строка коротко вздрагивает вправо (если меню не сменится и не перезапустит строки). */
    private void press(int i) {
        if (!animated() || i < 0 || i >= BUTTONS) return;
        String id = "#Row" + i + "Body";
        note("нажатая команда вздрагивает");
        anim.play("row" + i, 0, 180, (c, t) -> {
            c.set(id + ".Visible", true);
            UiAnim.box(c, id, 2 + (int) Math.round(7 * Math.sin(Math.PI * t)), 2, ROW_W - 4, ROW_H - 4);
        });
    }

    /** Обновить только окна реплик (печать по буквам). */
    public void refreshSpeech() {
        if (!Pages.isOpen(this, playerRef)) return;
        UICommandBuilder cmd = new UICommandBuilder();
        fillSpeech(cmd);
        Pages.update(this, playerRef, cmd);
    }

    /**
     * Окна реплик над головами: слой на весь экран, окно ставят размеры распорок (Anchor) по тому, где голова
     * на экране (BattleCamera.project). Поправка на размер окна посчитана для экрана 1920×1080.
     */
    private void fillSpeech(UICommandBuilder cmd) {
        for (int i = 0; i < BattleSession.BUBBLES; i++) {
            BattleSession.Bubble b = session.bubble(i);
            String sp = "#Sp" + i;
            cmd.set("#SpLayer" + i + ".Visible", b != null);
            if (b == null) continue;
            cmd.set(sp + "Name.Text", b.name());
            cmd.set(sp + "Name.Style.TextColor", b.party() ? "#e8c46a" : "#ff9c8a");
            cmd.set(sp + "Text.Text", b.text());
            double fx = clamp((b.x() * 1920 - 150) / 1620.0), fy = clamp((b.y() * 1080 - 76) / 1004.0);
            // Распорки слева и сверху получают размер через Anchor (проверенный способ), правая и нижняя тянутся.
            Anchor w = new Anchor();
            w.setWidth(Value.of((int) Math.round(fx * 1620)));
            cmd.setObject(sp + "Left.Anchor", w);
            Anchor h = new Anchor();
            h.setHeight(Value.of((int) Math.round(fy * 1004)));
            cmd.setObject(sp + "Top.Anchor", h);
        }
    }

    /** Кнопки над врагами: где враг на экране (по камере), размер — от роста на экране. Экран считается 1920×1080. */
    private void fillHits(UICommandBuilder cmd) {
        // Кнопки над врагами нужны только при выборе команды: во время показа действия они бы ездили под курсором.
        boolean choosing = session.battle().phase() == dev.jrpg.battle.Battle.Phase.AWAIT_COMMAND;
        for (int i = 0; i < ENEMIES; i++) {
            double[] box = !choosing || enemyIds[i] == null ? null : session.screenBox(enemyIds[i]);
            cmd.set("#HitLayer" + i + ".Visible", box != null);
            if (box == null) continue;
            int h = (int) Math.round(Math.max(70, Math.min(420, (box[2] - box[1]) * 1080 * 1.15)));
            int w = (int) Math.round(Math.max(80, Math.min(360, h * 0.9)));
            int x = (int) Math.round(box[0] * 1920 - w / 2.0), y = (int) Math.round(box[2] * 1080 - h);
            x = Math.max(0, Math.min(1920 - w, x));
            y = Math.max(0, Math.min(1080 - h, y));
            Anchor top = new Anchor();
            top.setHeight(Value.of(y));
            cmd.setObject("#Hit" + i + "Top.Anchor", top);
            Anchor row = new Anchor();
            row.setHeight(Value.of(h));
            cmd.setObject("#Hit" + i + "Row.Anchor", row);
            Anchor left = new Anchor();
            left.setWidth(Value.of(x));
            cmd.setObject("#Hit" + i + "Left.Anchor", left);
            Anchor size = new Anchor();
            size.setWidth(Value.of(w));
            size.setHeight(Value.of(h));
            cmd.setObject("#Hit" + i + ".Anchor", size);
        }
    }

    private static double clamp(double v) {
        return Math.max(0.01, Math.min(0.99, v));
    }

    public void closePage() {
        Pages.close(this, playerRef);
    }

    /** Esc или замена другим окном: бой не прерывается, сессия вернёт меню. */
    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        session.onMenuDismissed(this);
    }

    // ---------- заполнение ----------

    private void fill(UICommandBuilder cmd) {
        Battle b = session.battle();
        // Баннер, очередь и журнал обычно в HUD (BattleHud), здесь — их запасной вид.
        boolean hud = session.hudShown();
        cmd.set("#BannerRow.Visible", !hud);
        cmd.set("#QueueCol.Visible", !hud);
        cmd.set("#LogPanel.Visible", !hud);
        cmd.set("#BookSpace.Visible", hud);
        if (!hud) fillQueue(cmd, b);
        turnChange(b);
        fillEnemies(cmd, b);
        fillHits(cmd);
        fillParty(cmd, b);
        fillMenu(cmd, session.menu());
        menuMotion(cmd, b, session.menu());
        fillInfo(cmd);
        if (!hud) fillLog(cmd);
        fillInspect(cmd);
        fillGlow(cmd, session.menu());
        fillSpeech(cmd);
        fillReward(cmd);
        String banner = session.banner(), sub = session.bannerSub();
        cmd.set("#BannerBox.Visible", !banner.isEmpty() || !sub.isEmpty());
        cmd.set("#Banner.Text", banner);
        cmd.set("#BannerSub.Text", sub);
    }

    private List<Combatant> forecast(Battle b) {
        return b.phase() == Battle.Phase.ENDED ? List.of() : TurnQueue.forecast(b.combatants(), QUEUE);
    }

    /**
     * Очередь ходов. Сменился тот, кто ходит, — ходивший уезжает влево за край и исчезает, остальные
     * поднимаются на строку вверх; в конце строки встают на места и показывают новую очередь.
     */
    private void fillQueue(UICommandBuilder cmd, Battle b) {
        if (queueAnimating) return;
        List<Combatant> f = forecast(b);
        List<String> ids = f.stream().map(c -> c.id).toList();
        if (animated() && !shownQueue.isEmpty() && !f.isEmpty() && !shownQueue.get(0).equals(ids.get(0))) {
            queueAnimating = true;
            note("смена хода в очереди (ходивший уезжает влево)");
            int n = shownQueue.size();
            anim.play("queue", 0, 420, (c, t) -> {
                if (t < 1) {
                    for (int i = 0; i < n; i++) {
                        if (i == 0) {
                            UiAnim.box(c, "#Q0", UiAnim.ilerp(QW_LEFT, QW_LEFT - 280, UiAnim.easeIn(t)), qTop(0), QW_W, QW_CUR_H);
                        } else {
                            double k = UiAnim.easeInOut(Math.max(0, (t - 0.25) / 0.75));
                            UiAnim.box(c, "#Q" + i, QW_LEFT, UiAnim.ilerp(qTop(i), qTop(i - 1), k), QW_W,
                                    UiAnim.ilerp(QW_H, i == 1 ? QW_CUR_H : QW_H, k));
                        }
                    }
                    return;
                }
                for (int i = 0; i < QUEUE; i++) UiAnim.box(c, "#Q" + i, QW_LEFT, qTop(i), QW_W, i == 0 ? QW_CUR_H : QW_H);
                queueAnimating = false;
                fillQueueNow(c, forecast(session.battle()));
                // новый первый слегка «въезжает» на место
                anim.play("queueSettle", 0, 200, (c2, t2) -> UiAnim.box(c2, "#Q0",
                        QW_LEFT + UiAnim.ilerp(14, 0, UiAnim.easeOut(t2)), qTop(0), QW_W, QW_CUR_H));
            });
            return;
        }
        fillQueueNow(cmd, f);
    }

    private void fillQueueNow(UICommandBuilder cmd, List<Combatant> f) {
        shownQueue = f.stream().map(c -> c.id).toList();
        for (int i = 0; i < QUEUE; i++) {
            String q = "#Q" + i;
            boolean shown = i < f.size();
            cmd.set(q + ".Visible", shown);
            if (!shown) continue;
            Combatant c = f.get(i);
            cmd.set(q + "Name.Text", c.name);
            cmd.set(q + "Name.Style.TextColor", c.side == Side.PARTY ? PARTY_COLOR : ENEMY_COLOR);
            String sub = i == 0 ? "сейчас ходит" : (c.side == Side.PARTY ? "союзник" : (c.boss ? "БОСС" : "враг"));
            cmd.set(q + "Sub.Text", sub + "   " + c.hp + "/" + c.stats.maxHp() + " HP");
            icon(cmd, q + "Icon", portraitKey(c), i == 0 ? "m" : "s");
        }
    }

    /** Смена хода: баннер выезжает сверху; если ходит герой — его карточка выезжает слева «с пружинкой». */
    private void turnChange(Battle b) {
        Combatant cur = b.phase() == Battle.Phase.ENDED ? null : b.current();
        String id = cur == null ? null : cur.id;
        if (java.util.Objects.equals(id, shownTurn)) return;
        boolean first = shownTurn == null;
        shownTurn = id;
        if (!animated() || first || cur == null) return;
        bannerDrop(0);
        note("баннер хода выезжает сверху");
        if (cur.side != Side.PARTY) return;
        List<Combatant> party = b.combatants().stream().filter(c -> c.side == Side.PARTY).toList();
        int i = party.indexOf(cur);
        if (i < 0 || i >= PARTY) return;
        String mv = "#P" + i + "Move";
        note("карточка героя выезжает в его ход");
        anim.play("partyTurn" + i, 120, 380, (c, t) -> UiAnim.box(c, mv,
                UiAnim.ilerp(-26, 0, UiAnim.easeOutBack(t)), 0, PARTY_W, PARTY_H));
    }

    /**
     * Панель команд: в ход героя выезжает снизу, после выбора команды (и пока ходят враги) уезжает вниз.
     * Сменился экран меню (команды → цели → способности) — строки появляются по одной слева.
     */
    private void menuMotion(UICommandBuilder cmd, Battle b, BattleMenu m) {
        Combatant cur = b.current();
        boolean want = b.phase() == Battle.Phase.ENDED ? !m.options().isEmpty()
                : b.phase() == Battle.Phase.AWAIT_COMMAND && cur != null && cur.side == Side.PARTY;
        StringBuilder sig = new StringBuilder(m.title());
        for (BattleMenu.Option o : m.options()) sig.append('|').append(o.label());
        String newSig = (cur == null ? "" : cur.id) + "#" + sig;
        if (!animated()) {
            menuShown = want;
            menuSig = newSig;
            return;
        }
        boolean wasShown = Boolean.TRUE.equals(menuShown);
        if (menuShown == null || want != wasShown) {
            int from = menuShown == null ? MENU_HIDDEN : (wasShown ? 0 : MENU_HIDDEN);
            int to = want ? 0 : MENU_HIDDEN;
            menuShown = want;
            if (from != to) note(want ? "панель команд выезжает снизу" : "панель команд уезжает вниз");
            if (from == to) {
                UiAnim.box(cmd, "#MenuPanel", 0, to, MENU_W, MENU_H);
            } else {
                anim.play("menu", want ? 80 : 0, want ? 360 : 260, (c, t) -> UiAnim.box(c, "#MenuPanel", 0,
                        UiAnim.ilerp(from, to, want ? UiAnim.easeOut(t) : UiAnim.easeIn(t)), MENU_W, MENU_H));
            }
            if (want) {
                menuSig = newSig;
                stagger(m.options().size(), 200);
            }
            return;
        }
        if (want && !newSig.equals(menuSig)) {
            menuSig = newSig;
            stagger(m.options().size(), 0);
        }
    }

    /** Строки команд появляются по одной: каждая выезжает слева на 34 px. */
    private void stagger(int n, long delay) {
        note("строки команд появляются по одной");
        for (int i = 0; i < BUTTONS; i++) {
            String id = "#Row" + i + "Body";
            if (i >= n) {
                anim.cue("row" + i, 0, c -> {
                    c.set(id + ".Visible", true);
                    UiAnim.box(c, id, 2, 2, ROW_W - 4, ROW_H - 4);
                });
                continue;
            }
            anim.play("row" + i, delay + 45L * i, 220, (c, t) -> {
                c.set(id + ".Visible", t > 0);
                UiAnim.box(c, id, UiAnim.ilerp(-32, 2, UiAnim.easeOut(t)), 2, ROW_W - 4, ROW_H - 4);
            });
        }
    }

    private static final int REWARD_HEROES = 3, REWARD_LOOT = 8, REWARD_BAR = 300, REWARD_W = 660, REWARD_H = 560;
    private boolean rewardOn;

    /**
     * Окно наград после победы: опыт каждому (полоска заполняется, при новом уровне — до конца и заново),
     * новые уровни и очки навыков, добыча иконками. Окно опускается сверху, герои появляются по очереди.
     */
    private void fillReward(UICommandBuilder cmd) {
        BattleSession.Rewards r = session.shownRewards();
        cmd.set("#RewardLayer.Visible", r != null);
        if (r == null || rewardOn) return;
        rewardOn = true;
        cmd.set("#RewardTitle.Text", "ПОБЕДА!");
        cmd.set("#RewardSub.Text", r.encounter());
        int each = r.heroes().isEmpty() ? r.exp() : r.heroes().get(0).gained();
        cmd.set("#RewardExp.Text", "Опыт за бой: " + r.exp() + (r.heroes().size() > 1 ? "  (по " + each + " каждому)" : ""));
        for (int i = 0; i < REWARD_HEROES; i++) {
            String p = "#Rw" + i;
            BattleSession.HeroGain g = i < r.heroes().size() ? r.heroes().get(i) : null;
            cmd.set(p + ".Visible", g != null);
            if (g == null) continue;
            cmd.set(p + "Name.Text", g.name());
            int ups = g.levelAfter() - g.levelBefore();
            cmd.set(p + "Lvl.Text", ups > 0 ? "Уровень " + g.levelBefore() + " → " + g.levelAfter() + "!  Очков навыков: " + g.points()
                    : "Уровень " + g.levelAfter() + (g.points() > 0 ? "   очков навыков: " + g.points() : ""));
            cmd.set(p + "Lvl.Style.TextColor", ups > 0 ? "#ffe08a" : "#b4c2d6");
            cmd.set(p + "Exp.Text", "+" + g.gained() + " опыта");
            cmd.set(p + "Next.Text", "до следующего уровня: " + Math.max(0, g.nextAfter() - g.expAfter()));
            icon(cmd, p + "Icon", iconKey("portrait:" + g.id()), "m");
            int from = (int) Math.round(REWARD_BAR * Math.min(1.0, g.expBefore() / (double) Math.max(1, g.nextBefore())));
            int to = (int) Math.round(REWARD_BAR * Math.min(1.0, g.expAfter() / (double) Math.max(1, g.nextAfter())));
            setBar(cmd, p + "Bar", from, 8);
            if (animated()) {
                anim.play("rewardBar" + i, 500 + 250L * i, ups > 0 ? 1100 : 700, (c, t) -> {
                    int w = ups == 0 ? UiAnim.ilerp(from, to, UiAnim.easeOut(t))
                            : t < 0.5 ? UiAnim.ilerp(from, REWARD_BAR, UiAnim.easeIn(t * 2))
                            : UiAnim.ilerp(0, to, UiAnim.easeOut((t - 0.5) * 2));
                    setBar(c, p + "Bar", w, 8);
                });
                String mv = p;
                anim.play("rewardRow" + i, 150 + 120L * i, 260, (c, t) -> c.set(mv + ".Visible", t > 0));
            } else {
                setBar(cmd, p + "Bar", to, 8);
            }
        }
        cmd.set("#RewardLootTitle.Text", r.loot().isEmpty() ? "ДОБЫЧА: ничего не выпало" : "ДОБЫЧА (всё уже в рюкзаке)");
        for (int i = 0; i < REWARD_LOOT; i++) {
            BattleSession.LootLine l = i < r.loot().size() ? r.loot().get(i) : null;
            cmd.set("#Loot" + i + ".Visible", l != null);
            if (l == null) continue;
            String more = i == REWARD_LOOT - 1 && r.loot().size() > REWARD_LOOT ? "  и ещё " + (r.loot().size() - REWARD_LOOT) : "";
            cmd.set("#Loot" + i + "Name.Text", l.name() + (l.count() > 1 ? "  x" + l.count() : "") + more);
            cmd.set("#Loot" + i + "Name.Style.TextColor", l.color() != null ? l.color() : "#e9eef6");
            icon(cmd, "#Loot" + i + "Icon", iconKey(l.icon()), "r", l.tier());
        }
        if (animated()) {
            note("окно наград опускается, полоски опыта заполняются");
            anim.play("rewardIn", 0, 380, (c, t) -> UiAnim.box(c, "#RewardWin", 0,
                    UiAnim.ilerp(-120, 0, UiAnim.easeOut(t)), REWARD_W, REWARD_H));
        }
    }

    private void fillEnemies(UICommandBuilder cmd, Battle b) {
        List<Combatant> list = new ArrayList<>(b.alive(Side.ENEMY));
        for (int i = 0; i < ENEMIES; i++) {
            String e = "#E" + i;
            boolean shown = i < list.size();
            cmd.set(e + ".Visible", shown);
            enemyIds[i] = shown ? list.get(i).id : null;
            if (!shown) continue;
            Combatant c = list.get(i);
            cmd.set(e + "Name.Text", (c == b.current() ? "> " : "") + c.name);
            cmd.set(e + "Hp.Text", c.hp + "/" + c.stats.maxHp() + (c.boss ? " БОСС" : ""));
            bar(cmd, e + "Hp", c.hp, c.stats.maxHp(), BAR_ENEMY, 7);
            icon(cmd, e + "Icon", portraitKey(c), "s");
            statusIcons(cmd, e + "Status", c);
        }
    }

    private void fillParty(UICommandBuilder cmd, Battle b) {
        List<Combatant> list = b.combatants().stream().filter(c -> c.side == Side.PARTY).toList();
        for (int i = 0; i < PARTY; i++) {
            String p = "#P" + i;
            boolean shown = i < list.size();
            cmd.set(p + ".Visible", shown);
            partyIds[i] = shown ? list.get(i).id : null;
            if (!shown) continue;
            Combatant c = list.get(i);
            cmd.set(p + "Turn.Visible", c == b.current() && b.phase() != Battle.Phase.ENDED);
            cmd.set(p + "Name.Text", c.name + (c.isAlive() ? "" : "  (без сознания)"));
            cmd.set(p + "Nums.Text", "HP " + c.hp + "/" + c.stats.maxHp() + "  MP " + c.mp + "/" + c.stats.maxMp()
                    + "  AP " + c.ap);
            bar(cmd, p + "Hp", c.hp, c.stats.maxHp(), BAR_CARD, 8);
            bar(cmd, p + "Mp", c.mp, c.stats.maxMp(), BAR_CARD, 5);
            icon(cmd, p + "Icon", portraitKey(c), "m");
            statusIcons(cmd, p + "Status", c);
        }
    }

    private void fillMenu(UICommandBuilder cmd, BattleMenu m) {
        cmd.set("#MenuTitle.Text", m.title());
        cmd.set("#Error.Text", m.error() == null ? "" : m.error());
        List<BattleMenu.Option> opts = m.options();
        for (int i = 0; i < BUTTONS; i++) {
            boolean shown = i < opts.size();
            cmd.set("#Row" + i + ".Visible", shown);
            if (!shown) continue;
            BattleMenu.Option o = opts.get(i);
            cmd.set("#Opt" + i + ".Text", o.label());
            cmd.set("#Opt" + i + "Hint.Text", o.enabled() ? o.hint() : "нельзя");
            icon(cmd, "#Opt" + i + "Icon", iconKey(o.icon()), "s", o.tier());
        }
        cmd.set("#Back.Visible", m.canGoBack());
    }

    /**
     * Подсветка-аура: пункт меню под мышью, а на экране выбора цели — ещё и карточка этой цели;
     * карточка, над чьей «?» мышь.
     */
    private void fillGlow(UICommandBuilder cmd, BattleMenu m) {
        int hover = session.hoverIndex();
        String card = session.peekId();
        if (card == null && hover >= 0 && m.screen() == BattleMenu.Screen.TARGET && hover < m.targets().size()) {
            card = m.targets().get(hover).id;
        }
        for (int i = 0; i < BUTTONS; i++) cmd.set("#Row" + i + "Glow.Visible", i == hover);
        for (int i = 0; i < ENEMIES; i++) cmd.set("#E" + i + "Glow.Visible", card != null && card.equals(enemyIds[i]));
        for (int i = 0; i < PARTY; i++) cmd.set("#P" + i + "Glow.Visible", card != null && card.equals(partyIds[i]));
    }

    private static final int INS_LINES = 16;

    /**
     * Журнал боя слева внизу: последние строки, свежие (этот и прошлый ход) яркие, старые тусклее.
     * Новые строки печатаются по буквам золотым цветом, журнал при этом подъезжает снизу на строку;
     * через полсекунды после печати строки остывают до своего цвета.
     */
    private void fillLog(UICommandBuilder cmd) {
        List<BattleSession.LogEntry> all = session.journal();
        int now = session.turnNo();
        cmd.set("#LogSub.Text", session.encounterName());
        cmd.set("#LogTurn.Text", now > 0 ? "Ход " + now : "");
        if (animated() && logSeen >= 0 && all.size() > logSeen) {
            note("новые строки журнала печатаются, журнал подъезжает");
            if (!anim.has("logType")) {
                logFlashFrom = logSeen;
                logTyped = 0;
            }
            logSeen = all.size();
            logHot = true;
            int total = freshChars(all);
            int from = Math.min(logTyped, total);
            long dur = Math.max(250, Math.min(1100, (total - from) * 14L));
            anim.play("logType", 0, dur, (c, t) -> {
                logTyped = UiAnim.ilerp(from, total, t);
                renderLog(c);
            });
            anim.play("logScroll", 0, 300, (c, t) -> UiAnim.box(c, "#LogText", 0,
                    UiAnim.ilerp(18, 0, UiAnim.easeOut(t)), LOG_TEXT_W, LOG_TEXT_H));
            anim.play("logCool", dur + 500, 1, (c, t) -> {
                if (t < 1) return;
                logHot = false;
                renderLog(c);
            });
            return;
        }
        logSeen = all.size();
        if (anim.has("logType")) return; // строку сейчас печатают кадры
        renderLog(cmd);
    }

    private static final int LOG_TEXT_W = 414, LOG_TEXT_H = 233;
    private static final String LOG_HOT = "#ffd98a";
    /** Сколько букв свежих строк уже напечатано; свежие строки ещё горят золотым. */
    private int logTyped = Integer.MAX_VALUE;
    private boolean logHot;

    private int freshChars(List<BattleSession.LogEntry> all) {
        int n = 0;
        for (int k = Math.max(0, logFlashFrom); k < all.size(); k++) n += all.get(k).text().length();
        return n;
    }

    private void renderLog(UICommandBuilder cmd) {
        List<BattleSession.LogEntry> all = session.journal();
        int start = Math.max(0, all.size() - LOG_LINES);
        int now = session.turnNo();
        int budget = logTyped;
        // буквы свежих строк до первой показанной в окне тоже считаются напечатанными
        for (int k = Math.max(0, logFlashFrom); k < start && k < all.size(); k++) budget -= all.get(k).text().length();
        List<Message> lines = new ArrayList<>();
        for (int k = start; k < all.size(); k++) {
            BattleSession.LogEntry e = all.get(k);
            String base = e.color() == null ? RichText.DARK.text() : e.color();
            if (e.turn() < now - 1) base = dim(base);
            String text = e.text();
            if (k >= logFlashFrom) {
                if (budget <= 0) break;
                if (budget < text.length()) text = text.substring(0, budget);
                budget -= e.text().length();
                if (logHot) base = LOG_HOT;
            }
            lines.add(RichText.line(text, RichText.DARK, base, names(true), names(false)));
        }
        cmd.set("#LogText.TextSpans", RichText.lines(lines));
    }

    /** Строк в журнале (высота панели 280, шрифт 13). */
    static final int LOG_LINES = 13;

    /** Цвет старой строки: на 45% ближе к фону панели. */
    static String dim(String hex) {
        try {
            int c = Integer.parseInt(hex.substring(1, 7), 16);
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            int br = 0x10, bg = 0x18, bb = 0x26;
            r = (int) (r * 0.55 + br * 0.45);
            g = (int) (g * 0.55 + bg * 0.45);
            b = (int) (b * 0.55 + bb * 0.45);
            return String.format("#%02x%02x%02x", r, g, b);
        } catch (RuntimeException e) {
            return hex;
        }
    }

    /** Окно «Осмотр и эффекты»: выбранный боец, его эффекты с ходами и описанием, иммунитеты, слабости. */
    private void fillInspect(UICommandBuilder cmd) {
        dev.jrpg.battle.Combatant c = session.inspectTarget();
        cmd.set("#InsTitle.Text", c == null ? "Наведи на «?» у бойца" : c.name);
        cmd.set("#InsTitle.Style.TextColor", c != null && c.side == dev.jrpg.battle.Side.PARTY ? "#a9d8ff" : "#ffb3a6");
        cmd.set("#InsSub.Text", c == null ? "" : dev.jrpg.battle.Describe.roleTitle(c) + "   HP " + c.hp + "/" + c.stats.maxHp()
                + (c.stats.maxMp() > 0 ? "   MP " + c.mp + "/" + c.stats.maxMp() : ""));
        List<BattleSession.InsLine> lines = session.inspectLines(c);
        for (int i = 0; i < INS_LINES; i++) {
            BattleSession.InsLine l = i < lines.size() ? lines.get(i) : null;
            String text = l == null ? "" : (i == INS_LINES - 1 && lines.size() > INS_LINES ? "   ..." : l.text());
            cmd.set("#Ins" + i + ".TextSpans", l == null ? Message.raw("")
                    : RichText.line(text, RichText.DARK, l.color(), names(true), names(false)));
        }
    }

    private void fillInfo(UICommandBuilder cmd) {
        BattleSession.Info info = session.info();
        cmd.set("#InfoTitle.Text", info.title());
        cmd.set("#InfoText.TextSpans", RichText.describe(fit(info.text(), INFO_COLS, INFO_LINES), RichText.DARK,
                names(true), names(false), true));
        icon(cmd, "#InfoIcon", iconKey(info.icon()), "s");
    }

    /** Окно описания (470×280, шрифт 14): примерно столько букв в строке и строк влезает. */
    private static final int INFO_COLS = 54, INFO_LINES = 11;

    /** Обрезать текст, чтобы с переносами он влез в окно; лишнее — «…». */
    static String fit(String text, int cols, int maxLines) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (String line : text.split("\n", -1)) {
            int need = Math.max(1, (line.length() + cols - 1) / cols);
            if (used + need > maxLines) {
                int left = maxLines - used;
                if (left > 0) {
                    String cut = line.substring(0, Math.min(line.length(), left * cols - 2));
                    out.append(out.length() > 0 ? "\n" : "").append(cut).append("…");
                } else if (out.length() > 0) {
                    out.append("…");
                }
                return out.toString();
            }
            out.append(out.length() > 0 || used > 0 ? "\n" : "").append(line);
            used += need;
        }
        return out.toString();
    }

    /** Имена бойцов стороны (для раскраски текста). */
    private List<String> names(boolean party) {
        return session.battle().combatants().stream().filter(c -> (c.side == Side.PARTY) == party).map(c -> c.name).toList();
    }

    // ---------- иконки и полоски ----------

    private String portraitKey(Combatant c) {
        return iconKey("portrait:" + Describe.portrait(c));
    }

    /** "portrait:goblin" → "pt_goblin"; неизвестная иконка → запасная. */
    static String iconKey(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        if (raw.startsWith("portrait:")) {
            String k = "pt_" + raw.substring(9);
            return Icons.exists(k) ? k : "pt_unknown";
        }
        return Icons.exists(raw) ? raw : "magic";
    }

    private void icon(UICommandBuilder cmd, String slot, String key, String size) {
        icon(cmd, slot, key, size, 0);
    }

    private void icon(UICommandBuilder cmd, String slot, String key, String size, int tier) {
        if (!session.settings().uiIcons) return;
        putIcon(cmd, shownIcons, slot, key, size, tier);
    }

    /**
     * Вставляет иконку в слот: Icons/&lt;ключ&gt;_&lt;размер&gt;.ui и, если tier &gt; 0, поверх неё рамку уровня
     * Icons/tier_t&lt;N&gt;_&lt;размер&gt;.ui (одна прозрачная текстура на уровень для всех иконок). shown — что уже стоит в слотах.
     */
    static void putIcon(UICommandBuilder cmd, Map<String, String> shown, String slot, String key, String size, int tier) {
        boolean has = key != null && !key.isEmpty();
        int t = Math.max(0, Math.min(3, tier));
        String want = has ? key + "_" + size + (t > 0 ? "+" + t : "") : "";
        if (want.equals(shown.get(slot))) return;
        shown.put(slot, want);
        cmd.clear(slot);
        if (!has) return;
        cmd.append(slot, ICONS + key + "_" + size + ".ui");
        if (t > 0) cmd.append(slot, ICONS + (Icons.round(key) ? "tier_c" : "tier_t") + t + "_" + size + ".ui");
    }

    private void statusIcons(UICommandBuilder cmd, String slot, Combatant c) {
        if (!session.settings().uiIcons) return;
        List<String> keys = new ArrayList<>();
        for (StatusInstance s : c.statuses) {
            String k = "st_" + Describe.statusIcon(s.def.id());
            if (Icons.exists(k) && keys.size() < 8) keys.add(k);
        }
        String want = String.join(",", keys);
        if (want.equals(shownIcons.get(slot))) return;
        shownIcons.put(slot, want);
        cmd.clear(slot);
        for (String k : keys) cmd.append(slot, ICONS + k + "_s.ui");
    }

    private void bar(UICommandBuilder cmd, String prefix, int value, int max, int width, int height) {
        if (!session.settings().uiBars) {
            cmd.set(prefix + "Back.Visible", false);
            return;
        }
        cmd.set(prefix + "Back.Visible", max > 0);
        if (max <= 0) return;
        int w = Math.max(0, (int) Math.round(width * Math.max(0, Math.min(1.0, value / (double) max))));
        Integer cur = barShown.get(prefix), target = barTarget.get(prefix);
        if (!animated() || cur == null) {
            barShown.put(prefix, w);
            barTarget.put(prefix, w);
            setBar(cmd, prefix, w, height);
            return;
        }
        if (target != null && target == w) return;
        barTarget.put(prefix, w);
        int from = cur;
        note("полоски HP/MP плавно едут");
        // полоска плавно едет к новому значению (урон, лечение, трата MP)
        anim.play("bar" + prefix, 0, 450, (c, t) -> {
            int v = UiAnim.ilerp(from, w, UiAnim.easeOut(t));
            barShown.put(prefix, v);
            setBar(c, prefix, v, height);
        });
    }

    private static void setBar(UICommandBuilder cmd, String prefix, int w, int height) {
        Anchor a = new Anchor();
        a.setWidth(Value.of(Math.max(0, w)));
        a.setHeight(Value.of(height));
        cmd.setObject(prefix + "Fill.Anchor", a);
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Данные нажатия/наведения (ключи совпадают с EventData.append в bind). */
    public static final class Click {
        public static final BuilderCodec<Click> CODEC = BuilderCodec.builder(Click.class, Click::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (c, v) -> c.action = v, c -> c.action).add()
                .append(new KeyedCodec<>("Index", Codec.STRING), (c, v) -> c.index = v, c -> c.index).add()
                .build();
        String action;
        String index;

        public Click() {
        }
    }
}
