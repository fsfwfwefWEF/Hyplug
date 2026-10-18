package dev.jrpg.battle;

/**
 * Предмет рюкзака. Расходуемые описываются той же {@link Ability}, но без стоимости; у материалов
 * (добыча с врагов) эффекта нет — их нельзя использовать в бою, они копятся в рюкзаке.
 */
public record Item(String id, String name, Ability effect, Kind kind, String description) {
    /** Вкладки рюкзака. */
    public enum Kind {
        CONSUMABLE("Зелья"), BATTLE("Боевые"), MATERIAL("Материалы"), GEAR("Снаряжение");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    public Item(String id, String name, Ability effect) {
        this(id, name, effect, kindOf(effect), "");
    }

    public Item(String id, String name, Ability effect, String description) {
        this(id, name, effect, kindOf(effect), description);
    }

    /** Материал: только для рюкзака. */
    public static Item material(String id, String name, String description) {
        return new Item(id, name, null, Kind.MATERIAL, description);
    }

    /** Можно ли использовать в бою. */
    public boolean usable() {
        return effect != null;
    }

    private static Kind kindOf(Ability effect) {
        if (effect == null) return Kind.MATERIAL;
        return effect.damagePower() > 0 ? Kind.BATTLE : Kind.CONSUMABLE;
    }
}
