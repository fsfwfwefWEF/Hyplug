package dev.jrpg.battle;

import java.util.List;

/**
 * Способность (и базовая атака, и магия, и эффект предмета).
 *
 * @param damagePower множитель урона (0 — без урона)
 * @param magical     считать от mag вместо atk
 * @param healPower   лечение в долях макс. HP цели (0.3 = 30%)
 * @param mpRestore   восстановление MP
 * @param cures       id статусов, которые снимаются
 * @param apGain      сколько AP получает исполнитель (базовая атака даёт +1)
 * @param weight      «вес» действия для CTB: после хода ct -= weight (100 — норма)
 * @param sequenceId  id таймлайна анимаций/частиц/камеры в адаптере (см. DESIGN.md §8)
 */
public record Ability(String id, String name, int apCost, int mpCost, TargetType target,
                      double damagePower, boolean magical, double healPower, int mpRestore,
                      List<String> cures, List<StatusApply> applies, int apGain, int weight,
                      String sequenceId) {
}
