package dev.jrpg.hytale;

/** Окно, которое шлёт кадры анимации: ClickBuffer передаёт ему клики, пока сервер ждёт ответа на кадр. */
interface AnimatedPage {
    /** Кадры шли только что, и окно открыто не только что. */
    boolean animatingNow();

    /** Все нажатия окна передавать напрямую, даже когда сервер ответа не ждёт (окно реплики катсцены). */
    default boolean takeAllClicks() { return false; }
}
