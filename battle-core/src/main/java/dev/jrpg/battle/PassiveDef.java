package dev.jrpg.battle;

/** Пассивное умение из прокачки. Действие зашито в {@link Battle} по id, здесь имя и описание. */
public record PassiveDef(String id, String name, String text) {
}
