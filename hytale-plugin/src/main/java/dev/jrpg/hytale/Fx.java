package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Всё «зрелищное» в одном месте: камера, частицы, подписи над головой, перемещения.
 * Каждый вызов защищён: если что-то из API не сработало, бой продолжается, а в лог пишется
 * одно предупреждение (чтобы по логу было видно, что чинить).
 */
public final class Fx {
    private static final Set<String> warned = new HashSet<>();

    private Fx() {
    }

    private static void warnOnce(String key, Throwable t) {
        if (warned.add(key)) JrpgPlugin.get().warn(key, t);
    }

    public static Vector3d position(Store<EntityStore> store, Ref<EntityStore> ref) {
        TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
        return t == null ? new Vector3d(0, 0, 0) : new Vector3d(t.getPosition());
    }

    /** Частица name в точке pos, видна только игроку viewer. Пустое имя — ничего. */
    public static boolean particle(String name, Vector3d pos, Ref<EntityStore> viewer, Store<EntityStore> store) {
        if (name == null || name.isBlank()) return false;
        try {
            ParticleUtil.spawnParticleEffect(name, pos, List.of(viewer), store);
            return true;
        } catch (Throwable t) {
            warnOnce("Частица " + name + " не сработала", t);
            return false;
        }
    }

    public static void nameplate(Store<EntityStore> store, Ref<EntityStore> ref, String text) {
        try {
            if (ref == null || !ref.isValid()) return;
            Nameplate n = store.getComponent(ref, Nameplate.getComponentType());
            if (n == null) store.putComponent(ref, Nameplate.getComponentType(), new Nameplate(text));
            else n.setText(text);
        } catch (Throwable t) {
            warnOnce("Не удалось обновить подпись над NPC", t);
        }
    }

    public static void teleport(Store<EntityStore> store, Ref<EntityStore> ref, Vector3d pos, float yaw) {
        try {
            if (ref == null || !ref.isValid()) return;
            if (store.getComponent(ref, Player.getComponentType()) != null) {
                teleportPlayer(store, ref, pos, yaw);
                return;
            }
            store.putComponent(ref, Teleport.getComponentType(), new Teleport(pos, new Rotation3f(0, yaw, 0)));
        } catch (Throwable t) {
            warnOnce("Не удалось переместить сущность", t);
        }
    }

    /** Для игрока сервер требует отдельный вариант телепорта (синхронизирует клиента). */
    public static void teleportPlayer(Store<EntityStore> store, Ref<EntityStore> ref, Vector3d pos, float yaw) {
        try {
            if (ref == null || !ref.isValid()) return;
            store.putComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(pos, new Rotation3f(0, yaw, 0)));
        } catch (Throwable t) {
            warnOnce("Не удалось переместить игрока", t);
        }
    }

    // ---------- камера ----------

    /** Общий план арены. Настройки строятся относительно игрока (так устроен API камеры). */
    public static void battleCamera(PlayerRef player, Settings s) {
        if (!s.cameraEnabled) return;
        try {
            ServerCameraSettings c = new ServerCameraSettings();
            c.isFirstPerson = false;
            c.distance = s.cameraDistance;
            c.positionLerpSpeed = s.cameraLerp;
            c.rotationLerpSpeed = s.cameraLerp;
            c.displayCursor = true;
            c.rotationType = RotationType.Custom;
            c.rotation = new Direction((float) Math.toRadians(s.cameraYaw), (float) Math.toRadians(s.cameraPitch), 0f);
            player.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, true, c));
        } catch (Throwable t) {
            warnOnce("Камера боя не включилась", t);
        }
    }

    public static void resetCamera(PlayerRef player) {
        try {
            player.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, false, null));
        } catch (Throwable t) {
            warnOnce("Камера не вернулась", t);
        }
    }
}
