package dev.jrpg.hytale;

import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.cosmetics.PlayerSkinPart;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Человекоподобный вид компаньона: модель игрока Hytale со своим «скином» (как /npc spawn --randomModel).
 * На такой модели видны одежда и (позже) броня и оружие, и у неё те же анимации, что у игрока, —
 * ванильные замахи мечом, копьём, топором подходят ей как есть.
 *
 * Вид в settings.json → companionLooks: "male" / "female" — скин подбирается сам (всегда один и тот же
 * для компаньона: случайность с постоянным зерном), или строка JSON скина (как в файлах игроков) — тогда он.
 */
final class HumanLook {
    record Look(Model model, PlayerSkin skin) {}

    private static final Map<String, Look> cache = new ConcurrentHashMap<>();

    private HumanLook() {}

    /** Вид для компаньона who по описанию spec; null — описания нет или модель не собрать (останется роль). */
    static Look of(String who, String spec) {
        if (spec == null || spec.isBlank()) return null;
        return cache.computeIfAbsent(who + "|" + spec, k -> build(who, spec.trim()));
    }

    private static Look build(String who, String spec) {
        try {
            CosmeticsModule cm = CosmeticsModule.get();
            if (cm == null) return null;
            PlayerSkin skin;
            if (spec.startsWith("{")) {
                skin = cm.parseSkinFromJson(spec);
            } else {
                skin = pick(cm, who, spec.equalsIgnoreCase("female"));
            }
            if (skin == null) return null;
            Model model = cm.createModel(skin);
            if (model == null) return null;
            JrpgPlugin.get().log("Вид компаньона " + who + " (" + (spec.startsWith("{") ? "свой скин" : spec) + "): "
                    + describe(skin));
            return new Look(model, skin);
        } catch (Throwable t) {
            Fx.warnOnce("Не удалось собрать человеческую модель для " + who, t);
            return null;
        }
    }

    /**
     * Случайный скин с постоянным зерном, похожий на мужчину (борода, короткая/средняя стрижка) или
     * женщину (без бороды, длинные волосы). Из 300 попыток берётся лучшая по этим признакам.
     */
    private static PlayerSkin pick(CosmeticsModule cm, String who, boolean female) {
        Random rnd = new Random(("jrpg:" + who).hashCode());
        PlayerSkin best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < 300; i++) {
            PlayerSkin s = cm.generateRandomSkin(rnd);
            if (s == null) continue;
            int score = score(cm, s, female);
            if (score > bestScore) {
                best = s;
                bestScore = score;
            }
            if (score >= 4 && s.bodyCharacteristic != null && s.bodyCharacteristic.startsWith("Default")) break;
        }
        return best;
    }

    private static int score(CosmeticsModule cm, PlayerSkin s, boolean female) {
        boolean beard = s.facialHair != null && !s.facialHair.isBlank();
        PlayerSkinPart.HaircutType hair = hairType(cm, s.haircut);
        int score = 0;
        // Только человеческое тело («Default.…»): среди тел есть и гоблинское (в 0.7.3 Брам стал гоблином).
        if (s.bodyCharacteristic == null || !s.bodyCharacteristic.startsWith("Default")) score -= 50;
        if (s.face != null && s.face.toLowerCase(java.util.Locale.ROOT).contains("makeup") && !female) score -= 2;
        if (female) {
            score += beard ? -10 : 2;
            if (hair == PlayerSkinPart.HaircutType.Long) score += 2;
            else if (hair == PlayerSkinPart.HaircutType.Medium) score += 1;
        } else {
            score += beard ? 2 : 0;
            if (hair == PlayerSkinPart.HaircutType.Short) score += 2;
            else if (hair == PlayerSkinPart.HaircutType.Medium) score += 1;
            else if (hair == PlayerSkinPart.HaircutType.Long) score -= 1;
        }
        return score;
    }

    private static PlayerSkinPart.HaircutType hairType(CosmeticsModule cm, String haircut) {
        if (haircut == null || haircut.isBlank()) return null;
        try {
            String id = haircut.split("\\.")[0];
            PlayerSkinPart part = cm.getRegistry().getHaircuts().get(id);
            return part == null ? null : part.getHairType();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String describe(PlayerSkin s) {
        return "тело " + s.bodyCharacteristic + ", лицо " + s.face + ", волосы " + s.haircut + ", борода " + s.facialHair
                + ", верх " + s.overtop + "/" + s.undertop + ", низ " + s.pants + ", обувь " + s.shoes;
    }
}
