package dev.jrpg.battle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EncounterTest {
    private final GameData data = load();

    private static GameData load() {
        try {
            return GameData.load(Content.demo(), null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Battle bossBattle(List<String> log) {
        Encounter e = data.encounter("spider_queen_lair");
        return data.createBattle(e, Content.demoParty(), Content.demoInventory(), new BattleListener() {
            @Override public void onDialogue(Combatant s, String text) { log.add("say:" + text); }
            @Override public void onSummoned(Combatant c) { log.add("summon:" + c.templateId); }
            @Override public void onPhaseChange(Combatant b, String name) { log.add("phase:" + name); }
        }, 1);
    }

    private static Combatant queen(Battle b) {
        return b.combatants().stream().filter(c -> "spider_queen".equals(c.templateId)).findFirst().orElseThrow();
    }

    @Test
    void encountersHaveFixedPlacesFromJson() {
        Encounter e = data.encounter("spider_queen_lair");
        assertTrue(e.unique());
        assertEquals(310, e.location().x());
        assertEquals(3, e.enemies().size());
    }

    @Test
    void introAndBroodShieldOnStart() {
        List<String> log = new ArrayList<>();
        Battle b = bossBattle(log);
        b.start(Battle.Opening.NORMAL);
        assertTrue(log.contains("say:Кто посмел потревожить моё логово?"));
        assertNotNull(queen(b).status("brood_shield"), "пока живы паучата, королева под защитой");
    }

    @Test
    void phaseTriggersOnceAndSummons() {
        List<String> log = new ArrayList<>();
        Battle b = bossBattle(log);
        b.start(Battle.Opening.NORMAL);
        Combatant q = queen(b);
        int before = b.combatants().size();
        q.hp = (int) (q.stats.maxHp() * 0.5);
        drive(b, 1);
        assertTrue(log.contains("phase:Кислотный дождь"), log.toString());
        assertTrue(q.abilities.contains("acid_rain"));
        assertEquals(before + 2, b.combatants().size());
        assertEquals(1, log.stream().filter(s -> s.equals("phase:Кислотный дождь")).count());
    }

    @Test
    void bossIsImmuneToStunAndCannotFlee() {
        Battle b = bossBattle(new ArrayList<>());
        b.start(Battle.Opening.NORMAL);
        Combatant q = queen(b);
        while (b.phase() != Battle.Phase.AWAIT_COMMAND) b.acknowledge();
        assertThrows(IllegalArgumentException.class, () -> b.submit(new BattleAction.Flee()));
        assertTrue(q.immunities.contains("stun"));
    }

    @Test
    void uniqueBossDoesNotRespawnButRegularDoes() {
        WorldProgress p = new WorldProgress();
        Encounter boss = data.encounter("spider_queen_lair");
        Encounter mill = data.encounter("mill_goblins");
        p.markCleared(boss, 0);
        p.markCleared(mill, 0);
        assertFalse(p.isAvailable(boss, Long.MAX_VALUE / 2));
        assertFalse(p.isAvailable(mill, 60_000));
        assertTrue(p.isAvailable(mill, 20 * 60_000));
    }

    @Test
    void bossLootIsGuaranteed() {
        var loot = data.rollLoot(data.encounter("spider_queen_lair"), new java.util.Random(0));
        assertEquals(1, loot.get("queen_fang"));
    }

    /** Прогоняет бой, пока не будет сделано n действий (партия бьёт атакой первую цель). */
    private static void drive(Battle b, int actions) {
        int done = 0;
        while (b.phase() != Battle.Phase.ENDED && done < actions) {
            if (b.phase() == Battle.Phase.PRESENTING) {
                b.acknowledge();
                done++;
            } else {
                b.submit(new BattleAction.Attack(b.alive(Side.ENEMY).get(0).id));
            }
        }
    }
}
