package dev.jrpg.battle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Прокачка партии (тестовая версия): опыт, уровни, очки характеристик и дерево навыков.
 *
 * За уровень персонаж получает рост характеристик ({@link HeroDef#growth()}), 3 очка характеристик
 * и 1 очко навыков. Очки характеристик раздаются вручную ({@link Stat}), очки навыков тратятся
 * на узлы дерева ({@link SkillNode}): узел открывает новую способность в бою.
 */
public final class Progression {
    public static final int STAT_POINTS_PER_LEVEL = 3;
    public static final int SKILL_POINTS_PER_LEVEL = 1;
    public static final int MAX_LEVEL = 30;

    private Progression() {
    }

    /** Описание героя партии. growth — прибавка к характеристикам за каждый уровень после первого. */
    public record HeroDef(String id, String name, String title, Stats base, Stats growth,
                          List<String> startAbilities, List<SkillNode> tree) {
    }

    /**
     * Узел дерева навыков: способность abilityId, стоит cost очков, доступна с уровня minLevel
     * и (если requires != null) только после изучения другого узла.
     */
    public record SkillNode(String abilityId, int cost, int minLevel, String requires) {
    }

    /** Характеристика, в которую можно вложить очко, и сколько она даёт за очко. */
    public enum Stat {
        HP("Здоровье", 10), MP("Мана", 6), ATK("Сила", 2), MAG("Магия", 2), DEF("Защита", 2), SPD("Скорость", 1);

        public final String label;
        public final int perPoint;

        Stat(String label, int perPoint) {
            this.label = label;
            this.perPoint = perPoint;
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
        public int statPoints;
        public int skillPoints;
        public final Map<Stat, Integer> invested = new LinkedHashMap<>();
        public final Set<String> learned = new LinkedHashSet<>();

        Hero(HeroDef def) {
            this.def = def;
            for (Stat s : Stat.values()) invested.put(s, 0);
        }

        /** Итоговые характеристики: база + рост за уровни + вложенные очки. */
        public Stats stats() {
            int l = level - 1;
            Stats b = def.base(), g = def.growth();
            return new Stats(
                    b.maxHp() + g.maxHp() * l + invested.get(Stat.HP) * Stat.HP.perPoint,
                    b.maxMp() + g.maxMp() * l + invested.get(Stat.MP) * Stat.MP.perPoint,
                    b.atk() + g.atk() * l + invested.get(Stat.ATK) * Stat.ATK.perPoint,
                    b.mag() + g.mag() * l + invested.get(Stat.MAG) * Stat.MAG.perPoint,
                    b.def() + g.def() * l + invested.get(Stat.DEF) * Stat.DEF.perPoint,
                    b.spd() + g.spd() * l + invested.get(Stat.SPD) * Stat.SPD.perPoint);
        }

        public List<String> abilities() {
            List<String> out = new ArrayList<>(def.startAbilities());
            for (SkillNode n : def.tree()) if (learned.contains(n.abilityId())) out.add(n.abilityId());
            return out;
        }

        /** Добавить опыт; возвращает, сколько уровней получено. */
        public int addExp(int amount) {
            int ups = 0;
            exp += amount;
            while (level < MAX_LEVEL && exp >= expToNext(level)) {
                exp -= expToNext(level);
                level++;
                statPoints += STAT_POINTS_PER_LEVEL;
                skillPoints += SKILL_POINTS_PER_LEVEL;
                ups++;
            }
            return ups;
        }

        public String invest(Stat s) {
            if (statPoints <= 0) return "Нет свободных очков характеристик";
            statPoints--;
            invested.merge(s, 1, Integer::sum);
            return null;
        }

        /** null — можно изучить, иначе причина, почему нельзя. */
        public String whyCantLearn(SkillNode n) {
            if (learned.contains(n.abilityId())) return "уже изучено";
            if (level < n.minLevel()) return "нужен " + n.minLevel() + " уровень";
            if (n.requires() != null && !learned.contains(n.requires())) return "сначала предыдущий навык";
            if (skillPoints < n.cost()) return "нужно " + n.cost() + " очк. навыков";
            return null;
        }

        public String learn(SkillNode n) {
            String why = whyCantLearn(n);
            if (why != null) return why;
            skillPoints -= n.cost();
            learned.add(n.abilityId());
            return null;
        }

        /** Вернуть все очки (тестовая кнопка «Сброс»). */
        public void respec() {
            int spentStats = invested.values().stream().mapToInt(Integer::intValue).sum();
            statPoints += spentStats;
            for (Stat s : Stat.values()) invested.put(s, 0);
            for (SkillNode n : def.tree()) if (learned.remove(n.abilityId())) skillPoints += n.cost();
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

        /** Бойцы для нового боя: характеристики и способности по прокачке, HP/MP полные. */
        public List<Combatant> buildParty(Content content) {
            List<Combatant> out = new ArrayList<>();
            for (Hero h : heroes.values()) {
                out.add(new Combatant(h.def.id(), h.def.name(), Side.PARTY, h.stats(), h.abilities()));
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
