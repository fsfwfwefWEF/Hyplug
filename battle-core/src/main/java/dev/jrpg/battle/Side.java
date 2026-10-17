package dev.jrpg.battle;

/** Сторона боя. */
public enum Side {
    PARTY, ENEMY;

    public Side opposite() {
        return this == PARTY ? ENEMY : PARTY;
    }
}
