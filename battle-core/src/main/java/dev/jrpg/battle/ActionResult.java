package dev.jrpg.battle;

import java.util.List;

/**
 * Итог действия, посчитанный ядром. Адаптер «проигрывает» его анимацией по sequenceId
 * и показывает цифры из hits в момент шага "hit". combos — id сработавших комбо стихий ({@link Combo}).
 */
public record ActionResult(Combatant actor, String actionName, String sequenceId,
                           List<Hit> hits, boolean fleeAttempt, boolean fled) {

    public record Hit(Combatant target, int damage, int heal, int mpRestore, boolean crit,
                      List<String> statusesApplied, List<String> statusesCured, boolean killed,
                      List<String> combos) {
    }
}
