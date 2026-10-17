package dev.jrpg.battle;

import java.util.List;
import java.util.stream.Collectors;

/** Текстовые строки для HUD/меню: состояние партии, врагов, очередь ходов. */
public final class BattleText {
    private BattleText() {
    }

    public static String side(Battle b, Side side) {
        StringBuilder sb = new StringBuilder();
        for (Combatant c : b.combatants()) {
            if (c.side != side) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(c == b.current() ? "> " : "  ").append(c.name);
            if (!c.isAlive()) {
                sb.append("  (повержен)");
                continue;
            }
            sb.append("  HP ").append(c.hp).append('/').append(c.stats.maxHp());
            if (side == Side.PARTY) sb.append("  MP ").append(c.mp).append("  AP ").append(c.ap);
            if (!c.statuses.isEmpty()) {
                sb.append("  [").append(c.statuses.stream().map(s -> s.def.name() + " " + s.turnsLeft)
                        .collect(Collectors.joining(", "))).append(']');
            }
        }
        return sb.toString();
    }

    public static String queue(Battle b) {
        List<Combatant> f = TurnQueue.forecast(b.combatants(), 6);
        return "Очередь: " + f.stream().map(c -> c.name).collect(Collectors.joining(" > "));
    }

    /** Подпись над головой NPC. */
    public static String nameplate(Combatant c, boolean current) {
        if (!c.isAlive()) return c.name + " ✝";
        return (current ? "▶ " : "") + c.name + "  " + c.hp + "/" + c.stats.maxHp();
    }

    /** Имена комбо для журнала: id → имя (заполняет {@link Content} при добавлении комбо). */
    public static final java.util.Map<String, String> COMBO_NAMES = new java.util.concurrent.ConcurrentHashMap<>();

    public static String describe(ActionResult r) {
        StringBuilder sb = new StringBuilder(r.actor().name + ": " + r.actionName());
        if (r.fleeAttempt()) sb.append(r.fled() ? " — удалось!" : " — не вышло");
        for (ActionResult.Hit h : r.hits()) {
            sb.append("\n  ").append(h.target().name).append(':');
            for (String k : h.combos()) sb.append(" [").append(COMBO_NAMES.getOrDefault(k, k)).append(']');
            if (h.damage() > 0) sb.append(" -").append(h.damage()).append(" HP").append(h.crit() ? " КРИТ!" : "");
            if (h.heal() > 0) sb.append(" +").append(h.heal()).append(" HP");
            if (h.mpRestore() > 0) sb.append(" +").append(h.mpRestore()).append(" MP");
            if (!h.statusesApplied().isEmpty()) sb.append(" +").append(String.join(", ", h.statusesApplied()));
            if (!h.statusesCured().isEmpty()) sb.append(" снято: ").append(String.join(", ", h.statusesCured()));
            if (h.killed()) sb.append(" — повержен!");
        }
        if (r.reaction() == Battle.Reaction.DODGE) sb.append("\n  Уклонение: урон вдвое меньше");
        if (r.reaction() == Battle.Reaction.PARRY) {
            sb.append("\n  Парирование!");
            if (r.counter() != null && r.counterBy() != null) {
                sb.append(" ").append(r.counterBy().name).append(" контратакует: -").append(r.counter().damage()).append(" HP")
                        .append(r.counter().killed() ? " — повержен!" : "");
            }
        }
        return sb.toString();
    }
}
