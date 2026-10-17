package dev.jrpg.battle;

import java.util.ArrayList;
import java.util.List;

/** Участник боя: герой, компаньон или враг. Состояние живёт только на время боя. */
public final class Combatant {
    public static final int MAX_AP = 10;

    public final String id;
    public final String name;
    public final Side side;
    public final Stats stats;
    /** id способностей, доступных в меню «Способности». Изменяемый: босс может получить новые в фазе. */
    public final List<String> abilities;
    /** Из какого шаблона врага создан (enemies.json), null для героев. */
    public String templateId;
    /** Босс: в UI показывается крупной полосой HP, адаптер может включить свою музыку/камеру. */
    public boolean boss;
    /** id статусов, которые на бойца не действуют (босс может быть невосприимчив к оглушению). */
    public final java.util.Set<String> immunities = new java.util.HashSet<>();
    /** Пассивные умения из прокачки (id из {@link Content#passive(String)}). */
    public final java.util.Set<String> passives = new java.util.HashSet<>();
    /** Ссылка на сущность/модель в адаптере (например, UUID NPC в Hytale). */
    public String entityKey;

    public int hp;
    public int mp;
    public int ap;
    /** Счётчик готовности CTB: ходит тот, кто первым набрал {@link TurnQueue#THRESHOLD}. */
    public double ct;
    public final List<StatusInstance> statuses = new ArrayList<>();

    public Combatant(String id, String name, Side side, Stats stats, List<String> abilities) {
        this.id = id;
        this.name = name;
        this.side = side;
        this.stats = stats;
        this.abilities = new ArrayList<>(abilities);
        this.hp = stats.maxHp();
        this.mp = stats.maxMp();
    }

    public boolean isAlive() {
        return hp > 0;
    }

    public StatusInstance status(String statusId) {
        for (StatusInstance s : statuses) {
            if (s.def.id().equals(statusId)) return s;
        }
        return null;
    }

    /** Произведение всех множителей статусов данного вида (1.0, если таких нет). */
    public double multiplier(StatusDef.Kind kind) {
        double m = 1.0;
        for (StatusInstance s : statuses) {
            if (s.def.kind() == kind) m *= s.def.power();
        }
        return m;
    }

    public double effectiveSpeed() {
        return Math.max(1.0, stats.spd() * multiplier(StatusDef.Kind.SPEED_MULT));
    }

    @Override
    public String toString() {
        return name;
    }
}
