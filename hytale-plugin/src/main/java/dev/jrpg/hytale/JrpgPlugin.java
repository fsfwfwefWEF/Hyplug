package dev.jrpg.hytale;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import dev.jrpg.battle.Content;
import dev.jrpg.battle.GameData;
import dev.jrpg.battle.WorldProgress;

import javax.annotation.Nonnull;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Точка входа мода. Логика боя — в battle-core (пакет dev.jrpg.battle),
 * здесь связь с Hytale: команда /jrpg, боевые сессии, автостарт у встреч.
 */
public final class JrpgPlugin extends JavaPlugin {
    private static final String[] DATA_FILES = {"enemies.json", "encounters.json", "settings.json"};
    private static JrpgPlugin instance;

    private final Content content = Content.demo();
    private final WorldProgress progress = new WorldProgress();
    private final ConcurrentHashMap<UUID, BattleSession> sessions = new ConcurrentHashMap<>();
    /** Боевые предметы партии каждого игрока (пока в памяти; сохранение — следующий этап). */
    private final ConcurrentHashMap<UUID, java.util.Map<String, Integer>> inventories = new ConcurrentHashMap<>();
    /** Свой планировщик для пауз между ходами; всё, что трогает мир, переносится в world.execute. */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jrpg-scheduler");
        t.setDaemon(true);
        return t;
    });
    private GameData data;
    private Settings settings;

    public JrpgPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    @Override
    protected void setup() {
        reloadData();
        getCommandRegistry().registerCommand(new JrpgCommand(this));
        EncounterWatcher watcher = new EncounterWatcher(this);
        scheduler.scheduleAtFixedRate(watcher::tickSafe, 2, 1, TimeUnit.SECONDS);
        getLogger().at(Level.INFO).log("JRPG Battles: загружено встреч " + data.encounters().size());
    }

    @Override
    protected void shutdown() {
        for (BattleSession s : sessions.values()) s.abort("сервер останавливается");
        scheduler.shutdownNow();
    }

    /** Читает данные из папки мода (создаёт копии встроенных файлов при первом запуске). */
    public String reloadData() {
        Path dir = getDataDirectory();
        try {
            Files.createDirectories(dir);
            for (String f : DATA_FILES) {
                if (!Files.exists(dir.resolve(f))) Files.writeString(dir.resolve(f), GameData.builtin(f));
            }
            data = GameData.load(content, dir);
            settings = Settings.parse(Files.readString(dir.resolve("settings.json")));
            return "Данные загружены из " + dir.toAbsolutePath();
        } catch (Exception e) {
            getLogger().at(Level.SEVERE).log("JRPG: ошибка в файлах данных (" + dir + "): " + e.getMessage()
                    + ". Использую встроенные.");
            try {
                data = GameData.load(content, null);
                settings = Settings.parse(GameData.builtin("settings.json"));
            } catch (Exception fatal) {
                throw new IllegalStateException("Встроенные данные повреждены", fatal);
            }
            return "Ошибка в файлах: " + e.getMessage() + " (загружены встроенные)";
        }
    }

    public static JrpgPlugin get() { return instance; }
    public Content content() { return content; }
    public GameData data() { return data; }
    public Settings settings() { return settings; }
    public WorldProgress progress() { return progress; }
    public ConcurrentHashMap<UUID, BattleSession> sessions() { return sessions; }
    public ScheduledExecutorService scheduler() { return scheduler; }

    /** После боя автостарт встреч не срабатывает 30 с (иначе после бегства бой начнётся снова). */
    private final ConcurrentHashMap<UUID, Long> lastBattleEnd = new ConcurrentHashMap<>();

    public void markBattleEnded(UUID player) { lastBattleEnd.put(player, System.currentTimeMillis()); }

    public boolean onCooldown(UUID player) {
        Long t = lastBattleEnd.get(player);
        return t != null && System.currentTimeMillis() - t < 30_000;
    }

    public java.util.Map<String, Integer> inventoryOf(UUID player) {
        return inventories.computeIfAbsent(player, k -> Content.demoInventory());
    }

    public void log(String msg) {
        getLogger().at(Level.INFO).log("[JRPG] " + msg);
    }

    public void warn(String msg, Throwable t) {
        getLogger().at(Level.WARNING).log("[JRPG] " + msg + (t == null ? "" : ": " + t));
    }
}
