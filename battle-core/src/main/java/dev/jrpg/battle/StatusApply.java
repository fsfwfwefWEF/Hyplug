package dev.jrpg.battle;

/** «Наложить статус statusId с шансом chance на turns ходов». */
public record StatusApply(String statusId, double chance, int turns) {
}
