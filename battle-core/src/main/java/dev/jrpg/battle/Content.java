package dev.jrpg.battle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Реестр игровых данных: статусы, способности, предметы, шаблоны бойцов.
 * В прототипе заполняется кодом ({@link #demo()}); следующий шаг — загрузка из data/*.json.
 */
public final class Content {
    public static final String ATTACK = "attack";
    public static final String DEFEND = "defend";

    private final Map<String, StatusDef> statuses = new HashMap<>();
    private final Map<String, Ability> abilities = new HashMap<>();
    private final Map<String, Item> items = new HashMap<>();
    private final Map<String, String> flavors = new HashMap<>();
    private final Map<String, String> icons = new HashMap<>();
    private final Map<String, Progression.HeroDef> heroes = new java.util.LinkedHashMap<>();

    public void add(StatusDef s) { statuses.put(s.id(), s); }
    public void add(Ability a) { abilities.put(a.id(), a); }
    public void add(Item i) { items.put(i.id(), i); }
    public void add(Progression.HeroDef h) { heroes.put(h.id(), h); }

    /** Художественное описание способности/предмета для окна описаний ("" если нет). */
    public String flavor(String id) { return flavors.getOrDefault(id, ""); }
    public void flavor(String id, String text) { flavors.put(id, text); }

    /** Явная иконка способности/предмета (иначе выбирается по эффекту, см. {@link Describe}). */
    public String icon(String id) { return icons.get(id); }
    public void icon(String id, String key) { icons.put(id, key); }

    public java.util.Collection<Progression.HeroDef> heroes() { return heroes.values(); }
    public Progression.HeroDef hero(String id) { return require(heroes, id, "герой"); }

    public StatusDef status(String id) { return require(statuses, id, "статус"); }
    public Ability ability(String id) { return require(abilities, id, "способность"); }
    public Item item(String id) { return require(items, id, "предмет"); }

    private static <T> T require(Map<String, T> map, String id, String what) {
        T v = map.get(id);
        if (v == null) throw new IllegalArgumentException("Неизвестный " + what + ": " + id);
        return v;
    }

    // ---------- демо-контент ----------

    private static Ability dmg(String id, String name, int ap, int mp, TargetType t, double power,
                               boolean magical, List<StatusApply> applies, int apGain, int weight) {
        return new Ability(id, name, ap, mp, t, power, magical, 0, 0, List.of(), applies, apGain, weight, id);
    }

    private static Ability support(String id, String name, int ap, int mp, TargetType t, double heal,
                                   int mpRestore, List<String> cures, List<StatusApply> applies) {
        return new Ability(id, name, ap, mp, t, 0, true, heal, mpRestore, cures, applies, 0, 100, id);
    }

    public static Content demo() {
        Content c = new Content();
        c.add(new StatusDef("poison", "Яд", StatusDef.Trigger.TURN_START, StatusDef.Kind.DOT_PERCENT, 8, false, 1));
        c.add(new StatusDef("burn", "Горение", StatusDef.Trigger.TURN_END, StatusDef.Kind.DOT_FLAT, 6, true, 3));
        c.add(new StatusDef("regen", "Реген", StatusDef.Trigger.TURN_START, StatusDef.Kind.HOT_PERCENT, 10, false, 1));
        c.add(new StatusDef("haste", "Ускорение", StatusDef.Trigger.PASSIVE, StatusDef.Kind.SPEED_MULT, 1.5, false, 1));
        c.add(new StatusDef("slow", "Замедление", StatusDef.Trigger.PASSIVE, StatusDef.Kind.SPEED_MULT, 0.5, false, 1));
        c.add(new StatusDef("stun", "Оглушение", StatusDef.Trigger.TURN_START, StatusDef.Kind.SKIP_TURN, 0, false, 1));
        c.add(new StatusDef("guard", "Защита", StatusDef.Trigger.PASSIVE, StatusDef.Kind.DAMAGE_TAKEN_MULT, 0.5, false, 1));
        c.add(new StatusDef("brood_shield", "Защита выводка", StatusDef.Trigger.PASSIVE, StatusDef.Kind.DAMAGE_TAKEN_MULT, 0.5, false, 1));
        c.add(new StatusDef("charged", "Заряд", StatusDef.Trigger.PASSIVE, StatusDef.Kind.NEXT_ACTION_MULT, 1.5, false, 1));

        // Базовые действия: атака даёт +1 AP (как в Expedition 33), защита — тоже.
        c.add(dmg(ATTACK, "Атака", 0, 0, TargetType.SINGLE_ENEMY, 1.0, false, List.of(), 1, 100));
        c.add(new Ability(DEFEND, "Защита", 0, 0, TargetType.SELF, 0, false, 0, 0, List.of(),
                List.of(new StatusApply("guard", 1.0, 1)), 1, 80, DEFEND));

        // Герой (мечник)
        c.add(dmg("fire_slash", "Огненный разрез", 3, 0, TargetType.SINGLE_ENEMY, 1.6, false,
                List.of(new StatusApply("burn", 0.5, 3)), 0, 110));
        c.add(dmg("shield_bash", "Удар щитом", 2, 0, TargetType.SINGLE_ENEMY, 0.8, false,
                List.of(new StatusApply("stun", 0.35, 1)), 0, 100));
        c.add(support("focus", "Концентрация", 1, 0, TargetType.SELF, 0, 0, List.of(),
                List.of(new StatusApply("charged", 1.0, 3))));
        // Лира (маг)
        c.add(dmg("fireball", "Огненный шар", 0, 10, TargetType.SINGLE_ENEMY, 1.8, true,
                List.of(new StatusApply("burn", 0.7, 3)), 0, 100));
        c.add(dmg("blizzard", "Метель", 2, 18, TargetType.ALL_ENEMIES, 1.0, true,
                List.of(new StatusApply("slow", 0.4, 2)), 0, 120));
        c.add(support("cure", "Лечение", 0, 8, TargetType.SINGLE_ALLY, 0.35, 0, List.of("poison"), List.of()));
        c.add(support("haste_spell", "Ускорение", 2, 12, TargetType.SINGLE_ALLY, 0, 0, List.of(),
                List.of(new StatusApply("haste", 1.0, 3))));
        // Брам (танк)
        c.add(dmg("cleave", "Рассечение", 3, 0, TargetType.ALL_ENEMIES, 0.9, false, List.of(), 0, 120));
        c.add(support("rally", "Боевой клич", 4, 0, TargetType.ALL_ALLIES, 0.1, 0, List.of(),
                List.of(new StatusApply("regen", 1.0, 3))));
        // Враги
        c.add(dmg("venom_bite", "Ядовитый укус", 0, 0, TargetType.SINGLE_ENEMY, 0.9, false,
                List.of(new StatusApply("poison", 0.8, 3)), 0, 100));
        c.add(dmg("web", "Паутина", 2, 0, TargetType.SINGLE_ENEMY, 0.2, false,
                List.of(new StatusApply("slow", 1.0, 2)), 0, 90));
        c.add(dmg("goblin_frenzy", "Ярость гоблина", 3, 0, TargetType.SINGLE_ENEMY, 1.5, false, List.of(), 0, 120));
        // Королева пауков (босс)
        c.add(dmg("acid_rain", "Кислотный дождь", 3, 0, TargetType.ALL_ENEMIES, 0.7, false,
                List.of(new StatusApply("poison", 0.6, 3)), 0, 120));
        c.add(dmg("queen_frenzy", "Безумие королевы", 2, 0, TargetType.SINGLE_ENEMY, 1.8, false, List.of(), 0, 100));
        c.add(dmg("cocoon", "Кокон", 0, 0, TargetType.SINGLE_ENEMY, 0.3, false,
                List.of(new StatusApply("stun", 1.0, 2)), 0, 100));
        c.add(support("goo_mend", "Слизистое лечение", 0, 6, TargetType.SINGLE_ALLY, 0.3, 0, List.of(), List.of()));

        c.add(new Item("potion", "Зелье", support("item_potion", "Зелье", 0, 0, TargetType.SINGLE_ALLY,
                0.4, 0, List.of(), List.of())));
        c.add(new Item("ether", "Эфир", support("item_ether", "Эфир", 0, 0, TargetType.SINGLE_ALLY,
                0, 25, List.of(), List.of())));
        c.add(new Item("antidote", "Противоядие", support("item_antidote", "Противоядие", 0, 0,
                TargetType.SINGLE_ALLY, 0, 0, List.of("poison"), List.of())));
        c.add(new Item("bomb", "Бомба", dmg("item_bomb", "Бомба", 0, 0, TargetType.ALL_ENEMIES, 1.0, false,
                List.of(new StatusApply("burn", 1.0, 2)), 0, 100)));
        // Уникальный лут с босса
        c.add(new Item("queen_fang", "Клык королевы", dmg("item_queen_fang", "Клык королевы", 0, 0,
                TargetType.SINGLE_ENEMY, 2.5, false, List.of(new StatusApply("poison", 1.0, 4)), 0, 100)));

        // Способности, которые открываются прокачкой (дерево навыков в Progression)
        c.add(dmg("whirlwind", "Вихрь клинков", 4, 0, TargetType.ALL_ENEMIES, 1.1, false, List.of(), 0, 120));
        c.add(dmg("execute", "Казнь", 5, 0, TargetType.SINGLE_ENEMY, 2.6, false, List.of(), 0, 120));
        c.add(dmg("thunder", "Молния", 1, 14, TargetType.SINGLE_ENEMY, 1.5, true,
                List.of(new StatusApply("stun", 0.3, 1)), 0, 100));
        c.add(support("barrier", "Барьер", 3, 16, TargetType.ALL_ALLIES, 0, 0, List.of(),
                List.of(new StatusApply("guard", 1.0, 2))));
        c.add(support("iron_wall", "Железная стена", 2, 0, TargetType.SINGLE_ALLY, 0, 0, List.of(),
                List.of(new StatusApply("guard", 1.0, 2))));
        c.add(dmg("earthquake", "Землетрясение", 5, 0, TargetType.ALL_ENEMIES, 1.2, false,
                List.of(new StatusApply("slow", 0.3, 2)), 0, 130));

        demoFlavor(c);
        demoHeroes(c);
        return c;
    }

    private static void demoFlavor(Content c) {
        c.flavor(ATTACK, "Обычный удар оружием. Копит очки действия (AP) для способностей.");
        c.flavor(DEFEND, "Встать в защиту до своего следующего хода.");
        c.flavor("fire_slash", "Клинок, объятый пламенем, оставляет горящую рану.");
        c.flavor("shield_bash", "Тяжёлый удар щитом, может оглушить.");
        c.flavor("focus", "Сосредоточиться: следующий удар будет мощнее.");
        c.flavor("fireball", "Классика магии огня.");
        c.flavor("blizzard", "Ледяной вихрь накрывает всех врагов и сковывает движения.");
        c.flavor("cure", "Исцеляющий свет, заодно выводит яд.");
        c.flavor("haste_spell", "Ускоряет союзника: он ходит чаще.");
        c.flavor("cleave", "Широкий взмах по всем врагам.");
        c.flavor("rally", "Боевой клич: союзники понемногу восстанавливают здоровье.");
        c.flavor("venom_bite", "Укус с ядом.");
        c.flavor("web", "Липкая паутина замедляет цель.");
        c.flavor("goblin_frenzy", "Гоблин впадает в ярость и бьёт со всей силы.");
        c.flavor("acid_rain", "Кислота льётся на всю партию.");
        c.flavor("queen_frenzy", "Королева бросается на одну цель.");
        c.flavor("cocoon", "Королева заматывает цель в кокон: та пропускает ходы.");
        c.flavor("goo_mend", "Слизень латает союзника своей слизью.");
        c.flavor("whirlwind", "Вращение с клинком задевает всех врагов.");
        c.flavor("execute", "Сокрушительный удар по одной цели.");
        c.flavor("thunder", "Разряд молнии, иногда оглушает.");
        c.flavor("barrier", "Магический щит на всю партию.");
        c.flavor("iron_wall", "Брам прикрывает союзника собой.");
        c.flavor("earthquake", "Удар по земле сотрясает всех врагов.");
        c.flavor("potion", "Восстанавливает здоровье.");
        c.flavor("ether", "Восстанавливает ману.");
        c.flavor("antidote", "Выводит яд.");
        c.flavor("bomb", "Взрывается и поджигает всех врагов.");
        c.flavor("queen_fang", "Ядовитый клык поверженной королевы.");
        c.icon(ATTACK, "sword");
        c.icon(DEFEND, "shield");
        c.icon("potion", "potion");
        c.icon("ether", "ether");
        c.icon("antidote", "antidote");
        c.icon("bomb", "bomb");
        c.icon("queen_fang", "fang");
        c.icon("thunder", "thunder");
        c.icon("earthquake", "quake");
    }

    /** Герои партии: базовые характеристики, рост за уровень и дерево навыков. */
    private static void demoHeroes(Content c) {
        c.add(new Progression.HeroDef("hero", "Герой", "Мечник", new Stats(120, 20, 22, 8, 12, 12),
                new Stats(12, 2, 3, 1, 2, 1), List.of("fire_slash", "shield_bash", "focus"),
                List.of(new Progression.SkillNode("whirlwind", 1, 2, null),
                        new Progression.SkillNode("execute", 2, 4, "whirlwind"))));
        c.add(new Progression.HeroDef("lyra", "Лира", "Чародейка", new Stats(85, 70, 9, 24, 7, 14),
                new Stats(7, 8, 1, 3, 1, 1), List.of("fireball", "blizzard", "cure", "haste_spell"),
                List.of(new Progression.SkillNode("thunder", 1, 2, null),
                        new Progression.SkillNode("barrier", 2, 4, "thunder"))));
        c.add(new Progression.HeroDef("bram", "Брам", "Страж", new Stats(160, 15, 18, 6, 18, 8),
                new Stats(16, 1, 2, 1, 3, 0), List.of("cleave", "rally"),
                List.of(new Progression.SkillNode("iron_wall", 1, 2, null),
                        new Progression.SkillNode("earthquake", 2, 4, "iron_wall"))));
    }

    /** Стартовая партия из 3 персонажей (1 уровень, без прокачки). */
    public static List<Combatant> demoParty() {
        Content c = demo();
        return new Progression.Party(c).buildParty(c);
    }

    public static List<Combatant> demoEnemies() {
        return List.of(
                new Combatant("goblin", "Гоблин", Side.ENEMY, new Stats(90, 0, 17, 0, 8, 11),
                        List.of("goblin_frenzy")),
                new Combatant("spider", "Ядовитый паук", Side.ENEMY, new Stats(70, 0, 15, 0, 6, 15),
                        List.of("venom_bite", "web")),
                new Combatant("slime", "Слизень", Side.ENEMY, new Stats(110, 40, 12, 10, 10, 6),
                        List.of("goo_mend")));
    }

    public static Map<String, Integer> demoInventory() {
        Map<String, Integer> inv = new HashMap<>();
        inv.put("potion", 3);
        inv.put("ether", 1);
        inv.put("antidote", 2);
        inv.put("bomb", 1);
        return inv;
    }
}
