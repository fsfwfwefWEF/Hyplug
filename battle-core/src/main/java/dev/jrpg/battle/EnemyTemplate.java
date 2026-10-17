package dev.jrpg.battle;

import java.util.List;
import java.util.Map;

/**
 * Шаблон врага из enemies.json.
 * role — роль NPC Hytale, которой он изображается на арене (например "Trork_Warrior"); model — свой ассет (позже).
 * loot — что может выпасть, когда этот враг повержен. anims — свои имена анимаций модели по видам
 * ("attack", "cast"): у моделей Hytale нет общей анимации «Attack», у каждой свои (SwingWeaponAir, Roar…).
 */
public record EnemyTemplate(String id, String name, Stats stats, List<String> abilities, boolean boss,
                            List<String> immunities, String model, String role,
                            List<Encounter.Loot> loot, Map<String, List<String>> anims) {

    public EnemyTemplate(String id, String name, Stats stats, List<String> abilities, boolean boss,
                         List<String> immunities, String model, String role) {
        this(id, name, stats, abilities, boss, immunities, model, role, List.of(), Map.of());
    }

    public List<Encounter.Loot> loot() { return loot == null ? List.of() : loot; }

    /** Свои анимации для вида kind или пустой список (тогда берутся общие из settings.json). */
    public List<String> anim(String kind) {
        return anims == null ? List.of() : anims.getOrDefault(kind, List.of());
    }

    public Combatant create(String instanceId) {
        Combatant c = new Combatant(instanceId, name, Side.ENEMY, stats, abilities);
        c.templateId = id;
        c.boss = boss;
        if (immunities != null) c.immunities.addAll(immunities);
        return c;
    }
}
