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
 * У каждого героя своя сетка из {@link Grid#SIZE} узлов: в центре старт, вокруг четыре кольца.
 * Узел даёт прибавку к характеристике, новую способность или пассивное умение.
 * Сетка поделена на три сектора ролей ({@link Role}: защитник, целитель, боец) с мостами-гибридами между ними;
 * узлы «родной» роли героя стоят дешевле, но в чужие сектора тоже можно уйти (Лира-танк — пожалуйста).
 * Открыть можно только узел, соседний с уже открытым; внешние кольца требуют уровня.
 * Очки сетки даются за уровни ({@link #POINTS_PER_LEVEL}), характеристики ещё и растут сами ({@link HeroDef#growth()}).
 */
public final class Progression {
    public static final int POINTS_PER_LEVEL = 2;
    public static final int START_POINTS = 1;
    public static final int MAX_LEVEL = 30;

    private Progression() {
    }

    /** Роль в бою и сектор сетки. */
    public enum Role {
        TANK("Защитник", "принимает удары на себя: провокация, защита, здоровье"),
        HEALER("Целитель", "лечит и снимает статусы, мана и магия"),
        DPS("Боец", "наносит урон: криты, кровотечение, казнь");

        public final String label, about;

        Role(String label, String about) {
            this.label = label;
            this.about = about;
        }
    }

    /** Описание героя партии. growth — прибавка к характеристикам за каждый уровень после первого. */
    public record HeroDef(String id, String name, String title, Role role, Stats base, Stats growth,
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
     * 19..24 — третье (6 узлов, между лучами первого кольца); 25..36 — внешнее (12 узлов, на лучах второго).
     *
     * Сектора ролей по углу: защитник сверху справа (1, 2, 7, 8, 9, 19), целитель снизу справа
     * (3, 4, 11, 12, 13, 21), боец слева (5, 6, 15, 16, 17, 23). Узлы на границах — мосты-гибриды:
     * 10 и 20 (защитник + целитель), 14 и 22 (целитель + боец), 18 и 24 (боец + защитник).
     * Внешнее кольцо: 25-27 защитник, 28 мост, 29-31 целитель, 32 мост, 33-35 боец, 36 мост.
     */
    public static final class Grid {
        public static final int SIZE = 37;

        private Grid() {
        }

        public static int ring(int i) {
            return i == 0 ? 0 : i <= 6 ? 1 : i <= 18 ? 2 : i <= 24 ? 3 : 4;
        }

        /** Угол узла в градусах: 0 — вправо, -90 — вверх (экранные координаты, y вниз). */
        public static double angleDeg(int i) {
            return switch (ring(i)) {
                case 1 -> -90 + 60 * (i - 1);
                case 2 -> -90 + 30 * (i - 7);
                case 3 -> -60 + 60 * (i - 19);
                case 4 -> -90 + 30 * (i - 25);
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
                    if (j % 2 == 0) {
                        out.add(1 + j / 2);
                        out.add(25 + j);
                    } else out.add(19 + j / 2);
                }
                case 3 -> {
                    out.add(7 + 2 * (i - 19) + 1);
                    out.add(25 + 2 * (i - 19) + 1);
                }
                case 4 -> {
                    int k = i - 25;
                    out.add(25 + (k + 11) % 12);
                    out.add(25 + (k + 1) % 12);
                    // на одном луче: нечётные — с узлом третьего кольца, чётные — со вторым (линия идёт между узлами третьего)
                    out.add(k % 2 == 1 ? 19 + k / 2 : 7 + k);
                }
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

        /** Роли сектора узла: одна для обычного узла, две для моста, пусто для центра. */
        public static List<Role> sector(int i) {
            if (i == 0) return List.of();
            double a = Math.floorMod((int) Math.round(angleDeg(i)) + 120, 360); // 0..360, 0 = граница боец|защитник
            if (Math.abs(a % 120) < 1e-6) {
                int k = (int) (a / 120);
                Role[] after = {Role.TANK, Role.HEALER, Role.DPS};
                return List.of(after[(k + 2) % 3], after[k % 3]);
            }
            return List.of(new Role[]{Role.TANK, Role.HEALER, Role.DPS}[(int) (a / 120)]);
        }

        public static int minLevel(int i) {
            return switch (ring(i)) {
                case 2 -> 3;
                case 3 -> 6;
                case 4 -> 8;
                default -> 1;
            };
        }

        public static int cost(int i, GridNode n) {
            return switch (ring(i)) {
                case 0 -> 0;
                case 1 -> 1;
                case 2 -> n.kind() == NodeKind.STAT ? 1 : 2;
                case 3 -> 3;
                default -> n.kind() == NodeKind.STAT ? 2 : 3;
            };
        }

        /** Цена для героя: узлы его родной роли (и мосты с ней) во внешних кольцах на 1 очко дешевле. */
        public static int cost(int i, GridNode n, Role heroRole) {
            int base = cost(i, n);
            return ring(i) >= 2 && sector(i).contains(heroRole) ? Math.max(1, base - 1) : base;
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
        /** Надетое снаряжение: слот → id вещи ({@link Equipment}). */
        public final Map<Equipment.Slot, String> equipped = new java.util.EnumMap<>(Equipment.Slot.class);
        /** Реестр вещей (null в тестах без снаряжения). */
        Content content;

        Hero(HeroDef def) {
            this.def = def;
            unlocked.add(0);
        }

        /** Вещь в слоте или null. */
        public Equipment.Gear gear(Equipment.Slot slot) {
            String id = equipped.get(slot);
            return id == null || content == null || !content.hasGear(id) ? null : content.gear(id);
        }

        public GridNode node(int i) {
            return def.grid().get(i);
        }

        /** Итоговые характеристики: база + рост за уровни + узлы сетки + снаряжение. */
        public Stats stats() {
            return statsFor(equipped);
        }

        /** Характеристики с другим набором снаряжения (для сравнения в меню). */
        public Stats statsFor(Map<Equipment.Slot, String> eq) {
            int l = level - 1;
            Stats b = def.base(), g = def.growth();
            int[] add = new int[Stat.values().length];
            for (int i : unlocked) {
                GridNode n = node(i);
                if (n.kind() == NodeKind.STAT) add[n.stat().ordinal()] += n.value();
            }
            if (content != null) {
                for (String id : eq.values()) {
                    if (!content.hasGear(id)) continue;
                    Stats g2 = content.gear(id).bonus();
                    add[Stat.HP.ordinal()] += g2.maxHp();
                    add[Stat.MP.ordinal()] += g2.maxMp();
                    add[Stat.ATK.ordinal()] += g2.atk();
                    add[Stat.MAG.ordinal()] += g2.mag();
                    add[Stat.DEF.ordinal()] += g2.def();
                    add[Stat.SPD.ordinal()] += g2.spd();
                }
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
            // Навыки оружия и вещей доступны, пока вещь надета.
            if (content != null) for (String a : Equipment.skills(content, equipped)) if (!out.contains(a)) out.add(a);
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

        public int cost(int i) {
            return Grid.cost(i, node(i), def.role());
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
            int cost = cost(i);
            if (points < cost) return "нужно очков: " + cost;
            return null;
        }

        public String unlock(int i) {
            String why = whyCantUnlock(i);
            if (why != null) return why;
            points -= cost(i);
            unlocked.add(i);
            return null;
        }

        /** Вернуть все очки (тестовая кнопка «Сброс»). */
        public void respec() {
            for (int i : unlocked) points += cost(i);
            unlocked.clear();
            unlocked.add(0);
        }
    }

    /** Прогресс всей партии одного игрока. */
    public static final class Party {
        private final Map<String, Hero> heroes = new LinkedHashMap<>();

        public Party(Content content) {
            for (HeroDef d : content.heroes()) {
                Hero h = new Hero(d);
                h.content = content;
                h.equipped.putAll(content.startGear(d.id()));
                heroes.put(d.id(), h);
            }
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
                Equipment.Gear w = h.gear(Equipment.Slot.WEAPON);
                c.weaponPhys = Equipment.physBonus(w);
                c.weaponMag = Equipment.magBonus(w);
                c.weaponElement = w == null ? null : w.element();
                if (h.content != null) c.immunities.addAll(Equipment.immunities(h.content, h.equipped));
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
