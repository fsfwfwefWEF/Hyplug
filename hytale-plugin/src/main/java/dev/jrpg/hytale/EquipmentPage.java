package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jrpg.battle.Content;
import dev.jrpg.battle.Describe;
import dev.jrpg.battle.Equipment;
import dev.jrpg.battle.Progression;
import dev.jrpg.battle.Stats;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

/**
 * Экипировка (Common/UI/Custom/JrpgBattle/Equipment.ui, собирается tools/gen_battle_ui.py), как в макете:
 * вкладки героев, слева 8 слотов, в центре подходящие вещи из рюкзака, справа разница характеристик
 * (зелёное — лучше, оранжевое — хуже). Надетые вещи уходят из рюкзака, снятые возвращаются. В бою менять нельзя.
 */
public final class EquipmentPage extends InteractiveCustomUIPage<BattleMenuPage.Click> {
    public static final String LAYOUT = "JrpgBattle/Equipment.ui";
    private static final int TABS = 3, SLOTS = 8, ITEMS = 8, CMP = 12;
    private static final String GOOD = "#7be38c", BAD = "#ffa060", SAME = "#d6dde8", GOLD = "#e8c46a";

    private final JrpgPlugin plugin;
    private final Content content;
    private final Progression.Party party;
    private final Map<String, Integer> inventory;
    private int tab;
    private int slot;
    private int pageNo;
    /** Вещь из рюкзака, выбранная для сравнения (null — смотрим надетую). */
    private String picked;
    private String message = "";
    private List<Equipment.Gear> shown = List.of();

    public EquipmentPage(@Nonnull PlayerRef playerRef, JrpgPlugin plugin, String heroId) {
        super(playerRef, CustomPageLifetime.CanDismiss, BattleMenuPage.Click.CODEC);
        this.plugin = plugin;
        this.content = plugin.content();
        this.party = plugin.partyOf(playerRef.getUuid());
        this.inventory = plugin.inventoryOf(playerRef.getUuid());
        List<Progression.Hero> heroes = party.heroes();
        for (int i = 0; heroId != null && i < heroes.size(); i++) if (heroes.get(i).def.id().equals(heroId)) tab = i;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(LAYOUT);
        for (int i = 0; i < TABS; i++) bind(evt, "#Tab" + i, "tab", i);
        for (int i = 0; i < SLOTS; i++) bind(evt, "#Slot" + i + "Btn", "slot", i);
        for (int i = 0; i < ITEMS; i++) bind(evt, "#Item" + i + "Btn", "item", i);
        bind(evt, "#PrevPage", "page", -1);
        bind(evt, "#NextPage", "page", 1);
        bind(evt, "#Equip", "equip", -1);
        bind(evt, "#Unequip", "unequip", -1);
        bind(evt, "#Back", "back", -1);
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
        message = "";
        switch (data.action == null ? "" : data.action) {
            case "tab" -> {
                if (i >= 0 && i < Math.min(TABS, party.heroes().size())) {
                    tab = i;
                    picked = null;
                    pageNo = 0;
                }
            }
            case "slot" -> {
                if (i >= 0 && i < SLOTS) {
                    slot = i;
                    picked = null;
                    pageNo = 0;
                }
            }
            case "item" -> {
                int at = pageNo * ITEMS + i;
                if (i >= 0 && at < shown.size()) picked = shown.get(at).id();
            }
            case "page" -> pageNo += i;
            case "equip" -> change(true);
            case "unequip" -> change(false);
            case "back" -> {
                Pages.open(plugin, playerRef, ref, store, new MainMenuPage(playerRef, plugin));
                return;
            }
            case "close" -> {
                Pages.close(this, playerRef);
                return;
            }
            default -> { }
        }
        UICommandBuilder cmd = new UICommandBuilder();
        fill(cmd);
        Pages.update(this, playerRef, cmd);
    }

    private void change(boolean equip) {
        if (plugin.sessions().containsKey(playerRef.getUuid())) {
            message = "Во время боя снаряжение не меняется.";
            return;
        }
        Progression.Hero h = hero();
        Equipment.Slot s = Equipment.Slot.values()[slot];
        String err;
        String what;
        if (equip) {
            if (picked == null) {
                message = "Выбери вещь в списке.";
                return;
            }
            what = content.gear(picked).name();
            err = Equipment.equip(content, h, s, picked, inventory);
        } else {
            Equipment.Gear old = h.gear(s);
            what = old == null ? "" : old.name();
            err = Equipment.unequip(h, s, inventory);
        }
        if (err != null) {
            message = "Нельзя: " + err;
            return;
        }
        message = (equip ? "Надето: " : "Снято: ") + what;
        picked = null;
        plugin.savePlayer(playerRef.getUuid());
        plugin.followers().redress(playerRef.getUuid());
    }

    private Progression.Hero hero() {
        List<Progression.Hero> heroes = party.heroes();
        return heroes.get(Math.max(0, Math.min(tab, heroes.size() - 1)));
    }

