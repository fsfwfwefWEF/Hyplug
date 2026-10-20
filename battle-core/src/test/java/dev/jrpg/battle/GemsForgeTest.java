package dev.jrpg.battle;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** 0.15.0: качества, гнёзда, камни, кузнец. */
class GemsForgeTest {

    private static GameData data() throws Exception {
        return GameData.load(Content.demo(), null);
    }

    @Test
    void variantScalesAndKeepsSaves() throws Exception {
        Content c = data().content();
        Equipment.Gear base = c.gear("iron_sword");
        assertEquals(0, base.sockets());
        String id = Equipment.variantId("iron_sword", Equipment.Quality.EPIC.ordinal(), java.util.List.of());
        Equipment.Gear epic = c.gear(id);
        assertEquals(Equipment.Quality.EPIC, epic.quality());
        assertEquals(2, epic.sockets());
        assertTrue(epic.power() > base.power());
        assertTrue(epic.skills().contains("bleeding_cut"), "навык эпического меча");
        assertEquals("iron_sword", epic.base());
        assertTrue(c.hasItem(id));
        assertEquals(Item.Kind.GEAR, c.item(id).kind());
        assertEquals(c.icon("iron_sword"), c.icon(id));
        assertFalse(c.hasGear("iron_sword@9"));
        assertFalse(c.hasGear("nope@1"));
    }

    @Test
    void insertExtractUpgradeMerge() throws Exception {
        Content c = data().content();
        Map<String, Integer> inv = new HashMap<>();
        inv.put("iron_sword", 1);
        inv.put("gem_ruby_1", 4);
        inv.put("bone", 20);
        Forge.Result r = Forge.insert(c, inv, Forge.Place.bag("iron_sword"), "gem_ruby_1", -1);
        assertNotNull(r.error(), "у обычной вещи нет гнёзд");
        r = Forge.upgrade(c, inv, Forge.Place.bag("iron_sword"));
        assertNull(r.error(), r.message());
        assertEquals(17, inv.get("bone"));
        String good = r.id();
        assertEquals(1, c.gear(good).sockets());
        int atk = c.gear(good).bonus().atk();
        r = Forge.insert(c, inv, Forge.Place.bag(good), "gem_ruby_1", -1);
        assertNull(r.error(), r.message());
        Equipment.Gear withGem = c.gear(r.id());
        assertEquals(atk + 2, withGem.bonus().atk());
        assertEquals("fire", withGem.element());
        assertEquals(3, inv.get("gem_ruby_1"));
        r = Forge.extract(c, inv, Forge.Place.bag(withGem.id()), 0);
        assertNull(r.error());
        assertEquals(4, inv.get("gem_ruby_1"), "камень не ломается");
        assertEquals(good, r.id());
        r = Forge.merge(c, inv, "gem_ruby_1");
        assertNull(r.error());
        assertEquals(1, inv.get("gem_ruby_1"));
        assertEquals(1, inv.get("gem_ruby_2"));
    }

    @Test
    void wornGearAndAccessoryImmunity() throws Exception {
        Content c = data().content();
        Progression.Party party = new Progression.Party(c);
        Progression.Hero hero = party.hero("hero");
        Map<String, Integer> inv = new HashMap<>();
        String ring = Equipment.variantId("sage_ring", Equipment.Quality.RARE.ordinal(), java.util.List.of());
        inv.put(ring, 1);
        inv.put("gem_ruby_2", 1);
        assertNull(Equipment.equip(c, hero, Equipment.Slot.ACC1, ring, inv));
        Forge.Result r = Forge.insert(c, inv, Forge.Place.worn(hero, Equipment.Slot.ACC1), "gem_ruby_2", 1);
        assertNull(r.error(), r.message());
        assertEquals(r.id(), hero.equipped.get(Equipment.Slot.ACC1));
        assertTrue(c.gear(r.id()).immunities().contains("burn"));
        assertTrue(Equipment.immunities(c, hero.equipped).contains("burn"));
    }

    @Test
    void eliteLootGivesGems() throws Exception {
        GameData d = data();
        Content c = d.content();
        Map<String, Integer> got = Gems.enrich(c, Map.of("iron_sword", 1), "boss", new Random(1));
        assertTrue(got.keySet().stream().anyMatch(k -> k.startsWith(Gems.PREFIX)));
        assertTrue(got.keySet().stream().allMatch(c::hasItem), got.toString());
        Squads.Template elite = d.squads().templates.stream().filter(Squads.Template::elite).findFirst().orElseThrow();
        Encounter e = d.squads().encounter(elite, "x", "default", 0, 0, 0);
        assertTrue(Squads.elite(e));
        assertEquals("elite", d.lootRule(e));
    }
}
