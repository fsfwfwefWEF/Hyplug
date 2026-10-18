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
    private final Map<String, String> elements = new HashMap<>();
    private final List<Combo> combos = new java.util.ArrayList<>();
    private final Map<String, PassiveDef> passives = new java.util.LinkedHashMap<>();
    private final Map<String, Equipment.Gear> gear = new java.util.LinkedHashMap<>();
    private final Map<String, Map<Equipment.Slot, String>> startGear = new HashMap<>();
    private Map<String, Integer> starterBag = Map.of();

    public void add(StatusDef s) { statuses.put(s.id(), s); }
    public void add(Ability a) { abilities.put(a.id(), a); }
    public void add(Item i) { items.put(i.id(), i); }
    public void add(Progression.HeroDef h) { heroes.put(h.id(), h); }
    public void add(Combo k) {
        combos.add(k);
        BattleText.COMBO_NAMES.put(k.id(), k.name());
    }
    public void add(PassiveDef p) { passives.put(p.id(), p); }

    /** Вещь снаряжения; в рюкзаке она лежит как предмет вида «Снаряжение». */
    public void add(Equipment.Gear g) {
        gear.put(g.id(), g);
        items.put(g.id(), new Item(g.id(), g.name(), null, Item.Kind.GEAR, g.description()));
    }

    /** Перед перечитыванием equipment.json. */
    public void clearGear() {
        for (String id : gear.keySet()) items.remove(id);
        gear.clear();
        startGear.clear();
    }

    public Equipment.Gear gear(String id) { return require(gear, id, "вещь снаряжения"); }
    public boolean hasGear(String id) { return gear.containsKey(id); }
    public java.util.Collection<Equipment.Gear> gearList() { return gear.values(); }
    public void startGear(String heroId, Map<Equipment.Slot, String> eq) { startGear.put(heroId, eq); }
    /** Снаряжение героя в новой игре (пусто, если не задано). */
    public Map<Equipment.Slot, String> startGear(String heroId) { return startGear.getOrDefault(heroId, Map.of()); }
    public void starterBag(Map<String, Integer> bag) { starterBag = Map.copyOf(bag); }
    /** Запасные вещи, которые кладутся в рюкзак один раз (чтобы было что сравнить и надеть). */
    public Map<String, Integer> starterBag() { return starterBag; }

    /** Стихия способности ({@link Element}); урон без явной стихии считается физическим, поддержка — "". */
    public String element(Ability a) {
        String e = elements.get(a.id());
        if (e != null) return e;
        return a.damagePower() > 0 && !a.magical() ? Element.PHYSICAL : "";
    }
    public void element(String abilityId, String element) { elements.put(abilityId, element); }

    public List<Combo> combos() { return java.util.Collections.unmodifiableList(combos); }
    public PassiveDef passive(String id) { return require(passives, id, "пассивное умение"); }

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
    public boolean hasItem(String id) { return items.containsKey(id); }
    public java.util.Collection<Item> items() { return items.values(); }

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
        c.add(new StatusDef("wet", "Промок", StatusDef.Trigger.PASSIVE, StatusDef.Kind.MARKER, 0, false, 1));
        c.add(new StatusDef("frozen", "Заморозка", StatusDef.Trigger.TURN_START, StatusDef.Kind.SKIP_TURN, 0, false, 1));
        c.add(new StatusDef("armor_break", "Пробитая броня", StatusDef.Trigger.PASSIVE, StatusDef.Kind.DAMAGE_TAKEN_MULT, 1.35, false, 1));
        c.add(new StatusDef("bleed", "Кровотечение", StatusDef.Trigger.TURN_END, StatusDef.Kind.DOT_FLAT, 5, true, 3));
        c.add(new StatusDef("taunt", "Провокация", StatusDef.Trigger.PASSIVE, StatusDef.Kind.TAUNT, 0, false, 1));

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
        // Навыки оружия (equipment.json → skills): доступны, пока оружие в руке.
        c.add(dmg("blade_rush", "Натиск клинка", 2, 0, TargetType.SINGLE_ENEMY, 1.4, false,
                List.of(new StatusApply("bleed", 0.3, 3)), 0, 100));
        c.add(dmg("armor_split", "Раскол брони", 3, 0, TargetType.SINGLE_ENEMY, 1.1, false,
                List.of(new StatusApply("armor_break", 1.0, 2)), 0, 120));
        c.add(dmg("piercing_lunge", "Пронзающий выпад", 2, 0, TargetType.SINGLE_ENEMY, 1.3, false,
                List.of(new StatusApply("bleed", 0.5, 3)), 0, 100));
        c.add(dmg("arcane_bolt", "Чародейский заряд", 0, 6, TargetType.SINGLE_ENEMY, 1.3, true, List.of(), 0, 100));
        c.add(dmg("venom_edge", "Яд на клинке", 1, 0, TargetType.SINGLE_ENEMY, 0.8, false,
                List.of(new StatusApply("poison", 0.7, 3)), 0, 90));
        c.add(dmg("dawn_strike", "Рассветный удар", 4, 0, TargetType.SINGLE_ENEMY, 2.0, false,
                List.of(new StatusApply("burn", 0.6, 3)), 0, 120));
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

        // Материалы — добыча с врагов (loot в enemies.json). В бою не используются, копятся в рюкзаке.
        c.add(Item.material("wolf_fang", "Клык волка", "Острый волчий клык. Трофей охотника."));
        c.add(Item.material("bone", "Кость", "Старая кость скелета. Гремит в рюкзаке."));
        c.add(Item.material("spider_silk", "Паучий шёлк", "Прочная липкая нить паука."));
        c.add(Item.material("goblin_scrap", "Гоблинский хлам", "Гнутые железки и болты. Гоблины ими дорожат."));
        c.add(Item.material("trork_tusk", "Клык трорка", "Тяжёлый клык громилы-трорка."));
        c.add(Item.material("slime_gel", "Слизь", "Дрожащий комок слизи. Пахнет болотом."));
        c.icon("wolf_fang", "fang");
        c.icon("bone", "bone");
        c.icon("spider_silk", "silk");
        c.icon("goblin_scrap", "scrap");
        c.icon("trork_tusk", "tusk");
        c.icon("slime_gel", "gel");

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

        // Новые способности сетки навыков (0.5)
        c.add(dmg("bleeding_cut", "Кровавый разрез", 2, 0, TargetType.SINGLE_ENEMY, 1.1, false,
                List.of(new StatusApply("bleed", 0.8, 3)), 0, 100));
        c.add(dmg("armor_pierce", "Пробивающий удар", 3, 0, TargetType.SINGLE_ENEMY, 1.2, false,
                List.of(new StatusApply("armor_break", 1.0, 3)), 0, 110));
        c.add(dmg("storm_blade", "Клинок бури", 4, 10, TargetType.SINGLE_ENEMY, 1.7, false,
                List.of(new StatusApply("stun", 0.2, 1)), 0, 110));
        c.add(dmg("water_jet", "Водяной поток", 0, 8, TargetType.SINGLE_ENEMY, 1.0, true,
                List.of(new StatusApply("wet", 1.0, 3)), 0, 90));
        c.add(dmg("ice_lance", "Ледяное копьё", 1, 12, TargetType.SINGLE_ENEMY, 1.6, true,
                List.of(new StatusApply("slow", 0.5, 2)), 0, 100));
        c.add(dmg("chain_lightning", "Цепная молния", 3, 22, TargetType.ALL_ENEMIES, 1.1, true,
                List.of(new StatusApply("stun", 0.15, 1)), 0, 120));
        c.add(support("renew", "Обновление", 0, 10, TargetType.SINGLE_ALLY, 0.15, 0, List.of("bleed", "burn"),
                List.of(new StatusApply("regen", 1.0, 3))));
        c.add(dmg("sunder", "Раскол брони", 2, 0, TargetType.ALL_ENEMIES, 0.6, false,
                List.of(new StatusApply("armor_break", 0.6, 2)), 0, 110));
        c.add(dmg("ground_slam", "Сотрясающий удар", 3, 0, TargetType.SINGLE_ENEMY, 1.3, false,
                List.of(new StatusApply("stun", 0.4, 1)), 0, 110));
        c.add(support("second_wind", "Второе дыхание", 2, 0, TargetType.SELF, 0.3, 0, List.of("poison", "bleed"),
                List.of(new StatusApply("guard", 1.0, 1))));
        c.add(dmg("goo_spit", "Слизистый плевок", 0, 0, TargetType.SINGLE_ENEMY, 0.6, false,
                List.of(new StatusApply("wet", 1.0, 3)), 0, 100));

        // Враги отрядов (0.6)
        c.add(dmg("rend", "Разрыв", 0, 0, TargetType.SINGLE_ENEMY, 1.1, false,
                List.of(new StatusApply("bleed", 0.4, 3)), 0, 100));
        c.add(support("howl", "Вой", 3, 0, TargetType.ALL_ALLIES, 0, 0, List.of(),
                List.of(new StatusApply("haste", 1.0, 1))));
        c.add(dmg("bone_crush", "Дробящий удар", 0, 0, TargetType.SINGLE_ENEMY, 1.2, false,
                List.of(new StatusApply("armor_break", 0.4, 2)), 0, 110));
        c.add(dmg("trork_smash", "Удар дубиной", 2, 0, TargetType.SINGLE_ENEMY, 1.4, false,
                List.of(new StatusApply("stun", 0.25, 1)), 0, 120));

        // Роли (0.6): защитник держит врагов на себе, целитель снимает статусы и лечит всех
        c.add(support("provoke", "Провокация", 1, 0, TargetType.SELF, 0, 0, List.of(),
                List.of(new StatusApply("taunt", 1.0, 2), new StatusApply("guard", 1.0, 1))));
        c.add(support("bulwark", "Несокрушимый", 4, 0, TargetType.SELF, 0.2, 0, List.of(),
                List.of(new StatusApply("taunt", 1.0, 3), new StatusApply("guard", 1.0, 3))));
        c.add(support("purify", "Очищение", 1, 10, TargetType.SINGLE_ALLY, 0.1, 0,
                List.of("poison", "burn", "bleed", "slow", "stun", "frozen", "armor_break", "wet"), List.of()));
        c.add(support("sanctuary", "Святилище", 3, 24, TargetType.ALL_ALLIES, 0.25, 0, List.of(),
                List.of(new StatusApply("regen", 1.0, 3))));

        // Внешнее кольцо сетки (0.10)
        c.add(support("shield_wall", "Стена щитов", 3, 0, TargetType.ALL_ALLIES, 0.1, 0, List.of(),
                List.of(new StatusApply("guard", 1.0, 1))));
        c.add(support("war_cry", "Боевой рёв", 3, 0, TargetType.ALL_ALLIES, 0, 0, List.of(),
                List.of(new StatusApply("charged", 1.0, 3))));
        c.add(support("mass_cure", "Исцеление всех", 1, 22, TargetType.ALL_ALLIES, 0.3, 0,
                List.of("poison", "bleed", "burn"), List.of()));
        c.add(support("haste_all", "Общее ускорение", 4, 20, TargetType.ALL_ALLIES, 0, 0, List.of(),
                List.of(new StatusApply("haste", 1.0, 2))));
        c.add(dmg("meteor", "Метеор", 5, 30, TargetType.ALL_ENEMIES, 1.6, true,
                List.of(new StatusApply("burn", 0.5, 3)), 0, 140));
        c.add(dmg("shadow_strike", "Удар из тени", 4, 0, TargetType.SINGLE_ENEMY, 2.2, false,
                List.of(new StatusApply("bleed", 0.6, 3)), 0, 110));
        c.add(dmg("frost_nova", "Ледяная волна", 3, 20, TargetType.ALL_ENEMIES, 0.9, true,
                List.of(new StatusApply("slow", 0.5, 2)), 0, 120));
        c.add(dmg("tempest", "Буря", 4, 26, TargetType.ALL_ENEMIES, 1.2, true,
                List.of(new StatusApply("wet", 0.6, 3)), 0, 130));
        c.add(dmg("earth_spike", "Каменный шип", 3, 0, TargetType.SINGLE_ENEMY, 1.5, false,
                List.of(new StatusApply("armor_break", 0.5, 2)), 0, 110));
        // Враги поумнее (0.10): защита, усиление, удары по всем
        c.add(support("bone_wall", "Костяной панцирь", 2, 0, TargetType.SELF, 0.1, 0, List.of(),
                List.of(new StatusApply("guard", 1.0, 2))));
        c.add(dmg("war_stomp", "Топот", 3, 0, TargetType.ALL_ENEMIES, 0.7, false,
                List.of(new StatusApply("slow", 0.4, 2)), 0, 120));
        c.add(dmg("goblin_bomb", "Гоблинская бомба", 3, 0, TargetType.ALL_ENEMIES, 0.6, false,
                List.of(new StatusApply("burn", 0.4, 2)), 0, 110));
        c.add(support("frenzy_roar", "Рык ярости", 2, 0, TargetType.SELF, 0, 0, List.of(),
                List.of(new StatusApply("charged", 1.0, 3))));
        c.add(dmg("pounce", "Прыжок на горло", 2, 0, TargetType.SINGLE_ENEMY, 1.3, false,
                List.of(new StatusApply("bleed", 0.4, 3)), 0, 100));
        c.add(support("slime_split", "Слизистая броня", 2, 6, TargetType.ALL_ALLIES, 0.1, 0, List.of(),
                List.of(new StatusApply("regen", 1.0, 2))));

        demoElements(c);
        demoCombos(c);
        demoPassives(c);
        demoFlavor(c);
        demoHeroes(c);
        return c;
    }

    private static void demoFlavor(Content c) {
        c.flavor(ATTACK, "Обычный удар оружием. Копит очки действия (AP) для способностей.");
        c.flavor(DEFEND, "Встать в защиту до своего следующего хода.");
        c.flavor("blade_rush", "Навык меча: серия быстрых ударов, может пустить кровь.");
        c.flavor("armor_split", "Навык топора и булавы: тяжёлый удар раскалывает броню, цель получает больше урона.");
        c.flavor("piercing_lunge", "Навык копья: длинный выпад, оставляет глубокую рану.");
        c.flavor("arcane_bolt", "Навык посоха: сгусток чистой магии без затрат AP.");
        c.flavor("venom_edge", "Навык кинжалов: клинки смазаны ядом.");
        c.flavor("dawn_strike", "Навык Клинка рассвета: удар пламенем зари, поджигает цель.");
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
        c.flavor("iron_wall", "Прикрыть союзника собой: он получает вдвое меньше урона.");
        c.flavor("rend", "Волк рвёт клыками: рана кровоточит.");
        c.flavor("howl", "Вой стаи: все волки ускоряются.");
        c.flavor("bone_crush", "Тяжёлый удар костяной булавой, может пробить броню.");
        c.flavor("trork_smash", "Трорк лупит дубиной так, что звенит в ушах.");
        c.flavor("provoke", "Вызов на себя: враги бьют только тебя 2 хода, а ты в защите.");
        c.flavor("bulwark", "Стать скалой: провокация и защита на 3 хода, немного лечения.");
        c.flavor("purify", "Снимает с союзника яд, огонь, кровь, лёд, оглушение и прочие беды.");
        c.flavor("sanctuary", "Свет лечит всю партию и даёт Реген на 3 хода.");
        c.flavor("earthquake", "Удар по земле сотрясает всех врагов.");
        c.flavor("potion", "Восстанавливает здоровье.");
        c.flavor("ether", "Восстанавливает ману.");
        c.flavor("antidote", "Выводит яд.");
        c.flavor("bomb", "Взрывается и поджигает всех врагов.");
        c.flavor("queen_fang", "Ядовитый клык поверженной королевы.");
        c.flavor("bleeding_cut", "Режущий удар, после которого рана кровоточит.");
        c.flavor("armor_pierce", "Удар в щель доспеха: цель получает больше урона.");
        c.flavor("storm_blade", "Клинок, заряженный молнией. По промокшей цели бьёт ещё сильнее.");
        c.flavor("water_jet", "Струя воды сбивает с ног и делает цель мокрой: молния и лёд по ней сильнее.");
        c.flavor("ice_lance", "Острое ледяное копьё. По мокрой цели — заморозка.");
        c.flavor("chain_lightning", "Молния перескакивает по всем врагам.");
        c.flavor("renew", "Мягкий свет лечит, тушит огонь и останавливает кровь.");
        c.flavor("sunder", "Брам бьёт щитом по земле, раскалывая броню врагов.");
        c.flavor("ground_slam", "Тяжёлый удар, может оглушить.");
        c.flavor("second_wind", "Брам переводит дыхание: лечение, снимает яд и кровь, встаёт в защиту.");
        c.flavor("goo_spit", "Слизень плюётся: цель промокает.");
        c.icon(ATTACK, "sword");
        c.icon("blade_rush", "sword");
        c.icon("armor_split", "crack");
        c.icon("piercing_lunge", "bleed");
        c.icon("arcane_bolt", "magic");
        c.icon("venom_edge", "poison");
        c.icon("dawn_strike", "fire");
        c.icon("bleeding_cut", "bleed");
        c.icon("armor_pierce", "crack");
        c.icon("sunder", "crack");
        c.icon("storm_blade", "thunder");
        c.icon("chain_lightning", "thunder");
        c.icon("water_jet", "water");
        c.icon("goo_spit", "water");
        c.icon("ice_lance", "ice");
        c.icon("ground_slam", "quake");
        c.icon(DEFEND, "shield");
        c.icon("provoke", "taunt");
        c.icon("rend", "bleed");
        c.icon("howl", "haste");
        c.icon("bone_crush", "crack");
        c.icon("trork_smash", "stun");
        c.icon("bulwark", "taunt");
        c.icon("purify", "antidote");
        c.icon("sanctuary", "heal");
        c.icon("potion", "potion");
        c.icon("ether", "ether");
        c.icon("antidote", "antidote");
        c.icon("bomb", "bomb");
        c.icon("queen_fang", "fang");
        c.icon("thunder", "thunder");
        c.icon("earthquake", "quake");
        c.flavor("shield_wall", "Брам поднимает щит над всеми: партия в Защите и немного лечится.");
        c.flavor("war_cry", "Рёв, от которого стынет кровь у врагов: следующий удар каждого союзника x1.5.");
        c.flavor("mass_cure", "Свет на всю партию: лечит и снимает яд, огонь и кровотечение.");
        c.flavor("haste_all", "Ветер в спину: вся партия ускоряется на 2 хода.");
        c.flavor("meteor", "С неба падает пылающий камень: огромный урон огнём по всем врагам.");
        c.flavor("shadow_strike", "Шаг в тень и удар в спину: очень сильный, рана кровоточит.");
        c.flavor("frost_nova", "Волна холода от земли: лёд по всем врагам, замедляет. По мокрым — заморозка.");
        c.flavor("tempest", "Буря с ливнем и молниями: бьёт всех и делает мокрыми.");
        c.flavor("earth_spike", "Из-под земли бьёт каменный шип и пробивает броню.");
        c.flavor("bone_wall", "Скелет собирает кости в панцирь: Защита на 2 хода.");
        c.flavor("war_stomp", "Трорк топает так, что всех сбивает с ног: урон и замедление.");
        c.flavor("goblin_bomb", "Гоблин швыряет самодельную бомбу во всю партию.");
        c.flavor("frenzy_roar", "Враг ревёт и копит ярость: следующий удар x1.5.");
        c.flavor("pounce", "Волк прыгает на самую слабую цель.");
        c.flavor("slime_split", "Слизни обволакивают союзников: лечение и Реген.");
        c.icon("shield_wall", "shield");
        c.icon("war_cry", "focus");
        c.icon("mass_cure", "heal");
        c.icon("haste_all", "haste");
        c.icon("meteor", "fire");
        c.icon("shadow_strike", "bleed");
        c.icon("frost_nova", "ice");
        c.icon("tempest", "thunder");
        c.icon("earth_spike", "quake");
        c.icon("bone_wall", "shield");
        c.icon("war_stomp", "quake");
        c.icon("goblin_bomb", "bomb");
        c.icon("frenzy_roar", "focus");
        c.icon("pounce", "bleed");
        c.icon("slime_split", "regen");
    }

    private static void demoElements(Content c) {
        for (String id : List.of("fire_slash", "fireball", "item_bomb", "dawn_strike")) c.element(id, Element.FIRE);
        for (String id : List.of("blizzard", "ice_lance")) c.element(id, Element.ICE);
        for (String id : List.of("thunder", "chain_lightning", "storm_blade")) c.element(id, Element.THUNDER);
        for (String id : List.of("water_jet", "goo_spit")) c.element(id, Element.WATER);
        c.element("earthquake", Element.EARTH);
        c.element("earth_spike", Element.EARTH);
        c.element("meteor", Element.FIRE);
        c.element("goblin_bomb", Element.FIRE);
        c.element("frost_nova", Element.ICE);
        c.element("tempest", Element.THUNDER);
        for (String id : List.of("venom_bite", "acid_rain", "item_queen_fang", "venom_edge")) c.element(id, Element.POISON);
    }

    /**
     * Комбо стихий. Порядок важен: проверяются сверху вниз, каждое срабатывает один раз за удар.
     * Логика: вода делает цель уязвимой к молнии и льду, огонь гасится водой и взрывает яд,
     * лёд + удар раскалывает, огонь растапливает лёд.
     */
    private static void demoCombos(Content c) {
        c.add(new Combo("electrocute", "Электрошок", Element.THUNDER, "wet", 1.5, 0, true, "stun", 1, false,
                "урон x1.5 и оглушение"));
        c.add(new Combo("freeze", "Заморозка", Element.ICE, "wet", 1.0, 0, true, "frozen", 1, false,
                "цель замерзает и пропускает ход"));
        c.add(new Combo("steam", "Пар", Element.FIRE, "wet", 0.7, 0, true, null, 0, true,
                "вода гасит огонь: урон x0.7, горения нет"));
        c.add(new Combo("toxic_blast", "Токсичный взрыв", Element.FIRE, "poison", 1.0, 0.12, true, null, 0, false,
                "яд взрывается: +12% макс. HP урона, яд снят"));
        c.add(new Combo("shatter", "Раскол", Element.PHYSICAL, "frozen", 2.0, 0, true, null, 0, false,
                "удар по замёрзшей цели: урон x2, лёд разбит"));
        c.add(new Combo("thaw", "Оттепель", Element.FIRE, "frozen", 1.3, 0, true, null, 0, false,
                "огонь растапливает лёд: урон x1.3"));
        c.add(new Combo("extinguish", "Тушение", Element.WATER, "burn", 1.0, 0, true, null, 0, false,
                "вода тушит горение"));
        c.add(new Combo("overload", "Перегрузка", Element.THUNDER, "armor_break", 1.3, 0, false, null, 0, false,
                "молния бьёт по пробитой броне: урон x1.3"));
        c.add(new Combo("open_wound", "Открытая рана", Element.PHYSICAL, "bleed", 1.2, 0, false, null, 0, false,
                "удар по кровоточащей цели: урон x1.2"));
        c.add(new Combo("quake_shatter", "Раскол", Element.EARTH, "frozen", 1.8, 0, true, null, 0, false,
                "землетрясение разбивает лёд: урон x1.8"));
    }

    private static void demoPassives(Content c) {
        c.add(new PassiveDef(Passives.KEEN_EYE, "Острый глаз", "+10% шанс критического удара."));
        c.add(new PassiveDef(Passives.BLOODTHIRST, "Жажда крови", "Лечит 15% от нанесённого урона."));
        c.add(new PassiveDef(Passives.PYROMANIAC, "Пироман", "+25% урона по горящим целям."));
        c.add(new PassiveDef(Passives.EXECUTIONER, "Палач", "+40% урона по целям, у которых меньше 30% HP."));
        c.add(new PassiveDef(Passives.COMBO_MASTER, "Мастер комбо", "Комбо стихий наносят на 30% больше урона."));
        c.add(new PassiveDef(Passives.ELEMENTALIST, "Стихийник", "+15% к шансу наложить статус."));
        c.add(new PassiveDef(Passives.MANA_FLOW, "Поток маны", "+3 MP в начале каждого хода."));
        c.add(new PassiveDef(Passives.FIRST_STRIKE, "Первый удар", "Начинает бой с +2 AP."));
        c.add(new PassiveDef(Passives.THICK_SKIN, "Толстая кожа", "Начинает бой под Защитой (половина урона до первого хода)."));
        c.add(new PassiveDef(Passives.GUARDIAN, "Страж", "Получает на 15% меньше урона."));
        c.add(new PassiveDef(Passives.HEALER_GRACE, "Благодать", "Лечение сильнее на 25%."));
        c.add(new PassiveDef(Passives.PALADIN, "Паладин", "Гибрид защитника и целителя: начинает бой с Регеном (3 хода) и Защитой."));
        c.add(new PassiveDef(Passives.BATTLE_MAGE, "Боевой маг", "Гибрид целителя и бойца: магический урон и лечение +20%."));
        c.add(new PassiveDef(Passives.THORNS, "Шипы", "Возвращает 20% полученного урона тому, кто ударил."));
        c.add(new PassiveDef(Passives.SECOND_CHANCE, "Второй шанс", "Раз за бой смертельный удар оставляет 1 HP."));
        c.add(new PassiveDef(Passives.ARCANE_SURGE, "Всплеск магии", "Магический урон +15%."));
        c.add(new PassiveDef(Passives.TACTICIAN, "Тактик", "+1 AP в начале каждого хода."));
        c.add(new PassiveDef(Passives.SWIFT, "Стремительность", "Скорость +20%: ходит чаще."));
        c.add(new PassiveDef(Passives.ASSASSIN, "Убийца", "+5% шанс крита, криты бьют x2 вместо x1.5."));
        c.add(new PassiveDef(Passives.BERSERKER, "Берсерк", "Гибрид бойца и защитника: +35% урона, пока у себя меньше половины HP."));
    }

    /**
     * Герои партии: характеристики, рост за уровень и круговая сетка навыков.
     * Порядок узлов: центр, 6 узлов первого кольца (сверху по часовой), 12 второго, 6 третьего, 12 внешних (см. Progression.Grid).
     */
    private static void demoHeroes(Content c) {
        // Сетка: 1-6 кольцо 1, 7-18 кольцо 2, 19-24 внешнее. Сектора: защитник 1,2,7,8,9,19; целитель 3,4,11,12,13,21;
        // боец 5,6,15,16,17,23; мосты 10/20 (защ.+цел.), 14/22 (цел.+боец), 18/24 (боец+защ.).
        c.add(new Progression.HeroDef("hero", "Герой", "Мечник", Progression.Role.DPS, new Stats(120, 20, 22, 8, 12, 12),
                new Stats(12, 2, 3, 1, 2, 1), List.of("fire_slash", "shield_bash", "focus"), grid(
                        // кольцо 1: защ., защ., цел., цел., боец, боец
                        st(Progression.Stat.DEF, 2), ab("provoke"), st(Progression.Stat.MAG, 2), ab("cure"),
                        st(Progression.Stat.ATK, 3), ab("bleeding_cut"),
                        // кольцо 2: 7-9 защ., 10 мост, 11-13 цел., 14 мост, 15-17 боец, 18 мост
                        st(Progression.Stat.HP, 25), pa(Passives.GUARDIAN), ab("iron_wall"), ab("second_wind"),
                        st(Progression.Stat.MP, 8), pa(Passives.HEALER_GRACE), ab("purify"), pa(Passives.COMBO_MASTER),
                        pa(Passives.KEEN_EYE), pa(Passives.EXECUTIONER), ab("storm_blade"), ab("whirlwind"),
                        // внешнее: защ., мост, цел., мост, боец, мост
                        ab("bulwark"), pa(Passives.PALADIN), ab("rally"), pa(Passives.BATTLE_MAGE),
                        ab("execute"), pa(Passives.BERSERKER),
                        // внешнее кольцо 25-36: защ. 25-27, мост 28, цел. 29-31, мост 32, боец 33-35, мост 36
                        st(Progression.Stat.HP, 40), pa(Passives.THORNS), st(Progression.Stat.DEF, 4), pa(Passives.SECOND_CHANCE),
                        st(Progression.Stat.MAG, 4), ab("mass_cure"), st(Progression.Stat.SPD, 3), ab("war_cry"),
                        st(Progression.Stat.ATK, 5), ab("shadow_strike"), pa(Passives.ASSASSIN), pa(Passives.TACTICIAN))));
        c.add(new Progression.HeroDef("lyra", "Лира", "Чародейка", Progression.Role.HEALER, new Stats(85, 70, 9, 24, 7, 14),
                new Stats(7, 8, 1, 3, 1, 1), List.of("fireball", "blizzard", "cure", "haste_spell"), grid(
                        st(Progression.Stat.HP, 12), ab("barrier"), st(Progression.Stat.MAG, 3), ab("renew"),
                        st(Progression.Stat.MAG, 3), ab("water_jet"),
                        st(Progression.Stat.DEF, 2), pa(Passives.THICK_SKIN), ab("provoke"), pa(Passives.MANA_FLOW),
                        st(Progression.Stat.MP, 10), pa(Passives.HEALER_GRACE), ab("purify"), ab("thunder"),
                        pa(Passives.ELEMENTALIST), pa(Passives.COMBO_MASTER), ab("ice_lance"), pa(Passives.FIRST_STRIKE),
                        ab("bulwark"), pa(Passives.PALADIN), ab("sanctuary"), pa(Passives.BATTLE_MAGE),
                        ab("chain_lightning"), pa(Passives.BERSERKER),
                        st(Progression.Stat.HP, 25), pa(Passives.SECOND_CHANCE), st(Progression.Stat.DEF, 3), ab("haste_all"),
                        st(Progression.Stat.MAG, 5), ab("mass_cure"), pa(Passives.ARCANE_SURGE), ab("frost_nova"),
                        st(Progression.Stat.MP, 15), ab("meteor"), ab("tempest"), pa(Passives.TACTICIAN))));
        c.add(new Progression.HeroDef("bram", "Брам", "Страж", Progression.Role.TANK, new Stats(160, 15, 18, 6, 18, 8),
                new Stats(16, 1, 2, 1, 3, 0), List.of("cleave", "rally"), grid(
                        st(Progression.Stat.DEF, 3), ab("provoke"), st(Progression.Stat.MP, 5), ab("second_wind"),
                        st(Progression.Stat.ATK, 3), ab("ground_slam"),
                        st(Progression.Stat.HP, 30), pa(Passives.GUARDIAN), ab("iron_wall"), pa(Passives.THICK_SKIN),
                        st(Progression.Stat.MAG, 2), pa(Passives.HEALER_GRACE), ab("purify"), pa(Passives.FIRST_STRIKE),
                        pa(Passives.BLOODTHIRST), pa(Passives.KEEN_EYE), ab("sunder"), ab("armor_pierce"),
                        ab("bulwark"), pa(Passives.PALADIN), ab("sanctuary"), pa(Passives.BATTLE_MAGE),
                        ab("earthquake"), pa(Passives.BERSERKER),
                        st(Progression.Stat.HP, 50), ab("shield_wall"), pa(Passives.THORNS), pa(Passives.SECOND_CHANCE),
                        st(Progression.Stat.MAG, 3), ab("mass_cure"), st(Progression.Stat.MP, 8), ab("war_cry"),
                        st(Progression.Stat.ATK, 5), ab("earth_spike"), pa(Passives.SWIFT), st(Progression.Stat.DEF, 5))));
    }

    private static List<Progression.GridNode> grid(Progression.GridNode... nodes) {
        List<Progression.GridNode> out = new java.util.ArrayList<>();
        out.add(Progression.GridNode.start());
        out.addAll(List.of(nodes));
        return out;
    }

    private static Progression.GridNode st(Progression.Stat s, int v) { return Progression.GridNode.stat(s, v); }
    private static Progression.GridNode ab(String id) { return Progression.GridNode.ability(id); }
    private static Progression.GridNode pa(String id) { return Progression.GridNode.passive(id); }

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
