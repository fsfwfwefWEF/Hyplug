package dev.jrpg.battle;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentTest {

    private static Content loaded() throws Exception {
        Content c = Content.demo();
        GameData.load(c, null);
        return c;
    }

    @Test
    void startGearAndStats() throws Exception {
        Content c = loaded();
        Progression.Party party = new Progression.Party(c);
        Progression.Hero bram = party.hero("bram");
        assertEquals("iron_battleaxe", bram.equipped.get(Equipment.Slot.WEAPON));
        Stats base = bram.statsFor(Map.of());
        Stats full = bram.stats();
        assertTrue(full.def() > base.def(), "броня даёт защиту");
        assertTrue(full.maxHp() > base.maxHp());
        assertTrue(bram.abilities().contains("armor_split"), "навык секиры");
        Combatant cb = party.buildParty(c).stream().filter(x -> x.id.equals("bram")).findFirst().orElseThrow();
        assertEquals(8 * (1.0 + 0.5 * 0.1), cb.weaponPhys, 1e-9);
    }

    @Test
    void equipSwapsWithBackpackAndTwoHandedFreesOffhand() throws Exception {
        Content c = loaded();
        Progression.Hero hero = new Progression.Party(c).hero("hero");
        Map<String, Integer> inv = new LinkedHashMap<>(Map.of("copper_daggers", 1));
        assertNotNull(hero.equipped.get(Equipment.Slot.OFFHAND));
        assertNull(Equipment.equip(c, hero, Equipment.Slot.WEAPON, "copper_daggers", inv));
        assertEquals("copper_daggers", hero.equipped.get(Equipment.Slot.WEAPON));
        assertNull(hero.equipped.get(Equipment.Slot.OFFHAND), "двуручное снимает щит");
        assertEquals(1, inv.get("iron_sword"));
        assertEquals(1, inv.get("copper_shield"));
        assertNull(inv.get("copper_daggers"));
        assertNotNull(Equipment.equip(c, hero, Equipment.Slot.OFFHAND, "copper_shield", inv), "щит к двуручному нельзя");
        assertNotNull(Equipment.unequip(hero, Equipment.Slot.WEAPON, inv), "оружие только менять");
        assertNotNull(Equipment.equip(c, hero, Equipment.Slot.HEAD, "iron_sword", inv), "меч не шлем");
    }

    @Test
    void accessoriesGoToBothSlotsAndGiveImmunity() throws Exception {
        Content c = loaded();
        Progression.Party party = new Progression.Party(c);
        Progression.Hero lyra = party.hero("lyra");
        Map<String, Integer> inv = new LinkedHashMap<>(Map.of("antidote_ring", 1, "wind_amulet", 1));
        assertNull(Equipment.equip(c, lyra, Equipment.Slot.ACC1, "antidote_ring", inv));
        assertNull(Equipment.equip(c, lyra, Equipment.Slot.ACC2, "wind_amulet", inv));
        Combatant cb = party.buildParty(c).stream().filter(x -> x.id.equals("lyra")).findFirst().orElseThrow();
        assertTrue(cb.immunities.contains("poison"));
        List<Equipment.Diff> d = Equipment.compare(c, lyra, Equipment.Slot.ACC2, null);
        assertTrue(d.stream().anyMatch(x -> x.label().equals("Скорость") && x.better() < 0));
    }

    @Test
    void weaponRaisesDamageAndColorsAttack() throws Exception {
        Content c = loaded();
        Combatant plain = new Combatant("a", "A", Side.PARTY, new Stats(100, 0, 20, 0, 0, 50), List.of());
        Combatant armed = new Combatant("a", "A", Side.PARTY, new Stats(100, 0, 20, 0, 0, 50), List.of());
        armed.weaponPhys = 10;
        armed.weaponElement = Element.FIRE;
        int dPlain = hit(c, plain), dArmed = hit(c, armed);
        assertTrue(dArmed > dPlain * 1.3, dPlain + " → " + dArmed);
    }

    private static int hit(Content c, Combatant actor) {
        Combatant enemy = new Combatant("e", "E", Side.ENEMY, new Stats(1000, 0, 1, 0, 0, 1), List.of());
        Battle b = new Battle(c, List.of(actor, enemy), new LinkedHashMap<>(), new BattleListener() {}, 3);
        b.start(Battle.Opening.NORMAL);
        b.submit(new BattleAction.Attack("e"));
        return 1000 - enemy.hp;
    }

    @Test
    void saveKeepsEquipment() throws Exception {
        Content c = loaded();
        Progression.Party party = new Progression.Party(c);
        Map<String, Integer> inv = new LinkedHashMap<>(Map.of("sage_ring", 1));
        assertNull(Equipment.equip(c, party.hero("lyra"), Equipment.Slot.ACC1, "sage_ring", inv));
        String json = SaveData.writePlayer(party, inv, Map.of());
        Progression.Party back = new Progression.Party(c);
        SaveData.applyParty(SaveData.readPlayer(json), back);
        assertEquals("sage_ring", back.hero("lyra").equipped.get(Equipment.Slot.ACC1));
        assertEquals(party.hero("bram").equipped, back.hero("bram").equipped);
    }
}
