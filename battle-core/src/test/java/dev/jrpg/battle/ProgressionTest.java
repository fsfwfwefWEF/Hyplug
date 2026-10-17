package dev.jrpg.battle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProgressionTest {

    @Test
    void gridGeometryIsConnected() {
        java.util.Set<Integer> seen = new java.util.HashSet<>(List.of(0));
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>(List.of(0));
        while (!q.isEmpty()) for (int nb : Progression.Grid.neighbors(q.poll())) if (seen.add(nb)) q.add(nb);
        assertEquals(Progression.Grid.SIZE, seen.size(), "из центра достижим каждый узел");
        for (int a = 0; a < Progression.Grid.SIZE; a++)
            for (int b : Progression.Grid.neighbors(a))
                assertTrue(Progression.Grid.neighbors(b).contains(a), "связь " + a + "-" + b + " в обе стороны");
    }

    @Test
    void levelUpGivesGridPoints() {
        Progression.Hero hero = new Progression.Party(Content.demo()).hero("hero");
        int hp1 = hero.stats().maxHp();
        assertEquals(2, hero.addExp(Progression.expToNext(1) + Progression.expToNext(2)));
        assertEquals(3, hero.level);
        assertEquals(Progression.START_POINTS + 2 * Progression.POINTS_PER_LEVEL, hero.points);
        assertEquals(hp1 + 2 * hero.def.growth().maxHp(), hero.stats().maxHp());
    }

    @Test
    void unlockNeedsNeighbourLevelAndPoints() {
        Content c = Content.demo();
        Progression.Hero lyra = new Progression.Party(c).hero("lyra");
        assertNotNull(lyra.whyCantUnlock(7), "второе кольцо не соседствует с центром");
        assertNull(lyra.unlock(3), "узел первого кольца за 1 очко");
        assertTrue(lyra.abilities().contains("water_jet"));
        assertNotNull(lyra.whyCantUnlock(11), "второе кольцо требует 3 уровень");
        lyra.addExp(1000);
        assertNull(lyra.unlock(11), "узел 11 соседствует с узлом 3 (кольцо 1)");
        assertNull(lyra.unlock(10), "узел 10 — сосед 11 по кольцу");
        assertTrue(lyra.abilities().contains("ice_lance"));
        int pts = lyra.points;
        lyra.respec();
        assertEquals(pts + 1 + 1 + 2, lyra.points);
        assertEquals(java.util.Set.of(0), lyra.unlocked);
    }

    @Test
    void gridStatsAndPassivesReachBattle() {
        Content c = Content.demo();
        Progression.Party party = new Progression.Party(c);
        Progression.Hero bram = party.hero("bram");
        int def = bram.stats().def();
        bram.points = 10;
        assertNull(bram.unlock(1));  // Защита +3
        assertNull(bram.unlock(5));  // Толстая кожа
        Combatant inBattle = party.buildParty(c).stream().filter(x -> x.id.equals("bram")).findFirst().orElseThrow();
        assertEquals(def + 3, inBattle.stats.def());
        assertTrue(inBattle.passives.contains(Passives.THICK_SKIN));
    }

    @Test
    void everyGridNodeIsDescribed() {
        Content c = Content.demo();
        for (Progression.HeroDef d : c.heroes()) {
            Progression.Hero h = new Progression.Party(c).hero(d.id());
            for (int i = 0; i < Progression.Grid.SIZE; i++) {
                Progression.GridNode n = h.node(i);
                assertFalse(Describe.node(c, h, i).isBlank());
                assertFalse(Describe.nodeTitle(c, h, n).isBlank());
                assertFalse(Describe.nodeIcon(c, h, n).isBlank());
            }
        }
        assertTrue(Describe.ability(c, c.ability("fireball")).contains("Горение"));
        assertTrue(Describe.ability(c, c.ability("thunder")).contains("Электрошок"));
        assertEquals("fire", Describe.abilityIcon(c, c.ability("fireball")));
    }
}
