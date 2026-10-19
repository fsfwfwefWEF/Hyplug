package dev.jrpg.hytale;

/** Окно, которое шлёт кадры анимации: ClickBuffer передаёт ему клики, пока сервер ждёт ответа на кадр. */
interface AnimatedPage {
    /** Кадры шли только что, и окно открыто не только что. */
    boolean animatingNow();
}
