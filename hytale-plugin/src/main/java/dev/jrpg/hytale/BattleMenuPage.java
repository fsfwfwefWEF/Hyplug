package dev.jrpg.hytale;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jrpg.battle.Battle;
import dev.jrpg.battle.BattleMenu;
import dev.jrpg.battle.Combatant;
import dev.jrpg.battle.Content;
import dev.jrpg.battle.Describe;
import dev.jrpg.battle.Side;
import dev.jrpg.battle.StatusInstance;
import dev.jrpg.battle.TurnQueue;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Боевое меню: Common/UI/Custom/JrpgBattle/BattleMenu.ui (собирается tools/gen_battle_ui.py).
 *
 * Слева очередь ходов (#Q0..#Q7) и карточка ходящего (#Act...), сверху карточки врагов (#E0..#E4),
 * снизу партия (#P0..#P2) и журнал, справа окно описаний (#Info...) и команды (#Opt0..#Opt5 + #Back).
 * Наведение мыши на команду или кнопку «?» на карточке показывает описание в окне описаний.
 * Иконки — маленькие документы Icons/&lt;ключ&gt;_&lt;размер&gt;.ui, вставляются в пустые слоты.
 */
public final class BattleMenuPage extends InteractiveCustomUIPage<BattleMenuPage.Click> {
    public static final String LAYOUT = "JrpgBattle/BattleMenu.ui";
    public static final String ICONS = "JrpgBattle/Icons/";
    public static final int BUTTONS = 6, QUEUE = 8, ENEMIES = 5, PARTY = 3;
    private static final int BAR_CARD = 260, BAR_ENEMY = 196, BAR_ACT = 270;
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
        }
        for (int i = 0; i < ENEMIES; i++) {
            bind(evt, CustomUIEventBindingType.Activating, "#E" + i + "Info", "enemy", i);
            bind(evt, CustomUIEventBindingType.MouseEntered, "#E" + i + "Info", "enemy", i);
        }
        for (int i = 0; i < PARTY; i++) {
            bind(evt, CustomUIEventBindingType.Activating, "#P" + i + "Info", "ally", i);
            bind(evt, CustomUIEventBindingType.MouseEntered, "#P" + i + "Info", "ally", i);
        }
        bind(evt, CustomUIEventBindingType.Activating, "#Back", "back", -1);
        fill(cmd);
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
            case "enemy" -> { if (i >= 0 && i < ENEMIES) session.onInspect(enemyIds[i]); }
            case "ally" -> { if (i >= 0 && i < PARTY) session.onInspect(partyIds[i]); }
            default -> { }
        }
    }

    /** Обновить содержимое без пересборки страницы. */
    public void refresh() {
        UICommandBuilder cmd = new UICommandBuilder();
        fill(cmd);
        sendUpdate(cmd, false);
    }

    public void closePage() {
        close();
    }

    // ---------- заполнение ----------

    private void fill(UICommandBuilder cmd) {
        Battle b = session.battle();
        Content k = b.content();
        fillQueue(cmd, b);
        fillActive(cmd, b, k);
        fillEnemies(cmd, b);
        fillParty(cmd, b);
        fillMenu(cmd, session.menu());
        fillInfo(cmd);
        cmd.set("#Banner.Text", session.banner());
        cmd.set("#Log.Text", String.join("\n", session.logLines()));
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
            icon(cmd, q + "Icon", portraitKey(c), "m");
        }
    }

    private void fillActive(UICommandBuilder cmd, Battle b, Content k) {
        Combatant c = b.current();
        if (c == null) c = b.combatants().get(0);
        cmd.set("#ActHeader.Text", b.phase() == Battle.Phase.ENDED ? "БОЙ ОКОНЧЕН"
                : c.side == Side.PARTY ? "ХОДИТ" : "ХОДИТ ВРАГ");
        cmd.set("#ActName.Text", c.name);
        cmd.set("#ActName.Style.TextColor", c.side == Side.PARTY ? "#ffffff" : ENEMY_COLOR);
        cmd.set("#ActTitle.Text", Describe.roleTitle(c));
        cmd.set("#ActNums.Text", "HP " + c.hp + "/" + c.stats.maxHp()
                + (c.stats.maxMp() > 0 ? "   MP " + c.mp + "/" + c.stats.maxMp() : "")
                + "\nAP " + c.ap + " / " + Combatant.MAX_AP);
        bar(cmd, "#ActHp", c.hp, c.stats.maxHp(), BAR_ACT, 10);
        bar(cmd, "#ActMp", c.mp, c.stats.maxMp(), BAR_ACT, 6);
        icon(cmd, "#ActIcon", portraitKey(c), "l");
        statusIcons(cmd, "#ActStatus", c);
        cmd.set("#ActStatusText.Text", c.statuses.isEmpty() ? "Статусов нет"
                : c.statuses.stream().map(Describe::status).collect(Collectors.joining("\n")));
        cmd.set("#ActImmune.Text", c.immunities.isEmpty() ? "" : "Иммунитет: " + c.immunities.stream()
                .map(id -> k.status(id).name()).collect(Collectors.joining(", ")));
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
            cmd.set(e + "Hp.Text", c.hp + "/" + c.stats.maxHp() + " HP" + (c.boss ? "   БОСС" : ""));
            bar(cmd, e + "Hp", c.hp, c.stats.maxHp(), BAR_ENEMY, 8);
            icon(cmd, e + "Icon", portraitKey(c), "m");
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
            cmd.set(p + "Nums.Text", "HP " + c.hp + "/" + c.stats.maxHp() + "   MP " + c.mp + "/" + c.stats.maxMp()
                    + "   AP " + c.ap);
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
            icon(cmd, "#Opt" + i + "Icon", iconKey(o.icon()), "m");
        }
        cmd.set("#Back.Visible", m.canGoBack());
    }

    private void fillInfo(UICommandBuilder cmd) {
        BattleSession.Info info = session.info();
        cmd.set("#InfoTitle.Text", info.title());
        cmd.set("#InfoText.Text", info.text());
        icon(cmd, "#InfoIcon", iconKey(info.icon()), "m");
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
