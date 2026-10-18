package dev.jrpg.battle;

/** id пассивных умений, которые понимает {@link Battle}. Имена и описания — в {@link Content#passive(String)}. */
public final class Passives {
    /** +10% шанс крита. */
    public static final String KEEN_EYE = "keen_eye";
    /** Лечит 15% от нанесённого урона. */
    public static final String BLOODTHIRST = "bloodthirst";
    /** +25% урона по горящим. */
    public static final String PYROMANIAC = "pyromaniac";
    /** +40% урона по целям ниже 30% HP. */
    public static final String EXECUTIONER = "executioner";
    /** Комбо стихий сильнее на 30%. */
    public static final String COMBO_MASTER = "combo_master";
    /** +15% к шансу наложить статус. */
    public static final String ELEMENTALIST = "elementalist";
    /** +3 MP в начале каждого хода. */
    public static final String MANA_FLOW = "mana_flow";
    /** +2 AP в начале боя. */
    public static final String FIRST_STRIKE = "first_strike";
    /** Начинает бой под Защитой. */
    public static final String THICK_SKIN = "thick_skin";

    private Passives() {
    }
    /** Танк: -15% входящего урона. */
    public static final String GUARDIAN = "guardian";
    /** Хил: лечение +25%. */
    public static final String HEALER_GRACE = "healer_grace";
    /** Гибрид танк+хил: в начале боя Реген на 3 хода и Защита на 1 ход. */
    public static final String PALADIN = "paladin";
    /** Гибрид хил+дамагер: магический урон и лечение +20%. */
    public static final String BATTLE_MAGE = "battle_mage";
    /** Гибрид дамагер+танк: урон +35%, пока у себя меньше половины HP. */
    public static final String BERSERKER = "berserker";
    /** Танк: возвращает 20% полученного урона ударившему. */
    public static final String THORNS = "thorns";
    /** Раз за бой смертельный удар оставляет 1 HP. */
    public static final String SECOND_CHANCE = "second_chance";
    /** Магический урон +15%. */
    public static final String ARCANE_SURGE = "arcane_surge";
    /** +1 AP в начале каждого хода. */
    public static final String TACTICIAN = "tactician";
    /** Скорость +20%: ходит чаще. */
    public static final String SWIFT = "swift";
    /** Криты чаще (+5%) и бьют x2 вместо x1.5. */
    public static final String ASSASSIN = "assassin";
}
