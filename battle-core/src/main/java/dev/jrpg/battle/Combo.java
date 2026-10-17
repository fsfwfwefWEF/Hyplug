package dev.jrpg.battle;

/**
 * Комбо стихий: удар стихией element по цели со статусом requires даёт дополнительный эффект.
 * Например, молния по промокшей цели бьёт в 1.5 раза сильнее и оглушает.
 *
 * @param damageMult    множитель урона удара
 * @param bonusMaxHp    добавочный урон в долях макс. HP цели (0.12 = 12%)
 * @param consume       снять статус requires
 * @param applies       статус, который комбо накладывает (null — никакой)
 * @param blockStatuses обычные статусы удара в этот раз не накладываются (огонь по воде — пар, горения нет)
 * @param text          коротко, что делает (для описаний)
 */
public record Combo(String id, String name, String element, String requires, double damageMult, double bonusMaxHp,
                    boolean consume, String applies, int applyTurns, boolean blockStatuses, String text) {
}
