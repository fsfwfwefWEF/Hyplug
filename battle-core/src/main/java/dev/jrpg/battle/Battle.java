package dev.jrpg.battle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Пошаговый бой. Машина состояний одного хода:
 * TURN_START (статусы, +AP) → AWAIT_COMMAND (меню/ИИ) → PRESENTING (адаптер играет анимацию)
 * → {@link #acknowledge()} → TURN_END (длительности −1) → следующий ход.
 *
 * Результат действия считается сразу и детерминированно (при одном seed): анимация на правила не влияет.
 */
public final class Battle {
    public enum Phase { NOT_STARTED, AWAIT_COMMAND, PRESENTING, ENDED }
    public enum Outcome { VICTORY, DEFEAT, FLED }
    /** Кто ударил первым в мире: даёт бонус к стартовой инициативе. */
    public enum Opening { NORMAL, PARTY_ADVANTAGE, AMBUSH }

    public static final int FORECAST_SIZE = 8;

    private final Content content;
    private final List<Combatant> combatants;
    /** Общий инвентарь партии: itemId → количество. */
    private final Map<String, Integer> inventory;
    private final BattleListener listener;
    private final Random random;
    private final EnemyAI ai;

    private BattleScript script = BattleScript.NONE;
    private boolean fleeAllowed = true;
    private Phase phase = Phase.NOT_STARTED;
    private Outcome outcome;
    private Combatant current;
    private int pendingWeight = 100;

    public Battle(Content content, List<Combatant> combatants, Map<String, Integer> inventory,
                  BattleListener listener, long seed) {
        this.content = content;
        this.combatants = new ArrayList<>(combatants);
        this.inventory = inventory;
        this.listener = listener;
        this.random = new Random(seed);
        this.ai = new EnemyAI(content, random);
    }

    // ---------- публичное API для адаптера ----------

    public void start(Opening opening) {
        if (phase != Phase.NOT_STARTED) throw new IllegalStateException("Бой уже начат");
        for (Combatant c : combatants) {
            c.ct = random.nextDouble() * 30;
            if (opening == Opening.PARTY_ADVANTAGE && c.side == Side.PARTY) c.ct += 50;
            if (opening == Opening.AMBUSH && c.side == Side.ENEMY) c.ct += 50;
            c.ap = c.side == Side.PARTY && opening == Opening.PARTY_ADVANTAGE ? 2 : 1;
            if (c.passives.contains(Passives.FIRST_STRIKE)) c.ap += 2;
            if (c.passives.contains(Passives.THICK_SKIN)) applyStatus(c, content.status("guard"), 1, false);
            if (c.passives.contains(Passives.PALADIN)) {
                applyStatus(c, content.status("regen"), 3, false);
                applyStatus(c, content.status("guard"), 1, false);
            }
        }
        listener.onBattleStart(this);
        script.onBattleStart(this);
        nextTurn();
    }

    /** Команда игрока для текущего персонажа партии. Бросает IllegalArgumentException, если команда невалидна. */
    public void submit(BattleAction action) {
        if (phase != Phase.AWAIT_COMMAND) throw new IllegalStateException("Сейчас не ждём команду: " + phase);
        validate(current, action);
        execute(current, action);
    }

    /** Адаптер закончил показ анимации — продолжаем бой. */
    public void acknowledge() {
        if (phase != Phase.PRESENTING) throw new IllegalStateException("Нечего подтверждать: " + phase);
        endTurn(current, pendingWeight);
        if (checkEnd()) return;
        nextTurn();
    }

    /** Скрипт встречи: фазы босса, эксклюзивные механики. Ставится до start(). */
    public void setScript(BattleScript script) { this.script = script; }
    public void setFleeAllowed(boolean allowed) { this.fleeAllowed = allowed; }
    public boolean fleeAllowed() { return fleeAllowed; }

    /** Призыв нового бойца посреди боя (например, босс зовёт подмогу). */
    public void summon(Combatant c) {
        c.ct = 0;
        combatants.add(c);
        listener.onSummoned(c);
    }

    /** Реплика в бою: адаптер показывает субтитр/диалоговое окно. */
    public void say(Combatant speaker, String text) {
        listener.onDialogue(speaker, text);
    }

    public void announcePhase(Combatant boss, String phaseName) {
        listener.onPhaseChange(boss, phaseName);
    }

    public Random random() { return random; }
    public Phase phase() { return phase; }
    public Outcome outcome() { return outcome; }
    public Combatant current() { return current; }
    public List<Combatant> combatants() { return java.util.Collections.unmodifiableList(combatants); }
    public Map<String, Integer> inventory() { return inventory; }
    public Content content() { return content; }

    public List<Combatant> alive(Side side) {
        return combatants.stream().filter(c -> c.side == side && c.isAlive()).toList();
    }

    public Combatant byId(String id) {
        return combatants.stream().filter(c -> c.id.equals(id)).findFirst().orElse(null);
    }

    /** Можно ли сейчас использовать способность (для затемнения пунктов меню). */
    public boolean canAfford(Combatant c, Ability a) {
        return c.ap >= a.apCost() && c.mp >= a.mpCost();
    }

    // ---------- ход ----------

    private void nextTurn() {
        while (true) {
            Combatant actor = TurnQueue.advance(combatants);
            current = actor;
            actor.ap = Math.min(Combatant.MAX_AP, actor.ap + 1);
            if (actor.passives.contains(Passives.MANA_FLOW)) actor.mp = Math.min(actor.stats.maxMp(), actor.mp + 3);
            listener.onTurnStart(actor, TurnQueue.forecast(combatants, FORECAST_SIZE));

            tickStatuses(actor, StatusDef.Trigger.TURN_START);
            if (!actor.isAlive()) {
                listener.onCombatantDown(actor);
                if (checkEnd()) return;
                continue;
            }
            if (hasKind(actor, StatusDef.Kind.SKIP_TURN)) {
                listener.onTurnSkipped(actor, "оглушение");
                endTurn(actor, 100);
                if (checkEnd()) return;
                continue;
            }
            script.onTurnStart(this, actor);
            if (checkEnd()) return;
            if (actor.side == Side.ENEMY) {
                BattleAction forced = script.overrideAction(this, actor);
                execute(actor, forced != null ? forced : ai.choose(this, actor));
            } else {
                phase = Phase.AWAIT_COMMAND;
                listener.onAwaitCommand(actor);
            }
            return;
        }
    }

    private void endTurn(Combatant actor, int weight) {
        actor.ct -= weight;
        if (actor.isAlive()) {
            tickStatuses(actor, StatusDef.Trigger.TURN_END);
            if (!actor.isAlive()) listener.onCombatantDown(actor);
        }
        for (var it = actor.statuses.iterator(); it.hasNext(); ) {
            StatusInstance s = it.next();
            if (s.skipNextDecrement) {
                s.skipNextDecrement = false;
                continue;
            }
            if (--s.turnsLeft <= 0) {
                it.remove();
                listener.onStatusExpired(actor, s);
            }
        }
    }

    private void tickStatuses(Combatant c, StatusDef.Trigger trigger) {
        for (StatusInstance s : List.copyOf(c.statuses)) {
            if (s.def.trigger() != trigger || !c.isAlive()) continue;
            int max = c.stats.maxHp();
            int delta = switch (s.def.kind()) {
                case DOT_PERCENT -> -Math.min(c.hp - 1, Math.max(1, (int) (max * s.def.power() / 100.0)));
                case DOT_FLAT -> -Math.min(c.hp, (int) (s.def.power() * s.stacks));
                case HOT_PERCENT -> Math.min(max - c.hp, Math.max(1, (int) (max * s.def.power() / 100.0)));
                default -> 0;
            };
            if (delta == 0 && s.def.kind() != StatusDef.Kind.DOT_PERCENT) continue;
            c.hp += delta;
            listener.onStatusTick(c, s, delta);
        }
    }

    // ---------- действия ----------

    private void validate(Combatant actor, BattleAction action) {
        switch (action) {
            case BattleAction.Attack a -> requireTarget(actor, content.ability(Content.ATTACK), a.targetId());
            case BattleAction.UseAbility u -> {
                if (!actor.abilities.contains(u.abilityId()))
                    throw new IllegalArgumentException(actor.name + " не знает " + u.abilityId());
                Ability ab = content.ability(u.abilityId());
                if (!canAfford(actor, ab)) throw new IllegalArgumentException("Не хватает AP/MP");
                requireTarget(actor, ab, u.targetId());
            }
            case BattleAction.UseItem i -> {
                if (inventory.getOrDefault(i.itemId(), 0) <= 0)
                    throw new IllegalArgumentException("Нет предмета " + i.itemId());
                requireTarget(actor, content.item(i.itemId()).effect(), i.targetId());
            }
            case BattleAction.Defend d -> { }
            case BattleAction.Flee f -> {
                if (!fleeAllowed) throw new IllegalArgumentException("От этого боя не сбежать!");
            }
        }
    }

    private void requireTarget(Combatant actor, Ability ab, String targetId) {
        if (!ab.target().needsTargetChoice()) return;
        Combatant t = targetId == null ? null : byId(targetId);
        Side expected = ab.target().targetsEnemies() ? actor.side.opposite() : actor.side;
        if (t == null || t.side != expected)
            throw new IllegalArgumentException("Неверная цель для " + ab.name());
        // Предметы лечения/воскрешения в будущем могут целиться в павших — пока только живые.
        if (!t.isAlive()) throw new IllegalArgumentException(t.name + " уже повержен");
    }

    private void execute(Combatant actor, BattleAction action) {
        ActionResult result = switch (action) {
            case BattleAction.Attack a -> useAbility(actor, content.ability(Content.ATTACK), a.targetId(), null);
            case BattleAction.UseAbility u -> useAbility(actor, content.ability(u.abilityId()), u.targetId(), null);
            case BattleAction.UseItem i -> {
                inventory.merge(i.itemId(), -1, Integer::sum);
                Item item = content.item(i.itemId());
                yield useAbility(actor, item.effect(), i.targetId(), item.name());
            }
            case BattleAction.Defend d -> useAbility(actor, content.ability(Content.DEFEND), actor.id, null);
            case BattleAction.Flee f -> flee(actor);
        };
        phase = Phase.PRESENTING;
        listener.onActionExecuted(result);
        for (ActionResult.Hit h : result.hits()) {
            if (h.killed()) listener.onCombatantDown(h.target());
        }
        script.afterAction(this, result);
    }

    private ActionResult useAbility(Combatant actor, Ability ab, String targetId, String nameOverride) {
        actor.ap = Math.min(Combatant.MAX_AP, actor.ap - ab.apCost() + ab.apGain());
        actor.mp -= ab.mpCost();
        pendingWeight = ab.weight();

        double actionMult = 1.0;
        if (ab.damagePower() > 0) {
            actionMult = actor.multiplier(StatusDef.Kind.NEXT_ACTION_MULT);
            actor.statuses.removeIf(s -> s.def.kind() == StatusDef.Kind.NEXT_ACTION_MULT);
        }

        List<ActionResult.Hit> hits = new ArrayList<>();
        String element = content.element(ab);
        int lifesteal = 0;
        for (Combatant t : resolveTargets(actor, ab.target(), targetId)) {
            int damage = 0;
            boolean crit = false;
            List<String> applied = new ArrayList<>();
            List<String> combos = new ArrayList<>();
            boolean blockStatuses = false;
            if (ab.damagePower() > 0) {
                // Комбо стихий считаются до урона: они меняют множитель и снимают/вешают статусы.
                double comboMult = 1.0;
                double comboBonus = 0;
                for (Combo k : content.combos()) {
                    if (!k.element().equals(element) || t.status(k.requires()) == null) continue;
                    combos.add(k.id());
                    comboMult *= k.damageMult();
                    comboBonus += t.stats.maxHp() * k.bonusMaxHp();
                    if (k.consume()) t.statuses.removeIf(s -> s.def.id().equals(k.requires()));
                    if (k.blockStatuses()) blockStatuses = true;
                    if (k.applies() != null && !t.immunities.contains(k.applies())) {
                        applyStatus(t, content.status(k.applies()), k.applyTurns(), t == actor);
                        applied.add(k.applies());
                    }
                }
                if (!combos.isEmpty() && actor.passives.contains(Passives.COMBO_MASTER)) {
                    comboMult *= 1.3;
                    comboBonus *= 1.3;
                }
                int power = ab.magical() ? actor.stats.mag() : actor.stats.atk();
                double raw = power * ab.damagePower() * actionMult * (0.9 + random.nextDouble() * 0.2)
                        - t.stats.def() * (ab.magical() ? 0.25 : 0.5);
                double critChance = 0.05 + (actor.passives.contains(Passives.KEEN_EYE) ? 0.10 : 0);
                crit = random.nextDouble() < critChance;
                if (crit) raw *= 1.5;
                raw *= t.multiplier(StatusDef.Kind.DAMAGE_TAKEN_MULT) * comboMult;
                if (actor.passives.contains(Passives.PYROMANIAC) && t.status("burn") != null) raw *= 1.25;
                if (actor.passives.contains(Passives.EXECUTIONER) && t.hp < t.stats.maxHp() * 0.3) raw *= 1.4;
                if (actor.passives.contains(Passives.BATTLE_MAGE) && ab.magical()) raw *= 1.2;
                if (actor.passives.contains(Passives.BERSERKER) && actor.hp < actor.stats.maxHp() * 0.5) raw *= 1.35;
                if (t.passives.contains(Passives.GUARDIAN)) raw *= 0.85;
                damage = Math.min(t.hp, Math.max(1, (int) Math.round(raw + comboBonus)));
                t.hp -= damage;
                if (actor.passives.contains(Passives.BLOODTHIRST)) lifesteal += (int) Math.round(damage * 0.15);
            }
            int heal = 0;
            if (ab.healPower() > 0 && t.isAlive()) {
                double healMult = (actor.passives.contains(Passives.HEALER_GRACE) ? 1.25 : 1.0)
                        * (actor.passives.contains(Passives.BATTLE_MAGE) ? 1.2 : 1.0);
                heal = Math.min(t.stats.maxHp() - t.hp, (int) Math.round(t.stats.maxHp() * ab.healPower() * healMult));
                t.hp += heal;
            }
            int mpBack = 0;
            if (ab.mpRestore() > 0) {
                mpBack = Math.min(t.stats.maxMp() - t.mp, ab.mpRestore());
                t.mp += mpBack;
            }
            List<String> cured = new ArrayList<>();
            for (String cure : ab.cures()) {
                if (t.statuses.removeIf(s -> s.def.id().equals(cure))) cured.add(cure);
            }
            if (t.isAlive() && !blockStatuses) {
                double bonusChance = actor.passives.contains(Passives.ELEMENTALIST) ? 0.15 : 0;
                for (StatusApply sa : ab.applies()) {
                    if (t.immunities.contains(sa.statusId())) continue;
                    if (random.nextDouble() < sa.chance() + bonusChance) {
                        applyStatus(t, content.status(sa.statusId()), sa.turns(), t == actor);
                        if (!applied.contains(sa.statusId())) applied.add(sa.statusId());
                    }
                }
            }
            hits.add(new ActionResult.Hit(t, damage, heal, mpBack, crit, applied, cured,
                    damage > 0 && !t.isAlive(), combos));
        }
        if (lifesteal > 0 && actor.isAlive()) {
            int healed = Math.min(actor.stats.maxHp() - actor.hp, lifesteal);
            if (healed > 0) {
                actor.hp += healed;
                hits.add(new ActionResult.Hit(actor, 0, healed, 0, false, List.of(), List.of(), false, List.of()));
            }
        }
        return new ActionResult(actor, nameOverride != null ? nameOverride : ab.name(), ab.sequenceId(),
                hits, false, false);
    }

    /** Повторное наложение обновляет длительность; stackable-статусы ещё и копят стаки. */
    public static void applyStatus(Combatant t, StatusDef def, int turns, boolean onSelfThisTurn) {
        StatusInstance existing = t.status(def.id());
        if (existing == null) {
            existing = new StatusInstance(def, turns);
            t.statuses.add(existing);
            existing.skipNextDecrement = onSelfThisTurn;
            return;
        }
        existing.skipNextDecrement |= onSelfThisTurn;
        existing.turnsLeft = Math.max(existing.turnsLeft, turns);
        if (def.stackable()) existing.stacks = Math.min(def.maxStacks(), existing.stacks + 1);
    }

    private ActionResult flee(Combatant actor) {
        pendingWeight = 100;
        double party = alive(Side.PARTY).stream().mapToDouble(Combatant::effectiveSpeed).average().orElse(1);
        double enemy = alive(Side.ENEMY).stream().mapToDouble(Combatant::effectiveSpeed).average().orElse(1);
        double chance = Math.clamp(0.5 * party / enemy, 0.1, 0.9);
        boolean ok = random.nextDouble() < chance;
        if (ok) outcome = Outcome.FLED;
        return new ActionResult(actor, "Бегство", ok ? "flee_success" : "flee_fail", List.of(), true, ok);
    }

    private List<Combatant> resolveTargets(Combatant actor, TargetType type, String targetId) {
        return switch (type) {
            case SELF -> List.of(actor);
            case SINGLE_ALLY, SINGLE_ENEMY -> List.of(byId(targetId));
            case ALL_ALLIES -> alive(actor.side);
            case ALL_ENEMIES -> alive(actor.side.opposite());
        };
    }

    private static boolean hasKind(Combatant c, StatusDef.Kind kind) {
        return c.statuses.stream().anyMatch(s -> s.def.kind() == kind);
    }

    private boolean checkEnd() {
        if (outcome == null) {
            if (alive(Side.ENEMY).isEmpty()) outcome = Outcome.VICTORY;
            else if (alive(Side.PARTY).isEmpty()) outcome = Outcome.DEFEAT;
        }
        if (outcome == null) return false;
        phase = Phase.ENDED;
        listener.onBattleEnd(outcome);
        return true;
    }
}
