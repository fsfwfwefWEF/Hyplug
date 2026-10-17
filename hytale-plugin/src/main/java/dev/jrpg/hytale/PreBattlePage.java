package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jrpg.battle.Battle;
import dev.jrpg.battle.EnemyTemplate;
import dev.jrpg.battle.Encounter;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Random;

/**
 * Окно перед боем (PreBattle.ui): кто впереди, шанс побега, кнопки «Сражаться» и «Сбежать».
 * Сражаться — обычный бой с облётом камеры; сбежать — бросок шанса: удалось — встреча не тронет
 * игрока ещё немного, нет — бой начинается засадой (враги ходят первыми). Закрыть окно (Esc) = попытка сбежать.
 */
public final class PreBattlePage extends InteractiveCustomUIPage<BattleMenuPage.Click> {
    public static final String LAYOUT = "JrpgBattle/PreBattle.ui";
    private static final int MAX = 5;

    /** Что известно о встрече: где будет арена и откуда «отталкивать» игрока при удачном побеге. */
    public record Setup(Encounter encounter, Vector3d center, Vector3d dir, Vector3d origin, double radius,
                        double chance, Runnable beforeBattle) {}

    private final JrpgPlugin plugin;
    private final Setup setup;
    private final Random random = new Random();
    private boolean decided;
    private String hover = "";

    public PreBattlePage(@Nonnull PlayerRef playerRef, JrpgPlugin plugin, Setup setup) {
        super(playerRef, CustomPageLifetime.CanDismiss, BattleMenuPage.Click.CODEC);
        this.plugin = plugin;
        this.setup = setup;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(LAYOUT);
        bind(evt, CustomUIEventBindingType.Activating, "#Fight", "fight", 0);
        bind(evt, CustomUIEventBindingType.Activating, "#Flee", "flee", 1);
        bind(evt, CustomUIEventBindingType.MouseEntered, "#Fight", "hover", 0);
        bind(evt, CustomUIEventBindingType.MouseEntered, "#Flee", "hover", 1);
        bind(evt, CustomUIEventBindingType.MouseExited, "#Fight", "unhover", 0);
        bind(evt, CustomUIEventBindingType.MouseExited, "#Flee", "unhover", 1);
        fill(cmd);
    }

    private static void bind(UIEventBuilder evt, CustomUIEventBindingType type, String selector, String action, int index) {
        evt.addEventBinding(type, selector,
                new EventData().append("Action", action).append("Index", String.valueOf(index)), false);
    }

