package dev.jrpg.battle;

/** Базовые характеристики бойца. spd определяет частоту ходов в CTB. */
public record Stats(int maxHp, int maxMp, int atk, int mag, int def, int spd) {
}
