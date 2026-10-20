package dev.jrpg.battle;

/** Базовые характеристики бойца. spd определяет частоту ходов в CTB. */
public record Stats(int maxHp, int maxMp, int atk, int mag, int def, int spd) {
    public static final Stats ZERO = new Stats(0, 0, 0, 0, 0, 0);

    public Stats times(int k) {
        return new Stats(maxHp * k, maxMp * k, atk * k, mag * k, def * k, spd * k);
    }
}
