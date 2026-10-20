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
import dev.jrpg.battle.Backpack;
import dev.jrpg.battle.Content;
import dev.jrpg.battle.Describe;
import dev.jrpg.battle.Equipment;
import dev.jrpg.battle.Forge;
import dev.jrpg.battle.Gems;
import dev.jrpg.battle.Item;
import dev.jrpg.battle.Progression;
import dev.jrpg.battle.Stats;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Меню героя (Common/UI/Custom/JrpgBattle/Hero.ui, собирается tools/gen_battle_ui.py): кукла с 8 слотами,
 * рюкзак сеткой и карточка вещи. Перетаскивания в окнах мода нет, поэтому «клик-клик»: выбрал вещь в рюкзаке —
 * подсветились слоты, куда её можно надеть; нажал на слот — надета. Выбрал камень — подсветились вещи
 * со свободным гнездом (на кукле и в рюкзаке); нажал — камень вставлен. Достать камень — у кузнеца.
 */
public final class HeroPage extends InteractiveCustomUIPage<BattleMenuPage.Click> {
    public static final String LAYOUT = "JrpgBattle/Hero.ui";
    static final int INFO = 8, SOCKETS = 3;
    private static final int TABS = 3, SLOTS = 8, CELLS = 24, BAG_TABS = 6;
    private static final String[] BAG_TAB_NAMES = {"Все", "Оружие", "Броня", "Аксесс.", "Камни", "Прочее"};
    static final String GOOD = "#7be38c", BAD = "#ffa060", TEXT = "#e9eef6", DIM = "#9fb0c6", GOLD = "#e8c46a";

    private final JrpgPlugin plugin;
    private final Content content;
    private final Progression.Party party;
    private final Map<String, Integer> inventory;
    private final Map<String, String> shownIcons = new HashMap<>();
    private int tab, bagTab, pageNo;
    /** Выбранная вещь в рюкзаке (id) или надетая (слот); не больше одного. */
    private String pickBag;
    private Equipment.Slot pickSlot;
    private String message = "";
    private List<Backpack.Entry> shown = List.of();
    /** Перетаскивание сетками предметов игры (иначе только клики). Флаг игрока «gearclicks» — выключить. */
    private final boolean drag;
    static final String CLICKS_FLAG = "gearclicks";
    /** Слоты куклы в сетках перетаскивания: левая — броня, правая — оружие и аксессуары. */
    private static final Equipment.Slot[] DOLL_L = {Equipment.Slot.HEAD, Equipment.Slot.CHEST, Equipment.Slot.HANDS, Equipment.Slot.LEGS};
    private static final Equipment.Slot[] DOLL_R = {Equipment.Slot.WEAPON, Equipment.Slot.OFFHAND, Equipment.Slot.ACC1, Equipment.Slot.ACC2};
    private static final int CELL = 86, DOLL_MID = 116;
    /** Что показано в сетках сейчас: id вещей и id предметов игры (чтобы понять, откуда тащили). */
    private final String[] bagIds = new String[CELLS], bagHy = new String[CELLS];
    /** Что сейчас нарисовано в сетках (чтобы не слать .Slots без изменений). */
    private String dollKey, bagKey;

    public HeroPage(@Nonnull PlayerRef playerRef, JrpgPlugin plugin, String heroId) {
        super(playerRef, CustomPageLifetime.CanDismiss, BattleMenuPage.Click.CODEC);
        this.plugin = plugin;
        this.content = plugin.content();
        this.party = plugin.partyOf(playerRef.getUuid());
        this.inventory = plugin.inventoryOf(playerRef.getUuid());
        List<Progression.Hero> heroes = party.heroes();
        for (int i = 0; heroId != null && i < heroes.size(); i++) if (heroes.get(i).def.id().equals(heroId)) tab = i;
        this.drag = !plugin.flag(playerRef.getUuid(), CLICKS_FLAG, false);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(LAYOUT);
        shownIcons.clear();
        for (int i = 0; i < TABS; i++) bind(evt, "#Tab" + i, "tab", i);
        for (int i = 0; i < BAG_TABS; i++) bind(evt, "#BTab" + i, "btab", i);
        for (int i = 0; i < SLOTS; i++) bind(evt, "#D" + i + "Btn", "slot", i);
        for (int i = 0; i < CELLS; i++) bind(evt, "#S" + i + "Btn", "cell", i);
        bind(evt, "#PrevPage", "page", -1);
        bind(evt, "#NextPage", "page", 1);
        bind(evt, "#Act", "act", -1);
        bind(evt, "#Cancel", "cancel", -1);
        bind(evt, "#Back", "back", -1);
        bind(evt, "#Close", "close", -1);
        bind(evt, "#Mode", "mode", -1);
        if (drag) bindGrids(evt);
        fill(cmd);
        WindowIntro.play(this, playerRef, plugin, store, cmd, LAYOUT);
    }

