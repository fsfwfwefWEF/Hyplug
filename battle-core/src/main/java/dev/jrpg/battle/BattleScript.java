package dev.jrpg.battle;

/**
 * Скрипт конкретной встречи: фазы босса и эксклюзивные механики.
 * Простые вещи (фазы по HP, реплики, призыв, новые способности) описываются в encounters.json
 * и исполняются {@link PhaseScript}. Уникальную логику пишут классом-реализацией (см. {@code scripts/}).
 */
public interface BattleScript {
    BattleScript NONE = new BattleScript() {};

    default void onBattleStart(Battle battle) {}

    /** Начало хода actor (после статусов, до выбора действия). */
    default void onTurnStart(Battle battle, Combatant actor) {}

    /** Подменить действие врага (например, «каждый 3-й ход — Кокон»). null — решает обычный ИИ. */
    default BattleAction overrideAction(Battle battle, Combatant enemy) { return null; }

    default void afterAction(Battle battle, ActionResult result) {}

    static BattleScript combine(BattleScript... scripts) {
        return new BattleScript() {
            @Override public void onBattleStart(Battle b) { for (var s : scripts) s.onBattleStart(b); }
            @Override public void onTurnStart(Battle b, Combatant a) { for (var s : scripts) s.onTurnStart(b, a); }
            @Override public void afterAction(Battle b, ActionResult r) { for (var s : scripts) s.afterAction(b, r); }
            @Override public BattleAction overrideAction(Battle b, Combatant e) {
                for (var s : scripts) {
                    BattleAction a = s.overrideAction(b, e);
                    if (a != null) return a;
                }
                return null;
            }
        };
    }
}
