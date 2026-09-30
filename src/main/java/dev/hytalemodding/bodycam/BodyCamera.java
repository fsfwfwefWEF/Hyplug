package dev.hytalemodding.bodycam;

import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.PositionDistanceOffsetType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Камера «всё тело от первого лица».
 *
 * Приём такой: клиенту включается вид от третьего лица (isFirstPerson = false),
 * поэтому он рисует модель игрока целиком — туловище, руки, ноги. Но камера
 * не отъезжает назад, а стоит на уровне глаз (eyeOffset) и смотрит туда же,
 * куда смотрит игрок. Получается вид из глаз, в котором видно своё тело.
 *
 * Всё остальное (к кому привязана камера, повороты мышью, движение) остаётся
 * по умолчанию — как у обычной камеры игрока.
 */
public final class BodyCamera {

    /** Сдвиг вперёд по умолчанию, блоки. 0 — ровно в глазах. */
    public static final float DEFAULT_FORWARD = 0.0f;
    /** Сдвиг вверх по умолчанию, блоки. */
    public static final float DEFAULT_UP = 0.0f;

    /** Ограничения на сдвиги, чтобы камеру нельзя было унести далеко от головы. */
    public static final float MAX_FORWARD = 1.0f;
    public static final float MAX_UP = 1.0f;

    /** Настройки одного игрока. Живут, пока работает сервер. */
    public static final class Settings {
        public boolean enabled;
        public float forward = DEFAULT_FORWARD;
        public float up = DEFAULT_UP;

        Settings(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** Включена ли камера у игрока, который ещё ни разу не менял её сам. */
    public static final boolean ENABLED_BY_DEFAULT = true;

    private static final Map<UUID, Settings> SETTINGS = new ConcurrentHashMap<>();

    private BodyCamera() {
    }

    public static Settings settings(PlayerRef playerRef) {
        return SETTINGS.computeIfAbsent(playerRef.getUuid(), uuid -> new Settings(ENABLED_BY_DEFAULT));
    }

    /** Отправляет игроку камеру по его настройкам: включённую или обычную. */
    public static void apply(PlayerRef playerRef) {
        Settings settings = settings(playerRef);
        playerRef.getPacketHandler().writeNoCache(settings.enabled ? packet(settings) : reset());
    }

    static SetServerCamera packet(Settings settings) {
        ServerCameraSettings camera = new ServerCameraSettings();
        // Главное: вид от третьего лица, чтобы клиент рисовал всё тело.
        camera.isFirstPerson = false;
        // Камера от уровня глаз, а не от ног.
        camera.eyeOffset = true;
        // Отступ вдоль взгляда: положительная дистанция отодвигает камеру назад,
        // поэтому «вперёд» передаётся с минусом.
        camera.positionDistanceOffsetType = PositionDistanceOffsetType.DistanceOffset;
        camera.distance = -settings.forward;
        camera.positionOffset = new Position(0.0, settings.up, 0.0);
        // Камера сразу за головой, без плавного догоняния.
        camera.positionLerpSpeed = 1.0f;
        camera.rotationLerpSpeed = 1.0f;
        camera.allowPitchControls = true;
        camera.displayCursor = false;
        camera.displayReticle = true;
        camera.hideHeldItem = false;
        return new SetServerCamera(ClientCameraView.Custom, false, camera);
    }

    /** Обычная камера игрока, как у ванильного /camera reset. */
    static SetServerCamera reset() {
        return new SetServerCamera(ClientCameraView.Custom, false, null);
    }
}