    private void fill(UICommandBuilder cmd) {
        Encounter e = setup.encounter();
        boolean boss = e.enemies().stream().anyMatch(s -> plugin.data().enemy(s.template()).boss());
        cmd.set("#Title.Text", (boss ? "Босс: " : "Впереди враги: ") + e.name());
        cmd.set("#Sub.Text", "Сейчас начнётся битва! Можно сразиться или попробовать сбежать.");
        List<Encounter.Spot> spots = e.enemies();
        for (int i = 0; i < MAX; i++) {
            boolean shown = i < spots.size();
            cmd.set("#En" + i + ".Visible", shown);
            if (!shown) continue;
            EnemyTemplate t = plugin.data().enemy(spots.get(i).template());
            cmd.set("#En" + i + "Name.Text", t.name());
            cmd.set("#En" + i + "Hp.Text", (t.boss() ? "БОСС  " : "") + "HP " + t.stats().maxHp());
            if (plugin.settings().uiIcons) {
                String key = BattleMenuPage.iconKey("portrait:" + t.id());
                cmd.clear("#En" + i + "Icon");
                cmd.append("#En" + i + "Icon", BattleMenuPage.ICONS + key + "_m.ui");
            }
        }
        int pct = (int) Math.round(setup.chance() * 100);
        cmd.set("#Chance.Text", setup.chance() <= 0
                ? "От этого боя не сбежать."
                : "Шанс побега: " + pct + "%. Если не выйдет — враги нападут первыми.");
        cmd.set("#FleeBox.Visible", setup.chance() > 0);
        cmd.set("#Flee.Text", "Сбежать (" + pct + "%)");
        cmd.set("#FightBoxGlow.Visible", hover.equals("0"));
        cmd.set("#FleeBoxGlow.Visible", hover.equals("1"));
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull BattleMenuPage.Click data) {
        if (decided) return;
        switch (data.action == null ? "" : data.action) {
            case "fight" -> begin(ref, store, Battle.Opening.NORMAL);
            case "flee" -> tryFlee(ref, store);
            case "hover" -> {
                hover = data.index == null ? "" : data.index;
                refresh();
            }
            case "unhover" -> {
                if (hover.equals(data.index)) hover = "";
                refresh();
            }
            default -> { }
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (decided) return;
        // Закрыл окно — значит, пытается уйти. От неубегаемых боёв окно не спасает.
        World world = store.getExternalData().getWorld();
        world.execute(() -> {
            if (setup.chance() > 0) tryFlee(ref, store);
            else begin(ref, store, Battle.Opening.NORMAL);
        });
    }

    private void refresh() {
        UICommandBuilder cmd = new UICommandBuilder();
        fill(cmd);
        Pages.update(this, playerRef, cmd);
    }

    private void tryFlee(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (decided || setup.chance() <= 0) return;
        if (random.nextDouble() < setup.chance()) {
            decided = true;
            plugin.endPrompt(playerRef.getUuid());
            plugin.dodge(playerRef.getUuid(), setup.encounter().id());
            closeQuietly();
            pushAway(ref, store);
            playerRef.sendMessage(Message.raw("Удалось уйти незамеченным."));
        } else {
            playerRef.sendMessage(Message.raw("Побег не удался! Враги нападают первыми."));
            begin(ref, store, Battle.Opening.AMBUSH);
        }
    }

    private void begin(Ref<EntityStore> ref, Store<EntityStore> store, Battle.Opening opening) {
        if (decided) return;
        decided = true;
        plugin.endPrompt(playerRef.getUuid());
        World world = store.getExternalData().getWorld();
        // Это окно не закрываем: меню боя сразу встанет на его место. Если закрыть окно и тут же
        // открыть другое, клиент не подтверждает закрытие и сервер перестаёт принимать нажатия (0.6.0).
        world.execute(() -> {
            if (!ref.isValid()) return;
            if (setup.beforeBattle() != null) setup.beforeBattle().run();
            String err = BattleSession.start(plugin, setup.encounter(), playerRef, ref, store, world,
                    setup.center(), opening, setup.dir());
            if (err != null) {
                playerRef.sendMessage(Message.raw(err));
                closeQuietly();
            }
        });
    }

    /** Удачный побег: игрок оказывается чуть за границей зоны встречи, спиной к ней. */
    private void pushAway(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (setup.origin() == null || !ref.isValid()) return;
        Vector3d p = Fx.position(store, ref), o = setup.origin();
        double dx = p.x - o.x, dz = p.z - o.z, len = Math.hypot(dx, dz);
        if (len < 0.01) {
            dx = 0;
            dz = -1;
            len = 1;
        }
        double r = setup.radius() + 3;
        Vector3d to = new Vector3d(o.x + dx / len * r, p.y, o.z + dz / len * r);
        Double y = Fx.groundY(store.getExternalData().getWorld(), to.x, to.z, p.y);
        if (y != null && Math.abs(y - p.y) < 6) to = new Vector3d(to.x, y, to.z);
        Fx.teleportPlayer(store, ref, to, Fx.yawTowards(o, to));
    }

    private void closeQuietly() {
        try {
            Pages.close(this, playerRef);
        } catch (Throwable t) {
            plugin.warn("Не удалось закрыть окно перед боем", t);
        }
    }
}
