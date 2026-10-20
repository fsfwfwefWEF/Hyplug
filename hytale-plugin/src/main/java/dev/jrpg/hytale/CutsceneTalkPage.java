package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Окно реплики катсцены (CutsceneTalk.ui = Cutscene.ui + невидимые кнопки, собирает tools/gen_battle_ui.py).
 * В 0.13.0 клики в катсцене шли только через события мира (начало взаимодействия, кнопки мыши), и в игре они
 * не дошли: реплики закрывались лишь по таймеру, на выборе сцена вставала. Кнопки окна доходят всегда, поэтому
 * на время реплики открывается это окно: клик в любом месте — «дальше», клик по ответу — этот ответ.
 * Пока окно открыто, клиент прячет HUD, поэтому всё, что было в HUD катсцены (полосы, затемнение, реплика),
 * рисуется тут же — CutsceneHud шлёт кадры сюда. Esc — как ПКМ (дважды — пропустить сцену).
 */
final class CutsceneTalkPage extends InteractiveCustomUIPage<BattleMenuPage.Click> implements AnimatedPage {
    static final String LAYOUT = "JrpgBattle/CutsceneTalk.ui";

    private final CutsceneHud hud;
    private final long openedAt = System.currentTimeMillis();
    /** Закрываем сами (не Esc игрока). */
    volatile boolean closing;

    CutsceneTalkPage(@Nonnull PlayerRef playerRef, CutsceneHud hud) {
        super(playerRef, CustomPageLifetime.CanDismiss, BattleMenuPage.Click.CODEC);
        this.hud = hud;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(LAYOUT);
        bind(evt, "#Tap", "next", 0);
        bind(evt, "#TapBox", "next", 0);
        for (int i = 0; i < CutsceneHud.CHOICES; i++) bind(evt, "#ChBtn" + i, "pick", i);
        hud.renderAll(cmd);
    }

    private static void bind(UIEventBuilder evt, String selector, String action, int index) {
        evt.addEventBinding(CustomUIEventBindingType.Activating, selector,
                new EventData().append("Action", action).append("Index", String.valueOf(index)), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull BattleMenuPage.Click data) {
        UUID id = playerRef.getUuid();
        if ("pick".equals(data.action)) {
            int i;
            try {
                i = Integer.parseInt(data.index);
            } catch (Exception e) {
                i = 0;
            }
            Cutscene.input(id, Cutscene.Key.PICK, i);
        } else {
            Cutscene.input(id, Cutscene.Key.NEXT, 0);
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        super.onDismiss(ref, store);
        // Esc игрока: как ПКМ (первый раз — подсказка, второй — пропуск). Окно откроется снова, пока идёт реплика.
        if (!closing) Cutscene.input(playerRef.getUuid(), Cutscene.Key.SKIP, 0);
    }

    /** Кадры идут постоянно (печать текста): клики во время них ClickBuffer передаёт окну напрямую. */
    @Override
    public boolean animatingNow() {
        return System.currentTimeMillis() - openedAt > 250;
    }

    /**
     * В 0.13.1 клики терялись: кадры (печать, мигающая стрелка) шли без перерыва, сервер всё время ждал ответа
     * клиента и выбрасывал нажатия, а обход срабатывал не всегда. Теперь все нажатия этого окна — напрямую.
     */
    @Override
    public boolean takeAllClicks() {
        return System.currentTimeMillis() - openedAt > 150;
    }
}
