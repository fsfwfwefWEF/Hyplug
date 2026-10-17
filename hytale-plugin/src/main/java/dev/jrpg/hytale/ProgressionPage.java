package dev.jrpg.hytale;

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
import dev.jrpg.battle.Content;
import dev.jrpg.battle.Describe;
import dev.jrpg.battle.Progression;
import dev.jrpg.battle.Progression.Grid;
import dev.jrpg.battle.Progression.GridNode;
import dev.jrpg.battle.Stats;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Круговая сетка навыков: Common/UI/Custom/JrpgBattle/Progression.ui (собирается tools/gen_battle_ui.py).
 *
 * Вкладки героев (#Tab0..2), слева карточка героя, в центре 25 узлов #G0..#G24 и рёбра #Edge0..,
 * справа описание выбранного узла и кнопка «Открыть узел». Наведение или клик по узлу выбирает его,
 * кнопки «&lt;» / «&gt;» перебирают узлы рядом с открытыми (если клик по иконке не доходит до кнопки).
 */
public final class ProgressionPage extends InteractiveCustomUIPage<BattleMenuPage.Click> {
    public static final String LAYOUT = "JrpgBattle/Progression.ui";
    private static final int TABS = 3, EXP_BAR = 266;

    private final JrpgPlugin plugin;
    private final Progression.Party party;
    private final Content content;
    private final List<int[]> edges = Grid.edges();
    private int tab;
    private int selected;
    private String message = "";
    private final Map<String, String> shownIcons = new HashMap<>();

    public ProgressionPage(@Nonnull PlayerRef playerRef, JrpgPlugin plugin, Progression.Party party, String heroId) {
        super(playerRef, CustomPageLifetime.CanDismiss, BattleMenuPage.Click.CODEC);
        this.plugin = plugin;
        this.party = party;
        this.content = plugin.content();
        List<Progression.Hero> heroes = party.heroes();
        for (int i = 0; i < heroes.size(); i++) if (heroes.get(i).def.id().equals(heroId)) tab = i;
        selected = firstAvailable();
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(LAYOUT);
        shownIcons.clear();
        for (int i = 0; i < TABS; i++) bind(evt, CustomUIEventBindingType.Activating, "#Tab" + i, "tab", i);
        for (int i = 0; i < Grid.SIZE; i++) {
            bind(evt, CustomUIEventBindingType.Activating, "#G" + i + "Btn", "node", i);
            bind(evt, CustomUIEventBindingType.MouseEntered, "#G" + i + "Btn", "hover", i);
        }
        bind(evt, CustomUIEventBindingType.Activating, "#PrevNode", "step", -1);
        bind(evt, CustomUIEventBindingType.Activating, "#NextNode", "step", 1);
        bind(evt, CustomUIEventBindingType.Activating, "#Learn", "learn", -1);
        bind(evt, CustomUIEventBindingType.Activating, "#AddExp", "exp", 100);
        bind(evt, CustomUIEventBindingType.Activating, "#Respec", "respec", -1);
        bind(evt, CustomUIEventBindingType.Activating, "#Close", "close", -1);
        fill(cmd);
    }

    private static void bind(UIEventBuilder evt, CustomUIEventBindingType type, String selector, String action, int index) {
        evt.addEventBinding(type, selector,
                new EventData().append("Action", action).append("Index", String.valueOf(index)), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull BattleMenuPage.Click data) {
        int i;
        try {
            i = Integer.parseInt(data.index);
        } catch (Exception e) {
            i = -1;
        }
        Progression.Hero h = hero();
        switch (data.action == null ? "" : data.action) {
            case "tab" -> {
                if (i >= 0 && i < party.heroes().size()) tab = i;
                selected = firstAvailable();
                message = "";
            }
            case "hover" -> {
                if (i < 0 || i >= Grid.SIZE || i == selected) return;
                selected = i;
            }
            case "node" -> {
                // Повторный клик по выбранному доступному узлу — открыть его.
                if (i >= 0 && i < Grid.SIZE) {
                    if (i == selected && h.whyCantUnlock(i) == null) learn(h);
                    else {
                        selected = i;
                        message = "";
                    }
                }
            }
            case "step" -> {
                selected = step(h, i);
                message = "";
            }
            case "learn" -> learn(h);
            case "exp" -> {
                List<String> ups = party.addExp(i * party.heroes().size());
                message = ups.isEmpty() ? "+" + i + " опыта каждому" : String.join(" ", ups);
            }
            case "respec" -> {
                h.respec();
                selected = firstAvailable();
                message = "Очки " + h.def.name() + " возвращены";
            }
            case "close" -> {
                close();
                return;
            }
            default -> { }
        }
        UICommandBuilder cmd = new UICommandBuilder();
        fill(cmd);
        sendUpdate(cmd, false);
    }

    private void learn(Progression.Hero h) {
        String err = h.unlock(selected);
        message = err != null ? "Нельзя: " + err : "Открыто: " + Describe.nodeTitle(content, h, h.node(selected));
    }

    private Progression.Hero hero() {
        List<Progression.Hero> heroes = party.heroes();
        return heroes.get(Math.max(0, Math.min(tab, heroes.size() - 1)));
    }

    /** Первый узел, который можно открыть прямо сейчас (иначе первый соседний с открытыми, иначе центр). */
    private int firstAvailable() {
        Progression.Hero h = hero();
        for (int i = 1; i < Grid.SIZE; i++) if (h.whyCantUnlock(i) == null) return i;
        for (int i = 1; i < Grid.SIZE; i++) if (!h.unlocked.contains(i) && h.reachable(i)) return i;
        return 0;
    }

    /** Следующий/предыдущий узел среди открытых и соседних с ними. */
    private int step(Progression.Hero h, int dir) {
        for (int k = 1; k <= Grid.SIZE; k++) {
            int i = Math.floorMod(selected + dir * k, Grid.SIZE);
            if (h.unlocked.contains(i) || h.reachable(i)) return i;
        }
        return selected;
    }

    private void fill(UICommandBuilder cmd) {
        List<Progression.Hero> heroes = party.heroes();
        for (int i = 0; i < TABS; i++) {
            boolean shown = i < heroes.size();
            cmd.set("#Tab" + i + ".Visible", shown);
            if (shown) {
                Progression.Hero h = heroes.get(i);
                cmd.set("#Tab" + i + ".Text", (i == tab ? "> " : "") + h.def.name() + "  ур. " + h.level
                        + (h.points > 0 ? "  (" + h.points + ")" : ""));
            }
        }
        Progression.Hero h = hero();
        fillHero(cmd, h);
        fillGrid(cmd, h);
        fillDetail(cmd, h);
    }

    private void fillHero(UICommandBuilder cmd, Progression.Hero h) {
        Stats st = h.stats();
        cmd.set("#HeroName.Text", h.def.name());
        cmd.set("#HeroTitle.Text", h.def.title() + " · роль: " + h.def.role().label);
        cmd.set("#HeroLevel.Text", "Уровень " + h.level + "   опыт " + h.exp + " / " + Progression.expToNext(h.level));
        cmd.set("#Points.Text", "Очки сетки: " + h.points);
        icon(cmd, "#HeroIcon", "pt_" + h.def.id(), "l");
        if (plugin.settings().uiBars) {
            Anchor a = new Anchor();
            a.setWidth(Value.of((int) Math.round(EXP_BAR * Math.min(1.0, h.exp / (double) Progression.expToNext(h.level)))));
            a.setHeight(Value.of(6));
            cmd.setObject("#ExpFill.Anchor", a);
        } else {
            cmd.set("#ExpBack.Visible", false);
        }
        cmd.set("#HeroStats.Text", "Здоровье " + st.maxHp() + "   Мана " + st.maxMp()
                + "\nСила " + st.atk() + "   Магия " + st.mag()
                + "\nЗащита " + st.def() + "   Скорость " + st.spd());
        List<String> passives = h.passives();
        cmd.set("#HeroPassives.Text", passives.isEmpty() ? "Пассивных умений пока нет"
                : "Пассивные: " + passives.stream().map(id -> content.passive(id).name()).collect(Collectors.joining(", ")));
        cmd.set("#Known.Text", "В бою: " + h.abilities().stream()
                .map(id -> content.ability(id).name()).collect(Collectors.joining(", ")));
    }

    private void fillGrid(UICommandBuilder cmd, Progression.Hero h) {
        for (int i = 0; i < Grid.SIZE; i++) {
            String g = "#G" + i;
            boolean open = h.unlocked.contains(i);
            boolean avail = !open && h.whyCantUnlock(i) == null;
            cmd.set(g + "Sel.Visible", i == selected);
            cmd.set(g + "Open.Visible", open);
            cmd.set(g + "Avail.Visible", avail);
            cmd.set(g + "Lock.Visible", !open && !avail);
            GridNode n = h.node(i);
            icon(cmd, g + "Icon", BattleMenuPage.iconKey(Describe.nodeIcon(content, h, n)), "m");
        }
        for (int k = 0; k < edges.size(); k++) {
            int[] e = edges.get(k);
            cmd.set("#Edge" + k + ".Visible", h.unlocked.contains(e[0]) && h.unlocked.contains(e[1]));
        }
    }

    private void fillDetail(UICommandBuilder cmd, Progression.Hero h) {
        GridNode n = h.node(selected);
        cmd.set("#SelTitle.Text", Describe.nodeTitle(content, h, n));
        cmd.set("#SelKind.Text", Describe.nodeKind(n) + (selected == 0 ? "" : "   " + Describe.sector(selected)
                + ", кольцо " + Grid.ring(selected)));
        cmd.set("#SelText.Text", Describe.node(content, h, selected));
        icon(cmd, "#SelIcon", BattleMenuPage.iconKey(Describe.nodeIcon(content, h, n)), "m");
        cmd.set("#Learn.Visible", h.whyCantUnlock(selected) == null);
        cmd.set("#Message.Text", message);
    }

    private void icon(UICommandBuilder cmd, String slot, String key, String size) {
        if (!plugin.settings().uiIcons || key == null || !Icons.exists(key)) return;
        String want = key + "_" + size;
        if (want.equals(shownIcons.get(slot))) return;
        shownIcons.put(slot, want);
        cmd.clear(slot);
        cmd.append(slot, BattleMenuPage.ICONS + want + ".ui");
    }
}
