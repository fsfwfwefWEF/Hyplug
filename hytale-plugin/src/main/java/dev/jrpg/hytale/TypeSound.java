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
 * (Typewriter, Dialog, Text, Blip, Tick, Click, Button…, без зацикленных и шумов стройки). Выбор и список кандидатов пишутся в лог.
 */
final class TypeSound {
    private static final List<String> WORDS = List.of("typewriter", "dialog", "text", "blip", "tick", "click", "button",
            "tap", "pop", "select", "hover", "page", "book", "attn_extremelyquiet", "attn_veryquiet");
    private static final List<String> BAD = List.of("music", "ambient", "amb_", "loop", "explosion", "death", "_vo_",
            "voice", "craft", "error", "deny", "fail", "build", "emit", "fluid", "flamethrower", "hurt", "run",
            "pursuit", "alerted", "bubbling");
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
            List<String> all = new ArrayList<>();
            for (String id : SoundEvent.getAssetMap().getAssetMap().keySet()) {
                String low = id.toLowerCase(Locale.ROOT);
                if (BAD.stream().noneMatch(low::contains)) all.add(id);
            }
            String best = null;
            List<String> seen = new ArrayList<>();
            for (String w : WORDS) {
                for (String id : new TreeSet<>(all)) {
                    if (!id.toLowerCase(Locale.ROOT).contains(w)) continue;
                    if (seen.size() < 80) seen.add(id);
                    if (best == null) best = id;
                }
            }
            picked = best;
            JrpgPlugin.get().log("Звук печати реплик: " + (best == null ? "не найден" : best)
                    + " (свой: settings.json → speech.sound). Похожие звуки: " + seen);
        } catch (Throwable t) {
            Fx.warnOnce("Список звуков не прочитать", t);
        }
        return picked;
    }
}
