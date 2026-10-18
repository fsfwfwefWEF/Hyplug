package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.ItemAnimation;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.itemanimation.config.ItemPlayerAnimations;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Ванильные удары оружием: те же анимации, что у игрока и NPC в обычном бою Hytale (наборы
 * ItemPlayerAnimations — «Sword», «Axe», «Spear»…). Набор берётся у предмета в руке бойца (меч игрока,
 * дубина трорка), иначе — первый существующий из подсказок (settings.json → animations.heroes.*.weapon,
 * enemies.json → anim.weapon). Имена анимаций внутри набора заранее неизвестны, поэтому они ищутся
 * по словам (Swing, Slash, Stab…), а сами наборы и их анимации пишутся в лог при первом использовании.
 */
final class WeaponAnims {
    /** Слова, по которым анимация набора НЕ годится для удара: позы, ходьба, зарядка (зацикленные). */
    private static final List<String> NOT_A_STRIKE = List.of("idle", "walk", "run", "sprint", "crouch", "jump",
            "fall", "swim", "fly", "climb", "equip", "pullback", "charging", "loop", "hold", "aim", "sit", "slide",
            "mantle", "glide", "roll", "dodge", "charged", "leap", "whirlwind", "spin", "frontflip", "dash");

    private static final Random RND = new Random();
    private static final Set<String> loggedSets = new HashSet<>();
    private static volatile boolean loggedAll;

    private WeaponAnims() {}

    /**
     * Проиграть удар оружием. words — слова анимаций по порядку предпочтения (из settings.json:
     * animations.weaponAttack / weaponSkill / weaponCast / weaponParry). true — нашлось и отправлено.
     */
    static boolean play(Store<EntityStore> store, Ref<EntityStore> ref, List<String> hints, List<String> words) {
        if (ref == null || !ref.isValid() || words == null || words.isEmpty()) return false;
        Map<String, ItemPlayerAnimations> all = sets();
        if (all.isEmpty()) return false;
        List<String> tried = new ArrayList<>();
        String held = heldSet(store, ref);
        // Пустая рука (набор Default — удары кулаками) не мешает подсказкам: герой бьёт «мечом» из
        // settings.json, пока у него нет настоящего оружия в руке.
        boolean emptyHand = held != null && held.equalsIgnoreCase(ItemPlayerAnimations.DEFAULT_ID);
        if (held != null && !(emptyHand && hints != null && !hints.isEmpty())) tried.add(held);
        if (hints != null) for (String h : hints) if (!tried.contains(h)) tried.add(h);
        for (String setId : tried) {
            ItemPlayerAnimations set = find(all, setId);
            if (set == null) continue;
            String anim = pick(set, words);
            if (anim == null) continue;
            try {
                AnimationUtils.playAnimation(ref, AnimationSlot.Action, set.getId(), anim, true, store);
                return true;
            } catch (Throwable t) {
                Fx.warnOnce("Анимация оружия " + set.getId() + "/" + anim + " не сработала", t);
                return false;
            }
        }
        return false;
    }

    /** Есть ли у бойца предмет в руке со своим набором анимаций (меч, топор, посох…). */
    static boolean holdsWeapon(Store<EntityStore> store, Ref<EntityStore> ref) {
        String s = heldSet(store, ref);
        return s != null && !s.equalsIgnoreCase(ItemPlayerAnimations.DEFAULT_ID);
    }

    /** Набор анимаций предмета в руке: у игрока и у NPC (их оружие задаёт роль). null — рука пустая. */
    private static String heldSet(Store<EntityStore> store, Ref<EntityStore> ref) {
        try {
            LivingEntity e = store.getComponent(ref, Player.getComponentType());
            if (e == null) e = store.getComponent(ref, NPCEntity.getComponentType());
            if (e == null || e.getInventory() == null) return null;
            ItemStack stack = e.getInventory().getItemInHand();
            if (stack == null) return null;
            Item item = stack.getItem();
            if (item == null) return null;
            String id = item.getPlayerAnimationsId();
            return id == null || id.isBlank() ? null : id;
        } catch (Throwable t) {
            Fx.warnOnce("Не удалось узнать оружие в руке", t);
            return null;
        }
    }

    private static ItemPlayerAnimations find(Map<String, ItemPlayerAnimations> all, String id) {
        ItemPlayerAnimations set = all.get(id);
        if (set != null) return set;
        for (Map.Entry<String, ItemPlayerAnimations> e : all.entrySet()) {
            if (e.getKey().equalsIgnoreCase(id)) return e.getValue();
        }
        return null;
    }

    /**
     * Анимация удара в наборе. Сначала точные имена из списка (случайное из тех, что есть в наборе, —
     * разные замахи), потом по части имени. Заряжаемые и вращения (…Charged, Whirlwind, Spin…) по части
     * имени не берутся: они зациклены и не кончаются сами (в 0.7.2 Брам так и крутился после удара).
     */
    private static String pick(ItemPlayerAnimations set, List<String> words) {
        Map<String, ItemAnimation> anims = set.getAnimations();
        if (anims == null || anims.isEmpty()) return null;
        if (loggedSets.add(set.getId())) {
            JrpgPlugin.get().log("Анимации оружия " + set.getId() + ": " + new java.util.TreeSet<>(anims.keySet()));
        }
        List<String> exact = new ArrayList<>();
        for (String w : words) {
            for (String key : anims.keySet()) if (key.equalsIgnoreCase(w)) exact.add(key);
        }
        if (!exact.isEmpty()) return exact.get(RND.nextInt(exact.size()));
        for (String w : words) {
            String want = w.toLowerCase(Locale.ROOT);
            List<String> found = new ArrayList<>();
            for (String key : anims.keySet()) {
                String low = key.toLowerCase(Locale.ROOT);
                if (!low.contains(want)) continue;
                if (NOT_A_STRIKE.stream().anyMatch(bad -> low.contains(bad) && !want.contains(bad))) continue;
                found.add(key);
            }
            if (!found.isEmpty()) return found.get(RND.nextInt(found.size()));
        }
        return null;
    }

    private static Map<String, ItemPlayerAnimations> sets() {
        try {
            Map<String, ItemPlayerAnimations> all = ItemPlayerAnimations.getAssetMap().getAssetMap();
            if (!loggedAll && !all.isEmpty()) {
                loggedAll = true;
                JrpgPlugin.get().log("Наборы анимаций оружия (" + all.size() + "): " + new java.util.TreeSet<>(all.keySet()));
            }
            return all;
        } catch (Throwable t) {
            Fx.warnOnce("Список анимаций оружия не прочитать", t);
            return Map.of();
        }
    }
}
