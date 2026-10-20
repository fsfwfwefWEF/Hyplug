package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
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

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Кузница (Common/UI/Custom/JrpgBattle/Smith.ui): слева все вещи (надетые на героев и из рюкзака), в центре
 * карточка с гнёздами («Достать» — камень вернётся в рюкзак целым) и улучшение качества за материалы и камни,
 * справа камни из рюкзака: «Вставить в вещь» и «Слить 3 в 1».
 */
public final class SmithPage extends InteractiveCustomUIPage<BattleMenuPage.Click> {
    public static final String LAYOUT = "JrpgBattle/Smith.ui";
    private static final int ROWS = 9, GEMS = 8;

    /** Вещь в списке: где лежит. */
    private record Entry(Forge.Place place, Equipment.Gear gear, String owner, int count) {}

    private final JrpgPlugin plugin;
    private final Content content;
    private final Progression.Party party;
    private final Map<String, Integer> inventory;
    private final Map<String, String> shownIcons = new HashMap<>();
    private int listPage, gemPage;
    /** Выбранная вещь: герой (или null — рюкзак), слот, id. */
    private String pickHero;
    private Equipment.Slot pickSlot;
    private String pickId;
    private String pickGem;
    private String message = "";
    private List<Entry> items = List.of();
    private List<Backpack.Entry> gems = List.of();

