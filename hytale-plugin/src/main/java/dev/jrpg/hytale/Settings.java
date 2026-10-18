package dev.jrpg.hytale;

import dev.jrpg.battle.Json;

import java.util.HashMap;
import java.util.Map;

/** Настройки отображения боя (settings.json): камера, интерфейс, частицы, роли NPC, паузы. */
public final class Settings {
    public final Map<String, String> companionRoles = new HashMap<>();
    /** Человеческий вид компаньонов: "male" / "female" или JSON скина (см. {@link HumanLook}); нет — вид роли. */
    public final Map<String, String> companionLooks = new HashMap<>();
    /** Реплики в бою: id героя или шаблона врага → событие (start, attack, skill, hurt, death, win) → фразы. */
    public final Map<String, Map<String, java.util.List<String>>> barks = new HashMap<>();
    /** Шанс реплики на удар / получение урона (начало боя, смерть и победа — всегда). */
    public double barkChance = 0.35;
    /** Роль NPC, если роль врага/компаньона не найдена в игре. */
    public String fallbackRole = "Trork_Warrior";
    public final Camera camera = new Camera();
    /** Иконки (картинки) в меню; false — только текст (на случай, если картинки не грузятся). */
    public boolean uiIcons = true;
    /** Полоски HP/MP; false — только числа. */
    public boolean uiBars = true;
    /** Баннер хода, очередь и книга журнала в HUD с анимациями; false — всё в окне боя, без анимаций. */
    public boolean uiHud = false;
    /** Анимации HUD (сдвиг очереди, выезд баннера, перелистывание книги). */
    public boolean uiAnimations = true;
    /** Шрифт заголовков: "Secondary" (второй шрифт игры), "Default" или пусто — как в разметке. */
    public String uiTitleFont = "Secondary";
    /** Кнопки меню на своих текстурах (jr_btn*.png); false — стандартные кнопки игры. */
    public boolean uiSkinButtons = false;
    public String turnRing = "", targetRing = "", hitFx = "";
    /** Частицы эффектов боя по ключам {@link ParticleCatalog#KEYWORDS}; чего нет — подбирается само. */
    public final Map<String, String> fx = new HashMap<>();
    public final Reactions reactions = new Reactions();
    public final Followers follow = new Followers();
    public final Speech speech = new Speech();

    /** Реплики в бою: окно над головой (подпись), буквы печатаются по одной со звуком. */
    public static final class Speech {
        public long letterMs = 35, holdMs = 1800;
        /** Звук на букву (id звука игры); пусто — подобрать самому. Звучит каждая soundEvery-я буква. */
        public String sound = "";
        public float volume = 0.45f;
        public int soundEvery = 2;
        /** Окна реплик в меню боя над головами (как в FF); false — только подпись над NPC. */
        public boolean bubbles = true;
    }

    /** Парирование и уклонение: окна в мс относительно момента удара врага. */
    public static final class Reactions {
        /** Не используется: реакции выключены в BattleSession. */
        public boolean enabled = false;
        /** Уклонение засчитывается с dodgeBefore мс до удара и до dodgeAfter после. */
        public long dodgeBefore = 450, dodgeAfter = 150;
        /** Парирование: окно короче. */
        public long parryBefore = 220, parryAfter = 90;
    }

    /** Компаньоны, идущие за игроком вне боя. */
    public static final class Followers {
        public boolean enabled = true;
        /** Скорость шага и бега, блоков в секунду; расстояние, с которого переходят на бег и на телепорт. */
        public double walkSpeed = 4.3, runSpeed = 7.5, runFrom = 6, teleportFrom = 20;
        /** На каком расстоянии за спиной держаться. */
        public double distance = 2.6;
    }
    /** Пауза в мс с учётом {@link #battleSpeed}. */
    public long ms(long base) {
        return Math.round(base / battleSpeed);
    }

    public long actionDelayMs = 2400, enemyThinkMs = 1500;
    /** Общая скорость боя: 1 — обычная, 2 — вдвое быстрее, 0.5 — вдвое медленнее (делит все паузы). */
    public double battleSpeed = 1.0;
    /** Всплывающие цифры урона и названия эффектов над бойцами. */
    public boolean combatText = true;
    public boolean proximityTrigger = true;
    /** Окно «Сражаться / Сбежать» перед боем (false — бой начинается сразу, как в 0.5). */
    public boolean preBattlePrompt = true;
    /** Облёт камеры вокруг арены перед первым ходом. */
    public boolean introFlyby = true;
    /** NPC случайных отрядов бродят у своего места (false — стоят). */
    public boolean squadWander = true;
    /** Подсказка «[O] Меню героя» в правом верхнем углу экрана (пока меню висит на клавише режима). */
    public boolean keyHint = true;
    public final Animations anim = new Animations();