    /** Номера секций сеток в Hero.ui (InventorySectionId): по ним Dropped говорит, откуда тащили. */
    static final int SEC_DOLL_L = -1101, SEC_DOLL_R = -1102, SEC_BAG = -1103;

    /**
     * События сеток предметов игры (сами сетки в Hero.ui, у каждой свой номер секции, как в CustomInventory):
     * Dropped — на цель бросили предмет (SourceInventorySectionId/SourceSlotId — откуда), SlotClicking — клик.
     */
    private static void bindGrids(UIEventBuilder evt) {
        for (String g : new String[]{"DollL", "DollR", "BagGrid"}) {
            evt.addEventBinding(CustomUIEventBindingType.Dropped, "#" + g, new EventData().append("Action", "drop").append("Grid", g), false);
            evt.addEventBinding(CustomUIEventBindingType.SlotClicking, "#" + g, new EventData().append("Action", "slotclick").append("Grid", g), false);
        }
    }

    static String gridOfSection(int section) {
        return section == SEC_DOLL_L ? "DollL" : section == SEC_DOLL_R ? "DollR" : section == SEC_BAG ? "BagGrid" : null;
    }

    /** События сеток приходят с полями SlotIndex, SourceSlotId, ItemStackId… — их кодек кнопок не знает, разбираем сами. */
    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, String raw) {
        Map<String, Object> m;
        try {
            m = dev.jrpg.battle.Json.obj(dev.jrpg.battle.Json.parse(raw));
        } catch (Exception e) {
            super.handleDataEvent(ref, store, raw);
            return;
        }
        String action = String.valueOf(m.get("Action"));
        if (!action.equals("drop") && !action.equals("slotclick")) {
            super.handleDataEvent(ref, store, raw);
            return;
        }
        plugin.log("Перетаскивание (" + playerRef.getUsername() + "): " + raw);
        message = "";
        String grid = String.valueOf(m.get("Grid"));
        int slot = num(m.get("SlotIndex"));
        if (action.equals("slotclick")) {
            if (grid.equals("BagGrid")) {
                int at = pageNo * CELLS + slot;
                if (slot >= 0 && at < shown.size()) clickCell(shown.get(at).item().id());
            } else {
                Equipment.Slot s = dollSlot(grid, slot);
                if (s != null) clickSlot(s);
            }
        } else {
            dollKey = bagKey = null; // после броска сетки перерисовать всегда: клиент мог сдвинуть предмет у себя
            dropped(grid, slot, gridOfSection(num(m.get("SourceInventorySectionId"))), num(m.get("SourceSlotId")),
                    m.get("ItemStackId") == null ? null : String.valueOf(m.get("ItemStackId")));
        }
        UICommandBuilder cmd = new UICommandBuilder();
        fill(cmd);
        Pages.update(this, playerRef, cmd);
    }

    private static int num(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return o == null ? -1 : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Equipment.Slot dollSlot(String grid, int i) {
        Equipment.Slot[] a = grid.equals("DollL") ? DOLL_L : grid.equals("DollR") ? DOLL_R : null;
        return a == null || i < 0 || i >= a.length ? null : a[i];
    }

    /**
     * Бросили предмет на ячейку target сетки grid. Откуда — сетка srcGrid (по номеру секции) и ячейка src;
     * если клиент номер секции не прислал — ищем, в какой сетке на этом месте лежит предмет игры itemStackId.
     */
    private void dropped(String grid, int target, String srcGrid, int src, String itemStackId) {
        if (busy()) return;
        Progression.Hero h = hero();
        if (srcGrid != null && !holds(h, srcGrid, src, null)) srcGrid = null;
        if (srcGrid == null) for (String g : new String[]{"BagGrid", "DollL", "DollR"}) {
            if (g.equals(grid) || !holds(h, g, src, itemStackId)) continue;
            srcGrid = g;
            break;
        }
        if (srcGrid == null && holds(h, grid, src, itemStackId)) srcGrid = grid;
        if (srcGrid != null && srcGrid.equals(grid) && src == target) return; // бросили на то же место
        if (srcGrid == null) {
            message = "Не понял, откуда вещь (ячейка " + src + ").";
            return;
        }
        if (srcGrid.equals("BagGrid")) {
            String id = bagIds[src];
            if (grid.equals("BagGrid")) {
                // камень на вещь в рюкзаке — вставить
                String to = target >= 0 && target < CELLS ? bagIds[target] : null;
                if (isGem(id) && to != null && content.hasGear(to)) {
                    Forge.Result r = Forge.insert(content, inventory, Forge.Place.bag(to), id, -1);
                    message = r.error() != null ? "Нельзя: " + r.error() : r.message();
                    if (r.error() == null) saved();
                }
                return;
            }
            Equipment.Slot s = dollSlot(grid, target);
            if (s == null) return;
            pickBag = id;
            pickSlot = null;
            clickSlot(s);
            return;
        }
        Equipment.Slot from = dollSlot(srcGrid, src);
        if (from == null) return;
        if (grid.equals("BagGrid")) {
            pickSlot = from;
            pickBag = null;
            act(); // снять
            return;
        }
        Equipment.Slot to = dollSlot(grid, target);
        if (to != null && to != from && from.name().startsWith("ACC") && to.name().startsWith("ACC")) {
            String a = h.equipped.remove(from), b = h.equipped.remove(to);
            if (a != null) h.equipped.put(to, a);
            if (b != null) h.equipped.put(from, b);
            message = "Аксессуары поменялись местами.";
            saved();
        }
    }

    private boolean holds(Progression.Hero h, String grid, int i, String itemStackId) {
        if (i < 0) return false;
        String id;
        if (grid.equals("BagGrid")) id = i < CELLS ? bagIds[i] : null;
        else {
            Equipment.Slot s = dollSlot(grid, i);
            id = s == null ? null : h.equipped.get(s);
        }
        if (id == null) return false;
        return itemStackId == null || itemStackId.equals(DragItems.hytaleItem(content, id));
    }

    static void bind(UIEventBuilder evt, String selector, String action, int index) {
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
                    pickSlot = null;
                }
            }
            case "btab" -> {
                if (i >= 0 && i < BAG_TABS) {
                    bagTab = i;
                    pageNo = 0;
                }
            }
            case "page" -> pageNo += i;
            case "slot" -> { if (i >= 0 && i < SLOTS) clickSlot(Equipment.Slot.values()[i]); }
            case "cell" -> {
                int at = pageNo * CELLS + i;
                if (i >= 0 && at < shown.size()) clickCell(shown.get(at).item().id());
            }
            case "act" -> act();
            case "mode" -> {
                plugin.setFlag(playerRef.getUuid(), CLICKS_FLAG, drag);
                Pages.open(plugin, playerRef, ref, store, new HeroPage(playerRef, plugin, hero().def.id()));
                return;
            }
            case "cancel" -> {
                pickBag = null;
                pickSlot = null;
            }
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

    private boolean busy() {
        if (!plugin.sessions().containsKey(playerRef.getUuid())) return false;
        message = "Идёт бой: менять снаряжение можно после боя.";
        return true;
    }

    private boolean isGem(String id) {
        return id != null && Gems.parse(content, id) != null;
    }

    private void clickSlot(Equipment.Slot s) {
        Progression.Hero h = hero();
        if (pickBag == null) {
            pickSlot = pickSlot == s ? null : s;
            return;
        }
        if (busy()) return;
        if (isGem(pickBag)) {
            if (h.gear(s) == null) {
                message = "В слоте «" + s.label + "» нет вещи.";
                return;
            }
            done(Forge.insert(content, inventory, Forge.Place.worn(h, s), pickBag, -1), s);
            return;
        }
        if (!content.hasGear(pickBag)) {
            message = "Это не надеть.";
            return;
        }
        String name = content.gear(pickBag).name();
        String err = Equipment.equip(content, h, s, pickBag, inventory);
        if (err != null) {
            message = "Нельзя: " + err;
            return;
        }
        message = "Надето: " + name;
        pickBag = null;
        pickSlot = s;
        saved();
    }

    private void clickCell(String id) {
        // Камень выбран, а нажата вещь со свободным гнездом — вставляем в неё (вещь в рюкзаке).
        if (isGem(pickBag) && !id.equals(pickBag) && content.hasGear(id) && content.gear(id).freeSocket() >= 0) {
            if (busy()) return;
            Forge.Result r = Forge.insert(content, inventory, Forge.Place.bag(id), pickBag, -1);
            if (r.error() != null) message = "Нельзя: " + r.error();
            else {
                message = r.message();
                if (inventory.getOrDefault(pickBag, 0) <= 0) pickBag = r.id();
                saved();
            }
            return;
        }
        pickBag = id.equals(pickBag) ? null : id;
        pickSlot = null;
    }

    private void done(Forge.Result r, Equipment.Slot s) {
        if (r.error() != null) {
            message = "Нельзя: " + r.error();
            return;
        }
        message = r.message();
        if (inventory.getOrDefault(pickBag, 0) <= 0) pickBag = null;
        pickSlot = pickBag == null ? s : null;
        saved();
    }

    /** Кнопка действия: надеть выбранную вещь в первый подходящий слот или снять выбранную надетую. */
    private void act() {
        if (busy()) return;
        Progression.Hero h = hero();
        if (pickSlot != null) {
            Equipment.Gear old = h.gear(pickSlot);
            String err = Equipment.unequip(h, pickSlot, inventory);
            if (err != null) message = "Нельзя: " + err;
            else {
                message = "Снято: " + (old == null ? "" : old.name());
                pickSlot = null;
                saved();
            }
            return;
        }
        if (pickBag != null && content.hasGear(pickBag)) clickSlot(slotFor(h, content.gear(pickBag)));
    }

    /** Куда надеть по кнопке: аксессуар — в свободный из двух, остальное — в свой слот. */
    private static Equipment.Slot slotFor(Progression.Hero h, Equipment.Gear g) {
        if (!g.accessory()) return g.slot();
        return h.equipped.get(Equipment.Slot.ACC1) != null && h.equipped.get(Equipment.Slot.ACC2) == null
                ? Equipment.Slot.ACC2 : Equipment.Slot.ACC1;
    }

    private void saved() {
        plugin.savePlayer(playerRef.getUuid());
        plugin.followers().redress(playerRef.getUuid());
    }

    private Progression.Hero hero() {
        List<Progression.Hero> heroes = party.heroes();
        return heroes.get(Math.max(0, Math.min(tab, heroes.size() - 1)));
    }

    private boolean inTab(Item it) {
        boolean gear = content.hasGear(it.id());
        Equipment.Gear g = gear ? content.gear(it.id()) : null;
        return switch (bagTab) {
            case 1 -> gear && (g.slot() == Equipment.Slot.WEAPON || g.slot() == Equipment.Slot.OFFHAND);
            case 2 -> gear && g.slot().armor();
            case 3 -> gear && g.accessory();
            case 4 -> it.kind() == Item.Kind.GEM;
            case 5 -> !gear && it.kind() != Item.Kind.GEM;
            default -> true;
        };
    }

    private void fill(UICommandBuilder cmd) {
        List<Progression.Hero> heroes = party.heroes();
        Progression.Hero h = hero();
        for (int i = 0; i < TABS; i++) {
            boolean has = i < heroes.size();
            cmd.set("#Tab" + i + ".Visible", has);
            if (has) cmd.set("#Tab" + i + ".Text", (i == tab ? "> " : "") + heroes.get(i).def.name());
        }
        for (int i = 0; i < BAG_TABS; i++) cmd.set("#BTab" + i + ".Text", (i == bagTab ? ">" : "") + BAG_TAB_NAMES[i]);
        int[] stock = Forge.stock(content, inventory);
        cmd.set("#Total.Text", "Предметов: " + Backpack.total(inventory) + "   материалов " + stock[0] + ", камней " + stock[1]);

        if (pickBag != null && inventory.getOrDefault(pickBag, 0) <= 0) pickBag = null;
        boolean gemPicked = isGem(pickBag);
        Equipment.Gear gearPicked = pickBag != null && content.hasGear(pickBag) ? content.gear(pickBag) : null;
        cmd.set("#Mode.Text", drag ? "Режим: перетаскивание" : "Режим: клики");
        cmd.set("#Hint.Text", drag && pickBag == null && pickSlot == null
                ? "Перетащи вещь из рюкзака на куклу (или обратно). Камень — на вещь. Клики тоже работают." : gemPicked ? "Нажми на подсвеченную вещь с пустым гнездом — камень встанет в неё."
                : gearPicked != null ? "Нажми на подсвеченный слот куклы (или «Надеть»)."
                : "Нажми на вещь в рюкзаке, потом на слот куклы. Камень — на вещь с гнездом.");

        // Кукла
        cmd.set("#HeroName.Text", h.def.name());
        cmd.set("#HeroSub.Text", h.def.title());
        cmd.set("#DollLvl.Text", "Уровень " + h.level);
        icon(cmd, "#Portrait", BattleMenuPage.iconKey("portrait:" + h.def.id()), "l", 0);
        Stats st = h.stats();
        Equipment.Gear w = h.gear(Equipment.Slot.WEAPON);
        cmd.set("#DollStats.Text", "Здоровье " + st.maxHp() + "\nМана " + st.maxMp() + "\nАтака " + st.atk() + "\nМагия "
                + st.mag() + "\nЗащита " + st.def() + "\nСкорость " + st.spd());
        cmd.set("#HeroStats.Text", "Сила удара " + Math.round(st.atk() + Equipment.physBonus(w)) + "   Сила магии "
                + Math.round(st.mag() + Equipment.magBonus(w)) + (w != null && w.element() != null
                ? "\nСтихия удара: " + dev.jrpg.battle.Element.name(w.element()) : ""));
        for (int i = 0; i < SLOTS; i++) {
            Equipment.Slot s = Equipment.Slot.values()[i];
            Equipment.Gear g = h.gear(s);
            String d = "#D" + i;
            cmd.set(d + "Name.Text", g == null ? s.label : "");
            cmd.set(d + "Sel.Visible", s == pickSlot);
            boolean glow = gearPicked != null ? gearPicked.fits(s) : gemPicked && g != null && g.freeSocket() >= 0;
            cmd.set(d + "Glow.Visible", glow);
            cmd.set(d + "Count.Text", g != null && g.sockets() > 0 ? g.gemCount() + "/" + g.sockets() : "");
            icon(cmd, d + "Icon", g == null || drag ? "" : BattleMenuPage.iconKey(Describe.gearIcon(g)), "g", g == null || drag ? 0 : Describe.gearTier(g));
        }
        for (String g : new String[]{"#DollL", "#DollR", "#BagGrid"}) cmd.set(g + ".Visible", drag);
        if (drag) {
            // сетку перерисовываем, только когда в ней что-то поменялось: иначе клик сбивает начатое перетаскивание
            StringBuilder key = new StringBuilder();
            for (Equipment.Slot s : DOLL_L) key.append(h.equipped.get(s)).append('|');
            for (Equipment.Slot s : DOLL_R) key.append(h.equipped.get(s)).append('|');
            if (!key.toString().equals(dollKey)) {
                dollKey = key.toString();
                List<com.hypixel.hytale.server.core.ui.ItemGridSlot> l = new ArrayList<>(), r = new ArrayList<>();
                for (Equipment.Slot s : DOLL_L) l.add(DragItems.slot(content, h.equipped.get(s), 1));
                for (Equipment.Slot s : DOLL_R) r.add(DragItems.slot(content, h.equipped.get(s), 1));
                cmd.set("#DollL.Slots", l);
                cmd.set("#DollR.Slots", r);
            }
        }

        // Рюкзак
        List<Backpack.Entry> all = Backpack.list(content, inventory, null, Backpack.Sort.KIND);
        List<Backpack.Entry> list = new ArrayList<>();
        for (Backpack.Entry e : all) if (inTab(e.item())) list.add(e);
        shown = list;
        int pages = Math.max(1, (shown.size() + CELLS - 1) / CELLS);
        pageNo = Math.max(0, Math.min(pageNo, pages - 1));
        cmd.set("#Page.Text", pages > 1 ? "Страница " + (pageNo + 1) + " из " + pages : shown.isEmpty() ? "Пусто" : "");
        cmd.set("#PrevPage.Visible", pages > 1);
        cmd.set("#NextPage.Visible", pages > 1);
        List<com.hypixel.hytale.server.core.ui.ItemGridSlot> bagSlots = new ArrayList<>();
        StringBuilder bagKey = new StringBuilder();
        for (int i = 0; i < CELLS; i++) {
            int at = pageNo * CELLS + i;
            String s = "#S" + i;
            boolean has = at < shown.size();
            bagIds[i] = has ? shown.get(at).item().id() : null;
            bagHy[i] = has && drag ? DragItems.hytaleItem(content, bagIds[i]) : null;
            if (drag) {
                int n = has ? shown.get(at).count() : 0;
                bagSlots.add(DragItems.slot(content, bagIds[i], n));
                bagKey.append(bagIds[i]).append('x').append(n).append('|');
            }
            // в режиме перетаскивания ячейки — только подложка (видна всегда, предметы рисует сетка)
            cmd.set(s + ".Visible", has || drag);
            if (!has) {
                if (drag) {
                    cmd.set(s + "Sel.Visible", false);
                    cmd.set(s + "Glow.Visible", false);
                    cmd.set(s + "Count.Text", "");
                    icon(cmd, s + "Icon", "", "g", 0);
                }
                continue;
            }
            Backpack.Entry e = shown.get(at);
            String id = e.item().id();
            cmd.set(s + "Sel.Visible", id.equals(pickBag));
            cmd.set(s + "Glow.Visible", gemPicked && content.hasGear(id) && content.gear(id).freeSocket() >= 0);
            cmd.set(s + "Count.Text", drag ? "" : e.count() > 1 ? "x" + e.count() : "");
            icon(cmd, s + "Icon", drag ? "" : BattleMenuPage.iconKey(Describe.itemIcon(content, e.item())), "g", drag ? 0 : tier(content, id));
        }
        if (drag && !bagKey.toString().equals(this.bagKey)) {
            this.bagKey = bagKey.toString();
            cmd.set("#BagGrid.Slots", bagSlots);
        }

        // Карточка
        String cardId = pickBag != null ? pickBag : pickSlot != null ? h.equipped.get(pickSlot) : null;
        List<String[]> extra = new ArrayList<>();
        if (gearPicked != null) {
            Equipment.Slot to = slotFor(h, gearPicked);
            for (Equipment.Diff d : Equipment.compare(content, h, to, pickBag)) {
                if (d.better() == 0 && d.before().equals(d.after())) continue;
                String c = d.better() > 0 ? GOOD : d.better() < 0 ? BAD : GOLD;
                extra.add(new String[]{d.before().isEmpty() || d.after().isEmpty()
                        ? (d.better() > 0 ? "+ " : "− ") + d.label() + ": " + (d.after().isEmpty() ? d.before() : d.after())
                        : d.label() + ": " + d.before() + " → " + d.after(), c});
            }
            if (extra.isEmpty()) extra.add(new String[]{"Характеристики не изменятся", DIM});
            extra.add(0, new String[]{"Если надеть (" + to.label.toLowerCase(java.util.Locale.ROOT) + "):", GOLD});
        }
        card(plugin, content, cmd, shownIcons, cardId, inventory.getOrDefault(cardId, 0), extra, false);
        boolean busy = plugin.sessions().containsKey(playerRef.getUuid());
        cmd.set("#Act.Visible", !busy && (pickSlot != null && h.gear(pickSlot) != null && pickSlot != Equipment.Slot.WEAPON
                || gearPicked != null));
        cmd.set("#Act.Text", pickSlot != null ? "Снять" : "Надеть");
        cmd.set("#Cancel.Visible", pickBag != null || pickSlot != null);
        cmd.set("#Message.Text", busy && message.isEmpty() ? "Идёт бой: менять снаряжение можно после боя." : message);
    }

    private void icon(UICommandBuilder cmd, String slot, String key, String size, int tier) {
        if (!plugin.settings().uiIcons) return;
        BattleMenuPage.putIcon(cmd, shownIcons, slot, key, size, tier);
    }

    /** Рамка уровня: снаряжение — по качеству, камень — по уровню. */
    static int tier(Content content, String id) {
        if (id == null) return 0;
        if (content.hasGear(id)) return Describe.gearTier(content.gear(id));
        Gems.Gem g = Gems.parse(content, id);
        if (g == null) return 0;
        return switch (g.tier().level()) {
            case 1 -> 0;
            case 2 -> 1;
            case 3 -> 2;
            default -> 3;
        };
    }

    /**
     * Карточка вещи (общая для меню героя и кузницы): иконка, имя цветом качества, вид, строки описания #Info0..7
     * (сначала extra — сравнение, потом описание), гнёзда #Sock0..2. extract — кузница: у камней кнопки «Достать».
     */
    static void card(JrpgPlugin plugin, Content content, UICommandBuilder cmd, Map<String, String> shownIcons, String id,
                     int count, List<String[]> extra, boolean extract) {
        boolean icons = plugin.settings().uiIcons;
        List<String[]> lines = new ArrayList<>(extra);
        Equipment.Gear g = id != null && content.hasGear(id) ? content.gear(id) : null;
        if (id == null || !content.hasItem(id)) {
            cmd.set("#SelName.Text", "");
            cmd.set("#SelKind.Text", "Ничего не выбрано");
            if (icons) BattleMenuPage.putIcon(cmd, shownIcons, "#SelIcon", "bag", "g", 0);
        } else {
            Item it = content.item(id);
            cmd.set("#SelName.Text", it.name());
            cmd.set("#SelName.Style.TextColor", g != null ? g.quality().color : it.kind() == Item.Kind.GEM
                    ? Gems.parse(content, id).type().color() : GOLD);
            cmd.set("#SelKind.Text", g != null ? (g.accessory() ? "Аксессуар" : g.slot().label) + " · " + g.quality().label
                    + (g.sockets() > 0 ? " · гнёзд " + g.sockets() : " · без гнёзд") + (count > 0 ? " · в рюкзаке " + count : " · надето")
                    : it.kind().label + (count > 0 ? " · в рюкзаке " + count : ""));
            if (icons) BattleMenuPage.putIcon(cmd, shownIcons, "#SelIcon", BattleMenuPage.iconKey(Describe.itemIcon(content, it)),
                    "g", tier(content, id));
            String text;
            if (g != null) {
                StringBuilder sb = new StringBuilder();
                for (String l : Describe.gear(content, g).split("\n")) {
                    if (l.startsWith("Гнездо")) continue;
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(l);
                }
                if (g.description() != null && !g.description().isEmpty()) sb.append("\n").append(g.description());
                text = sb.toString();
            } else {
                String flavor = content.flavor(id);
                text = flavor.isEmpty() ? it.description() : flavor;
                if (it.effect() != null) text += "\nВ бою: " + Describe.ability(content, it.effect());
            }
            for (String l : wrap(text, 46)) lines.add(new String[]{l, lines.size() == extra.size() ? DIM : TEXT});
        }
        for (int i = 0; i < INFO; i++) {
            boolean has = i < lines.size();
            cmd.set("#Info" + i + ".Text", has ? lines.get(i)[0] : "");
            if (has) cmd.set("#Info" + i + ".Style.TextColor", lines.get(i)[1]);
        }
        for (int i = 0; i < SOCKETS; i++) {
            boolean has = g != null && i < g.sockets();
            cmd.set("#Sock" + i + "Box.Visible", has);
            if (!has) continue;
            String gid = g.gem(i);
            Gems.Gem gem = gid == null ? null : Gems.parse(content, gid);
            cmd.set("#Sock" + i + ".Text", gem == null ? "Пустое гнездо " + (i + 1)
                    : gem.name() + ": " + Gems.text(content, gem.in(Forge.slotOf(g))));
            cmd.set("#Sock" + i + ".Style.TextColor", gem == null ? DIM : gem.type().color());
            if (icons) BattleMenuPage.putIcon(cmd, shownIcons, "#Sock" + i + "Icon", gem == null ? "" : BattleMenuPage.iconKey(gem.type().icon()),
                    "r", gem == null ? 0 : tier(content, gid));
            if (extract) cmd.set("#Ext" + i + ".Visible", gem != null);
        }
    }

    /** Перенос по словам (Label обрезает длинные строки). */
    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String para : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
            if (line.length() > 0) out.add(line.toString());
        }
        return out;
    }
}
