package dev.jrpg.battle.scripts;

import dev.jrpg.battle.*;

import java.util.Comparator;

/**
 * Эксклюзивные механики Королевы пауков (пример «уникального босса»):
 * 1. «Защита выводка»: пока жив хоть один паучонок, королева получает вдвое меньше урона.
 *    Игроку выгодно сначала убивать паучат.
 * 2. Каждый 3-й свой ход королева обязательно плетёт «Кокон» на самого здорового героя (оглушение).
 * Фазы по HP (кислотный дождь, призыв, ярость) — в encounters.json, а не здесь.
 */
public final class SpiderQueenScript implements BattleScript {
    public static final String ID = "spider_queen";
    private static final String QUEEN = "spider_queen";
    private static final String BROOD = "spiderling";
    private static final String SHIELD = "brood_shield";

    private int queenTurns;

    @Override
    public void onBattleStart(Battle battle) {
        updateShield(battle);
    }

    @Override
    public void onTurnStart(Battle battle, Combatant actor) {
        updateShield(battle);
    }

    @Override
    public void afterAction(Battle battle, ActionResult result) {
        updateShield(battle);
    }

    @Override
    public BattleAction overrideAction(Battle battle, Combatant enemy) {
        if (!QUEEN.equals(enemy.templateId)) return null;
        if (++queenTurns % 3 != 0) return null;
        Combatant target = battle.alive(Side.PARTY).stream()
                .max(Comparator.comparingInt(c -> c.hp)).orElse(null);
        if (target == null) return null;
        battle.say(enemy, target.name + ", ты будешь моим ужином!");
        return new BattleAction.UseAbility("cocoon", target.id);
    }

    private void updateShield(Battle battle) {
        Combatant queen = battle.combatants().stream()
                .filter(c -> QUEEN.equals(c.templateId) && c.isAlive()).findFirst().orElse(null);
        if (queen == null) return;
        boolean broodAlive = battle.alive(Side.ENEMY).stream().anyMatch(c -> BROOD.equals(c.templateId));
        StatusInstance shield = queen.status(SHIELD);
        if (broodAlive && shield == null) {
            Battle.applyStatus(queen, battle.content().status(SHIELD), 999, false);
            battle.say(null, "Паучата прикрывают королеву: урон по ней уменьшен вдвое.");
        } else if (!broodAlive && shield != null) {
            queen.statuses.remove(shield);
            battle.say(null, "Выводок пал, королева беззащитна!");
        } else if (shield != null) {
            shield.turnsLeft = 999;
        }
    }
}
