package dev.jrpg.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jrpg.battle.Encounter;

import javax.annotation.Nonnull;

/**
 * /jrpg start [id] — бой прямо здесь (по умолчанию mill_goblins)
 * /jrpg list — встречи и их координаты
 * /jrpg menu — снова открыть боевое меню
 * /jrpg stop — прервать бой
 * /jrpg reload — перечитать json из папки мода
 * /jrpg particle <имя> — показать частицу под ногами (проверка имён)
 * /jrpg party — меню прокачки партии
 * /jrpg roles <часть имени> — какие роли NPC есть в игре (для enemies.json / settings.json)
 * /jrpg squad <id|random> — поставить отряд из squads.json в 12-16 блоках (проверка)
 * /jrpg squads — отряды вокруг игрока
 */
public final class JrpgCommand extends AbstractCommandCollection {

    public JrpgCommand(JrpgPlugin plugin) {
        super("jrpg", "JRPG-бои");
        addSubCommand(new Start(plugin));
        addSubCommand(new ListCmd(plugin));
        addSubCommand(new Menu(plugin));
        addSubCommand(new Stop(plugin));
        addSubCommand(new Reload(plugin));
        addSubCommand(new Particle(plugin));
        addSubCommand(new Party(plugin));
        addSubCommand(new Roles(plugin));
        addSubCommand(new SquadCmd(plugin));
        addSubCommand(new SquadsCmd(plugin));
    }

    static final class SquadCmd extends Sub {
        private final RequiredArg<String> idArg =
                withRequiredArg("id", "id отряда из squads.json или random", ArgTypes.STRING);

        SquadCmd(JrpgPlugin plugin) {
            super(plugin, "squad", "Поставить отряд рядом (проверка)");
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            String id = idArg.get(ctx);
            SquadSpawner.Squad s = plugin.squads().spawnNear(playerRef, store, world, Fx.position(store, ref),
                    id.equalsIgnoreCase("random") ? null : id, 12, 16, false);
            if (s == null) {
                ctx.sendMessage(Message.raw("Не получилось: нет такого отряда (" + String.join(", ",
                        plugin.data().squads().templates.stream().map(t -> t.id()).toList())
                        + ") или рядом не загружена земля."));
            } else {
                ctx.sendMessage(Message.raw("Отряд «" + s.template.name() + "» стоит в "
                        + Math.round(Math.hypot(s.pos.x - Fx.position(store, ref).x, s.pos.z - Fx.position(store, ref).z))
                        + " блоках. Подойди ближе."));
            }
        }
    }

    static final class SquadsCmd extends Sub {
        SquadsCmd(JrpgPlugin plugin) {
            super(plugin, "squads", "Отряды вокруг");
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            var list = plugin.squads().squadsOf(playerRef.getUuid());
            if (list.isEmpty()) {
                ctx.sendMessage(Message.raw("Рядом отрядов нет" + (plugin.data().squads().enabled ? "." : " (отряды выключены в squads.json).")));
                return;
            }
            var me = Fx.position(store, ref);
            for (var s : list) {
                ctx.sendMessage(Message.raw(String.format("%s @ %.0f %.0f %.0f (%.0f бл.)", s.template.name(),
                        s.pos.x, s.pos.y, s.pos.z, Math.hypot(s.pos.x - me.x, s.pos.z - me.z))));
            }
        }
    }

    private abstract static class Sub extends AbstractPlayerCommand {
        final JrpgPlugin plugin;

        Sub(JrpgPlugin plugin, String name, String desc) {
            super(name, desc);
            this.plugin = plugin;
            requireNoPermission();
        }
    }

    static final class Start extends Sub {
        private final RequiredArg<String> encounterArg =
                withRequiredArg("encounter", "id встречи из encounters.json (см. /jrpg list)", ArgTypes.STRING);

