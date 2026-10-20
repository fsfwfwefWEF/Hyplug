package dev.jrpg.hytale;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Objects;

/**
 * HUD катсцены (Common/UI/Custom/JrpgBattle/Cutscene.ui). Именно HUD, а не окно: пока открыто окно, клиент
 * прячет HUD и показывает курсор, а каждое обновление окна ждёт подтверждения (так делает и Viewfinder:
 * полосы и подсказки — в HUD). Состояние задаёт {@link Cutscene}, раз в тик сцены {@link #frame} шлёт
 * клиенту только то, что изменилось. Анимации — шагами: высота полос, слой затемнения (#Fade1..10),
 * подъём титров. С сервера меняются только проверенные свойства: .Text, .Visible, .Style.TextColor, .Anchor.
 */
final class CutsceneHud extends CustomUIHud {
    static final String KEY = "jrpg_cutscene";
    static final String LAYOUT = "JrpgBattle/Cutscene.ui";
    static final int CHOICES = 4;

    // ---- что должно быть на экране (пишет Cutscene в потоке мира) ----
    double barsTarget, fadeTarget;
    long barsMs = 600, fadeMs = 800;
    long flashUntil;
    String title = "", titleSub = "";
    long titleFrom, titleUntil;
    String subWho = "", subText = "", subColor = "#e8c46a";
    long subUntil;
    boolean dialog;
    String dlgName = "", dlgColor = "#e8c46a", dlgText = "";
    int dlgShown;
    List<String> choices = List.of();
    int selected;
    boolean nextMark;
    String hint = "";

    // ---- что уже отправлено ----
    private double bars, fade;
    private long lastFrame;
    private int sentBars = -1, sentFade = -1, sentTitleTop = -1, sentChoices = -1, sentSel = -1;
    private Boolean sentFlash, sentTitle, sentSub, sentDlg, sentNext;
    private String sentTitleText, sentSubText, sentDlgName, sentDlgText, sentHint;
    private final String[] sentChoice = new String[CHOICES];

    CutsceneHud(@Nonnull PlayerRef playerRef) {
        super(playerRef, KEY);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder cmd) {
        cmd.append(LAYOUT);
        // После (пере)сборки всё отправить заново.
        sentBars = sentFade = sentTitleTop = sentChoices = sentSel = -1;
        sentFlash = sentTitle = sentSub = sentDlg = sentNext = null;
        sentTitleText = sentSubText = sentDlgName = sentDlgText = sentHint = null;
        java.util.Arrays.fill(sentChoice, null);
        render(cmd, System.currentTimeMillis());
    }

    /** Кадр: плавно довести полосы и затемнение до цели и отправить изменения. */
    void frame(long now) {
        UICommandBuilder cmd = new UICommandBuilder();
        if (render(cmd, now)) {
            try {
                update(false, cmd);
            } catch (Throwable t) {
                Fx.warnOnce("HUD катсцены не обновляется", t);
            }
        }
    }

    /** Мгновенно поставить полосы и затемнение (начало сцены из черноты). */
    void snap() {
        bars = barsTarget;
        fade = fadeTarget;
    }

