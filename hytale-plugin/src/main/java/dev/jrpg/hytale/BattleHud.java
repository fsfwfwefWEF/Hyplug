package dev.jrpg.hytale;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.jrpg.battle.Battle;
import dev.jrpg.battle.Combatant;
import dev.jrpg.battle.Describe;
import dev.jrpg.battle.Side;
import dev.jrpg.battle.TurnQueue;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HUD боя (Common/UI/Custom/JrpgBattle/BattleHud.ui): баннер хода, очередь ходов и журнал-книга.
 *
 * Здесь, а не в окне боя, потому что HUD обновляется без подтверждений клиента: частые обновления
 * (анимации) не мешают нажатиям в окне. Анимации — смена Anchor шагами раз в {@link #FRAME_MS} мс:
 *  - смена хода: ходивший уезжает из очереди влево, остальные поднимаются; баннер выезжает сверху;
 *  - книга: когда правая страница заполнена, лист переворачивается — правая страница уходит налево,
 *    справа начинается новая.
 * Выключается settings.json → ui.hud = false (тогда всё это показывает окно боя, без анимаций).
 */
final class BattleHud extends CustomUIHud {
    static final String KEY = "jrpg_battle";
    static final String LAYOUT = "JrpgBattle/BattleHud.ui";
    private static final int QUEUE = 8;
    private static final int FRAME_MS = 40;

    // геометрия из tools/gen_battle_ui.py (hud)
    private static final int Q_LEFT = 11, Q_W = 214, Q_CUR_H = 50, Q_H = 36, Q_GAP = 4, Q_TOP = 38;
    private static final int BANNER_TOP = 96, BANNER_FROM = -70;
    private static final int FLIP_RIGHT = 222, FLIP_SPINE = 220, FLIP_W = 206, FLIP_TOP = 12, FLIP_H = 266;
    /** Страница книги: символов в строке и строк на странице (шрифт 13, ширина 176). */
    private static final int PAGE_CHARS = 25, PAGE_LINES = 11;

    private final BattleSession session;
    private final Map<String, String> shownIcons = new HashMap<>();

    /** Кто сейчас показан в очереди (id), чтобы анимировать сдвиг при смене хода. */
    private List<String> shownQueue = List.of();
    private boolean queueAnimating;
    private int animSeq;

    /** Страницы книги: строки журнала (уже цветные). Правая — последняя, левая — предпоследняя. */
    private final List<List<BattleSession.LogEntry>> pages = new ArrayList<>();
    private int pageLines;
    private int bookConsumed;
    private boolean flipping;

    BattleHud(@Nonnull PlayerRef playerRef, BattleSession session) {
        super(playerRef, KEY);
        this.session = session;
        pages.add(new ArrayList<>());
    }

    @Override
    protected void build(@Nonnull UICommandBuilder cmd) {
        cmd.append(LAYOUT);
        shownIcons.clear();
        fonts(cmd);
        cmd.set("#HFlip.Visible", false);
        fillBanner(cmd);
        fillQueue(cmd, forecast());
        consumeBook();
        fillBook(cmd);
    }

    /** Шрифт с сервера менять нельзя: клиент отключает игрока («CustomUI is not allowed to change this property»). */
    private void fonts(UICommandBuilder cmd) {
    }

    /** Обычное обновление (после любого события боя): тексты, книга; очередь — если не идёт анимация. */
    void refresh() {
        UICommandBuilder cmd = new UICommandBuilder();
        fillBanner(cmd);
        if (!queueAnimating) {
            List<Combatant> f = forecast();
            List<String> ids = f.stream().map(c -> c.id).toList();
            if (!ids.equals(shownQueue)) fillQueue(cmd, f);
            else fillQueueTexts(cmd, f);
        }
        int before = pages.size();
        consumeBook();
        if (pages.size() > before && session.settings().uiAnimations && !flipping) {
            send(cmd);
            flip();
            return;
        }
        if (!flipping) fillBook(cmd);
        send(cmd);
    }

    /** Смена хода: баннер выезжает сверху, очередь сдвигается (ходивший уезжает влево). */
    void onTurn() {
        if (!session.settings().uiAnimations) {
            refresh();
            return;
        }
        int seq = ++animSeq;
        UICommandBuilder first = new UICommandBuilder();
        fillBanner(first);
        send(first);
        animateBanner(seq);
        List<Combatant> next = forecast();
        if (shownQueue.isEmpty() || next.isEmpty() || shownQueue.get(0).equals(next.get(0).id)) {
            UICommandBuilder cmd = new UICommandBuilder();
            fillQueue(cmd, next);
            send(cmd);
            return;
        }
        queueAnimating = true;
        int frames = 8;
        for (int k = 1; k <= frames; k++) {
            final int step = k;
            session.hudLater((long) k * FRAME_MS, () -> {
                if (seq != animSeq) return;
                double t = ease(step / (double) frames);
                UICommandBuilder cmd = new UICommandBuilder();
                int n = shownQueue.size();
                for (int i = 0; i < n; i++) {
                    if (i == 0) {
                        anchor(cmd, "#HQ0", (int) Math.round(Q_LEFT - t * 260), qTop(0), Q_W, Q_CUR_H);
                    } else {
                        int y = (int) Math.round(qTop(i) + (qTop(i - 1) - qTop(i)) * t);
                        anchor(cmd, "#HQ" + i, Q_LEFT, y, Q_W, Q_H);
                    }
                }
                send(cmd);
                if (step == frames) {
                    UICommandBuilder snap = new UICommandBuilder();
                    for (int i = 0; i < QUEUE; i++) anchor(snap, "#HQ" + i, Q_LEFT, qTop(i), Q_W, i == 0 ? Q_CUR_H : Q_H);
                    fillQueue(snap, forecast());
                    send(snap);
                    queueAnimating = false;
                }
            });
        }
    }

    private void animateBanner(int seq) {
        int frames = 7;
        UICommandBuilder start = new UICommandBuilder();
        bannerTop(start, BANNER_FROM);
        send(start);
        for (int k = 1; k <= frames; k++) {
            final int step = k;
            session.hudLater((long) k * FRAME_MS, () -> {
                if (seq != animSeq) return;
                UICommandBuilder cmd = new UICommandBuilder();
                double t = easeOutBack(step / (double) frames);
                bannerTop(cmd, (int) Math.round(BANNER_FROM + (BANNER_TOP - BANNER_FROM) * t));
                send(cmd);
            });
        }
    }

    private static void bannerTop(UICommandBuilder cmd, int top) {
        Anchor a = new Anchor();
        a.setLeft(Value.of(0));
        a.setRight(Value.of(0));
        a.setTop(Value.of(top));
        a.setHeight(Value.of(62));
        cmd.setObject("#HBannerRow.Anchor", a);
    }

    // ---------- книга ----------

    /** Забрать новые строки журнала и разложить по страницам (новая страница, когда правая полна). */
    private void consumeBook() {
        List<BattleSession.LogEntry> fresh = session.bookSince(bookConsumed);
        bookConsumed += fresh.size();
        for (BattleSession.LogEntry e : fresh) {
            int lines = Math.max(1, (e.text().length() + PAGE_CHARS - 1) / PAGE_CHARS);
            if (pageLines + lines > PAGE_LINES && !pages.get(pages.size() - 1).isEmpty()) {
                pages.add(new ArrayList<>());
                pageLines = 0;
                while (pages.size() > 40) pages.remove(0);
            }
            pages.get(pages.size() - 1).add(e);
            pageLines += lines;
        }
    }

    /**
     * Перелистывание: лист закрывает правую страницу и сужается к корешку (под ним уже новая страница),
     * затем раскрывается над левой (под ним уже бывшая правая) и исчезает.
     */
    private void flip() {
        flipping = true;
        int frames = 10, half = frames / 2;
        UICommandBuilder start = new UICommandBuilder();
        cmdFlip(start, FLIP_RIGHT, FLIP_W);
        start.set("#HFlip.Visible", true);
        fillPage(start, "R", pages.size() - 1);
        send(start);
        for (int k = 1; k <= frames; k++) {
            final int step = k;
            session.hudLater((long) k * FRAME_MS, () -> {
                UICommandBuilder cmd = new UICommandBuilder();
                if (step <= half) {
                    double t = ease(step / (double) half);
                    cmdFlip(cmd, FLIP_RIGHT, (int) Math.round(FLIP_W * (1 - t)));
                    if (step == half) fillPage(cmd, "L", pages.size() - 2);
                } else {
                    double t = ease((step - half) / (double) half);
                    int w = (int) Math.round(FLIP_W * t);
                    cmdFlip(cmd, FLIP_SPINE - w, w);
                }
                if (step == frames) {
                    cmd.set("#HFlip.Visible", false);
                    flipping = false;
                    fillBook(cmd);
                }
                send(cmd);
            });
        }
    }

    private static void cmdFlip(UICommandBuilder cmd, int left, int width) {
        anchor(cmd, "#HFlip", left, FLIP_TOP, Math.max(0, width), FLIP_H);
    }

    private void fillBook(UICommandBuilder cmd) {
        fillPage(cmd, "L", pages.size() - 2);
        fillPage(cmd, "R", pages.size() - 1);
    }

    private void fillPage(UICommandBuilder cmd, String side, int index) {
        List<BattleSession.LogEntry> page = index >= 0 && index < pages.size() ? pages.get(index) : List.of();
        List<String> party = names(Side.PARTY), enemies = names(Side.ENEMY);
        List<Message> lines = new ArrayList<>();
        for (BattleSession.LogEntry e : page) {
            lines.add(RichText.line(e.text(), RichText.PARCHMENT, ink(e.color()), party, enemies));
        }
        cmd.set("#HBook" + side + ".TextSpans", RichText.lines(lines));
        int number = index + 1;
        cmd.set("#HBook" + side + "Num.Text", index >= 0 ? "— " + number + " —" : "");
        cmd.set("#HBook" + side + "Head.Text", index == 0 ? session.encounterName() : "");
    }

    /** Яркие цвета журнала (для тёмного окна) → чернила для пергамента. */
    static String ink(String color) {
        if (color == null) return RichText.PARCHMENT.text();
        return switch (color.toLowerCase()) {
            case "#ffd98a", "#e8c46a", "#ffd23f" -> "#7a4e00";
            case "#ff9c8a" -> "#8e2626";
            case "#7be38c" -> "#2f6a24";
            case "#c9a0ff" -> "#5a2a80";
            case "#ff5d5d" -> "#a01818";
            case "#fff1a8" -> "#5a4a20";
            case "#9fb0c6" -> "#7a6a50";
            default -> RichText.PARCHMENT.text();
        };
    }

    // ---------- очередь и баннер ----------

    private List<Combatant> forecast() {
        Battle b = session.battle();
        return b == null || b.phase() == Battle.Phase.ENDED ? List.of() : TurnQueue.forecast(b.combatants(), QUEUE);
    }

    private void fillBanner(UICommandBuilder cmd) {
        String banner = session.banner(), sub = session.bannerSub();
        cmd.set("#HBannerRow.Visible", !banner.isEmpty() || !sub.isEmpty());
        cmd.set("#HBanner.Text", banner);
        cmd.set("#HBannerSub.Text", sub);
    }

    private void fillQueue(UICommandBuilder cmd, List<Combatant> f) {
        shownQueue = f.stream().map(c -> c.id).toList();
        cmd.set("#HQueue.Visible", !f.isEmpty());
        for (int i = 0; i < QUEUE; i++) {
            String q = "#HQ" + i;
            boolean shown = i < f.size();
            cmd.set(q + ".Visible", shown);
            if (!shown) continue;
            Combatant c = f.get(i);
            cmd.set(q + "Cur.Visible", i == 0);
            cmd.set(q + "Pty.Visible", i > 0 && c.side == Side.PARTY);
            cmd.set(q + "Ene.Visible", i > 0 && c.side != Side.PARTY);
            icon(cmd, q + "Icon", BattleMenuPage.iconKey("portrait:" + Describe.portrait(c)), i == 0 ? "m" : "s");
        }
        fillQueueTexts(cmd, f);
    }

    private void fillQueueTexts(UICommandBuilder cmd, List<Combatant> f) {
        for (int i = 0; i < Math.min(QUEUE, f.size()); i++) {
            String q = "#HQ" + i;
            Combatant c = f.get(i);
            cmd.set(q + "Name.Text", c.name);
            cmd.set(q + "Name.Style.TextColor", c.side == Side.PARTY ? "#a9d8ff" : "#ffb3a6");
            String sub = i == 0 ? "сейчас ходит" : (c.side == Side.PARTY ? "союзник" : (c.boss ? "БОСС" : "враг"));
            cmd.set(q + "Sub.Text", sub + "  ·  " + c.hp + "/" + c.stats.maxHp() + " HP");
        }
    }

    private List<String> names(Side side) {
        Battle b = session.battle();
        if (b == null) return List.of();
        return b.combatants().stream().filter(c -> (c.side == Side.PARTY) == (side == Side.PARTY)).map(c -> c.name).toList();
    }

    private void icon(UICommandBuilder cmd, String slot, String key, String size) {
        if (!session.settings().uiIcons) return;
        String want = key == null || key.isEmpty() ? "" : key + "_" + size;
        if (want.equals(shownIcons.get(slot))) return;
        shownIcons.put(slot, want);
        cmd.clear(slot);
        if (!want.isEmpty()) cmd.append(slot, BattleMenuPage.ICONS + want + ".ui");
    }

    // ---------- утилиты ----------

    private static int qTop(int i) {
        return i == 0 ? Q_TOP : Q_TOP + Q_CUR_H + 6 + (i - 1) * (Q_H + Q_GAP);
    }

    private static void anchor(UICommandBuilder cmd, String id, int left, int top, int width, int height) {
        Anchor a = new Anchor();
        a.setLeft(Value.of(left));
        a.setTop(Value.of(top));
        a.setWidth(Value.of(width));
        a.setHeight(Value.of(height));
        cmd.setObject(id + ".Anchor", a);
    }

    private static double ease(double t) {
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    private static double easeOutBack(double t) {
        double c1 = 1.4, c3 = c1 + 1;
        return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
    }

    private void send(UICommandBuilder cmd) {
        try {
            update(false, cmd);
        } catch (Throwable t) {
            Fx.warnOnce("HUD боя не обновился", t);
        }
    }
}
