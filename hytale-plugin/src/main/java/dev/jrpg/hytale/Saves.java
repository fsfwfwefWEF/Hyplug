package dev.jrpg.hytale;

import dev.jrpg.battle.SaveData;
import dev.jrpg.battle.WorldProgress;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Файлы сохранений в папке мода: players/&lt;uuid&gt;.json (прокачка партии, рюкзак, настройки игрока)
 * и world.json (побеждённые боссы, зачищенные встречи). Запись атомарная: сначала .tmp, потом замена,
 * чтобы падение сервера посреди записи не испортило сохранение.
 */
final class Saves {
    private final JrpgPlugin plugin;
    private final Path dir;

    Saves(JrpgPlugin plugin, Path dataDir) {
        this.plugin = plugin;
        this.dir = dataDir;
    }

    private Path playerFile(UUID id) { return dir.resolve("players").resolve(id + ".json"); }

    /** Сохранение игрока или null (новый игрок или файл повреждён — тогда он отложен в .broken). */
    SaveData.Player loadPlayer(UUID id) {
        Path f = playerFile(id);
        if (!Files.exists(f)) return null;
        try {
            return SaveData.readPlayer(Files.readString(f));
        } catch (Exception e) {
            plugin.warn("Сохранение " + f.getFileName() + " не читается, начинаю заново (старое: .broken)", e);
            try {
                Files.move(f, f.resolveSibling(f.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
                // не страшно: при следующем сохранении файл перезапишется
            }
            return null;
        }
    }

    void writePlayer(UUID id, String json) {
        write(playerFile(id), json);
    }

    void loadWorld(WorldProgress into) {
        Path f = dir.resolve("world.json");
        if (!Files.exists(f)) return;
        try {
            SaveData.readWorld(Files.readString(f), into);
        } catch (Exception e) {
            plugin.warn("world.json не читается, прогресс мира с нуля", e);
        }
    }

    void writeWorld(String json) {
        write(dir.resolve("world.json"), json);
    }

    private void write(Path f, String json) {
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            plugin.warn("Не удалось сохранить " + f, e);
        }
    }
}
