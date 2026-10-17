package dev.jrpg.battle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ReactionSaveLootTest {

    private record Run(Battle battle, Combatant hero, Combatant enemy, List<ActionResult> results) {}

    /** Враг быстрее героя: бой начинается с его замаха. */
    private static Run enemyFirst() {
        Combatant hero = new Combatant("hero", "Герой", Side.PARTY, new Stats(200, 50, 20, 20, 5, 5), List.of());
        Combatant enemy = new Combatant("enemy", "Враг", Side.ENEMY, new Stats(200, 0, 30, 0, 5, 30), List.of());
        List<ActionResult> results = new ArrayList<>();
        Battle b = new Battle(Content.demo(), List.of(hero, enemy), Content.demoInventory(), new BattleListener() {
            @Override
            public void onActionExecuted(ActionResult r) { results.add(r); }
        }, 11);
        b.setReactions(true);
        b.start(Battle.Opening.NORMAL);
        return new Run(b, hero, enemy, results);
    }

    @Test
    void enemyAttackWaitsForReaction() {
        Run r = enemyFirst();
        assertEquals(Battle.Phase.AWAIT_REACTION, r.battle().phase());
        assertEquals(BattleMenu.Screen.REACTION, new BattleMenu(r.battle()).screen());
        assertTrue(r.results().isEmpty());
        r.battle().react(Battle.Reaction.NONE);
        assertEquals(Battle.Phase.PRESENTING, r.battle().phase());
        assertTrue(r.hero().hp < 200);
    }

    @Test
    void dodgeHalvesDamage() {
        Run plain = enemyFirst(), dodged = enemyFirst();
        plain.battle().react(Battle.Reaction.NONE);
        dodged.battle().react(Battle.Reaction.DODGE);
        int full = 200 - plain.hero().hp, half = 200 - dodged.hero().hp;
        assertEquals(Math.max(1, Math.round(full * Battle.DODGE_MULT)), half);
        assertEquals(Battle.Reaction.DODGE, dodged.results().get(0).reaction());
    }

    @Test
    void parryBlocksAndCounters() {
        Run r = enemyFirst();
        r.battle().react(Battle.Reaction.PARRY);
        assertEquals(200, r.hero().hp);
        ActionResult res = r.results().get(0);
        assertNotNull(res.counter());
        assertEquals(r.hero(), res.counterBy());
        assertEquals(200 - res.counter().damage(), r.enemy().hp);
        assertTrue(BattleText.describe(res).contains("Парирование"));
    }

    @Test
    void reactionsOffByDefault() {
        Combatant hero = new Combatant("hero", "Герой", Side.PARTY, new Stats(200, 50, 20, 20, 5, 5), List.of());
        Combatant enemy = new Combatant("enemy", "Враг", Side.ENEMY, new Stats(200, 0, 30, 0, 5, 30), List.of());
        Battle b = new Battle(Content.demo(), List.of(hero, enemy), Content.demoInventory(), new BattleListener() {}, 11);
        b.start(Battle.Opening.NORMAL);
        assertEquals(Battle.Phase.PRESENTING, b.phase());
    }

    @Test
    void saveRoundTrip() {
        Content content = Content.demo();
        Progression.Party party = new Progression.Party(content);
        Progression.Hero h = party.heroes().get(0);
        h.addExp(500);
        int lvl = h.level;
        assertNull(h.unlock(1));
        Map<String, Integer> inv = new LinkedHashMap<>(Map.of("potion", 4, "wolf_fang", 2, "removed_item", 3));
        String json = SaveData.writePlayer(party, inv, Map.of("follow", false));

        SaveData.Player save = SaveData.readPlayer(json);
        Progression.Party loaded = new Progression.Party(content);
        SaveData.applyParty(save, loaded);
        Progression.Hero l = loaded.hero(h.def.id());
        assertEquals(lvl, l.level);
        assertEquals(h.exp, l.exp);
        assertEquals(h.points, l.points);
        assertEquals(h.unlocked, l.unlocked);
        assertEquals(Map.of("potion", 4, "wolf_fang", 2), SaveData.inventory(save, content));
        assertFalse(save.flag("follow", true));

        WorldProgress wp = new WorldProgress();
        wp.defeatedUnique.add("boss");
        wp.clearedAtMillis.put("camp", 123456789012L);
        WorldProgress back = new WorldProgress();
        SaveData.readWorld(SaveData.writeWorld(wp), back);
        assertEquals(wp.defeatedUnique, back.defeatedUnique);
        assertEquals(wp.clearedAtMillis, back.clearedAtMillis);
    }

    @Test
    void jsonWriterEscapes() {
        String s = Json.write(Map.of("a", "кв\"от\\а\n", "b", List.of(1, 2.5, true)));
        Map<String, Object> m = Json.obj(Json.parse(s));
        assertEquals("кв\"от\\а\n", m.get("a"));
        assertEquals(List.of(1.0, 2.5, true), m.get("b"));
    }

    @Test
    void enemiesDropLoot() throws Exception {
        GameData data = GameData.load(Content.demo(), null);
        EnemyTemplate wolf = data.enemy("wolf");
        Random rnd = new Random(1);
        int fangs = 0;
        for (int i = 0; i < 50; i++) fangs += data.rollLoot(wolf, rnd).getOrDefault("wolf_fang", 0);
        assertTrue(fangs > 20 && fangs < 50, "клык ~70%: " + fangs);
        assertFalse(data.content().item("wolf_fang").usable());
        assertFalse(wolf.anim("attack").isEmpty());
    }

    @Test
    void backpackFiltersAndSorts() {
        Content c = Content.demo();
        Map<String, Integer> inv = new LinkedHashMap<>(Map.of("potion", 2, "wolf_fang", 5, "bone", 1, "bomb", 0, "nope", 3));
        List<Backpack.Entry> all = Backpack.list(c, inv, null, Backpack.Sort.KIND);
        assertEquals(List.of("potion", "wolf_fang", "bone"), all.stream().map(e -> e.item().id()).toList());
        List<Backpack.Entry> mats = Backpack.list(c, inv, Item.Kind.MATERIAL, Backpack.Sort.COUNT);
        assertEquals(List.of("wolf_fang", "bone"), mats.stream().map(e -> e.item().id()).toList());
    }
}
