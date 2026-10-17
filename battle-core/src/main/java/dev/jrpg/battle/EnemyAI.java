package dev.jrpg.battle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Простой ИИ врага: лечит союзника при HP < 35%, иначе случайная доступная атакующая способность
 * (или обычная атака) по самому раненому герою, а если кто-то провоцирует (танк) — по нему. Позже — профили поведения в JSON.
 */
public final class EnemyAI {
    private final Content content;
    private final Random random;

    public EnemyAI(Content content, Random random) {
        this.content = content;
        this.random = random;
    }

    public BattleAction choose(Battle battle, Combatant self) {
        List<Ability> usable = self.abilities.stream().map(content::ability)
                .filter(a -> battle.canAfford(self, a)).toList();

        Combatant hurtAlly = battle.alive(self.side).stream()
                .filter(c -> c.hp < c.stats.maxHp() * 0.35)
                .min(Comparator.comparingInt(c -> c.hp)).orElse(null);
        if (hurtAlly != null) {
            for (Ability a : usable) {
                if (a.healPower() > 0) {
                    return new BattleAction.UseAbility(a.id(), a.target().needsTargetChoice() ? hurtAlly.id : null);
                }
            }
        }

        // Провокация танка: одиночные атаки идут только в него.
        List<Combatant> foes = battle.alive(self.side.opposite());
        Combatant target = foes.stream().filter(c -> c.statuses.stream()
                        .anyMatch(s -> s.def.kind() == StatusDef.Kind.TAUNT)).findFirst()
                .orElseGet(() -> foes.stream().min(Comparator.comparingInt(c -> c.hp)).orElseThrow());
        List<Ability> offensive = new ArrayList<>(usable.stream().filter(a -> a.damagePower() > 0).toList());
        if (!offensive.isEmpty() && random.nextDouble() < 0.6) {
            Ability a = offensive.get(random.nextInt(offensive.size()));
            return new BattleAction.UseAbility(a.id(), a.target().needsTargetChoice() ? target.id : null);
        }
        return new BattleAction.Attack(target.id);
    }
}
