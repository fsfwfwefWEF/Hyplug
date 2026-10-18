package dev.jrpg.hytale;

import com.hypixel.hytale.builtin.audio.components.ForcedMusicTracker;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.musiccontainer.config.MusicContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jrpg.battle.Encounter;
import dev.jrpg.battle.GameData;
import dev.jrpg.battle.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Музыка боя: на время боя игроку включается «принудительная» музыка (как /audio music force),
 * после боя выключается, и снова играет музыка мира.
 *
 * Какой трек — решает music.json в папке мода (читается заново перед каждым боем, /jrpg reload не нужен).
 * Трек — это ассет MusicContainer из мода: Server/Audio/MusicContainers/<id>.json со ссылкой на
 * Common/Music/...ogg (Ogg Vorbis, 44100 или 48000 Гц). Новые ассеты подхватываются только при запуске сервера.
 * Всё вызывать в потоке мира.
 */
final class BattleMusic {
    static final String FILE = "music.json";

    private BattleMusic() {}

    /** Включить музыку боя. Возвращает id трека или null, если своей музыки у этого боя нет. */
    static String start(JrpgPlugin plugin, Encounter encounter, Store<EntityStore> store, Ref<EntityStore> player) {
        String id = pick(plugin, encounter);
        if (id == null || id.isEmpty()) return null;
        return play(plugin, store, player, id) ? id : null;
    }

    static void stop(JrpgPlugin plugin, Store<EntityStore> store, Ref<EntityStore> player) {
        set(plugin, store, player, 0);
    }

    /** Включить трек по id (для проверки командой). false — такого трека нет. */
    static boolean play(JrpgPlugin plugin, Store<EntityStore> store, Ref<EntityStore> player, String id) {
        int index = MusicContainer.getAssetMap().getIndex(id);
        if (index <= 0 || MusicContainer.getAssetMap().getAsset(id) == null) {
            plugin.warn("Нет музыки «" + id + "» (проверь Server/Audio/MusicContainers и music.json)", null);
            return false;
        }
        return set(plugin, store, player, index);
    }

    private static boolean set(JrpgPlugin plugin, Store<EntityStore> store, Ref<EntityStore> player, int index) {
        try {
            if (player == null || !player.isValid()) return false;
            ForcedMusicTracker tracker = store.getComponent(player, ForcedMusicTracker.getComponentType());
            if (tracker == null) return false;
            tracker.setCurrentContainerIndex(index);
            return true;
        } catch (Throwable t) {
            plugin.warn("Не удалось переключить музыку", t);
            return false;
        }
    }

    /** encounters[id встречи или id отряда] → bosses (unique) → squads (случайные отряды) → default. */
    static String pick(JrpgPlugin plugin, Encounter encounter) {
        Map<String, Object> cfg = config(plugin);
        if (cfg == null) return null;
        Map<String, Object> byId = cfg.get("encounters") instanceof Map<?, ?> m ? Json.obj(m) : Map.of();
        String id = encounter.id();
        boolean squad = id.startsWith(SquadSpawner.ID_PREFIX);
        // У отряда id вида squad:<номер>:<id из squads.json> — в music.json пишется просто id отряда.
        String key = squad ? id.substring(id.lastIndexOf(':') + 1) : id;
        if (byId.get(key) instanceof String s) return s;
        String group = squad ? Json.str(cfg, "squads", "") : encounter.unique() ? Json.str(cfg, "bosses", "") : "";
        return group.isEmpty() ? Json.str(cfg, "default", "") : group;
    }

    private static Map<String, Object> config(JrpgPlugin plugin) {
        Path file = plugin.getDataDirectory().resolve(FILE);
        try {
            if (!Files.exists(file)) Files.writeString(file, GameData.builtin(FILE));
            return Json.obj(Json.parse(Files.readString(file)));
        } catch (Exception e) {
            plugin.warn("Ошибка в " + file + ", музыка боя выключена", e);
            return null;
        }
    }
}
