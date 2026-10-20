package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.asset.type.musiccontainer.config.MusicContainer;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jrpg.battle.Encounter;
import dev.jrpg.battle.Progression;
import dev.jrpg.battle.Squads;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Админ-меню (Common/UI/Custom/JrpgBattle/Admin.ui, собирает tools/gen_battle_ui.py): всё, что есть в командах
 * /jrpg, кнопками по разделам — катсцены, сцены навыков, бои, отряды, герои, музыка, система.
 * Каждая строка — главная кнопка, вторая кнопка (не у всех) и подсказка. Список строк раздела строится заново
 * при каждом показе (новые сцены из json видны сразу). Действия в мире (катсцена, сцена навыка) закрывают окно:
 * пока окно открыто, клиент прячет HUD. Бой сам сменяет это окно своим.
 */
public final class AdminPage extends InteractiveCustomUIPage<BattleMenuPage.Click> {
    public static final String LAYOUT = "JrpgBattle/Admin.ui";
    static final String[] TABS = {"Катсцены", "Сцены навыков", "Бои и встречи", "Отряды", "Герои",
            "Музыка и эффекты", "Система"};
    private static final String[] SUBS = {
            "Сцены из cutscenes.json (файл перечитывается сам). «+ бой» — сцена, потом бой её встречи. В сцене: ЛКМ — дальше, колесо или 1–4 — ответ, два раза ПКМ — пропустить.",
            "Сцены навыков из sequences.json: играются перед тобой на трёх мишенях. «Медленно» — в 3 раза медленнее. Шаги и что не нашлось — в логе сервера.",
            "Встречи из encounters.json: начать бой прямо здесь или перенестись к месту встречи.",
            "Случайные отряды из squads.json: поставить рядом (в 12–16 блоках) или убрать все.",
            "Прокачка для проверки: уровень и очки сетки героев. Сохраняется сразу.",
            "Музыка боя (свои треки мода) и частицы, которые мод подобрал для эффектов.",
            "Файлы мода, сохранения, клавиша O и окна героя."};
    private static final int ROWS = 10;

    private record Row(String act, String alt, String hint, Action onAct, Action onAlt) {}

    private interface Action {
        void run(Ref<EntityStore> ref, Store<EntityStore> store);
    }

    private final JrpgPlugin plugin;
    private int tab, page;
    private String status = "";
    private List<Row> rows = List.of();

    public AdminPage(@Nonnull PlayerRef playerRef, JrpgPlugin plugin) {
        super(playerRef, CustomPageLifetime.CanDismiss, BattleMenuPage.Click.CODEC);
        this.plugin = plugin;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(LAYOUT);
        for (int i = 0; i < TABS.length; i++) bind(evt, "#Tab" + i, "tab", i);
        for (int i = 0; i < ROWS; i++) {
            bind(evt, "#Act" + i, "act", i);
            bind(evt, "#Alt" + i, "alt", i);
        }
        bind(evt, "#Prev", "prev", 0);
        bind(evt, "#Next", "next", 0);
        bind(evt, "#Close", "close", 0);
        fill(cmd, store, ref);
        WindowIntro.play(this, playerRef, plugin, store, cmd, LAYOUT);
    }

