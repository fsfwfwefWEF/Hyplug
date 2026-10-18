package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interface_.ChatMessage;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.regex.Pattern;

/**
 * Меню мода на клавишу смены режима (креатив / приключение).
 *
 * Клавиша не шлёт серверу ничего особого: клиент отправляет в чат команду «/gm c» или «/gm a»
 * (в логе сервера — «executed command: gm c»). Мы ловим этот пакет до обработки, не пускаем его дальше
 * и открываем своё меню. Режим переключается кнопкой в меню, а /gamemode и /jrpg gmkey работают как раньше.
 * Выключить перехват для себя: /jrpg gmkey (флаг gmKeyMenu в сохранении игрока).
 */
final class GameMenuKey {
    static final String FLAG = "gmKeyMenu";
    /** Ровно то, что шлёт клавиша: /gm, /gm a, /gm c (и то же самое, набранное руками). */
    private static final Pattern GM = Pattern.compile("^/gm(\\s+[ac])?\\s*$", Pattern.CASE_INSENSITIVE);

    private final JrpgPlugin plugin;
    private PacketFilter filter;

    GameMenuKey(JrpgPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        filter = PacketAdapters.registerInbound((PlayerPacketFilter) this::onPacket);
    }

    void stop() {
        if (filter != null) PacketAdapters.deregisterInbound(filter);
        filter = null;
    }

    /** Поток сети. true — пакет съеден (команда /gm не выполнится). */
    private boolean onPacket(PlayerRef playerRef, Packet packet) {
        if (!(packet instanceof ChatMessage chat) || chat.message == null || !GM.matcher(chat.message).matches()) return false;
        try {
            if (!plugin.flag(playerRef.getUuid(), FLAG, true)) return false;
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) return false;
            Store<EntityStore> store = ref.getStore();
            store.getExternalData().getWorld().execute(() -> open(playerRef, ref, store));
            return true;
        } catch (Throwable t) {
            plugin.warn("Клавиша меню", t);
            return false;
        }
    }

    /** Поток мира: в бою — вернуть боевое меню, если сейчас нет окна, иначе — меню героя. */
    private void open(PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!ref.isValid()) return;
        BattleSession s = plugin.sessions().get(playerRef.getUuid());
        if (s != null) {
            var pm = Pages.manager(store, ref);
            if (pm != null && pm.getCustomPage() == null) s.openMenu();
            return;
        }
        if (plugin.isPrompting(playerRef.getUuid())) return;
        Pages.open(plugin, playerRef, ref, store, new MainMenuPage(playerRef, plugin));
    }

    /** /jrpg gmkey — включить/выключить меню на клавише режима. */
    static final class Toggle extends AbstractPlayerCommand {
        private final JrpgPlugin plugin;

        Toggle(JrpgPlugin plugin) {
            super("gmkey", "Меню мода на клавише режима: вкл/выкл");
            this.plugin = plugin;
            requireNoPermission();
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            boolean on = !plugin.flag(playerRef.getUuid(), FLAG, true);
            plugin.setFlag(playerRef.getUuid(), FLAG, on);
            ctx.sendMessage(Message.raw(on ? "Клавиша режима теперь открывает меню героя."
                    : "Клавиша режима снова переключает креатив / приключение."));
        }
    }

    /** /jrpg music <id|off> — включить трек боя (проверка) или выключить. */
    static final class MusicCmd extends AbstractPlayerCommand {
        private final JrpgPlugin plugin;
        private final RequiredArg<String> idArg =
                withRequiredArg("id", "id MusicContainer (например Jrpg_Battle_Darkness) или off", ArgTypes.STRING);

        MusicCmd(JrpgPlugin plugin) {
            super("music", "Проверить музыку боя");
            this.plugin = plugin;
            requireNoPermission();
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            String id = idArg.get(ctx);
            if (id.equalsIgnoreCase("off")) {
                BattleMusic.stop(plugin, store, ref);
                ctx.sendMessage(Message.raw("Музыка боя выключена."));
            } else if (BattleMusic.play(plugin, store, ref, id)) {
                ctx.sendMessage(Message.raw("Играет «" + id + "». Выключить: /jrpg music off"));
            } else {
                ctx.sendMessage(Message.raw("Нет музыки «" + id + "». Треки мода: Jrpg_Battle_Darkness."));
            }
        }
    }
}
