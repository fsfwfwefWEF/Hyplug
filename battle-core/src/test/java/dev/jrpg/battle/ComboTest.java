package dev.jrpg.battle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ComboTest {
    private final Content content = Content.demo();

    private Combatant mage() {
        return new Combatant("lyra", "Лира", Side.PARTY, new Stats(100, 200, 10, 30, 5, 30),
                List.of("thunder", "fireball", "water_jet", "ice_lance"));
    }

    private Combatant dummy() {
        return new Combatant("dummy", "Чучело", Side.ENEMY, new Stats(5000, 0, 1, 0, 0, 1), List.of());
    }

    private ActionResult cast(Combatant actor, Combatant target, String ability) {
        List<ActionResult> out = new ArrayList<>();
        Battle b = new Battle(content, List.of(actor, target), Content.demoInventory(), new BattleListener() {
            @Override
            public void onActionExecuted(ActionResult r) {
                out.add(r);
            }
        }, 3);
        b.start(Battle.Opening.NORMAL);
        assertEquals(actor, b.current());
        b.submit(new BattleAction.UseAbility(ability, target.id));
        return out.get(0);
    }

    @Test
    void thunderOnWetTargetElectrocutes() {
        Combatant lyra = mage(), dummy = dummy();
        Battle.applyStatus(dummy, content.status("wet"), 3, false);
        ActionResult r = cast(lyra, dummy, "thunder");
        ActionResult.Hit h = r.hits().get(0);
        assertEquals(List.of("electrocute"), h.combos());
        assertTrue(h.statusesApplied().contains("stun"));
        assertNull(dummy.status("wet"), "вода расходуется");
        assertTrue(BattleText.describe(r).contains("Электрошок"));
    }

    @Test
    void fireOnWetTargetMakesSteamAndNoBurn() {
        Combatant lyra = mage(), dummy = dummy();
        Battle.applyStatus(dummy, content.status("wet"), 3, false);
        ActionResult.Hit h = cast(lyra, dummy, "fireball").hits().get(0);
        assertEquals(List.of("steam"), h.combos());
        assertNull(dummy.status("burn"));
    }

    @Test
    void iceOnWetFreezesAndPhysicalShatters() {
        Combatant lyra = mage(), dummy = dummy();
        Battle.applyStatus(dummy, content.status("wet"), 3, false);
        ActionResult.Hit h = cast(lyra, dummy, "ice_lance").hits().get(0);
        assertEquals(List.of("freeze"), h.combos());
        assertNotNull(dummy.status("frozen"));

        Combatant hero = new Combatant("hero", "Герой", Side.PARTY, new Stats(100, 0, 30, 0, 5, 30), List.of());
        Combatant frozen = dummy();
        Battle.applyStatus(frozen, content.status("frozen"), 1, false);
        List<ActionResult> out = new ArrayList<>();
        Battle b = new Battle(content, List.of(hero, frozen), Content.demoInventory(), new BattleListener() {
            @Override
            public void onActionExecuted(ActionResult r) { out.add(r); }
        }, 3);
        b.start(Battle.Opening.NORMAL);
        b.submit(new BattleAction.Attack(frozen.id));
        assertEquals(List.of("shatter"), out.get(0).hits().get(0).combos());
        assertNull(frozen.status("frozen"));
    }

    @Test
    void fireOnPoisonExplodes() {
        Combatant lyra = mage(), dummy = dummy();
        Battle.applyStatus(dummy, content.status("poison"), 3, false);
        ActionResult.Hit h = cast(lyra, dummy, "fireball").hits().get(0);
        assertEquals(List.of("toxic_blast"), h.combos());
        assertTrue(h.damage() >= 600, "бонус 12% от 5000 HP: " + h.damage());
    }

    @Test
    void passivesWork() {
        Combatant lyra = mage(), dummy = dummy();
        lyra.passives.add(Passives.FIRST_STRIKE);
        lyra.passives.add(Passives.MANA_FLOW);
        lyra.mp = 0;
        Battle b = new Battle(content, List.of(lyra, dummy), Content.demoInventory(), new BattleListener() {}, 3);
        b.start(Battle.Opening.NORMAL);
        assertEquals(lyra, b.current());
        assertEquals(4, lyra.ap, "1 базовый + 2 первый удар + 1 за ход");
        assertEquals(3, lyra.mp, "поток маны");
    }
}
