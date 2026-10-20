package dev.jrpg.hytale;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import dev.jrpg.battle.Content;
import dev.jrpg.battle.Equipment;
import dev.jrpg.battle.Gems;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ячейки сетки предметов игры (ItemGrid) для перетаскивания в меню героя. Перетаскивать клиент умеет только
 * настоящие предметы Hytale, поэтому каждой нашей вещи подбирается похожий предмет игры (оружие — тот, что
 * видно в руке, камень — по названию камня, остальное — по словам), а имя и описание в подсказке — наши.
 */
final class DragItems {
    private static final Map<String, String> picked = new ConcurrentHashMap<>();
    private static volatile String fallback;

    private DragItems() {}

    /** Ячейка с предметом для нашей вещи id (null — пустая ячейка). */
    static ItemGridSlot slot(Content content, String id, int count) {
        if (id == null || !content.hasItem(id)) return new ItemGridSlot();
        String hy = hytaleItem(content, id);
        ItemGridSlot s = hy == null ? new ItemGridSlot() : new ItemGridSlot(new ItemStack(hy, Math.max(1, count)));
        dev.jrpg.battle.Item it = content.item(id);
        s.setName(it.name());
        String desc = content.hasGear(id) ? dev.jrpg.battle.Describe.gear(content, content.gear(id)) : it.description();
        s.setDescription(desc == null ? "" : desc);
        s.setActivatable(true);
        return s;
    }

    /** Предмет Hytale для нашей вещи (id предмета игры) или null. */
    static String hytaleItem(Content content, String id) {
        String key = content.hasGear(id) ? content.gear(id).base() : id;
        String r = picked.computeIfAbsent(key, k -> {
            String found = find(content, id);
            JrpgPlugin.get().log("Перетаскивание: «" + content.item(id).name() + "» показан как " + (found == null ? "ничего" : found));
            return found == null ? "" : found;
        });
        return r.isEmpty() ? null : r;
    }

    private static String find(Content content, String id) {
        try {
            if (content.hasGear(id)) {
                Equipment.Gear g = content.gear(id);
                String v = GearVisuals.itemId(g);
                if (v != null) return v;
                if (g.accessory()) return search(g.id().contains("ring") ? List.of("ring") : List.of("amulet", "necklace", "ring"));
                return search(List.of(g.type()));
            }
            Gems.Gem gem = Gems.parse(content, id);
            if (gem != null) return search(List.of(gem.type().id(), "gem", "crystal"));
            String[] words = id.toLowerCase(Locale.ROOT).replace("item_", "").split("_");
            return search(List.of(words));
        } catch (Throwable t) {
            Fx.warnOnce("Не удалось подобрать предмет для перетаскивания", t);
            return null;
        }
    }

    /** Самый короткий id предмета игры, содержащий первое подходящее слово; иначе запасной предмет. */
    private static String search(List<String> words) {
        Map<String, Item> all = Item.getAssetMap().getAssetMap();
        for (String w : words) {
            if (w == null || w.length() < 3) continue;
            String best = null;
            for (String k : all.keySet()) {
                if (!k.toLowerCase(Locale.ROOT).contains(w)) continue;
                if (best == null || k.length() < best.length() || (k.length() == best.length() && k.compareTo(best) < 0)) best = k;
            }
            if (best != null) return best;
        }
        return fallback(all);
    }

    private static String fallback(Map<String, Item> all) {
        if (fallback != null) return fallback;
        String best = null;
        for (String k : all.keySet()) {
            String low = k.toLowerCase(Locale.ROOT);
            if (!(low.contains("ingredient") || low.contains("ore") || low.contains("rock"))) continue;
            if (best == null || k.compareTo(best) < 0) best = k;
        }
        if (best == null) for (String k : all.keySet()) if (best == null || k.compareTo(best) < 0) best = k;
        fallback = best;
        return best;
    }

    static void reset() {
        picked.clear();
        fallback = null;
    }
}
