package dev.jrpg.battle;

/** Стихии способностей (для комбо). Строки, чтобы их было удобно писать в данных. */
public final class Element {
    public static final String PHYSICAL = "physical";
    public static final String FIRE = "fire";
    public static final String ICE = "ice";
    public static final String THUNDER = "thunder";
    public static final String WATER = "water";
    public static final String EARTH = "earth";
    public static final String POISON = "poison";

    private Element() {
    }

    public static String name(String element) {
        return switch (element) {
            case PHYSICAL -> "Физический";
            case FIRE -> "Огонь";
            case ICE -> "Лёд";
            case THUNDER -> "Молния";
            case WATER -> "Вода";
            case EARTH -> "Земля";
            case POISON -> "Яд";
            default -> element;
        };
    }
}
