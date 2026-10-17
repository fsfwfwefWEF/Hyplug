package dev.jrpg.battle.demo;

import dev.jrpg.battle.*;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.stream.Collectors;

/**
 * Консольный «адаптер»: тот же контракт, что будет у Hytale-плагина, только вместо камеры и частиц — текст.
 * Запуск: java -cp build dev.jrpg.battle.demo.ConsoleDemo [--auto] [--seed N]
 */
public final class ConsoleDemo implements BattleListener {
    private final boolean auto;
    private final Scanner in = new Scanner(System.in);
    private Battle battle;
    private EnemyAI autoPilot;

    private ConsoleDemo(boolean auto) {
        this.auto = auto;
    }

    public static void main(String[] args) throws Exception {
        boolean auto = List.of(args).contains("--auto");
        long seed = 42;
        String only = null;
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--seed")) seed = Long.parseLong(args[i + 1]);
            if (args[i].equals("--encounter")) only = args[i + 1];
        }

        Content content = Content.demo();
        GameData data = GameData.load(content, null);
        WorldProgress progress = new WorldProgress();
        Map<String, Integer> inventory = Content.demoInventory();
        ConsoleDemo demo = new ConsoleDemo(auto);
        demo.autoPilot = new EnemyAI(content, new Random(seed + 1));

