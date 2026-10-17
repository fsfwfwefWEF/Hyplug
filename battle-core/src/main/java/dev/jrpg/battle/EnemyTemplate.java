package dev.jrpg.battle;

import java.util.List;

/**
 * Шаблон врага из enemies.json.
 * role — роль NPC Hytale, которой он изображается на арене (например "Trork_Warrior"); model — свой ассет (позже).
 */
public record EnemyTemplate(String id, String name, Stats stats, List<String> abilities, boolean boss,
                            List<String> immunities, String model, String role) {

    public Combatant create(String instanceId) {
        Combatant c = new Combatant(instanceId, name, Side.ENEMY, stats, abilities);
        c.templateId = id;
        c.boss = boss;
        if (immunities != null) c.immunities.addAll(immunities);
        return c;
    }
}
