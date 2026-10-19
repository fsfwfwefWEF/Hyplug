package dev.jrpg.hytale;

import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Анимации окна: дорожки, которые каждый кадр пишут в UICommandBuilder (обычно Anchor элемента).
 *
 * Свойств анимации в разметке .ui у клиента нет (кроме спрайтов), поэтому движение — смена Anchor шагами
 * с сервера: окно раз в {@link #FRAME_MS} мс собирает один пакет со всеми активными дорожками.
 * Дорожка с тем же ключом заменяет прежнюю. До старта (задержка) дорожка уже держит своё начальное
 * состояние (t = 0) — так элементы ждут своей очереди спрятанными.
 */
final class UiAnim {
    static final long FRAME_MS = 35;

    interface Frame {
        void apply(UICommandBuilder cmd, double t);
    }

    private static final class Track {
        final long start, dur;
        final Frame frame;
        final Runnable done;
        boolean primed;

        Track(long start, long dur, Frame frame, Runnable done) {
            this.start = start;
            this.dur = dur;
            this.frame = frame;
            this.done = done;
        }
    }

    private final Map<String, Track> tracks = new LinkedHashMap<>();

    /** Дорожка: через delay мс за dur мс t идёт 0→1. done — после последнего кадра. */
    void play(String key, long delay, long dur, Frame frame, Runnable done) {
        tracks.put(key, new Track(System.currentTimeMillis() + Math.max(0, delay), Math.max(1, dur), frame, done));
    }

    void play(String key, long delay, long dur, Frame frame) {
        play(key, delay, dur, frame, null);
    }

    /** Разовое действие через delay мс (смена текста, видимости). */
    void cue(String key, long delay, java.util.function.Consumer<UICommandBuilder> action) {
        play(key, delay, 1, (cmd, t) -> { if (t >= 1) action.accept(cmd); });
    }

    boolean has(String key) { return tracks.containsKey(key); }

    boolean active() { return !tracks.isEmpty(); }

    void clear() { tracks.clear(); }

    /** Записать кадр на момент now; закончившиеся дорожки получают t = 1 и удаляются. */
    void tick(UICommandBuilder cmd, long now) {
        java.util.List<Runnable> done = new ArrayList<>();
        // По копии: кадр дорожки может запустить новую (play/cue) — без копии это ConcurrentModificationException,
        // кадры обрывались, и панели застревали на полпути до следующего обновления окна (0.10.9).
        for (Map.Entry<String, Track> en : new ArrayList<>(tracks.entrySet())) {
            Track tr = en.getValue();
            try {
                step(cmd, now, en.getKey(), tr, done);
            } catch (RuntimeException e) {
                tracks.remove(en.getKey(), tr); // сломанная дорожка не должна крутить кадры вечно
                if (firstError == null) firstError = e;
            }
        }
        done.forEach(Runnable::run);
    }

    private RuntimeException firstError;

    /** Ошибка дорожки с прошлых кадров (дорожка уже снята), или null. */
    RuntimeException takeError() {
        RuntimeException e = firstError;
        firstError = null;
        return e;
    }

    private void step(UICommandBuilder cmd, long now, String key, Track tr, java.util.List<Runnable> done) {
        if (now < tr.start) {
            if (!tr.primed) {
                tr.frame.apply(cmd, 0);
                tr.primed = true;
            }
            return;
        }
        // после старта t > 0 (t = 0 — только ожидание своей очереди)
        double t = Math.max(1e-3, Math.min(1, (now - tr.start) / (double) tr.dur));
        tr.primed = true;
        tr.frame.apply(cmd, t);
        if (t >= 1) {
            tracks.remove(key, tr);
            if (tr.done != null) done.add(tr.done);
        }
    }

    // ---------- помощники ----------

    static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    static int ilerp(int a, int b, double t) { return (int) Math.round(a + (b - a) * t); }

    static double easeOut(double t) { return 1 - Math.pow(1 - t, 3); }

    static double easeIn(double t) { return t * t * t; }

    static double easeInOut(double t) { return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2; }

    /** С небольшим перелётом в конце (выезд «с пружинкой»). */
    static double easeOutBack(double t) {
        double c1 = 1.4, c3 = c1 + 1;
        return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
    }

    /** Anchor с Left/Top/Width/Height. */
    static void box(UICommandBuilder cmd, String id, int left, int top, int w, int h) {
        Anchor a = new Anchor();
        a.setLeft(Value.of(left));
        a.setTop(Value.of(top));
        a.setWidth(Value.of(w));
        a.setHeight(Value.of(h));
        cmd.setObject(id + ".Anchor", a);
    }

    /** Anchor растянутого по ширине элемента (Left 0, Right 0) со сдвигом сверху. */
    static void row(UICommandBuilder cmd, String id, int top, int h) {
        Anchor a = new Anchor();
        a.setLeft(Value.of(0));
        a.setRight(Value.of(0));
        a.setTop(Value.of(top));
        a.setHeight(Value.of(h));
        cmd.setObject(id + ".Anchor", a);
    }

    /** Цвет #rrggbb, смешанный с белым на долю k (0..1) — «вспышка» текста. */
    static String brighten(String hex, double k) {
        try {
            int c = Integer.parseInt(hex.substring(1, 7), 16);
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            r = (int) Math.round(r + (255 - r) * k);
            g = (int) Math.round(g + (255 - g) * k);
            b = (int) Math.round(b + (255 - b) * k);
            return String.format("#%02x%02x%02x", r, g, b);
        } catch (Exception e) {
            return hex;
        }
    }
}
