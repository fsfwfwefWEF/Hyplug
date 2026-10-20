package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageEvent;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageEventType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Клики во время анимаций окна (бой, сетка навыков).
 *
 * Каждый кадр анимации — пакет окну, и пока клиент на него не ответил, PageManager молча выбрасывает
 * нажатия (Pages). Чтобы кнопки не «залипали» в анимациях, нажатие на окно боя, пришедшее, пока сервер
 * ждёт ответа на кадр, перехватывается и передаётся окну напрямую в потоке мира. Только для открытого
 * окна боя, которое сейчас анимируется и открыто не только что (иначе клик мог относиться к прошлому окну).
 */
final class ClickBuffer {
    private final JrpgPlugin plugin;
    private PacketFilter filter;
    private volatile boolean logged;

    ClickBuffer(JrpgPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        try {
            filter = PacketAdapters.registerInbound((PlayerPacketFilter) this::onPacket);
        } catch (Throwable t) {
            plugin.warn("Перехват кликов окна не подключился", t);
        }
    }

    void stop() {
        if (filter != null) PacketAdapters.deregisterInbound(filter);
        filter = null;
    }

    /** Поток сети. true — пакет съеден (передан окну сами). */
    private boolean onPacket(PlayerRef playerRef, Packet packet) {
        if (!(packet instanceof CustomPageEvent e) || e.type != CustomPageEventType.Data || e.data == null) return false;
        try {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) return false;
            Store<EntityStore> store = ref.getStore();
            PageManager pm = Pages.manager(store, ref);
            if (pm == null || !(pm.getCustomPage() instanceof AnimatedPage anim)) return false;
            boolean all = anim.takeAllClicks();
            if (!all && !anim.animatingNow()) return false;
            var page = pm.getCustomPage();
            if (!all && Pages.pendingAcks(pm) <= 0) return false;
            String data = e.data;
            store.getExternalData().getWorld().execute(() -> {
                if (!ref.isValid()) return;
                PageManager now = Pages.manager(store, ref);
                if (now == null || now.getCustomPage() != page) return;
                if (all) {
                    plugin.log("Клик окна реплики: " + data + " (ждёт ответа клиента: " + Pages.pendingAcks(now) + ")");
                } else if (!logged) {
                    logged = true;
                    plugin.log("Клик во время анимации окна передан окну напрямую (иначе сервер бы его выбросил)");
                }
                page.handleDataEvent(ref, store, data);
            });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
