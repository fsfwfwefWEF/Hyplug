package dev.jrpg.battle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Модель боевого меню, не зависящая от движка: что показать и что делать по нажатию.
 * Адаптер (Hytale UI-страница, консоль) только рисует {@link #title()} и {@link #options()}
 * и передаёт нажатия в {@link #click(int)} / {@link #back()}.
 *
 * Экраны: Главное (Атака/Способности/Предметы/Защита/Бегство) → Способности/Предметы → Выбор цели.
 */
public final class BattleMenu {
    public enum Screen { MAIN, SKILLS, ITEMS, TARGET, WAITING, ENDED }

    public record Option(String label, boolean enabled, String hint) {}

    private final Battle battle;
    private Screen screen = Screen.WAITING;
    /** Что выбрано до экрана цели: "attack", "skill:<id>", "item:<id>". */
    private String pending;
    private List<String> ids = List.of();
    private String error;

    public BattleMenu(Battle battle) {
        this.battle = battle;
    }

    public Screen screen() {
        return switch (battle.phase()) {
            case ENDED -> Screen.ENDED;
            case AWAIT_COMMAND -> screen == Screen.WAITING ? Screen.MAIN : screen;
            default -> Screen.WAITING;
        };
    }

    /** Сброс на главный экран (в начале каждого хода партии). */
    public void reset() {
        screen = Screen.MAIN;
        pending = null;
        error = null;
    }

    /** Последняя ошибка ввода («не хватает AP»), показывается под меню. */
    public String error() { return error; }

    public String title() {
        Combatant c = battle.current();
        return switch (screen()) {
            case MAIN -> "Ход: " + c.name + "  (AP " + c.ap + ", MP " + c.mp + ")";
            case SKILLS -> c.name + ": способности";
            case ITEMS -> "Предметы";
            case TARGET -> "Выбери цель";
            case WAITING -> c == null ? "…" : "Ходит " + c.name + "…";
            case ENDED -> switch (battle.outcome()) {
                case VICTORY -> "ПОБЕДА!";
                case DEFEAT -> "Поражение…";
                case FLED -> "Партия сбежала";
            };
        };
    }

    public List<Option> options() {
        Combatant c = battle.current();
        List<Option> out = new ArrayList<>();
        switch (screen()) {
            case MAIN -> {
                out.add(new Option("Атака", true, "+1 AP"));
                out.add(new Option("Способности", !c.abilities.isEmpty(), ""));
                out.add(new Option("Предметы", battle.inventory().values().stream().anyMatch(n -> n > 0), ""));
                out.add(new Option("Защита", true, "урон -50%, +1 AP"));
                out.add(new Option("Бегство", battle.fleeAllowed(), battle.fleeAllowed() ? "" : "нельзя"));
            }
            case SKILLS -> {
                for (String id : skillIds()) {
                    Ability a = battle.content().ability(id);
                    String cost = (a.apCost() > 0 ? a.apCost() + " AP " : "") + (a.mpCost() > 0 ? a.mpCost() + " MP" : "");
                    out.add(new Option(a.name() + (cost.isBlank() ? "" : "  [" + cost.trim() + "]"),
                            battle.canAfford(c, a), ""));
                }
            }
            case ITEMS -> {
                for (String id : itemIds()) {
                    out.add(new Option(battle.content().item(id).name() + "  x" + battle.inventory().get(id), true, ""));
                }
            }
            case TARGET -> {
                for (Combatant t : targets()) {
                    out.add(new Option(t.name + "  " + t.hp + "/" + t.stats.maxHp() + " HP", true, ""));
                }
            }
            case ENDED -> out.add(new Option("Закрыть", true, ""));
            case WAITING -> { }
        }
        return out;
    }

    public boolean canGoBack() {
        Screen s = screen();
        return s == Screen.SKILLS || s == Screen.ITEMS || s == Screen.TARGET;
    }

    public void back() {
        error = null;
        switch (screen()) {
            case SKILLS, ITEMS -> screen = Screen.MAIN;
            case TARGET -> screen = pending.startsWith("skill:") ? Screen.SKILLS
                    : pending.startsWith("item:") ? Screen.ITEMS : Screen.MAIN;
            default -> { }
        }
    }

    /**
     * Нажатие на пункт. Возвращает готовую команду (её нужно отдать в {@link Battle#submit}),
     * или null, если просто сменился экран / пункт недоступен.
     */
    public BattleAction click(int index) {
        error = null;
        List<Option> opts = options();
        if (index < 0 || index >= opts.size()) return null;
        if (!opts.get(index).enabled()) {
            error = "Недоступно: " + opts.get(index).label();
            return null;
        }
        Combatant c = battle.current();
        switch (screen()) {
            case MAIN -> {
                switch (index) {
                    case 0 -> { return chooseTarget("attack", battle.content().ability(Content.ATTACK)); }
                    case 1 -> screen = Screen.SKILLS;
                    case 2 -> screen = Screen.ITEMS;
                    case 3 -> { return new BattleAction.Defend(); }
                    case 4 -> { return new BattleAction.Flee(); }
                    default -> { }
                }
                return null;
            }
            case SKILLS -> {
                String id = skillIds().get(index);
                return chooseTarget("skill:" + id, battle.content().ability(id));
            }
            case ITEMS -> {
                String id = itemIds().get(index);
                return chooseTarget("item:" + id, battle.content().item(id).effect());
            }
            case TARGET -> {
                return build(pending, targets().get(index).id);
            }
            default -> { return null; }
        }
    }

    private BattleAction chooseTarget(String what, Ability effect) {
        if (!effect.target().needsTargetChoice()) return build(what, null);
        pending = what;
        screen = Screen.TARGET;
        // Если цель одна — не спрашиваем.
        List<Combatant> t = targets();
        if (t.size() == 1) return build(what, t.get(0).id);
        return null;
    }

    private BattleAction build(String what, String targetId) {
        screen = Screen.WAITING;
        if (what.equals("attack")) return new BattleAction.Attack(targetId);
        if (what.startsWith("skill:")) return new BattleAction.UseAbility(what.substring(6), targetId);
        return new BattleAction.UseItem(what.substring(5), targetId);
    }

    /** Текущие цели экрана выбора цели (для подсветки кольцом при наведении). */
    public List<Combatant> targets() {
        if (pending == null) return List.of();
        Ability effect = pending.equals("attack") ? battle.content().ability(Content.ATTACK)
                : pending.startsWith("skill:") ? battle.content().ability(pending.substring(6))
                : battle.content().item(pending.substring(5)).effect();
        Side side = effect.target().targetsEnemies() ? battle.current().side.opposite() : battle.current().side;
        return battle.alive(side);
    }

    private List<String> skillIds() {
        return battle.current().abilities;
    }

    private List<String> itemIds() {
        return battle.inventory().entrySet().stream().filter(e -> e.getValue() > 0)
                .map(Map.Entry::getKey).sorted().toList();
    }

    /** Если сабмит упал (например, цель умерла), возвращаемся в главное меню с текстом ошибки. */
    public void rejected(String message) {
        screen = Screen.MAIN;
        pending = null;
        error = message;
    }
}
