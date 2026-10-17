package dev.jrpg.hytale;

import com.hypixel.hytale.protocol.AttachedToType;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.PositionDistanceOffsetType;
import com.hypixel.hytale.protocol.PositionType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.joml.Vector3d;

/**
 * Боевая камера в духе Final Fantasy: общий план арены сверху и сбоку, при ходе — наезд на того,
 * кто действует, при атаке — кадр «атакующий + цель».
 *
 * Камера — «орбита» вокруг точки: смотрим на точку look с расстояния dist, поворот (yaw) и наклон (pitch)
 * задаются в градусах. В Hytale yaw = 0 смотрит на -Z, отрицательный pitch — взгляд сверху вниз.
 *
 * Точка look передаётся либо как мировые координаты (anchor = "world", PositionType.Custom),
 * либо как смещение от игрока (anchor = "player", PositionType.AttachedToPlusOffset), если первый
 * вариант в игре поведёт себя не так.
 */
public final class BattleCamera {
    private final PlayerRef player;
    private final Settings.Camera s;
    /** Арена: где стоит партия, где враги, направление «партия → враги» (единичный вектор по XZ). */
    private final Vector3d partyCenter, enemyCenter;
    private final double axisX, axisZ;
    private Vector3d playerPos;
    private String lastShot = "";

    public BattleCamera(PlayerRef player, Settings.Camera settings, Vector3d partyCenter, Vector3d enemyCenter) {
        this.player = player;
        this.s = settings;
        this.partyCenter = new Vector3d(partyCenter);
        this.enemyCenter = new Vector3d(enemyCenter);
        double dx = enemyCenter.x - partyCenter.x, dz = enemyCenter.z - partyCenter.z;
        double len = Math.max(1e-6, Math.hypot(dx, dz));
        this.axisX = dx / len;
        this.axisZ = dz / len;
        this.playerPos = new Vector3d(partyCenter);
    }

    /** Где сейчас стоит игрок (нужно для режима anchor = "player"). */
    public void setPlayerPos(Vector3d pos) {
        if (pos != null) this.playerPos = new Vector3d(pos);
    }

    /** Yaw (градусы), при котором камера смотрит вдоль оси «партия → враги». */
    private double axisYawDeg() {
        return Math.toDegrees(Math.atan2(-axisX, -axisZ));
    }

    /** Общий план: центр арены, сбоку-сзади партии и сверху. */
    public void overview() {
        Vector3d look = new Vector3d((partyCenter.x + enemyCenter.x) / 2, partyCenter.y + 1.0,
                (partyCenter.z + enemyCenter.z) / 2);
        shot("overview", look, axisYawDeg() + s.overviewSide, s.overviewPitch, s.overviewDistance, s.lerp);
    }

    /**
     * Наезд на бойца, чей сейчас ход. Герой — вид «из-за плеча» в сторону врагов (видно и героя, и цели),
     * враг — спереди-сбоку со стороны партии.
     */
    public void focus(Vector3d pos, boolean partySide) {
        if (pos == null) {
            overview();
            return;
        }
        Vector3d look = new Vector3d(pos.x, pos.y + 1.2, pos.z);
        double yaw = partySide ? axisYawDeg() + s.focusSide : axisYawDeg() - s.focusSide;
        shot("focus:" + pos, look, yaw, s.focusPitch, s.focusDistance, s.lerp);
    }

    /** Кадр «действующий + цель»: точка между ними, камера сбоку, чтобы видно было обоих. */
    public void action(Vector3d actor, Vector3d target, boolean partySide) {
        if (actor == null || target == null) {
            focus(actor, partySide);
            return;
        }
        Vector3d look = new Vector3d((actor.x + target.x) / 2, Math.max(actor.y, target.y) + 1.0, (actor.z + target.z) / 2);
        double span = Math.hypot(actor.x - target.x, actor.z - target.z);
        double dist = Math.max(s.focusDistance, s.actionDistance + span * 0.6);
        double yaw = axisYawDeg() + (partySide ? s.actionSide : -s.actionSide);
        shot("action:" + look, look, yaw, s.actionPitch, dist, s.lerp);
    }

    private void shot(String id, Vector3d look, double yawDeg, double pitchDeg, double dist, float lerp) {
        if (!s.enabled) return;
        lastShot = id;
        try {
            ServerCameraSettings c = new ServerCameraSettings();
            c.isFirstPerson = false;
            c.eyeOffset = false;
            c.displayCursor = true;
            c.positionLerpSpeed = lerp;
            c.rotationLerpSpeed = lerp;
            c.distance = (float) dist;
            c.positionDistanceOffsetType = PositionDistanceOffsetType.DistanceOffset;
            if ("player".equals(s.anchor)) {
                c.attachedToType = AttachedToType.LocalPlayer;
                c.positionType = PositionType.AttachedToPlusOffset;
                c.positionOffset = new Position(look.x - playerPos.x, look.y - playerPos.y, look.z - playerPos.z);
            } else {
                c.attachedToType = AttachedToType.None;
                c.positionType = PositionType.Custom;
                c.position = new Position(look.x, look.y, look.z);
            }
            c.rotationType = RotationType.Custom;
            c.rotation = new Direction((float) Math.toRadians(yawDeg), (float) Math.toRadians(pitchDeg), 0f);
            player.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, true, c));
        } catch (Throwable t) {
            Fx.warnOnce("Камера боя не сработала (" + id + ")", t);
        }
    }

    public String lastShot() { return lastShot; }

    public void reset() {
        try {
            player.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, false, null));
        } catch (Throwable t) {
            Fx.warnOnce("Камера не вернулась", t);
        }
    }
}
