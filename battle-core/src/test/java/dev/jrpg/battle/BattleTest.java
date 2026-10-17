package dev.jrpg.battle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BattleTest {

    private static Combatant dummy(String id, Side side, int spd) {
        return new Combatant(id, id, side, new Stats(100, 50, 20, 20, 5, spd), List.of());
    }

    private static Battle battle(List<Combatant> all, BattleListener l) {
        return new Battle(Content.demo(), all, Content.demoInventory(), l, 7);
    }

    @Test
    void fasterCombatantActsMoreOften() {
        Combatant fast = dummy("fast", Side.PARTY, 20);
        Combatant slow = dummy("slow", Side.ENEMY, 10);
        List<Combatant> order = TurnQueue.forecast(List.of(fast, slow), 9);
        long fastTurns = order.stream().filter(c -> c == fast).count();
        assertEquals(6, fastTurns, "скорость 20 против 10 → вдвое больше ходов: " + order);
    }

    @Test
    void hasteChangesForecast() {
        Combatant a = dummy("a", Side.PARTY, 10);
        Combatant b = dummy("b", Side.ENEMY, 10);
        Battle.applyStatus(a, Content.demo().status("haste"), 3, false);
        long aTurns = TurnQueue.forecast(List.of(a, b), 10).stream().filter(c -> c == a).count();
        assertEquals(6, aTurns);
    }

    @Test
    void poisonTicksAtTurnStartAndExpires() {
        Combatant hero = dummy("hero", Side.PARTY, 10);
        Combatant enemy = dummy("enemy", Side.ENEMY, 1);
        Battle.applyStatus(hero, Content.demo().status("poison"), 2, false);
        List<Integer> ticks = new ArrayList<>();
        Battle b = battle(List.of(hero, enemy), new BattleListener() {
            @Override
            public void onStatusTick(Combatant t, StatusInstance s, int delta) {
                ticks.add(delta);
            }
        });
        b.start(Battle.Opening.NORMAL);
        assertEquals(hero, b.current());
        assertEquals(List.of(-8), ticks);
        b.submit(new BattleAction.Defend());
        b.acknowledge();
        assertEquals(hero, b.current());
        assertEquals(List.of(-8, -8), ticks);
        b.submit(new BattleAction.Defend());
        b.acknowledge();
        assertNull(hero.status("poison"), "яд на 2 хода должен закончиться");
        assertEquals(2, ticks.size());
    }

    @Test
    void guardLastsUntilNextOwnTurn() {
        Combatant hero = dummy("hero", Side.PARTY, 10);
        Combatant enemy = dummy("enemy", Side.ENEMY, 1);
        Battle b = battle(List.of(hero, enemy), new BattleListener() {});
        b.start(Battle.Opening.NORMAL);
        b.submit(new BattleAction.Defend());
        b.acknowledge();
        assertNotNull(hero.status("guard"), "Защита не должна слетать в конце того же хода");
    }

    @Test
    void abilityCostsApAndAttackGivesAp() {
        List<Combatant> all = new ArrayList<>(Content.demoParty());
        Combatant goblin = dummy("goblin", Side.ENEMY, 1);
        all.add(goblin);
        Battle b = battle(all, new BattleListener() {});
        b.start(Battle.Opening.NORMAL);
        Combatant actor = b.current();
        int apBefore = actor.ap;
        b.submit(new BattleAction.Attack("goblin"));
        assertEquals(apBefore + 1, actor.ap);
        assertTrue(goblin.hp < 100);
    }

    @Test
    void cannotUseAbilityWithoutAp() {
        List<Combatant> all = new ArrayList<>(Content.demoParty());
        all.add(dummy("goblin", Side.ENEMY, 1));
        Battle b = battle(all, new BattleListener() {});
        b.start(Battle.Opening.NORMAL);
        Combatant actor = b.current();
        actor.ap = 0;
        String expensive = actor.abilities.stream()
                .filter(id -> Content.demo().ability(id).apCost() > 0).findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class,
                () -> b.submit(new BattleAction.UseAbility(expensive, "goblin")));
    }

    @Test
    void itemsAreConsumed() {
        Combatant hero = dummy("hero", Side.PARTY, 10);
        hero.hp = 10;
        Battle b = battle(List.of(hero, dummy("enemy", Side.ENEMY, 1)), new BattleListener() {});
        b.start(Battle.Opening.NORMAL);
        b.submit(new BattleAction.UseItem("potion", "hero"));
        assertEquals(50, hero.hp);
        assertEquals(2, b.inventory().get("potion"));
    }

    @Test
    void demoBattlesAlwaysFinish() {
        for (long seed = 0; seed < 50; seed++) {
            List<Combatant> all = new ArrayList<>(Content.demoParty());
            all.addAll(Content.demoEnemies());
            Content content = Content.demo();
            Battle b = new Battle(content, all, Content.demoInventory(), new BattleListener() {}, seed);
            EnemyAI pilot = new EnemyAI(content, new java.util.Random(seed));
            b.start(Battle.Opening.NORMAL);
            int guard = 0;
            while (b.phase() != Battle.Phase.ENDED && guard++ < 1000) {
                if (b.phase() == Battle.Phase.PRESENTING) b.acknowledge();
                else b.submit(pilot.choose(b, b.current()));
            }
            assertEquals(Battle.Phase.ENDED, b.phase(), "seed " + seed);
        }
    }
}
