package dev.jrpg.hytale;

import dev.jrpg.battle.Json;

import java.util.HashMap;
import java.util.Map;

/** Настройки отображения боя (settings.json): камера, интерфейс, частицы, роли NPC, паузы. */
public final class Settings {
    public final Map<String, String> companionRoles = new HashMap<>();
    /** Роль NPC, если роль врага/компаньона не найдена в игре. */
    public String fallbackRole = "Trork_Warrior";
    public final Camera camera = new Camera();
    /** Иконки (картинки) в меню; false — только текст (на случай, если картинки не грузятся). */
    public boolean uiIcons = true;
    /** Полоски HP/MP; false — только числа. */
    public boolean uiBars = true;
    public String turnRing = "", targetRing = "", hitFx = "";
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
    }

    public static Settings parse(String json) {
        Map<String, Object> m = Json.obj(Json.parse(json));
        Settings s = new Settings();
        Object roles = m.get("companionRoles");
        if (roles != null) Json.obj(roles).forEach((k, v) -> s.companionRoles.put(k, String.valueOf(v)));
        s.fallbackRole = Json.str(m, "fallbackRole", s.fallbackRole);
        Object cam = m.get("camera");
        if (cam != null) {
            Map<String, Object> c = Json.obj(cam);
            Camera k = s.camera;
            k.enabled = Json.bool(c, "enabled", k.enabled);
            k.lerp = (float) Json.num(c, "lerp", k.lerp);
            k.flip = Json.bool(c, "flip", k.flip);
            k.minHeight = Json.num(c, "minHeight", k.minHeight);
            k.targetDistance = Json.num(c, "targetDistance", k.targetDistance);
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
        }
        Object fx = m.get("particles");
        if (fx != null) {
            Map<String, Object> p = Json.obj(fx);
            s.turnRing = Json.str(p, "turnRing", "");
            s.targetRing = Json.str(p, "targetRing", "");
            s.hitFx = Json.str(p, "hit", "");
        }
        s.actionDelayMs = (long) Json.num(m, "actionDelayMs", s.actionDelayMs);
        s.enemyThinkMs = (long) Json.num(m, "enemyThinkMs", s.enemyThinkMs);
        s.battleSpeed = Math.max(0.1, Json.num(m, "battleSpeed", s.battleSpeed));
        s.combatText = Json.bool(m, "combatText", s.combatText);
        s.proximityTrigger = Json.bool(m, "proximityTrigger", true);
        return s;
    }
}