        while (true) {
            List<Encounter> available = data.encounters().stream()
                    .filter(e -> progress.isAvailable(e, System.currentTimeMillis())).toList();
            Encounter encounter;
            if (only != null) {
                encounter = data.encounter(only);
            } else {
                System.out.println();
                System.out.println("=== КАРТА: встречи, расставленные в encounters.json ===");
                for (int i = 0; i < available.size(); i++) {
                    Encounter e = available.get(i);
                    System.out.printf("  %d) %s%s  @ (%.0f, %.0f, %.0f)%n", i + 1, e.name(),
                            e.unique() ? " [УНИКАЛЬНЫЙ БОСС]" : "", e.location().x(), e.location().y(), e.location().z());
                }
                System.out.println("  0) выход");
                if (available.isEmpty()) return;
                int n = auto ? 1 : demo.readInt(0, available.size());
                if (n == 0) return;
                encounter = available.get(n - 1);
            }

            System.out.println();
            System.out.println("Партия входит в зону «" + encounter.name() + "»…");
            demo.battle = data.createBattle(encounter, Content.demoParty(), inventory, demo, seed++);
            demo.battle.start(Battle.Opening.NORMAL);
            // Цикл «драйвера»: в Hytale то же самое делают UI-события и SequencePlayer по тикам мира.
            while (demo.battle.phase() != Battle.Phase.ENDED) {
                if (demo.battle.phase() == Battle.Phase.PRESENTING) demo.battle.acknowledge();
                else if (demo.battle.phase() == Battle.Phase.AWAIT_COMMAND) demo.askCommand();
            }
            if (demo.battle.outcome() == Battle.Outcome.VICTORY) {
                progress.markCleared(encounter, System.currentTimeMillis());
                Map<String, Integer> loot = data.rollLoot(encounter, new Random(seed));
                loot.forEach((item, cnt) -> inventory.merge(item, cnt, Integer::sum));
                System.out.println("Награда: " + encounter.exp() + " опыта, предметы: "
                        + (loot.isEmpty() ? "нет" : loot.entrySet().stream()
                        .map(en -> content.item(en.getKey()).name() + " x" + en.getValue())
                        .collect(Collectors.joining(", "))));
                if (encounter.unique()) System.out.println("Этот босс побеждён навсегда и больше не появится.");
            }
            if (auto || only != null) return;
        }
    }

    @Override
    public void onDialogue(Combatant speaker, String text) {
        System.out.println(speaker == null ? "  * " + text : "  " + speaker.name + ": «" + text + "»");
    }

    @Override
    public void onSummoned(Combatant c) {
        System.out.println("  + Появляется " + c.name + "!");
    }

    @Override
    public void onPhaseChange(Combatant boss, String phaseName) {
        System.out.println("  !!! " + boss.name + " — новая фаза: " + phaseName + " [катсцена/камера на босса]");
    }

    @Override
    public void onBattleStart(Battle b) {
        System.out.println("=== БОЙ НАЧАЛСЯ === [камера: общий план]");
    }

    @Override
    public void onTurnStart(Combatant actor, List<Combatant> forecast) {
        System.out.println();
        System.out.println("--- Ход: " + actor.name + " [камера → " + actor.name + ", синее кольцо]  очередь: "
                + forecast.stream().map(c -> c.name).collect(Collectors.joining(" > ")));
    }

    @Override
    public void onStatusTick(Combatant t, StatusInstance s, int delta) {
        System.out.printf("  %s: %s %s%d HP%n", t.name, s.def.name(), delta >= 0 ? "+" : "", delta);
    }

    @Override
    public void onStatusExpired(Combatant t, StatusInstance s) {
        System.out.println("  " + t.name + ": «" + s.def.name() + "» прошло");
    }

    @Override
    public void onTurnSkipped(Combatant actor, String reason) {
        System.out.println("  " + actor.name + " пропускает ход (" + reason + ")");
    }

    @Override
    public void onActionExecuted(ActionResult r) {
        System.out.println("  " + r.actor().name + " → " + r.actionName() + "  [анимация: " + r.sequenceId() + "]");
        if (r.fleeAttempt()) System.out.println(r.fled() ? "  Удалось сбежать!" : "  Сбежать не вышло");
        for (ActionResult.Hit h : r.hits()) {
            StringBuilder sb = new StringBuilder("    " + h.target().name + ":");
            if (h.damage() > 0) sb.append(" -").append(h.damage()).append(" HP").append(h.crit() ? " КРИТ!" : "");
            if (h.heal() > 0) sb.append(" +").append(h.heal()).append(" HP");
            if (h.mpRestore() > 0) sb.append(" +").append(h.mpRestore()).append(" MP");
            if (!h.statusesApplied().isEmpty()) sb.append(" статусы ").append(h.statusesApplied());
            if (!h.statusesCured().isEmpty()) sb.append(" снято ").append(h.statusesCured());
            System.out.println(sb);
        }
    }

    @Override
    public void onCombatantDown(Combatant c) {
        System.out.println("  ✝ " + c.name + " повержен");
    }

    @Override
    public void onBattleEnd(Battle.Outcome outcome) {
        System.out.println();
        System.out.println("=== КОНЕЦ БОЯ: " + outcome + " === [камера: вернуть игроку]");
        printState();
    }

    private void askCommand() {
        Combatant actor = battle.current();
        printState();
        if (auto) {
            submitSafely(autoPilot.choose(battle, actor));
            return;
        }
        System.out.println(actor.name + ": 1) Атака  2) Способности  3) Предметы  4) Защита  5) Бегство");
        switch (readInt(1, 5)) {
            case 1 -> submitSafely(new BattleAction.Attack(pickTarget(actor, TargetType.SINGLE_ENEMY)));
            case 2 -> {
                List<Ability> list = actor.abilities.stream().map(battle.content()::ability).toList();
                for (int i = 0; i < list.size(); i++) {
                    Ability a = list.get(i);
                    System.out.printf("  %d) %s  [%d AP, %d MP]%s%n", i + 1, a.name(), a.apCost(), a.mpCost(),
                            battle.canAfford(actor, a) ? "" : "  (недоступно)");
                }
                System.out.println("  0) назад");
                int n = readInt(0, list.size());
                if (n == 0) return;
                Ability a = list.get(n - 1);
                submitSafely(new BattleAction.UseAbility(a.id(), pickTarget(actor, a.target())));
            }
            case 3 -> {
                List<String> ids = battle.inventory().entrySet().stream().filter(e -> e.getValue() > 0)
                        .map(java.util.Map.Entry::getKey).sorted().toList();
                for (int i = 0; i < ids.size(); i++) {
                    System.out.printf("  %d) %s x%d%n", i + 1, battle.content().item(ids.get(i)).name(),
                            battle.inventory().get(ids.get(i)));
                }
                System.out.println("  0) назад");
                int n = readInt(0, ids.size());
                if (n == 0) return;
                Item item = battle.content().item(ids.get(n - 1));
                submitSafely(new BattleAction.UseItem(item.id(), pickTarget(actor, item.effect().target())));
            }
            case 4 -> submitSafely(new BattleAction.Defend());
            default -> submitSafely(new BattleAction.Flee());
        }
    }

    private String pickTarget(Combatant actor, TargetType type) {
        if (!type.needsTargetChoice()) return null;
        List<Combatant> options = battle.alive(type.targetsEnemies() ? actor.side.opposite() : actor.side);
        for (int i = 0; i < options.size(); i++) System.out.println("    " + (i + 1) + ") " + options.get(i).name);
        return options.get(readInt(1, options.size()) - 1).id;
    }

    private void submitSafely(BattleAction action) {
        try {
            battle.submit(action);
        } catch (IllegalArgumentException e) {
            System.out.println("  ! " + e.getMessage());
        }
    }

    int readInt(int min, int max) {
        while (true) {
            System.out.print("> ");
            if (!in.hasNextLine()) System.exit(0);
            try {
                int v = Integer.parseInt(in.nextLine().trim());
                if (v >= min && v <= max) return v;
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void printState() {
        for (Combatant c : battle.combatants()) {
            if (!c.isAlive()) continue;
            System.out.printf("  [%s] %-24s HP %3d/%-3d MP %2d AP %d %s%n", c.side == Side.PARTY ? "P" : "E",
                    c.name, c.hp, c.stats.maxHp(), c.mp, c.ap, c.statuses.isEmpty() ? "" : c.statuses);
        }
    }
}
