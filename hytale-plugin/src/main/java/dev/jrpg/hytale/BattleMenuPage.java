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
public final class BattleMenuPage extends InteractiveCustomUIPage<BattleMenuPage.Click> {
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
        style(cmd);
        fill(cmd);
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
            case "opt" -> session.onOption(i);
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
        Pages.update(this, playerRef, cmd);
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
        fillEnemies(cmd, b);
        fillHits(cmd);
        fillParty(cmd, b);
        fillMenu(cmd, session.menu());
        fillInfo(cmd);
        if (!hud) fillLog(cmd);
        fillInspect(cmd);
        fillGlow(cmd, session.menu());
        fillSpeech(cmd);
        String banner = session.banner(), sub = session.bannerSub();
        cmd.set("#BannerBox.Visible", !banner.isEmpty() || !sub.isEmpty());
        cmd.set("#Banner.Text", banner);
        cmd.set("#BannerSub.Text", sub);
    }

    private void fillQueue(UICommandBuilder cmd, Battle b) {
        List<Combatant> f = b.phase() == Battle.Phase.ENDED ? List.of() : TurnQueue.forecast(b.combatants(), QUEUE);
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

    /** Журнал боя слева внизу: последние строки, свежие (этот и прошлый ход) яркие, старые тусклее. */
    private void fillLog(UICommandBuilder cmd) {
        List<BattleSession.LogEntry> all = session.journal();
        List<BattleSession.LogEntry> tail = all.subList(Math.max(0, all.size() - LOG_LINES), all.size());
        int now = session.turnNo();
        List<Message> lines = new ArrayList<>();
        for (BattleSession.LogEntry e : tail) {
            String base = e.color() == null ? RichText.DARK.text() : e.color();
            if (e.turn() < now - 1) base = dim(base);
            lines.add(RichText.line(e.text(), RichText.DARK, base, names(true), names(false)));
        }
        cmd.set("#LogText.TextSpans", RichText.lines(lines));
        cmd.set("#LogSub.Text", session.encounterName());
        cmd.set("#LogTurn.Text", now > 0 ? "Ход " + now : "");
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
        int w = (int) Math.round(width * Math.max(0, Math.min(1.0, value / (double) max)));
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