    private static void bind(UIEventBuilder evt, String selector, String action, int index) {
        evt.addEventBinding(CustomUIEventBindingType.Activating, selector,
                new EventData().append("Action", action).append("Index", String.valueOf(index)), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull BattleMenuPage.Click data) {
        int i;
        try {
            i = Integer.parseInt(data.index == null ? "0" : data.index);
        } catch (NumberFormatException e) {
            i = 0;
        }
        String a = data.action == null ? "" : data.action;
        switch (a) {
            case "close" -> {
                Pages.close(this, playerRef);
                return;
            }
            case "tab" -> {
                tab = Math.max(0, Math.min(TABS.length - 1, i));
                page = 0;
                status = "";
            }
            case "prev" -> page = Math.max(0, page - 1);
            case "next" -> page++;
            case "act", "alt" -> {
                int at = page * ROWS + i;
                if (at < 0 || at >= rows.size()) return;
                Row r = rows.get(at);
                Action act = a.equals("act") ? r.onAct() : r.onAlt();
                if (act == null) return;
                try {
                    act.run(ref, store);
                } catch (Throwable t) {
                    plugin.warn("Админ-меню: действие «" + (a.equals("act") ? r.act() : r.alt()) + "» не сработало", t);
                    status = "Не сработало: " + t.getMessage();
                }
                // Действие могло закрыть окно или сменить его другим — тогда не обновляем.
                if (!Pages.isOpen(this, playerRef)) return;
            }
            default -> {
                return;
            }
        }
        UICommandBuilder cmd = new UICommandBuilder();
        fill(cmd, store, ref);
        Pages.update(this, playerRef, cmd);
    }

    // ---------- показ ----------

    private void fill(UICommandBuilder cmd, Store<EntityStore> store, Ref<EntityStore> ref) {
        try {
            rows = rows(tab, store, ref);
        } catch (Throwable t) {
            plugin.warn("Админ-меню: раздел «" + TABS[tab] + "» не построился", t);
            rows = List.of();
            status = "Раздел не построился: " + t.getMessage();
        }
        int pages = Math.max(1, (rows.size() + ROWS - 1) / ROWS);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int t = 0; t < TABS.length; t++) cmd.set("#Tab" + t + "Mark.Text", t == tab ? ">" : "");
        cmd.set("#SecTitle.Text", TABS[tab]);
        cmd.set("#SecSub.Text", SUBS[tab]);
        for (int k = 0; k < ROWS; k++) {
            int at = page * ROWS + k;
            Row r = at < rows.size() ? rows.get(at) : null;
            cmd.set("#Row" + k + ".Visible", r != null);
            if (r == null) continue;
            cmd.set("#Act" + k + ".Text", r.act());
            boolean alt = r.alt() != null && r.onAlt() != null;
            cmd.set("#AltBox" + k + ".Visible", alt);
            if (alt) cmd.set("#Alt" + k + ".Text", r.alt());
            cmd.set("#Hint" + k + ".Text", r.hint() == null ? "" : r.hint());
        }
        cmd.set("#Page.Text", pages > 1 ? "стр. " + (page + 1) + " из " + pages : "");
        cmd.set("#Prev.Visible", page > 0);
        cmd.set("#Next.Visible", page < pages - 1);
        cmd.set("#Status.Text", status);
    }

