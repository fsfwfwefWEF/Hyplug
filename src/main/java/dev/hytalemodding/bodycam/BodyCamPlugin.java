package dev.hytalemodding.bodycam;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.bodycam.commands.BodyCamCommand;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * Точка входа мода.
 *
 * Что делает мод:
 *  - каждому вошедшему игроку включает камеру «всё тело от первого лица»;
 *  - команда /bodycam включает и выключает её и двигает камеру.
 */
public class BodyCamPlugin extends JavaPlugin {

    public BodyCamPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        this.getCommandRegistry().registerCommand(new BodyCamCommand());
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, BodyCamPlugin::onPlayerReady);
        this.getLogger().at(Level.INFO).log("[BodyCam] Loaded. Type /bodycam in chat to toggle the body camera.");
    }

    /** Ставит камеру, как только игрок полностью зашёл в мир (и после смены мира). */
    private static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (!ref.isValid()) {
            return;
        }

        Store<EntityStore> store = ref.getStore();
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        BodyCamera.apply(playerRef);
    }
}
