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
}
