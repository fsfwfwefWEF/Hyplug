package dev.jrpg.battle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * CTB-очередь (как в FF X): каждый «тик» ct += скорость, ходит тот, кто первым набрал THRESHOLD.
 * Тики считаются мгновенно, поэтому можно показать прогноз будущих ходов.
 */
public final class TurnQueue {
    public static final double THRESHOLD = 100.0;

    private TurnQueue() {
    }

    /** Продвигает время до ближайшего хода и возвращает того, кто ходит. ct не сбрасывается здесь. */
    public static Combatant advance(List<Combatant> all) {
        List<Combatant> alive = all.stream().filter(Combatant::isAlive).toList();
        if (alive.isEmpty()) return null;
        double minTicks = Double.MAX_VALUE;
        for (Combatant c : alive) {
            minTicks = Math.min(minTicks, ticksToReady(c));
        }
        for (Combatant c : alive) {
            c.ct += c.effectiveSpeed() * minTicks;
        }
        return alive.stream().max(readyOrder()).orElseThrow();
    }

    /** Прогноз: кто будет ходить следующие count ходов (каждый ход считается с весом 100). */
    public static List<Combatant> forecast(List<Combatant> all, int count) {
        List<Combatant> alive = all.stream().filter(Combatant::isAlive).toList();
        List<double[]> ct = new ArrayList<>();
        for (Combatant c : alive) ct.add(new double[]{c.ct});
        List<Combatant> out = new ArrayList<>();
        for (int n = 0; n < count && !alive.isEmpty(); n++) {
            double minTicks = Double.MAX_VALUE;
            for (int i = 0; i < alive.size(); i++) {
                double need = Math.max(0, THRESHOLD - ct.get(i)[0]) / alive.get(i).effectiveSpeed();
                minTicks = Math.min(minTicks, need);
            }
            int best = -1;
            for (int i = 0; i < alive.size(); i++) {
                ct.get(i)[0] += alive.get(i).effectiveSpeed() * minTicks;
                if (best < 0 || isBefore(alive.get(i), ct.get(i)[0], alive.get(best), ct.get(best)[0])) best = i;
            }
            out.add(alive.get(best));
            ct.get(best)[0] -= THRESHOLD;
        }
        return out;
    }

    private static boolean isBefore(Combatant a, double ctA, Combatant b, double ctB) {
        if (Math.abs(ctA - ctB) > 1e-9) return ctA > ctB;
        return a.side == Side.PARTY && b.side != Side.PARTY;
    }

    private static double ticksToReady(Combatant c) {
        return Math.max(0, THRESHOLD - c.ct) / c.effectiveSpeed();
    }

    /** При равенстве ct первой ходит партия. */
    private static Comparator<Combatant> readyOrder() {
        return Comparator.<Combatant>comparingDouble(c -> c.ct)
                .thenComparing(c -> c.side == Side.PARTY ? 1 : 0);
    }
}
