package dev.hytalemodding.bodycam.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemodding.bodycam.BodyCamera;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /bodycam} — камера «всё тело от первого лица».
 *
 * <pre>
 * /bodycam                 включить / выключить
 * /bodycam on | off        включить / выключить явно
 * /bodycam forward 0.2     сдвинуть камеру вперёд по взгляду (минус — назад)
 * /bodycam up 0.1          сдвинуть камеру вверх (минус — вниз)
 * /bodycam reset           вернуть сдвиги по умолчанию
 * /bodycam info            показать текущие настройки
 * </pre>
 */
public class BodyCamCommand extends AbstractPlayerCommand {

    public static final String NAME = "bodycam";
    public static final List<String> ALIASES = List.of("bc", "камера");

    public BodyCamCommand() {
        super(NAME, "Камера от лица со всем телом: on, off, forward, up, reset, info");
        this.addAliases(ALIASES.toArray(new String[0]));
        // Слова после команды разбираются здесь, чтобы работали и /bodycam, и /bodycam up 0.1.
        this.setAllowsExtraArguments(true);
        // Иначе сервер выдал бы команде отдельное право, и обычные игроки не смогли бы её вызвать.
        this.requireNoPermission();
    }

    @Override
    protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        BodyCamera.Settings settings = BodyCamera.settings(playerRef);
        List<String> arguments = arguments(context.getInputString());

        if (arguments.isEmpty()) {
            settings.enabled = !settings.enabled;
            BodyCamera.apply(playerRef);
            reply(context, settings.enabled ? "Камера со всем телом включена." : "Обычная камера.");
            return;
        }

        String action = arguments.get(0).toLowerCase(Locale.ROOT);
        switch (action) {
            case "on", "вкл" -> {
                settings.enabled = true;
                BodyCamera.apply(playerRef);
                reply(context, "Камера со всем телом включена.");
            }
            case "off", "выкл" -> {
                settings.enabled = false;
                BodyCamera.apply(playerRef);
                reply(context, "Обычная камера.");
            }
            case "forward", "вперед", "вперёд" -> {
                Float value = number(context, arguments, BodyCamera.MAX_FORWARD);
                if (value != null) {
                    settings.forward = value;
                    settings.enabled = true;
                    BodyCamera.apply(playerRef);
                    reply(context, "Сдвиг вперёд: " + format(value) + ".");
                }
            }
            case "up", "вверх" -> {
                Float value = number(context, arguments, BodyCamera.MAX_UP);
                if (value != null) {
                    settings.up = value;
                    settings.enabled = true;
                    BodyCamera.apply(playerRef);
                    reply(context, "Сдвиг вверх: " + format(value) + ".");
                }
            }
            case "reset", "сброс" -> {
                settings.forward = BodyCamera.DEFAULT_FORWARD;
                settings.up = BodyCamera.DEFAULT_UP;
                settings.enabled = true;
                BodyCamera.apply(playerRef);
                reply(context, "Сдвиги камеры сброшены.");
            }
            case "info", "инфо" -> reply(context, "Камера: " + (settings.enabled ? "со всем телом" : "обычная")
                    + ", вперёд " + format(settings.forward) + ", вверх " + format(settings.up) + ".");
            default -> usage(context);
        }
    }

    /** Число после слова: /bodycam up 0.1. null — если его нет или оно не подходит. */
    private static Float number(CommandContext context, List<String> arguments, float limit) {
        if (arguments.size() < 2) {
            usage(context);
            return null;
        }
        float value;
        try {
            value = Float.parseFloat(arguments.get(1).replace(',', '.'));
        } catch (NumberFormatException error) {
            reply(context, "Нужно число, например 0.2 или -0.1.");
            return null;
        }
        if (Float.isNaN(value) || Math.abs(value) > limit) {
            reply(context, "Число должно быть от -" + format(limit) + " до " + format(limit) + ".");
            return null;
        }
        return value;
    }

    /** Слова после имени команды. */
    static List<String> arguments(String input) {
        List<String> words = new ArrayList<>();
        if (input == null) {
            return words;
        }
        words.addAll(Arrays.asList(input.trim().split("\\s+")));
        words.removeIf(String::isEmpty);
        if (!words.isEmpty()) {
            String first = words.get(0).toLowerCase(Locale.ROOT);
            if (first.startsWith("/")) {
                first = first.substring(1);
            }
            if (first.equals(NAME) || ALIASES.contains(first)) {
                words.remove(0);
            }
        }
        return words;
    }

    private static String format(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static void usage(CommandContext context) {
        reply(context, "/bodycam — вкл/выкл; /bodycam on|off; /bodycam forward <число>; "
                + "/bodycam up <число>; /bodycam reset; /bodycam info");
    }

    private static void reply(CommandContext context, String text) {
        context.sendMessage(Message.raw("[BodyCam] " + text));
    }
}
