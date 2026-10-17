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
        assertNull(lyra.unlock(6), "узел первого кольца за 1 очко");
        assertTrue(lyra.abilities().contains("water_jet"));
        assertNotNull(lyra.whyCantUnlock(17), "второе кольцо требует 3 уровень");
        lyra.addExp(1000);
        int before = lyra.points;
        assertNull(lyra.unlock(17), "узел 17 соседствует с узлом 6 (кольцо 1)");
        assertEquals(before - 2, lyra.points, "способность бойца для целителя — полная цена");
        assertNull(lyra.unlock(16), "узел 16 — сосед 17 по кольцу");
        assertTrue(lyra.abilities().contains("ice_lance"));
        int pts = lyra.points;
        lyra.respec();
        assertEquals(pts + 1 + 2 + 2, lyra.points);
        assertEquals(java.util.Set.of(0), lyra.unlocked);
    }

    @Test
    void rolesSplitTheGridAndDiscountOwnSector() {
        assertEquals(java.util.List.of(Progression.Role.TANK), Progression.Grid.sector(8));
        assertEquals(java.util.List.of(Progression.Role.HEALER), Progression.Grid.sector(12));
        assertEquals(java.util.List.of(Progression.Role.DPS), Progression.Grid.sector(16));
        assertEquals(2, Progression.Grid.sector(20).size(), "внешний мост — гибрид двух ролей");
        Content c = Content.demo();
        Progression.Party party = new Progression.Party(c);
        Progression.Hero bram = party.hero("bram"), lyra = party.hero("lyra");
        assertEquals(1, bram.cost(8), "пассивка своей роли во 2 кольце дешевле");
        assertEquals(2, lyra.cost(8), "чужая роль — полная цена");
        assertEquals(1, lyra.cost(12), "Лира — целитель: пассивка целителя дешевле");
        assertEquals(1, party.hero("hero").cost(1), "первое кольцо всегда 1 очко");
    }

    @Test
    void gridStatsAndPassivesReachBattle() {
        Content c = Content.demo();
        Progression.Party party = new Progression.Party(c);
        Progression.Hero bram = party.hero("bram");
        bram.addExp(1000);
        int def = bram.stats().def();
        bram.points = 10;
        assertNull(bram.unlock(1));  // Защита +3
        assertNull(bram.unlock(2));  // Провокация
        assertNull(bram.unlock(7));  // Здоровье +30
        assertNull(bram.unlock(8));  // Страж
        Combatant inBattle = party.buildParty(c).stream().filter(x -> x.id.equals("bram")).findFirst().orElseThrow();
        assertEquals(def + 3, inBattle.stats.def());
        assertTrue(inBattle.passives.contains(Passives.GUARDIAN));
        assertTrue(inBattle.abilities.contains("provoke"));
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
