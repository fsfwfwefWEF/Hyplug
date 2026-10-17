package dev.jrpg.hytale;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
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
import dev.jrpg.battle.BattleMenu;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Боевое меню: Common/UI/Custom/JrpgBattle/BattleMenu.ui.
 * Содержимое берётся из {@link BattleSession} (модель меню — {@link BattleMenu} в ядре).
 * 6 универсальных кнопок #Opt0..#Opt5 + #Back: на каждом экране меню меняются только их подписи.
 */
public final class BattleMenuPage extends InteractiveCustomUIPage<BattleMenuPage.Click> {
    public static final String LAYOUT = "JrpgBattle/BattleMenu.ui";
    public static final int BUTTONS = 6;

    private final BattleSession session;

    public BattleMenuPage(@Nonnull PlayerRef playerRef, BattleSession session) {
        super(playerRef, CustomPageLifetime.CanDismiss, Click.CODEC);
        this.session = session;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(LAYOUT);
        for (int i = 0; i < BUTTONS; i++) {
            evt.addEventBinding(CustomUIEventBindingType.Activating, "#Opt" + i,
                    new EventData().append("Action", "opt").append("Index", String.valueOf(i)), false);
        }
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#Back",
                new EventData().append("Action", "back").append("Index", "-1"), false);
        fill(cmd);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Click data) {
        if ("back".equals(data.action)) session.onBack();
        else if ("opt".equals(data.action)) session.onOption(parse(data.index));
    }

    /** Обновить содержимое без пересборки страницы. */
    public void refresh() {
        UICommandBuilder cmd = new UICommandBuilder();
        fill(cmd);
        sendUpdate(cmd, false);
    }

    public void closePage() {
        close();
    }

    private void fill(UICommandBuilder cmd) {
        BattleSession.View v = session.view();
        cmd.set("#PartyInfo.Text", v.party());
        cmd.set("#EnemyInfo.Text", v.enemies());
        cmd.set("#Queue.Text", v.queue());
        cmd.set("#Log.Text", v.log());
        cmd.set("#MenuTitle.Text", v.title());
        cmd.set("#Error.Text", v.error() == null ? "" : v.error());
        List<BattleMenu.Option> opts = v.options();
        for (int i = 0; i < BUTTONS; i++) {
            boolean shown = i < opts.size();
            cmd.set("#Opt" + i + ".Visible", shown);
            if (shown) {
                BattleMenu.Option o = opts.get(i);
                cmd.set("#Opt" + i + ".Text", o.enabled() ? o.label() : o.label() + "  (нельзя)");
            }
        }
        cmd.set("#Back.Visible", v.canGoBack());
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Данные нажатия кнопки (ключи совпадают с EventData.append в build). */
    public static final class Click {
        public static final BuilderCodec<Click> CODEC = BuilderCodec.builder(Click.class, Click::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (c, v) -> c.action = v, c -> c.action).add()
                .append(new KeyedCodec<>("Index", Codec.STRING), (c, v) -> c.index = v, c -> c.index).add()
                .build();
        String action;
        String index;

        public Click() {
        }
    }
}
