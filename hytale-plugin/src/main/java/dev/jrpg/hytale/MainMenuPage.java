package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Меню героя на клавише режима (Common/UI/Custom/JrpgBattle/MainMenu.ui, написан вручную):
 * прокачка, экипировка, рюкзак, компаньоны идут / ждут, смена режима игры (саму клавишу перехватывает GameMenuKey).
 */
public final class MainMenuPage extends InteractiveCustomUIPage<BattleMenuPage.Click> {
    public static final String LAYOUT = "JrpgBattle/MainMenu.ui";

    private final JrpgPlugin plugin;

    public MainMenuPage(@Nonnull PlayerRef playerRef, JrpgPlugin plugin) {
        super(playerRef, CustomPageLifetime.CanDismiss, BattleMenuPage.Click.CODEC);
        this.plugin = plugin;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(LAYOUT);
        bind(evt, "#Party", "party");
        bind(evt, "#Gear", "gear");
        bind(evt, "#Bag", "bag");
        bind(evt, "#Follow", "follow");
        bind(evt, "#Mode", "mode");
        bind(evt, "#Close", "close");
        fill(cmd, store, ref);
        WindowIntro.play(this, playerRef, plugin, store, cmd, LAYOUT);
    }

    private static void bind(UIEventBuilder evt, String selector, String action) {
        evt.addEventBinding(CustomUIEventBindingType.Activating, selector,
                new EventData().append("Action", action).append("Index", "0"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull BattleMenuPage.Click data) {
        switch (data.action == null ? "" : data.action) {
            // Другое окно сменяет это сразу, без закрытия между ними (см. Pages).
            case "party" -> plugin.openProgression(playerRef, ref, store, null);
            case "gear" -> plugin.openEquipment(playerRef, ref, store, null);
            case "bag" -> plugin.openBackpack(playerRef, ref, store);
            case "follow" -> {
                boolean on = !plugin.flag(playerRef.getUuid(), CompanionFollowers.FLAG, true);
                plugin.setFlag(playerRef.getUuid(), CompanionFollowers.FLAG, on);
                UICommandBuilder cmd = new UICommandBuilder();
                fill(cmd, store, ref);
                Pages.update(this, playerRef, cmd);
            }
            case "mode" -> {
                // Та же команда, что слала бы клавиша; идёт мимо перехвата, права проверяет сервер.
                String command = gameMode(store, ref) == GameMode.Creative ? "gm a" : "gm c";
                Pages.close(this, playerRef);
                plugin.menuKey().allowNextChange(playerRef.getUuid());
                CommandManager.get().handleCommand(playerRef, command);
            }
            case "close" -> Pages.close(this, playerRef);
            default -> { }
        }
    }

    private void fill(UICommandBuilder cmd, Store<EntityStore> store, Ref<EntityStore> ref) {
        boolean follow = plugin.flag(playerRef.getUuid(), CompanionFollowers.FLAG, true);
        cmd.set("#Follow.Text", follow ? "Компаньоны: идут за тобой" : "Компаньоны: ждут");
        cmd.set("#Mode.Text", gameMode(store, ref) == GameMode.Creative ? "Режим: креатив → приключение"
                : "Режим: приключение → креатив");
    }

    private static GameMode gameMode(Store<EntityStore> store, Ref<EntityStore> ref) {
        Player p = ref.isValid() ? store.getComponent(ref, Player.getComponentType()) : null;
        return p == null ? GameMode.Adventure : p.getGameMode();
    }
}
