package dev.jrpg.hytale;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.protocol.CombatTextUpdate;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.function.consumer.TriConsumer;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
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

    /** Текущая подпись над сущностью или null, если подписи нет. */
    public static String nameplateText(Store<EntityStore> store, Ref<EntityStore> ref) {
        try {
            Nameplate n = ref == null || !ref.isValid() ? null : store.getComponent(ref, Nameplate.getComponentType());
            return n == null ? null : n.getText();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Вернуть подпись, какая была (null — подписи не было: убрать). */
    public static void restoreNameplate(Store<EntityStore> store, Ref<EntityStore> ref, String text) {
        try {
            if (ref == null || !ref.isValid()) return;
            if (text != null) nameplate(store, ref, text);
            else if (store.getComponent(ref, Nameplate.getComponentType()) != null) store.removeComponent(ref, Nameplate.getComponentType());
        } catch (Throwable t) {
            warnOnce("Не удалось вернуть подпись", t);
        }
    }

    private static final Set<String> combatTextLogged = new HashSet<>();

    /**
     * Цифры урона (CombatTextUpdate) клиент рисует только у сущностей, в списке UI-компонентов которых
     * есть «всплывающий текст» (CombatText). У части ролей его нет — тогда цифр не видно (так было в 0.7.3).
     * Добавляем все такие компоненты к тому, что у сущности уже есть (полоски HP роли не трогаем).
     */
    public static void ensureCombatText(Store<EntityStore> store, Ref<EntityStore> ref, String who) {
        try {
            if (ref == null || !ref.isValid()) return;
            var map = com.hypixel.hytale.server.core.modules.entityui.asset.EntityUIComponent.getAssetMap();
            var type = com.hypixel.hytale.server.core.modules.entityui.UIComponentList.getComponentType();
            var cur = store.getComponent(ref, type);
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            boolean had = false;
            if (cur != null && cur.getComponentIds() != null) {
                for (int id : cur.getComponentIds()) {
                    var a = map.getAsset(id);
                    if (a == null) continue;
                    names.add(a.getId());
                    had |= a instanceof com.hypixel.hytale.server.core.modules.entityui.asset.CombatTextUIComponent;
                }
            }
            if (had) return;
            int added = 0;
            for (var a : map.getAssetMap().values()) {
                if (a instanceof com.hypixel.hytale.server.core.modules.entityui.asset.CombatTextUIComponent && names.add(a.getId())) added++;
            }
            if (added == 0) return;
            var list = new com.hypixel.hytale.server.core.modules.entityui.UIComponentList(names.toArray(String[]::new));
            list.update();
            store.putComponent(ref, type, list);
            if (combatTextLogged.add(who)) {
                JrpgPlugin.get().log("Цифры урона: у «" + who + "» не было всплывающего текста, добавлено " + added
                        + " (" + names + ")");
            }
        } catch (Throwable t) {
            warnOnce("Не удалось включить цифры урона", t);
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

    /**
     * Всплывающий текст над сущностью (как цифры урона в обычном бою Hytale), виден игроку viewer.
     * color — "#rrggbb". Работает для NPC; над самим игроком клиент может его не показать.
     */
    public static void combatText(Store<EntityStore> store, Ref<EntityStore> viewer, Ref<EntityStore> target,
                                  String text, String color) {
        try {
            if (target == null || !target.isValid() || viewer == null || !viewer.isValid()) return;
            EntityTrackerSystems.EntityViewer v = store.getComponent(viewer, EntityTrackerSystems.EntityViewer.getComponentType());
            if (v == null) return;
            int rgb = Integer.parseInt(color.substring(1), 16);
            Color c = new Color((byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb);
            v.queueUpdate(target, new CombatTextUpdate(0f, text, c));
        } catch (Throwable t) {
            warnOnce("Всплывающий текст не сработал", t);
        }
    }

    /** Роль можно задать списком через «|»: берётся первая, которая есть в игре, иначе fallbackRole. */
    public static String resolveRole(String spec, String who) {
        if (spec == null || spec.isBlank()) return null;
        NPCPlugin npcs = NPCPlugin.get();
        for (String r : spec.split("\\|")) {
            String role = r.trim();
            if (!role.isEmpty() && npcs.getIndex(role) >= 0) return role;
        }
        String fb = JrpgPlugin.get().settings().fallbackRole;
        warnOnce("Роль NPC не найдена: " + spec + " (" + who + "). Показываю как " + fb
                + ". Список ролей: /jrpg roles <часть имени>", null);
        return fb != null && !fb.isBlank() && npcs.getIndex(fb) >= 0 ? fb : null;
    }

    /**
     * NPC-«кукла»: стоит на месте (Frozen), неуязвима, смотрит по yaw. Своего ИИ у неё нет — бой ведёт мод.
     * null — роль не нашлась или спавн не удался (причина в логе).
     */
    public static Ref<EntityStore> spawnPuppet(Store<EntityStore> store, String roleSpec, String who, Vector3d pos, float yaw) {
        return spawnPuppet(store, roleSpec, who, pos, yaw, null);
    }

    /**
     * То же, с подписью над головой. Frozen, Invulnerable, NonSerialized и подпись ставятся на заготовку
     * ДО появления NPC в мире (в 0.7.0 — после): так у куклы не бывает ни одного тика своего ИИ (раньше
     * трорк-компаньон успевал «проснуться» и напасть), она сразу стоит в позе покоя, а не бежит на месте
     * (поза выбирается при появлении: Frozen есть — Idle), и никогда не сохраняется в мир.
     */
    public static Ref<EntityStore> spawnPuppet(Store<EntityStore> store, String roleSpec, String who, Vector3d pos, float yaw,
                                               String nameplate) {
        return spawnPuppet(store, roleSpec, who, pos, yaw, nameplate, null);
    }

    /** То же, с человеческим видом look (модель игрока со скином, см. {@link HumanLook}); null — вид роли. */
    public static Ref<EntityStore> spawnPuppet(Store<EntityStore> store, String roleSpec, String who, Vector3d pos, float yaw,
                                               String nameplate, HumanLook.Look look) {
        try {
            String role = resolveRole(roleSpec, who);
            if (role == null || pos == null) return null;
            NPCPlugin npcs = NPCPlugin.get();
            TriConsumer<NPCEntity, Holder<EntityStore>, Store<EntityStore>> prepare = (npc, holder, st) -> {
                holder.putComponent(Frozen.getComponentType(), Frozen.get());
                holder.putComponent(Invulnerable.getComponentType(), Invulnerable.INSTANCE);
                try {
                    holder.putComponent(EntityStore.REGISTRY.getNonSerializedComponentType(),
                            com.hypixel.hytale.component.NonSerialized.get());
                } catch (Throwable t) {
                    warnOnce("Не удалось пометить NPC как временного", t);
                }
                if (nameplate != null) holder.putComponent(Nameplate.getComponentType(), new Nameplate(nameplate));
                if (look != null) {
                    holder.putComponent(com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent.getComponentType(),
                            new com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent(look.skin()));
                }
            };
            TriConsumer<NPCEntity, Ref<EntityStore>, Store<EntityStore>> after = null;
            Pair<Ref<EntityStore>, NPCEntity> spawned = npcs.spawnEntity(store, npcs.getIndex(role), pos,
                    new Rotation3f(0, yaw, 0), look == null ? null : look.model(), prepare, after);
            if (spawned == null || spawned.first() == null) return null;
            Ref<EntityStore> ref = spawned.first();
            // На всякий случай ещё раз (если заготовку кто-то переписал): кукла заморожена и стоит.
            if (store.getComponent(ref, Frozen.getComponentType()) == null) {
                store.putComponent(ref, Frozen.getComponentType(), Frozen.get());
            }
            setMovement(store, ref, "idle");
            ensureCombatText(store, ref, who);
            // Роль могла поставить свою подпись — наша важнее.
            if (nameplate != null) nameplate(store, ref, nameplate);
            // Поворот ещё раз телепортом: при спавне NPC иногда разворачивается сам.
            teleport(store, ref, pos, yaw);
            return ref;
        } catch (Throwable t) {
            JrpgPlugin.get().warn("Не удалось заспавнить " + who + " (роль " + roleSpec + ")", t);
            return null;
        }
    }

    /**
     * Поза движения NPC для клиента: "idle" (стоит), "walk" (идёт), "run" (бежит). Клиент сам играет
     * Idle/Walk/Run модели. Состояние надо снимать, когда NPC остановился, иначе он «бежит на месте».
     */
    public static void setMovement(Store<EntityStore> store, Ref<EntityStore> ref, String state) {
        try {
            if (ref == null || !ref.isValid()) return;
            MovementStatesComponent mc = store.getComponent(ref, MovementStatesComponent.getComponentType());
            if (mc == null) return;
            MovementStates ms = mc.getMovementStates();
            ms.idle = state.equals("idle");
            ms.horizontalIdle = ms.idle;
            ms.walking = state.equals("walk");
            ms.running = state.equals("run");
            ms.sprinting = false;
            ms.onGround = true;
            ms.falling = false;
            ms.jumping = false;
        } catch (Throwable t) {
            warnOnce("Поза движения NPC не сработала", t);
        }
    }

    /**
     * Плавно поставить NPC в точку (как двигаются обычные NPC: клиент сглаживает между тиками).
     * Для игрока — телепорт (его позицией управляет клиент).
     */
    public static void place(Store<EntityStore> store, Ref<EntityStore> ref, Vector3d pos, float yaw) {
        try {
            if (ref == null || !ref.isValid()) return;
            if (store.getComponent(ref, Player.getComponentType()) != null) {
                teleportPlayer(store, ref, pos, yaw);
                return;
            }
            TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
            if (t == null) return;
            t.setPosition(pos);
            t.getRotation().setYaw(yaw);
            HeadRotation head = store.getComponent(ref, HeadRotation.getComponentType());
            if (head != null) head.getRotation().setYaw(yaw);
        } catch (Throwable t) {
            warnOnce("NPC не двигается", t);
        }
    }

    /** Убрать сущность из мира (куклу или NPC отряда). */
    public static void remove(Store<EntityStore> store, Ref<EntityStore> ref) {
        try {
            if (ref != null && ref.isValid()) store.removeEntity(ref, com.hypixel.hytale.component.RemoveReason.REMOVE);
        } catch (Throwable t) {
            warnOnce("Не удалось убрать сущность", t);
        }
    }

    /** Насколько выше и ниже опорной высоты искать землю, блоков. */
    private static final int GROUND_UP = 24, GROUND_SCAN = 56;

    /**
     * Высота земли около опорной высоты nearY (обычно высота игрока): y, на котором можно стоять.
     * Ищется настоящий верхний твёрдый блок в столбце (HeightmapColumn.getFloor, как у NPC движка), а не
     * getHeight: в 0.6 карта высот отдавала оценку 79 для всех столбцов, и отряды оказывались в горах под
     * землёй. null — чанк не загружен, столбец сплошной (пещера в горе), под ногами вода, листва или ствол.
     */
    public static Double groundY(com.hypixel.hytale.server.core.universe.world.World world, double x, double z, double nearY) {
        try {
            var chunks = world.getChunkStore();
            Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> chunk =
                    chunks.getChunkReference(com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(x, z));
            if (chunk == null || !chunk.isValid()) return null;
            var hm = chunks.getStore().getComponent(chunk,
                    com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn.getComponentType());
            if (hm == null) return null;
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            int lx = com.hypixel.hytale.math.util.ChunkUtil.localCoordinate(bx);
            int lz = com.hypixel.hytale.math.util.ChunkUtil.localCoordinate(bz);
            // Сверху вниз: если стартовая точка уже в камне — это гора выше, пробуем повыше ещё раз.
            for (int from = (int) Math.floor(nearY) + GROUND_UP, pass = 0; pass < 2; pass++, from += GROUND_SCAN - 8) {
                int floor = hm.getFloor(chunks, lx, from, lz, GROUND_SCAN);
                if (floor == Integer.MIN_VALUE) return null;
                if (floor >= from) continue;
                return standable(chunks, bx, floor, bz) ? (double) (floor + 1) : null;
            }
            return null;
        } catch (Throwable t) {
            warnOnce("Не удалось узнать высоту земли", t);
            return null;
        }
    }

    /** Твёрдый блок, не листва и не ствол, и над ним нет воды. */
    private static boolean standable(com.hypixel.hytale.server.core.universe.world.storage.ChunkStore chunks,
                                     int x, int y, int z) {
        var store = chunks.getStore();
        var section = chunks.getChunkSectionReferenceAtBlock(x, y, z);
        if (section == null || !section.isValid()) return false;
        var blocks = store.getComponent(section,
                com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection.getComponentType());
        if (blocks == null) return false;
        int lx = com.hypixel.hytale.math.util.ChunkUtil.localCoordinate(x), lz = com.hypixel.hytale.math.util.ChunkUtil.localCoordinate(z);
        var type = com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap()
                .getAsset(blocks.get(lx, y, lz));
        if (type == null || type.getMaterial() != com.hypixel.hytale.protocol.BlockMaterial.Solid) return false;
        String id = type.getId() == null ? "" : type.getId();
        if (id.contains("Leaves") || id.contains("Trunk") || id.contains("Branch")) return false;
        var above = chunks.getChunkSectionReferenceAtBlock(x, y + 1, z);
        if (above != null && above.isValid()) {
            var fluids = store.getComponent(above,
                    com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection.getComponentType());
            if (fluids != null && fluids.getFluidId(lx, y + 1, lz) != 0) return false;
        }
        return true;
    }

    /** Какие модели уже записаны в лог со списком анимаций (чтобы подобрать имена в settings.json). */
    private static final Set<String> loggedModels = new HashSet<>();

    /**
     * Анимация из списка кандидатов, которая есть у модели сущности: сначала точное совпадение имени,
     * потом имя, содержащее кандидата («Attack» найдёт «AttackSwing»). null — подходящей нет.
     */
    public static String pickAnimation(Store<EntityStore> store, Ref<EntityStore> ref, List<String> candidates) {
        try {
            if (ref == null || !ref.isValid() || candidates == null || candidates.isEmpty()) return null;
            ModelComponent mc = store.getComponent(ref, ModelComponent.getComponentType());
            if (mc == null || mc.getModel() == null) return null;
            Set<String> ids = mc.getModel().getAnimationSetMap().keySet();
            String model = mc.getModel().getModelAssetId();
            if (loggedModels.add(model)) JrpgPlugin.get().log("Анимации модели " + model + ": " + ids);
            for (String want : candidates) for (String id : ids) if (id.equalsIgnoreCase(want)) return id;
            for (String want : candidates) {
                String w = want.toLowerCase();
                for (String id : ids) if (id.toLowerCase().contains(w)) return id;
            }
        } catch (Throwable t) {
            warnOnce("Не удалось прочитать анимации модели", t);
        }
        return null;
    }

    /** Проиграть анимацию (из кандидатов) в слоте Action. true — нашлась и отправлена. */
    public static boolean animate(Store<EntityStore> store, Ref<EntityStore> ref, List<String> candidates) {
        String id = pickAnimation(store, ref, candidates);
        if (id == null) return false;
        try {
            AnimationUtils.playAnimation(ref, AnimationSlot.Action, id, true, store);
            return true;
        } catch (Throwable t) {
            warnOnce("Анимация " + id + " не сработала", t);
            return false;
        }
    }

    private static final java.util.Random RND = new java.util.Random();

    /**
     * Анимация «со своим характером»: случайная из own (свои имена моба/героя, точные совпадения),
     * чтобы удары не были одинаковыми; если ни одной нет у модели — первая подходящая из общего списка.
     */
    public static boolean animate(Store<EntityStore> store, Ref<EntityStore> ref, List<String> own, List<String> common) {
        return animateIn(store, ref, AnimationSlot.Action, own, common) != null;
    }

    /** То же в нужном слоте (Status держит позу, например «лежит»). Возвращает имя анимации или null. */
    public static String animateIn(Store<EntityStore> store, Ref<EntityStore> ref, AnimationSlot slot,
                                   List<String> own, List<String> common) {
        String id = null;
        if (own != null && !own.isEmpty()) {
            List<String> have = new java.util.ArrayList<>();
            for (String want : own) {
                String found = pickAnimation(store, ref, List.of(want));
                if (found != null && found.equalsIgnoreCase(want) && !have.contains(found)) have.add(found);
            }
            if (!have.isEmpty()) id = have.get(RND.nextInt(have.size()));
        }
        if (id == null) id = pickAnimation(store, ref, common);
        if (id == null) return null;
        try {
            AnimationUtils.playAnimation(ref, slot, id, true, store);
            return id;
        } catch (Throwable t) {
            warnOnce("Анимация " + id + " не сработала", t);
            return null;
        }
    }

    /** Снять анимацию слота (встать после «лежит», вернуть обычную позу). */
    public static void stopAnimation(Store<EntityStore> store, Ref<EntityStore> ref, AnimationSlot slot) {
        try {
            if (ref != null && ref.isValid()) AnimationUtils.stopAnimation(ref, slot, true, store);
        } catch (Throwable t) {
            warnOnce("Не удалось снять анимацию", t);
        }
    }

    /** Частица на высоте груди бойца (pos — точка у ног). */
    public static boolean particleAt(String name, Vector3d feet, double up, Ref<EntityStore> viewer, Store<EntityStore> store) {
        if (feet == null) return false;
        return particle(name, new Vector3d(feet.x, feet.y + up, feet.z), viewer, store);
    }

    /** Поворот (yaw, радианы), чтобы смотреть из from в to. В Hytale yaw = 0 смотрит на -Z. */
    public static float yawTowards(Vector3d from, Vector3d to) {
        return (float) Math.atan2(-(to.x - from.x), -(to.z - from.z));
    }
}
