package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager;
import com.hypixel.hytale.server.core.entity.entities.player.windows.WindowManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Клики без потерь (приём из мода Replay). Обычный PageManager выбрасывает нажатия, пока клиент не подтвердил
 * все пакеты окна, а каждое наше обновление (анимации, печать текста) добавляет ещё одно ожидание. Этот
 * менеджер на нажатие сбрасывает счётчик и передаёт его окну, а подтверждение просто обнуляет счётчик.
 *
 * Ставится игроку (поле Player.pageManager) при открытии нашего окна, когда никакого окна нет; снимается при
 * выходе игрока и выключении плагина. Выключатель: settings.json → ui.fastClicks.
 */
final class JrpgPageManager extends PageManager {
    private static final Map<UUID, PageManager> originals = new ConcurrentHashMap<>();
    private static final Map<UUID, Player> players = new ConcurrentHashMap<>();
    private static volatile Field field;
    private static volatile boolean failed;

    JrpgPageManager(PlayerRef playerRef, WindowManager windows) {
        init(playerRef, windows);
    }

    @Override
    public void handleLegacyCustomPageEvent(Ref<EntityStore> ref, Store<EntityStore> store, CustomPageEvent e) {
        switch (e.type) {
            case Data -> {
                clearLegacyCustomPageAcknowledgements();
                super.handleLegacyCustomPageEvent(ref, store, e);
            }
            case Acknowledge -> clearLegacyCustomPageAcknowledgements();
            default -> super.handleLegacyCustomPageEvent(ref, store, e);
        }
    }

    private static Field field() throws ReflectiveOperationException {
        Field f = field;
        if (f == null) {
            f = Player.class.getDeclaredField("pageManager");
            f.setAccessible(true);
            field = f;
        }
        return f;
    }

    /** Поставить игроку наш менеджер (поток мира). Только когда окна нет. Возвращает текущий менеджер. */
    static PageManager install(JrpgPlugin plugin, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store) {
        Player p = ref == null || !ref.isValid() ? null : store.getComponent(ref, Player.getComponentType());
        if (p == null) return null;
        PageManager pm = p.getPageManager();
        if (pm instanceof JrpgPageManager || failed || !plugin.settings().uiFastClicks) return pm;
        if (pm == null || pm.getCustomPage() != null || pm.getActiveServersidePage() != null) return pm;
        try {
            JrpgPageManager mine = new JrpgPageManager(playerRef, p.getWindowManager());
            field().set(p, mine);
            originals.put(playerRef.getUuid(), pm);
            players.put(playerRef.getUuid(), p);
            plugin.log("Клики без потерь включены для " + playerRef.getUsername());
            return mine;
        } catch (Throwable t) {
            failed = true;
            plugin.warn("Не удалось поставить менеджер окон для кликов без потерь (работает ClickBuffer)", t);
            return pm;
        }
    }

    /** Вернуть игроку обычный менеджер (выход игрока). */
    static void restore(UUID id) {
        PageManager orig = originals.remove(id);
        Player p = players.remove(id);
        if (orig == null || p == null) return;
        try {
            if (p.getPageManager() instanceof JrpgPageManager) field().set(p, orig);
        } catch (Throwable ignored) {
            // игрок уже выгружен
        }
    }

    /** Выключение плагина: вернуть всем. */
    static void restoreAll() {
        for (UUID id : java.util.List.copyOf(originals.keySet())) restore(id);
    }
}
