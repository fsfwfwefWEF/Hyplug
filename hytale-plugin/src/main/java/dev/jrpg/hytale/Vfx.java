package dev.jrpg.hytale;

import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.server.core.modules.debug.DebugUtils;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Эффекты способностей из фигур клиента (DebugUtils: шар, конус, цилиндр, диск, бублик, линия) — они точно
 * рисуются (кольцо выбора цели сделано так же), в отличие от частиц, которые подбираются по именам наугад.
 * Каждая фигура живёт доли секунды и гаснет сама (FLAG_FADE); движение — новая фигура каждые {@link #STEP_MS} мс.
 *
 * Виды: meteor (горящий шар падает с неба, взрыв и ударная волна), fireball (шар летит от бойца), flame (языки огня),
 * ice (ледяные шипы из земли), lightning (зигзаг молнии с неба), water (брызги и волна), earth (каменные шипы и
 * волна), poison (зелёные пузыри), acid (кислотный дождь), heal (кольца света вверх), buff (золотые кольца),
 * haste (голубые кольца), guard (прозрачный щит-пузырь), taunt (красное кольцо), slash (белые разрезы),
 * shadow (фиолетовые разрезы), cast (сияние у колдующего).
 */
final class Vfx {
    static final long STEP_MS = 50;
    private static final int FLAGS = DebugUtils.FLAG_FADE | DebugUtils.FLAG_NO_WIREFRAME;
    private static final Random RND = new Random();

    static final Vector3f FIRE = new Vector3f(1.0f, 0.45f, 0.08f), EMBER = new Vector3f(1.0f, 0.18f, 0.05f),
            GOLD = new Vector3f(1.0f, 0.84f, 0.35f), ICE = new Vector3f(0.55f, 0.9f, 1.0f),
            BOLT = new Vector3f(1.0f, 0.97f, 0.55f), WATER = new Vector3f(0.25f, 0.55f, 1.0f),
            EARTH = new Vector3f(0.55f, 0.38f, 0.2f), POISON = new Vector3f(0.45f, 0.95f, 0.25f),
            HEAL = new Vector3f(0.5f, 1.0f, 0.6f), SHIELD = new Vector3f(0.45f, 0.7f, 1.0f),
            RED = new Vector3f(1.0f, 0.25f, 0.25f), WHITE = new Vector3f(1f, 1f, 1f),
            SHADOW = new Vector3f(0.6f, 0.3f, 1.0f), HASTE = new Vector3f(0.4f, 1.0f, 0.95f);

    private Vfx() {}

    /**
     * Эффект способности: сначала по id (особые), потом по смыслу — урон по стихии, лечение, усиление, защита.
     * Пустая строка — без эффекта. melee — удар вблизи (огненный разрез не летит снарядом).
     */
    static String kindFor(dev.jrpg.battle.Ability ab, String element, boolean melee) {
        if (ab == null) return "";
        String id = ab.id().startsWith("item_") ? ab.id().substring(5) : ab.id();
        switch (id) {
            case "meteor": return "meteor";
            case "acid_rain": return "acid";
            case "shadow_strike", "execute", "bleeding_cut", "rend", "pounce": return "shadow";
            case "provoke", "bulwark", "frenzy_roar", "war_cry": return id.equals("war_cry") || id.equals("frenzy_roar") ? "buff" : "taunt";
            case "haste_spell", "haste_all", "howl": return "haste";
            case "earthquake", "earth_spike", "war_stomp", "ground_slam", "sunder": return "earth";
            default: break;
        }
        if (ab.damagePower() > 0) {
            if (element == null) element = "";
            return switch (element) {
                case "fire" -> melee ? "flame" : "fireball";
                case "ice" -> "ice";
                case "thunder" -> "lightning";
                case "water" -> melee ? "water" : "water";
                case "earth" -> "earth";
                case "poison" -> "poison";
                default -> "slash";
            };
        }
        if (ab.healPower() > 0 || ab.mpRestore() > 0 || !ab.cures().isEmpty()) return "heal";
        for (dev.jrpg.battle.StatusApply sa : ab.applies()) {
            switch (sa.statusId()) {
                case "guard", "brood_shield": return "guard";
                case "taunt": return "taunt";
                case "haste": return "haste";
                default: break;
            }
        }
        return ab.applies().isEmpty() ? "" : "buff";
    }

    /** Через сколько мс после начала эффекта он «попадает» (урон и цифры показывать тогда). */
    static long leadMs(String kind) {
        return switch (kind) {
            case "meteor" -> 900;
            case "fireball", "water" -> 450;
            case "lightning" -> 250;
            default -> 0;
        };
    }

    /**
     * Сыграть эффект kind по цели at (ноги цели); from — откуда (ноги бойца) для снарядов.
     * big — удар по всем или крит: эффект крупнее.
     */
    static void play(JrpgPlugin plugin, World world, String kind, Vector3d from, Vector3d at, boolean big) {
        if (world == null || at == null || kind == null || kind.isEmpty()) return;
        double s = big ? 1.3 : 1.0;
        switch (kind) {
            case "meteor" -> meteor(plugin, world, at, s);
            case "fireball" -> projectile(plugin, world, from, at, FIRE, 0.55, () -> burst(plugin, world, at, FIRE, EMBER, s));
            case "flame" -> burst(plugin, world, at, FIRE, EMBER, s);
            case "ice" -> spikes(plugin, world, at, ICE, s, 6);
            case "lightning" -> lightning(plugin, world, at, s);
            case "water" -> projectile(plugin, world, from, at, WATER, 0.45, () -> splash(plugin, world, at, WATER, s));
            case "earth" -> {
                spikes(plugin, world, at, EARTH, s, 5);
                waves(plugin, world, at, EARTH, 2.2 * s);
            }
            case "poison" -> bubbles(plugin, world, at, POISON, s);
            case "acid" -> rain(plugin, world, at, POISON, s);
            case "heal" -> rings(plugin, world, at, HEAL, 4, s);
            case "buff" -> rings(plugin, world, at, GOLD, 3, s);
            case "haste" -> rings(plugin, world, at, HASTE, 3, s);
            case "guard" -> bubble(world, at, SHIELD, s);
            case "taunt" -> {
                donut(world, at.x, at.y + 0.08, at.z, 1.1 * s, 0.85 * s, RED, 0.9f, 1.2f);
                rings(plugin, world, at, RED, 2, s);
            }
            case "slash" -> slash(plugin, world, at, WHITE, s);
            case "shadow" -> {
                slash(plugin, world, at, SHADOW, s * 1.2);
                bubbles(plugin, world, at, RED, 0.6);
            }
            case "cast" -> rings(plugin, world, at, GOLD, 2, 0.8);
            default -> { }
        }
    }

    // ---------- эффекты ----------

    private static void meteor(JrpgPlugin plugin, World world, Vector3d at, double s) {
        // Падает наискосок с высоты ~10 блоков за 800 мс, оставляя огненный след.
        Vector3d start = new Vector3d(at.x + 3.5, at.y + 10, at.z - 2.5);
        int steps = 16;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double e = t * t; // разгон
            Vector3d p = new Vector3d(start.x + (at.x - start.x) * e, start.y + (at.y + 0.6 - start.y) * e,
                    start.z + (at.z - start.z) * e);
            later(plugin, world, i * STEP_MS, () -> {
                sphere(world, p, FIRE, 0.95f, 1.3 * s, 0.12f);
                sphere(world, p, EMBER, 0.6f, 0.8 * s, 0.45f);            // след
                sphere(world, jitter(p, 0.5), GOLD, 0.8f, 0.3 * s, 0.35f);
            });
        }
        long boom = (steps + 1) * STEP_MS;
        later(plugin, world, boom, () -> {
            sphere(world, new Vector3d(at.x, at.y + 0.8, at.z), GOLD, 0.9f, 3.0 * s, 0.25f);
            sphere(world, new Vector3d(at.x, at.y + 0.8, at.z), FIRE, 0.7f, 2.2 * s, 0.6f);
        });
        later(plugin, world, boom, () -> waves(plugin, world, at, FIRE, 3.2 * s));
        later(plugin, world, boom, () -> burst(plugin, world, at, FIRE, EMBER, s * 1.2));
    }

    private static void projectile(JrpgPlugin plugin, World world, Vector3d from, Vector3d at, Vector3f color,
                                   double size, Runnable onHit) {
        if (from == null) {
            onHit.run();
            return;
        }
        int steps = 8;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            Vector3d p = new Vector3d(from.x + (at.x - from.x) * t, from.y + 1.2 + Math.sin(t * Math.PI) * 0.8,
                    from.z + (at.z - from.z) * t);
            later(plugin, world, i * STEP_MS, () -> {
                sphere(world, p, color, 0.95f, size, 0.1f);
                sphere(world, p, color, 0.45f, size * 0.7, 0.3f);
            });
        }
        later(plugin, world, (steps + 1) * STEP_MS, onHit);
    }

    private static void burst(JrpgPlugin plugin, World world, Vector3d at, Vector3f a, Vector3f b, double s) {
        for (int k = 0; k < 7; k++) {
            int kk = k;
            later(plugin, world, k * 40L, () -> {
                Vector3d p = jitter(new Vector3d(at.x, at.y + 0.4 + kk * 0.18, at.z), 0.45 * s);
                sphere(world, p, kk % 2 == 0 ? a : b, 0.85f, (0.6 + RND.nextDouble() * 0.4) * s, 0.4f);
            });
        }
    }

    private static void spikes(JrpgPlugin plugin, World world, Vector3d at, Vector3f color, double s, int n) {
        for (int k = 0; k < n; k++) {
            double ang = Math.PI * 2 * k / n + RND.nextDouble() * 0.4;
            double r = (0.5 + RND.nextDouble() * 0.5) * s;
            double x = at.x + Math.cos(ang) * r, z = at.z + Math.sin(ang) * r;
            double h = (1.0 + RND.nextDouble() * 0.8) * s;
            long delay = k * 30L;
            // Шип растёт из земли за три шага.
            for (int g = 1; g <= 3; g++) {
                double hh = h * g / 3.0;
                later(plugin, world, delay + g * STEP_MS, () -> cone(world, x, at.y + hh / 2, z, 0.35 * s, hh, color, 0.9f,
                        hh >= h ? 0.9f : 0.12f));
            }
        }
        donut(world, at.x, at.y + 0.05, at.z, 1.3 * s, 0.2, color, 0.5f, 0.8f);
    }

    private static void lightning(JrpgPlugin plugin, World world, Vector3d at, double s) {
        for (int bolt = 0; bolt < 2; bolt++) {
            long delay = bolt * 120L;
            later(plugin, world, delay, () -> {
                Vector3d prev = new Vector3d(at.x + (RND.nextDouble() - 0.5), at.y + 9, at.z + (RND.nextDouble() - 0.5));
                int segs = 7;
                for (int i = 1; i <= segs; i++) {
                    double t = i / (double) segs;
                    Vector3d p = i == segs ? new Vector3d(at.x, at.y + 1.0, at.z)
                            : new Vector3d(at.x + (RND.nextDouble() - 0.5) * 1.4, prev.y - 8.0 / segs, at.z + (RND.nextDouble() - 0.5) * 1.4);
                    line(world, prev, p, BOLT, 0.12 * s, 0.95f, 0.35f);
                    line(world, prev, p, WHITE, 0.05 * s, 1.0f, 0.2f);
                    prev = p;
                }
                sphere(world, new Vector3d(at.x, at.y + 1.0, at.z), BOLT, 0.8f, 1.8 * s, 0.2f);
            });
        }
    }

    private static void splash(JrpgPlugin plugin, World world, Vector3d at, Vector3f color, double s) {
        for (int k = 0; k < 8; k++) {
            double ang = Math.PI * 2 * k / 8;
            for (int g = 0; g < 4; g++) {
                double t = g / 3.0;
                Vector3d p = new Vector3d(at.x + Math.cos(ang) * t * 1.2 * s, at.y + 0.5 + Math.sin(t * Math.PI) * 0.9,
                        at.z + Math.sin(ang) * t * 1.2 * s);
                later(plugin, world, g * STEP_MS, () -> sphere(world, p, color, 0.85f, 0.3 * s, 0.15f));
            }
        }
        waves(plugin, world, at, color, 1.8 * s);
    }

    private static void waves(JrpgPlugin plugin, World world, Vector3d at, Vector3f color, double maxR) {
        int steps = 6;
        for (int i = 1; i <= steps; i++) {
            double r = maxR * i / steps;
            later(plugin, world, i * STEP_MS, () -> donut(world, at.x, at.y + 0.08, at.z, r, Math.max(0, r - 0.35), color, 0.85f, 0.15f));
        }
    }

    private static void bubbles(JrpgPlugin plugin, World world, Vector3d at, Vector3f color, double s) {
        for (int k = 0; k < 8; k++) {
            Vector3d base = jitter(new Vector3d(at.x, at.y + 0.3, at.z), 0.5 * s);
            double size = (0.2 + RND.nextDouble() * 0.25) * s;
            long delay = k * 45L;
            for (int g = 0; g < 5; g++) {
                Vector3d p = new Vector3d(base.x, base.y + g * 0.3, base.z);
                later(plugin, world, delay + g * STEP_MS, () -> sphere(world, p, color, 0.8f, size, 0.12f));
            }
        }
    }

    private static void rain(JrpgPlugin plugin, World world, Vector3d at, Vector3f color, double s) {
        for (int k = 0; k < 14; k++) {
            Vector3d base = jitter(new Vector3d(at.x, at.y + 5, at.z), 1.4 * s);
            long delay = k * 35L;
            for (int g = 0; g < 6; g++) {
                Vector3d p = new Vector3d(base.x, base.y - g * 0.9, base.z);
                later(plugin, world, delay + g * STEP_MS, () -> sphere(world, p, color, 0.8f, 0.18, 0.1f));
            }
        }
        later(plugin, world, 300, () -> bubbles(plugin, world, at, color, s));
    }

    private static void rings(JrpgPlugin plugin, World world, Vector3d at, Vector3f color, int n, double s) {
        for (int k = 0; k < n; k++) {
            long delay = k * 140L;
            for (int g = 0; g < 8; g++) {
                double y = at.y + 0.1 + g * 0.28;
                double r = (0.9 - g * 0.06) * s;
                later(plugin, world, delay + g * STEP_MS, () -> donut(world, at.x, y, at.z, r, r - 0.12, color, 0.85f, 0.12f));
            }
        }
        for (int k = 0; k < 6; k++) {
            Vector3d p = jitter(new Vector3d(at.x, at.y + 1.0, at.z), 0.6 * s);
            later(plugin, world, k * 60L, () -> sphere(world, p, color, 0.9f, 0.15, 0.5f));
        }
    }

    private static void bubble(World world, Vector3d at, Vector3f color, double s) {
        sphere(world, new Vector3d(at.x, at.y + 1.0, at.z), color, 0.3f, 2.4 * s, 1.0f);
        donut(world, at.x, at.y + 0.06, at.z, 1.25 * s, 1.0 * s, color, 0.8f, 1.0f);
    }

    private static void slash(JrpgPlugin plugin, World world, Vector3d at, Vector3f color, double s) {
        double cy = at.y + 1.1;
        double[][] cuts = {{-0.8, 0.7, 0.8, -0.6}, {0.8, 0.6, -0.7, -0.7}};
        for (int c = 0; c < cuts.length; c++) {
            double[] k = cuts[c];
            // Разрез «прорисовывается» за 3 шага, перпендикулярно к оси X (виден сбоку и спереди камеры).
            for (int g = 1; g <= 3; g++) {
                double t = g / 3.0;
                Vector3d a = new Vector3d(at.x + k[0] * s, cy + k[1] * s, at.z - 0.3);
                Vector3d b = new Vector3d(at.x + (k[0] + (k[2] - k[0]) * t) * s, cy + (k[1] + (k[3] - k[1]) * t) * s, at.z - 0.3);
                later(plugin, world, c * 90L + g * 30L, () -> line(world, a, b, color, 0.08 * s, 0.95f, 0.18f));
            }
        }
    }

    // ---------- фигуры ----------

    static void sphere(World world, Vector3d p, Vector3f color, float opacity, double size, float time) {
        try {
            DebugUtils.add(world, DebugShape.Sphere, new Matrix4d().translate(p.x, p.y, p.z).scale(size), color, opacity, time, FLAGS);
        } catch (Throwable t) {
            Fx.warnOnce("Эффект (шар) не рисуется", t);
        }
    }

    static void cone(World world, double x, double y, double z, double width, double height, Vector3f color,
                     float opacity, float time) {
        try {
            DebugUtils.add(world, DebugShape.Cone, new Matrix4d().translate(x, y, z).scale(width, height, width), color, opacity, time, FLAGS);
        } catch (Throwable t) {
            Fx.warnOnce("Эффект (конус) не рисуется", t);
        }
    }

    static void donut(World world, double x, double y, double z, double outer, double inner, Vector3f color,
                      float opacity, float time) {
        try {
            DebugUtils.addDonut(world, x, y, z, outer, Math.max(0, inner), 0.05, 0.0, Math.PI * 2, color, opacity, time, FLAGS);
        } catch (Throwable t) {
            Fx.warnOnce("Эффект (кольцо) не рисуется", t);
        }
    }

    static void line(World world, Vector3d a, Vector3d b, Vector3f color, double thickness, float opacity, float time) {
        try {
            // Отрезок = вытянутый цилиндр от a до b (addLine рисует только тонкую линию без прозрачности).
            Vector3d d = new Vector3d(b).sub(a);
            double len = d.length();
            if (len < 1e-4) return;
            Vector3d mid = new Vector3d(a).add(b).mul(0.5);
            Matrix4d m = new Matrix4d().translate(mid.x, mid.y, mid.z)
                    .rotateTowards(d.x / len, d.y / len, d.z / len, 0, 1, 0.0001)
                    .rotateX(Math.PI / 2)
                    .scale(thickness, len, thickness);
            DebugUtils.add(world, DebugShape.Cylinder, m, color, opacity, time, FLAGS);
        } catch (Throwable t) {
            Fx.warnOnce("Эффект (линия) не рисуется", t);
        }
    }

    private static Vector3d jitter(Vector3d p, double r) {
        return new Vector3d(p.x + (RND.nextDouble() - 0.5) * 2 * r, p.y + (RND.nextDouble() - 0.5) * r, p.z + (RND.nextDouble() - 0.5) * 2 * r);
    }

    private static void later(JrpgPlugin plugin, World world, long ms, Runnable r) {
        Runnable safe = () -> {
            try {
                r.run();
            } catch (Throwable t) {
                Fx.warnOnce("Эффект способности сломался", t);
            }
        };
        if (ms <= 0) {
            safe.run();
            return;
        }
        plugin.scheduler().schedule(() -> {
            try {
                world.execute(safe);
            } catch (Throwable ignored) {
                // мир выгружен
            }
        }, ms, TimeUnit.MILLISECONDS);
    }
}
