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
 * Каждый кадр — «орбита» вокруг точки взгляда look: камера стоит на расстоянии dist, сбоку на угол side
 * и сверху на угол pitch. Позицию камеры считаем сами: клиент Hytale ставит камеру ровно в переданную
 * точку (в 0.4 мы передавали точку взгляда, и камера оказывалась внутри персонажей).
 *
 * Угол side отсчитывается от направления «из-за спины партии»: 0 — камера сзади партии и смотрит на врагов,
 * 90 — строго сбоку арены. Камера всегда остаётся с одной стороны арены (правило 180°),
 * иначе картинка «прыгает» и непонятно, кто где.
 */
public final class BattleCamera {
    private final PlayerRef player;
    private final Settings.Camera s;
    /** Арена: центр партии, центр врагов и единичные векторы «партия → враги» (A) и «вправо» (R) по XZ. */
    private final Vector3d partyCenter, enemyCenter;
    private final double ax, az, rx, rz;
    private String lastShot = "";
    /** Куда поставили камеру и куда она смотрит (последний кадр) — для окон реплик над головами. */
    private volatile Vector3d lastCam, lastLook;
    /**
     * Кино-режим (катсцены): без курсора и мыши в мире, ввод игрока не двигает ни его, ни камеру
     * (как PlaybackSafety.configureCamera в CameraMenu). Плюс свои угол обзора, размытие фона и крен.
     */
    private volatile boolean cinema;
    private volatile float fovOverride = -1f, roll;
    private volatile com.hypixel.hytale.protocol.DepthOfFieldSettings dof;

    public BattleCamera(PlayerRef player, Settings.Camera settings, Vector3d partyCenter, Vector3d enemyCenter) {
        this.player = player;
        this.s = settings;
        this.partyCenter = new Vector3d(partyCenter);
        this.enemyCenter = new Vector3d(enemyCenter);
        double dx = enemyCenter.x - partyCenter.x, dz = enemyCenter.z - partyCenter.z;
        double len = Math.max(1e-6, Math.hypot(dx, dz));
        this.ax = dx / len;
        this.az = dz / len;
        double sign = s.flip ? -1 : 1;
        this.rx = -az * sign;
        this.rz = ax * sign;
    }

    private Vector3d arenaMid(double up) {
        return new Vector3d((partyCenter.x + enemyCenter.x) / 2, partyCenter.y + up, (partyCenter.z + enemyCenter.z) / 2);
    }

    /** Общий план: центр арены, сбоку и сверху. */
    public void overview() {
        shot("overview", arenaMid(1.0), s.overviewSide, s.overviewPitch, s.overviewDistance);
    }

    /**
     * Облёт арены перед боем: кадр k из n. Камера идёт по дуге от «из-за спин врагов» к обычному
     * общему плану (правило 180° тут нарочно нарушается — это заставка), медленнее обычного.
     */
    public void flyby(int k, int n) {
        double t = n <= 1 ? 1 : k / (double) (n - 1);
        double side = 215 + (s.overviewSide - 215) * t;
        double dist = s.overviewDistance * (0.75 + 0.25 * t);
        double pitch = -20 + (s.overviewPitch + 20) * t;
        shot("flyby:" + k, arenaMid(1.0), side, pitch, dist, Math.min(s.lerp, 0.05f));
    }

    /** Кадр выбора цели: враги крупнее, чем на общем плане, но видны все. */
    public void targets(boolean enemies) {
        Vector3d c = enemies ? enemyCenter : partyCenter;
        Vector3d look = new Vector3d(c.x, c.y + 1.0, c.z);
        shot("targets:" + enemies, look, enemies ? s.focusSide + 10 : s.focusSide, s.overviewPitch, s.targetDistance);
    }

    /**
     * Наезд на бойца, чей ход. Партия — из-за спины сбоку (видно героя и врагов впереди),
     * враг — со стороны партии и сбоку (видно его «лицо»).
     */
    public void focus(Vector3d pos, boolean partySide) {
        if (pos == null) {
            overview();
            return;
        }
        Vector3d look = new Vector3d(pos.x, pos.y + 1.2, pos.z);
        shot("focus:" + pos, look, partySide ? s.focusSide : s.focusSide + 15, s.focusPitch, s.focusDistance);
    }