    /**
     * Имена анимаций моделей Hytale, из которых берётся первая существующая у модели.
     * Реальные имена моделей мод пишет в лог сервера («Анимации модели ...») — по ним список можно поправить.
     */
    public static final class Animations {
        public boolean enabled = true;
        public java.util.List<String> attack = java.util.List.of("Attack", "Swing", "Slash", "Strike", "Punch", "Bite", "Melee", "Hit");
        public java.util.List<String> cast = java.util.List.of("Cast", "Spell", "Magic", "Shoot", "Throw", "Roar", "Attack");
        public java.util.List<String> hurt = java.util.List.of("Hurt", "Damage", "Flinch");
        public java.util.List<String> death = java.util.List.of("Death", "Die", "Knockedout", "KnockedoutLaydown", "Laydown", "Sleep");
        public java.util.List<String> victory = java.util.List.of("Cheer", "Celebrate", "Victory", "Wave", "Emote", "Roar");
        /** Анимации героев и компаньонов по id (attack/cast/dodge/parry), если у их модели нет общих имён. */
        public final Map<String, Map<String, java.util.List<String>>> heroes = new HashMap<>();
        public java.util.List<String> dodge = java.util.List.of("RollBackward", "DashBackward", "Roll", "Dodge");
        public java.util.List<String> parry = java.util.List.of("Block", "Parry", "Guard", "SwingWeaponAir", "Attack");
        /** Подход к цели на арене: скорость бега, блоков в секунду (NPC бегут плавно, игрок делает рывок). */
        public double runSpeed = 6.5;
        /** Шаги подхода для самого игрока (его двигает телепорт) и пауза между ними, мс. */
        public int stepCount = 7;
        public long stepMs = 50;
        /** Сбили с ног (оглушение): падение, поза «лежит» (держится, пока оглушён), подъём. */
        public java.util.List<String> knockdown = java.util.List.of("HurtFall", "Knockedout", "Hurt");
        public java.util.List<String> down = java.util.List.of("KnockedoutLaydown", "Laydown", "Sleep");
        public java.util.List<String> wake = java.util.List.of("KnockedoutWake", "Wake", "GetUp", "Getup");
        /** Сколько мс поверженный враг лежит, прежде чем исчезнуть. */
        public long lieMs = 2600;
        /** Слова анимаций оружия (наборы ItemPlayerAnimations) для обычного удара, приёма, магии и блока. */
        public java.util.List<String> weaponAttack = java.util.List.of("SwingLeft", "SwingRight", "SwingDown", "Stab",
                "SwingDownLeft", "SwingDownRight", "SwingUpLeft", "SwingUpRight");
        public java.util.List<String> weaponSkill = java.util.List.of("SwingDownStrong", "SwingDownLeft", "SwingDownRight",
                "Sweep", "SwingUpLeft", "SwingUpRight", "GuardBash", "SwingDown", "Stab");
        public java.util.List<String> weaponCast = java.util.List.of("CastLeft", "CastEnchant");
        public java.util.List<String> weaponParry = java.util.List.of("GuardBash");
        /** Сколько мс длится удар: потом его анимация снимается (если сама не кончилась). */
        public long strikeMs = 1100;
        /** Рывок самого игрока к цели и обратно (его двигает телепорт, поэтому — рывок, а не бег). */
        public java.util.List<String> dashForward = java.util.List.of("DashForward");
        public java.util.List<String> dashBack = java.util.List.of("DashBackward");
        public long dashMs = 220;
    }

    /** Камера: углы в градусах, расстояния в блоках. */
    public static final class Camera {
        public boolean enabled = true;
        public float lerp = 0.1f;
        /** Камера с другой стороны арены. */
        public boolean flip = false;
        /** Камера никогда не опускается ниже стольких блоков над точкой взгляда (чтобы не влезать в модели). */
        public double minHeight = 3.5;
        public double overviewDistance = 20, overviewPitch = -38, overviewSide = 55;
        public double focusDistance = 11, focusPitch = -28, focusSide = 45;
        public double actionDistance = 10, actionPitch = -30, actionSide = 75;
        public double targetDistance = 15;
        /** Угол обзора камеры боя по вертикали (градусы). По нему окна реплик ставятся над головами. */
        public float fov = 70f;
        /** Мышь в мире: наведение на врага подсвечивает его, клик выбирает целью. */
        public boolean mouseTargets = true;
    }

