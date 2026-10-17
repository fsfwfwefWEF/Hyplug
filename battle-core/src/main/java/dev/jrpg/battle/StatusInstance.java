package dev.jrpg.battle;

/** Статус, висящий на конкретном бойце. */
public final class StatusInstance {
    public final StatusDef def;
    public int turnsLeft;
    public int stacks = 1;
    /** Наложен владельцем на себя в текущем ходу: этот конец хода не уменьшает длительность. */
    public boolean skipNextDecrement;

    public StatusInstance(StatusDef def, int turns) {
        this.def = def;
        this.turnsLeft = turns;
    }

    @Override
    public String toString() {
        return def.name() + (stacks > 1 ? " x" + stacks : "") + " (" + turnsLeft + ")";
    }
}
