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
import dev.jrpg.battle.Item;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Рюкзак партии (Common/UI/Custom/JrpgBattle/Backpack.ui, собирается tools/gen_battle_ui.py):
 * сетка ячеек с иконками и количеством, вкладки «Все / Зелья / Боевые / Материалы / Снаряжение», сортировка
 * по типу, имени и количеству, справа описание выбранного предмета и «Выбросить 1».
 */
public final class BackpackPage extends InteractiveCustomUIPage<BattleMenuPage.Click> {
    public static final String LAYOUT = "JrpgBattle/Backpack.ui";
    private static final int CELLS = 24, TABS = 6;
    private static final String[] TAB_NAMES = {"Все", "Зелья", "Боевые", "Материалы", "Снаряжение", "Камни"};
    private static final Item.Kind[] TAB_KINDS = {null, Item.Kind.CONSUMABLE, Item.Kind.BATTLE, Item.Kind.MATERIAL, Item.Kind.GEAR, Item.Kind.GEM};
    private static final String[][] SORTS = {{"#SortKind", "Тип"}, {"#SortName", "Имя"}, {"#SortCount", "Кол-во"}};

    private final JrpgPlugin plugin;
    private final Content content;
    private final Map<String, Integer> inventory;
    private final Map<String, String> shownIcons = new HashMap<>();
    private int tab;
    private Backpack.Sort sort = Backpack.Sort.KIND;
    private int pageNo;
    private String selectedId;
    private String message = "";
    private List<Backpack.Entry> shown = List.of();

    public BackpackPage(@Nonnull PlayerRef playerRef, JrpgPlugin plugin) {
        super(playerRef, CustomPageLifetime.CanDismiss, BattleMenuPage.Click.CODEC);
        this.plugin = plugin;
        this.content = plugin.content();
        this.inventory = plugin.inventoryOf(playerRef.getUuid());
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(LAYOUT);
        shownIcons.clear();
        for (int i = 0; i < TABS; i++) bind(evt, "#Tab" + i, "tab", i);
        for (int i = 0; i < SORTS.length; i++) bind(evt, SORTS[i][0], "sort", i);
        for (int i = 0; i < CELLS; i++) bind(evt, "#S" + i + "Btn", "cell", i);
        bind(evt, "#PrevPage", "page", -1);
        bind(evt, "#NextPage", "page", 1);
        bind(evt, "#Drop", "drop", -1);
        bind(evt, "#Close", "close", -1);
        fill(cmd);
        WindowIntro.play(this, playerRef, plugin, store, cmd, LAYOUT);
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
                if (i >= 0 && i < TABS) {
                    tab = i;
                    pageNo = 0;
                }
            }
            case "sort" -> { if (i >= 0 && i < SORTS.length) sort = Backpack.Sort.values()[i]; }
            case "page" -> pageNo += i;
            case "cell" -> {
                int at = pageNo * CELLS + i;
                if (i >= 0 && at < shown.size()) selectedId = shown.get(at).item().id();
            }
            case "drop" -> drop();
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

    private void drop() {
        if (selectedId == null) return;
        if (plugin.sessions().containsKey(playerRef.getUuid())) {
            message = "Во время боя выбрасывать нельзя.";
            return;
        }
        synchronized (inventory) {
            int n = inventory.getOrDefault(selectedId, 0);
            if (n <= 0) return;
            if (n == 1) inventory.remove(selectedId);
            else inventory.put(selectedId, n - 1);
        }
        message = "Выброшено: " + content.item(selectedId).name();
        plugin.savePlayer(playerRef.getUuid());
    }

    private void fill(UICommandBuilder cmd) {
        shown = Backpack.list(content, inventory, TAB_KINDS[tab], sort);
        int pages = Math.max(1, (shown.size() + CELLS - 1) / CELLS);
        pageNo = Math.max(0, Math.min(pageNo, pages - 1));
        if (selectedId != null && shown.stream().noneMatch(e -> e.item().id().equals(selectedId))) selectedId = null;
        if (selectedId == null && !shown.isEmpty()) selectedId = shown.get(pageNo * CELLS < shown.size() ? pageNo * CELLS : 0).item().id();

        for (int i = 0; i < TABS; i++) cmd.set("#Tab" + i + ".Text", (i == tab ? "> " : "") + TAB_NAMES[i]);
        for (int i = 0; i < SORTS.length; i++) cmd.set(SORTS[i][0] + ".Text", (sort.ordinal() == i ? "> " : "") + SORTS[i][1]);
        cmd.set("#Total.Text", "Предметов: " + Backpack.total(inventory) + "   /jrpg bag — открыть снова");
        cmd.set("#Page.Text", pages > 1 ? "Страница " + (pageNo + 1) + " из " + pages : "");
        cmd.set("#PrevPage.Visible", pages > 1);
        cmd.set("#NextPage.Visible", pages > 1);
        cmd.set("#Empty.Text", shown.isEmpty() ? "Здесь пока пусто. Добыча с врагов попадает сюда." : "");

        for (int i = 0; i < CELLS; i++) {
            int at = pageNo * CELLS + i;
            String s = "#S" + i;
            boolean has = at < shown.size();
            cmd.set(s + ".Visible", has);
            if (!has) continue;
            Backpack.Entry e = shown.get(at);
            cmd.set(s + "Sel.Visible", e.item().id().equals(selectedId));
            cmd.set(s + "Count.Text", e.count() > 1 ? "x" + e.count() : "");
            icon(cmd, s + "Icon", BattleMenuPage.iconKey(Describe.itemIcon(content, e.item())), "g", tier(e.item().id()));
        }

        Backpack.Entry sel = shown.stream().filter(e -> e.item().id().equals(selectedId)).findFirst().orElse(null);
        cmd.set("#Drop.Visible", sel != null);
        if (sel == null) {
            cmd.set("#SelName.Text", "");
            cmd.set("#SelKind.Text", "");
            cmd.set("#SelText.Text", "Выбери предмет слева.");
            icon(cmd, "#SelIcon", "bag", "g", 0);
        } else {
            cmd.set("#SelName.Text", sel.item().name());
            cmd.set("#SelKind.Text", sel.item().kind().label + (sel.item().usable() ? " · можно в бою" : ""));
            cmd.set("#SelText.Text", Describe.item(content, sel.item(), sel.count()));
            icon(cmd, "#SelIcon", BattleMenuPage.iconKey(Describe.itemIcon(content, sel.item())), "g", tier(sel.item().id()));
        }
        cmd.set("#Message.Text", message);
    }

    private void icon(UICommandBuilder cmd, String slot, String key, String size, int tier) {
        if (!plugin.settings().uiIcons) return;
        BattleMenuPage.putIcon(cmd, shownIcons, slot, key, size, tier);
    }

    /** Рамка уровня: у снаряжения — по качеству. */
    private int tier(String itemId) {
        return HeroPage.tier(content, itemId);
    }
}