    private List<Row> rows(int tab, Store<EntityStore> store, Ref<EntityStore> ref) throws Exception {
        UUID uuid = playerRef.getUuid();
        List<Row> out = new ArrayList<>();
        switch (tab) {
            case 0 -> {
                Map<String, Cutscene.Def> all = Cutscene.defs(plugin);
                out.add(new Row("Показывать заново", null, "Одноразовые сцены (once) снова покажутся у встреч и в зонах.",
                        (r, s) -> {
                            for (String k : all.keySet()) plugin.setFlag(uuid, "cut:" + k, false);
                            status = "Все катсцены снова покажутся.";
                        }, null));
                for (Cutscene.Def d : all.values()) {
                    StringBuilder h = new StringBuilder(d.id());
                    if (d.before() != null) h.append(" · перед встречей ").append(d.before());
                    if (d.zone() != null) h.append(" · по зоне");
                    if (d.once()) h.append(Cutscene.seen(plugin, uuid, d.id()) ? " · уже видел" : " · один раз");
                    out.add(new Row(cut(d.name() == null || d.name().isBlank() ? d.id() : d.name(), 26),
                            d.before() != null ? "+ бой" : null, h.toString(),
                            (r, s) -> command("jrpg cut " + d.id(), true),
                            d.before() != null ? (r, s) -> command("jrpg cut " + d.id() + ":battle", true) : null));
                }
            }
            case 1 -> {
                for (Map.Entry<String, String> sc : Sequences.skillScenes(plugin).entrySet()) {
                    String id = sc.getKey();
                    out.add(new Row(cut(sc.getValue(), 26), "Медленно", "Перед тобой: кастующий и три мишени (" + id + ").",
                            (r, s) -> command("jrpg seq " + id, true), (r, s) -> command("jrpg seq " + id + ":slow", true)));
                }
            }
            case 2 -> {
                out.add(new Row("Сбросить зачистку", null, "Пройденные встречи и боссы снова доступны.", (r, s) -> {
                    plugin.progress().defeatedUnique.clear();
                    plugin.progress().clearedAtMillis.clear();
                    plugin.saveWorld();
                    status = "Встречи и боссы снова доступны.";
                }, null));
                long now = System.currentTimeMillis();
                for (Encounter e : plugin.data().encounters()) {
                    Encounter.Location l = e.location();
                    String hint = String.format("%s · %.0f %.0f %.0f%s%s", e.id(), l.x(), l.y(), l.z(),
                            e.unique() ? " · босс" : "", plugin.progress().isAvailable(e, now) ? "" : " · зачищено");
                    out.add(new Row("Бой: " + cut(e.name(), 24), "Туда", hint, (r, s) -> {
                        World w = s.getExternalData().getWorld();
                        String err = BattleSession.start(plugin, e, playerRef, r, s, w, null);
                        if (err != null) status = err;
                    }, (r, s) -> {
                        Fx.teleportPlayer(s, r, new Vector3d(l.x(), l.y(), l.z() - 8), 0f);
                        status = "Перенёс к встрече «" + e.name() + "».";
                    }));
                }
            }
            case 3 -> {
                out.add(new Row("Случайный отряд", null, "Отряд по весам из squads.json в 12–16 блоках.",
                        (r, s) -> squad(r, s, null), null));
                out.add(new Row("Убрать все отряды", null, "Все отряды в мире исчезнут (появятся снова со временем).",
                        (r, s) -> {
                            plugin.clearSquads();
                            status = "Отряды убраны.";
                        }, null));
                for (Squads.Template t : plugin.data().squads().templates) {
                    out.add(new Row("Отряд: " + cut(t.name(), 22), null, t.id() + " · " + String.join(", ", t.enemies()),
                            (r, s) -> squad(r, s, t.id()), null));
                }
            }
            case 4 -> {
                Progression.Party party = plugin.partyOf(uuid);
                for (Progression.Hero h : party.heroes()) {
                    String hint = String.format("ур. %d · опыт %d/%d · очков сетки %d", h.level, h.exp,
                            Progression.expToNext(h.level), h.points);
                    out.add(new Row("+1 уровень: " + h.def.name(), "+5 очков", hint, (r, s) -> {
                        int ups = h.addExp(Math.max(1, Progression.expToNext(h.level) - h.exp));
                        plugin.savePlayer(uuid);
                        status = ups > 0 ? h.def.name() + ": уровень " + h.level + "." : h.def.name() + ": уровень уже максимальный.";
                    }, (r, s) -> {
                        h.points += 5;
                        plugin.savePlayer(uuid);
                        status = h.def.name() + ": +5 очков сетки (" + h.points + ").";
                    }));
                }
                out.add(new Row("Сбросить сетку всем", null, "Открытые узлы закроются, очки вернутся.", (r, s) -> {
                    for (Progression.Hero h : party.heroes()) h.respec();
                    plugin.savePlayer(uuid);
                    status = "Сетка сброшена, очки возвращены.";
                }, null));
                out.add(new Row("Открыть прокачку", null, "Окно сетки навыков.",
                        (r, s) -> plugin.openProgression(playerRef, r, s, null), null));
            }
            case 5 -> {
                out.add(new Row("Музыка: выкл", null, "Вернуть музыку мира.", (r, s) -> {
                    BattleMusic.stop(plugin, s, r);
                    status = "Музыка мира.";
                }, null));
                TreeSet<String> tracks = new TreeSet<>();
                for (String id : MusicContainer.getAssetMap().getAssetMap().keySet()) {
                    if (id.toLowerCase(java.util.Locale.ROOT).startsWith("jrpg")) tracks.add(id);
                }
                for (String id : tracks) {
                    out.add(new Row(cut(id, 26), null, "Трек мода (music.json выбирает, где он играет).", (r, s) -> {
                        status = BattleMusic.play(plugin, s, r, id) ? "Играет «" + id + "»." : "Трек не найден, см. лог.";
                    }, null));
                }
                out.add(new Row("Частицы боя", null, "Какие частицы мод подобрал для эффектов (ниже, в строке статуса).",
                        (r, s) -> status = ParticleCatalog.summary(), null));
                out.add(new Row("Все частицы в лог", null, "Полный список систем частиц игры — в лог сервера.", (r, s) -> {
                    List<String> ids = new ArrayList<>(ParticleCatalog.ids());
                    java.util.Collections.sort(ids);
                    plugin.log("Частицы игры (" + ids.size() + "): " + String.join(", ", ids));
                    status = "Записал в лог " + ids.size() + " систем частиц.";
                }, null));
            }
            default -> {
                out.add(new Row("Перечитать файлы", null, "enemies, encounters, settings, squads, equipment.json (отряды пропадут).",
                        (r, s) -> {
                            plugin.clearSquads();
                            status = plugin.reloadData();
                        }, null));
                out.add(new Row("Сохранить сейчас", null, "Партия, рюкзак и мир — в папку мода.", (r, s) -> {
                    plugin.savePlayer(uuid);
                    plugin.saveWorld();
                    status = "Сохранено.";
                }, null));
                boolean key = plugin.flag(uuid, GameMenuKey.FLAG, true);
                out.add(new Row(key ? "Клавиша O: меню героя" : "Клавиша O: режим игры", null,
                        "Нажми, чтобы переключить, что делает клавиша режима.", (r, s) -> {
                    plugin.setFlag(uuid, GameMenuKey.FLAG, !key);
                    status = !key ? "Клавиша O открывает меню героя." : "Клавиша O снова меняет режим игры.";
                }, null));
                boolean follow = plugin.flag(uuid, CompanionFollowers.FLAG, true);
                out.add(new Row(follow ? "Компаньоны: идут" : "Компаньоны: ждут", null, "Нажми, чтобы переключить.",
                        (r, s) -> plugin.setFlag(uuid, CompanionFollowers.FLAG, !follow), null));
                out.add(new Row("Меню героя", null, "Окно на клавише O.",
                        (r, s) -> Pages.open(plugin, playerRef, r, s, new MainMenuPage(playerRef, plugin)), null));
                out.add(new Row("Экипировка", null, "Оружие и броня героев.",
                        (r, s) -> plugin.openEquipment(playerRef, r, s, null), null));
                out.add(new Row("Рюкзак", null, "Добыча и зелья.", (r, s) -> plugin.openBackpack(playerRef, r, s), null));
            }
        }
        return out;
    }

    // ---------- действия ----------

    /** Команда /jrpg от имени игрока (как набранная в чате); close — сначала закрыть окно (сцена идёт в HUD). */
    private void command(String command, boolean close) {
        if (close) Pages.close(this, playerRef);
        CommandManager.get().handleCommand(playerRef, command);
    }

    private void squad(Ref<EntityStore> ref, Store<EntityStore> store, String id) {
        World w = store.getExternalData().getWorld();
        Vector3d me = Fx.position(store, ref);
        SquadSpawner.Squad s = plugin.squads().spawnNear(playerRef, store, w, me, id, 12, 16, false);
        status = s == null ? "Не получилось: рядом не загружена земля или отряда нет."
                : "Отряд «" + s.template.name() + "» в " + Math.round(Math.hypot(s.pos.x - me.x, s.pos.z - me.z)) + " блоках.";
    }

    private static String cut(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
