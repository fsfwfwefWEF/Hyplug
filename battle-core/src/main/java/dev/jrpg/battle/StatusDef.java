package dev.jrpg.battle;

/**
 * Описание статуса (яд, реген, оглушение...). Данные, а не логика:
 * поведение определяется {@link Kind}, сила — {@code power}.
 */
public record StatusDef(String id, String name, Trigger trigger, Kind kind, double power,
                        boolean stackable, int maxStacks) {

    /** Когда статус срабатывает. Длительность всегда уменьшается в конце хода владельца. */
    public enum Trigger { TURN_START, TURN_END, PASSIVE }

    public enum Kind {
        /** Урон power% от макс. HP за тик (не опускает ниже 1 HP). */
        DOT_PERCENT,
        /** Фиксированный урон power × стаки за тик. */
        DOT_FLAT,
        /** Лечение power% от макс. HP за тик. */
        HOT_PERCENT,
        /** Множитель скорости (Ускорение 1.5, Замедление 0.5). */
        SPEED_MULT,
        /** Пропуск хода. */
        SKIP_TURN,
        /** Множитель входящего урона (Защита 0.5). */
        DAMAGE_TAKEN_MULT,
        /** Множитель урона следующей способности, снимается после неё. */
        NEXT_ACTION_MULT,
        /** Своего действия нет: метка для комбо стихий (Промок — молния бьёт сильнее и т.п.). */
        MARKER,
        /** Провокация: враги бьют одиночными атаками только этого бойца. */
        TAUNT
    }
}