    /**
     * Портрет в полный рост (осмотр героя или врага в бою): камера спереди-сбоку от бойца на уровне груди,
     * близко, чтобы его было хорошо видно от ног до головы. faceYaw — куда боец смотрит.
     */
    public void profile(Vector3d pos, float faceYaw, double scale) {
        if (pos == null) return;
        // Направление взгляда бойца (yaw как у сущностей: вперёд = (-sin, -cos)).
        double fx = -Math.sin(faceYaw), fz = -Math.cos(faceYaw);
        // Камера на 35° сбоку от линии взгляда, со стороны правила 180° (как остальные кадры).
        double side = Math.toRadians(35) * (s.flip ? -1 : 1);
        double cx = fx * Math.cos(side) - fz * Math.sin(side), cz = fx * Math.sin(side) + fz * Math.cos(side);
        // scale — во сколько раз боец выше человека (босс крупнее): камера дальше и выше.
        double dist = s.profileDistance * scale;
        Vector3d look = new Vector3d(pos.x, pos.y + 0.95 * scale, pos.z);
        Vector3d cam = new Vector3d(pos.x + cx * dist, pos.y + 1.15 * scale, pos.z + cz * dist);
        place("profile:" + pos, cam, look, s.lerp);
    }

    /**
     * Финал боя: облёт партии на средней высоте (кадр k из n) — камера идёт дугой перед героями
     * со стороны врагов, чтобы видно было лица и эмоции.
     */
    public void outro(int k, int n) {
        double t = n <= 1 ? 1 : k / (double) (n - 1);
        double ang = Math.toRadians(-70 + 140 * t) * (s.flip ? -1 : 1);
        // Вперёд от партии к врагам (ax, az), поворот на ang вокруг вертикали.
        double dx = ax * Math.cos(ang) - az * Math.sin(ang), dz = ax * Math.sin(ang) + az * Math.cos(ang);
        Vector3d look = new Vector3d(partyCenter.x, partyCenter.y + 1.0, partyCenter.z);
        Vector3d cam = new Vector3d(partyCenter.x + dx * s.outroDistance, partyCenter.y + s.outroHeight,
                partyCenter.z + dz * s.outroDistance);
        place("outro:" + k, cam, look, Math.min(s.lerp, 0.06f));
    }

    /** Кадр «действующий + цель»: точка между ними, камера сбоку, чтобы видно было обоих. */
    public void action(Vector3d actor, Vector3d target) {
        if (actor == null || target == null) {
            focus(actor, true);
            return;
        }
        Vector3d look = new Vector3d((actor.x + target.x) / 2, Math.max(actor.y, target.y) + 1.0, (actor.z + target.z) / 2);
        double span = Math.hypot(actor.x - target.x, actor.z - target.z);
        double dist = Math.max(s.focusDistance, s.actionDistance + span * 0.7);
        shot("action:" + look, look, s.actionSide, s.actionPitch, dist);
    }

    private void shot(String id, Vector3d look, double sideDeg, double pitchDeg, double dist) {
        shot(id, look, sideDeg, pitchDeg, dist, s.lerp);
    }

    private void shot(String id, Vector3d look, double sideDeg, double pitchDeg, double dist, float lerp) {
        // Направление от точки взгляда к камере: назад (к партии) на cos(side), вправо на sin(side).
        double side = Math.toRadians(sideDeg), pitch = Math.toRadians(Math.min(-5, pitchDeg));
        double ux = -ax * Math.cos(side) + rx * Math.sin(side);
        double uz = -az * Math.cos(side) + rz * Math.sin(side);
        double horiz = dist * Math.cos(-pitch);
        double camX = look.x + ux * horiz, camZ = look.z + uz * horiz;
        double camY = look.y + Math.max(s.minHeight, dist * Math.sin(-pitch));
        place(id, new Vector3d(camX, camY, camZ), look, lerp);
    }

