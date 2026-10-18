package dev.jrpg.hytale;

import com.hypixel.hytale.server.core.Message;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Раскраска текста для окон боя: в одной строке разные цвета (Label.TextSpans = Message с кусками).
 *
 * Описания способностей и строки журнала пишутся обычным текстом, а цвета ставит словарь: числа и ходы —
 * золотом, MP — голубым, AP — оранжевым, стихии и статусы — цветом стихии, имена героев и врагов — своими
 * цветами, «названия» в кавычках — светлым, подписи «Цель:», «Урон:» — приглушённым, первая строка без
 * двоеточия (художественное описание) — курсивом. Две палитры: для тёмных окон и для пергамента книги.
 */
final class RichText {
    /** Палитра: обычный текст, подпись поля, число, MP, AP, плюс, минус, свои, враги, название, пояснение. */
    record Palette(String text, String field, String number, String mp, String ap, String plus, String minus,
                   String party, String enemy, String name, String flavor, Map<String, String> elements) {}

    static final Palette DARK = new Palette("#e9eef6", "#8fa6c4", "#f0c75e", "#7fb8f0", "#ffb35a", "#7fe39a",
            "#ff7f6e", "#a9d8ff", "#ff9c8a", "#f3e7c9", "#aab6c8", elements(false));

    static final Palette PARCHMENT = new Palette("#3a2a1a", "#7a6040", "#8a5a0c", "#1f5f9a", "#a0520c", "#2f7a2c",
            "#a32a22", "#1f4f86", "#8e2626", "#2a1c10", "#6e5a40", elements(true));

    /** Основа слова → цвет (стихии, статусы, эффекты). Ищется по началу слова без учёта регистра. */
    private static Map<String, String> elements(boolean ink) {
        Map<String, String> m = new LinkedHashMap<>();
        String fire = ink ? "#b5470c" : "#ff9b5a", ice = ink ? "#1d6aa6" : "#8fd8ff", water = ink ? "#1c5aa8" : "#6fb7ff",
                thunder = ink ? "#7a5a00" : "#ffe46a", earth = ink ? "#7a5226" : "#d7b27a", poison = ink ? "#3f7a1c" : "#a8e35a",
                blood = ink ? "#9b1c1c" : "#ff6a6a", holy = ink ? "#2f7a2c" : "#7fe39a", stun = ink ? "#806000" : "#ffd23f",
                dark = ink ? "#5a2a80" : "#c9a0ff", wind = ink ? "#2a7a6a" : "#7fe3d0";
        for (String s : List.of("огн", "огон", "пламен", "горен", "пожар")) m.put(s, fire);
        for (String s : List.of("лёд", "лед", "льд", "ледян", "мороз", "озноб", "заморо", "холод", "метел")) m.put(s, ice);
        for (String s : List.of("вод", "мокр", "волн", "ливн")) m.put(s, water);
        for (String s : List.of("гром", "молни", "электр", "заряд", "разряд")) m.put(s, thunder);
        for (String s : List.of("земл", "камн", "камен", "землетряс", "трещин")) m.put(s, earth);
        for (String s : List.of("яд", "отрав", "токсин")) m.put(s, poison);
        for (String s : List.of("кров", "кровотеч")) m.put(s, blood);
        for (String s : List.of("лечен", "исцел", "реген", "восстан")) m.put(s, holy);
        for (String s : List.of("оглуш", "паутин", "замедл")) m.put(s, stun);
        for (String s : List.of("тьм", "проклят", "провок")) m.put(s, dark);
        for (String s : List.of("ускор", "ветер", "ветр")) m.put(s, wind);
        return m;
    }

    // Числа со своими единицами: «+12 HP», «-8%», «130%», «3 хода», «12 MP», «2 AP», «x1.5».
    private static final Pattern TOKEN = Pattern.compile(
            "([+\\-−]?\\d+(?:[.,]\\d+)?\\s?%?(?:\\s?(?:HP|MP|AP|ход(?:а|ов)?|урона))?|x\\d+(?:[.,]\\d+)?|«[^»]+»|[\\p{L}]+)");

    private RichText() {
    }

