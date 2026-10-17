package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Мелкие действия с миром: частицы, подписи над головой, перемещения и повороты. Камера — {@link BattleCamera}.
 * Каждый вызов защищён: если что-то из API не сработало, бой продолжается, а в лог пишется
 * одно предупреждение (чтобы по логу было видно, что чинить).
 */
public final class Fx {
    private static final Set<String> warned = new HashSet<>();

    private Fx() {
    }

    static void warnOnce(String key, Throwable t) {
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
            Rotation3f rot = new Rotation3f(0, yaw, 0);
            store.putComponent(ref, Teleport.getComponentType(), new Teleport(pos, rot).setHeadRotation(rot));
        } catch (Throwable t) {
            warnOnce("Не удалось переместить сущность", t);
        }
    }

    /** Для игрока сервер требует отдельный вариант телепорта (синхронизирует клиента). */
    public static void teleportPlayer(Store<EntityStore> store, Ref<EntityStore> ref, Vector3d pos, float yaw) {
        try {
            if (ref == null || !ref.isValid()) return;
            Rotation3f rot = new Rotation3f(0, yaw, 0);
            store.putComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(pos, rot).setHeadRotation(rot));
        } catch (Throwable t) {
            warnOnce("Не удалось переместить игрока", t);
        }
    }

    /** Поворот (yaw, радианы), чтобы смотреть из from в to. В Hytale yaw = 0 смотрит на -Z. */
    public static float yawTowards(Vector3d from, Vector3d to) {
        return (float) Math.atan2(-(to.x - from.x), -(to.z - from.z));
    }
}
