package dev.jrpg.hytale;

import com.hypixel.hytale.builtin.weather.components.WeatherTracker;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.interface_.HudComponent;
import com.hypixel.hytale.protocol.packets.world.UpdateTime;
import com.hypixel.hytale.protocol.packets.world.UpdateTimeSettings;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.asset.type.weather.config.Weather;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jrpg.battle.Encounter;
import dev.jrpg.battle.GameData;
import dev.jrpg.battle.Json;
import dev.jrpg.battle.Progression;
import org.joml.Vector3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * Катсцены (data/cutscenes.json, копия для правки — в папке мода, перечитывается при изменении файла).
 *
 * Сцена = актёры + шаги на том же движке, что и навыки ({@link SkillSequence}: камера, движение, анимации,
 * эмоции, частицы, звуки, тряска, свечение, метки и переходы), плюс шаги катсцены (этот класс):
 * полосы кино, затемнение, вспышка, титры, субтитры, реплики в окне с печатью по буквам и звуком,
 * выбор ответа, время суток и погода только у этого игрока, музыка, появление и уход актёров.
 *
 * Управление без окна (приём Viewfinder, {@link CutsceneInput}): ЛКМ — дальше (допечатать реплику,
 * закрыть её, подтвердить выбор), колесо — выбор ответа, цифры 1–4 — сразу ответ, ПКМ дважды — пропуск.
 * Запасной путь: /jrpg cut next | skip | 1..4.
 *
 * Безопасность (приёмы CameraMenu PlaybackSafety): до начала записывается файл спасения
 * (players/cutscene-&lt;uuid&gt;.json — где стоял, какого размера был); игрок заморожен и неуязвим, тело спрятано;
 * при входе в игру после краша всё возвращается ({@link #rescue}).
 */
final class Cutscene implements SkillSequence.Ext {
    static final String FILE = "cutscenes.json";

    /** Общие настройки из "_settings". */
    static final class Config {
        boolean freezePlayer = true, hidePlayer = true, skippable = true;
        int barsHeight = 96;
        long clickTimeoutMs = 30000, autoHoldMs = 1600;
        String hint = "ЛКМ — дальше   ·   колесо / 1-4 — ответ   ·   ПКМ x2 — пропустить";
    }

    /** Описание сцены из файла. */
    record Def(String id, String name, Map<String, Object> raw, List<Map<String, Object>> steps,
               Map<String, Object> actors, String before, Map<String, Object> zone, boolean once, String then,
               boolean skippable) {}

    /** Где сцена стоит: начало координат и куда «вперёд»; encounter — встреча, перед которой она идёт. */
    record Place(Vector3d origin, float yaw, Encounter encounter, Vector3d encounterCenter) {}

    private static volatile Config config = new Config();
    private static volatile Map<String, Def> cache = Map.of();
    private static volatile long cacheStamp = -1;
    private static final Map<UUID, Cutscene> active = new ConcurrentHashMap<>();

    static Config settings() { return config; }

    static boolean playing(UUID player) { return active.containsKey(player); }

    // ---------- файл ----------

    /** Все сцены (перечитываются, если файл поменялся). */
    static synchronized Map<String, Def> defs(JrpgPlugin plugin) throws Exception {
        Path f = plugin.dataDir().resolve(FILE);
        if (!Files.exists(f)) {
            Files.createDirectories(f.getParent());
            Files.writeString(f, GameData.builtin(FILE));
        }
        long stamp = Files.getLastModifiedTime(f).toMillis();
        if (stamp == cacheStamp) return cache;
        Map<String, Object> root = Json.obj(Json.parse(Files.readString(f)));
        Config c = new Config();
        if (root.get("_settings") instanceof Map<?, ?> sm) {
            Map<String, Object> s = Json.obj(sm);
            c.freezePlayer = Json.bool(s, "freezePlayer", c.freezePlayer);
            c.hidePlayer = Json.bool(s, "hidePlayer", c.hidePlayer);
            c.skippable = Json.bool(s, "skippable", c.skippable);
            c.barsHeight = (int) Json.num(s, "barsHeight", c.barsHeight);
            c.clickTimeoutMs = (long) Json.num(s, "clickTimeoutMs", c.clickTimeoutMs);
            c.autoHoldMs = (long) Json.num(s, "autoHoldMs", c.autoHoldMs);
            c.hint = Json.str(s, "hint", c.hint);
        }
        Map<String, Def> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : root.entrySet()) {
            if (e.getKey().startsWith("_") || !(e.getValue() instanceof Map<?, ?> m)) continue;
            Map<String, Object> d = Json.obj(m);
            List<Map<String, Object>> steps = new ArrayList<>();
            if (d.get("steps") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?>) steps.add(Json.obj(o));
            Map<String, Object> actors = d.get("actors") instanceof Map<?, ?> am ? Json.obj(am) : Map.of();
            Map<String, Object> zone = d.get("zone") instanceof Map<?, ?> zm ? Json.obj(zm) : null;
            out.put(e.getKey(), new Def(e.getKey(), Json.str(d, "name", e.getKey()), d, steps, actors,
                    d.get("before") == null ? null : String.valueOf(d.get("before")), zone, Json.bool(d, "once", true),
                    Json.str(d, "then", "battle"), Json.bool(d, "skippable", c.skippable)));
        }
        config = c;
        cache = out;
        cacheStamp = stamp;
        return out;
    }

    /** Сцена перед встречей (ещё не виденная игроком, если она одноразовая), иначе null. */
    static Def before(JrpgPlugin plugin, UUID player, String encounterId) {
        try {
            for (Def d : defs(plugin).values()) {
                if (encounterId.equals(d.before()) && !(d.once() && seen(plugin, player, d.id()))) return d;
            }
        } catch (Exception e) {
            Fx.warnOnce("Ошибка в " + FILE + ": " + e.getMessage(), e);
        }
        return null;
    }

    /** Сцена, в зону которой игрок вошёл (мир, x/y/z, radius), иначе null. */
    static Def zoneAt(JrpgPlugin plugin, UUID player, String world, Vector3d pos) {
        try {
            for (Def d : defs(plugin).values()) {
                Map<String, Object> z = d.zone();
                if (z == null) continue;
                String w = Json.str(z, "world", "*");
                if (!w.equals("*") && !w.equalsIgnoreCase(world)) continue;
                double dx = pos.x - Json.num(z, "x", 0), dy = pos.y - Json.num(z, "y", 0), dz = pos.z - Json.num(z, "z", 0);
                double r = Json.num(z, "radius", 5);
                if (dx * dx + dz * dz <= r * r && Math.abs(dy) < Json.num(z, "height", 6)
                        && !(d.once() && seen(plugin, player, d.id()))) return d;
            }
        } catch (Exception e) {
            Fx.warnOnce("Ошибка в " + FILE + ": " + e.getMessage(), e);
        }
        return null;
    }

    static boolean seen(JrpgPlugin plugin, UUID player, String id) {
        return plugin.flag(player, "cut:" + id, false);
    }

    // ---------- запуск ----------

    /**
     * Запустить сцену (в потоке мира). after — что сделать после (бой, окно перед боем), вызывается и при
     * пропуске. null — запущена, иначе текст ошибки (after не вызывается).
     */
    static String play(JrpgPlugin plugin, PlayerRef playerRef, Ref<EntityStore> me, Store<EntityStore> store, World world,
                       Def def, Place place, Runnable after) {
        UUID uuid = playerRef.getUuid();
        if (active.containsKey(uuid)) return "Сцена уже идёт.";
        if (plugin.sessions().containsKey(uuid)) return "Сначала закончи бой.";
        if (def.steps().isEmpty()) return "У сцены " + def.id() + " нет шагов.";
        Cutscene c = new Cutscene(plugin, playerRef, me, store, world, def, place, after);
        active.put(uuid, c);
        try {
            c.begin();
        } catch (Throwable t) {
            plugin.warn("Катсцена " + def.id() + " не запустилась", t);
            c.cleanup(false);
            return "Катсцена не запустилась, см. лог.";
        }
        return null;
    }

    /** Место сцены перед встречей: начало у игрока, вперёд — к центру встречи (или как задано в сцене). */
    static Place placeFor(Def def, Store<EntityStore> store, Ref<EntityStore> me, Encounter e, Vector3d center) {
        Vector3d p = Fx.position(store, me);
        Object o = def.raw().get("origin");
        if (o instanceof Map<?, ?> m && Json.obj(m).get("world") instanceof List<?> w && w.size() >= 3) {
            Vector3d at = new Vector3d(num(w.get(0)), num(w.get(1)), num(w.get(2)));
            float yaw = (float) Math.toRadians(Json.num(Json.obj(m), "yaw", 0));
            return new Place(at, yaw, e, center);
        }
        if ("encounter".equals(o) && center != null) {
            return new Place(new Vector3d(center), Fx.yawTowards(center, p), e, center);
        }
        float yaw;
        if (center != null && Math.hypot(center.x - p.x, center.z - p.z) > 0.5) yaw = Fx.yawTowards(p, center);
        else {
            HeadRotation head = store.getComponent(me, HeadRotation.getComponentType());
            yaw = head == null ? 0 : head.getRotation().yaw();
        }
        return new Place(p, yaw, e, center);
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    // ---------- ввод (из любых потоков) ----------

    enum Key { NEXT, SKIP, SKIP_NOW, WHEEL, PICK }

    private record Input(Key key, int arg) {}

    /** Ввод игрока; true — сцена идёт и ввод съеден. */
    static boolean input(UUID player, Key key, int arg) {
        Cutscene c = active.get(player);
        if (c == null) return false;
        long now = System.currentTimeMillis();
        // ЛКМ может прийти и событием взаимодействия, и событием мыши — второе в пределах 150 мс не считаем.
        if (key == Key.NEXT || key == Key.SKIP) {
            if (key == c.lastKey && now - c.lastKeyAt < 150) return true;
            c.lastKey = key;
            c.lastKeyAt = now;
        }
        c.inputs.add(new Input(key, arg));
        return true;
    }

    /** Оборвать сцену игрока (вышел из игры, сервер останавливается). */
    static void cancel(UUID player) {
        Cutscene c = active.get(player);
        if (c == null) return;
        try {
            c.world.execute(() -> {
                if (c.seq != null && !c.seq.finished()) c.seq.end();
                else c.cleanup(false);
            });
        } catch (Throwable t) {
            active.remove(player);
        }
    }

    static void cancelAll() {
        for (UUID id : List.copyOf(active.keySet())) cancel(id);
    }

    // ---------- сцена ----------

    private final JrpgPlugin plugin;
    private final PlayerRef player;
    private final Ref<EntityStore> me;
    private final Store<EntityStore> store;
    private final World world;
    private final Def def;
    private final Place place;
    private final Runnable after;
    private final CutsceneHud hud;
    private final ConcurrentLinkedQueue<Input> inputs = new ConcurrentLinkedQueue<>();
    private volatile Key lastKey;
    private volatile long lastKeyAt;

    private SkillSequence seq;
    private BattleCamera camera;
    private final Map<String, Ref<EntityStore>> actors = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> actorDefs = new LinkedHashMap<>();
    private boolean addedFrozen, addedInvulnerable, timeChanged, weatherChanged, musicChanged, cleaned;
    private Float playerScale;

    // реплика / выбор
    private boolean dialog, typing, choosing;
    private double typed, lineDoneAt, autoMs = -1, lineStart;
    private List<Map<String, Object>> options = List.of();
    private long clicks;
    private double lastTick;
    private int letterCount;
    // пропуск
    private long skipArmedUntil, fadeSetAt;
    private double skipAt = -1;

    private Cutscene(JrpgPlugin plugin, PlayerRef player, Ref<EntityStore> me, Store<EntityStore> store, World world,
                     Def def, Place place, Runnable after) {
        this.plugin = plugin;
        this.player = player;
        this.me = me;
        this.store = store;
        this.world = world;
        this.def = def;
        this.place = place;
        this.after = after;
        this.hud = new CutsceneHud(player);
    }

    private void begin() {
        UUID uuid = player.getUuid();
        writeRescue();
        plugin.followers().pause(uuid);

        // Игрок: неуязвим, заморожен, тело спрятано (в кадре играет его двойник-актёр, если он есть в сцене).
        if (store.getComponent(me, Invulnerable.getComponentType()) == null) {
            store.putComponent(me, Invulnerable.getComponentType(), Invulnerable.INSTANCE);
            addedInvulnerable = true;
        }
        if (config.freezePlayer && store.getComponent(me, Frozen.getComponentType()) == null) {
            store.putComponent(me, Frozen.getComponentType(), Frozen.get());
            addedFrozen = true;
        }
        if (Json.bool(def.raw(), "hidePlayer", config.hidePlayer)) {
            Float was = Fx.scale(store, me);
            playerScale = was == null || was < 0.2f ? 1.0f : was;
            Fx.setScale(store, me, 0.01f);
        }

        // HUD: ванильный прячем, свой — полосы, затемнение, реплики.
        Player p = store.getComponent(me, Player.getComponentType());
        if (p != null) {
            p.getHudManager().hideHudComponents(player, HudComponent.values());
            if (Json.bool(def.raw(), "fromBlack", false)) {
                hud.fadeTarget = 1;
                hud.snap();
            }
            hud.hint = def.skippable() ? config.hint : config.hint.replace("   ·   ПКМ x2 — пропустить", "");
            p.getHudManager().addCustomHud(player, hud);
        }

        // Актёры.
        for (Map.Entry<String, Object> e : def.actors().entrySet()) {
            if (e.getValue() instanceof Map<?, ?> m) spawnActor(e.getKey(), Json.obj(m));
        }

        // Камера: кино-режим, первый кадр — сзади-сверху от начала сцены, взгляд вперёд.
        Vector3d o = place.origin();
        double fx = -Math.sin(place.yaw()), fz = -Math.cos(place.yaw());
        Vector3d ahead = new Vector3d(o.x + fx * 6, o.y, o.z + fz * 6);
        camera = new BattleCamera(player, plugin.settings().camera, o, ahead);
        camera.cinema(true);
        camera.aim("cut:start", new Vector3d(o.x - fx * 5, o.y + 3, o.z - fz * 5), new Vector3d(ahead.x, o.y + 1.2, ahead.z), 1f);

        Ref<EntityStore> caster = actors.isEmpty() ? me : actors.values().iterator().next();
        Ref<EntityStore> target = actors.get(Json.str(def.raw(), "target", ""));
        List<Ref<EntityStore>> targets = new ArrayList<>();
        for (String n : Json.strings(def.raw(), "targets")) if (actors.get(n) != null) targets.add(actors.get(n));
        if (targets.isEmpty() && target != null) targets.add(target);

        seq = new SkillSequence(plugin, "катсцена " + def.id(), def.steps(), player, me, store, world, camera,
                new SkillSequence.Cast(caster, target, targets, Fx.position(store, caster), yawOf(caster), List.of()), 1,
                new SkillSequence.Hooks() {
                    @Override
                    public void hit(List<Ref<EntityStore>> on) {
                        for (Ref<EntityStore> t : on) Fx.animate(store, t, plugin.settings().anim.hurt);
                    }

                    @Override
                    public void done(SkillSequence q) {
                        if (!q.missing().isEmpty()) {
                            player.sendMessage(Message.raw("Катсцена " + def.id() + ": не нашлось — " + String.join("; ", q.missing())));
                        }
                        cleanup(true);
                    }
                })
                .actors(actors).ext(this).maxMs(Json.num(def.raw(), "maxSeconds", 600) * 1000).origin(o, place.yaw());
        plugin.log("Катсцена " + def.id() + " для " + player.getUsername() + ": актёров " + actors.size()
                + ", шагов " + def.steps().size());
        seq.start(300);
    }

    // ---------- актёры ----------

    /**
     * Актёр: {"hero": "hero"|"bram"|...} — герой отряда (вид и снаряжение как в бою; "hero" — сам игрок),
     * {"enemy": id} — враг из enemies.json, {"role": роль NPC, "look": вид человека} — кто угодно.
     * at — точка (как в шагах), face — куда смотреть, name — имя в репликах, color — цвет имени,
     * hidden — появиться спрятанным (шаг show).
     */
    private void spawnActor(String id, Map<String, Object> a) {
        Vector3d pos = ground(seqPoint(a.getOrDefault("at", List.of(0, 0, 0))));
        var s = plugin.settings();
        String role, name = Json.str(a, "name", id);
        HumanLook.Look look = null;
        Progression.Hero hero = null;
        if (a.get("hero") != null) {
            String h = String.valueOf(a.get("hero"));
            try {
                hero = plugin.partyOf(player.getUuid()).hero(h);
            } catch (IllegalArgumentException e) {
                hero = null;
            }
            if (!a.containsKey("name")) name = hero != null ? hero.def.name() : (h.equals("hero") ? player.getUsername() : h);
            look = h.equals("hero") ? HumanLook.ofPlayer(store, me) : HumanLook.of(h, s.companionLooks.get(h));
            role = s.companionRoles.getOrDefault(h, s.companionRoles.getOrDefault("bram", s.fallbackRole));
        } else if (a.get("enemy") != null) {
            String en = String.valueOf(a.get("enemy"));
            try {
                var t = plugin.data().enemy(en);
                role = t.role();
                if (!a.containsKey("name")) name = t.name();
            } catch (Exception e) {
                role = s.fallbackRole;
            }
        } else {
            role = Json.str(a, "role", s.fallbackRole);
            if (a.get("look") != null) look = HumanLook.of(id, a.get("look") instanceof String str ? str : Json.write(a.get("look")));
        }
        float yaw = a.containsKey("face") ? Fx.yawTowards(pos, seqPoint(a.get("face")))
                : (float) (place.yaw() + Math.toRadians(Json.num(a, "yaw", 0)));
        Ref<EntityStore> ref = Fx.spawnPuppet(store, role, id, pos, yaw, Json.str(a, "nameplate", ""), look);
        if (ref == null) {
            player.sendMessage(Message.raw("Катсцена: актёр " + id + " не появился (роль " + role + ")."));
            return;
        }
        if (hero != null) GearVisuals.dressNpc(store, ref, hero, plugin.content());
        if (Json.bool(a, "hidden", false)) Fx.setScale(store, ref, 0.01f);
        Map<String, Object> d = new LinkedHashMap<>(a);
        d.put("name", name);
        actorDefs.put(id, d);
        actors.put(id, ref);
        if (seq != null) seq.actors(Map.of(id, ref));
    }

    /** Точка из описания актёра: до старта движка — относительно места сцены. */
    private Vector3d seqPoint(Object spec) {
        if (seq != null) return seq.point(spec);
        if (spec instanceof List<?> l && l.size() >= 3) {
            double fx = -Math.sin(place.yaw()), fz = -Math.cos(place.yaw()), rx = -fz, rz = fx;
            double x = num(l.get(0)), y = num(l.get(1)), z = num(l.get(2));
            Vector3d o = place.origin();
            return new Vector3d(o.x + rx * x + fx * z, o.y + y, o.z + rz * x + fz * z);
        }
        if (spec instanceof String n) {
            if (n.equals("player")) return Fx.position(store, me);
            if (n.equals("encounter") && place.encounterCenter() != null) return new Vector3d(place.encounterCenter());
            Ref<EntityStore> r = actors.get(n);
            if (r != null) return Fx.position(store, r);
        }
        if (spec instanceof Map<?, ?> m && Json.obj(m).get("world") instanceof List<?> w && w.size() >= 3) {
            return new Vector3d(num(w.get(0)), num(w.get(1)), num(w.get(2)));
        }
        return new Vector3d(place.origin());
    }

    private Vector3d ground(Vector3d p) {
        Double y = Fx.groundY(world, p.x, p.z, p.y);
        return y == null || Math.abs(y - p.y) > 4 ? p : new Vector3d(p.x, y, p.z);
    }

    private float yawOf(Ref<EntityStore> ref) {
        try {
            var t = store.getComponent(ref, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
            return t == null ? place.yaw() : t.getRotation().yaw();
        } catch (Throwable t) {
            return place.yaw();
        }
    }

    // ---------- шаги катсцены (SkillSequence.Ext) ----------

    @Override
    public boolean step(String key, Map<String, Object> st, List<Ref<EntityStore>> on, double when) {
        switch (key) {
            case "bars", "letterbox" -> {
                Object v = st.get(key);
                hud.barsTarget = v instanceof Number n ? n.doubleValue() : (Json.bool(st, key, true) ? 1 : 0);
                hud.barsMs = (long) Json.num(st, "ms", 700);
            }
            case "fade" -> {
                Object v = st.get("fade");
                hud.fadeTarget = v instanceof Number n ? Math.max(0, Math.min(1, n.doubleValue())) : "out".equals(v) ? 1 : 0;
                hud.fadeMs = (long) Json.num(st, "ms", 800);
                fadeSetAt = System.currentTimeMillis();
            }
            case "flash" -> hud.flashUntil = System.currentTimeMillis() + (long) Json.num(st, "flash", 120);
            case "title" -> {
                hud.title = Json.str(st, "title", "");
                hud.titleSub = Json.str(st, "sub", "");
                hud.titleFrom = System.currentTimeMillis();
                hud.titleUntil = hud.titleFrom + (long) Json.num(st, "ms", 3500);
            }
            case "subtitle" -> {
                String text = Json.str(st, "subtitle", "");
                String who = Json.str(st, "who", "");
                hud.subWho = speaker(who);
                hud.subColor = Json.str(st, "color", color(who));
                hud.subText = text;
                double ms = Json.num(st, "ms", text.isEmpty() ? 0 : 1500 + 55.0 * text.length());
                hud.subUntil = text.isEmpty() ? 1 : System.currentTimeMillis() + (long) ms;
                voice(st);
            }
            case "say" -> say(Json.str(st, "say", ""), Json.str(st, "who", ""), st, when, List.of());
            case "choice" -> {
                List<Map<String, Object>> opts = new ArrayList<>();
                if (st.get("options") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) opts.add(Json.obj(m));
                if (opts.isEmpty()) {
                    seqMissing("choice без options");
                    return true;
                }
                say(Json.str(st, "choice", ""), Json.str(st, "who", ""), st, when, opts);
            }
            case "time" -> setHour(Json.num(st, "time", 12));
            case "weather" -> setWeather(Json.str(st, "weather", ""));
            case "music" -> {
                String id = Json.str(st, "music", "");
                if (id.isEmpty() || id.equals("stop")) BattleMusic.stop(plugin, store, me);
                else if (!BattleMusic.play(plugin, store, me, id)) seqMissing("музыка " + id);
                musicChanged = true;
            }
            case "hide", "show" -> {
                boolean show = key.equals("show");
                for (Ref<EntityStore> r : on.isEmpty() || st.get(key) instanceof String ? named(Json.str(st, key, "")) : on) {
                    Fx.setScale(store, r, show ? 1.0f : 0.01f);
                }
            }
            case "spawn" -> {
                Object v = st.get("spawn");
                if (v instanceof Map<?, ?> m) {
                    Map<String, Object> a = Json.obj(m);
                    spawnActor(Json.str(a, "id", "actor" + actors.size()), a);
                } else {
                    String id = String.valueOf(v);
                    Map<String, Object> a = new LinkedHashMap<>(st);
                    a.remove("spawn");
                    a.remove("t");
                    spawnActor(id, a);
                }
            }
            case "despawn" -> {
                for (String n : Json.str(st, "despawn", "").split(",")) {
                    Ref<EntityStore> r = actors.remove(n.trim());
                    if (r == null) continue;
                    plugin.mover().forget(r);
                    Fx.remove(store, r);
                }
            }
            case "hint" -> hud.hint = st.get("hint") instanceof String s ? s : (Json.bool(st, "hint", true) ? config.hint : "");
            case "player" -> {
                // "player": "show" — показать настоящего игрока (в конце сцены, если дальше не бой).
                if ("show".equals(st.get("player")) && playerScale != null) {
                    Fx.setScale(store, me, playerScale);
                    Fx.restorePlayerLook(store, me);
                    playerScale = null;
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public Boolean waited(String what, Map<String, Object> st, double now, double since) {
        return switch (what) {
            case "click" -> {
                if (st.get("_clicks") == null) st.put("_clicks", clicks);
                boolean ok = clicks > ((Number) st.get("_clicks")).longValue() || now - since > config.clickTimeoutMs;
                if (ok) st.remove("_clicks");
                yield ok;
            }
            case "dialog" -> !dialog;
            case "fade" -> System.currentTimeMillis() >= fadeSetAt + hud.fadeMs || now - since > 5000;
            case "title" -> System.currentTimeMillis() >= hud.titleUntil;
            case "subtitle" -> hud.subUntil <= 1 || System.currentTimeMillis() >= hud.subUntil;
            default -> null;
        };
    }

    @Override
    public boolean holds() {
        return dialog || skipAt >= 0;
    }

    @Override
    public void tick(double now) {
        double dt = lastTick == 0 ? 0 : now - lastTick;
        lastTick = now;
        for (Input in; (in = inputs.poll()) != null; ) handle(in, now);
        long real = System.currentTimeMillis();
        if (skipAt >= 0 && now >= skipAt) {
            skipAt = -1;
            dialog = false;
            hud.dialog = false;
            seq.end();
            return;
        }
        if (skipArmedUntil > 0 && real > skipArmedUntil) {
            skipArmedUntil = 0;
            hud.hint = config.hint;
        }
        if (dialog) {
            if (typing) {
                long letterMs = Math.max(5, plugin.settings().speech.letterMs);
                typed += dt / letterMs;
                int shown = (int) Math.min(hud.dlgText.length(), typed);
                var sp = plugin.settings().speech;
                for (int k = hud.dlgShown; k < shown; k++) {
                    char ch = hud.dlgText.charAt(k);
                    if (sp.soundEvery > 0 && !Character.isWhitespace(ch) && letterCount++ % sp.soundEvery == 0) {
                        TypeSound.play(player, sp.sound, sp.volume);
                    }
                }
                hud.dlgShown = shown;
                if (shown >= hud.dlgText.length()) lineTyped(now);
            } else if (!choosing && autoMs >= 0 && now - lineDoneAt >= autoMs) {
                closeLine();
            } else if (!choosing && now - lineStart > config.clickTimeoutMs + 60.0 * hud.dlgText.length()) {
                closeLine(); // никто не нажал — сцена не должна висеть вечно
            }
        }
        hud.frame(real);
    }

    private void handle(Input in, double now) {
        switch (in.key()) {
            case NEXT -> {
                clicks++;
                if (!dialog) return;
                if (typing) {
                    hud.dlgShown = hud.dlgText.length();
                    lineTyped(now);
                } else if (choosing) {
                    choose(hud.selected);
                } else {
                    closeLine();
                }
            }
            case WHEEL -> {
                if (choosing && !options.isEmpty()) {
                    hud.selected = Math.floorMod(hud.selected + in.arg(), options.size());
                    TypeSound.play(player, plugin.settings().speech.sound, plugin.settings().speech.volume);
                }
            }
            case PICK -> {
                if (choosing && in.arg() >= 0 && in.arg() < options.size()) {
                    if (typing) {
                        hud.dlgShown = hud.dlgText.length();
                        lineTyped(now);
                    }
                    hud.selected = in.arg();
                    choose(in.arg());
                }
            }
            case SKIP, SKIP_NOW -> {
                if (!def.skippable()) return;
                long real = System.currentTimeMillis();
                if (real < skipArmedUntil || in.key() == Key.SKIP_NOW) {
                    skipArmedUntil = 0;
                    hud.hint = "";
                    hud.fadeTarget = 1;
                    hud.fadeMs = 250;
                    skipAt = now + 320;
                } else {
                    skipArmedUntil = real + 2500;
                    hud.hint = "ПКМ ещё раз — пропустить сцену";
                }
            }
        }
    }

    // ---------- реплики ----------

    private void say(String text, String who, Map<String, Object> st, double when, List<Map<String, Object>> opts) {
        dialog = true;
        typing = true;
        typed = 0;
        lineStart = when;
        letterCount = 0;
        options = opts;
        choosing = false;
        autoMs = st.containsKey("auto") ? Json.num(st, "auto", config.autoHoldMs) : -1;
        hud.dialog = true;
        hud.dlgName = speaker(who);
        hud.dlgColor = Json.str(st, "color", color(who));
        hud.dlgText = text;
        hud.dlgShown = 0;
        hud.nextMark = false;
        hud.choices = List.of();
        hud.selected = 0;
        voice(st);
    }

    private void lineTyped(double now) {
        typing = false;
        lineDoneAt = now;
        if (!options.isEmpty()) {
            choosing = true;
            List<String> texts = new ArrayList<>();
            for (Map<String, Object> o : options) texts.add(Json.str(o, "text", "..."));
            hud.choices = texts;
            hud.selected = 0;
        } else {
            hud.nextMark = true;
        }
    }

    private void closeLine() {
        dialog = false;
        typing = false;
        choosing = false;
        hud.dialog = false;
        hud.nextMark = false;
        hud.choices = List.of();
    }

    private void choose(int i) {
        if (i < 0 || i >= options.size()) return;
        Map<String, Object> o = options.get(i);
        plugin.log("Катсцена " + def.id() + ": выбран ответ " + (i + 1) + " «" + Json.str(o, "text", "") + "»");
        String set = Json.str(o, "set", "");
        if (!set.isEmpty()) {
            seq.vars().add(set);
            // Выбор запоминается и после сцены: флаг игрока choice:<имя> (для будущих сцен и квестов).
            plugin.setFlag(player.getUuid(), "choice:" + set, true);
        }
        closeLine();
        String go = Json.str(o, "goto", "");
        if (!go.isEmpty()) seq.jump(go);
    }

    private void voice(Map<String, Object> st) {
        String v = Json.str(st, "voice", "");
        if (v.isEmpty()) return;
        int idx = SoundEvent.getAssetMap().getIndex(v);
        if (idx < 0) {
            seqMissing("звук " + v);
            return;
        }
        SoundUtil.playSoundEvent2dToPlayer(player, idx, SoundCategory.Voice, 1.0f, 1.0f);
    }

    /** Имя говорящего: актёр сцены (его name), иначе как написано. */
    private String speaker(String who) {
        Map<String, Object> d = actorDefs.get(who);
        return d != null ? Json.str(d, "name", who) : who;
    }

    private String color(String who) {
        Map<String, Object> d = actorDefs.get(who);
        return d != null ? Json.str(d, "color", "#e8c46a") : "#e8c46a";
    }

    private List<Ref<EntityStore>> named(String names) {
        List<Ref<EntityStore>> out = new ArrayList<>();
        for (String n : names.split(",")) {
            Ref<EntityStore> r = actors.get(n.trim());
            if (r != null) out.add(r);
        }
        return out;
    }

    private void seqMissing(String what) {
        if (seq != null) seq.missing().add(what);
    }

    // ---------- время суток и погода (только у этого игрока, как в Viewfinder) ----------

    private void setHour(double hour) {
        try {
            WorldTimeResource wt = store.getResource(WorldTimeResource.getResourceType());
            UpdateTimeSettings ts = WorldTimeResource.updateTimeSettingsPacket(new UpdateTimeSettings(), world);
            ts.timePaused = true;
            Instant day = wt.getGameTime().truncatedTo(ChronoUnit.DAYS);
            Instant at = day.plusNanos((long) (Math.floorMod((long) (hour * 3600e9), (long) 86400e9)));
            player.getPacketHandler().writeNoCache(ts);
            player.getPacketHandler().writeNoCache(new UpdateTime(WorldTimeResource.instantToInstantData(at)));
            timeChanged = true;
        } catch (Throwable t) {
            Fx.warnOnce("Время суток катсцены не ставится", t);
            seqMissing("время суток");
        }
    }

    private void setWeather(String id) {
        try {
            WeatherTracker wt = store.getComponent(me, WeatherTracker.getComponentType());
            if (wt == null) {
                seqMissing("погода (у игрока нет WeatherTracker)");
                return;
            }
            if (id.isEmpty()) {
                wt.clearOverrideWeatherIndex();
                return;
            }
            int idx = Weather.getAssetMap().getIndex(id);
            if (idx < 0) {
                plugin.log("Погода в игре: " + new java.util.TreeSet<>(Weather.getAssetMap().getAssetMap().keySet()));
                seqMissing("погода " + id + " (список в логе)");
                return;
            }
            wt.setOverrideWeatherIndex(idx);
            weatherChanged = true;
        } catch (Throwable t) {
            Fx.warnOnce("Погода катсцены не ставится", t);
            seqMissing("погода");
        }
    }

    // ---------- конец ----------

    /** Убрать всё за сценой и вернуть игрока (в потоке мира). runAfter — запустить то, что после (бой). */
    private void cleanup(boolean runAfter) {
        if (cleaned) return;
        cleaned = true;
        UUID uuid = player.getUuid();
        try {
            for (Ref<EntityStore> r : actors.values()) {
                plugin.mover().forget(r);
                Fx.remove(store, r);
            }
            actors.clear();
            if (camera != null) camera.reset();
            if (me.isValid()) {
                if (timeChanged) {
                    WorldTimeResource wt = store.getResource(WorldTimeResource.getResourceType());
                    player.getPacketHandler().writeNoCache(WorldTimeResource.updateTimeSettingsPacket(new UpdateTimeSettings(), world));
                    wt.sendTimePackets(player);
                }
                if (weatherChanged) {
                    WeatherTracker w = store.getComponent(me, WeatherTracker.getComponentType());
                    if (w != null) w.clearOverrideWeatherIndex();
                }
                if (musicChanged) BattleMusic.stop(plugin, store, me);
                if (addedFrozen) store.tryRemoveComponent(me, Frozen.getComponentType());
                if (addedInvulnerable) store.tryRemoveComponent(me, Invulnerable.getComponentType());
                if (playerScale != null) {
                    Fx.setScale(store, me, playerScale);
                    Fx.restorePlayerLook(store, me);
                }
                Player p = store.getComponent(me, Player.getComponentType());
                if (p != null) {
                    p.getHudManager().removeCustomHud(player, CutsceneHud.KEY);
                    p.getHudManager().resetVisibleHudComponents(player);
                }
            }
            plugin.followers().resume(uuid);
            plugin.setFlag(uuid, "cut:" + def.id(), true);
            // Игрок вышел посреди сцены — файл остаётся: при входе всё вернёт rescue.
            if (me.isValid()) deleteRescue();
        } catch (Throwable t) {
            plugin.warn("Уборка после катсцены " + def.id(), t);
        } finally {
            active.remove(uuid, this);
        }
        plugin.log("Катсцена " + def.id() + " закончена");
        if (runAfter && after != null && me.isValid()) {
            // Чуть позже: клиент должен успеть вернуть камеру и HUD.
            plugin.scheduler().schedule(() -> {
                try {
                    world.execute(() -> {
                        if (me.isValid()) after.run();
                    });
                } catch (Throwable t) {
                    plugin.warn("После катсцены " + def.id(), t);
                }
            }, 250, TimeUnit.MILLISECONDS);
        }
    }

    // ---------- файл спасения ----------

    private Path rescueFile() {
        return plugin.dataDir().resolve("players").resolve("cutscene-" + player.getUuid() + ".json");
    }

    private void writeRescue() {
        try {
            Vector3d p = Fx.position(store, me);
            Float sc = Fx.scale(store, me);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scene", def.id());
            m.put("x", p.x);
            m.put("y", p.y);
            m.put("z", p.z);
            m.put("scale", sc == null || sc < 0.2f ? 1.0 : sc.doubleValue());
            m.put("frozen", store.getComponent(me, Frozen.getComponentType()) == null);
            m.put("invulnerable", store.getComponent(me, Invulnerable.getComponentType()) == null);
            Files.createDirectories(rescueFile().getParent());
            Files.writeString(rescueFile(), Json.write(m));
        } catch (Throwable t) {
            Fx.warnOnce("Файл спасения катсцены не пишется", t);
        }
    }

    private void deleteRescue() {
        try {
            Files.deleteIfExists(rescueFile());
        } catch (Throwable ignored) {
            // не страшно
        }
    }

    /**
     * Игрок вошёл: если прошлая катсцена оборвалась (краш сервера), вернуть размер, вид, снять заморозку
     * и неуязвимость, которые ставила сцена, поставить туда, где стоял. Вызывать в потоке мира.
     */
    static void rescue(JrpgPlugin plugin, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store) {
        if (active.containsKey(playerRef.getUuid())) return;
        Path f = plugin.dataDir().resolve("players").resolve("cutscene-" + playerRef.getUuid() + ".json");
        if (!Files.exists(f)) return;
        try {
            Map<String, Object> m = Json.obj(Json.parse(Files.readString(f)));
            if (Json.bool(m, "frozen", false)) store.tryRemoveComponent(ref, Frozen.getComponentType());
            if (Json.bool(m, "invulnerable", false)) store.tryRemoveComponent(ref, Invulnerable.getComponentType());
            Fx.setScale(store, ref, (float) Json.num(m, "scale", 1));
            Fx.restorePlayerLook(store, ref);
            Fx.teleportPlayer(store, ref, new Vector3d(Json.num(m, "x", 0), Json.num(m, "y", 80), Json.num(m, "z", 0)), 0f);
            Files.deleteIfExists(f);
            plugin.log("Катсцена " + Json.str(m, "scene", "?") + " оборвалась у " + playerRef.getUsername() + " — игрок возвращён");
        } catch (Throwable t) {
            plugin.warn("Возврат после оборванной катсцены", t);
        }
    }
}
