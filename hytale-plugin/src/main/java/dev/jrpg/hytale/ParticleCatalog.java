package dev.jrpg.hytale;

import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Частицы для эффектов боя, подобранные по словам из имён систем частиц, которые есть в игре
 * (имена ассетов заранее неизвестны, поэтому мод ищет их сам при первом бое и пишет выбор в лог).
 * Любой выбор можно заменить в settings.json → "particles" (ключи как в {@link #KEYWORDS}),
 * пустая строка там — выключить эффект. Берутся только короткие системы (живут до 4 с), чтобы
 * случайно не поставить вечный эмиттер вроде костра, и не берутся служебные/чужие (Example_, Warning,
 * Prefire, Signature…). Для обычных ударов оружием («physical») сам ничего не подбирает: в 0.7.0 это
 * давало «ледяную» вспышку на каждом ударе мечом. Частица ударов — только если задать её в settings.json.
 */
final class ParticleCatalog {
    static final Map<String, List<String>> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put("physical", List.of("Impact", "Hit", "Slash", "Strike", "Swing", "Damage"));
        KEYWORDS.put("fire", List.of("Fire", "Flame", "Burn", "Ember", "Explosion"));
        KEYWORDS.put("ice", List.of("Ice", "Frost", "Freeze", "Snow"));
        KEYWORDS.put("thunder", List.of("Lightning", "Thunder", "Electric", "Shock", "Spark"));
        KEYWORDS.put("water", List.of("Splash", "Water", "Bubble"));
        KEYWORDS.put("earth", List.of("Rock", "Earth", "Debris", "Stone", "Dust"));
        KEYWORDS.put("poison", List.of("Poison", "Toxic", "Venom", "Acid"));
        KEYWORDS.put("heal", List.of("Heal", "Regen", "Restore", "Life", "Holy"));
        KEYWORDS.put("cast", List.of("Magic", "Spell", "Cast", "Rune", "Charge", "Arcane"));
        KEYWORDS.put("buff", List.of("Buff", "Level", "Power", "Sparkle", "Glow"));
        KEYWORDS.put("loot", List.of("Sparkle", "Glint", "Twinkle", "Shine", "Star", "Pickup", "Coin"));
        KEYWORDS.put("dodge", List.of("Dust", "Puff", "Smoke", "Cloud", "Dash"));
        KEYWORDS.put("parry", List.of("Spark", "Clash", "Metal", "Block", "Impact"));
        KEYWORDS.put("death", List.of("Poof", "Death", "Soul", "Smoke", "Despawn"));
    }

    /** Слова, по которым система частиц похожа на постоянный эмиттер — такие не берём. */
    private static final List<String> LOOPING = List.of("loop", "ambient", "idle", "trail", "weather", "rain",
            "torch", "campfire", "portal", "aura", "beam", "constant", "persistent", "fog", "fireflies", "spawner");
    /** Служебные и «чужие» системы (примеры, предупреждения, подготовка выстрела, приёмы оружия). */
    private static final List<String> FOREIGN = List.of("example", "test", "debug", "warning", "prefire",
            "signature", "_dev", "editor");
    /** Слова разовых вспышек: такие системы берём, даже если срок жизни не задан. */
    private static final List<String> BURST = List.of("explosion", "impact", "hit", "burst", "splash", "implosion",
            "spark", "poof", "shatter", "break", "smash", "slam", "strike");
    /** Эффекты, которые подбираются только вручную (settings.json → particles). */
    private static final List<String> MANUAL = List.of("physical");

    private static volatile Map<String, String> picks;

    private ParticleCatalog() {}

    /** Все id систем частиц в игре (пусто, если ассеты ещё не загружены). */
    static List<String> ids() {
        try {
            return new ArrayList<>(ParticleSystem.getAssetMap().getAssetMap().keySet());
        } catch (Throwable t) {
            Fx.warnOnce("Список частиц не прочитать", t);
            return List.of();
        }
    }

    /** Частица для эффекта key ("fire", "physical", "loot"…): из settings.json или подобранная. "" — нет. */
    static String get(String key) {
        Map<String, String> over = JrpgPlugin.get().settings().fx;
        if (over.containsKey(key)) return over.get(key);
        Map<String, String> p = picks;
        if (p == null) p = pickAll();
        return p.getOrDefault(key, "");
    }

    /** Частица под стихию способности; для стихии без своей частицы — никакой (не «лёд» на всё). */
    static String element(String element) {
        return element == null ? "" : get(element);
    }

    /** Сбросить выбор (после /jrpg reload). */
    static void reset() { picks = null; }

    static String summary() {
        StringBuilder sb = new StringBuilder();
        for (String k : KEYWORDS.keySet()) {
            String v = get(k);
            sb.append(k).append("=").append(v.isEmpty() ? "—" : v).append("  ");
        }
        return sb.toString().trim();
    }

    private static synchronized Map<String, String> pickAll() {
        if (picks != null) return picks;
        Map<String, ParticleSystem> all;
        try {
            all = ParticleSystem.getAssetMap().getAssetMap();
        } catch (Throwable t) {
            Fx.warnOnce("Список частиц не прочитать", t);
            return Map.of();
        }
        if (all.isEmpty()) return Map.of(); // ассеты ещё не загружены — попробуем в следующий раз
        List<String> usable = new ArrayList<>();
        all.forEach((id, ps) -> {
            String low = id.toLowerCase(Locale.ROOT);
            if (LOOPING.stream().anyMatch(low::contains) || FOREIGN.stream().anyMatch(low::contains)) return;
            float life = ps == null ? 0 : ps.getLifeSpan();
            // 0 — срок не задан: берём, если по имени похоже на разовую вспышку.
            boolean burst = BURST.stream().anyMatch(low::contains);
            if ((life > 0 && life <= 4) || (life <= 0 && burst)) usable.add(id);
        });
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : KEYWORDS.entrySet()) {
            if (MANUAL.contains(e.getKey())) {
                out.put(e.getKey(), "");
                continue;
            }
            String best = null;
            for (String word : e.getValue()) {
                String w = word.toLowerCase(Locale.ROOT);
                for (String id : usable) {
                    if (id.toLowerCase(Locale.ROOT).contains(w) && (best == null || id.length() < best.length())) best = id;
                }
                if (best != null) break;
            }
            out.put(e.getKey(), best == null ? "" : best);
        }
        picks = out;
        List<String> sorted = new ArrayList<>(all.keySet());
        java.util.Collections.sort(sorted);
        JrpgPlugin.get().log("Все системы частиц (" + sorted.size() + "): " + sorted);
        JrpgPlugin.get().log("Частицы боя (из " + all.size() + ", коротких " + usable.size() + "): " + out
                + ". Поменять: settings.json → particles, список: /jrpg particles <слово>");
        return out;
    }
}
