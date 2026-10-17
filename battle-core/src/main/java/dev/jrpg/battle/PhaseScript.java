package dev.jrpg.battle;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Фазы босса из encounters.json: когда HP босса падает ниже порога — реплика, новые способности,
 * статусы на себя и призыв подмоги. Каждая фаза срабатывает один раз.
 */
public final class PhaseScript implements BattleScript {

    /**
     * @param boss    templateId босса
     * @param hpBelow доля HP (0.5 = 50%)
     */
    public record Phase(String boss, double hpBelow, String name, String say, List<String> addAbilities,
                        List<String> removeAbilities, List<StatusApply> selfStatuses, List<String> summon) {}

    private final List<Phase> phases;
    private final GameData data;
    private final Set<Phase> fired = new HashSet<>();
    private int summonCounter;

    public PhaseScript(List<Phase> phases, GameData data) {
        this.phases = phases;
        this.data = data;
    }

    @Override
    public void onTurnStart(Battle battle, Combatant actor) {
        check(battle);
    }

    @Override
    public void afterAction(Battle battle, ActionResult result) {
        check(battle);
    }

    private void check(Battle battle) {
        for (Phase p : phases) {
            if (fired.contains(p)) continue;
            Combatant boss = battle.combatants().stream()
                    .filter(c -> p.boss().equals(c.templateId) && c.isAlive()).findFirst().orElse(null);
            if (boss == null || boss.hp > boss.stats.maxHp() * p.hpBelow()) continue;
            fired.add(p);
            battle.announcePhase(boss, p.name());
            if (p.say() != null) battle.say(boss, p.say());
            if (p.removeAbilities() != null) boss.abilities.removeAll(p.removeAbilities());
            if (p.addAbilities() != null) {
                for (String a : p.addAbilities()) if (!boss.abilities.contains(a)) boss.abilities.add(a);
            }
            if (p.selfStatuses() != null) {
                for (StatusApply sa : p.selfStatuses()) {
                    Battle.applyStatus(boss, battle.content().status(sa.statusId()), sa.turns(), false);
                }
            }
            if (p.summon() != null) {
                for (String t : p.summon()) {
                    battle.summon(data.enemy(t).create(t + "#s" + (++summonCounter)));
                }
            }
        }
    }
}
