"""Рисует иконки мода (способности, статусы, портреты) в asset pack.

Запуск из корня проекта: python3 tools/gen_icons.py
Результат: hytale-plugin/src/main/resources/Common/UI/Custom/JrpgBattle/Icons/
  <key>.png        картинка
  <key>_s.ui / _m.ui / _l.ui  маленький документ интерфейса с этой картинкой (20/40/88 px),
                   его плагин вставляет в слот иконки (cmd.append).
"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(__file__), "..", "hytale-plugin", "src", "main", "resources",
                   "Common", "UI", "Custom", "JrpgBattle", "Icons")
SS = 4  # суперсэмплинг
SIZES = {"s": 22, "m": 40, "l": 88}

GOLD = (222, 184, 96)
GOLD_D = (120, 88, 36)
WHITE = (250, 246, 236)
INK = (24, 20, 30)


def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def frame(size, top, bottom, border=GOLD, round_=0.22, circle=False):
    S = size * SS
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    grad = Image.new("RGBA", (S, S))
    gd = ImageDraw.Draw(grad)
    for y in range(S):
        gd.line([(0, y), (S, y)], fill=lerp(top, bottom, y / S) + (255,))
    mask = Image.new("L", (S, S), 0)
    md = ImageDraw.Draw(mask)
    pad = int(S * 0.04)
    if circle:
        md.ellipse([pad, pad, S - pad, S - pad], fill=255)
    else:
        md.rounded_rectangle([pad, pad, S - pad, S - pad], radius=int(S * round_), fill=255)
    img.paste(grad, (0, 0), mask)
    d = ImageDraw.Draw(img)
    w = max(SS * 2, int(S * 0.055))
    if circle:
        d.ellipse([pad, pad, S - pad, S - pad], outline=border + (255,), width=w)
        d.ellipse([pad + w, pad + w, S - pad - w, S - pad - w], outline=GOLD_D + (255,), width=max(SS, w // 3))
    else:
        d.rounded_rectangle([pad, pad, S - pad, S - pad], radius=int(S * round_), outline=border + (255,), width=w)
        d.rounded_rectangle([pad + w, pad + w, S - pad - w, S - pad - w], radius=int(S * round_ * 0.8),
                            outline=GOLD_D + (255,), width=max(SS, w // 3))
    # блик сверху
    hl = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    hd = ImageDraw.Draw(hl)
    hd.ellipse([int(S * 0.1), int(-S * 0.45), int(S * 0.9), int(S * 0.38)], fill=(255, 255, 255, 34))
    img = Image.alpha_composite(img, Image.composite(hl, Image.new("RGBA", (S, S)), mask))
    return img


def P(S, pts):
    return [(x * S, y * S) for x, y in pts]


def glow(img, color, S):
    g = Image.new("RGBA", img.size, (0, 0, 0, 0))
    return g


# ---------- глифы (координаты 0..1) ----------

def g_sword(d, S, c=WHITE):
    d.polygon(P(S, [(0.72, 0.18), (0.82, 0.18), (0.82, 0.28), (0.40, 0.70), (0.30, 0.60)]), fill=c)
    d.polygon(P(S, [(0.24, 0.56), (0.30, 0.50), (0.50, 0.70), (0.44, 0.76)]), fill=GOLD)
    d.polygon(P(S, [(0.30, 0.68), (0.36, 0.74), (0.24, 0.86), (0.18, 0.80)]), fill=(140, 96, 60))
    d.ellipse(P(S, [(0.13, 0.81), (0.21, 0.89)]), fill=GOLD)


def g_shield(d, S, c=(170, 196, 230)):
    d.polygon(P(S, [(0.5, 0.16), (0.80, 0.27), (0.76, 0.56), (0.5, 0.86), (0.24, 0.56), (0.20, 0.27)]), fill=c)
    d.polygon(P(S, [(0.5, 0.24), (0.70, 0.32), (0.67, 0.54), (0.5, 0.76)]), fill=(120, 150, 200))
    d.line(P(S, [(0.5, 0.24), (0.5, 0.78)]), fill=GOLD, width=int(S * 0.04))


def g_flame(d, S, outer=(255, 140, 40), inner=(255, 226, 120)):
    d.polygon(P(S, [(0.5, 0.12), (0.62, 0.32), (0.74, 0.30), (0.78, 0.55), (0.70, 0.76), (0.5, 0.88),
                    (0.30, 0.76), (0.22, 0.55), (0.30, 0.38), (0.40, 0.44)]), fill=outer)
    d.polygon(P(S, [(0.5, 0.40), (0.60, 0.58), (0.62, 0.72), (0.5, 0.82), (0.38, 0.72), (0.40, 0.58)]), fill=inner)


def g_snow(d, S, c=(220, 245, 255)):
    w = int(S * 0.055)
    for k in range(3):
        a = math.pi / 3 * k
        x, y = math.cos(a) * 0.34, math.sin(a) * 0.34
        d.line(P(S, [(0.5 - x, 0.5 - y), (0.5 + x, 0.5 + y)]), fill=c, width=w)
        for sgn in (1, -1):
            bx, by = 0.5 + sgn * x * 0.62, 0.5 + sgn * y * 0.62
            for t in (a + math.pi / 4, a - math.pi / 4):
                d.line(P(S, [(bx, by), (bx + sgn * math.cos(t) * 0.12, by + sgn * math.sin(t) * 0.12)]), fill=c, width=w)
    d.ellipse(P(S, [(0.44, 0.44), (0.56, 0.56)]), fill=c)


def g_cross(d, S, c=(140, 255, 160)):
    d.rounded_rectangle(P(S, [(0.40, 0.18), (0.60, 0.82)]), radius=int(S * 0.04), fill=c)
    d.rounded_rectangle(P(S, [(0.18, 0.40), (0.82, 0.60)]), radius=int(S * 0.04), fill=c)


def g_drop(d, S, c=(140, 220, 90), shine=True):
    d.polygon(P(S, [(0.5, 0.14), (0.66, 0.42), (0.34, 0.42)]), fill=c)
    d.ellipse(P(S, [(0.28, 0.34), (0.72, 0.84)]), fill=c)
    if shine:
        d.ellipse(P(S, [(0.38, 0.48), (0.48, 0.60)]), fill=(255, 255, 255, 140))


def g_skull(d, S, c=(200, 255, 150)):
    d.ellipse(P(S, [(0.24, 0.16), (0.76, 0.66)]), fill=c)
    d.rounded_rectangle(P(S, [(0.34, 0.56), (0.66, 0.80)]), radius=int(S * 0.04), fill=c)
    d.ellipse(P(S, [(0.32, 0.36), (0.46, 0.50)]), fill=INK)
    d.ellipse(P(S, [(0.54, 0.36), (0.68, 0.50)]), fill=INK)
    for x in (0.42, 0.50, 0.58):
        d.line(P(S, [(x, 0.66), (x, 0.80)]), fill=INK, width=int(S * 0.025))


def g_star(d, S, c=(255, 230, 120), r1=0.36, r2=0.15, n=5, cx=0.5, cy=0.52):
    pts = []
    for i in range(n * 2):
        r = r1 if i % 2 == 0 else r2
        a = -math.pi / 2 + i * math.pi / n
        pts.append((cx + math.cos(a) * r, cy + math.sin(a) * r))
    d.polygon(P(S, pts), fill=c)


def g_bolt(d, S, c=(255, 236, 90)):
    d.polygon(P(S, [(0.58, 0.10), (0.28, 0.54), (0.48, 0.54), (0.40, 0.90), (0.74, 0.42), (0.54, 0.42), (0.66, 0.10)]), fill=c)


def g_spiral(d, S, c=(200, 170, 255)):
    pts = []
    for i in range(160):
        t = i / 160 * math.pi * 4.2
        r = 0.03 + t / (math.pi * 4.2) * 0.32
        pts.append((0.5 + math.cos(t) * r, 0.5 + math.sin(t) * r))
    d.line(P(S, pts), fill=c, width=int(S * 0.06), joint="curve")


def g_arrows(d, S, c=(140, 230, 255)):
    for dx in (0.0, 0.2):
        d.polygon(P(S, [(0.22 + dx, 0.24), (0.52 + dx, 0.5), (0.22 + dx, 0.76), (0.32 + dx, 0.5)]), fill=c)


def g_snail(d, S, c=(170, 150, 230)):
    d.polygon(P(S, [(0.78 - 0.0, 0.24), (0.48, 0.5), (0.78, 0.76), (0.68, 0.5)]), fill=c)
    d.rounded_rectangle(P(S, [(0.22, 0.44), (0.52, 0.56)]), radius=int(S * 0.03), fill=c)


def g_web(d, S, c=(235, 235, 245)):
    w = int(S * 0.03)
    for k in range(8):
        a = math.pi / 4 * k
        d.line(P(S, [(0.5, 0.5), (0.5 + math.cos(a) * 0.38, 0.5 + math.sin(a) * 0.38)]), fill=c, width=w)
    for r in (0.12, 0.22, 0.32):
        pts = [(0.5 + math.cos(math.pi / 4 * k) * r, 0.5 + math.sin(math.pi / 4 * k) * r) for k in range(9)]
        d.line(P(S, pts), fill=c, width=w)


def g_eye(d, S, c=(255, 220, 130)):
    d.ellipse(P(S, [(0.16, 0.32), (0.84, 0.68)]), fill=c)
    d.ellipse(P(S, [(0.38, 0.36), (0.62, 0.64)]), fill=INK)
    d.ellipse(P(S, [(0.45, 0.42), (0.53, 0.50)]), fill=WHITE)


def g_wave(d, S, c=(240, 170, 90)):
    w = int(S * 0.06)
    for y in (0.36, 0.52, 0.68):
        pts = [(0.16 + i / 40 * 0.68, y + math.sin(i / 40 * math.pi * 3) * 0.05) for i in range(41)]
        d.line(P(S, pts), fill=c, width=w)


def g_bottle(d, S, c=(230, 70, 90)):
    d.rectangle(P(S, [(0.43, 0.14), (0.57, 0.30)]), fill=(200, 170, 120))
    d.ellipse(P(S, [(0.24, 0.30), (0.76, 0.86)]), fill=(230, 235, 245))
    d.ellipse(P(S, [(0.28, 0.44), (0.72, 0.82)]), fill=c)
    d.ellipse(P(S, [(0.34, 0.48), (0.44, 0.58)]), fill=(255, 255, 255, 150))


def g_bomb(d, S):
    d.ellipse(P(S, [(0.20, 0.30), (0.74, 0.84)]), fill=(50, 50, 64))
    d.ellipse(P(S, [(0.30, 0.40), (0.42, 0.52)]), fill=(120, 120, 140))
    d.line(P(S, [(0.6, 0.34), (0.70, 0.20)]), fill=(200, 160, 100), width=int(S * 0.04))
    g_star(d, S, (255, 200, 80), 0.10, 0.04, 6, 0.74, 0.16)


def g_fang(d, S, c=(245, 240, 220)):
    d.polygon(P(S, [(0.30, 0.18), (0.70, 0.18), (0.62, 0.40), (0.50, 0.86), (0.40, 0.40)]), fill=c)
    d.polygon(P(S, [(0.50, 0.60), (0.56, 0.40), (0.50, 0.86)]), fill=(150, 230, 110))


def g_run(d, S, c=WHITE):
    d.ellipse(P(S, [(0.52, 0.14), (0.66, 0.28)]), fill=c)
    w = int(S * 0.07)
    d.line(P(S, [(0.56, 0.30), (0.46, 0.56)]), fill=c, width=w)
    d.line(P(S, [(0.46, 0.56), (0.62, 0.70), (0.58, 0.86)]), fill=c, width=w)
    d.line(P(S, [(0.46, 0.56), (0.34, 0.72), (0.20, 0.72)]), fill=c, width=w)
    d.line(P(S, [(0.52, 0.38), (0.70, 0.46)]), fill=c, width=w)
    d.line(P(S, [(0.52, 0.38), (0.34, 0.40)]), fill=c, width=w)


def g_up(d, S, c=(255, 226, 110)):
    d.polygon(P(S, [(0.5, 0.14), (0.80, 0.48), (0.62, 0.48), (0.62, 0.84), (0.38, 0.84), (0.38, 0.48), (0.20, 0.48)]), fill=c)


def g_cleave(d, S, c=WHITE):
    d.arc(P(S, [(0.14, 0.14), (0.86, 0.86)]), 200, 340, fill=c, width=int(S * 0.08))
    d.arc(P(S, [(0.24, 0.30), (0.76, 0.82)]), 210, 330, fill=(255, 200, 120), width=int(S * 0.05))
    g_sword(d, S)


def g_quake(d, S):
    d.polygon(P(S, [(0.12, 0.62), (0.88, 0.62), (0.88, 0.86), (0.12, 0.86)]), fill=(150, 110, 70))
    d.line(P(S, [(0.5, 0.62), (0.44, 0.72), (0.54, 0.78), (0.48, 0.86)]), fill=INK, width=int(S * 0.04))
    for x in (0.26, 0.5, 0.74):
        d.polygon(P(S, [(x, 0.20), (x + 0.07, 0.40), (x - 0.07, 0.40)]), fill=(240, 200, 140))
        d.rectangle(P(S, [(x - 0.025, 0.40), (x + 0.025, 0.56)]), fill=(240, 200, 140))


def g_focus(d, S):
    g_star(d, S, (255, 236, 140), 0.38, 0.12, 4)
    d.ellipse(P(S, [(0.42, 0.44), (0.58, 0.60)]), fill=WHITE)


def g_regen(d, S):
    g_cross(d, S, (140, 255, 170))
    d.arc(P(S, [(0.10, 0.10), (0.90, 0.90)]), 20, 300, fill=(200, 255, 200), width=int(S * 0.05))


def g_letter(d, S, ch, c=WHITE):
    # Простая «буква» для портретов: рисуем крупный символ встроенным шрифтом с масштабированием
    from PIL import ImageFont
    try:
        f = ImageFont.truetype("DejaVuSans-Bold.ttf", int(S * 0.5))
    except OSError:
        f = ImageFont.load_default()
    bb = d.textbbox((0, 0), ch, font=f)
    d.text(((S - (bb[2] - bb[0])) / 2 - bb[0], (S - (bb[3] - bb[1])) / 2 - bb[1]), ch, font=f, fill=c)


# ---------- портреты ----------

def p_hero(d, S):
    g_sword(d, S)


def p_lyra(d, S):
    d.polygon(P(S, [(0.5, 0.10), (0.70, 0.56), (0.30, 0.56)]), fill=(120, 80, 200))
    d.polygon(P(S, [(0.18, 0.56), (0.82, 0.56), (0.74, 0.64), (0.26, 0.64)]), fill=(150, 110, 230))
    g_star(d, S, (255, 236, 140), 0.12, 0.05, 5, 0.5, 0.38)
    d.ellipse(P(S, [(0.36, 0.62), (0.64, 0.88)]), fill=(240, 210, 180))


def p_bram(d, S):
    g_shield(d, S, (200, 170, 120))


def p_goblin(d, S):
    d.ellipse(P(S, [(0.26, 0.24), (0.74, 0.78)]), fill=(120, 170, 70))
    d.polygon(P(S, [(0.28, 0.40), (0.06, 0.28), (0.26, 0.54)]), fill=(120, 170, 70))
    d.polygon(P(S, [(0.72, 0.40), (0.94, 0.28), (0.74, 0.54)]), fill=(120, 170, 70))
    d.ellipse(P(S, [(0.36, 0.42), (0.46, 0.52)]), fill=(255, 220, 60))
    d.ellipse(P(S, [(0.54, 0.42), (0.64, 0.52)]), fill=(255, 220, 60))
    d.polygon(P(S, [(0.40, 0.64), (0.60, 0.64), (0.50, 0.70)]), fill=WHITE)


def p_spider(d, S, body=(70, 60, 80), eyes=(255, 80, 80), big=False):
    w = int(S * 0.035)
    for side in (-1, 1):
        for k, a in enumerate((0.25, 0.40, 0.58, 0.72)):
            x0, y0 = 0.5 + side * 0.10, 0.50
            x1, y1 = 0.5 + side * 0.30, a
            x2, y2 = 0.5 + side * 0.42, a + 0.16
            d.line(P(S, [(x0, y0), (x1, y1 - 0.1), (x2, y2)]), fill=body, width=w)
    d.ellipse(P(S, [(0.30, 0.44), (0.70, 0.86)]), fill=body)
    d.ellipse(P(S, [(0.36, 0.26), (0.64, 0.52)]), fill=body)
    for ex, ey in ((0.43, 0.34), (0.53, 0.34), (0.46, 0.41), (0.50, 0.41)):
        d.ellipse(P(S, [(ex, ey), (ex + 0.05, ey + 0.05)]), fill=eyes)
    if big:
        d.polygon(P(S, [(0.34, 0.24), (0.40, 0.08), (0.46, 0.20), (0.5, 0.06), (0.54, 0.20), (0.60, 0.08), (0.66, 0.24)]),
                  fill=GOLD)


def p_slime(d, S):
    d.polygon(P(S, [(0.5, 0.20), (0.74, 0.46), (0.84, 0.80), (0.16, 0.80), (0.26, 0.46)]), fill=(90, 200, 230))
    d.ellipse(P(S, [(0.14, 0.60), (0.86, 0.86)]), fill=(90, 200, 230))
    d.ellipse(P(S, [(0.36, 0.48), (0.46, 0.60)]), fill=INK)
    d.ellipse(P(S, [(0.54, 0.48), (0.64, 0.60)]), fill=INK)
    d.ellipse(P(S, [(0.30, 0.30), (0.40, 0.40)]), fill=(255, 255, 255, 150))


ICONS = {
    # способности и действия: (верх, низ, глиф)
    "sword": ("#5b6f8f", "#26304a", g_sword),
    "shield": ("#4f7aa8", "#1f3352", g_shield),
    "magic": ("#7a4fc4", "#2e1a5a", lambda d, S: g_star(d, S, (230, 210, 255))),
    "fire": ("#c4502a", "#4a140c", g_flame),
    "ice": ("#4aa6d6", "#123c5a", g_snow),
    "heal": ("#3fae6a", "#0f3d24", g_cross),
    "poison": ("#5f9e2c", "#1e3a0c", lambda d, S: g_drop(d, S, (170, 240, 90))),
    "stun": ("#c9a227", "#4a3a08", lambda d, S: g_star(d, S, (255, 246, 170), 0.36, 0.12, 4)),
    "haste": ("#2aa1b8", "#0c3a44", g_arrows),
    "regen": ("#2f9e6e", "#0c3a28", g_regen),
    "web": ("#6a6a7e", "#24242e", g_web),
    "focus": ("#c48a2a", "#4a2c08", g_focus),
    "cleave": ("#8a5a3a", "#2e1a10", g_cleave),
    "thunder": ("#5a62c8", "#181c52", g_bolt),
    "quake": ("#8a6a3a", "#2e2210", g_quake),
    "ether": ("#3a6ad0", "#0f2052", lambda d, S: g_bottle(d, S, (80, 140, 255))),
    "potion": ("#b03a52", "#3a0c18", g_bottle),
    "antidote": ("#4a9e5a", "#123a1a", lambda d, S: g_bottle(d, S, (120, 230, 120))),
    "bomb": ("#7a3a2a", "#2a0e08", g_bomb),
    "fang": ("#5a7a3a", "#1e2a10", g_fang),
    "flee": ("#4a5a6a", "#1a2028", g_run),
    "levelup": ("#c99a2a", "#4a3008", g_up),
    "info": ("#3a5a7a", "#121e2a", g_eye),
    # статусы (круглые)
    "st_poison": ("#5f9e2c", "#1e3a0c", lambda d, S: g_skull(d, S)),
    "st_burn": ("#d0602a", "#4a140c", g_flame),
    "st_regen": ("#2f9e6e", "#0c3a28", g_cross),
    "st_haste": ("#2aa1b8", "#0c3a44", g_arrows),
    "st_slow": ("#6a5ab8", "#1e1844", g_snail),
    "st_stun": ("#c9a227", "#4a3a08", g_spiral),
    "st_guard": ("#4f7aa8", "#1f3352", g_shield),
    "st_brood_shield": ("#6a6a7e", "#24242e", g_web),
    "st_charged": ("#c48a2a", "#4a2c08", g_focus),
    # портреты (круглые, крупные)
    "pt_hero": ("#3a5a9a", "#121e3a", p_hero),
    "pt_lyra": ("#6a3aa8", "#220e44", p_lyra),
    "pt_bram": ("#8a6a3a", "#2e2210", p_bram),
    "pt_goblin": ("#4a6a2a", "#16220a", p_goblin),
    "pt_spider": ("#5a2a2a", "#1e0a0a", p_spider),
    "pt_spiderling": ("#4a3a4a", "#1a121a", lambda d, S: p_spider(d, S, (90, 80, 100), (255, 140, 140))),
    "pt_spider_queen": ("#7a1a3a", "#2a0614", lambda d, S: p_spider(d, S, (40, 30, 50), (255, 60, 60), True)),
    "pt_slime": ("#2a6a7a", "#0a222a", p_slime),
    "pt_unknown": ("#4a4a5a", "#18181e", lambda d, S: g_letter(d, S, "?")),
}


def render(key, size=128):
    top, bottom, glyph = ICONS[key]
    circle = key.startswith("st_") or key.startswith("pt_")
    img = frame(size, hexc(top), hexc(bottom), circle=circle)
    S = size * SS
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    glyph(d, S)
    # тень под глифом
    shadow = layer.split()[3].filter(ImageFilter.GaussianBlur(S * 0.02))
    sh = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    sh.putalpha(shadow.point(lambda a: int(a * 0.6)))
    sh = sh.transform(sh.size, Image.AFFINE, (1, 0, -S * 0.015, 0, 1, -S * 0.02))
    img = Image.alpha_composite(img, sh)
    img = Image.alpha_composite(img, layer)
    return img.resize((size, size), Image.LANCZOS)


UI = """Group {{
  Anchor: (Width: {n}, Height: {n});
  Background: (TexturePath: "{key}.png");
}}
"""


def main():
    os.makedirs(OUT, exist_ok=True)
    for key in ICONS:
        size = 128 if key.startswith("pt_") else 64
        render(key, size).save(os.path.join(OUT, key + ".png"))
        for suffix, n in SIZES.items():
            with open(os.path.join(OUT, f"{key}_{suffix}.ui"), "w", encoding="utf-8") as f:
                f.write(UI.format(n=n, key=key))
    # список ключей для плагина (какие иконки существуют)
    idx = os.path.join(os.path.dirname(__file__), "..", "hytale-plugin", "src", "main", "resources", "data", "icons.txt")
    os.makedirs(os.path.dirname(idx), exist_ok=True)
    with open(idx, "w", encoding="utf-8") as f:
        f.write("\n".join(ICONS) + "\n")
    # лист-превью для просмотра всех иконок разом
    keys = list(ICONS)
    cols = 10
    rows = (len(keys) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * 72, rows * 72), (18, 16, 26, 255))
    for i, k in enumerate(keys):
        sheet.alpha_composite(render(k, 64), ((i % cols) * 72 + 4, (i // cols) * 72 + 4))
    preview = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "icons_preview.png")
    sheet.save(preview)
    print("icons:", len(keys), "->", os.path.abspath(OUT))


if __name__ == "__main__":
    main()
