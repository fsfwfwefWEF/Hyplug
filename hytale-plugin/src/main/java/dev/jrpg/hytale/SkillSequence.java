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
    }

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
        if (now > MAX_MS) {
            missing.add("сцена дольше " + (long) (MAX_MS / 1000) + " с — оборвана");
            finish();
            return;
        }
        while (index < steps.size()) {
            Map<String, Object> st = steps.get(index);
            double when = when(st);
            if (now < when) break;
            if (st.containsKey("wait")) {
                if (!waited(st, now, when)) break;
                segStart = prev = now;
                index++;
                continue;
            }
            run(st, when);
            prev = when;
            lastAction = now;
            index++;
        }
        if (cam != null && !cam.frame(now)) cam = null;
        // Конец: все шаги прошли, кастующий дошёл, последние эффекты успели доиграть.
        if (index >= steps.size() && !plugin.mover().moving(cast.caster()) && now - lastAction > 700) finish();
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
        if (what.equals("arrived")) return !plugin.mover().moving(cast.caster()) || now - since > 8000;
        missing.add("wait «" + what + "» (есть: arrived или число мс)");
        return true;
    }

    // ---------- шаг ----------

    private void run(Map<String, Object> st, double when) {
        List<Ref<EntityStore>> on = who(Json.str(st, "on", "caster"));
        StringBuilder log = new StringBuilder();
        for (String key : st.keySet()) {
            if (List.of("t", "on", "ms", "ease", "fallback", "attach", "offset", "color", "dist", "height", "side",
                    "from", "to", "stopAt", "radius", "fallMs").contains(key)) continue;
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
                    default -> missing.add("шаг «" + key + "» пока не поддержан");
                }
            } catch (Throwable t) {
                Fx.warnOnce("Сцена " + id + ": шаг " + key + " не сработал", t);
                missing.add("шаг " + key + " упал (см. лог)");
            }
        }
        plugin.log("Сцена " + id + " [" + Math.round(when) + " мс]: " + log.toString().trim()
                + (st.containsKey("on") ? " → " + st.get("on") : ""));
    }

    private List<Ref<EntityStore>> who(String on) {
        return switch (on) {
            case "target" -> cast.target() == null ? List.of() : List.of(cast.target());
            case "targets" -> cast.targets();
            default -> List.of(cast.caster());
        };
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
            default -> missing.add("камера «" + kind + "» (есть focus, follow, orbit, battle)");
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

    private void move(Map<String, Object> st) {
        String how = Json.str(st, "move", "run");
        Ref<EntityStore> ref = cast.caster();
        Vector3d from = Fx.position(store, ref);
        if (how.equals("back")) {
            go(ref, from, cast.casterHome(), true, cast.casterYaw());
            return;
        }
        String to = Json.str(st, "to", "target");
        Vector3d goal = to.equals("home") ? cast.casterHome() : center(who(to));
        double stop = to.equals("home") ? 0 : Json.num(st, "stopAt", 1.6);
        double dx = goal.x - from.x, dz = goal.z - from.z, len = Math.hypot(dx, dz);
        if (len <= stop + 0.05) return;
        Vector3d dest = new Vector3d(goal.x - dx / len * stop, goal.y, goal.z - dz / len * stop);
        go(ref, from, dest, !how.equals("walk"), Fx.yawTowards(dest, goal));
    }

    private void go(Ref<EntityStore> ref, Vector3d from, Vector3d to, boolean run, float endYaw) {
        double dist = Math.hypot(to.x - from.x, to.z - from.z);
        long ms = Math.max(150, Math.round(dist / (run ? RUN_SPEED : WALK_SPEED) * 1000 * slow));
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
