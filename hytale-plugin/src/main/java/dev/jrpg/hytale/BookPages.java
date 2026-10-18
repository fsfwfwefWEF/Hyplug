package dev.jrpg.hytale;

import java.util.ArrayList;
import java.util.List;

/**
 * Журнал боя по страницам книги: строка занимает столько строк страницы, сколько символов / PAGE_CHARS;
 * когда правая страница полна, начинается новая (книга «перелистывается» сама). Показываются две последние.
 */
final class BookPages {
    /** Символов в строке и строк на странице (шрифт 13, ширина страницы 176). */
    static final int PAGE_CHARS = 25, PAGE_LINES = 11;

    private BookPages() {
    }

    static List<List<BattleSession.LogEntry>> paginate(List<BattleSession.LogEntry> entries) {
        List<List<BattleSession.LogEntry>> pages = new ArrayList<>();
        pages.add(new ArrayList<>());
        int used = 0;
        for (BattleSession.LogEntry e : entries) {
            int lines = Math.max(1, (e.text().length() + PAGE_CHARS - 1) / PAGE_CHARS);
            if (used + lines > PAGE_LINES && !pages.get(pages.size() - 1).isEmpty()) {
                pages.add(new ArrayList<>());
                used = 0;
            }
            pages.get(pages.size() - 1).add(e);
            used += lines;
        }
        return pages;
    }
}
