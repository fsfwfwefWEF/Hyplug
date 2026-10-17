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
import dev.jrpg.battle.Ability;
import dev.jrpg.battle.Content;
import dev.jrpg.battle.Describe;
import dev.jrpg.battle.Progression;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Меню прокачки партии (тестовое): Common/UI/Custom/JrpgBattle/Progression.ui.
 * Вкладки героев (#Tab0..2), характеристики с кнопками «+» (#StatPlus0..5),
 * дерево навыков (#Node0..3 с кнопками #Learn0..3), тестовые кнопки «+100 опыта» и «Сбросить очки».
 */
public final class ProgressionPage extends InteractiveCustomUIPage<BattleMenuPage.Click> {
    public static final String LAYOUT = "JrpgBattle/Progression.ui";
    private static final int TABS = 3, STATS = 6, NODES = 4, EXP_BAR = 370;

    private final JrpgPlugin plugin;
    private final Progression.Party party;
    private final Content content;
    private int tab;
    private String message = "";
    private final java.util.Map<String, String> shownIcons = new java.util.HashMap<>();

    public ProgressionPage(@Nonnull PlayerRef playerRef, JrpgPlugin plugin, Progression.Party party, String heroId) {
        super(playerRef, CustomPageLifetime.CanDismiss, BattleMenuPage.Click.CODEC);
        this.plugin = plugin;
        this.party = party;
        this.content = plugin.content();
        List<Progression.Hero> heroes = party.heroes();
        for (int i = 0; i < heroes.size(); i++) if (heroes.get(i).def.id().equals(heroId)) tab = i;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(LAYOUT);
        shownIcons.clear();
        for (int i = 0; i < TABS; i++) bind(evt, "#Tab" + i, "tab", i);
        for (int i = 0; i < STATS; i++) bind(evt, "#StatPlus" + i, "stat", i);
        for (int i = 0; i < NODES; i++) bind(evt, "#Learn" + i, "learn", i);
        bind(evt, "#AddExp", "exp", 100);
        bind(evt, "#Respec", "respec", -1);
        bind(evt, "#Close", "close", -1);
        fill(cmd);
    }

    private static void bind(UIEventBuilder evt, String selector, String action, int index) {
        evt.addEventBinding(CustomUIEventBindingType.Activating, selector,
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
        String a = data.action == null ? "" : data.action;
        switch (a) {
            case "tab" -> {
                if (i >= 0 && i < party.heroes().size()) tab = i;
                message = "";
            }
            case "stat" -> {
                if (i >= 0 && i < Progression.Stat.values().length) {
                    Progression.Stat s = Progression.Stat.values()[i];
                    String err = h.invest(s);
                    message = err != null ? err : s.label + " +" + s.perPoint;
                }
            }
            case "learn" -> {
                List<Progression.SkillNode> tree = h.def.tree();
                if (i >= 0 && i < tree.size()) {
                    Progression.SkillNode n = tree.get(i);
                    String err = h.learn(n);
                    message = err != null ? "Нельзя: " + err : "Изучено: " + content.ability(n.abilityId()).name();
                }
            }
            case "exp" -> {
                List<String> ups = party.addExp(i * party.heroes().size());
                message = ups.isEmpty() ? "+" + i + " опыта каждому" : String.join(" ", ups);
            }
            case "respec" -> {
                h.respec();
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

    private Progression.Hero hero() {
        List<Progression.Hero> heroes = party.heroes();
        return heroes.get(Math.max(0, Math.min(tab, heroes.size() - 1)));
    }

    private void fill(UICommandBuilder cmd) {
        List<Progression.Hero> heroes = party.heroes();
        for (int i = 0; i < TABS; i++) {
            boolean shown = i < heroes.size();
            cmd.set("#Tab" + i + ".Visible", shown);
            if (shown) {
                Progression.Hero h = heroes.get(i);
                cmd.set("#Tab" + i + ".Text", (i == tab ? "> " : "") + h.def.name() + "  ур. " + h.level);
            }
        }
        Progression.Hero h = hero();
        var st = h.stats();
        cmd.set("#HeroName.Text", h.def.name());
        cmd.set("#HeroTitle.Text", h.def.title());
        cmd.set("#HeroLevel.Text", "Уровень " + h.level + "\nОпыт " + h.exp + " / " + Progression.expToNext(h.level));
        cmd.set("#Points.Text", "Очки характеристик: " + h.statPoints + "\nОчки навыков: " + h.skillPoints);
        icon(cmd, "#HeroIcon", "pt_" + h.def.id(), "l");
        if (plugin.settings().uiBars) {
            Anchor a = new Anchor();
            a.setWidth(Value.of((int) Math.round(EXP_BAR * Math.min(1.0, h.exp / (double) Progression.expToNext(h.level)))));
            a.setHeight(Value.of(8));
            cmd.setObject("#ExpFill.Anchor", a);
        } else {
            cmd.set("#ExpBack.Visible", false);
        }

        int[] values = {st.maxHp(), st.maxMp(), st.atk(), st.mag(), st.def(), st.spd()};
        Progression.Stat[] stats = Progression.Stat.values();
        for (int i = 0; i < STATS; i++) {
            Progression.Stat s = stats[i];
            int inv = h.invested.get(s);
            cmd.set("#Stat" + i + ".Text", s.label + ": " + values[i] + (inv > 0 ? "   (вложено " + inv + ")" : "")
                    + "   +" + s.perPoint + " за очко");
            cmd.set("#StatPlus" + i + ".Visible", h.statPoints > 0);
        }

        List<Progression.SkillNode> tree = h.def.tree();
        for (int i = 0; i < NODES; i++) {
            boolean shown = i < tree.size();
            cmd.set("#Node" + i + ".Visible", shown);
            if (!shown) continue;
            Progression.SkillNode n = tree.get(i);
            Ability ab = content.ability(n.abilityId());
            boolean learned = h.learned.contains(n.abilityId());
            String why = h.whyCantLearn(n);
            cmd.set("#Node" + i + "Name.Text", ab.name() + (learned ? "  (изучено)" : ""));
            cmd.set("#Node" + i + "Text.Text", Describe.ability(content, ab).replace("\n", ".  "));
            cmd.set("#Node" + i + "State.Text", learned ? "Уже в бою" : why == null ? "Стоит " + n.cost() + " очк." : why);
            cmd.set("#Learn" + i + ".Visible", !learned && why == null);
            icon(cmd, "#Node" + i + "Icon", BattleMenuPage.iconKey(Describe.abilityIcon(content, ab)), "m");
        }
        cmd.set("#Known.Text", "В бою: " + h.abilities().stream()
                .map(id -> content.ability(id).name()).collect(Collectors.joining(", ")));
        cmd.set("#Message.Text", message);
    }

    private void icon(UICommandBuilder cmd, String slot, String key, String size) {
        if (!plugin.settings().uiIcons || !Icons.exists(key)) return;
        String want = key + "_" + size;
        if (want.equals(shownIcons.get(slot))) return;
        shownIcons.put(slot, want);
        cmd.clear(slot);
        cmd.append(slot, BattleMenuPage.ICONS + want + ".ui");
    }
}
