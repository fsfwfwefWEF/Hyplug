package dev.jrpg.hytale;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import dev.jrpg.battle.Battle;
import dev.jrpg.battle.Content;
import dev.jrpg.battle.GameData;
import dev.jrpg.battle.Progression;
import dev.jrpg.battle.SaveData;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
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
    private static final String[] DATA_FILES = {"enemies.json", "encounters.json", "settings.json", "squads.json", "equipment.json", "gems.json"};
    /**
     * Версия встроенных файлов данных. Если копия в папке мода старее, она сохраняется в backup-vN/
     * и заменяется новой (иначе новые враги/настройки не появятся у тех, кто уже запускал мод).
     */
    private static final int DATA_VERSION = 17;
    private static final String STARTER_GEAR_FLAG = "starterGear";
    /** Автосохранение загруженных игроков, с. */
    private static final long AUTOSAVE_S = 60;
    private static JrpgPlugin instance;

    private final Content content = Content.demo();
    private final WorldProgress progress = new WorldProgress();
    private final ConcurrentHashMap<UUID, BattleSession> sessions = new ConcurrentHashMap<>();
    /** Рюкзак каждого игрока (зелья, боевые предметы, добыча). Сохраняется в players/<uuid>.json. */
    private final ConcurrentHashMap<UUID, java.util.Map<String, Integer>> inventories = new ConcurrentHashMap<>();
    /** Прокачка партии каждого игрока. Сохраняется туда же. */
    private final ConcurrentHashMap<UUID, Progression.Party> parties = new ConcurrentHashMap<>();
    /** Мелкие настройки игрока (follow — компаньоны ходят следом). */
    private final ConcurrentHashMap<UUID, java.util.Map<String, Object>> flags = new ConcurrentHashMap<>();
    private Saves saves;
    private final CompanionFollowers followers = new CompanionFollowers(this);
    private final NpcMover mover = new NpcMover(this);
    private final GameMenuKey menuKey = new GameMenuKey(this);
    private final ClickBuffer clickBuffer = new ClickBuffer(this);
    /** Свой планировщик для пауз между ходами; всё, что трогает мир, переносится в world.execute. */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jrpg-scheduler");
        t.setDaemon(true);
        return t;
    });
    private GameData data;
    private Settings settings;
    private final SquadSpawner squads = new SquadSpawner(this);
    /** У кого сейчас открыто окно «Сражаться / Сбежать» (чтобы не открывать его каждую секунду). */
    private final java.util.Set<UUID> prompting = ConcurrentHashMap.newKeySet();
    /** Удачные побеги: игрок+встреча → до какого времени встреча его не трогает. */
    private final ConcurrentHashMap<String, Long> dodged = new ConcurrentHashMap<>();

    public JrpgPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    @Override
    protected void setup() {
        reloadData();
        saves = new Saves(this, getDataDirectory());
        saves.loadWorld(progress);
        smiths = new Smiths(this);
        getCommandRegistry().registerCommand(new JrpgCommand(this));
        EncounterWatcher watcher = new EncounterWatcher(this);
        scheduler.scheduleAtFixedRate(watcher::tickSafe, 2, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::autosave, AUTOSAVE_S, AUTOSAVE_S, TimeUnit.SECONDS);
        followers.start();
        mover.start();
        menuKey.start();
        clickBuffer.start();
        // Катсцены: клики без окна (ЛКМ дальше, ПКМ пропуск, колесо — выбор) и возврат игрока после краша.
        getEntityStoreRegistry().registerSystem(new CutsceneInput());
        getEventRegistry().registerGlobal(com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent.class, e -> {
            try {
                Ref<EntityStore> r = e.getPlayerRef();
                if (r == null || !r.isValid()) return;
                Store<EntityStore> st = r.getStore();
                PlayerRef pr = st.getComponent(r, PlayerRef.getComponentType());
                if (pr != null) st.getExternalData().getWorld().execute(() -> Cutscene.rescue(this, pr, r, st));
            } catch (Throwable t) {
                warn("Проверка оборванной катсцены", t);
            }
        });
        getEventRegistry().register(PlayerDisconnectEvent.class, e -> onDisconnect(e.getPlayerRef()));
        // Мышь в мире во время боя (камера боя включает курсор и отправку движения мыши): наведение и клик по врагу.
        getEventRegistry().register(com.hypixel.hytale.server.core.event.events.player.PlayerMouseMotionEvent.class, e -> {
            BattleSession s = sessionOf(e.getPlayerRef());
            if (s != null) s.onWorld(() -> s.onWorldHover(e.getTargetEntityRef()));
        });
        getEventRegistry().register(com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent.class, e -> {
            PlayerRef cp = e.getPlayerRefComponent();
            if (cp != null && Cutscene.playing(cp.getUuid()) && e.getMouseButton() != null) {
                e.setCancelled(true);
                var mb = e.getMouseButton();
                if (mb.state == com.hypixel.hytale.protocol.MouseButtonState.Released) {
                    if (mb.mouseButtonType == com.hypixel.hytale.protocol.MouseButtonType.Left) Cutscene.input(cp.getUuid(), Cutscene.Key.NEXT, 0);
                    else if (mb.mouseButtonType == com.hypixel.hytale.protocol.MouseButtonType.Right) Cutscene.input(cp.getUuid(), Cutscene.Key.SKIP, 0);
                }
                return;
            }
            BattleSession s = sessionOf(e.getPlayerRef());
            if (s == null || e.getMouseButton() == null) return;
            // В бою клики не бьют оружием по миру, а выбирают цель.
            e.setCancelled(true);
            if (e.getMouseButton().mouseButtonType == com.hypixel.hytale.protocol.MouseButtonType.Left
                    && e.getMouseButton().state == com.hypixel.hytale.protocol.MouseButtonState.Released) {
                s.onWorld(() -> s.onWorldClick(e.getTargetEntityRef()));
            }
        });
        getLogger().at(Level.INFO).log("JRPG Battles: загружено встреч " + data.encounters().size());
    }

    @Override
    protected void shutdown() {
        menuKey.stop();
        clickBuffer.stop();
        JrpgPageManager.restoreAll();
        Cutscene.cancelAll();
        for (BattleSession s : sessions.values()) s.abort("сервер останавливается");
        clearSquads();
        followers.clearAll();
        if (smiths != null) smiths.despawnAll();
        for (UUID id : java.util.List.copyOf(parties.keySet())) savePlayerNow(id);
        saveWorld();
        scheduler.shutdownNow();
    }

    /** Игрок вышел: сохранить и выгрузить, убрать его бой и компаньонов. */
    private void onDisconnect(PlayerRef ref) {
        UUID id = ref.getUuid();
        try {
            BattleSession s = sessions.get(id);
            if (s != null) s.abort("игрок вышел");
            followers.forget(id);
            Sequences.cancel(id);
            Cutscene.cancel(id);
            savePlayerNow(id);
            JrpgPageManager.restore(id);
        } catch (Throwable t) {
            warn("Ошибка при выходе игрока", t);
        } finally {
            parties.remove(id);
            inventories.remove(id);
            flags.remove(id);
            prompting.remove(id);
            Pages.forget(id);
        }
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
            data.setDifficulty(settings.difficulty);
            ParticleCatalog.reset();
            GearVisuals.reset();
            DragItems.reset();
            return "Данные загружены из " + dir.toAbsolutePath();
        } catch (Exception e) {
            getLogger().at(Level.SEVERE).log("JRPG: ошибка в файлах данных (" + dir + "): " + e.getMessage()
                    + ". Использую встроенные.");
            try {
                data = GameData.load(content, null);
                settings = Settings.parse(GameData.builtin("settings.json"));
                data.setDifficulty(settings.difficulty);
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
        load(player);
        return parties.get(player);
    }

    /** Средний уровень героев игрока: по нему растут враги (GameData.Difficulty). */
    public int partyLevel(UUID player) {
        return (int) Math.round(partyOf(player).heroes().stream().mapToInt(h -> h.level).average().orElse(1));
    }

    /** При первом обращении — прочитать сохранение игрока (или начать новое). */
    private synchronized void load(UUID player) {
        if (parties.containsKey(player)) return;
        Progression.Party party = new Progression.Party(content);
        java.util.Map<String, Integer> inv;
        java.util.Map<String, Object> fl = new ConcurrentHashMap<>();
        SaveData.Player save = saves == null ? null : saves.loadPlayer(player);
        if (save != null) {
            SaveData.applyParty(save, party);
            inv = new java.util.LinkedHashMap<>(SaveData.inventory(save, content));
            save.flags().forEach((k, v) -> { if (v != null) fl.put(k, v); });
            log("Загружено сохранение игрока " + player);
        } else {
            inv = new java.util.LinkedHashMap<>(Content.demoInventory());
        }
        // Запасные вещи снаряжения кладутся один раз (и в старые сохранения): есть что сравнить и надеть.
        if (!(fl.get(STARTER_GEAR_FLAG) instanceof Boolean b && b)) {
            content.starterBag().forEach((k, v) -> inv.merge(k, v, Integer::sum));
            fl.put(STARTER_GEAR_FLAG, true);
        }
        inventories.put(player, java.util.Collections.synchronizedMap(inv));
        flags.put(player, fl);
        parties.put(player, party);
    }

    public boolean flag(UUID player, String key, boolean def) {
        load(player);
        return flags.get(player).get(key) instanceof Boolean b ? b : def;
    }

    /** Свой порядок вещей в рюкзаке (перетаскиванием в меню героя): id по порядку; пусто — по видам. */
    public java.util.List<String> bagOrder(UUID player) {
        load(player);
        java.util.List<String> out = new java.util.ArrayList<>();
        if (flags.get(player).get("bagOrder") instanceof java.util.List<?> l) for (Object o : l) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    public void setBagOrder(UUID player, java.util.List<String> order) {
        load(player);
        flags.get(player).put("bagOrder", new java.util.ArrayList<>(order));
        savePlayer(player);
    }

    public void setFlag(UUID player, String key, boolean value) {
        load(player);
        flags.get(player).put(key, value);
        savePlayer(player);
    }

    /** Сохранить игрока: JSON собирается сразу (в потоке мира), файл пишется в фоне. */
    public void savePlayer(UUID player) {
        String json = playerJson(player);
        if (json != null) scheduler.execute(() -> saves.writePlayer(player, json));
    }

    private void savePlayerNow(UUID player) {
        String json = playerJson(player);
        if (json != null) saves.writePlayer(player, json);
    }

    private String playerJson(UUID player) {
        Progression.Party party = parties.get(player);
        if (party == null || saves == null) return null;
        java.util.Map<String, Integer> inv = inventories.get(player);
        synchronized (inv) {
            return SaveData.writePlayer(party, new java.util.LinkedHashMap<>(inv), new java.util.LinkedHashMap<>(flags.get(player)));
        }
    }

    public void saveWorld() {
        if (saves != null) saves.writeWorld(SaveData.writeWorld(progress));
    }

    private void autosave() {
        for (UUID id : java.util.List.copyOf(parties.keySet())) {
            try {
                savePlayerNow(id);
            } catch (Throwable t) {
                // Партию как раз меняли в потоке мира — сохранится в следующий раз.
            }
        }
    }

    /**
     * Показать окно перед боем (вызывать в потоке мира). Если окно выключено в настройках — бой сразу.
     */
    public void openPrompt(PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store, PreBattlePage.Setup setup) {
        if (!settings.preBattlePrompt) {
            if (setup.beforeBattle() != null) setup.beforeBattle().run();
            String err = BattleSession.start(this, setup.encounter(), playerRef, ref, store,
                    store.getExternalData().getWorld(), setup.center(), Battle.Opening.NORMAL, setup.dir());
            if (err != null) warn("Автостарт " + setup.encounter().id() + ": " + err, null);
            return;
        }
        Player p = store.getComponent(ref, Player.getComponentType());
        if (p == null || !prompting.add(playerRef.getUuid())) return;
        try {
            Pages.open(this, playerRef, ref, store, new PreBattlePage(playerRef, this, setup), null,
                    () -> prompting.remove(playerRef.getUuid()));
        } catch (Throwable t) {
            prompting.remove(playerRef.getUuid());
            warn("Окно перед боем не открылось", t);
        }
    }

    public boolean isPrompting(UUID player) { return prompting.contains(player); }
    public void endPrompt(UUID player) { prompting.remove(player); }

    /** После удачного побега встреча/отряд не трогает игрока 40 секунд. */
    public void dodge(UUID player, String encounterId) {
        dodged.put(player + "|" + encounterId, System.currentTimeMillis() + 40_000);
    }

    public boolean dodged(UUID player, String encounterId) {
        Long until = dodged.get(player + "|" + encounterId);
        return until != null && until > System.currentTimeMillis();
    }

    SquadSpawner squads() { return squads; }

    /** Убрать отряды всех игроков (перезагрузка данных, выключение). */
    void clearSquads() {
        for (UUID id : squads.players()) {
            for (SquadSpawner.Squad sq : squads.squadsOf(id)) {
                Ref<EntityStore> any = sq.npcs.stream().filter(Ref::isValid).findFirst().orElse(null);
                if (any == null) {
                    squads.remove(id, sq, null);
                    continue;
                }
                Store<EntityStore> st = any.getStore();
                try {
                    st.getExternalData().getWorld().execute(() -> squads.remove(id, sq, st));
                } catch (Throwable t) {
                    warn("Не удалось убрать отряд", t);
                }
            }
        }
    }

    /** Открыть меню прокачки (вызывать в потоке мира). heroId — какая вкладка открыта (null — первая). */
    public void openProgression(PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store, String heroId) {
        Pages.open(this, playerRef, ref, store, new ProgressionPage(playerRef, this, partyOf(playerRef.getUuid()), heroId));
    }

    /** Бой, в котором участвует эта сущность игрока (null — не в бою). */
    private BattleSession sessionOf(Ref<EntityStore> playerEntity) {
        if (playerEntity == null) return null;
        for (BattleSession s : sessions.values()) if (!s.isClosed() && s.isPlayer(playerEntity)) return s;
        return null;
    }

    public static JrpgPlugin get() { return instance; }
    public Content content() { return content; }
    public GameData data() { return data; }
    public Settings settings() { return settings; }

    /** Папка мода с файлами данных и сохранениями. */
    Path dataDir() { return getDataDirectory(); }
    public WorldProgress progress() { return progress; }
    public ConcurrentHashMap<UUID, BattleSession> sessions() { return sessions; }
    public ScheduledExecutorService scheduler() { return scheduler; }

    /** После боя автостарт встреч не срабатывает settings.retrySeconds (иначе после бегства бой начнётся снова). */
    private final ConcurrentHashMap<UUID, Long> lastBattleEnd = new ConcurrentHashMap<>();

    public void markBattleEnded(UUID player) { lastBattleEnd.put(player, System.currentTimeMillis()); }

    public boolean onCooldown(UUID player) {
        Long t = lastBattleEnd.get(player);
        return t != null && System.currentTimeMillis() - t < settings.retrySeconds * 1000L;
    }

    public java.util.Map<String, Integer> inventoryOf(UUID player) {
        load(player);
        return inventories.get(player);
    }

    CompanionFollowers followers() { return followers; }

    private volatile Smiths smiths;

    Smiths smiths() { return smiths; }
    NpcMover mover() { return mover; }

    GameMenuKey menuKey() { return menuKey; }

    /** Эта сущность — кукла чьего-то идущего боя (её не трогают уборка компаньонов и т.п.). */
    boolean isBattlePuppet(Ref<EntityStore> ref) {
        for (BattleSession s : sessions.values()) if (s.owns(ref)) return true;
        return false;
    }

    /** Открыть экипировку (вызывать в потоке мира). heroId — какая вкладка открыта (null — первая). */
    public void openEquipment(PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store, String heroId) {
        Pages.open(this, playerRef, ref, store, new HeroPage(playerRef, this, heroId));
    }

    /** Открыть рюкзак (вызывать в потоке мира). */
    public void openBackpack(PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store) {
        Pages.open(this, playerRef, ref, store, new BackpackPage(playerRef, this));
    }

    public void log(String msg) {
        getLogger().at(Level.INFO).log("[JRPG] " + msg);
    }

    public void warn(String msg, Throwable t) {
        getLogger().at(Level.WARNING).log("[JRPG] " + msg + (t == null ? "" : ": " + t));
    }
}
