package dev.jrpg.battle;

import java.util.List;

/**
 * Итог действия, посчитанный ядром. Адаптер «проигрывает» его анимацией по sequenceId
 * и показывает цифры из hits в момент шага "hit". combos — id сработавших комбо стихий ({@link Combo}).
 */
public record ActionResult(Combatant actor, String actionName, String sequenceId,
                           List<Hit> hits, boolean fleeAttempt, boolean fled,
                           Battle.Reaction reaction, Hit counter, Combatant counterBy) {

    public ActionResult(Combatant actor, String actionName, String sequenceId,
                        List<Hit> hits, boolean fleeAttempt, boolean fled) {
        this(actor, actionName, sequenceId, hits, fleeAttempt, fled, Battle.Reaction.NONE, null, null);
    }

    /** Ответ партии на удар врага: NONE, если не было или не удался. counter — контратака после парирования. */
    public Battle.Reaction reaction() { return reaction == null ? Battle.Reaction.NONE : reaction; }

    public record Hit(Combatant target, int damage, int heal, int mpRestore, boolean crit,
                      List<String> statusesApplied, List<String> statusesCured, boolean killed,
                      List<String> combos) {
    }
}
