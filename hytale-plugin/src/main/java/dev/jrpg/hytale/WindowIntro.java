package dev.jrpg.hytale;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Появление окон меню (герой, экипировка, рюкзак, сетка, окно перед боем): окно #Window опускается
 * сверху с лёгкой пружинкой. Размер окна читается из его .ui (tools/gen_battle_ui.py → centered).
 */
final class WindowIntro {
    private static final Pattern WINDOW = Pattern.compile(
            "Group #Window \\{\\s*Anchor: \\(Left: 0, Top: 0, Width: (\\d+), Height: (\\d+)\\);");
    private static final Map<String, int[]> SIZES = new ConcurrentHashMap<>();
    private static final int FROM = -70, FRAMES = 7;
    private static final java.util.Set<String> NOTED = ConcurrentHashMap.newKeySet();

    private WindowIntro() {}

    /** Вызывать в конце build(): ставит окно выше места и запускает кадры спуска. */
    static void play(CustomUIPage page, PlayerRef playerRef, JrpgPlugin plugin, Store<EntityStore> store,
                     UICommandBuilder cmd, String layout) {
        if (!plugin.settings().uiAnimations) return;
        int[] wh = size(layout);
        if (wh == null) {
            plugin.log("Появление окна: не нашёл размер #Window в " + layout);
            return;
        }
        if (NOTED.add(layout)) plugin.log("Анимация окна: " + layout + " опускается сверху");
        UiAnim.box(cmd, "#Window", 0, FROM, wh[0], wh[1]);
        World world = store.getExternalData().getWorld();
        for (int k = 1; k <= FRAMES; k++) {
            double t = k / (double) FRAMES;
            plugin.scheduler().schedule(() -> world.execute(() -> {
                UICommandBuilder f = new UICommandBuilder();
                UiAnim.box(f, "#Window", 0, UiAnim.ilerp(FROM, 0, UiAnim.easeOutBack(t)), wh[0], wh[1]);
                Pages.update(page, playerRef, f);
            }), UiAnim.FRAME_MS * k, TimeUnit.MILLISECONDS);
        }
    }

    private static int[] size(String layout) {
        return SIZES.computeIfAbsent(layout, l -> {
            try (InputStream in = WindowIntro.class.getClassLoader().getResourceAsStream("Common/UI/Custom/" + l)) {
                if (in == null) return null;
                Matcher m = WINDOW.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                return m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))} : null;
            } catch (Exception e) {
                return null;
            }
        });
    }
}
