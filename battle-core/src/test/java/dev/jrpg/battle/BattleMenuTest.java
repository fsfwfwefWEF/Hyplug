package dev.jrpg.battle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BattleMenuTest {

    private static Battle partyFirst() {
        List<Combatant> all = new ArrayList<>(Content.demoParty());
        all.add(new Combatant("e1", "Враг1", Side.ENEMY, new Stats(200, 0, 5, 0, 5, 1), List.of()));
        all.add(new Combatant("e2", "Враг2", Side.ENEMY, new Stats(200, 0, 5, 0, 5, 1), List.of()));
        Battle b = new Battle(Content.demo(), all, Content.demoInventory(), new BattleListener() {}, 3);
        b.start(Battle.Opening.PARTY_ADVANTAGE);
        return b;
    }

    @Test
    void mainMenuHasFiveActions() {
        Battle b = partyFirst();
        BattleMenu m = new BattleMenu(b);
        assertEquals(BattleMenu.Screen.MAIN, m.screen());
        assertEquals(List.of("Атака", "Способности", "Предметы", "Защита", "Бегство"),
                m.options().stream().map(BattleMenu.Option::label).toList());
    }

    @Test
    void attackAsksForTargetThenSubmits() {
        Battle b = partyFirst();
        BattleMenu m = new BattleMenu(b);
        assertNull(m.click(0));
        assertEquals(BattleMenu.Screen.TARGET, m.screen());
        assertEquals(2, m.options().size());
        BattleAction a = m.click(1);
        assertEquals(new BattleAction.Attack("e2"), a);
        b.submit(a);
        assertEquals(Battle.Phase.PRESENTING, b.phase());
    }

    @Test
    void backReturnsFromTargetToSkills() {
        Battle b = partyFirst();
        BattleMenu m = new BattleMenu(b);
        m.click(1);
        assertEquals(BattleMenu.Screen.SKILLS, m.screen());
        // первая способность с выбором цели-врага
        int idx = -1;
        for (int i = 0; i < b.current().abilities.size(); i++) {
            Ability a = b.content().ability(b.current().abilities.get(i));
            if (a.target() == TargetType.SINGLE_ENEMY && b.canAfford(b.current(), a)) { idx = i; break; }
        }
        if (idx >= 0) {
            assertNull(m.click(idx));
            assertEquals(BattleMenu.Screen.TARGET, m.screen());
            m.back();
            assertEquals(BattleMenu.Screen.SKILLS, m.screen());
        }
        m.back();
        assertEquals(BattleMenu.Screen.MAIN, m.screen());
    }

    @Test
    void itemsMenuUsesPotionOnAlly() {
        Battle b = partyFirst();
        BattleMenu m = new BattleMenu(b);
        b.current().hp = 10;
        m.click(2);
        assertEquals(BattleMenu.Screen.ITEMS, m.screen());
        int potion = m.options().stream().map(BattleMenu.Option::label).toList().indexOf(
                m.options().stream().map(BattleMenu.Option::label).filter(l -> l.startsWith("Зелье")).findFirst().orElseThrow());
        assertNull(m.click(potion));
        assertEquals(BattleMenu.Screen.TARGET, m.screen());
        int self = b.alive(Side.PARTY).indexOf(b.current());
        BattleAction a = m.click(self);
        assertInstanceOf(BattleAction.UseItem.class, a);
        b.submit(a);
        assertTrue(b.byId(((BattleAction.UseItem) a).targetId()).hp > 10);
    }

    @Test
    void unaffordableSkillShowsError() {
        Battle b = partyFirst();
        BattleMenu m = new BattleMenu(b);
        b.current().ap = 0;
        b.current().mp = 0;
        m.click(1);
        int locked = -1;
        for (int i = 0; i < m.options().size(); i++) if (!m.options().get(i).enabled()) { locked = i; break; }
        assertTrue(locked >= 0);
        assertNull(m.click(locked));
        assertNotNull(m.error());
    }

    @Test
    void textHelpersDoNotCrash() {
        Battle b = partyFirst();
        assertTrue(BattleText.side(b, Side.PARTY).contains("Герой"));
        assertTrue(BattleText.queue(b).startsWith("Очередь"));
    }

    @Test
    void viewOtherHeroShowsTheirAbilitiesReadOnly() {
        Battle b = partyFirst();
        BattleMenu m = new BattleMenu(b);
        Combatant other = b.alive(Side.PARTY).stream().filter(c -> c != b.current()).findFirst().orElseThrow();
        assertTrue(m.view(other.id));
        assertEquals(other, m.viewed());
        assertTrue(m.title().contains(other.name));
        assertEquals(other.abilities.size() + 1, m.options().size());
        assertNull(m.click(0), "осмотр не ходит");
        assertNotNull(m.error());
        assertTrue(m.canGoBack());
        m.back();
        assertNull(m.viewed());
        assertEquals(BattleMenu.Screen.MAIN, m.screen());
        assertFalse(m.view(b.current().id), "свой текущий герой — обычное меню");
    }
}
