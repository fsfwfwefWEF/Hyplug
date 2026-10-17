package dev.jrpg.battle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProgressionTest {

    @Test
    void levelUpGivesPointsAndGrowth() {
        Content c = Content.demo();
        Progression.Party party = new Progression.Party(c);
        Progression.Hero hero = party.hero("hero");
        int hp1 = hero.stats().maxHp();
        int ups = hero.addExp(Progression.expToNext(1) + Progression.expToNext(2));
        assertEquals(2, ups);
        assertEquals(3, hero.level);
        assertEquals(2 * Progression.STAT_POINTS_PER_LEVEL, hero.statPoints);
        assertEquals(2, hero.skillPoints);
        assertEquals(hp1 + 2 * hero.def.growth().maxHp(), hero.stats().maxHp());
    }

    @Test
    void skillTreeRequiresLevelOrderAndPoints() {
        Content c = Content.demo();
        Progression.Hero lyra = new Progression.Party(c).hero("lyra");
        Progression.SkillNode first = lyra.def.tree().get(0), second = lyra.def.tree().get(1);
        assertNotNull(lyra.whyCantLearn(first), "на 1 уровне без очков нельзя");
        lyra.addExp(1000);
        assertNotNull(lyra.whyCantLearn(second), "второй навык только после первого");
        assertNull(lyra.learn(first));
        assertNull(lyra.learn(second));
        assertTrue(lyra.abilities().containsAll(List.of(first.abilityId(), second.abilityId())));
        lyra.respec();
        assertFalse(lyra.abilities().contains(first.abilityId()));
    }

    @Test
    void investedStatsReachBattle() {
        Content c = Content.demo();
        Progression.Party party = new Progression.Party(c);
        Progression.Hero bram = party.hero("bram");
        bram.addExp(Progression.expToNext(1));
        int before = bram.stats().def();
        assertNull(bram.invest(Progression.Stat.DEF));
        Combatant inBattle = party.buildParty(c).stream().filter(x -> x.id.equals("bram")).findFirst().orElseThrow();
        assertEquals(before + Progression.Stat.DEF.perPoint, inBattle.stats.def());
    }

    @Test
    void everyAbilityHasDescriptionAndIcon() {
        Content c = Content.demo();
        for (Progression.HeroDef h : c.heroes()) {
            for (String id : h.startAbilities()) {
                Ability a = c.ability(id);
                assertFalse(Describe.ability(c, a).isBlank());
                assertFalse(Describe.abilityIcon(c, a).isBlank());
            }
            for (Progression.SkillNode n : h.tree()) assertFalse(Describe.ability(c, c.ability(n.abilityId())).isBlank());
        }
        assertTrue(Describe.ability(c, c.ability("fireball")).contains("Горение"));
        assertEquals("fire", Describe.abilityIcon(c, c.ability("fireball")));
    }
}
