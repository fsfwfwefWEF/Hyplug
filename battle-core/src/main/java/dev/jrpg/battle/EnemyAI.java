package dev.jrpg.battle;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * ИИ врага с оценкой ходов: перебирает все доступные способности (и обычную атаку) по всем целям,
 * считает ожидаемую пользу и берёт лучшую с небольшой случайностью.
 *
 * Что учитывает: ожидаемый урон с защитой цели, комбо стихий и Зарядом; добивание (большой бонус за убийство);
 * приоритет целителей и раненых; удары по всем ценятся по сумме; статусы — только если их ещё нет и нет иммунитета;
 * лечение — только когда союзник ранен; усиления — когда их ещё нет; провокацию танка соблюдает.
 * Обычная атака копит AP, поэтому дорогие способности слегка штрафуются, чтобы враг не тратил AP впустую.
 */
public final class EnemyAI {
    /** Разброс оценок: 0.15 — ходы с близкой пользой выбираются то так, то так. */
    private static final double JITTER = 0.15;

    private final Content content;
    private final Random random;

    public EnemyAI(Content content, Random random) {
        this.content = content;
        this.random = random;
    }

    public BattleAction choose(Battle battle, Combatant self) {
        List<Ability> usable = new ArrayList<>();
        usable.add(content.ability(Content.ATTACK));
        for (String id : self.abilities) {
            Ability a = content.ability(id);
            if (!a.id().equals(Content.ATTACK) && battle.canAfford(self, a)) usable.add(a);
        }
        List<Combatant> foes = battle.alive(self.side.opposite());
        List<Combatant> allies = battle.alive(self.side);
        Combatant taunter = foes.stream().filter(c -> c.statuses.stream()
                .anyMatch(s -> s.def.kind() == StatusDef.Kind.TAUNT)).findFirst().orElse(null);

        BattleAction best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Ability a : usable) {
            for (Combatant t : candidates(a, self, foes, allies, taunter)) {
                List<Combatant> hit = switch (a.target()) {
                    case SELF -> List.of(self);
                    case SINGLE_ALLY, SINGLE_ENEMY -> List.of(t);
                    case ALL_ALLIES -> allies;
                    case ALL_ENEMIES -> foes;
                };
                double score = score(battle, self, a, hit, allies);
                score -= a.apCost() * 1.5 + a.mpCost() * 0.15;
                score *= 1 - JITTER + random.nextDouble() * 2 * JITTER;
                if (score > bestScore) {
                    bestScore = score;
                    String target = a.target().needsTargetChoice() ? t.id : null;
                    best = a.id().equals(Content.ATTACK) ? new BattleAction.Attack(t.id)
                            : new BattleAction.UseAbility(a.id(), target);
                }
            }
        }
        if (best == null) {
            Combatant t = taunter != null ? taunter : foes.getFirst();
            best = new BattleAction.Attack(t.id);
        }
        return best;
    }

    /** Кого можно выбрать целью: для ударов по всем/по себе — один «представитель», чтобы не считать дважды. */
    private static List<Combatant> candidates(Ability a, Combatant self, List<Combatant> foes, List<Combatant> allies,
                                              Combatant taunter) {
        return switch (a.target()) {
            case SINGLE_ENEMY -> taunter != null ? List.of(taunter) : foes;
            case SINGLE_ALLY -> allies;
            case SELF, ALL_ALLIES -> List.of(self);
            case ALL_ENEMIES -> foes.isEmpty() ? List.of() : List.of(foes.getFirst());
        };
    }

    private double score(Battle battle, Combatant self, Ability a, List<Combatant> targets, List<Combatant> allies) {
        double total = 0;
        boolean offensive = a.damagePower() > 0;
        for (Combatant t : targets) {
            double s = 0;
            if (offensive) {
                double dmg = expectedDamage(self, a, t);
                double frac = Math.min(1.0, dmg / Math.max(1, t.hp));
                s += Math.min(dmg, t.hp) / Math.max(1, t.stats.maxHp()) * 100;
                if (dmg >= t.hp) s += 60;                      // добить
                else s += frac * 20;                            // чем ближе к смерти, тем ценнее удар
                if (isHealer(t)) s *= 1.25;                     // целителя — в первую очередь
                if (t.status("guard") != null) s *= 0.7;        // в защите — бить невыгодно
            }
            if (a.healPower() > 0 && t.isAlive()) {
                double missing = 1.0 - t.hp / (double) t.stats.maxHp();
                if (missing > 0.25) s += Math.min(missing, a.healPower()) * 160 + (missing > 0.6 ? 25 : 0);
                else s -= 10;
            }
            for (String cure : a.cures()) if (t.status(cure) != null && t.side == self.side) s += 15;
            for (StatusApply sa : a.applies()) s += statusValue(battle, self, a, t, sa, allies);
            total += s;
        }
        return total;
    }

    private double expectedDamage(Combatant self, Ability a, Combatant t) {
        double power = a.magical() ? self.stats.mag() : self.stats.atk();
        double raw = power * a.damagePower() * self.multiplier(StatusDef.Kind.NEXT_ACTION_MULT)
                * Battle.mitigation(t, a.magical());
        raw = Math.max(1, raw) * t.multiplier(StatusDef.Kind.DAMAGE_TAKEN_MULT);
        String element = content.element(a);
        for (Combo k : content.combos()) {
            if (k.element().equals(element) && t.status(k.requires()) != null) {
                raw = raw * k.damageMult() + t.stats.maxHp() * k.bonusMaxHp();
            }
        }
        if (t.passives.contains(Passives.GUARDIAN)) raw *= 0.85;
        return raw;
    }

    /** Польза наложить статус: дебафф врагу, бафф своему. 0, если уже висит или иммунитет. */
    private double statusValue(Battle battle, Combatant self, Ability a, Combatant t, StatusApply sa, List<Combatant> allies) {
        if (t.immunities.contains(sa.statusId())) return 0;
        StatusDef def = content.status(sa.statusId());
        boolean already = t.status(def.id()) != null;
        double v = switch (def.kind()) {
            case SKIP_TURN -> 30;
            case DOT_FLAT, DOT_PERCENT -> 12;
            case SPEED_MULT -> def.power() < 1 ? 10 : 14;
            case DAMAGE_TAKEN_MULT -> def.power() > 1 ? 14
                    : (t.hp < t.stats.maxHp() * 0.5 ? 22 : 10);                 // защита нужнее раненому
            case HOT_PERCENT -> t.hp < t.stats.maxHp() * 0.8 ? 10 : 3;
            case NEXT_ACTION_MULT -> 16;                                          // Заряд усиливает и обычную атаку
            case MARKER -> comboReady(allies, def.id()) ? 10 : 4;              // «Промок»: если у своих есть молния/лёд
            case TAUNT -> 0;
            default -> 3;
        };
        if (already) v *= def.stackable() ? 0.5 : 0.1;
        // Дебафф на своего или бафф на врага не нужен.
        boolean buff = def.kind() == StatusDef.Kind.HOT_PERCENT || def.kind() == StatusDef.Kind.NEXT_ACTION_MULT
                || (def.kind() == StatusDef.Kind.DAMAGE_TAKEN_MULT && def.power() < 1)
                || (def.kind() == StatusDef.Kind.SPEED_MULT && def.power() > 1);
        if (buff != (t.side == self.side)) return 0;
        return v * Math.min(1.0, sa.chance());
    }

    private boolean isHealer(Combatant c) {
        for (String id : c.abilities) if (content.ability(id).healPower() > 0 && content.ability(id).target() != TargetType.SELF) return true;
        return false;
    }

    private boolean comboReady(List<Combatant> allies, String marker) {
        for (Combatant a : allies) {
            for (String id : a.abilities) {
                String el = content.element(content.ability(id));
                for (Combo k : content.combos()) if (k.requires().equals(marker) && k.element().equals(el)) return true;
            }
        }
        return false;
    }
}
