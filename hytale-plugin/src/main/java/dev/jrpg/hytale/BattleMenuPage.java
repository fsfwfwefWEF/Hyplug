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
        if (s.uiTitleFont != null && !s.uiTitleFont.isEmpty()) {
            List<String> titles = new ArrayList<>(List.of("#Banner", "#MenuTitle", "#InfoTitle", "#InsTitle", "#InsHeader",
                    "#QueueTitle", "#LogTitle"));
            for (int i = 0; i < ENEMIES; i++) titles.add("#E" + i + "Name");
            for (int i = 0; i < PARTY; i++) titles.add("#P" + i + "Name");
            for (String id : titles) cmd.set(id + ".Style.FontName", s.uiTitleFont);
        }
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
     * Окна реплик над головами: слой на весь экран, окно ставят доли распорок (FlexWeight) по тому, где голова
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
            int left = (int) Math.round(fx * 1000), top = (int) Math.round(fy * 1000);
            cmd.set(sp + "Left.FlexWeight", left);
            cmd.set(sp + "Right.FlexWeight", 1000 - left);
            cmd.set(sp + "Top.FlexWeight", top);
            cmd.set(sp + "Bottom.FlexWeight", 1000 - top);
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
            icon(cmd, "#Opt" + i + "Icon", iconKey(o.icon()), "s");
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

    private static final int LOG_LINES = 14, INS_LINES = 16;

    /** Журнал боя слева: последние строки, каждая своим цветом (урон партии, урон врагов, лечение…). */
    private void fillLog(UICommandBuilder cmd) {
        List<BattleSession.LogEntry> j = session.journal();
        int from = Math.max(0, j.size() - LOG_LINES);
        for (int i = 0; i < LOG_LINES; i++) {
            int at = from + i;
            BattleSession.LogEntry e = at < j.size() ? j.get(at) : null;
            cmd.set("#Log" + i + ".TextSpans", e == null ? Message.raw("")
                    : RichText.line(e.text(), RichText.DARK, e.color(), names(true), names(false)));
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
        cmd.set("#InfoText.TextSpans", RichText.describe(info.text(), RichText.DARK, names(true), names(false), true));
        icon(cmd, "#InfoIcon", iconKey(info.icon()), "s");
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
        if (!session.settings().uiIcons) return;
        String want = key == null || key.isEmpty() ? "" : key + "_" + size;
        if (want.equals(shownIcons.get(slot))) return;
        shownIcons.put(slot, want);
        cmd.clear(slot);
        if (!want.isEmpty()) cmd.append(slot, ICONS + want + ".ui");
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
