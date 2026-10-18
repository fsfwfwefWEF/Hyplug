package dev.jrpg.battle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Описания для интерфейса: что делает способность/предмет/статус, иконки, «класс» бойца.
 * Тексты собираются из данных (урон, стоимость, статусы), поэтому всегда совпадают с правилами боя.
 * Художественное описание (flavor) задаётся в {@link Content#flavor(String)}.
 */
public final class Describe {
    private Describe() {
    }

    // ---------- способности ----------

    public static String ability(Content content, Ability a) {
        List<String> lines = new ArrayList<>();
        String flavor = content.flavor(a.id());
        if (!flavor.isEmpty()) lines.add(flavor);
        lines.add("Цель: " + target(a.target()));
        String element = content.element(a);
        if (a.damagePower() > 0) {
            lines.add("Урон: " + pct(a.damagePower()) + " от " + (a.magical() ? "магии" : "силы")
                    + (a.magical() ? " (защита цели влияет слабо)" : ""));
        }
        if (a.healPower() > 0) lines.add("Лечение: " + pct(a.healPower()) + " макс. HP цели");
        if (a.mpRestore() > 0) lines.add("Восстанавливает " + a.mpRestore() + " MP");
        for (StatusApply sa : a.applies()) {
            StatusDef s = content.status(sa.statusId());
            String chance = sa.chance() >= 1.0 ? "" : pct(sa.chance()) + " шанс: ";
            lines.add(chance + s.name() + " на " + turns(sa.turns()) + " (" + statusShort(s) + ")");
        }
        if (!a.cures().isEmpty()) {
            lines.add("Снимает: " + String.join(", ", a.cures().stream().map(id -> content.status(id).name()).toList()));
        }
        lines.add(cost(a));
        if (!element.isEmpty() && a.damagePower() > 0) {
            lines.add("Стихия: " + Element.name(element));
            for (Combo k : content.combos()) {
                if (k.element().equals(element)) {
                    lines.add("Комбо по цели с «" + content.status(k.requires()).name() + "»: " + k.name() + " — " + k.text());
                }
            }
        }
        if (a.weight() > 100) lines.add("Тяжёлое действие: следующий ход наступит позже");
        else if (a.weight() < 100) lines.add("Быстрое действие: следующий ход наступит раньше");
        return String.join("\n", lines);
    }

    public static String cost(Ability a) {
        List<String> parts = new ArrayList<>();
        if (a.apCost() > 0) parts.add(a.apCost() + " AP");
        if (a.mpCost() > 0) parts.add(a.mpCost() + " MP");
        String c = parts.isEmpty() ? "Бесплатно" : "Стоимость: " + String.join(", ", parts);
        if (a.apGain() > 0) c += ", даёт +" + a.apGain() + " AP";
        return c;
    }

    public static String target(TargetType t) {
        return switch (t) {
            case SELF -> "на себя";
            case SINGLE_ALLY -> "один союзник";
            case SINGLE_ENEMY -> "один враг";
            case ALL_ALLIES -> "все союзники";
            case ALL_ENEMIES -> "все враги";
        };
    }

    // ---------- статусы ----------

    /** Коротко, что делает статус («-8% HP в начале хода»). */
    public static String statusShort(StatusDef s) {
        return switch (s.kind()) {
            case DOT_PERCENT -> "-" + num(s.power()) + "% HP в начале хода";
            case DOT_FLAT -> "-" + num(s.power()) + " HP в конце хода" + (s.stackable() ? " за стак, до " + s.maxStacks() : "");
            case HOT_PERCENT -> "+" + num(s.power()) + "% HP в начале хода";
            case SPEED_MULT -> s.power() >= 1 ? "ходит чаще (x" + num(s.power()) + ")" : "ходит реже (x" + num(s.power()) + ")";
            case SKIP_TURN -> "пропускает ход";
            case DAMAGE_TAKEN_MULT -> "получает " + pct(s.power()) + " урона";
            case NEXT_ACTION_MULT -> "следующая атака x" + num(s.power());
            case MARKER -> "особая метка для комбо стихий";
            case TAUNT -> "враги бьют только его";
        };
    }

    public static String status(StatusInstance s) {
        return s.def.name() + (s.stacks > 1 ? " x" + s.stacks : "") + " — " + statusShort(s.def)
                + ", осталось " + turns(s.turnsLeft);
    }

    // ---------- бойцы ----------

    /** Полная карточка бойца: характеристики, статусы, иммунитеты, способности с описанием. */
    public static String combatant(Content content, Combatant c) {
        List<String> lines = new ArrayList<>();
        lines.add(roleTitle(c) + "   HP " + c.hp + "/" + c.stats.maxHp()
                + (c.stats.maxMp() > 0 ? "   MP " + c.mp + "/" + c.stats.maxMp() : "") + "   AP " + c.ap);
        Stats s = c.stats;
        lines.add("Сила " + s.atk() + "  Магия " + s.mag() + "  Защита " + s.def() + "  Скорость " + s.spd());
        if (!c.statuses.isEmpty()) {
            lines.add("Статусы:");
            for (StatusInstance st : c.statuses) lines.add("  " + status(st));
        }
        if (!c.immunities.isEmpty()) {
            lines.add("Иммунитет: " + String.join(", ", c.immunities.stream().map(id -> content.status(id).name()).toList()));
        }
        List<String> weak = new ArrayList<>();
        for (Combo k : content.combos()) {
            if (c.status(k.requires()) != null) weak.add(k.name() + " (" + Element.name(k.element()) + ")");
        }
        if (!weak.isEmpty()) lines.add("Уязвим к комбо: " + String.join(", ", weak));
        if (!c.passives.isEmpty()) {
            lines.add("Пассивные: " + String.join(", ", c.passives.stream().map(id -> content.passive(id).name()).toList()));
        }
        if (!c.abilities.isEmpty()) {
            lines.add("Способности:");
            for (String id : c.abilities) {
                Ability a = content.ability(id);
                lines.add("  " + a.name() + " — " + oneLine(content, a));
            }
        }
        return String.join("\n", lines);
    }

    /** Способность одной строкой (для списка способностей врага). */
    public static String oneLine(Content content, Ability a) {
        List<String> parts = new ArrayList<>();
        parts.add(target(a.target()));
        if (a.damagePower() > 0) parts.add("урон " + pct(a.damagePower()));
        if (a.healPower() > 0) parts.add("лечение " + pct(a.healPower()));
        for (StatusApply sa : a.applies()) parts.add(content.status(sa.statusId()).name());
        if (!a.cures().isEmpty()) parts.add("снимает статусы");
        return String.join(", ", parts);
    }

    public static String roleTitle(Combatant c) {
        if (c.boss) return "БОСС";
        String t = ROLE_TITLES.get(c.id);
        if (t != null) return t;
        return c.side == Side.ENEMY ? "Враг" : "Союзник";
    }

    private static final Map<String, String> ROLE_TITLES = Map.of(
            "hero", "Мечник", "lyra", "Чародейка", "bram", "Страж");

    // ---------- иконки ----------

    /** Ключ иконки способности (файл Assets/Icons/<ключ>.png в asset pack мода). */
    public static String abilityIcon(Content content, Ability a) {
        String explicit = content.icon(a.id());
        if (explicit != null) return explicit;
        for (StatusApply sa : a.applies()) {
            switch (sa.statusId()) {
                case "burn" -> { return "fire"; }
                case "poison" -> { return "poison"; }
                case "stun" -> { return "stun"; }
                case "haste" -> { return "haste"; }
                case "regen" -> { return "regen"; }
                case "slow" -> { return a.magical() ? "ice" : "web"; }
                case "guard", "brood_shield" -> { return "shield"; }
                case "charged" -> { return "focus"; }
                case "wet" -> { return "water"; }
                case "bleed" -> { return "bleed"; }
                case "armor_break" -> { return "crack"; }
                case "frozen" -> { return "ice"; }
                case "taunt" -> { return "taunt"; }
                default -> { }
            }
        }
        if (a.healPower() > 0 || !a.cures().isEmpty()) return "heal";
        if (a.mpRestore() > 0) return "ether";
        if (a.damagePower() > 0) {
            if (a.magical()) return "magic";
            return a.target() == TargetType.ALL_ENEMIES ? "cleave" : "sword";
        }
        return "magic";
    }

    public static String itemIcon(Content content, Item item) {
        String explicit = content.icon(item.id());
        if (explicit != null) return explicit;
        return item.effect() == null ? "bag" : abilityIcon(content, item.effect());
    }

    /** Описание предмета для рюкзака: вид, художественный текст и что делает в бою. */
    public static String item(Content content, Item item, int count) {
        StringBuilder sb = new StringBuilder(item.kind().label + "   в рюкзаке: " + count);
        String flavor = content.flavor(item.id());
        if (flavor.isEmpty()) flavor = item.description();
        if (!flavor.isEmpty()) sb.append("\n").append(flavor);
        if (item.effect() != null) sb.append("\nВ бою: ").append(ability(content, item.effect()));
        else sb.append("\nВ бою не используется.");
        return sb.toString();
    }

    /** Ключ иконки статуса: Assets/Status/<id>.png. */
    public static String statusIcon(String statusId) {
        return statusId;
    }

    /** Ключ портрета: Assets/Portraits/<ключ>.png (герои по id, враги по шаблону). */
    public static String portrait(Combatant c) {
        return c.templateId != null ? c.templateId : c.id;
    }

    // ---------- прокачка ----------

    /** Название узла сетки. */
    public static String nodeTitle(Content content, Progression.Hero hero, Progression.GridNode n) {
        return switch (n.kind()) {
            case START -> hero.def.name() + ": " + hero.def.title();
            case STAT -> n.stat().label + " +" + n.value();
            case ABILITY -> content.ability(n.ref()).name();
            case PASSIVE -> content.passive(n.ref()).name();
        };
    }

    public static String nodeKind(Progression.GridNode n) {
        return switch (n.kind()) {
            case START -> "Начало пути";
            case STAT -> "Характеристика";
            case ABILITY -> "Способность";
            case PASSIVE -> "Пассивное умение";
        };
    }

    /** Сектор узла: «Защитник», «Защитник + Целитель» (мост) или пусто для центра. */
    public static String sector(int index) {
        return String.join(" + ", Progression.Grid.sector(index).stream().map(r -> r.label).toList());
    }

    /** Описание узла сетки: что даёт, сколько стоит, почему нельзя открыть. */
    public static String node(Content content, Progression.Hero hero, int index) {
        Progression.GridNode n = hero.node(index);
        List<String> lines = new ArrayList<>();
        switch (n.kind()) {
            case START -> {
                lines.add("Центр сетки. Открывай соседние узлы, чтобы двигаться к внешним кольцам.");
                lines.add("");
                lines.add("Роль героя: " + hero.def.role().label + " (" + hero.def.role().about + "). "
                        + "Узлы своей роли во 2 и 3 кольце на 1 очко дешевле.");
                for (Progression.Role r : Progression.Role.values()) lines.add(r.label + ": " + r.about);
            }
            case STAT -> lines.add("Навсегда повышает характеристику «" + n.stat().label + "» на " + n.value() + ".");
            case ABILITY -> lines.add(ability(content, content.ability(n.ref())));
            case PASSIVE -> lines.add(content.passive(n.ref()).text());
        }
        if (index != 0) {
            lines.add("");
            boolean own = Progression.Grid.sector(index).contains(hero.def.role());
            lines.add("Сектор: " + sector(index) + (own ? " (роль героя)" : ""));
            lines.add("Цена: " + hero.cost(index) + " очк.   Уровень: " + Progression.Grid.minLevel(index) + "+");
            String why = hero.whyCantUnlock(index);
            lines.add(hero.unlocked.contains(index) ? "Открыто" : why == null ? "Можно открыть" : "Нельзя: " + why);
        }
        return String.join("\n", lines);
    }

    /** Ключ иконки узла сетки. */
    public static String nodeIcon(Content content, Progression.Hero hero, Progression.GridNode n) {
        return switch (n.kind()) {
            case START -> "pt_" + hero.def.id();
            case STAT -> switch (n.stat()) {
                case HP -> "heart";
                case MP -> "ether";
                case ATK -> "sword";
                case MAG -> "magic";
                case DEF -> "shield";
                case SPD -> "haste";
            };
            case ABILITY -> abilityIcon(content, content.ability(n.ref()));
            case PASSIVE -> "star";
        };
    }

    // ---------- утилиты ----------

    static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    public static String turns(int n) {
        int m10 = n % 10, m100 = n % 100;
        String w = (m10 == 1 && m100 != 11) ? "ход" : (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) ? "хода" : "ходов";
        return n + " " + w;
    }
}
