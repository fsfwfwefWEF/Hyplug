package dev.jrpg.battle;

/** На кого может быть направлено действие. */
public enum TargetType {
    SELF, SINGLE_ALLY, SINGLE_ENEMY, ALL_ALLIES, ALL_ENEMIES;

    public boolean needsTargetChoice() {
        return this == SINGLE_ALLY || this == SINGLE_ENEMY;
    }

    public boolean targetsEnemies() {
        return this == SINGLE_ENEMY || this == ALL_ENEMIES;
    }
}
