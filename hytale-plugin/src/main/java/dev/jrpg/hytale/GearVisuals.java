package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.util.InventoryHelper;
import dev.jrpg.battle.Content;
import dev.jrpg.battle.Equipment;
import dev.jrpg.battle.Progression;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Снаряжение на моделях: оружие в руке, щит во второй руке и броня в слотах Hytale (Head/Chest/Hands/Legs).
 *
 * Предмет Hytale для вещи ищется по equipment.json → model: точный id или слова (все слова должны быть в id,
 * для брони — ещё и нужный слот), из найденных берётся самый короткий id. Найденное и списки оружия и брони
 * игры пишутся в лог («Снаряжение …», «Оружие в игре», «Броня в игре»), чтобы поправить model при нужде.
 *
 * Компаньонам вещи кладутся в инвентарь NPC. Игроку — только на время боя: оружие в свободную ячейку хотбара
 * (или вместо предмета в руке, который потом возвращается), броня — только в пустые слоты брони.
 */
final class GearVisuals {
    private static final Map<String, String> resolved = new ConcurrentHashMap<>();
    private static final Set<String> logged = ConcurrentHashMap.newKeySet();
    private static volatile boolean listed;

    private GearVisuals() {}

    /** Предмет Hytale для вещи или null (не нашёлся). */
    static String itemId(Equipment.Gear g) {
        if (g == null || g.model().isEmpty()) return null;
        String r = resolved.computeIfAbsent(g.id(), k -> {
            String found = find(g);
            JrpgPlugin.get().log("Снаряжение «" + g.name() + "» (" + g.id() + "): "
                    + (found == null ? "предмет Hytale не найден по " + g.model() : found));
            return found == null ? "" : found;
        });
        return r.isEmpty() ? null : r;
    }

    static void reset() {
        resolved.clear();
    }

    private static String find(Equipment.Gear g) {
        try {
            Map<String, Item> all = Item.getAssetMap().getAssetMap();
            logLists(all);
            for (String spec : g.model()) {
                if (spec == null || spec.isBlank()) continue;
                if (all.containsKey(spec) && suits(all.get(spec), g.slot())) return spec;
                String[] words = spec.toLowerCase(Locale.ROOT).split("[\\s_]+");
                String best = null;
                for (Map.Entry<String, Item> e : all.entrySet()) {
                    String low = e.getKey().toLowerCase(Locale.ROOT);
                    boolean all3 = true;
                    for (String w : words) if (!w.isEmpty() && !low.contains(w)) { all3 = false; break; }
                    if (!all3 || !suits(e.getValue(), g.slot())) continue;
                    if (best == null || e.getKey().length() < best.length()
                            || (e.getKey().length() == best.length() && e.getKey().compareTo(best) < 0)) best = e.getKey();
                }
                if (best != null) return best;
            }
        } catch (Throwable t) {
            Fx.warnOnce("Не удалось найти предметы для снаряжения", t);
        }
        return null;
    }

    private static boolean suits(Item item, Equipment.Slot slot) {
        if (item == null) return false;
        if (slot.armor()) {
            return item.getArmor() != null && item.getArmor().getArmorSlot() == armorSlot(slot);
        }
        if (slot == Equipment.Slot.WEAPON || slot == Equipment.Slot.OFFHAND) {
            String id = item.getId();
            return item.getArmor() == null && (item.getWeapon() != null || (id != null && id.startsWith("Weapon_")));
        }
        return false;
    }

    private static ItemArmorSlot armorSlot(Equipment.Slot s) {
        return switch (s) {
            case HEAD -> ItemArmorSlot.Head;
            case CHEST -> ItemArmorSlot.Chest;
            case HANDS -> ItemArmorSlot.Hands;
            case LEGS -> ItemArmorSlot.Legs;
            default -> null;
        };
    }