    private boolean render(UICommandBuilder cmd, long now) {
        long dt = lastFrame == 0 ? 0 : Math.min(200, now - lastFrame);
        lastFrame = now;
        bars = approach(bars, barsTarget, dt, barsMs);
        fade = approach(fade, fadeTarget, dt, fadeMs);
        boolean changed = false;

        int barH = (int) Math.round(UiAnim.easeInOut(bars) * Cutscene.settings().barsHeight);
        if (barH != sentBars) {
            sentBars = barH;
            Anchor top = new Anchor();
            top.setLeft(Value.of(0));
            top.setRight(Value.of(0));
            top.setTop(Value.of(0));
            top.setHeight(Value.of(barH));
            cmd.setObject("#BarTop.Anchor", top);
            Anchor bot = new Anchor();
            bot.setLeft(Value.of(0));
            bot.setRight(Value.of(0));
            bot.setBottom(Value.of(0));
            bot.setHeight(Value.of(barH));
            cmd.setObject("#BarBot.Anchor", bot);
            changed = true;
        }
        int layer = (int) Math.round(fade * 10);
        if (layer != sentFade) {
            for (int k = 1; k <= 10; k++) {
                boolean was = sentFade == k, now2 = layer == k;
                if (sentFade < 0 || was != now2) cmd.set("#Fade" + k + ".Visible", now2);
            }
            sentFade = layer;
            changed = true;
        }
        boolean flash = now < flashUntil;
        if (!Objects.equals(sentFlash, flash)) {
            cmd.set("#Flash.Visible", flash);
            sentFlash = flash;
            changed = true;
        }

        // Титры: проявляются подъёмом снизу (0.5 с), висят до titleUntil.
        boolean showTitle = !title.isEmpty() && now >= titleFrom && now < titleUntil;
        if (!Objects.equals(sentTitle, showTitle)) {
            cmd.set("#TitleBox.Visible", showTitle);
            sentTitle = showTitle;
            changed = true;
        }
        if (showTitle) {
            String t = title + "\n" + titleSub;
            if (!t.equals(sentTitleText)) {
                cmd.set("#Title.Text", title);
                cmd.set("#TitleSub.Text", titleSub);
                sentTitleText = t;
                changed = true;
            }
            double k = Math.min(1, (now - titleFrom) / 500.0);
            int top = 400 - (int) Math.round(UiAnim.easeOut(k) * 30);
            if (top != sentTitleTop) {
                Anchor a = new Anchor();
                a.setLeft(Value.of(0));
                a.setRight(Value.of(0));
                a.setTop(Value.of(top));
                a.setHeight(Value.of(120));
                cmd.setObject("#TitleBox.Anchor", a);
                sentTitleTop = top;
                changed = true;
            }
        }

        // Субтитры (не реплика в окне — строка внизу, поверх нижней полосы).
        boolean showSub = !subText.isEmpty() && (subUntil == 0 || now < subUntil) && !dialog;
        if (!Objects.equals(sentSub, showSub)) {
            cmd.set("#SubBox.Visible", showSub);
            sentSub = showSub;
            changed = true;
        }
        if (showSub) {
            String t = subWho + "\u0001" + subText + "\u0001" + subColor;
            if (!t.equals(sentSubText)) {
                cmd.set("#SubWho.Text", subWho);
                cmd.set("#SubWho.Style.TextColor", subColor);
                cmd.set("#SubText.Text", subText);
                sentSubText = t;
                changed = true;
            }
        }

        // Окно реплики.
        if (!Objects.equals(sentDlg, dialog)) {
            cmd.set("#DlgRow.Visible", dialog);
            sentDlg = dialog;
            changed = true;
        }
        if (dialog) {
            String n = dlgName + "\u0001" + dlgColor;
            if (!n.equals(sentDlgName)) {
                cmd.set("#DlgName.Text", dlgName);
                cmd.set("#DlgName.Style.TextColor", dlgColor);
                sentDlgName = n;
                changed = true;
            }
            String shown = dlgText.substring(0, Math.max(0, Math.min(dlgShown, dlgText.length())));
            if (!shown.equals(sentDlgText)) {
                cmd.set("#DlgText.Text", shown);
                sentDlgText = shown;
                changed = true;
            }
            int nc = Math.min(CHOICES, choices.size());
            if (nc != sentChoices) {
                for (int i = 0; i < CHOICES; i++) cmd.set("#Ch" + i + ".Visible", i < nc);
                sentChoices = nc;
                sentSel = -1;
                changed = true;
            }
            for (int i = 0; i < nc; i++) {
                String c = choices.get(i);
                if (!c.equals(sentChoice[i])) {
                    cmd.set("#Ch" + i + "Text.Text", c);
                    sentChoice[i] = c;
                    changed = true;
                }
            }
            if (nc > 0 && selected != sentSel) {
                for (int i = 0; i < nc; i++) {
                    cmd.set("#Ch" + i + "Sel.Visible", i == selected);
                    cmd.set("#Ch" + i + "Mark.Text", i == selected ? ">" : String.valueOf(i + 1));
                    cmd.set("#Ch" + i + "Text.Style.TextColor", i == selected ? "#ffe9a8" : "#c9d1dc");
                }
                sentSel = selected;
                changed = true;
            }
            // Мигающая стрелка «дальше», когда текст допечатан и выбора нет.
            boolean next = nextMark && nc == 0 && (now / 450) % 2 == 0;
            if (!Objects.equals(sentNext, next)) {
                cmd.set("#DlgNextBox.Visible", next);
                sentNext = next;
                changed = true;
            }
        }
        if (!hint.equals(sentHint)) {
            cmd.set("#Hint.Text", hint);
            sentHint = hint;
            changed = true;
        }
        return changed;
    }

    /** Линейно к цели: полный ход 0→1 за ms. */
    private static double approach(double cur, double target, long dt, long ms) {
        if (ms <= 0) return target;
        double step = dt / (double) ms;
        if (cur < target) return Math.min(target, cur + step);
        if (cur > target) return Math.max(target, cur - step);
        return cur;
    }
}
