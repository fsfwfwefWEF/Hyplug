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
        return explicit != null ? explicit : abilityIcon(content, item.effect());
    }

    /** Ключ иконки статуса: Assets/Status/<id>.png. */
    public static String statusIcon(String statusId) {
        return statusId;
    }

    /** Ключ портрета: Assets/Portraits/<ключ>.png (герои по id, враги по шаблону). */
    public static String portrait(Combatant c) {
        return c.templateId != null ? c.templateId : c.id;
    }

    // ---------- утилиты ----------

    static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    static String turns(int n) {
        int m10 = n % 10, m100 = n % 100;
        String w = (m10 == 1 && m100 != 11) ? "ход" : (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) ? "хода" : "ходов";
        return n + " " + w;
    }
}