        Start(JrpgPlugin plugin) {
            super(plugin, "start", "Начать бой здесь");
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            String id = encounterArg.get(ctx);
            Encounter e;
            try {
                e = plugin.data().encounter(id);
            } catch (IllegalArgumentException ex) {
                ctx.sendMessage(Message.raw("Нет такой встречи: " + id + ". Список: /jrpg list"));
                return;
            }
            String err = BattleSession.start(plugin, e, playerRef, ref, store, world, null);
            if (err != null) ctx.sendMessage(Message.raw(err));
        }
    }

    static final class ListCmd extends Sub {
        ListCmd(JrpgPlugin plugin) {
            super(plugin, "list", "Список встреч");
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            for (Encounter e : plugin.data().encounters()) {
                boolean open = plugin.progress().isAvailable(e, System.currentTimeMillis());
                ctx.sendMessage(Message.raw(String.format("%s — %s @ %.0f %.0f %.0f%s%s", e.id(), e.name(),
                        e.location().x(), e.location().y(), e.location().z(), e.unique() ? " [БОСС]" : "",
                        open ? "" : " (зачищено)")));
            }
        }
    }

    static final class Menu extends Sub {
        Menu(JrpgPlugin plugin) {
            super(plugin, "menu", "Открыть боевое меню");
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            BattleSession s = plugin.sessions().get(playerRef.getUuid());
            if (s == null) ctx.sendMessage(Message.raw("Ты не в бою."));
            else s.openMenu();
        }
    }

    static final class Stop extends Sub {
        Stop(JrpgPlugin plugin) {
            super(plugin, "stop", "Прервать бой");
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            BattleSession s = plugin.sessions().get(playerRef.getUuid());
            if (s == null) ctx.sendMessage(Message.raw("Ты не в бою."));
            else s.abort("прервано командой");
        }
    }

    static final class Reload extends Sub {
        Reload(JrpgPlugin plugin) {
            super(plugin, "reload", "Перечитать json-файлы мода");
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            plugin.clearSquads();
            ctx.sendMessage(Message.raw(plugin.reloadData()));
        }
    }

    static final class Particle extends Sub {
        private final RequiredArg<String> nameArg =
                withRequiredArg("name", "id системы частиц", ArgTypes.STRING);

        Particle(JrpgPlugin plugin) {
            super(plugin, "particle", "Показать частицу под ногами");
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            String name = nameArg.get(ctx);
            boolean ok = Fx.particle(name, Fx.position(store, ref), ref, store);
            ctx.sendMessage(Message.raw(ok ? "Частица " + name + " отправлена" : "Не удалось: см. лог сервера"));
        }
    }

    static final class Party extends Sub {
        Party(JrpgPlugin plugin) {
            super(plugin, "party", "Меню прокачки партии");
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            if (plugin.sessions().containsKey(playerRef.getUuid())) {
                ctx.sendMessage(Message.raw("Сначала закончи бой."));
                return;
            }
            plugin.openProgression(playerRef, ref, store, null);
        }
    }

    static final class Roles extends Sub {
        private final RequiredArg<String> filterArg =
                withRequiredArg("filter", "часть имени роли, например spider", ArgTypes.STRING);

        Roles(JrpgPlugin plugin) {
            super(plugin, "roles", "Найти роли NPC по части имени");
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            String f = filterArg.get(ctx).toLowerCase(java.util.Locale.ROOT);
            java.util.List<String> all = com.hypixel.hytale.server.npc.NPCPlugin.get().getRoleTemplateNames(true);
            java.util.List<String> hits = all.stream().filter(r -> r.toLowerCase(java.util.Locale.ROOT).contains(f))
                    .sorted().limit(40).toList();
            ctx.sendMessage(Message.raw(hits.isEmpty() ? "Ролей с «" + f + "» нет (всего ролей " + all.size() + ")"
                    : "Роли (" + hits.size() + "): " + String.join(", ", hits)));
            plugin.log("Роли по запросу '" + f + "': " + String.join(", ", hits));
        }
    }
}
