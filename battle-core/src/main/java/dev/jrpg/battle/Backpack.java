package dev.jrpg.battle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Содержимое рюкзака для окна: фильтр по виду предмета и сортировка. */
public final class Backpack {
    public enum Sort { KIND, NAME, COUNT }

    public record Entry(Item item, int count) {}

    private Backpack() {}

    /** filter == null — все предметы. Неизвестные моду предметы и нулевые количества пропускаются. */
    public static List<Entry> list(Content content, Map<String, Integer> inventory, Item.Kind filter, Sort sort) {
        List<Entry> out = new ArrayList<>();
        synchronized (inventory) {
            inventory.forEach((id, n) -> {
                if (n == null || n <= 0 || !content.hasItem(id)) return;
                Item item = content.item(id);
                if (filter == null || item.kind() == filter) out.add(new Entry(item, n));
            });
        }
        Comparator<Entry> byName = Comparator.comparing(e -> e.item().name());
        out.sort(switch (sort) {
            case KIND -> Comparator.<Entry, Integer>comparing(e -> e.item().kind().ordinal()).thenComparing(byName);
            case NAME -> byName;
            case COUNT -> Comparator.<Entry, Integer>comparing(e -> -e.count()).thenComparing(byName);
        });
        return out;
    }

    public static int total(Map<String, Integer> inventory) {
        synchronized (inventory) {
            return inventory.values().stream().mapToInt(n -> n == null ? 0 : Math.max(0, n)).sum();
        }
    }
}