    private void fill(UICommandBuilder cmd) {
        List<Progression.Hero> heroes = party.heroes();
        for (int i = 0; i < TABS; i++) {
            boolean has = i < heroes.size();
            cmd.set("#Tab" + i + ".Visible", has);
            if (has) cmd.set("#Tab" + i + ".Text", (i == tab ? "> " : "") + heroes.get(i).def.name());
        }
        cmd.set("#Hint.Text", "Слот слева, вещь в центре, справа — что изменится. Вещи берутся из рюкзака.");
        Progression.Hero h = hero();
        Equipment.Slot sel = Equipment.Slot.values()[slot];
        cmd.set("#HeroName.Text", h.def.name());
        cmd.set("#HeroSub.Text", h.def.title() + " · уровень " + h.level);
        for (int i = 0; i < SLOTS; i++) {
            Equipment.Slot s = Equipment.Slot.values()[i];
            Equipment.Gear g = h.gear(s);
            cmd.set("#Slot" + i + "Sel.Visible", i == slot);
            cmd.set("#Slot" + i + "Btn.Text", s.label + ": " + (g == null ? "пусто" : g.name()));
        }
        Stats st = h.stats();
        Equipment.Gear w = h.gear(Equipment.Slot.WEAPON);
        cmd.set("#HeroStats.Text", "Здоровье " + st.maxHp() + "  Мана " + st.maxMp() + "  Защита " + st.def()
                + "  Скорость " + st.spd() + "\nСила удара " + Math.round(st.atk() + Equipment.physBonus(w))
                + "  Сила магии " + Math.round(st.mag() + Equipment.magBonus(w)));

        // Центр: подходящие вещи из рюкзака.
        shown = Equipment.candidates(content, inventory, sel);
        int pages = Math.max(1, (shown.size() + ITEMS - 1) / ITEMS);
        pageNo = Math.max(0, Math.min(pageNo, pages - 1));
        if (picked != null && shown.stream().noneMatch(g -> g.id().equals(picked))) picked = null;
        cmd.set("#ListTitle.Text", "Рюкзак: " + sel.label.toLowerCase(java.util.Locale.ROOT));
        cmd.set("#ListSub.Text", shown.isEmpty() ? "" : "Нажми на вещь, чтобы сравнить");
        for (int i = 0; i < ITEMS; i++) {
            int at = pageNo * ITEMS + i;
            boolean has = at < shown.size();
            cmd.set("#Item" + i + ".Visible", has);
            if (!has) continue;
            Equipment.Gear g = shown.get(at);
            int n = inventory.getOrDefault(g.id(), 0);
            cmd.set("#Item" + i + "Sel.Visible", g.id().equals(picked));
            cmd.set("#Item" + i + "Btn.Text", g.name() + (n > 1 ? "  x" + n : ""));
            cmd.set("#Item" + i + "Q.Text", g.quality().label);
            cmd.set("#Item" + i + "Q.Style.TextColor", g.quality().color);
        }
        cmd.set("#Page.Text", pages > 1 ? "Страница " + (pageNo + 1) + " из " + pages : "");
        cmd.set("#PrevPage.Visible", pages > 1);
        cmd.set("#NextPage.Visible", pages > 1);
        cmd.set("#Empty.Text", shown.isEmpty() ? "Подходящих вещей в рюкзаке нет. Снаряжение падает с врагов." : "");

        // Справа: сравнение выбранной вещи с надетой или описание надетой.
        Equipment.Gear cur = h.gear(sel);
        Equipment.Gear show = picked != null ? content.gear(picked) : cur;
        int line = 0;
        if (show == null) {
            cmd.set("#CmpTitle.Text", sel.label + ": пусто");
            cmd.set("#CmpTitle.Style.TextColor", GOLD);
            cmd.set("#CmpSub.Text", "");
            cmd.set("#CmpDesc.Text", "");
        } else {
            cmd.set("#CmpTitle.Text", show.name());
            cmd.set("#CmpTitle.Style.TextColor", show.quality().color);
            cmd.set("#CmpSub.Text", (show.accessory() ? "Аксессуар" : show.slot().label) + " · " + show.quality().label
                    + (picked != null ? "   сравнение с: " + (cur == null ? "пусто" : cur.name()) : "   надето"));
            String desc = show.description();
            cmd.set("#CmpDesc.Text", desc == null ? "" : desc);
        }
        if (picked != null) {
            for (Equipment.Diff d : Equipment.compare(content, h, sel, picked)) {
                if (line >= CMP) break;
                if (d.better() == 0 && d.before().equals(d.after())) continue;
                String text = d.before().isEmpty() ? d.label() + ": " + d.after()
                        : d.after().isEmpty() ? d.label() + ": " + d.before()
                        : d.label() + "  " + d.before() + " → " + d.after() + delta(d);
                setLine(cmd, line++, text, d.better() > 0 ? GOOD : d.better() < 0 ? BAD : GOLD);
            }
            if (line == 0) setLine(cmd, line++, "Характеристики не изменятся", SAME);
        } else if (show != null) {
            for (String l : Describe.gear(content, show).split("\n")) {
                if (line >= CMP) break;
                setLine(cmd, line++, l, line == 1 ? "#9fb0c6" : SAME);
            }
        }
        for (; line < CMP; line++) setLine(cmd, line, "", SAME);

        boolean busy = plugin.sessions().containsKey(playerRef.getUuid());
        cmd.set("#Equip.Visible", picked != null && !busy);
        cmd.set("#Unequip.Visible", cur != null && sel != Equipment.Slot.WEAPON && !busy);
        cmd.set("#Message.Text", busy && message.isEmpty() ? "Идёт бой: менять снаряжение можно после боя." : message);
    }

    private static String delta(Equipment.Diff d) {
        try {
            int v = Integer.parseInt(d.after()) - Integer.parseInt(d.before());
            return v == 0 ? "" : "  (" + (v > 0 ? "+" : "") + v + ")";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static void setLine(UICommandBuilder cmd, int i, String text, String color) {
        cmd.set("#Cmp" + i + ".Text", text);
        cmd.set("#Cmp" + i + ".Style.TextColor", color);
    }
}
