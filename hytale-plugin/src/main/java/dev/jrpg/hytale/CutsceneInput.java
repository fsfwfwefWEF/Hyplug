package dev.jrpg.hytale;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.protocol.InteractionChainData;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.event.InteractionChainStartEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Управление катсценой без окна (приём из Viewfinder): клики игрока приходят как начало цепочки
 * взаимодействия; пока идёт сцена, цепочка отменяется (игрок не машет оружием) и превращается в ввод сцены.
 * ЛКМ (Primary) и «Использовать» (Use) — дальше, ПКМ (Secondary) — пропуск, колесо мыши и цифры — смена
 * ячейки хотбара (SwapFrom): ±1 от текущей ячейки = прокрутка ответа, другая ячейка = ответ с этим номером.
 */
final class CutsceneInput extends EntityEventSystem<EntityStore, InteractionChainStartEvent> {

    CutsceneInput() {
        super(InteractionChainStartEvent.class);
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                       CommandBuffer<EntityStore> cb, InteractionChainStartEvent e) {
        try {
            PlayerRef p = store.getComponent(chunk.getReferenceTo(index), PlayerRef.getComponentType());
            if (p == null || !Cutscene.playing(p.getUuid())) return;
            e.setCancelled(true);
            switch (e.getType()) {
                case Primary, Use -> Cutscene.input(p.getUuid(), Cutscene.Key.NEXT, 0);
                case Secondary -> Cutscene.input(p.getUuid(), Cutscene.Key.SKIP, 0);
                case SwapFrom -> {
                    InteractionChainData data = e.getChain().getChainData();
                    int held = e.getContext().getHeldItemSlot();
                    if (data == null || held < 0 || data.targetSlot == held) return;
                    int d = Math.floorMod(data.targetSlot - held, 9);
                    if (d == 1) Cutscene.input(p.getUuid(), Cutscene.Key.WHEEL, 1);
                    else if (d == 8) Cutscene.input(p.getUuid(), Cutscene.Key.WHEEL, -1);
                    else Cutscene.input(p.getUuid(), Cutscene.Key.PICK, data.targetSlot);
                }
                default -> { }
            }
        } catch (Throwable t) {
            Fx.warnOnce("Ввод катсцены", t);
        }
    }
}
