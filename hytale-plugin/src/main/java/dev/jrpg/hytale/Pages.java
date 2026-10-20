package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPage;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Открытие, обновление и закрытие наших окон так, чтобы сервер не начал игнорировать кнопки.
 *
 * PageManager считает пакеты окна, на которые клиент ещё не ответил (customPageRequiredAcknowledgments),
 * и пока счётчик не ноль, молча выбрасывает все нажатия. В 0.6 счётчик залипал: окно закрывалось
 * (SetPage None) и тут же открывалось другое, или обновление уходило уже закрытому окну, — клиент
 * на такое не отвечает, и кнопки умирали до перезахода. Поэтому:
 *  - одно окно сменяет другое сразу через openCustomPage, без закрытия между ними;
 *  - обновления и закрытие уходят только окну, которое сейчас открыто;
 *  - перед открытием окна «с нуля» залипший счётчик сбрасывается (если наших пакетов давно не было).
 * Всё вызывать в потоке мира игрока.
 */
final class Pages {
    /** Сколько ждать ответа клиента, прежде чем считать счётчик залипшим. */
    private static final long STALE_MS = 400;
    private static final long RETRY_MS = 60;

    private static final Map<UUID, Long> lastTraffic = new ConcurrentHashMap<>();
    private static volatile Field acksField;
    private static volatile boolean acksFieldFailed;

    private Pages() {}

    static PageManager manager(Store<EntityStore> store, Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) return null;
        Player p = store.getComponent(ref, Player.getComponentType());
        return p == null ? null : p.getPageManager();
    }

    static PageManager manager(PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        return ref == null ? null : manager(ref.getStore(), ref);
    }

    /** Открыть окно (заменяет текущее, если оно есть). */
    static void open(JrpgPlugin plugin, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                     CustomUIPage page) {
        open(plugin, playerRef, ref, store, page, null, null);
    }

    /**
     * Открыть окно. Если окна сейчас нет, а сервер ещё ждёт ответа клиента на прошлые пакеты (например,
     * окно только что закрыли), открытие откладывается, пока ответ не придёт; не пришёл за STALE_MS —
     * счётчик сбрасывается. wanted — нужно ли ещё окно к моменту отложенного открытия; dropped — если
     * открытие отменилось (игрок ушёл, окно уже не нужно или за это время открылось другое).
     */
    static void open(JrpgPlugin plugin, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                     CustomUIPage page, BooleanSupplier wanted, Runnable dropped) {
        PageManager pm = JrpgPageManager.install(plugin, playerRef, ref, store);
        if (pm == null || (wanted != null && !wanted.getAsBoolean())) {
            if (dropped != null) dropped.run();
            return;
        }
        if (pm.getCustomPage() == null && !acksSettled(plugin, playerRef, pm)) {
            World world = store.getExternalData().getWorld();
            plugin.scheduler().schedule(() -> world.execute(() -> {
                PageManager now = manager(store, ref);
                if (now == null || now.getCustomPage() != null) {
                    if (dropped != null) dropped.run();
                    return;
                }
                open(plugin, playerRef, ref, store, page, wanted, dropped);
            }), RETRY_MS, TimeUnit.MILLISECONDS);
            return;
        }
        touch(playerRef);
        pm.openCustomPage(ref, store, page);
    }

    /** Обновить окно, только если оно сейчас открыто у игрока. Синхронно, без отложенной отправки. */
    static boolean update(CustomUIPage page, PlayerRef playerRef, UICommandBuilder cmd) {
        PageManager pm = manager(playerRef);
        if (pm == null || pm.getCustomPage() != page) return false;
        touch(playerRef);
        pm.updateLegacyCustomPage(new CustomPage(page.getClass().getName(), false, false, page.getLifetime(),
                cmd.getCommands(), UIEventBuilder.EMPTY_EVENT_BINDING_ARRAY));
        return true;
    }

    /** Закрыть окно, только если открыто именно оно (чужое окно не трогаем). */
    static boolean close(CustomUIPage page, PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        PageManager pm = manager(playerRef);
        if (pm == null || pm.getCustomPage() != page) return false;
        touch(playerRef);
        pm.closePage(ref, ref.getStore());
        return true;
    }

    static boolean isOpen(CustomUIPage page, PlayerRef playerRef) {
        PageManager pm = manager(playerRef);
        return pm != null && pm.getCustomPage() == page;
    }

    static void forget(UUID player) {
        lastTraffic.remove(player);
    }

    private static void touch(PlayerRef playerRef) {
        lastTraffic.put(playerRef.getUuid(), System.currentTimeMillis());
    }

    /**
     * Можно ли открывать окно с нуля: сервер ни одного ответа не ждёт, или наши пакеты ушли давно и ответа
     * на них уже не будет — тогда счётчик сбрасывается. false — подождать ещё.
     */
    private static boolean acksSettled(JrpgPlugin plugin, PlayerRef playerRef, PageManager pm) {
        int pending = pendingAcks(pm);
        if (pending == 0) return true;
        Long last = lastTraffic.get(playerRef.getUuid());
        if (last != null && System.currentTimeMillis() - last < STALE_MS) return false;
        // pending == -1: счётчик не прочитать — сброс всё равно безопасен, раз окна нет и пакетов давно не было.
        pm.clearLegacyCustomPageAcknowledgements();
        if (pending > 0) plugin.log("Сброшено " + pending + " неподтверждённых пакетов окна у "
                + playerRef.getUsername() + " (иначе кнопки не нажимались бы)");
        return true;
    }

    /** Значение счётчика неподтверждённых пакетов; -1, если прочитать не вышло. */
    static int pendingAcks(PageManager pm) {
        if (acksFieldFailed) return -1;
        try {
            Field f = acksField;
            if (f == null) {
                f = PageManager.class.getDeclaredField("customPageRequiredAcknowledgments");
                f.setAccessible(true);
                acksField = f;
            }
            return ((AtomicInteger) f.get(pm)).get();
        } catch (Throwable t) {
            acksFieldFailed = true;
            return -1;
        }
    }
}
