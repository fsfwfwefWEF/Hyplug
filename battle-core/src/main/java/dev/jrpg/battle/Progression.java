package dev.jrpg.battle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Прокачка партии: опыт, уровни и круговая сетка навыков (в духе Sphere Grid из Final Fantasy X).
 *
 * У каждого героя своя сетка из {@link Grid#SIZE} узлов: в центре старт, вокруг три кольца.
 * Узел даёт прибавку к характеристике, новую способность или пассивное умение.
 * Открыть можно только узел, соседний с уже открытым; внешние кольца требуют уровня.
 * Очки сетки даются за уровни ({@link #POINTS_PER_LEVEL}), характеристики ещё и растут сами ({@link HeroDef#growth()}).
 */
public final class Progression {
    public static final int POINTS_PER_LEVEL = 2;
    public static final int START_POINTS = 1;
    public static final int MAX_LEVEL = 30;

    private Progression() {
    }

    /** Описание героя партии. growth — прибавка к характеристикам за каждый уровень после первого. */
    public record HeroDef(String id, String name, String title, Stats base, Stats growth,
                          List<String> startAbilities, List<GridNode> grid) {
        public HeroDef {
            if (grid.size() != Grid.SIZE) throw new IllegalArgumentException("Сетка " + id + ": нужно " + Grid.SIZE + " узлов");
        }
    }

    public enum NodeKind { START, STAT, ABILITY, PASSIVE }

    /** Узел сетки: ref — id способности/пассивки или имя характеристики, value — прибавка (для STAT). */
    public record GridNode(NodeKind kind, String ref, int value) {
        public static GridNode start() { return new GridNode(NodeKind.START, "", 0); }
        public static GridNode stat(Stat s, int value) { return new GridNode(NodeKind.STAT, s.name(), value); }
        public static GridNode ability(String id) { return new GridNode(NodeKind.ABILITY, id, 0); }
        public static GridNode passive(String id) { return new GridNode(NodeKind.PASSIVE, id, 0); }

        public Stat stat() { return Stat.valueOf(ref); }
    }

    /** Характеристика, которую может поднять узел сетки. */
    public enum Stat {
        HP("Здоровье"), MP("Мана"), ATK("Сила"), MAG("Магия"), DEF("Защита"), SPD("Скорость");

        public final String label;

        Stat(String label) {
            this.label = label;
        }
    }

    /**
     * Геометрия сетки (одна на всех героев, чтобы интерфейс был общим):
     * 0 — центр; 1..6 — первое кольцо (6 узлов, от верхнего по часовой); 7..18 — второе (12 узлов);
     * 19..24 — внешнее (6 узлов, между лучами первого кольца).
     */
    public static final class Grid {
        public static final int SIZE = 25;

        private Grid() {
        }

        public static int ring(int i) {
            return i == 0 ? 0 : i <= 6 ? 1 : i <= 18 ? 2 : 3;
        }

        /** Угол узла в градусах: 0 — вправо, -90 — вверх (экранные координаты, y вниз). */
        public static double angleDeg(int i) {
            return switch (ring(i)) {
                case 1 -> -90 + 60 * (i - 1);
                case 2 -> -90 + 30 * (i - 7);
                case 3 -> -60 + 60 * (i - 19);
                default -> 0;
            };
        }

        public static List<Integer> neighbors(int i) {
            List<Integer> out = new ArrayList<>();
            switch (ring(i)) {
                case 0 -> { for (int k = 1; k <= 6; k++) out.add(k); }
                case 1 -> {
                    out.add(0);
                    out.add(7 + 2 * (i - 1));
                }
                case 2 -> {
                    int j = i - 7;
                    out.add(7 + (j + 11) % 12);
                    out.add(7 + (j + 1) % 12);
                    if (j % 2 == 0) out.add(1 + j / 2);
                    else out.add(19 + j / 2);
                }
                case 3 -> out.add(7 + 2 * (i - 19) + 1);
                default -> { }
            }
            return out;
        }

        /** Все рёбра (a < b) — для рисования линий. */
        public static List<int[]> edges() {
            List<int[]> out = new ArrayList<>();
            for (int a = 0; a < SIZE; a++) for (int b : neighbors(a)) if (a < b) out.add(new int[]{a, b});
            return out;
        }

        public static int minLevel(int i) {
            return switch (ring(i)) {
                case 2 -> 3;
                case 3 -> 6;
                default -> 1;
            };
        }

        public static int cost(int i, GridNode n) {
            return switch (ring(i)) {
                case 0 -> 0;
                case 1 -> 1;
                case 2 -> n.kind() == NodeKind.STAT ? 1 : 2;
                default -> 3;
            };
        }
    }

    /** Опыт до следующего уровня. */
    public static int expToNext(int level) {
        return 40 + 30 * level;
    }

    /** Прогресс одного героя. */
    public static final class Hero {
        public final HeroDef def;
        public int level = 1;
        public int exp;
        public int points = START_POINTS;
        /** Открытые узлы сетки (центр открыт всегда). */
        public final Set<Integer> unlocked = new LinkedHashSet<>();

        Hero(HeroDef def) {
            this.def = def;
            unlocked.add(0);
        }

        public GridNode node(int i) {
            return def.grid().get(i);
        }

        /** Итоговые характеристики: база + рост за уровни + узлы сетки. */
        public Stats stats() {
            int l = level - 1;
            Stats b = def.base(), g = def.growth();
            int[] add = new int[Stat.values().length];
            for (int i : unlocked) {
                GridNode n = node(i);
                if (n.kind() == NodeKind.STAT) add[n.stat().ordinal()] += n.value();
            }
            return new Stats(
                    b.maxHp() + g.maxHp() * l + add[Stat.HP.ordinal()],
                    b.maxMp() + g.maxMp() * l + add[Stat.MP.ordinal()],
                    b.atk() + g.atk() * l + add[Stat.ATK.ordinal()],
                    b.mag() + g.mag() * l + add[Stat.MAG.ordinal()],
                    b.def() + g.def() * l + add[Stat.DEF.ordinal()],
                    b.spd() + g.spd() * l + add[Stat.SPD.ordinal()]);
        }

        public List<String> abilities() {
            List<String> out = new ArrayList<>(def.startAbilities());
            for (int i = 0; i < Grid.SIZE; i++) {
                GridNode n = node(i);
                if (unlocked.contains(i) && n.kind() == NodeKind.ABILITY && !out.contains(n.ref())) out.add(n.ref());
            }
            return out;
        }

        public List<String> passives() {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < Grid.SIZE; i++) {
                GridNode n = node(i);
                if (unlocked.contains(i) && n.kind() == NodeKind.PASSIVE && !out.contains(n.ref())) out.add(n.ref());
            }
            return out;
        }

        /** Добавить опыт; возвращает, сколько уровней получено. */
        public int addExp(int amount) {
            int ups = 0;
            exp += amount;
            while (level < MAX_LEVEL && exp >= expToNext(level)) {
                exp -= expToNext(level);
                level++;
                points += POINTS_PER_LEVEL;
                ups++;
            }
            return ups;
        }

        public boolean reachable(int i) {
            for (int nb : Grid.neighbors(i)) if (unlocked.contains(nb)) return true;
            return false;
        }

        /** null — можно открыть, иначе причина, почему нельзя. */
        public String whyCantUnlock(int i) {
            if (i < 0 || i >= Grid.SIZE) return "нет такого узла";
            if (unlocked.contains(i)) return "уже открыто";
            if (!reachable(i)) return "сначала открой соседний узел";
            if (level < Grid.minLevel(i)) return "нужен " + Grid.minLevel(i) + " уровень";
            int cost = Grid.cost(i, node(i));
            if (points < cost) return "нужно очков: " + cost;
            return null;
        }

        public String unlock(int i) {
            String why = whyCantUnlock(i);
            if (why != null) return why;
            points -= Grid.cost(i, node(i));
            unlocked.add(i);
            return null;
        }

        /** Вернуть все очки (тестовая кнопка «Сброс»). */
        public void respec() {
            for (int i : unlocked) points += Grid.cost(i, node(i));
            unlocked.clear();
            unlocked.add(0);
        }
    }

    /** Прогресс всей партии одного игрока. */
    public static final class Party {
        private final Map<String, Hero> heroes = new LinkedHashMap<>();

        public Party(Content content) {
            for (HeroDef d : content.heroes()) heroes.put(d.id(), new Hero(d));
        }

        public List<Hero> heroes() { return List.copyOf(heroes.values()); }

        public Hero hero(String id) {
            Hero h = heroes.get(id);
            if (h == null) throw new IllegalArgumentException("Нет героя " + id);
            return h;
        }

        /** Бойцы для нового боя: характеристики, способности и пассивки по прокачке, HP/MP полные. */
        public List<Combatant> buildParty(Content content) {
            List<Combatant> out = new ArrayList<>();
            for (Hero h : heroes.values()) {
                Combatant c = new Combatant(h.def.id(), h.def.name(), Side.PARTY, h.stats(), h.abilities());
                c.passives.addAll(h.passives());
                out.add(c);
            }
            return out;
        }

        /** Опыт за бой делится поровну. Возвращает строки «Лира достигает 3 уровня!». */
        public List<String> addExp(int total) {
            List<String> msgs = new ArrayList<>();
            if (heroes.isEmpty() || total <= 0) return msgs;
            int each = Math.max(1, total / heroes.size());
            for (Hero h : heroes.values()) {
                int ups = h.addExp(each);
                if (ups > 0) msgs.add(h.def.name() + " достигает " + h.level + " уровня!");
            }
            return msgs;
        }
    }
}