    public SmithPage(@Nonnull PlayerRef playerRef, JrpgPlugin plugin) {
        super(playerRef, CustomPageLifetime.CanDismiss, BattleMenuPage.Click.CODEC);
        this.plugin = plugin;
        this.content = plugin.content();
        this.party = plugin.partyOf(playerRef.getUuid());
        this.inventory = plugin.inventoryOf(playerRef.getUuid());
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(LAYOUT);
        shownIcons.clear();
        for (int i = 0; i < ROWS; i++) HeroPage.bind(evt, "#L" + i + "Btn", "item", i);
        for (int i = 0; i < GEMS; i++) HeroPage.bind(evt, "#G" + i + "Btn", "gem", i);
        for (int i = 0; i < HeroPage.SOCKETS; i++) HeroPage.bind(evt, "#Ext" + i, "extract", i);
        HeroPage.bind(evt, "#LPrev", "lpage", -1);
        HeroPage.bind(evt, "#LNext", "lpage", 1);
        HeroPage.bind(evt, "#GPrev", "gpage", -1);
        HeroPage.bind(evt, "#GNext", "gpage", 1);
        HeroPage.bind(evt, "#Upgrade", "upgrade", -1);
        HeroPage.bind(evt, "#Insert", "insert", -1);
        HeroPage.bind(evt, "#Merge", "merge", -1);
        HeroPage.bind(evt, "#Close", "close", -1);
        fill(cmd);
        WindowIntro.play(this, playerRef, plugin, store, cmd, LAYOUT);
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
            case "item" -> {
                int at = listPage * ROWS + i;
                if (i >= 0 && at < items.size()) {
                    Entry e = items.get(at);
                    pickHero = e.place().hero() == null ? null : e.place().hero().def.id();
                    pickSlot = e.place().slot();
                    pickId = e.gear().id();
                }
            }
            case "gem" -> {
                int at = gemPage * GEMS + i;
                if (i >= 0 && at < gems.size()) pickGem = gems.get(at).item().id();
            }
            case "lpage" -> listPage += i;
            case "gpage" -> gemPage += i;
            case "extract" -> { if (i >= 0) run(place() == null ? null : Forge.extract(content, inventory, place(), i)); }
            case "upgrade" -> run(place() == null ? null : Forge.upgrade(content, inventory, place()));
            case "insert" -> {
                if (pickGem == null) message = "Выбери камень справа.";
                else run(place() == null ? null : Forge.insert(content, inventory, place(), pickGem, -1));
            }
            case "merge" -> {
                if (pickGem == null) message = "Выбери камень справа.";
                else {
                    Forge.Result r = Forge.merge(content, inventory, pickGem);
                    message = r.error() != null ? "Нельзя: " + r.error() : r.message();
                    if (r.error() == null) {
                        if (inventory.getOrDefault(pickGem, 0) <= 0) pickGem = r.id();
                        plugin.savePlayer(playerRef.getUuid());
                    }
                }
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

    /** Где сейчас выбранная вещь (null — не выбрана или пропала). */
    private Forge.Place place() {
        if (pickId == null) return null;
        if (pickHero != null) {
            for (Progression.Hero h : party.heroes()) {
                if (h.def.id().equals(pickHero) && pickId.equals(h.equipped.get(pickSlot))) return Forge.Place.worn(h, pickSlot);
            }
            return null;
        }
        return inventory.getOrDefault(pickId, 0) > 0 ? Forge.Place.bag(pickId) : null;
    }

    private void run(Forge.Result r) {
        if (plugin.sessions().containsKey(playerRef.getUuid())) {
            message = "Во время боя кузница закрыта.";
            return;
        }
        if (r == null) {
            message = "Выбери вещь слева.";
            return;
        }
        if (r.error() != null) {
            message = "Нельзя: " + r.error();
            return;
        }
        message = r.message();
        pickId = r.id();
        if (pickGem != null && inventory.getOrDefault(pickGem, 0) <= 0) pickGem = null;
        plugin.savePlayer(playerRef.getUuid());
        if (pickHero != null) plugin.followers().redress(playerRef.getUuid());
    }

    private void fill(UICommandBuilder cmd) {
        // Список вещей: сначала надетые (по героям), потом рюкзак.
        List<Entry> list = new ArrayList<>();
        for (Progression.Hero h : party.heroes()) {
            for (Equipment.Slot s : Equipment.Slot.values()) {
                Equipment.Gear g = h.gear(s);
                if (g != null) list.add(new Entry(Forge.Place.worn(h, s), g, h.def.name(), 0));
            }
        }
        for (Backpack.Entry e : Backpack.list(content, inventory, Item.Kind.GEAR, Backpack.Sort.KIND)) {
            if (content.hasGear(e.item().id())) list.add(new Entry(Forge.Place.bag(e.item().id()), content.gear(e.item().id()), null, e.count()));
        }
        items = list;
        Forge.Place cur = place();
        if (cur == null) pickId = null;
        int lpages = Math.max(1, (items.size() + ROWS - 1) / ROWS);
        listPage = Math.max(0, Math.min(listPage, lpages - 1));
        for (int i = 0; i < ROWS; i++) {
            int at = listPage * ROWS + i;
            String r = "#L" + i;
            boolean has = at < items.size();
            cmd.set(r + ".Visible", has);
            if (!has) continue;
            Entry e = items.get(at);
            Equipment.Gear g = e.gear();
            boolean sel = g.id().equals(pickId) && (e.place().hero() == null ? pickHero == null
                    : e.place().hero().def.id().equals(pickHero) && e.place().slot() == pickSlot);
            cmd.set(r + "Sel.Visible", sel);
            cmd.set(r + "Btn.Text", g.name() + (e.count() > 1 ? " x" + e.count() : ""));
            cmd.set(r + "Q.Text", e.owner() != null ? e.owner() : "рюкзак");
            cmd.set(r + "Q.Style.TextColor", g.quality().color);
            icon(cmd, r + "Icon", BattleMenuPage.iconKey(Describe.gearIcon(g)), "r", Describe.gearTier(g));
        }
        cmd.set("#LPage.Text", lpages > 1 ? (listPage + 1) + " / " + lpages : "");
        cmd.set("#LPrev.Visible", lpages > 1);
        cmd.set("#LNext.Visible", lpages > 1);

        // Камни
        gems = Backpack.list(content, inventory, Item.Kind.GEM, Backpack.Sort.NAME);
        if (pickGem != null && inventory.getOrDefault(pickGem, 0) <= 0) pickGem = null;
        int gpages = Math.max(1, (gems.size() + GEMS - 1) / GEMS);
        gemPage = Math.max(0, Math.min(gemPage, gpages - 1));
        for (int i = 0; i < GEMS; i++) {
            int at = gemPage * GEMS + i;
            String r = "#G" + i;
            boolean has = at < gems.size();
            cmd.set(r + ".Visible", has);
            if (!has) continue;
            Backpack.Entry e = gems.get(at);
            Gems.Gem gem = Gems.parse(content, e.item().id());
            cmd.set(r + "Sel.Visible", e.item().id().equals(pickGem));
            cmd.set(r + "Btn.Text", e.item().name());
            cmd.set(r + "Q.Text", "x" + e.count());
            cmd.set(r + "Q.Style.TextColor", gem == null ? HeroPage.DIM : gem.type().color());
            icon(cmd, r + "Icon", BattleMenuPage.iconKey(Describe.itemIcon(content, e.item())), "r", HeroPage.tier(content, e.item().id()));
        }
        cmd.set("#GPage.Text", gems.isEmpty() ? "Камней нет. Они падают с сильных групп (Элита) и боссов." : gpages > 1 ? (gemPage + 1) + " / " + gpages : "");
        cmd.set("#GPrev.Visible", gpages > 1);
        cmd.set("#GNext.Visible", gpages > 1);
        cmd.set("#Insert.Visible", pickGem != null && pickId != null);
        cmd.set("#Merge.Visible", pickGem != null);

        // Карточка и улучшение
        List<String[]> extra = new ArrayList<>();
        if (pickId != null && pickHero != null) {
            for (Progression.Hero h : party.heroes()) if (h.def.id().equals(pickHero)) extra.add(new String[]{"Надето: " + h.def.name(), HeroPage.GOLD});
        }
        HeroPage.card(plugin, content, cmd, shownIcons, pickId, pickHero == null && pickId != null ? inventory.getOrDefault(pickId, 0) : 0, extra, true);
        int[] stock = Forge.stock(content, inventory);
        cmd.set("#Total.Text", "Материалов: " + stock[0] + "   камней: " + stock[1]);
        cmd.set("#Hint.Text", "Выбери вещь слева. Камень справа → «Вставить». «Достать» возвращает камень в рюкзак целым.");
        Equipment.Gear g = pickId == null ? null : content.gear(pickId);
        Equipment.Cost cost = g == null ? null : Forge.upgradeCost(content, g);
        if (g == null) cmd.set("#UpCost.Text", "");
        else if (cost == null) cmd.set("#UpCost.Text", "Легендарное — лучше не бывает.");
        else {
            Equipment.Quality next = g.quality().next();
            List<String> newSkills = Equipment.qualitySkills(content, content.gear(g.base()), next);
            newSkills.removeAll(g.skills());
            List<String> names = new ArrayList<>();
            for (String s : newSkills) names.add(content.ability(s).name());
            cmd.set("#UpCost.Text", "До «" + next.label + "»: материалов " + cost.materials() + " (есть " + stock[0] + ")"
                    + (cost.gems() > 0 ? ", камней " + cost.gems() + " (есть " + stock[1] + ")" : "")
                    + ". Гнёзд станет " + next.sockets + (names.isEmpty() ? "" : ", навык: " + String.join(", ", names)) + ".");
        }
        boolean busy = plugin.sessions().containsKey(playerRef.getUuid());
        cmd.set("#Upgrade.Visible", cost != null && !busy);
        cmd.set("#Message.Text", busy && message.isEmpty() ? "Во время боя кузница закрыта." : message);
    }

    private void icon(UICommandBuilder cmd, String slot, String key, String size, int tier) {
        if (!plugin.settings().uiIcons) return;
        BattleMenuPage.putIcon(cmd, shownIcons, slot, key, size, tier);
    }
}
