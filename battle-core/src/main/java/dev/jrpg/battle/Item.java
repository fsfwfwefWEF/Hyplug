package dev.jrpg.battle;

/** Расходуемый предмет: эффект описывается той же {@link Ability}, но без стоимости. */
public record Item(String id, String name, Ability effect) {
}
