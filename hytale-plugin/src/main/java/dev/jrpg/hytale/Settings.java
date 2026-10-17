package dev.jrpg.hytale;

import dev.jrpg.battle.Json;

import java.util.HashMap;
import java.util.Map;

/** Настройки отображения боя (settings.json): камера, частицы, роли NPC, паузы. */
public final class Settings {
    public final Map<String, String> companionRoles = new HashMap<>();
    public boolean cameraEnabled = true;
    public float cameraDistance = 13f, cameraPitch = -35f, cameraYaw = 0f, cameraLerp = 0.08f;
    public String turnRing = "", targetRing = "", hitFx = "";
    public long actionDelayMs = 1400, enemyThinkMs = 700;
    public boolean proximityTrigger = true;

    public static Settings parse(String json) {
        Map<String, Object> m = Json.obj(Json.parse(json));
        Settings s = new Settings();
        Object roles = m.get("companionRoles");
        if (roles != null) Json.obj(roles).forEach((k, v) -> s.companionRoles.put(k, String.valueOf(v)));
        Object cam = m.get("camera");
        if (cam != null) {
            Map<String, Object> c = Json.obj(cam);
            s.cameraEnabled = Json.bool(c, "enabled", true);
            s.cameraDistance = (float) Json.num(c, "distance", 13);
            s.cameraPitch = (float) Json.num(c, "pitch", -35);
            s.cameraYaw = (float) Json.num(c, "yaw", 0);
            s.cameraLerp = (float) Json.num(c, "lerp", 0.08);
        }
        Object fx = m.get("particles");
        if (fx != null) {
            Map<String, Object> p = Json.obj(fx);
            s.turnRing = Json.str(p, "turnRing", "");
            s.targetRing = Json.str(p, "targetRing", "");
            s.hitFx = Json.str(p, "hit", "");
        }
        s.actionDelayMs = (long) Json.num(m, "actionDelayMs", 1400);
        s.enemyThinkMs = (long) Json.num(m, "enemyThinkMs", 700);
        s.proximityTrigger = Json.bool(m, "proximityTrigger", true);
        return s;
    }
}
