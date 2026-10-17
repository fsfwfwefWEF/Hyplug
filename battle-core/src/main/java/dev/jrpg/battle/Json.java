package dev.jrpg.battle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Минимальный JSON-парсер (объекты → Map, массивы → List, числа → Double) и запись обратно ({@link #write}).
 * Свой, чтобы мод не зависел от версии Gson внутри сервера Hytale.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.error("лишние символы");
        return v;
    }

    private Object value() {
        if (i >= s.length()) throw error("неожиданный конец");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek('}')) { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, value());
            ws();
            if (peek(',')) { i++; continue; }
            expect('}');
            return m;
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek(']')) { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (peek(',')) { i++; continue; }
            expect(']');
            return l;
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw error("незакрытая строка");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                default -> b.append(e);
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw error("ожидалось значение");
        return Double.parseDouble(s.substring(start, i));
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) throw error("ожидалось " + word);
        i += word.length();
        return v;
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) i++;
            else if (s.startsWith("//", i)) { while (i < s.length() && s.charAt(i) != '\n') i++; }
            else break;
        }
    }

    private boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }

    private void expect(char c) {
        if (!peek(c)) throw error("ожидалось '" + c + "'");
        i++;
    }

    private IllegalArgumentException error(String msg) {
        int line = 1;
        for (int k = 0; k < Math.min(i, s.length()); k++) if (s.charAt(k) == '\n') line++;
        return new IllegalArgumentException("JSON: " + msg + " (строка " + line + ")");
    }

    // ---------- помощники для чтения ----------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? List.of() : (List<Object>) v;
    }

    public static List<String> strings(Map<String, Object> m, String k) {
        return list(m, k).stream().map(String::valueOf).toList();
    }

    public static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : String.valueOf(v);
    }

    public static double num(Map<String, Object> m, String k, double def) {
        Object v = m.get(k);
        return v == null ? def : ((Number) v).doubleValue();
    }

    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        return v == null ? def : (Boolean) v;
    }

    // ---------- запись (сохранения) ----------

    /** Map/List/String/Number/Boolean/null → JSON с отступами. */
    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o, "");
        return sb.append('\n').toString();
    }

    private static void write(StringBuilder sb, Object o, String indent) {
        String in = indent + "  ";
        if (o == null) sb.append("null");
        else if (o instanceof String str) quote(sb, str);
        else if (o instanceof Boolean b) sb.append(b);
        else if (o instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (o instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            int k = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append(in);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), in);
                sb.append(++k < m.size() ? ",\n" : "\n");
            }
            sb.append(indent).append('}');
        } else if (o instanceof Iterable<?> it) {
            List<Object> l = new ArrayList<>();
            it.forEach(l::add);
            boolean flat = l.stream().allMatch(x -> !(x instanceof Map) && !(x instanceof Iterable));
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append(flat ? "[" : "[\n");
            for (int k = 0; k < l.size(); k++) {
                if (!flat) sb.append(in);
                write(sb, l.get(k), in);
                if (k + 1 < l.size()) sb.append(flat ? ", " : ",\n");
            }
            sb.append(flat ? "]" : "\n" + indent + "]");
        } else quote(sb, String.valueOf(o));
    }

    private static void quote(StringBuilder sb, String str) {
        sb.append('"');
        for (char c : str.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
