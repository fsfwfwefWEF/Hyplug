package dev.jrpg.battle;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** 0.10: внешнее кольцо сетки, новые пассивки, ИИ с оценкой ходов, рост врагов с уровнем партии. */
class SmarterEnemiesTest {

    private static Combatant unit(String id, Side side, int hp, int atk, int def, List<String> abilities) {
        return new Combatant(id, id, side, new Stats(hp, 50, atk, atk, def, 10), abilities);
    }

    private static Battle battle(Content c, List<Combatant> all) {
        return new Battle(c, all, new java.util.HashMap<>(), new BattleListener() {}, 3);
    }

    @Test
    void outerRingIsReachableAndCostsMore() {
        assertEquals(37, Progression.Grid.SIZE);
        for (int i = 25; i < 37; i++) {
            assertEquals(4, Progression.Grid.ring(i));
            assertEquals(8, Progression.Grid.minLevel(i));
        }
        // внешний узел на одном луче со вторым или третьим кольцом
        assertTrue(Progression.Grid.neighbors(25).contains(7));
        assertTrue(Progression.Grid.neighbors(26).contains(19));
        assertEquals(List.of(Progression.Role.TANK, Progression.Role.HEALER), Progression.Grid.sector(28));
    }

    @Test
    void aiFinishesOffNearlyDeadHero() {
        Content c = Content.demo();
        Combatant enemy = unit("e", Side.ENEMY, 100, 20, 5, List.of());
        Combatant healthy = unit("tank", Side.PARTY, 200, 10, 5, List.of());
        Combatant dying = unit("mage", Side.PARTY, 100, 10, 5, List.of());
        dying.hp = 8;
        Battle b = battle(c, List.of(enemy, healthy, dying));
        int hits = 0;
        for (int seed = 0; seed < 20; seed++) {
            BattleAction a = new EnemyAI(c, new Random(seed)).choose(b, enemy);
            if (a instanceof BattleAction.Attack at && at.targetId().equals("mage")) hits++;
        }
        assertTrue(hits >= 18, "добивает раненого: " + hits + "/20");
    }

    @Test
    void aiHealsHurtAllyAndRespectsTaunt() {
        Content c = Content.demo();
        Combatant healer = unit("slime", Side.ENEMY, 100, 10, 5, List.of("goo_mend"));
        Combatant hurt = unit("goblin", Side.ENEMY, 100, 10, 5, List.of());
        hurt.hp = 20;
        Combatant tank = unit("bram", Side.PARTY, 200, 10, 5, List.of());
        Combatant mage = unit("lyra", Side.PARTY, 60, 10, 5, List.of());
        mage.hp = 10;
        Battle.applyStatus(tank, c.status("taunt"), 2, false);
        Battle b = battle(c, List.of(healer, hurt, tank, mage));
        BattleAction a = new EnemyAI(c, new Random(1)).choose(b, healer);
        assertEquals(new BattleAction.UseAbility("goo_mend", "goblin"), a);

        Combatant brute = unit("trork", Side.ENEMY, 100, 30, 5, List.of());
        Battle b2 = battle(c, List.of(brute, tank, mage));
        BattleAction hit = new EnemyAI(c, new Random(2)).choose(b2, brute);
        assertEquals(new BattleAction.Attack("bram"), hit, "провокация: только в танка");
    }

    @Test
    void thornsReflectAndSecondChanceSaves() {
        Content c = Content.demo();
        Combatant spiky = unit("bram", Side.PARTY, 300, 10, 0, List.of());
        spiky.passives.add(Passives.THORNS);
        Combatant brute = new Combatant("trork", "trork", Side.ENEMY, new Stats(300, 0, 60, 0, 0, 500), List.of()); // ходит первым
        Battle b = battle(c, List.of(spiky, brute));
        b.start(Battle.Opening.NORMAL);
        assertTrue(brute.hp < 300, "шипы вернули урон: " + brute.hp);

        Combatant lucky = unit("lyra", Side.PARTY, 10, 10, 0, List.of());
        lucky.passives.add(Passives.SECOND_CHANCE);
        Combatant killer = new Combatant("wolf", "wolf", Side.ENEMY, new Stats(300, 0, 80, 0, 0, 500), List.of());
        Battle b2 = battle(c, List.of(lucky, killer));
        b2.start(Battle.Opening.NORMAL);
        assertEquals(1, lucky.hp, "второй шанс оставил 1 HP");
        assertTrue(lucky.secondChanceUsed);
    }

    @Test
    void enemiesGrowWithPartyLevel() throws Exception {
        GameData d = GameData.load(Content.demo(), null);
        EnemyTemplate wolf = d.enemy("wolf");
        int l1 = d.spawnEnemy(wolf, "w", 1).stats.maxHp();
        int l5 = d.spawnEnemy(wolf, "w", 5).stats.maxHp();
        assertTrue(l1 > wolf.stats().maxHp(), "сложность крепче базы");
        assertTrue(l5 > l1, "растут с уровнем: " + l1 + " → " + l5);
        d.setDifficulty(GameData.Difficulty.NONE);
        assertEquals(wolf.stats().maxHp(), d.spawnEnemy(wolf, "w", 5).stats.maxHp());
    }

    @Test
    void heroesHaveFullOuterRing() {
        Content c = Content.demo();
        for (Progression.HeroDef h : c.heroes()) {
            for (int i = 25; i < 37; i++) {
                Progression.GridNode n = h.grid().get(i);
                switch (n.kind()) {
                    case ABILITY -> c.ability(n.ref());
                    case PASSIVE -> c.passive(n.ref());
                    default -> { }
                }
            }
        }
    }
}
