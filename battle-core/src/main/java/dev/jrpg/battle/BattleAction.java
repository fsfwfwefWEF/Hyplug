package dev.jrpg.battle;

/** Команда на ход: то, что выбрал игрок в меню или ИИ. targetId может быть null для групповых/на себя. */
public sealed interface BattleAction {
    record Attack(String targetId) implements BattleAction {}
    record UseAbility(String abilityId, String targetId) implements BattleAction {}
    record UseItem(String itemId, String targetId) implements BattleAction {}
    record Defend() implements BattleAction {}
    record Flee() implements BattleAction {}
}