    /** Поставить камеру в точку cam взглядом на look. */
    private void place(String id, Vector3d cam, Vector3d look, float lerp) {
        if (!s.enabled) return;
        lastShot = id;
        try {
            double camX = cam.x, camY = cam.y, camZ = cam.z;
            // Смотрим из камеры на точку look (yaw как у сущностей: atan2(-dx, -dz)).
            double vx = look.x - camX, vy = look.y - camY, vz = look.z - camZ;
            float yaw = (float) Math.atan2(-vx, -vz);
            float realPitch = (float) Math.atan2(vy, Math.hypot(vx, vz));

            ServerCameraSettings c = new ServerCameraSettings();
            c.isFirstPerson = false;
            c.eyeOffset = false;
            c.displayCursor = true;
            c.positionLerpSpeed = lerp;
            c.rotationLerpSpeed = lerp;
            c.distance = 0f;
            c.positionDistanceOffsetType = PositionDistanceOffsetType.None;
            c.attachedToType = AttachedToType.None;
            c.positionType = PositionType.Custom;
            c.position = new Position(camX, camY, camZ);
            c.rotationType = RotationType.Custom;
            c.rotation = new Direction(yaw, realPitch, cinema ? roll : 0f);
            if (fovOverride > 1) c.baseFov = fovOverride;
            else if (s.fov > 1) c.baseFov = s.fov;
            if (cinema) {
                c.displayCursor = false;
                c.displayReticle = false;
                c.hideHeldItem = true;
                c.skipCharacterPhysics = true;
                c.allowPitchControls = false;
                c.movementMultiplier = new org.joml.Vector3f(0f, 0f, 0f);
                c.lookMultiplier = new org.joml.Vector2f(0f, 0f);
                c.speedModifier = 0f;
                c.mouseInputTargetType = com.hypixel.hytale.protocol.MouseInputTargetType.None;
                c.sendMouseMotion = false;
                if (dof != null) c.depthOfField = dof;
            } else if (s.mouseTargets) {
                // Курсор в мире: клиент шлёт движение мыши и сущность под курсором (наведение и клик по врагу).
                c.sendMouseMotion = true;
                c.mouseInputTargetType = com.hypixel.hytale.protocol.MouseInputTargetType.Entity;
            }
            lastCam = new Vector3d(camX, camY, camZ);
            lastLook = new Vector3d(look);
            player.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, true, c));
        } catch (Throwable t) {
            Fx.warnOnce("Камера боя не сработала (" + id + ")", t);
        }
    }

    public String lastShot() { return lastShot; }

    /** Кадр сцены навыка: камера ровно в cam, взгляд на look (позицию считает сам таймлайн, см. SkillSequence). */
    public void aim(String id, Vector3d cam, Vector3d look, float lerp) {
        place(id, cam, look, lerp);
    }

    /** Где камера стоит и куда смотрит по последнему кадру (null — кадров ещё не было). */
    public Vector3d lastCam() { return lastCam == null ? null : new Vector3d(lastCam); }

    public Vector3d lastLook() { return lastLook == null ? null : new Vector3d(lastLook); }

    /**
     * Где точка мира на экране: {x, y} от 0 до 1 (0,0 — левый верх), null — позади камеры.
     * Считается по последнему кадру камеры и углу обзора fov (16:9); пока камера плавно едет, чуть отстаёт.
     */
    public double[] project(Vector3d p) {
        Vector3d cam = lastCam, look = lastLook;
        if (cam == null || look == null || p == null) return null;
        double fx = look.x - cam.x, fy = look.y - cam.y, fz = look.z - cam.z;
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl < 1e-6) return null;
        fx /= fl; fy /= fl; fz /= fl;
        // right = forward × up(0,1,0); up' = right × forward
        double rx = -fz, ry = 0, rz = fx;
        double rl = Math.hypot(rx, rz);
        if (rl < 1e-6) return null;
        rx /= rl; rz /= rl;
        double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
        double dx = p.x - cam.x, dy = p.y - cam.y, dz = p.z - cam.z;
        double z = dx * fx + dy * fy + dz * fz;
        if (z < 0.5) return null;
        double x = dx * rx + dy * ry + dz * rz, y = dx * ux + dy * uy + dz * uz;
        double tanV = Math.tan(Math.toRadians(fov() / 2.0)), tanH = tanV * 16.0 / 9.0;
        return new double[]{0.5 + x / (z * tanH) / 2, 0.5 - y / (z * tanV) / 2};
    }

    /** Кино-режим для катсцен (см. поле cinema). */
    public void cinema(boolean on) { cinema = on; }

    /** Угол обзора кадров (градусы); 0 и меньше — как в настройках камеры. */
    public void fov(float degrees) { fovOverride = degrees; }

    public float fov() { return fovOverride > 1 ? fovOverride : (s.fov > 1 ? s.fov : 70f); }

    /** Крен камеры (радианы), только в кино-режиме. */
    public void roll(float radians) { roll = radians; }

    /** Размытие фона (глубина резкости); null — выключить. Только в кино-режиме. */
    public void dof(com.hypixel.hytale.protocol.DepthOfFieldSettings d) { dof = d; }

    /** Повторить последний кадр (после смены fov, размытия или крена). */
    public void refresh() {
        Vector3d c = lastCam, l = lastLook;
        if (c != null && l != null) place(lastShot, c, l, 1f);
    }

    public void reset() {
        try {
            player.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, false, null));
        } catch (Throwable t) {
            Fx.warnOnce("Камера не вернулась", t);
        }
    }
}
