package dev.jrpg.battle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.10.3: иконки снаряжения по типу и слоту, рамки уровня у вещей и способностей. */
class IconTiersTest {

    @Test
    void gearGetsTypeAndSlotIcons() throws Exception {
        GameData d = GameData.load(Content.demo(), null);
        Content c = d.content();
        assertEquals("gear_axe", Describe.gearIcon(c.gear("iron_battleaxe")));
        assertEquals("gear_sword", Describe.gearIcon(c.gear("iron_sword")));
        assertEquals("gear_helm", Describe.gearIcon(c.gear("iron_helm")));
        assertEquals("gear_ring", Describe.gearIcon(c.gear("sage_ring")));
        assertEquals("gear_amulet", Describe.gearIcon(c.gear("wind_amulet")));
        assertEquals("gear_boots", Describe.itemIcon(c, c.item("leather_boots")));
        assertEquals(0, Describe.gearTier(c.gear("iron_sword")));
    }

    @Test
    void outerRingAbilitiesHaveHigherTier() {
        Content c = Content.demo();
        int outer = 0;
        for (Progression.HeroDef h : c.heroes()) {
            for (int i = 25; i < 37; i++) {
                Progression.GridNode n = h.grid().get(i);
                if (n.kind() == Progression.NodeKind.ABILITY && Describe.abilityTier(c, n.ref()) == 3) outer++;
            }
        }
        assertTrue(outer > 0, "у внешнего кольца рамка 3");
        assertEquals(0, Describe.abilityTier(c, Content.ATTACK));
    }
}
