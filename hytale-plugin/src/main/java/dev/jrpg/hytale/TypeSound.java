package dev.jrpg.hytale;

import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Звук «печати» реплик: короткий щелчок интерфейса на каждую букву окна над головой.
 * Звук берётся из settings.json → speech.sound, иначе подбирается сам среди звуков игры по словам
 * (Typewriter, Type, Text, Blip, Tick, Click…; только UI). Выбор и список кандидатов пишутся в лог.
 */
final class TypeSound {
    private static final List<String> WORDS = List.of("typewriter", "type", "text", "dialog", "blip", "tick", "click",
            "hover", "select", "tap", "button");
    private static final List<String> BAD = List.of("music", "ambient", "amb_", "loop", "explosion", "death", "_vo_",
            "voice", "craft", "open", "close", "error", "deny", "fail");

    private static volatile String picked;
    private static volatile boolean resolved;

    private TypeSound() {}

    static void play(PlayerRef player, String configured, float volume) {
        String id = resolve(configured);
        if (id == null || player == null) return;
        try {
            SoundUtil.playSoundEvent2dToPlayer(player, SoundEvent.getAssetMap().getIndex(id), SoundCategory.UI, volume, 1.0f);
        } catch (Throwable t) {
            Fx.warnOnce("Звук печати реплик не играет (" + id + ")", t);
        }
    }

    private static String resolve(String configured) {
        if (configured != null && !configured.isBlank()) return configured;
        if (resolved) return picked;
        resolved = true;
        try {
            List<String> ui = new ArrayList<>();
            for (String id : SoundEvent.getAssetMap().getAssetMap().keySet()) {
                String low = id.toLowerCase(Locale.ROOT);
                if (low.contains("ui") && BAD.stream().noneMatch(low::contains)) ui.add(id);
            }
            String best = null;
            for (String w : WORDS) {
                for (String id : new TreeSet<>(ui)) {
                    if (id.toLowerCase(Locale.ROOT).contains(w)) {
                        best = id;
                        break;
                    }
                }
                if (best != null) break;
            }
            picked = best;
            JrpgPlugin.get().log("Звук печати реплик: " + (best == null ? "не найден" : best)
                    + " (свой: settings.json → speech.sound). Звуки интерфейса: "
                    + new TreeSet<>(ui).stream().limit(60).toList());
        } catch (Throwable t) {
            Fx.warnOnce("Список звуков не прочитать", t);
        }
        return picked;
    }
}