    public static Settings parse(String json) {
        Map<String, Object> m = Json.obj(Json.parse(json));
        Settings s = new Settings();
        Object roles = m.get("companionRoles");
        if (roles != null) Json.obj(roles).forEach((k, v) -> s.companionRoles.put(k, String.valueOf(v)));
        s.fallbackRole = Json.str(m, "fallbackRole", s.fallbackRole);
        Object looks = m.get("companionLooks");
        if (looks != null) Json.obj(looks).forEach((k, v) -> {
            if (v != null) s.companionLooks.put(k, v instanceof String str ? str : Json.write(v));
        });
        Object barks = m.get("barks");
        if (barks != null) Json.obj(barks).forEach((who, v) -> {
            Map<String, java.util.List<String>> byEvent = new HashMap<>();
            Map<String, Object> bm = Json.obj(v);
            for (String ev : bm.keySet()) byEvent.put(ev, Json.strings(bm, ev));
            s.barks.put(who, byEvent);
        });
        s.barkChance = Json.num(m, "barkChance", s.barkChance);
        s.keyHint = Json.bool(m, "keyHint", s.keyHint);
        Object sp = m.get("speech");
        if (sp != null) {
            Map<String, Object> v = Json.obj(sp);
            s.speech.letterMs = (long) Math.max(5, Json.num(v, "letterMs", s.speech.letterMs));
            s.speech.holdMs = (long) Math.max(300, Json.num(v, "holdMs", s.speech.holdMs));
            s.speech.sound = Json.str(v, "sound", s.speech.sound);
            s.speech.volume = (float) Json.num(v, "volume", s.speech.volume);
            s.speech.soundEvery = (int) Math.max(0, Json.num(v, "soundEvery", s.speech.soundEvery));
            s.speech.bubbles = Json.bool(v, "bubbles", s.speech.bubbles);
        }
        Object cam = m.get("camera");
        if (cam != null) {
            Map<String, Object> c = Json.obj(cam);
            Camera k = s.camera;
            k.enabled = Json.bool(c, "enabled", k.enabled);
            k.lerp = (float) Json.num(c, "lerp", k.lerp);
            k.flip = Json.bool(c, "flip", k.flip);
            k.minHeight = Json.num(c, "minHeight", k.minHeight);
            k.targetDistance = Json.num(c, "targetDistance", k.targetDistance);
            k.fov = (float) Json.num(c, "fov", k.fov);
            k.mouseTargets = Json.bool(c, "mouseTargets", k.mouseTargets);
            Object o = c.get("overview");
            if (o != null) {
                Map<String, Object> v = Json.obj(o);
                k.overviewDistance = Json.num(v, "distance", k.overviewDistance);
                k.overviewPitch = Json.num(v, "pitch", k.overviewPitch);
                k.overviewSide = Json.num(v, "side", k.overviewSide);
            }
            Object f = c.get("focus");
            if (f != null) {
                Map<String, Object> v = Json.obj(f);
                k.focusDistance = Json.num(v, "distance", k.focusDistance);
                k.focusPitch = Json.num(v, "pitch", k.focusPitch);
                k.focusSide = Json.num(v, "side", k.focusSide);
            }
            Object a = c.get("action");
            if (a != null) {
                Map<String, Object> v = Json.obj(a);
                k.actionDistance = Json.num(v, "distance", k.actionDistance);
                k.actionPitch = Json.num(v, "pitch", k.actionPitch);
                k.actionSide = Json.num(v, "side", k.actionSide);
            }
        }
        Object ui = m.get("ui");
        if (ui != null) {
            Map<String, Object> u = Json.obj(ui);
            s.uiIcons = Json.bool(u, "icons", s.uiIcons);
            s.uiBars = Json.bool(u, "bars", s.uiBars);
            s.uiHud = Json.bool(u, "hud", s.uiHud);
            s.uiAnimations = Json.bool(u, "animations", s.uiAnimations);
            s.uiTitleFont = Json.str(u, "titleFont", s.uiTitleFont);
            s.uiSkinButtons = Json.bool(u, "skinButtons", s.uiSkinButtons);
        }
        Object fx = m.get("particles");
        if (fx != null) {
            Map<String, Object> p = Json.obj(fx);
            s.turnRing = Json.str(p, "turnRing", "");
            s.targetRing = Json.str(p, "targetRing", "");
            s.hitFx = Json.str(p, "hit", "");
            for (String k : ParticleCatalog.KEYWORDS.keySet()) {
                if (p.containsKey(k)) s.fx.put(k, Json.str(p, k, ""));
            }
        }
        Object re = m.get("reactions");
        if (re != null) {
            Map<String, Object> r = Json.obj(re);
            Reactions k = s.reactions;
            k.enabled = Json.bool(r, "enabled", k.enabled);
            k.dodgeBefore = (long) Json.num(r, "dodgeBeforeMs", k.dodgeBefore);
            k.dodgeAfter = (long) Json.num(r, "dodgeAfterMs", k.dodgeAfter);
            k.parryBefore = (long) Json.num(r, "parryBeforeMs", k.parryBefore);
            k.parryAfter = (long) Json.num(r, "parryAfterMs", k.parryAfter);
        }
        Object fo = m.get("followers");
        if (fo != null) {
            Map<String, Object> f = Json.obj(fo);
            Followers k = s.follow;
            k.enabled = Json.bool(f, "enabled", k.enabled);
            k.walkSpeed = Json.num(f, "walkSpeed", k.walkSpeed);
            k.runSpeed = Json.num(f, "runSpeed", k.runSpeed);
            k.runFrom = Json.num(f, "runFrom", k.runFrom);
            k.teleportFrom = Json.num(f, "teleportFrom", k.teleportFrom);
            k.distance = Json.num(f, "distance", k.distance);
        }
        s.actionDelayMs = (long) Json.num(m, "actionDelayMs", s.actionDelayMs);
        s.enemyThinkMs = (long) Json.num(m, "enemyThinkMs", s.enemyThinkMs);
        s.battleSpeed = Math.max(0.1, Json.num(m, "battleSpeed", s.battleSpeed));
        s.combatText = Json.bool(m, "combatText", s.combatText);
        s.proximityTrigger = Json.bool(m, "proximityTrigger", true);
        s.preBattlePrompt = Json.bool(m, "preBattlePrompt", s.preBattlePrompt);
        s.introFlyby = Json.bool(m, "introFlyby", s.introFlyby);
        s.squadWander = Json.bool(m, "squadWander", s.squadWander);
        Object an = m.get("animations");
        if (an != null) {
            Map<String, Object> a = Json.obj(an);
            Animations k = s.anim;
            k.enabled = Json.bool(a, "enabled", k.enabled);
            if (a.containsKey("attack")) k.attack = Json.strings(a, "attack");
            if (a.containsKey("cast")) k.cast = Json.strings(a, "cast");
            if (a.containsKey("hurt")) k.hurt = Json.strings(a, "hurt");
            if (a.containsKey("death")) k.death = Json.strings(a, "death");
            if (a.containsKey("victory")) k.victory = Json.strings(a, "victory");
            if (a.containsKey("dodge")) k.dodge = Json.strings(a, "dodge");
            if (a.containsKey("parry")) k.parry = Json.strings(a, "parry");
            if (a.get("heroes") != null) {
                Json.obj(a.get("heroes")).forEach((hero, v) -> {
                    Map<String, java.util.List<String>> byKind = new HashMap<>();
                    Map<String, Object> hm = Json.obj(v);
                    for (String kind : hm.keySet()) byKind.put(kind, Json.strings(hm, kind));
                    k.heroes.put(hero, byKind);
                });
            }
            k.stepCount = (int) Math.max(1, Json.num(a, "stepCount", k.stepCount));
            k.stepMs = (long) Math.max(10, Json.num(a, "stepMs", k.stepMs));
            k.runSpeed = Math.max(1, Json.num(a, "runSpeed", k.runSpeed));
            k.lieMs = (long) Math.max(300, Json.num(a, "lieMs", k.lieMs));
            if (a.containsKey("knockdown")) k.knockdown = Json.strings(a, "knockdown");
            if (a.containsKey("down")) k.down = Json.strings(a, "down");
            if (a.containsKey("wake")) k.wake = Json.strings(a, "wake");
            if (a.containsKey("weaponAttack")) k.weaponAttack = Json.strings(a, "weaponAttack");
            if (a.containsKey("weaponSkill")) k.weaponSkill = Json.strings(a, "weaponSkill");
            if (a.containsKey("weaponCast")) k.weaponCast = Json.strings(a, "weaponCast");
            if (a.containsKey("weaponParry")) k.weaponParry = Json.strings(a, "weaponParry");
            k.strikeMs = (long) Math.max(300, Json.num(a, "strikeMs", k.strikeMs));
            if (a.containsKey("dashForward")) k.dashForward = Json.strings(a, "dashForward");
            if (a.containsKey("dashBack")) k.dashBack = Json.strings(a, "dashBack");
            k.dashMs = (long) Math.max(60, Json.num(a, "dashMs", k.dashMs));
        }
        return s;
    }
}
