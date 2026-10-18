"""Рисует рамки, кнопки, баннер хода, плашки очереди, книгу журнала и полоски для меню боя.

Запуск из корня проекта: python3 tools/gen_frames.py [превью.png]
Результат: hytale-plugin/src/main/resources/Common/UI/Custom/JrpgBattle/jr_*.png (рядом с BattleMenu.ui и
BattleHud.ui, путь в разметке — просто имя файла, как у иконок).

Рамки окон и кнопки растягиваются как 9-slice: в разметке Background: (TexturePath: "jr_panel.png", Border: N),
где N = BORDER[имя]. Баннер, книга и плашки очереди нарисованы под свой точный размер.
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import art  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "..", "hytale-plugin", "src", "main", "resources",
                   "Common", "UI", "Custom", "JrpgBattle")
SS = 3

# Ширина неизменяемого края 9-slice для каждой растягиваемой текстуры (в пикселях текстуры = экрана).
BORDER = {"jr_panel": 22, "jr_panel_enemy": 22, "jr_panel_party": 22, "jr_panel_gold": 22,
          "jr_btn": 12, "jr_btn_hover": 12, "jr_btn_press": 12, "jr_btn_off": 12, "jr_bubble": 16, "jr_slot": 8}

BLUE_GLOW = (70, 140, 230)
RED_GLOW = (220, 70, 60)
GOLD_GLOW = (255, 200, 90)


def save(img, name):
    path = os.path.join(OUT, name + ".png")
    img.save(path)
    return img


def ornament(d, cx, cy, r, color, flip_x=1, flip_y=1):
    """Уголок-филигрань: ромб и два завитка вдоль краёв."""
    d.polygon([(cx, cy - r), (cx + r * 0.7, cy), (cx, cy + r), (cx - r * 0.7, cy)], fill=color)
    w = max(1, int(r * 0.22))
    d.line([(cx + flip_x * r * 1.0, cy), (cx + flip_x * r * 2.6, cy)], fill=color, width=w)
    d.line([(cx, cy + flip_y * r * 1.0), (cx, cy + flip_y * r * 2.6)], fill=color, width=w)
    d.ellipse([cx + flip_x * r * 2.6 - w, cy - w, cx + flip_x * r * 2.6 + w, cy + w], fill=color)
    d.ellipse([cx - w, cy + flip_y * r * 2.6 - w, cx + w, cy + flip_y * r * 2.6 + w], fill=color)


def panel(size, glow=BLUE_GLOW, fill_alpha=0.9, corners=True, gold=art.GOLD, glow_strength=0.55, radius=0.09):
    W, H = size[0] * SS, size[1] * SS
    dim = (W, H)
    pad = int(SS * 1)
    shape = art.rounded_mask(dim, pad, int(min(W, H) * radius))
    body = art.vgrad(dim, [(0, (24, 34, 56)), (0.5, (14, 21, 36)), (1, (9, 13, 24))])
    body[..., :3] = np.clip(body[..., :3] + art.noise(dim, 0.018, seed=size[0] + size[1], blur=SS * 0.8)[..., None], 0, 1)
    # внутреннее свечение у края (цвет стороны)
    edge = np.clip(shape - art.erode(shape, SS * 9), 0, 1)
    body = art.add(body, glow, art.blur_mask(edge, SS * 4) * shape, glow_strength)
    out = art.with_alpha(body, shape * fill_alpha)
    # тонкая светлая линия свечения внутри каймы
    inner_line = np.clip(art.erode(shape, SS * 3) - art.erode(shape, SS * 4), 0, 1)
    out = art.add(out, art.lighten(glow, 0.35), inner_line, 0.55)
    ring, _ = art.bevel_ring(dim, shape, SS * 2, base=gold)
    out = art.over(out, ring)
    img = art.img_of(out)
    if corners:
        d = ImageDraw.Draw(img)
        r = SS * 4.2
        col = art.lighten(gold, 0.2) + (255,)
        m = SS * 6
        ornament(d, m, m, r, col, 1, 1)
        ornament(d, W - 1 - m, m, r, col, -1, 1)
        ornament(d, m, H - 1 - m, r, col, 1, -1)
        ornament(d, W - 1 - m, H - 1 - m, r, col, -1, -1)
    return img.resize(size, Image.LANCZOS)


def button(size, top, bottom, rim, glow=None, glow_strength=0.0, alpha=1.0):
    W, H = size[0] * SS, size[1] * SS
    dim = (W, H)
    shape = art.rounded_mask(dim, SS, int(H * 0.22))
    body = art.vgrad(dim, [(0, art.lighten(top, 0.12)), (0.48, top), (0.52, art.darken(top, 0.1)), (1, bottom)])
    if glow:
        body = art.add(body, glow, art.blur_mask(np.clip(shape - art.erode(shape, SS * 6), 0, 1), SS * 3) * shape,
                       glow_strength)
    out = art.with_alpha(body, shape * alpha)
    # блик верхней половины
    x, y = art.coords(dim)
    hi = np.clip(1 - y / 0.5, 0, 1) * art.erode(shape, SS * 2)
    out = art.add(out, (255, 255, 255), hi, 0.07)
    ring, _ = art.bevel_ring(dim, shape, int(SS * 1.6), base=rim)
    out = art.over(out, ring)
    return art.img_of(out).resize(size, Image.LANCZOS)


def banner(size):
    """Баннер «Ход: …»: тёмная полоса, по краям тает, золотые линии сверху и снизу, ромбы по центру."""
    W, H = size[0] * SS, size[1] * SS
    dim = (W, H)
    x, y = art.coords(dim)
    fade = np.clip(np.minimum(x, 1 - x) / 0.22, 0, 1) ** 1.3
    body = art.vgrad(dim, [(0, (16, 24, 42)), (0.5, (10, 16, 30)), (1, (6, 10, 20))])
    body = art.add(body, (60, 110, 200), np.exp(-((y - 0.5) ** 2) / 0.08) * fade, 0.18)
    out = art.with_alpha(body, fade * 0.92)
    line = Image.new("L", dim, 0)
    ld = ImageDraw.Draw(line)
    for yy in (SS * 3, H - SS * 4):
        ld.rectangle([0, yy, W, yy + SS * 1.4], fill=255)
    lm = art.mask_from(line) * fade
    out = art.over(out, art.with_alpha(art.metal(dim, art.GOLD), lm))
    img = art.img_of(out)
    d = ImageDraw.Draw(img)
    col = art.GOLD_HI + (255,)
    for yy in (SS * 3.7, H - SS * 3.3):
        d.polygon([(W / 2, yy - SS * 4), (W / 2 + SS * 7, yy), (W / 2, yy + SS * 4), (W / 2 - SS * 7, yy)], fill=col)
    return img.resize(size, Image.LANCZOS)


def queue_plate(size, side_color, active=False):
    W, H = size[0] * SS, size[1] * SS
    dim = (W, H)
    shape = art.rounded_mask(dim, SS, int(H * 0.2))
    if active:
        body = art.vgrad(dim, [(0, (46, 70, 118)), (1, (20, 32, 60))])
        body = art.add(body, GOLD_GLOW, art.blur_mask(np.clip(shape - art.erode(shape, SS * 5), 0, 1), SS * 3) * shape, 0.5)
    else:
        body = art.vgrad(dim, [(0, (26, 36, 58)), (1, (12, 18, 32))])
    x, y = art.coords(dim)
    # цветная полоса стороны слева
    stripe = (x < (5 * SS) / W).astype(np.float32) * shape
    body = art.add(body, side_color, art.blur_mask(stripe, SS * 1.5), 0.9)
    body = art.add(body, side_color, np.clip(1 - x / 0.5, 0, 1) * shape, 0.12)
    out = art.with_alpha(body, shape * (0.95 if active else 0.85))
    ring, _ = art.bevel_ring(dim, shape, int(SS * (1.8 if active else 1.1)),
                             base=art.GOLD if active else (110, 128, 160))
    out = art.over(out, ring)
    return art.img_of(out).resize(size, Image.LANCZOS)


def parchment(dim, seed=3):
    """Пергамент: тёплый цвет, пятна, волокна."""
    base = art.vgrad(dim, [(0, (240, 226, 192)), (1, (226, 206, 164))])
    n1 = art.noise(dim, 0.05, seed=seed, blur=dim[0] * 0.03)
    n2 = art.noise(dim, 0.025, seed=seed + 1, blur=1.2)
    base[..., :3] = np.clip(base[..., :3] + (n1 + n2)[..., None] * np.array([1.0, 0.92, 0.75]), 0, 1)
    return base


def book(size):
    """Раскрытая книга журнала: кожаная обложка, две страницы, тень корешка, металлические уголки."""
    W, H = size[0] * SS, size[1] * SS
    dim = (W, H)
    cover = art.rounded_mask(dim, SS * 2, SS * 12)
    leather = art.vgrad(dim, [(0, (104, 62, 34)), (1, (62, 34, 18))])
    leather[..., :3] = np.clip(leather[..., :3] + art.noise(dim, 0.05, seed=9, blur=SS * 1.2)[..., None], 0, 1)
    out = art.with_alpha(leather, cover)
    # тиснёная рамка на коже
    emb = np.clip(art.erode(cover, SS * 5) - art.erode(cover, SS * 6), 0, 1)
    out = art.add(out, (200, 150, 80), emb, 0.35)
    # страницы
    m = SS * 12
    pages = Image.new("L", dim, 0)
    pd = ImageDraw.Draw(pages)
    pd.rounded_rectangle([m, m, W / 2 - SS * 2, H - m], radius=SS * 4, fill=255)
    pd.rounded_rectangle([W / 2 + SS * 2, m, W - m, H - m], radius=SS * 4, fill=255)
    pm = art.mask_from(pages)
    # стопка страниц снизу (несколько линий)
    stack = Image.new("L", dim, 0)
    sd = ImageDraw.Draw(stack)
    for k in range(3):
        o = SS * (2 + k * 2)
        sd.rounded_rectangle([m - o * 0.3, m + o, W / 2, H - m + o], radius=SS * 4, fill=255)
        sd.rounded_rectangle([W / 2, m + o, W - m + o * 0.3, H - m + o], radius=SS * 4, fill=255)
    sm = art.mask_from(stack)
    out = art.over(out, art.with_alpha(art.solid(dim, (196, 176, 136)), sm))
    out = art.over(out, art.with_alpha(art.solid(dim, (120, 96, 60)), np.clip(sm - pm, 0, 1) * 0.5))
    paper = parchment(dim)
    x, y = art.coords(dim)
    spine = np.exp(-((x - 0.5) ** 2) / 0.0011)
    paper[..., :3] = np.clip(paper[..., :3] * (1 - 0.38 * spine[..., None]), 0, 1)
    edge = np.clip(1 - np.minimum(np.minimum(x - m / W, (1 - m / W) - x), np.minimum(y - m / H, (1 - m / H) - y)) / 0.05, 0, 1)
    paper[..., :3] = np.clip(paper[..., :3] * (1 - 0.18 * edge[..., None]), 0, 1)
    out = art.over(out, art.with_alpha(paper, pm))
    img = art.img_of(out)
    d = ImageDraw.Draw(img)
    # металлические уголки обложки
    for cx, cy, fx, fy in ((0, 0, 1, 1), (W, 0, -1, 1), (0, H, 1, -1), (W, H, -1, -1)):
        pts = [(cx, cy), (cx + fx * SS * 26, cy), (cx, cy + fy * SS * 26)]
        d.polygon(pts, fill=art.GOLD_MID + (255,))
        d.polygon([(cx + fx * SS * 3, cy + fy * SS * 3), (cx + fx * SS * 20, cy + fy * SS * 3),
                   (cx + fx * SS * 3, cy + fy * SS * 20)], fill=art.GOLD + (255,))
    # ляссе (закладка)
    bx = W * 0.56
    d.polygon([(bx, H - m - SS * 3), (bx + SS * 7, H - m - SS * 3), (bx + SS * 7, H - SS * 1),
               (bx + SS * 3.5, H - SS * 5), (bx, H - SS * 1)], fill=(150, 30, 40, 255))
    return img.resize(size, Image.LANCZOS)


def page(size):
    """Переворачиваемая страница: пергамент с тенью к сгибу (растягивается по ширине при перелистывании)."""
    W, H = size[0] * SS, size[1] * SS
    dim = (W, H)
    paper = parchment(dim, seed=5)
    x, y = art.coords(dim)
    paper[..., :3] = np.clip(paper[..., :3] * (0.72 + 0.3 * x)[..., None], 0, 1)
    shape = art.rounded_mask(dim, SS, SS * 4)
    return art.img_of(art.with_alpha(paper, shape)).resize(size, Image.LANCZOS)


def bar(size, top, bottom):
    W, H = size[0] * SS, size[1] * SS
    dim = (W, H)
    body = art.vgrad(dim, [(0, art.lighten(top, 0.35)), (0.35, top), (1, bottom)])
    return art.img_of(body).resize(size, Image.LANCZOS)


def bar_back(size):
    W, H = size[0] * SS, size[1] * SS
    dim = (W, H)
    body = art.vgrad(dim, [(0, (4, 6, 10)), (1, (22, 28, 40))])
    return art.img_of(body).resize(size, Image.LANCZOS)


def bubble(size):
    """Окно реплики: тёмное, золотая кайма, без уголков-завитков."""
    return panel(size, glow=GOLD_GLOW, fill_alpha=0.94, corners=False, glow_strength=0.25, radius=0.2)


def slot(size):
    W, H = size[0] * SS, size[1] * SS
    dim = (W, H)
    shape = art.rounded_mask(dim, SS, SS * 4)
    body = art.vgrad(dim, [(0, (8, 12, 22)), (1, (20, 28, 44))])
    out = art.with_alpha(body, shape * 0.9)
    ring, _ = art.bevel_ring(dim, shape, SS, base=(96, 112, 140))
    return art.img_of(art.over(out, ring)).resize(size, Image.LANCZOS)


ITEMS = {
    "jr_panel": lambda: panel((96, 96)),
    "jr_panel_enemy": lambda: panel((96, 96), glow=RED_GLOW, glow_strength=0.5),
    "jr_panel_party": lambda: panel((96, 96), glow=BLUE_GLOW, glow_strength=0.7),
    "jr_panel_gold": lambda: panel((96, 96), glow=GOLD_GLOW, glow_strength=0.75),
    "jr_btn": lambda: button((128, 36), (40, 62, 104), (16, 26, 48), art.GOLD_MID),
    "jr_btn_hover": lambda: button((128, 36), (62, 96, 156), (26, 42, 78), art.GOLD, GOLD_GLOW, 0.45),
    "jr_btn_press": lambda: button((128, 36), (22, 34, 60), (40, 60, 100), art.GOLD),
    "jr_btn_off": lambda: button((128, 36), (36, 40, 50), (20, 22, 30), (90, 90, 100), alpha=0.8),
    "jr_banner": lambda: banner((600, 60)),
    "jr_q_cur": lambda: queue_plate((214, 50), GOLD_GLOW, active=True),
    "jr_q_party": lambda: queue_plate((214, 36), BLUE_GLOW),
    "jr_q_enemy": lambda: queue_plate((214, 36), RED_GLOW),
    "jr_book": lambda: book((440, 290)),
    "jr_page": lambda: page((206, 266)),
    "jr_bar_back": lambda: bar_back((64, 8)),
    "jr_bar_hp": lambda: bar((64, 8), (96, 220, 120), (30, 120, 60)),
    "jr_bar_mp": lambda: bar((64, 8), (100, 170, 255), (30, 70, 170)),
    "jr_bar_ehp": lambda: bar((64, 8), (240, 100, 90), (130, 30, 30)),
    "jr_bar_ap": lambda: bar((64, 8), (255, 214, 110), (170, 110, 30)),
    "jr_bubble": lambda: bubble((64, 64)),
    "jr_slot": lambda: slot((32, 32)),
}


def main():
    os.makedirs(OUT, exist_ok=True)
    made = {k: save(f(), k) for k, f in ITEMS.items()}
    print("frames:", len(made), "->", os.path.abspath(OUT))
    if len(sys.argv) > 1:
        preview(made, sys.argv[1])


def preview(made, path):
    """Лист-превью: рамки растянуты как в игре (9-slice), баннер, книга, кнопки."""
    sheet = Image.new("RGBA", (1000, 760), (60, 80, 70, 255))
    # фон «мира» для оценки прозрачности
    g = art.img_of(art.vgrad((1000, 760), [(0, (120, 160, 190)), (0.55, (150, 180, 170)), (0.56, (70, 110, 60)), (1, (40, 70, 40))]))
    sheet.alpha_composite(g)
    def nine(name, w, h):
        src = made[name]
        b = BORDER[name]
        sw, sh = src.size
        out = Image.new("RGBA", (w, h))
        xs = [(0, b, 0, b), (b, sw - b, b, w - b), (sw - b, sw, w - b, w)]
        ys = [(0, b, 0, b), (b, sh - b, b, h - b), (sh - b, sh, h - b, h)]
        for x0, x1, dx0, dx1 in xs:
            for y0, y1, dy0, dy1 in ys:
                if dx1 > dx0 and dy1 > dy0:
                    out.alpha_composite(src.crop((x0, y0, x1, y1)).resize((dx1 - dx0, dy1 - dy0)), (dx0, dy0))
        return out
    sheet.alpha_composite(nine("jr_panel", 300, 200), (20, 20))
    sheet.alpha_composite(nine("jr_panel_enemy", 200, 70), (340, 20))
    sheet.alpha_composite(nine("jr_panel_party", 300, 70), (560, 20))
    sheet.alpha_composite(nine("jr_panel_gold", 300, 70), (560, 100))
    sheet.alpha_composite(made["jr_banner"], (340, 180))
    for i, n in enumerate(("jr_btn", "jr_btn_hover", "jr_btn_press", "jr_btn_off")):
        sheet.alpha_composite(nine(n, 220, 34), (20, 250 + i * 42))
    sheet.alpha_composite(made["jr_q_cur"], (280, 260))
    sheet.alpha_composite(made["jr_q_party"], (280, 316))
    sheet.alpha_composite(made["jr_q_enemy"], (280, 356))
    sheet.alpha_composite(made["jr_book"], (20, 440))
    sheet.alpha_composite(made["jr_page"], (520, 460))
    sheet.alpha_composite(nine("jr_bubble", 260, 70), (520, 260))
    for i, n in enumerate(("jr_bar_hp", "jr_bar_mp", "jr_bar_ehp", "jr_bar_ap")):
        sheet.alpha_composite(made["jr_bar_back"].resize((200, 8)), (740, 460 + i * 20))
        sheet.alpha_composite(made[n].resize((140, 8)), (740, 460 + i * 20))
    sheet.save(path)


if __name__ == "__main__":
    main()