    private static void logLists(Map<String, Item> all) {
        if (listed) return;
        listed = true;
        Set<String> weapons = new TreeSet<>(), armor = new TreeSet<>();
        for (Map.Entry<String, Item> e : all.entrySet()) {
            Item it = e.getValue();
            if (it == null) continue;
            if (it.getArmor() != null) armor.add(e.getKey());
            else if (it.getWeapon() != null || e.getKey().startsWith("Weapon_")) weapons.add(e.getKey());
        }
        JrpgPlugin.get().log("Оружие в игре (" + weapons.size() + "): " + weapons.stream().limit(160).toList());
        JrpgPlugin.get().log("Броня в игре (" + armor.size() + "): " + armor.stream().limit(160).toList());
    }

    /** Одеть NPC-компаньона по снаряжению героя: оружие, вторая рука, броня; пустые слоты брони очищаются. */
    static void dressNpc(Store<EntityStore> store, Ref<EntityStore> ref, Progression.Hero h, Content content) {
        if (ref == null || !ref.isValid() || h == null) return;
        List<String> report = new ArrayList<>();
        try {
            String weapon = itemId(h.gear(Equipment.Slot.WEAPON));
            if (weapon != null && InventoryHelper.setHotbarItem(ref, weapon, (byte) 0, store)) {
                InventoryHelper.setHotbarSlot(ref, (byte) 0, store);
                report.add("в руке " + weapon);
            } else if (weapon != null) {
                report.add("оружие " + weapon + " не встало");
            }
        } catch (Throwable t) {
            Fx.warnOnce("Не удалось дать оружие компаньону", t);
        }
        try {
            String off = itemId(h.gear(Equipment.Slot.OFFHAND));
            if (off != null && InventoryHelper.setOffHandItem(ref, off, (byte) 0, store)) {
                InventoryHelper.setOffHandSlot(ref, (byte) 0, store);
                report.add("вторая рука " + off);
            }
        } catch (Throwable t) {
            Fx.warnOnce("Не удалось дать щит компаньону", t);
        }
        try {
            InventoryComponent.Armor armor = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
            if (armor == null) {
                armor = new InventoryComponent.Armor((short) 4);
                store.putComponent(ref, InventoryComponent.Armor.getComponentType(), armor);
                report.add("слоты брони добавлены");
            }
            ItemContainer box = armor.getInventory();
            List<String> worn = new ArrayList<>();
            for (Equipment.Slot s : new Equipment.Slot[]{Equipment.Slot.HEAD, Equipment.Slot.CHEST, Equipment.Slot.HANDS, Equipment.Slot.LEGS}) {
                short idx = (short) armorSlot(s).ordinal();
                String id = itemId(h.gear(s));
                if (idx >= box.getCapacity()) continue;
                if (id == null) {
                    box.removeItemStackFromSlot(idx);
                } else if (InventoryHelper.useArmor(box, id)) {
                    worn.add(id);
                }
            }
            armor.setOutdatedEquipment(true);
            if (!worn.isEmpty()) report.add("броня " + worn);
        } catch (Throwable t) {
            Fx.warnOnce("Не удалось надеть броню на компаньона", t);
        }
        String line = h.def.name() + ": " + report;
        if (logged.add(line)) JrpgPlugin.get().log("Снаряжение на модели " + line);
    }

    /** Что положили игроку на время боя, чтобы потом вернуть как было. */
    static final class Loan {
        final short slot;
        final byte prevActive;
        final ItemStack replaced;
        final String weapon;
        final Map<Equipment.Slot, String> armor = new EnumMap<>(Equipment.Slot.class);

        Loan(short slot, byte prevActive, ItemStack replaced, String weapon) {
            this.slot = slot;
            this.prevActive = prevActive;
            this.replaced = replaced;
            this.weapon = weapon;
        }
    }

