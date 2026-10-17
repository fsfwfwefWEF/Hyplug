package dev.jrpg.battle;

import java.util.List;

/**
 * Точка связи ядра с адаптером (Hytale, консоль, тесты). Ядро сообщает, что произошло,
 * адаптер решает, как это показать: камера, частицы, анимации, UI.
 */
public interface BattleListener {
    default void onBattleStart(Battle battle) {}

    /** Начался ход actor; forecast — прогноз следующих ходов (для таймлайна в HUD). */
    default void onTurnStart(Combatant actor, List<Combatant> forecast) {}

    /** Сработал статус: delta < 0 — урон, > 0 — лечение. */
    default void onStatusTick(Combatant target, StatusInstance status, int delta) {}

    default void onStatusExpired(Combatant target, StatusInstance status) {}

    default void onTurnSkipped(Combatant actor, String reason) {}

    /** Ход персонажа партии: открыть меню (Атака/Способности/Предметы/Защита/Бегство). */
    default void onAwaitCommand(Combatant actor) {}

    /** Действие посчитано. Адаптер играет анимацию и вызывает {@link Battle#acknowledge()}. */
    default void onActionExecuted(ActionResult result) {}

    default void onCombatantDown(Combatant combatant) {}

    default void onBattleEnd(Battle.Outcome outcome) {}

    /** Новый боец появился посреди боя: адаптер спавнит NPC с эффектом появления. */
    default void onSummoned(Combatant c) {}

    /** Реплика (диалог босса и т.п.). speaker может быть null — текст рассказчика. */
    default void onDialogue(Combatant speaker, String text) {}

    /** Босс перешёл в новую фазу: катсцена/смена музыки/эффект. */
    default void onPhaseChange(Combatant boss, String phaseName) {}
}
