package dev.jrpg.hytale;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import dev.jrpg.battle.Content;
import dev.jrpg.battle.GameData;
import dev.jrpg.battle.Progression;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
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
    /**
     * Версия встроенных файлов данных. Если копия в папке мода старее, она сохраняется в backup-vN/
     * и заменяется новой (иначе новые враги/настройки не появятся у тех, кто уже запускал мод).
     */
    private static final int DATA_VERSION = 3;
    private static JrpgPlugin instance;

    private final Content content = Content.demo();
    private final WorldProgress progress = new WorldProgress();
    private final ConcurrentHashMap<UUID, BattleSession> sessions = new ConcurrentHashMap<>();
    /** Боевые предметы партии каждого игрока (пока в памяти; сохранение — следующий этап). */
    private final ConcurrentHashMap<UUID, java.util.Map<String, Integer>> inventories = new ConcurrentHashMap<>();
    /** Прокачка партии каждого игрока (пока в памяти, до перезапуска сервера). */
    private final ConcurrentHashMap<UUID, Progression.Party> parties = new ConcurrentHashMap<>();
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
            upgradeDataFiles(dir);
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

    private void upgradeDataFiles(Path dir) throws java.io.IOException {
        Path ver = dir.resolve("data-version.txt");
        int have = 1;
        if (Files.exists(ver)) {
            try {
                have = Integer.parseInt(Files.readString(ver).trim());
            } catch (NumberFormatException ignored) {
                have = 1;
            }
        }
        if (have >= DATA_VERSION) return;
        boolean any = false;
        for (String f : DATA_FILES) any |= Files.exists(dir.resolve(f));
        if (any) {
            Path backup = dir.resolve("backup-v" + have);
            Files.createDirectories(backup);
            for (String f : DATA_FILES) {
                if (Files.exists(dir.resolve(f))) {
                    Files.move(dir.resolve(f), backup.resolve(f), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            getLogger().at(Level.INFO).log("[JRPG] Файлы данных обновлены до версии " + DATA_VERSION
                    + ", старые сохранены в " + backup);
        }
        Files.writeString(ver, String.valueOf(DATA_VERSION));
    }

    public Progression.Party partyOf(UUID player) {
        return parties.computeIfAbsent(player, k -> new Progression.Party(content));
    }

    /** Открыть меню прокачки (вызывать в потоке мира). heroId — какая вкладка открыта (null — первая). */
    public void openProgression(PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store, String heroId) {
        Player p = store.getComponent(ref, Player.getComponentType());
        if (p == null) return;
        p.getPageManager().openCustomPage(ref, store, new ProgressionPage(playerRef, this, partyOf(playerRef.getUuid()), heroId));
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