    /** Дать игроку оружие героя в руку и броню в пустые слоты. null — ничего не дали. */
    static Loan lendToPlayer(Store<EntityStore> store, Ref<EntityStore> ref, Progression.Hero h) {
        if (ref == null || !ref.isValid() || h == null) return null;
        Loan loan = null;
        try {
            String weapon = itemId(h.gear(Equipment.Slot.WEAPON));
            InventoryComponent.Hotbar bar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
            if (weapon != null && bar != null) {
                ItemContainer box = bar.getInventory();
                byte active = bar.getActiveSlot();
                short slot = -1;
                if (active >= 0 && active < box.getCapacity() && ItemStack.isEmpty(box.getItemStack(active))) slot = active;
                for (short i = 0; slot < 0 && i < box.getCapacity(); i++) {
                    if (ItemStack.isEmpty(box.getItemStack(i))) slot = i;
                }
                ItemStack replaced = null;
                if (slot < 0 && active >= 0) {
                    slot = active;
                    replaced = box.getItemStack(slot);
                }
                if (slot >= 0) {
                    box.setItemStackForSlot(slot, new ItemStack(weapon));
                    if (slot != active) bar.setActiveSlot((byte) slot, ref, store);
                    loan = new Loan(slot, active, replaced, weapon);
                }
            }
            InventoryComponent.Armor armor = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
            if (armor != null) {
                ItemContainer box = armor.getInventory();
                for (Equipment.Slot s : new Equipment.Slot[]{Equipment.Slot.HEAD, Equipment.Slot.CHEST, Equipment.Slot.HANDS, Equipment.Slot.LEGS}) {
                    String id = itemId(h.gear(s));
                    short idx = (short) armorSlot(s).ordinal();
                    if (id == null || idx >= box.getCapacity() || !ItemStack.isEmpty(box.getItemStack(idx))) continue;
                    box.setItemStackForSlot(idx, new ItemStack(id));
                    if (loan == null) loan = new Loan((short) -1, (byte) -1, null, null);
                    loan.armor.put(s, id);
                }
            }
            if (loan != null && logged.add("loan:" + loan.weapon + loan.armor)) {
                JrpgPlugin.get().log("Снаряжение игрока на бой: в руке " + loan.weapon
                        + (loan.replaced != null ? " (вместо своего предмета, вернётся после боя)" : "")
                        + ", броня в пустые слоты " + loan.armor.values());
            }
        } catch (Throwable t) {
            Fx.warnOnce("Не удалось дать игроку оружие героя", t);
        }
        return loan;
    }

    /** Вернуть игроку как было: убрать выданное (если оно ещё там) и вернуть свой предмет и ячейку. */
    static void restorePlayer(Store<EntityStore> store, Ref<EntityStore> ref, Loan loan) {
        if (loan == null || ref == null || !ref.isValid()) return;
        try {
            InventoryComponent.Hotbar bar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
            if (bar != null && loan.slot >= 0) {
                ItemContainer box = bar.getInventory();
                ItemStack now = box.getItemStack(loan.slot);
                if (now != null && loan.weapon.equals(now.getItemId())) {
                    if (loan.replaced != null && !loan.replaced.isEmpty()) box.setItemStackForSlot(loan.slot, loan.replaced);
                    else box.removeItemStackFromSlot(loan.slot);
                }
                if (loan.prevActive >= 0 && loan.prevActive != loan.slot) bar.setActiveSlot(loan.prevActive, ref, store);
            }
            InventoryComponent.Armor armor = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
            if (armor != null) {
                ItemContainer box = armor.getInventory();
                loan.armor.forEach((s, id) -> {
                    short idx = (short) armorSlot(s).ordinal();
                    ItemStack now = box.getItemStack(idx);
                    if (now != null && id.equals(now.getItemId())) box.removeItemStackFromSlot(idx);
                });
            }
        } catch (Throwable t) {
            Fx.warnOnce("Не удалось вернуть игроку его предметы после боя", t);
        }
    }
}
