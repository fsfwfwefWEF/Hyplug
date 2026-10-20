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

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.event.events.ecs.ChangeGameModeEvent;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Меню мода на клавишу смены режима (креатив / приключение).
 *
 * Клавиша не шлёт серверу ничего особого: клиент отправляет в чат команду «/gm c» или «/gm a»
 * (в логе сервера — «executed command: gm c»). Ловим её двумя способами:
 * 1) пакет чата до обработки (в 0.7.2 только так — и в игре пользователя это не сработало, причина не ясна);
 * 2) сама смена режима (событие ChangeGameModeEvent, его шлёт команда /gm): отменяем и открываем меню.
 * Режим переключается кнопкой в меню (она разрешает одну смену). Выключить перехват: /jrpg gmkey
 * (флаг gmKeyMenu в сохранении игрока). Пока перехват включён, в правом верхнем углу экрана висит
 * подсказка «[O] Меню героя» (HUD).
 */
final class GameMenuKey {
    static final String FLAG = "gmKeyMenu";
    /** Ровно то, что шлёт клавиша: /gm, /gm a, /gm c (и то же самое, набранное руками). */
    private static final Pattern GM = Pattern.compile("^/gm(\\s+[ac])?\\s*$", Pattern.CASE_INSENSITIVE);

    static final String HUD_KEY = "JrpgKeyHint";

    private final JrpgPlugin plugin;
    private PacketFilter filter;
    /** Игроки, которым следующую смену режима не перехватывать (кнопка «Режим» в меню). */
    private final Set<UUID> allowOnce = ConcurrentHashMap.newKeySet();
    /** Когда меню открывалось последний раз: клавиша может прийти и пакетом, и сменой режима. */
    private final Map<UUID, Long> lastOpen = new ConcurrentHashMap<>();
    private volatile boolean loggedPacket, loggedEvent;

    GameMenuKey(JrpgPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        filter = PacketAdapters.registerInbound((PlayerPacketFilter) this::onPacket);
        try {
            plugin.getEntityStoreRegistry().registerSystem(new ModeChange());
        } catch (Throwable t) {
            plugin.warn("Перехват смены режима не подключился", t);
        }
        plugin.scheduler().scheduleAtFixedRate(this::hudTickSafe, 3, 2, TimeUnit.SECONDS);
    }

    /** Следующую смену режима этого игрока пропустить (меню само переключает режим). */
    void allowNextChange(UUID player) {
        allowOnce.add(player);
        plugin.scheduler().schedule(() -> allowOnce.remove(player), 3, TimeUnit.SECONDS);
    }

    /** Смена режима (команда /gm от клавиши, /gamemode): при включённом перехвате — меню вместо смены. */
    private final class ModeChange extends EntityEventSystem<EntityStore, ChangeGameModeEvent> {
        ModeChange() {
            super(ChangeGameModeEvent.class);
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                           @Nonnull CommandBuffer<EntityStore> cb, @Nonnull ChangeGameModeEvent event) {
            try {
                Ref<EntityStore> ref = chunk.getReferenceTo(index);
                PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef == null) return;
                UUID id = playerRef.getUuid();
                if (allowOnce.remove(id) || !plugin.flag(id, FLAG, true)) return;
                event.setCancelled(true);
                if (!loggedEvent) {
                    loggedEvent = true;
                    plugin.log("Клавиша режима: смена режима отменена, открываю меню героя");
                }
                World world = store.getExternalData().getWorld();
                world.execute(() -> open(playerRef, ref, store));
            } catch (Throwable t) {
                Fx.warnOnce("Перехват смены режима", t);
            }
        }

        @Override
        public Query<EntityStore> getQuery() {
            return com.hypixel.hytale.component.Archetype.empty();
        }
    }

    // ---------- подсказка на экране ----------

    /** «[O] Меню героя» в правом верхнем углу: подсказка, что открывает клавиша режима. */
    static final class KeyHintHud extends CustomUIHud {
        KeyHintHud(PlayerRef playerRef) {
            super(playerRef, HUD_KEY);
        }

        @Override
        protected void build(@Nonnull UICommandBuilder cmd) {
            cmd.appendInline(null, """
                    Group {
                      Anchor: (Right: 18, Top: 18, Width: 196, Height: 34);
                      LayoutMode: Left;
                      Background: #070b12(0.78);
                      Padding: (Full: 4);

                      Group {
                        Anchor: (Width: 26, Height: 26);
                        Background: #e8c46a;
                        Padding: (Full: 2);
                        Group {
                          LayoutMode: Top;
                          Background: #1a2233;
                          Label {
                            Text: "O";
                            Style: (FontSize: 15, TextColor: #e8c46a, RenderBold: true,
                                    HorizontalAlignment: Center, VerticalAlignment: Center);
                          }
                        }
                      }
                      Group { Anchor: (Width: 8); }
                      Label {
                        Text: "Меню героя";
                        Style: (FontSize: 15, TextColor: #e8eef8, RenderBold: true, VerticalAlignment: Center);
                      }
                    }
                    """);
        }
    }

    private void hudTickSafe() {
        try {
            for (PlayerRef p : Universe.get().getPlayers()) {
                if (!p.isValid()) continue;
                Ref<EntityStore> ref = p.getReference();
                if (ref == null || !ref.isValid()) continue;
                // На время катсцены подсказка прячется (кадр чистый).
                boolean want = plugin.settings().keyHint && plugin.flag(p.getUuid(), FLAG, true) && !Cutscene.playing(p.getUuid());
                Store<EntityStore> store = ref.getStore();
                store.getExternalData().getWorld().execute(() -> syncHud(p, ref, store, want));
            }
        } catch (Throwable t) {
            Fx.warnOnce("Подсказка клавиши меню", t);
        }
    }

    private void syncHud(PlayerRef p, Ref<EntityStore> ref, Store<EntityStore> store, boolean want) {
        try {
            if (!ref.isValid()) return;
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) return;
            HudManager hud = player.getHudManager();
            boolean has = hud.getCustomHud(HUD_KEY) != null;
            if (want && !has) hud.addCustomHud(p, new KeyHintHud(p));
            else if (!want && has) hud.removeCustomHud(p, HUD_KEY);
        } catch (Throwable t) {
            Fx.warnOnce("Подсказка клавиши меню не показалась", t);
        }
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
            if (!loggedPacket) {
                loggedPacket = true;
                plugin.log("Клавиша режима: пойман пакет " + chat.message.trim() + ", открываю меню героя");
            }
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
        long now = System.currentTimeMillis();
        Long last = lastOpen.put(playerRef.getUuid(), now);
        if (last != null && now - last < 350) return; // одно нажатие пришло двумя путями
        BattleSession s = plugin.sessions().get(playerRef.getUuid());
        if (s != null) {
            var pm = Pages.manager(store, ref);
            if (pm != null && pm.getCustomPage() == null) s.openMenu();
            return;
        }
        if (plugin.isPrompting(playerRef.getUuid()) || Cutscene.playing(playerRef.getUuid())) return;
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