    /**
     * Многострочный текст (описание способности, осмотр). names — имена бойцов: свои → party, враги → enemy.
     * Строка «Подпись: значение» красит подпись приглушённо; первая строка без двоеточия — курсив (если flavor).
     */
    static Message describe(String text, Palette p, Collection<String> partyNames, Collection<String> enemyNames,
                            boolean firstLineFlavor) {
        List<Message> parts = new ArrayList<>();
        String[] lines = text == null ? new String[0] : text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) parts.add(Message.raw("\n"));
            String line = lines[i];
            if (i == 0 && firstLineFlavor && !line.contains(":") && lines.length > 1) {
                parts.add(Message.raw(line).color(p.flavor()).italic(true));
                continue;
            }
            int colon = line.indexOf(": ");
            if (colon > 0 && colon < 28 && !startsWithName(line, partyNames, enemyNames)) {
                parts.add(Message.raw(line.substring(0, colon + 1)).color(p.field()));
                spans(parts, line.substring(colon + 1), p, p.text(), partyNames, enemyNames);
            } else {
                spans(parts, line, p, p.text(), partyNames, enemyNames);
            }
        }
        return Message.join(parts.toArray(new Message[0]));
    }

    /** Одна строка с основным цветом base (строка журнала). */
    static Message line(String text, Palette p, String base, Collection<String> partyNames, Collection<String> enemyNames) {
        List<Message> parts = new ArrayList<>();
        spans(parts, text, p, base, partyNames, enemyNames);
        return Message.join(parts.toArray(new Message[0]));
    }

    /** Строки подряд через перевод строки (страница книги). */
    static Message lines(List<Message> lines) {
        List<Message> parts = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) parts.add(Message.raw("\n"));
            parts.add(lines.get(i));
        }
        return parts.isEmpty() ? Message.raw("") : Message.join(parts.toArray(new Message[0]));
    }

    private static boolean startsWithName(String line, Collection<String> a, Collection<String> b) {
        for (String n : a) if (!n.isEmpty() && line.startsWith(n + ":")) return true;
        for (String n : b) if (!n.isEmpty() && line.startsWith(n + ":")) return true;
        return false;
    }

    private static void spans(List<Message> out, String text, Palette p, String base,
                              Collection<String> partyNames, Collection<String> enemyNames) {
        if (text == null || text.isEmpty()) return;
        // имена бойцов могут быть из нескольких слов: размечаем их заранее
        List<int[]> nameRanges = new ArrayList<>();
        List<String> nameColors = new ArrayList<>();
        markNames(text, partyNames, p.party(), nameRanges, nameColors);
        markNames(text, enemyNames, p.enemy(), nameRanges, nameColors);
        StringBuilder plain = new StringBuilder();
        int pos = 0;
        while (pos < text.length()) {
            int named = rangeAt(nameRanges, pos);
            if (named >= 0) {
                flush(out, plain, base);
                int[] r = nameRanges.get(named);
                out.add(Message.raw(text.substring(r[0], r[1])).color(nameColors.get(named)).bold(true));
                pos = r[1];
                continue;
            }
            Matcher m = TOKEN.matcher(text);
            if (!m.find(pos) || m.start() != pos) {
                plain.append(text.charAt(pos++));
                continue;
            }
            String tok = m.group(1);
            String color = colorOf(tok, p);
            if (color == null) {
                plain.append(tok);
            } else {
                flush(out, plain, base);
                Message seg = Message.raw(tok).color(color);
                if (tok.startsWith("«")) seg = seg.bold(true);
                out.add(seg);
            }
            pos = m.end();
        }
        flush(out, plain, base);
    }

    private static void markNames(String text, Collection<String> names, String color, List<int[]> ranges, List<String> colors) {
        for (String n : names) {
            if (n == null || n.length() < 2) continue;
            int from = 0;
            while ((from = text.indexOf(n, from)) >= 0) {
                int end = from + n.length();
                boolean wordStart = from == 0 || !Character.isLetter(text.charAt(from - 1));
                if (wordStart && rangeAt(ranges, from) < 0) {
                    ranges.add(new int[]{from, end});
                    colors.add(color);
                }
                from = end;
            }
        }
    }

    private static int rangeAt(List<int[]> ranges, int pos) {
        for (int i = 0; i < ranges.size(); i++) if (ranges.get(i)[0] == pos) return i;
        return -1;
    }

    private static void flush(List<Message> out, StringBuilder plain, String base) {
        if (plain.length() == 0) return;
        out.add(Message.raw(plain.toString()).color(base));
        plain.setLength(0);
    }

    /** Цвет куска или null (обычный текст). */
    static String colorOf(String tok, Palette p) {
        if (tok.startsWith("«")) return p.name();
        char c0 = tok.charAt(0);
        if (Character.isDigit(c0) || c0 == '+' || c0 == '-' || c0 == '−' || (c0 == 'x' && tok.length() > 1 && Character.isDigit(tok.charAt(1)))) {
            if (tok.endsWith("MP")) return p.mp();
            if (tok.endsWith("AP")) return p.ap();
            if (tok.endsWith("HP")) return c0 == '+' ? p.plus() : (c0 == '-' || c0 == '−') ? p.minus() : p.number();
            if (tok.endsWith("урона")) return p.minus();
            return p.number();
        }
        if (tok.equals("MP")) return p.mp();
        if (tok.equals("AP")) return p.ap();
        String low = tok.toLowerCase();
        for (Map.Entry<String, String> e : p.elements().entrySet()) {
            String stem = e.getKey();
            // короткие основы («яд», «пар») — только целым словом или с окончанием до 3 букв
            if (low.startsWith(stem) && (stem.length() >= 4 || low.length() <= stem.length() + 3)) return e.getValue();
        }
        return null;
    }
}
