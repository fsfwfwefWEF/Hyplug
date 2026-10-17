package dev.jrpg.battle;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RolesAndSquadsTest {
    private final Content content = Content.demo();

    @Test
    void tauntPullsEnemyAttacks() throws Exception {
        Combatant tank = new Combatant("bram", "Брам", Side.PARTY, new Stats(500, 0, 10, 0, 10, 1), List.of());
        Combatant weak = new Combatant("lyra", "Лира", Side.PARTY, new Stats(30, 0, 10, 0, 1, 1), List.of());
        Combatant foe = new Combatant("goblin", "Гоблин", Side.ENEMY, new Stats(100, 0, 10, 0, 1, 50), List.of());
        Battle b = new Battle(content, List.of(tank, weak, foe), Content.demoInventory(), new BattleListener() {}, 1);
        Battle.applyStatus(tank, content.status("taunt"), 3, false);
        BattleAction a = new EnemyAI(content, new Random(1)).choose(b, foe);
        assertEquals(new BattleAction.Attack("bram"), a, "без провокации бил бы самую слабую — Лиру");
    }

    @Test
    void squadsLoadAndStayAwayFromManualEncounters() throws Exception {
        GameData data = GameData.load(content, null);
        Squads s = data.squads();
        assertTrue(s.enabled);
        assertFalse(s.templates.isEmpty());
        Encounter mill = data.encounter("mill_goblins");
        Encounter.Location l = mill.location();
        assertFalse(s.allowedAt("default", l.x() + 5, l.z(), data.encounters()), "не рядом с ручной встречей");
        assertTrue(s.allowedAt("default", l.x() + 500, l.z() + 500, data.encounters()));

        Squads.Template t = s.pick(new Random(7));
        Encounter e = s.encounter(t, "squad:1", "default", 0, 64, 0);
        assertEquals(t.enemies().size(), e.enemies().size());
        Battle b = data.createBattle(e, Content.demoParty(), Content.demoInventory(), new BattleListener() {}, 1);
        assertEquals(3 + t.enemies().size(), b.combatants().size());
    }

    @Test
    void escapeChanceDependsOnSpeedAndBosses() throws Exception {
        GameData data = GameData.load(content, null);
        List<Combatant> party = Content.demoParty();
        double mill = Escape.chance(data.encounter("mill_goblins"), party, data);
        assertTrue(mill > 0.1 && mill < 0.9, "обычная встреча: шанс между 10% и 90%, было " + mill);
        assertEquals(0.0, Escape.chance(data.encounter("spider_queen_lair"), party, data), "от босса не убежать");
    }
}
