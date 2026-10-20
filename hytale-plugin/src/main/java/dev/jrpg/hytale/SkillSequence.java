package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.EntityPart;
import com.hypixel.hytale.protocol.ModelParticle;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.entities.SpawnModelParticles;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.asset.type.itemanimation.config.ItemPlayerAnimations;
import com.hypixel.hytale.server.core.asset.type.modelvfx.config.ModelVFX;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jrpg.battle.Json;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Кинематографичная сцена навыка: таймлайн из data/sequences.json (см. cinematic-skills/PLAN.md).
 *
 * Шаги идут по порядку. t — мс от начала сцены (после wait — от момента, когда дождались), "+N" — от
 * предыдущего шага, без t — сразу за предыдущим. В одном шаге может быть несколько действий: camera, move,
 * anim, emote, glow, particles, shape, sound, shake, hit. Неизвестное имя (анимации, частицы) пишется в лог
 * и в итог сцены, шаг пропускается — сцена не ломается. Раз в {@link #TICK_MS} мс сцена делает шаг в потоке мира:
 * запускает наступившие шаги и двигает камеру (облёт, слежка, плавный наезд считаются здесь же).
 * slow — замедление: время сцены идёт в slow раз медленнее (анимации моделей играют как есть).
 *
 * Тот же движок играет катсцены ({@link Cutscene}): у сцены могут быть именованные актёры (on: "bram",
 * "bram,lyra", "all"), точки в мире ([x,y,z] от начала сцены, имя актёра, {"at":..,"off":[..]}),
 * метки и переходы (label / goto / if / end), камера по ключевым кадрам (shot, path, track, native),
 * угол обзора, размытие фона, крен, замедление мира. Шаги и ожидания, которых движок не знает, отдаются
 * расширению {@link Ext} (катсцена: субтитры, диалоги, затемнение, полосы, титры, время суток, погода).
 */
final class SkillSequence {
    static final long TICK_MS = 50;
    private static final double RUN_SPEED = 7.0, WALK_SPEED = 3.5;
    /** Сцена дольше этого (в мс сцены) обрывается: защита от вечного wait. */
    private static final double MAX_MS = 40_000;

    /** Кто в сцене: кастующий (его дом и поворот — куда вернуться), главная цель, все цели. */
    record Cast(Ref<EntityStore> caster, Ref<EntityStore> target, List<Ref<EntityStore>> targets,
                Vector3d casterHome, float casterYaw, List<String> weaponHints) {}

    /** Что делает сцена в бою: hit — показать урон по целям, done — сцена кончилась (или оборвалась). */
    interface Hooks {
        void hit(List<Ref<EntityStore>> targets);

        void done(SkillSequence seq);
    }

    /** Расширение движка (катсцена): свои шаги, свои ожидания и свой тик. */
    interface Ext {
        /** Шаг key понят и выполнен — true; false — такого шага нет и у расширения. */
        boolean step(String key, Map<String, Object> st, List<Ref<EntityStore>> on, double when);

        /** Дождались ли what (true/false); null — такого ожидания расширение не знает. */
        Boolean waited(String what, Map<String, Object> st, double now, double since);

        /** Каждый тик сцены (до шагов). */
        void tick(double now);

        /** Сцена стоит (идёт реплика, ждём выбор): следующие шаги не начинаются; время потом — с момента отпуска. */
        boolean holds();
    }

    private interface CamTrack {
        /** Кадр камеры на момент now (мс сцены); false — проезд закончен (камера стоит, где остановилась). */
        boolean frame(double now);
    }

    private final JrpgPlugin plugin;
    final String id;
    private final List<Map<String, Object>> steps;
    private final PlayerRef player;
    private final Ref<EntityStore> viewer;
    private final Store<EntityStore> store;
    private final World world;
    private final BattleCamera camera;
    private final Cast cast;
    private final double slow;
    private final Hooks hooks;

    private final Set<String> missing = new LinkedHashSet<>();
    private final AtomicBoolean busy = new AtomicBoolean();
    private ScheduledFuture<?> task;
    private long startReal;
    private int index;
    private double segStart, prev, lastAction;
    private CamTrack cam;
    private boolean finished;

    // ---- для катсцен (у навыков всё по умолчанию) ----
    /** Актёры по именам и где каждый стоял в начале (move back / to home). */
    private final Map<String, Ref<EntityStore>> actors = new java.util.LinkedHashMap<>();
    private final Map<Ref<EntityStore>, Vector3d> homes = new java.util.HashMap<>();
    private final Map<Ref<EntityStore>, Float> homeYaws = new java.util.HashMap<>();
    /** Начало координат сцены и куда «вперёд» ([x,y,z] в шагах: x вправо, y вверх, z вперёд). */
    private Vector3d origin;
    private float originYaw;
    private Ext ext;
    private double maxMs = MAX_MS;
    /** Переменные сцены (выборы в диалогах): "if": "имя" / "!имя". */
    private final Set<String> vars = new java.util.HashSet<>();
    private boolean jumped, held;
    /** Плавная смена угла обзора и размытие, которое следит за актёром. */
    private CamTrack fovTrack, dofTrack;
    private double slowUntil = -1;
    SkillSequence(JrpgPlugin plugin, String id, List<Map<String, Object>> steps, PlayerRef player, Ref<EntityStore> viewer,
                  Store<EntityStore> store, World world, BattleCamera camera, Cast cast, double slow, Hooks hooks) {
        this.plugin = plugin;
        this.id = id;
        this.steps = steps;
        this.player = player;
        this.viewer = viewer;
        this.store = store;
        this.world = world;
        this.camera = camera;
        this.cast = cast;
        this.slow = Math.max(1, slow);
        this.hooks = hooks;
        this.origin = cast.casterHome();
        this.originYaw = cast.casterYaw();
        homes.put(cast.caster(), cast.casterHome());
        homeYaws.put(cast.caster(), cast.casterYaw());
    }

    /** Актёры сцены по именам (их нынешние места запоминаются как «дом»). */
    SkillSequence actors(Map<String, Ref<EntityStore>> named) {
        actors.putAll(named);
        for (Ref<EntityStore> r : named.values()) {
            if (r == null || !r.isValid()) continue;
            homes.putIfAbsent(r, Fx.position(store, r));
            homeYaws.putIfAbsent(r, yawOf(r));
        }
        return this;
    }

    SkillSequence ext(Ext e) {
        ext = e;
        return this;
    }

    SkillSequence maxMs(double ms) {
        maxMs = ms;
        return this;
    }

    SkillSequence origin(Vector3d o, float yaw) {
        origin = new Vector3d(o);
        originYaw = yaw;
        return this;
    }

    Set<String> vars() { return vars; }

    Map<String, Ref<EntityStore>> actorMap() { return actors; }

    /** Перейти к метке (шаг {"label": name}, дальше — шаги после неё); время считается от этого момента. */
    boolean jump(String label) {
        for (int i = 0; i < steps.size(); i++) {
            if (label.equals(String.valueOf(steps.get(i).get("label")))) {
                index = i + 1;
                segStart = prev = now();
                jumped = true;
                return true;
            }
        }
        missing.add("метка «" + label + "»");
        return false;
    }

    /** Завершить сцену сейчас (пропуск катсцены, шаг end). */
    void end() { finish(); }

    boolean finished() { return finished; }
    /** Запустить через delayMs (успеть заспавнить кукол). */
    void start(long delayMs) {
        startReal = System.currentTimeMillis() + delayMs;
        plugin.log("Сцена " + id + ": " + steps.size() + " шагов" + (slow > 1 ? ", замедление x" + slow : ""));
        task = plugin.scheduler().scheduleAtFixedRate(() -> {
            if (finished || !busy.compareAndSet(false, true)) return;
            try {
                world.execute(() -> {
                    try {
                        if (System.currentTimeMillis() >= startReal) tick();
                    } catch (Throwable t) {
                        plugin.warn("Сцена " + id + " сломалась", t);
                        finish();
                    } finally {
                        busy.set(false);
                    }
                });
            } catch (Throwable t) {
                busy.set(false);
                finish(); // мир выгружен
            }
        }, delayMs, TICK_MS, TimeUnit.MILLISECONDS);
    }

    /** Чего не нашлось по ходу сцены (имена анимаций, частиц, звуков, неподдержанные шаги). */
    Set<String> missing() { return missing; }

    /** Оборвать сцену (бой закончился, игрок вышел). */
    void cancel() { finish(); }

    private void finish() {
        if (finished) return;
        finished = true;
        if (task != null) task.cancel(false);
        plugin.mover().stop(cast.caster());
        for (Ref<EntityStore> r : actors.values()) plugin.mover().stop(r);
        if (slowUntil >= 0) {
            slowUntil = -1;
            timeDilation(1f);
        }
        plugin.log("Сцена " + id + " закончена" + (missing.isEmpty() ? "" : "; не нашлось: " + String.join("; ", missing)));
        try {
            hooks.done(this);
        } catch (Throwable t) {
            plugin.warn("Конец сцены " + id + " не сработал", t);
        }
    }

    // ---------- время ----------

    private double now() {
        return (System.currentTimeMillis() - startReal) / slow;
    }

    private void tick() {
        if (finished) return;
        double now = now();
        if (now > maxMs) {
            missing.add("сцена дольше " + (long) (maxMs / 1000) + " с — оборвана");
            finish();
            return;
        }
        if (slowUntil >= 0 && now >= slowUntil) {
            slowUntil = -1;
            timeDilation(1f);
        }
        if (ext != null) ext.tick(now);
        jumped = false; // переход из тика расширения (выбор в диалоге) уже поставил index
        while (index < steps.size() && !finished) {
            if (ext != null && ext.holds()) {
                held = true;
                break;
            }
            if (held) {
                held = false;
                segStart = prev = now;
            }
            Map<String, Object> st = steps.get(index);
            if (!condition(st)) {
                index++;
                continue;
            }
            double when = when(st);
            if (now < when) break;
            if (st.containsKey("wait")) {
                if (!waited(st, now, when)) break;
                if (jumped) {
                    jumped = false;
                    continue;
                }
                segStart = prev = now;
                index++;
                continue;
            }
            run(st, when);
            prev = when;
            lastAction = now;
            if (Json.bool(st, "end", false)) {
                finish();
                return;
            }
            if (st.get("goto") != null && jump(String.valueOf(st.get("goto")))) {
                jumped = false;
                continue;
            }
            if (jumped) {
                jumped = false;
                continue;
            }
            index++;
        }
        if (finished) return;
        if (cam != null && !cam.frame(now)) cam = null;
        boolean extra = false;
        if (fovTrack != null) {
            if (!fovTrack.frame(now)) fovTrack = null;
            extra = true;
        }
        if (dofTrack != null) {
            if (!dofTrack.frame(now)) dofTrack = null;
            extra = true;
        }
        if (extra && cam == null) camera.refresh();
        // Конец: все шаги прошли, кастующий дошёл, последние эффекты успели доиграть.
        if (index >= steps.size() && !plugin.mover().moving(cast.caster()) && now - lastAction > 700
                && (ext == null || !ext.holds())) finish();
    }

    private double when(Map<String, Object> st) {
        Object t = st.get("t");
        if (t instanceof Number n) return segStart + n.doubleValue();
        if (t instanceof String s) {
            try {
                if (s.startsWith("+")) return prev + Double.parseDouble(s.substring(1).trim());
                return segStart + Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                missing.add("время шага «" + s + "»");
            }
        }
        return prev;
    }

    private boolean waited(Map<String, Object> st, double now, double since) {
        Object w = st.get("wait");
        if (w instanceof Number n) return now >= since + n.doubleValue();
        String what = String.valueOf(w);
        if (what.equals("arrived")) {
            List<Ref<EntityStore>> on = st.containsKey("on") ? who(Json.str(st, "on", "caster")) : List.of(cast.caster());
            for (Ref<EntityStore> r : on) if (plugin.mover().moving(r) && now - since <= 12000) return false;
            return true;
        }
        if (what.equals("camera")) return cam == null || now - since > 30000;
        if (ext != null) {
            Boolean r = ext.waited(what, st, now, since);
            if (r != null) return r;
        }
        missing.add("wait «" + what + "» (есть: arrived, camera, число мс" + (ext != null ? ", click, dialog, choice" : "") + ")");
        return true;
    }

    /** "if": "имя" — шаг только если выбор сделан; "!имя" — только если нет. */
    private boolean condition(Map<String, Object> st) {
        Object c = st.get("if");
        if (c == null) return true;
        String v = String.valueOf(c).trim();
        return v.startsWith("!") ? !vars.contains(v.substring(1)) : vars.contains(v);
    }

    // ---------- шаг ----------

    private void run(Map<String, Object> st, double when) {
        // Кто делает шаг: on, иначе говорящий (who в репликах катсцены — его эмоция/анимация), иначе кастующий.
        String whoSays = Json.str(st, "who", "");
        List<Ref<EntityStore>> on = who(Json.str(st, "on", actors.containsKey(whoSays) ? whoSays : "caster"));
        StringBuilder log = new StringBuilder();
        for (String key : st.keySet()) {
            if (PARAMS.contains(key)) continue;
            log.append(key).append(' ');
            try {
                switch (key) {
                    case "camera" -> camera(st, on, when);
                    case "move" -> move(st);
                    case "anim" -> anim(st, on);
                    case "emote" -> emote(st, on);
                    case "glow" -> glow(st, on);
                    case "particles" -> particles(st, on);
                    case "shape" -> shape(st, on);
                    case "sound" -> sound(Json.str(st, "sound", ""));
                    case "shake" -> shake(Json.num(st, "shake", 0.5));
                    case "hit" -> {
                        if (Json.bool(st, "hit", true)) hooks.hit(on);
                    }
                    case "fov" -> fov(st);
                    case "dof" -> dof(st);
                    case "roll" -> {
                        camera.roll((float) Math.toRadians(Json.num(st, "roll", 0)));
                        if (cam == null) camera.refresh();
                    }
                    case "slowmo" -> slowmo(st, when);
                    case "face" -> face(st, on);
                    case "set" -> vars.add(Json.str(st, "set", ""));
                    case "unset" -> vars.remove(Json.str(st, "unset", ""));
                    default -> {
                        if (ext == null || !ext.step(key, st, on, when)) missing.add("шаг «" + key + "» пока не поддержан");
                    }
                }
            } catch (Throwable t) {
                Fx.warnOnce("Сцена " + id + ": шаг " + key + " не сработал", t);
                missing.add("шаг " + key + " упал (см. лог)");
            }
        }
        plugin.log("Сцена " + id + " [" + Math.round(when) + " мс]: " + log.toString().trim()
                + (st.containsKey("on") ? " → " + st.get("on") : ""));
    }

    /** Параметры шагов (не действия). */
    private static final Set<String> PARAMS = Set.of("t", "on", "ms", "ease", "fallback", "attach", "offset", "color",
            "dist", "height", "side", "from", "to", "stopAt", "radius", "fallMs", "label", "goto", "if", "end", "pos",
            "look", "points", "keys", "speed", "loop", "note", "who", "voice", "auto", "options", "size", "sub", "at",
            "fromCurrent", "id", "hero", "enemy", "role", "name", "hidden", "yaw", "nameplate", "_clicks");

    /** Кто: caster, target, targets, all (все актёры), имя актёра или несколько через запятую. */
    List<Ref<EntityStore>> who(String on) {
        switch (on) {
            case "target":
                return cast.target() == null ? List.of() : List.of(cast.target());
            case "targets":
                return cast.targets();
            case "caster":
                return List.of(cast.caster());
            case "all": {
                List<Ref<EntityStore>> all = new ArrayList<>();
                for (Ref<EntityStore> r : actors.values()) if (r != null && r.isValid()) all.add(r);
                return all;
            }
            default: {
                List<Ref<EntityStore>> out = new ArrayList<>();
                for (String n : on.split(",")) {
                    Ref<EntityStore> r = actors.get(n.trim());
                    if (r != null && r.isValid()) out.add(r);
                    else if (!n.isBlank()) missing.add("актёр «" + n.trim() + "»");
                }
                return out;
            }
        }
    }

    // ---------- точки ----------

    /**
     * Точка мира: [x,y,z] — от начала сцены (x вправо, y вверх, z вперёд), "имя" — где стоит актёр
     * (caster/target тоже), {"at": точка или имя, "off": [x,y,z]} — со сдвигом (у актёра — в его осях:
     * x вправо, z вперёд от его взгляда), {"world": [x,y,z]} — абсолютные координаты.
     */
    Vector3d point(Object spec) {
        if (spec instanceof List<?> l && l.size() >= 3) return local(origin, originYaw, l);
        if (spec instanceof String name) {
            List<Ref<EntityStore>> r = who(name);
            return r.isEmpty() ? new Vector3d(origin) : center(r);
        }
        if (spec instanceof Map<?, ?> m) {
            Object w = m.get("world");
            if (w instanceof List<?> wl && wl.size() >= 3) return new Vector3d(d(wl.get(0)), d(wl.get(1)), d(wl.get(2)));
            Object at = m.get("at");
            Object off = m.get("off");
            Vector3d base;
            float yaw = originYaw;
            if (at instanceof String name && !who(name).isEmpty()) {
                Ref<EntityStore> r = who(name).get(0);
                base = Fx.position(store, r);
                yaw = yawOf(r);
            } else {
                base = at == null ? new Vector3d(origin) : point(at);
            }
            return off instanceof List<?> ol && ol.size() >= 3 ? local(base, yaw, ol) : base;
        }
        return new Vector3d(origin);
    }

    /** Точка взгляда: у актёра (имя) — на уровне груди. */
    private Vector3d lookPoint(Object spec) {
        Vector3d p = point(spec);
        return spec instanceof String ? new Vector3d(p.x, p.y + 1.3, p.z) : p;
    }

    private static Vector3d local(Vector3d base, float yaw, List<?> xyz) {
        double fx = -Math.sin(yaw), fz = -Math.cos(yaw), rx = -fz, rz = fx;
        double x = d(xyz.get(0)), y = d(xyz.get(1)), z = d(xyz.get(2));
        return new Vector3d(base.x + rx * x + fx * z, base.y + y, base.z + rz * x + fz * z);
    }

    private static double d(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    // ---------- камера ----------

    private void camera(Map<String, Object> st, List<Ref<EntityStore>> on, double when) {
        String kind = Json.str(st, "camera", "battle");
        double ms = Json.num(st, "ms", 600);
        String ease = Json.str(st, "ease", "InOutQuad");
        Vector3d center = center(on);
        switch (kind) {
            case "focus" -> {
                double dist = Json.num(st, "dist", 3.0), height = Json.num(st, "height", 1.5), side = Json.num(st, "side", 35);
                float yaw = on.size() == 1 ? yawOf(on.get(0)) : Fx.yawTowards(center, cast.casterHome());
                double a = Math.toRadians(side);
                double fx = -Math.sin(yaw), fz = -Math.cos(yaw);
                double dx = fx * Math.cos(a) - fz * Math.sin(a), dz = fx * Math.sin(a) + fz * Math.cos(a);
                Vector3d toCam = new Vector3d(center.x + dx * dist, center.y + height, center.z + dz * dist);
                Vector3d toLook = new Vector3d(center.x, center.y + 1.1, center.z);
                cam = glide(toCam, toLook, when, ms, ease);
            }
            case "follow" -> {
                Ref<EntityStore> ref = on.isEmpty() ? cast.caster() : on.get(0);
                double dist = Json.num(st, "dist", 4.0), height = Json.num(st, "height", 2.0), side = Json.num(st, "side", 25);
                cam = now -> {
                    Vector3d c = Fx.position(store, ref), tc = center(cast.targets());
                    double dx = tc.x - c.x, dz = tc.z - c.z, len = Math.max(1e-6, Math.hypot(dx, dz));
                    dx /= len;
                    dz /= len;
                    double a = Math.toRadians(side);
                    // Сзади-сбоку: направление «назад» (-d), повёрнутое на side.
                    double bx = -dx * Math.cos(a) + dz * Math.sin(a), bz = -dx * Math.sin(a) - dz * Math.cos(a);
                    camera.aim("seq:follow", new Vector3d(c.x + bx * dist, c.y + height, c.z + bz * dist),
                            new Vector3d(c.x + dx * 3, c.y + 1.0, c.z + dz * 3), 0.3f);
                    return true;
                };
            }
            case "orbit" -> {
                double dist = Json.num(st, "dist", 9), height = Json.num(st, "height", 5);
                double from = Json.num(st, "from", -20), to = Json.num(st, "to", 20);
                // Ноль облёта — со стороны кастующего (камера за его спиной смотрит на цели).
                double bx = cast.casterHome().x - center.x, bz = cast.casterHome().z - center.z;
                double len = Math.max(1e-6, Math.hypot(bx, bz));
                double ux = bx / len, uz = bz / len;
                Vector3d look = new Vector3d(center.x, center.y + 0.8, center.z);
                cam = now -> {
                    double t = ms <= 0 ? 1 : Math.min(1, Math.max(0, (now - when) / ms));
                    double a = Math.toRadians(from + (to - from) * ease(ease, t));
                    double vx = ux * Math.cos(a) - uz * Math.sin(a), vz = ux * Math.sin(a) + uz * Math.cos(a);
                    camera.aim("seq:orbit", new Vector3d(center.x + vx * dist, center.y + height, center.z + vz * dist), look, 0.3f);
                    return t < 1;
                };
            }
            case "battle" -> {
                cam = null;
                camera.action(Fx.position(store, cast.caster()), center(cast.targets()));
            }
            case "shot" -> {
                // Кадр: камера в pos, взгляд на look; ms — плавный проезд от текущего кадра (0 — склейка).
                Vector3d to = point(st.get("pos"));
                Vector3d look = lookPoint(st.containsKey("look") ? st.get("look") : st.getOrDefault("on", "caster"));
                cam = glide(to, look, when, ms, ease);
            }
            case "path" -> cam = path(st, when, ms, ease);
            case "track" -> {
                // Камера стоит (или едет к pos) и следит за актёром.
                Ref<EntityStore> ref = on.isEmpty() ? cast.caster() : on.get(0);
                Vector3d to = point(st.get("pos"));
                Vector3d fromCam = camera.lastCam() == null ? to : camera.lastCam();
                double up = Json.num(st, "height", 1.3);
                cam = now -> {
                    double t = ms <= 0 ? 1 : Math.min(1, Math.max(0, (now - when) / ms));
                    Vector3d p = Fx.position(store, ref);
                    camera.aim("seq:track", new Vector3d(fromCam).lerp(to, ease(ease, t)), new Vector3d(p.x, p.y + up, p.z), 0.35f);
                    return ref.isValid();
                };
            }
            case "native" -> nativeKeys(st);
            case "player" -> {
                cam = null;
                camera.reset();
            }
            default -> missing.add("камера «" + kind + "» (есть focus, follow, orbit, battle, shot, path, track, native, player)");
        }
    }

    /** Плавный проезд от текущего кадра к (toCam, toLook) за ms. */
    private CamTrack glide(Vector3d toCam, Vector3d toLook, double start, double ms, String ease) {
        Vector3d fromCam = camera.lastCam(), fromLook = camera.lastLook();
        if (fromCam == null || fromLook == null) {
            fromCam = toCam;
            fromLook = toLook;
        }
        Vector3d fc = fromCam, fl = fromLook;
        return now -> {
            double t = ms <= 0 ? 1 : Math.min(1, Math.max(0, (now - start) / ms));
            double e = ease(ease, t);
            camera.aim("seq:focus", new Vector3d(fc).lerp(toCam, e), new Vector3d(fl).lerp(toLook, e), 0.35f);
            return t < 1;
        };
    }

    /**
     * Проезд по ключевым кадрам: points = [{pos, look}, ...], сплайн Catmull-Rom (как в CameraMenu) через все
     * точки, первая — текущий кадр камеры, если "fromCurrent": true. ms — на весь проезд.
     */
    private CamTrack path(Map<String, Object> st, double start, double ms, String ease) {
        List<Vector3d> ps = new ArrayList<>(), ls = new ArrayList<>();
        if (Json.bool(st, "fromCurrent", false) && camera.lastCam() != null) {
            ps.add(camera.lastCam());
            ls.add(camera.lastLook());
        }
        Object pts = st.get("points");
        if (pts instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> m)) continue;
                ps.add(point(m.get("pos")));
                Object lk = m.get("look");
                ls.add(lk == null ? (ls.isEmpty() ? new Vector3d(origin) : new Vector3d(ls.get(ls.size() - 1))) : lookPoint(lk));
            }
        }
        if (ps.size() < 2) {
            missing.add("камера path: нужно минимум 2 точки");
            return null;
        }
        return now -> {
            double t = ms <= 0 ? 1 : Math.min(1, Math.max(0, (now - start) / ms));
            double e = ease(ease, t);
            camera.aim("seq:path", spline(ps, e), spline(ls, e), 0.5f);
            return t < 1;
        };
    }

    /** Точка сплайна Catmull-Rom по списку при параметре u 0..1 (равномерно по отрезкам). */
    static Vector3d spline(List<Vector3d> p, double u) {
        int n = p.size() - 1;
        double f = Math.min(n - 1e-9, Math.max(0, u * n));
        int i = (int) Math.floor(f);
        double t = f - i;
        Vector3d p0 = p.get(Math.max(0, i - 1)), p1 = p.get(i), p2 = p.get(Math.min(n, i + 1)), p3 = p.get(Math.min(n, i + 2));
        return new Vector3d(cr(p0.x, p1.x, p2.x, p3.x, t), cr(p0.y, p1.y, p2.y, p3.y, t), cr(p0.z, p1.z, p2.z, p3.z, t));
    }

    private static double cr(double p0, double p1, double p2, double p3, double t) {
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (-p0 + 3 * p1 - 3 * p2 + p3) * t * t * t);
    }

    /**
     * Родная последовательность камеры клиента (CameraSequenceBuilder, как облёт в Viewfinder): клиент сам
     * плавно ведёт камеру по keys = [{pos, look, ms, fov, ease}]. Не проверено вместе с нашими кадрами:
     * пока она идёт, другие camera-шаги не ставить.
     */
    private void nativeKeys(Map<String, Object> st) {
        Object keys = st.get("keys");
        if (!(keys instanceof List<?> l) || l.isEmpty()) {
            missing.add("камера native: нет keys");
            return;
        }
        cam = null;
        var b = new com.hypixel.hytale.server.core.modules.camera.CameraSequenceBuilder()
                .addFlag((byte) 2).addFlag((byte) 4).addFlag((byte) 1).baseFov(camera.fov());
        boolean first = true;
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> k = Json.obj(raw);
            float secs = (float) (first ? 0.05 : Json.num(k, "ms", 1000) * slow / 1000.0);
            first = false;
            com.hypixel.hytale.protocol.EasingType et = com.hypixel.hytale.protocol.EasingType.Linear;
            for (var v : com.hypixel.hytale.protocol.EasingType.values()) if (v.name().equalsIgnoreCase(Json.str(k, "ease", "Linear"))) et = v;
            var kb = new com.hypixel.hytale.server.core.modules.camera.CameraKeyframeBuilder(secs, et)
                    .position(point(k.get("pos"))).lookAt(lookPoint(k.getOrDefault("look", "caster")));
            if (k.containsKey("fov")) kb.fov((float) Json.num(k, "fov", 70));
            b.keyframe(kb);
        }
        b.sendTo(player);
    }

    // ---------- угол обзора, размытие, замедление, поворот ----------

    /** {"fov": 40, "ms": 800} — плавно сменить угол обзора (меньше = «зум»). */
    private void fov(Map<String, Object> st) {
        float from = camera.fov(), to = (float) Json.num(st, "fov", 70);
        double ms = Json.num(st, "ms", 600), start = now();
        String ease = Json.str(st, "ease", "InOutQuad");
        fovTrack = now -> {
            double t = ms <= 0 ? 1 : Math.min(1, Math.max(0, (now - start) / ms));
            camera.fov((float) (from + (to - from) * ease(ease, t)));
            return t < 1;
        };
    }

    /**
     * {"dof": {"focus": "bram", "blur": 0.7}} — фон размыт, резко там, где focus (актёр или расстояние в блоках);
     * "dof": false — выключить. Зона резкости как в Viewfinder: вокруг расстояния фокуса.
     */
    private void dof(Map<String, Object> st) {
        Object d = st.get("dof");
        if (!(d instanceof Map<?, ?> m)) {
            dofTrack = null;
            camera.dof(null);
            if (cam == null) camera.refresh();
            return;
        }
        Map<String, Object> o = Json.obj(m);
        float blur = (float) Json.num(o, "blur", 0.7);
        Object focus = o.getOrDefault("focus", "caster");
        dofTrack = now -> {
            float dist;
            if (focus instanceof Number n) dist = n.floatValue();
            else {
                Vector3d c = camera.lastCam(), p = lookPoint(focus);
                dist = c == null ? 4f : (float) c.distance(p);
            }
            camera.dof(dofAt(dist, blur));
            return true;
        };
    }

    static com.hypixel.hytale.protocol.DepthOfFieldSettings dofAt(float focus, float blur) {
        focus = Math.max(0.3f, focus);
        float depth = Math.max(0.3f, focus * 0.35f);
        float nearSharp = Math.max(0.05f, focus - depth * 0.8f), farSharp = focus + depth * 1.3f;
        float nearBlurry = nearSharp * 0.45f, farBlurry = farSharp + Math.max(2.5f, focus * 2f);
        return new com.hypixel.hytale.protocol.DepthOfFieldSettings(nearBlurry, nearSharp, farSharp, farBlurry, blur, blur);
    }

    /** {"slowmo": 0.3, "ms": 600} — весь мир медленнее (World.setTimeDilation), потом обратно. */
    private void slowmo(Map<String, Object> st, double when) {
        float k = (float) Math.max(0.0101, Math.min(4, Json.num(st, "slowmo", 0.3)));
        timeDilation(k);
        slowUntil = k >= 0.999 ? -1 : when + Json.num(st, "ms", 600);
    }

    private void timeDilation(float k) {
        try {
            World.setTimeDilation(k, store);
        } catch (Throwable t) {
            Fx.warnOnce("Замедление мира не сработало", t);
        }
    }

    /** {"face": точка или имя, "on": "bram"} — повернуться лицом. */
    private void face(Map<String, Object> st, List<Ref<EntityStore>> on) {
        Vector3d to = point(st.get("face"));
        for (Ref<EntityStore> r : on) {
            Vector3d p = Fx.position(store, r);
            Fx.place(store, r, p, Fx.yawTowards(p, to));
        }
    }

    static double ease(String name, double t) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "linear" -> t;
            case "inquad" -> t * t;
            case "outquad" -> 1 - (1 - t) * (1 - t);
            case "incubic" -> t * t * t;
            case "outcubic" -> UiAnim.easeOut(t);
            case "inoutcubic" -> UiAnim.easeInOut(t);
            default -> t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2; // InOutQuad
        };
    }

    private Vector3d center(List<Ref<EntityStore>> refs) {
        if (refs.isEmpty()) return Fx.position(store, cast.caster());
        double x = 0, y = 0, z = 0;
        for (Ref<EntityStore> r : refs) {
            Vector3d p = Fx.position(store, r);
            x += p.x;
            y += p.y;
            z += p.z;
        }
        return new Vector3d(x / refs.size(), y / refs.size(), z / refs.size());
    }

    private float yawOf(Ref<EntityStore> ref) {
        try {
            TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
            if (t != null) return t.getRotation().yaw();
        } catch (Throwable t) {
            Fx.warnOnce("Поворот сущности не читается", t);
        }
        return cast.casterYaw();
    }

    // ---------- движение ----------

    /**
     * move: run / walk к to (target, targets, home, имя актёра, точка), back — домой. Кто идёт — on
     * (по умолчанию кастующий), каждый к своей точке со сдвигом stopAt. В катсцене "speed" — блоков в секунду.
     */
    private void move(Map<String, Object> st) {
        String how = Json.str(st, "move", "run");
        List<Ref<EntityStore>> movers = st.containsKey("on") ? who(Json.str(st, "on", "caster")) : List.of(cast.caster());
        boolean run = !how.equals("walk");
        double speed = Json.num(st, "speed", 0);
        for (Ref<EntityStore> ref : movers) {
            Vector3d from = Fx.position(store, ref);
            if (how.equals("back")) {
                Vector3d home = homes.getOrDefault(ref, cast.casterHome());
                go(ref, from, home, true, homeYaws.getOrDefault(ref, cast.casterYaw()), speed);
                continue;
            }
            Object toSpec = st.getOrDefault("to", "target");
            boolean home = "home".equals(toSpec);
            Vector3d goal = home ? homes.getOrDefault(ref, cast.casterHome()) : point(toSpec);
            boolean actorGoal = toSpec instanceof String && !home;
            double stop = actorGoal ? Json.num(st, "stopAt", 1.6) : Json.num(st, "stopAt", 0);
            double dx = goal.x - from.x, dz = goal.z - from.z, len = Math.hypot(dx, dz);
            if (len <= stop + 0.05) continue;
            Vector3d dest = new Vector3d(goal.x - dx / len * stop, goal.y, goal.z - dz / len * stop);
            if (!actorGoal && !home) {
                // Точка в мире: держаться земли.
                Double y = Fx.groundY(world, dest.x, dest.z, dest.y);
                if (y != null && Math.abs(y - dest.y) < 4) dest = new Vector3d(dest.x, y, dest.z);
            }
            float endYaw = st.containsKey("face") ? Fx.yawTowards(dest, point(st.get("face"))) : Fx.yawTowards(dest, goal);
            if (len < 1e-3) endYaw = yawOf(ref);
            go(ref, from, dest, run, endYaw, speed);
        }
    }

    private void go(Ref<EntityStore> ref, Vector3d from, Vector3d to, boolean run, float endYaw, double speed) {
        double dist = Math.hypot(to.x - from.x, to.z - from.z);
        double v = speed > 0 ? speed : (run ? RUN_SPEED : WALK_SPEED);
        long ms = Math.max(150, Math.round(dist / v * 1000 * slow));
        plugin.mover().go(store, world, ref, from, to, ms, run, false, endYaw);
    }

    // ---------- анимации ----------

    private void anim(Map<String, Object> st, List<Ref<EntityStore>> on) {
        List<String> names = withFallback(st, "anim");
        for (Ref<EntityStore> ref : on) {
            boolean ok = false;
            for (String n : names) {
                if (playAnim(ref, n)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) missing.add("анимация " + String.join(" / ", names));
        }
    }

    private boolean playAnim(Ref<EntityStore> ref, String name) {
        if (name.startsWith("weapon:")) {
            List<String> hints = ref == cast.caster() ? cast.weaponHints() : List.of();
            return WeaponAnims.play(store, ref, hints, List.of(name.substring(7)));
        }
        if (name.startsWith("emote:")) {
            String e = Fx.pickEmote(List.of(name.substring(6)), Set.of());
            return e != null && Fx.emote(store, ref, e);
        }
        int slash = name.indexOf('/');
        if (slash > 0) {
            // Набор анимаций (свой, из пака мода) / анимация в нём: "Jrpg_Bram/BladeWipe".
            String set = name.substring(0, slash), anim = name.substring(slash + 1);
            ItemPlayerAnimations ipa = ItemPlayerAnimations.getAssetMap().getAssetMap().get(set);
            if (ipa == null || ipa.getAnimations() == null || !ipa.getAnimations().containsKey(anim)) return false;
            AnimationUtils.playAnimation(ref, AnimationSlot.Action, set, anim, true, store);
            return true;
        }
        String found = Fx.pickAnimation(store, ref, List.of(name));
        if (found == null || !found.equalsIgnoreCase(name)) return false;
        AnimationUtils.playAnimation(ref, AnimationSlot.Action, found, true, store);
        return true;
    }

    private void emote(Map<String, Object> st, List<Ref<EntityStore>> on) {
        String want = Json.str(st, "emote", "");
        String e = Fx.pickEmote(List.of(want), Set.of());
        if (e == null) {
            missing.add("эмоция " + want);
            return;
        }
        for (Ref<EntityStore> ref : on) Fx.emote(store, ref, e);
    }

    // ---------- свечение ----------

    private void glow(Map<String, Object> st, List<Ref<EntityStore>> on) {
        Object g = st.get("glow");
        String color = g instanceof Map<?, ?> m ? String.valueOf(m.get("color")) : String.valueOf(g);
        float seconds = (float) (Json.num(st, "ms", 1000) * slow / 1000.0);
        for (Ref<EntityStore> ref : on) {
            String used = Glow.apply(store, ref, color, seconds);
            if (used == null) missing.add("свечение " + color + " (в игре нет эффектов с подсветкой — см. лог «Подсветки»)");
            else plugin.log("Сцена " + id + ": свечение " + color + " → эффект " + used);
        }
    }

    /**
     * Подсветка модели. Свой цвет без своих ассетов задать нельзя (EntityEffect и ModelVFX — ассеты), поэтому
     * берётся ванильный эффект с подсветкой (ModelVFX.highlightColor) ближайшего цвета. Список — в лог один раз.
     * Эффекты с уроном (горение, яд), сменой модели и неуязвимостью не берутся.
     */
    static final class Glow {
        private record Option(String id, EntityEffect effect, int rgb) {}

        private static volatile List<Option> options;

        static String apply(Store<EntityStore> store, Ref<EntityStore> ref, String hex, float seconds) {
            if (ref == null || !ref.isValid()) return null;
            Option best = nearest(rgb(hex));
            if (best == null) return null;
            EffectControllerComponent ec = store.getComponent(ref, EffectControllerComponent.getComponentType());
            if (ec == null) {
                ec = new EffectControllerComponent();
                store.putComponent(ref, EffectControllerComponent.getComponentType(), ec);
            }
            ec.addEffect(ref, best.effect(), seconds, OverlapBehavior.OVERWRITE, store);
            return best.id();
        }

        private static Option nearest(int want) {
            Option best = null;
            long bestD = Long.MAX_VALUE;
            for (Option o : options()) {
                int dr = ((o.rgb() >> 16) & 255) - ((want >> 16) & 255);
                int dg = ((o.rgb() >> 8) & 255) - ((want >> 8) & 255);
                int db = (o.rgb() & 255) - (want & 255);
                long d = (long) dr * dr + (long) dg * dg + (long) db * db;
                if (d < bestD) {
                    bestD = d;
                    best = o;
                }
            }
            return best;
        }

        private static List<Option> options() {
            List<Option> o = options;
            if (o != null) return o;
            List<Option> out = new ArrayList<>();
            try {
                for (Map.Entry<String, EntityEffect> e : EntityEffect.getAssetMap().getAssetMap().entrySet()) {
                    EntityEffect ef = e.getValue();
                    if (ef == null || ef.getApplicationEffects() == null) continue;
                    if (ef.getDamageCalculator() != null || ef.getModelChange() != null || ef.isInvulnerable()) continue;
                    String vfx = ef.getApplicationEffects().toPacket().modelVFXId;
                    if (vfx == null || vfx.isBlank()) continue;
                    ModelVFX mv = ModelVFX.getAssetMap().getAssetMap().get(vfx);
                    if (mv == null || mv.getHighlightColor() == null) continue;
                    Color c = mv.getHighlightColor();
                    out.add(new Option(e.getKey(), ef, ((c.red & 255) << 16) | ((c.green & 255) << 8) | (c.blue & 255)));
                }
            } catch (Throwable t) {
                Fx.warnOnce("Список эффектов с подсветкой не прочитать", t);
            }
            if (out.isEmpty()) return out; // ассеты ещё не загружены — попробуем в следующий раз
            options = out;
            StringBuilder sb = new StringBuilder();
            for (Option x : out) sb.append(x.id()).append(String.format("(#%06x) ", x.rgb()));
            JrpgPlugin.get().log("Подсветки в игре (" + out.size() + "): " + sb.toString().trim());
            return out;
        }
    }

    // ---------- частицы и фигуры ----------

    private static volatile Set<String> particleIds;

    private void particles(Map<String, Object> st, List<Ref<EntityStore>> on) {
        List<String> names = withFallback(st, "particles");
        String name = null;
        for (String n : names) {
            String r = resolveParticle(n);
            if (r != null) {
                name = r;
                break;
            }
        }
        if (name == null) {
            missing.add("частицы " + String.join(" / ", names));
            return;
        }
        Vector3f off = vec(st.get("offset"), st.containsKey("attach") ? new Vector3f() : new Vector3f(0, 1.0f, 0));
        String attach = Json.str(st, "attach", "");
        String color = Json.str(st, "color", "");
        for (Ref<EntityStore> ref : on) {
            if (!attach.isEmpty()) {
                attached(ref, name, attach, off, color);
            } else {
                Vector3d p = Fx.position(store, ref);
                Fx.particle(name, new Vector3d(p.x + off.x, p.y + off.y, p.z + off.z), viewer, store);
            }
        }
    }

    /** "@fire" — частица из ParticleCatalog по ключу, иначе id системы частиц (должен быть в игре). */
    private static String resolveParticle(String n) {
        if (n.startsWith("@")) {
            String r = ParticleCatalog.get(n.substring(1));
            return r == null || r.isBlank() ? null : r;
        }
        Set<String> ids = particleIds;
        if (ids == null) {
            List<String> all = ParticleCatalog.ids();
            if (all.isEmpty()) return n;
            ids = Set.copyOf(all);
            particleIds = ids;
        }
        return ids.contains(n) ? n : null;
    }

    /** Частицы на модели: на оружии (PrimaryItem / SecondaryItem), на всей сущности (Self) или на кости по имени. */
    private void attached(Ref<EntityStore> ref, String system, String attach, Vector3f off, String color) {
        NetworkId nid = store.getComponent(ref, NetworkId.getComponentType());
        if (nid == null) {
            missing.add("частицы на модели: у сущности нет сетевого id");
            return;
        }
        ModelParticle mp = new ModelParticle();
        mp.systemId = system;
        mp.scale = 1f;
        if (!color.isEmpty()) {
            int c = rgb(color);
            mp.color = new Color((byte) (c >> 16), (byte) (c >> 8), (byte) c);
        }
        EntityPart part = null;
        for (EntityPart p : EntityPart.values()) if (p.name().equalsIgnoreCase(attach)) part = p;
        if (part != null) {
            mp.targetEntityPart = part;
        } else {
            mp.targetEntityPart = EntityPart.Self;
            mp.targetNodeName = attach;
        }
        mp.positionOffset = off;
        mp.rotationOffset = new Direction(0f, 0f, 0f);
        mp.detachedFromModel = false;
        player.getPacketHandler().writeNoCache(new SpawnModelParticles(nid.getId(), new ModelParticle[]{mp}));
    }

    private void shape(Map<String, Object> st, List<Ref<EntityStore>> on) {
        String kind = Json.str(st, "shape", "");
        Vector3d from = Fx.position(store, cast.caster());
        for (Ref<EntityStore> ref : on) {
            Vector3d p = Fx.position(store, ref);
            if (kind.equals("ring")) {
                double r = Json.num(st, "radius", 1.2);
                int c = rgb(Json.str(st, "color", "#ffd27a"));
                Vector3f col = new Vector3f(((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f);
                Vfx.donut(world, p.x, p.y + 0.06, p.z, r, r * 0.8, col, 0.9f, (float) (Json.num(st, "ms", 1200) * slow / 1000));
            } else {
                Vfx.play(plugin, world, kind, from, p, cast.targets().size() > 1);
            }
        }
    }

    // ---------- звук и тряска ----------

    private void sound(String id) {
        int idx = id.isBlank() ? -1 : SoundEvent.getAssetMap().getIndex(id);
        if (idx < 0) {
            missing.add("звук " + id);
            return;
        }
        SoundUtil.playSoundEvent2dToPlayer(player, idx, SoundCategory.SFX, 1.0f, 1.0f);
    }

    private static volatile String shakeId;

    /** Тряска камеры: ванильный эффект камеры с тряской (подбирается по имени, список в лог). */
    private void shake(double intensity) {
        var map = com.hypixel.hytale.server.core.asset.type.camera.CameraEffect.getAssetMap().getAssetMap();
        String sid = shakeId;
        if (sid == null || !map.containsKey(sid)) {
            if (map.isEmpty()) {
                missing.add("тряска (эффектов камеры нет)");
                return;
            }
            JrpgPlugin.get().log("Эффекты камеры (" + map.size() + "): " + new java.util.TreeSet<>(map.keySet()));
            for (String word : List.of("impact", "shake", "explosion", "heavy", "hit", "damage")) {
                for (String k : new java.util.TreeSet<>(map.keySet())) {
                    if (sid == null && k.toLowerCase(Locale.ROOT).contains(word)) sid = k;
                }
                if (sid != null) break;
            }
            if (sid == null) sid = map.keySet().iterator().next();
            shakeId = sid;
            JrpgPlugin.get().log("Тряска сцен: " + sid);
        }
        player.getPacketHandler().writeNoCache(map.get(sid).createCameraShakePacket((float) intensity));
    }

    // ---------- разбор ----------

    private static List<String> withFallback(Map<String, Object> st, String key) {
        List<String> out = new ArrayList<>();
        Object v = st.get(key);
        if (v instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o));
        else if (v != null) out.add(String.valueOf(v));
        out.addAll(Json.strings(st, "fallback"));
        return out;
    }

    private static Vector3f vec(Object o, Vector3f def) {
        if (!(o instanceof List<?> l) || l.size() < 3) return def;
        return new Vector3f(f(l.get(0)), f(l.get(1)), f(l.get(2)));
    }

    private static float f(Object o) {
        return o instanceof Number n ? n.floatValue() : 0f;
    }

    static int rgb(String hex) {
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return Integer.parseInt(h.substring(0, 6), 16);
        } catch (Exception e) {
            return 0xffffff;
        }
    }
}
