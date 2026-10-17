package dev.jrpg.battle;

import java.util.List;

/**
 * Шанс уйти от боя ещё до его начала (окно «Сражаться / Сбежать» у встречи).
 * Как и бегство в бою, зависит от скорости партии против скорости врагов; до боя чуть щедрее,
 * зато чем больше врагов, тем сложнее. От боссов и встреч с canFlee = false не убежать.
 */
public final class Escape {
    private Escape() {
    }

    public static double chance(Encounter e, List<Combatant> party, GameData data) {
        if (!e.canFlee() || e.enemies().isEmpty()) return e.canFlee() ? 1.0 : 0.0;
        double enemySpd = 0;
        for (Encounter.Spot s : e.enemies()) {
            EnemyTemplate t = data.enemy(s.template());
            if (t.boss()) return 0.0;
            enemySpd += t.stats().spd();
        }
        enemySpd /= e.enemies().size();
        double partySpd = party.stream().mapToDouble(c -> c.stats.spd()).average().orElse(10);
        double chance = 0.6 * partySpd / Math.max(1, enemySpd) - 0.05 * (e.enemies().size() - 1);
        return Math.clamp(chance, 0.1, 0.9);
    }
}
