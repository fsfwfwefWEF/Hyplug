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

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import art  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "..", "hytale-plugin", "src", "main", "resources",
                   "Common", "UI", "Custom", "JrpgBattle", "Icons")
SS = 3  # суперсэмплинг
SIZES = {"s": 22, "r": 30, "m": 40, "g": 48, "l": 88}

GOLD = (222, 184, 96)
GOLD_D = (120, 88, 36)
WHITE = (250, 246, 236)
INK = (24, 20, 30)


def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def P(S, pts):
    return [(x * S, y * S) for x, y in pts]


# ---------- глифы (координаты 0..1) ----------

def g_sword(d, S, c=WHITE):
    # клинок: светлая и тёмная грани, дол посередине
    d.polygon(P(S, [(0.80, 0.14), (0.86, 0.14), (0.86, 0.20), (0.41, 0.69), (0.31, 0.59)]), fill=(236, 240, 250))
    d.polygon(P(S, [(0.86, 0.14), (0.86, 0.20), (0.41, 0.69), (0.36, 0.64)]), fill=(150, 162, 188))
    d.line(P(S, [(0.80, 0.21), (0.42, 0.59)]), fill=(120, 136, 170), width=int(S * 0.018))
    # гарда с камнем
    d.polygon(P(S, [(0.20, 0.55), (0.27, 0.48), (0.52, 0.73), (0.45, 0.80)]), fill=(232, 186, 90))
    d.polygon(P(S, [(0.20, 0.55), (0.23, 0.52), (0.48, 0.77), (0.45, 0.80)]), fill=(150, 104, 40))
    d.ellipse(P(S, [(0.315, 0.595), (0.405, 0.685)]), fill=(70, 150, 255))
    d.ellipse(P(S, [(0.33, 0.61), (0.36, 0.64)]), fill=(220, 240, 255))
    # рукоять с обмоткой и навершием
    d.polygon(P(S, [(0.31, 0.71), (0.37, 0.77), (0.24, 0.90), (0.18, 0.84)]), fill=(110, 70, 40))
    for t in (0.25, 0.5, 0.75):
        x, y = 0.34 - 0.13 * t, 0.74 + 0.13 * t
        d.line(P(S, [(x - 0.03, y - 0.03), (x + 0.03, y + 0.03)]), fill=(70, 42, 22), width=int(S * 0.015))
    d.ellipse(P(S, [(0.11, 0.84), (0.21, 0.94)]), fill=(232, 186, 90))


def g_shield(d, S, c=(170, 196, 230)):
    rim = (232, 186, 90)
    d.polygon(P(S, [(0.5, 0.12), (0.83, 0.25), (0.79, 0.57), (0.5, 0.90), (0.21, 0.57), (0.17, 0.25)]), fill=rim)
    d.polygon(P(S, [(0.5, 0.18), (0.77, 0.29), (0.73, 0.55), (0.5, 0.82), (0.27, 0.55), (0.23, 0.29)]), fill=c)
    darker = tuple(int(v * 0.68) for v in c[:3])
    d.polygon(P(S, [(0.5, 0.18), (0.77, 0.29), (0.73, 0.55), (0.5, 0.82)]), fill=darker)
    d.polygon(P(S, [(0.5, 0.30), (0.58, 0.46), (0.5, 0.66), (0.42, 0.46)]), fill=rim)


def flame_shape(cx, top, w, h, n=48, lick=0.0):
    pts = []
    for i in range(n + 1):
        t = i / n * math.pi * 2
        x = math.sin(t)
        y = -math.cos(t)
        # капля: узкий верх, круглый низ, язычки по бокам
        r = (1 - 0.55 * max(0.0, y)) * (1 + lick * math.sin(t * 3))
        pts.append((cx + x * w * r, top + h * (0.5 - 0.5 * y * (1.1 if y > 0 else 0.9))))
    return pts


def g_flame(d, S, outer=(232, 70, 24), inner=(255, 226, 120)):
    d.polygon(P(S, flame_shape(0.5, 0.10, 0.31, 0.80, lick=0.08)), fill=outer)
    d.polygon(P(S, flame_shape(0.47, 0.26, 0.22, 0.62, lick=0.06)), fill=(255, 140, 36))
    d.polygon(P(S, flame_shape(0.52, 0.44, 0.13, 0.44)), fill=(255, 210, 90))
    d.polygon(P(S, flame_shape(0.52, 0.58, 0.065, 0.28)), fill=(255, 250, 220))
    for ex, ey, r in ((0.26, 0.24, 0.025), (0.74, 0.32, 0.02), (0.68, 0.14, 0.015)):
        d.ellipse(P(S, [(ex - r, ey - r), (ex + r, ey + r)]), fill=(255, 200, 90))


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
    dark = tuple(int(v * 0.62) for v in c[:3])
    d.rounded_rectangle(P(S, [(0.39, 0.17), (0.61, 0.83)]), radius=int(S * 0.05), fill=dark)
    d.rounded_rectangle(P(S, [(0.17, 0.39), (0.83, 0.61)]), radius=int(S * 0.05), fill=dark)
    d.rounded_rectangle(P(S, [(0.41, 0.19), (0.59, 0.79)]), radius=int(S * 0.04), fill=c)
    d.rounded_rectangle(P(S, [(0.19, 0.41), (0.79, 0.59)]), radius=int(S * 0.04), fill=c)
    d.rounded_rectangle(P(S, [(0.44, 0.21), (0.50, 0.40)]), radius=int(S * 0.02), fill=(240, 255, 240))
    d.rounded_rectangle(P(S, [(0.21, 0.44), (0.40, 0.50)]), radius=int(S * 0.02), fill=(240, 255, 240))


def g_drop(d, S, c=(140, 220, 90), shine=True):
    dark = tuple(int(v * 0.6) for v in c[:3])
    d.polygon(P(S, [(0.5, 0.12), (0.68, 0.44), (0.32, 0.44)]), fill=dark)
    d.ellipse(P(S, [(0.27, 0.34), (0.73, 0.86)]), fill=dark)
    d.polygon(P(S, [(0.5, 0.16), (0.64, 0.44), (0.36, 0.44)]), fill=c)
    d.ellipse(P(S, [(0.30, 0.36), (0.68, 0.82)]), fill=c)
    if shine:
        d.ellipse(P(S, [(0.37, 0.46), (0.47, 0.62)]), fill=(255, 255, 255, 190))
        d.ellipse(P(S, [(0.55, 0.68), (0.61, 0.74)]), fill=(255, 255, 255, 120))


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
    d.polygon(P(S, [(0.58, 0.08), (0.26, 0.55), (0.47, 0.55), (0.38, 0.92), (0.76, 0.42), (0.55, 0.42), (0.68, 0.08)]), fill=(255, 196, 40))
    d.polygon(P(S, [(0.60, 0.12), (0.33, 0.52), (0.50, 0.52), (0.43, 0.80), (0.68, 0.45), (0.51, 0.45), (0.63, 0.12)]), fill=c)
    d.polygon(P(S, [(0.61, 0.16), (0.42, 0.46), (0.52, 0.46), (0.58, 0.18)]), fill=(255, 252, 220))


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
    d.rounded_rectangle(P(S, [(0.41, 0.10), (0.59, 0.20)]), radius=int(S * 0.02), fill=(150, 100, 60))
    d.rectangle(P(S, [(0.44, 0.19), (0.56, 0.33)]), fill=(200, 215, 235))
    d.ellipse(P(S, [(0.22, 0.30), (0.78, 0.88)]), fill=(196, 210, 232))
    d.ellipse(P(S, [(0.25, 0.33), (0.75, 0.85)]), fill=(70, 80, 110))
    dark = tuple(int(v * 0.6) for v in c[:3])
    d.chord(P(S, [(0.25, 0.33), (0.75, 0.85)]), 350, 190, fill=dark)
    d.chord(P(S, [(0.27, 0.36), (0.73, 0.82)]), 355, 185, fill=c)
    d.ellipse(P(S, [(0.42, 0.62), (0.47, 0.67)]), fill=(255, 255, 255, 120))
    d.ellipse(P(S, [(0.55, 0.70), (0.59, 0.74)]), fill=(255, 255, 255, 100))
    d.arc(P(S, [(0.30, 0.38), (0.70, 0.80)]), 200, 250, fill=(255, 255, 255), width=int(S * 0.03))


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


def g_heart(d, S, c=(240, 80, 100)):
    d.ellipse(P(S, [(0.18, 0.24), (0.52, 0.56)]), fill=c)
    d.ellipse(P(S, [(0.48, 0.24), (0.82, 0.56)]), fill=c)
    d.polygon(P(S, [(0.20, 0.46), (0.80, 0.46), (0.5, 0.84)]), fill=c)
    d.ellipse(P(S, [(0.28, 0.32), (0.38, 0.42)]), fill=(255, 255, 255, 150))


def g_crack(d, S, c=(170, 196, 230)):
    g_shield(d, S, c)
    w = int(S * 0.05)
    d.line(P(S, [(0.46, 0.14), (0.56, 0.36), (0.42, 0.50), (0.58, 0.66), (0.50, 0.88)]), fill=INK, width=w)
    d.line(P(S, [(0.42, 0.50), (0.28, 0.46)]), fill=INK, width=int(w * 0.7))


def g_water(d, S):
    g_drop(d, S, (90, 170, 255))
    w = int(S * 0.04)
    d.arc(P(S, [(0.16, 0.70), (0.50, 0.92)]), 200, 340, fill=(200, 235, 255), width=w)
    d.arc(P(S, [(0.50, 0.70), (0.84, 0.92)]), 200, 340, fill=(200, 235, 255), width=w)


def g_letter(d, S, ch, c=WHITE):
    # Простая «буква» для портретов: рисуем крупный символ встроенным шрифтом с масштабированием
    from PIL import ImageFont
    try:
        f = ImageFont.truetype("DejaVuSans-Bold.ttf", int(S * 0.5))
    except OSError:
        f = ImageFont.load_default()
    bb = d.textbbox((0, 0), ch, font=f)
    d.text(((S - (bb[2] - bb[0])) / 2 - bb[0], (S - (bb[3] - bb[1])) / 2 - bb[1]), ch, font=f, fill=c)


# ---------- добыча и рюкзак ----------

def g_bone(d, S, c=(236, 228, 206)):
    w = int(S * 0.12)
    d.line(P(S, [(0.30, 0.70), (0.70, 0.30)]), fill=c, width=w)
    for x, y in ((0.24, 0.68), (0.32, 0.78), (0.68, 0.22), (0.78, 0.32)):
        d.ellipse(P(S, [(x - 0.08, y - 0.08), (x + 0.08, y + 0.08)]), fill=c)


def g_scrap(d, S, c=(176, 160, 140)):
    cx, cy, r1, r2 = 0.5, 0.5, 0.32, 0.22
    pts = []
    for k in range(16):
        a = k / 16 * 2 * math.pi
        r = r1 if k % 2 == 0 else r2
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    d.polygon(P(S, pts), fill=c)
    d.ellipse(P(S, [(0.40, 0.40), (0.60, 0.60)]), fill=(60, 50, 40))
    d.line(P(S, [(0.22, 0.80), (0.40, 0.66)]), fill=(200, 120, 70), width=int(S * 0.05))


def g_tusk(d, S, c=(244, 236, 214)):
    pts = [(0.26 + 0.5 * t, 0.84 - 0.62 * math.sin(t * math.pi / 2)) for t in [i / 20 for i in range(21)]]
    inner = [(x + 0.10 * (1 - i / 20), y + 0.04) for i, (x, y) in enumerate(pts)]
    d.polygon(P(S, pts + inner[::-1]), fill=c)
    d.ellipse(P(S, [(0.18, 0.76), (0.40, 0.92)]), fill=(150, 110, 80))


def g_bag(d, S, c=(170, 120, 70)):
    d.ellipse(P(S, [(0.18, 0.32), (0.82, 0.88)]), fill=c)
    d.polygon(P(S, [(0.36, 0.34), (0.64, 0.34), (0.58, 0.22), (0.42, 0.22)]), fill=(140, 96, 56))
    d.line(P(S, [(0.34, 0.34), (0.66, 0.34)]), fill=GOLD, width=int(S * 0.05))
    d.ellipse(P(S, [(0.30, 0.46), (0.42, 0.58)]), fill=(255, 255, 255, 60))


def g_dodge(d, S, c=(170, 235, 255)):
    g_run(d, S, WHITE)
    for y in (0.40, 0.55, 0.70):
        d.line(P(S, [(0.06, y), (0.22, y)]), fill=c, width=int(S * 0.04))


def g_parry(d, S):
    g_sword(d, S, WHITE)
    d.polygon(P(S, [(0.28, 0.18), (0.18, 0.18), (0.18, 0.28), (0.60, 0.70), (0.70, 0.60)]), fill=(200, 210, 230))
    g_star(d, S, (255, 236, 120), 0.16, 0.06, 8, 0.5, 0.44)


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


def g_taunt(d, S):
    g_shield(d, S, (230, 120, 90))
    d.rectangle(P(S, [(0.46, 0.30), (0.54, 0.56)]), fill=WHITE)
    d.ellipse(P(S, [(0.45, 0.62), (0.55, 0.72)]), fill=WHITE)


def p_wolf(d, S):
    fur = (150, 150, 165)
    d.polygon(P(S, [(0.22, 0.18), (0.36, 0.40), (0.24, 0.46)]), fill=fur)
    d.polygon(P(S, [(0.78, 0.18), (0.64, 0.40), (0.76, 0.46)]), fill=fur)
    d.polygon(P(S, [(0.24, 0.36), (0.76, 0.36), (0.70, 0.66), (0.5, 0.88), (0.30, 0.66)]), fill=fur)
    d.polygon(P(S, [(0.40, 0.62), (0.60, 0.62), (0.5, 0.86)]), fill=(200, 200, 210))
    d.ellipse(P(S, [(0.45, 0.76), (0.55, 0.84)]), fill=INK)
    d.polygon(P(S, [(0.33, 0.48), (0.45, 0.50), (0.38, 0.55)]), fill=(255, 210, 60))
    d.polygon(P(S, [(0.67, 0.48), (0.55, 0.50), (0.62, 0.55)]), fill=(255, 210, 60))


def p_skeleton(d, S):
    bone = (230, 226, 210)
    d.ellipse(P(S, [(0.26, 0.16), (0.74, 0.66)]), fill=bone)
    d.rectangle(P(S, [(0.36, 0.60), (0.64, 0.82)]), fill=bone)
    d.ellipse(P(S, [(0.33, 0.36), (0.47, 0.50)]), fill=INK)
    d.ellipse(P(S, [(0.53, 0.36), (0.67, 0.50)]), fill=INK)
    d.polygon(P(S, [(0.5, 0.52), (0.46, 0.60), (0.54, 0.60)]), fill=INK)
    for x in (0.42, 0.5, 0.58):
        d.line(P(S, [(x, 0.66), (x, 0.80)]), fill=INK, width=int(S * 0.02))


def p_trork(d, S):
    skin = (150, 120, 90)
    d.ellipse(P(S, [(0.20, 0.20), (0.80, 0.80)]), fill=skin)
    d.rectangle(P(S, [(0.28, 0.36), (0.72, 0.44)]), fill=(90, 70, 50))
    d.ellipse(P(S, [(0.34, 0.44), (0.44, 0.54)]), fill=(255, 90, 60))
    d.ellipse(P(S, [(0.56, 0.44), (0.66, 0.54)]), fill=(255, 90, 60))
    d.polygon(P(S, [(0.34, 0.66), (0.40, 0.56), (0.42, 0.68)]), fill=WHITE)
    d.polygon(P(S, [(0.66, 0.66), (0.60, 0.56), (0.58, 0.68)]), fill=WHITE)



# ---------- снаряжение (0.10.3) ----------

STEEL, STEEL_D, WOOD, LEATHER = (214, 222, 236), (130, 142, 166), (120, 78, 44), (150, 100, 60)


def g_axe(d, S):
    d.line(P(S, [(0.30, 0.88), (0.62, 0.20)]), fill=WOOD, width=int(S * 0.07))
    d.polygon(P(S, [(0.52, 0.22), (0.80, 0.14), (0.88, 0.40), (0.64, 0.46)]), fill=STEEL)
    d.polygon(P(S, [(0.80, 0.14), (0.88, 0.40), (0.76, 0.34)]), fill=STEEL_D)
    d.line(P(S, [(0.80, 0.15), (0.88, 0.39)]), fill=(255, 255, 255), width=int(S * 0.02))


def g_mace(d, S):
    d.line(P(S, [(0.28, 0.86), (0.56, 0.44)]), fill=WOOD, width=int(S * 0.07))
    for k in range(8):
        a = k * math.pi / 4
        cx, cy = 0.62 + math.cos(a) * 0.17, 0.34 + math.sin(a) * 0.17
        d.polygon(P(S, [(cx, cy), (0.62 + math.cos(a + 0.3) * 0.1, 0.34 + math.sin(a + 0.3) * 0.1),
                        (0.62 + math.cos(a - 0.3) * 0.1, 0.34 + math.sin(a - 0.3) * 0.1)]), fill=STEEL_D)
    d.ellipse(P(S, [(0.49, 0.21), (0.75, 0.47)]), fill=STEEL)
    d.ellipse(P(S, [(0.54, 0.25), (0.62, 0.33)]), fill=(255, 255, 255))


def g_spear(d, S):
    d.line(P(S, [(0.18, 0.86), (0.66, 0.30)]), fill=WOOD, width=int(S * 0.05))
    d.polygon(P(S, [(0.84, 0.12), (0.72, 0.38), (0.60, 0.30)]), fill=STEEL)
    d.polygon(P(S, [(0.84, 0.12), (0.72, 0.38), (0.68, 0.32)]), fill=STEEL_D)
    d.line(P(S, [(0.58, 0.40), (0.66, 0.46)]), fill=(200, 60, 50), width=int(S * 0.04))


def g_staff(d, S):
    d.line(P(S, [(0.26, 0.88), (0.58, 0.32)]), fill=WOOD, width=int(S * 0.06))
    d.arc(P(S, [(0.48, 0.10), (0.80, 0.42)]), 120, 420, fill=(232, 186, 90), width=int(S * 0.04))
    d.ellipse(P(S, [(0.56, 0.18), (0.72, 0.34)]), fill=(150, 110, 255))
    d.ellipse(P(S, [(0.59, 0.21), (0.64, 0.26)]), fill=(240, 230, 255))


def g_daggers(d, S):
    for flip in (False, True):
        def X(x):
            return 1 - x if flip else x
        d.polygon(P(S, [(X(0.70), 0.14), (X(0.76), 0.18), (X(0.46), 0.60), (X(0.40), 0.54)]), fill=STEEL)
        d.line(P(S, [(X(0.34), 0.52), (X(0.50), 0.66)]), fill=(232, 186, 90), width=int(S * 0.04))
        d.line(P(S, [(X(0.42), 0.60), (X(0.28), 0.80)]), fill=LEATHER, width=int(S * 0.05))


def g_helm(d, S):
    d.chord(P(S, [(0.20, 0.18), (0.80, 0.82)]), 180, 360, fill=STEEL)
    d.rectangle(P(S, [(0.20, 0.49), (0.80, 0.74)]), fill=STEEL)
    d.rectangle(P(S, [(0.28, 0.50), (0.72, 0.58)]), fill=INK)
    d.rectangle(P(S, [(0.46, 0.56), (0.54, 0.76)]), fill=INK)
    d.line(P(S, [(0.5, 0.18), (0.5, 0.48)]), fill=(232, 186, 90), width=int(S * 0.05))
    d.chord(P(S, [(0.26, 0.24), (0.74, 0.70)]), 200, 250, fill=(255, 255, 255, 140))


def g_chest(d, S):
    d.polygon(P(S, [(0.30, 0.18), (0.42, 0.24), (0.58, 0.24), (0.70, 0.18), (0.86, 0.30), (0.78, 0.46),
                    (0.72, 0.42), (0.72, 0.84), (0.28, 0.84), (0.28, 0.42), (0.22, 0.46), (0.14, 0.30)]), fill=STEEL)
    d.polygon(P(S, [(0.50, 0.30), (0.72, 0.42), (0.72, 0.84), (0.50, 0.84)]), fill=STEEL_D)
    d.line(P(S, [(0.28, 0.62), (0.72, 0.62)]), fill=(232, 186, 90), width=int(S * 0.04))


def g_gloves(d, S):
    d.rounded_rectangle(P(S, [(0.30, 0.36), (0.70, 0.76)]), radius=int(S * 0.08), fill=LEATHER)
    for k in range(4):
        x = 0.31 + k * 0.10
        d.rounded_rectangle(P(S, [(x, 0.16 + (0.04 if k in (0, 3) else 0)), (x + 0.08, 0.44)]), radius=int(S * 0.04), fill=LEATHER)
    d.rounded_rectangle(P(S, [(0.16, 0.44), (0.32, 0.56)]), radius=int(S * 0.05), fill=LEATHER)
    d.rectangle(P(S, [(0.28, 0.72), (0.72, 0.86)]), fill=STEEL)


def g_boots(d, S):
    d.polygon(P(S, [(0.30, 0.14), (0.56, 0.14), (0.56, 0.62), (0.84, 0.70), (0.84, 0.86), (0.30, 0.86)]), fill=LEATHER)
    d.rectangle(P(S, [(0.28, 0.80), (0.86, 0.88)]), fill=(70, 46, 26))
    d.rectangle(P(S, [(0.28, 0.14), (0.58, 0.24)]), fill=STEEL)


def g_ring(d, S):
    d.ellipse(P(S, [(0.20, 0.34), (0.80, 0.90)]), outline=(232, 186, 90), width=int(S * 0.08))
    d.polygon(P(S, [(0.5, 0.12), (0.64, 0.26), (0.5, 0.42), (0.36, 0.26)]), fill=(255, 90, 120))
    d.polygon(P(S, [(0.5, 0.12), (0.64, 0.26), (0.5, 0.26)]), fill=(255, 190, 200))


def g_amulet(d, S):
    d.arc(P(S, [(0.20, 0.06), (0.80, 0.60)]), 0, 180, fill=(232, 186, 90), width=int(S * 0.03))
    d.arc(P(S, [(0.20, 0.06), (0.80, 0.60)]), 180, 360, fill=(232, 186, 90), width=int(S * 0.03))
    d.ellipse(P(S, [(0.34, 0.46), (0.66, 0.78)]), fill=(232, 186, 90))
    d.ellipse(P(S, [(0.39, 0.51), (0.61, 0.73)]), fill=(80, 200, 220))
    d.ellipse(P(S, [(0.43, 0.54), (0.49, 0.60)]), fill=(230, 255, 255))


def gem_glyph(c):
    """Огранённый камень: корона и павильон, светлые и тёмные грани."""
    def draw(d, S):
        light = tuple(min(255, int(v + (255 - v) * 0.55)) for v in c)
        dark = tuple(int(v * 0.55) for v in c)
        top, mid, bot = 0.24, 0.42, 0.86
        d.polygon(P(S, [(0.30, top), (0.70, top), (0.86, mid), (0.5, bot), (0.14, mid)]), fill=c)
        d.polygon(P(S, [(0.30, top), (0.70, top), (0.62, mid), (0.38, mid)]), fill=light)
        d.polygon(P(S, [(0.14, mid), (0.38, mid), (0.5, bot)]), fill=dark)
        d.polygon(P(S, [(0.62, mid), (0.86, mid), (0.5, bot)]), fill=tuple(int(v * 0.8) for v in c))
        d.polygon(P(S, [(0.36, 0.28), (0.46, 0.28), (0.42, 0.36)]), fill=(255, 255, 255))
    return draw


# ---------- новые способности (0.10.3) ----------

def g_meteor(d, S):
    for k, w in ((0, 0.16), (1, 0.11), (2, 0.07)):
        off = 0.06 * k
        d.line(P(S, [(0.18 + off, 0.14 - off * 0.2), (0.58, 0.56)]), fill=(255, 150 + 40 * k, 60 + 60 * k), width=int(S * w))
    d.ellipse(P(S, [(0.46, 0.46), (0.84, 0.84)]), fill=(120, 60, 40))
    d.ellipse(P(S, [(0.52, 0.52), (0.66, 0.66)]), fill=(255, 160, 60))
    d.ellipse(P(S, [(0.68, 0.64), (0.76, 0.72)]), fill=(70, 34, 20))


def g_shadow(d, S):
    d.ellipse(P(S, [(0.14, 0.14), (0.86, 0.86)]), fill=(70, 30, 110, 200))
    d.ellipse(P(S, [(0.26, 0.20), (0.92, 0.86)]), fill=(0, 0, 0, 0))
    d.polygon(P(S, [(0.74, 0.16), (0.80, 0.22), (0.42, 0.66), (0.36, 0.60)]), fill=(200, 170, 255))
    d.line(P(S, [(0.30, 0.56), (0.46, 0.72)]), fill=(232, 186, 90), width=int(S * 0.045))
    d.line(P(S, [(0.38, 0.64), (0.22, 0.84)]), fill=(60, 40, 70), width=int(S * 0.055))


def g_nova(d, S):
    for k in range(8):
        a = k * math.pi / 4
        d.polygon(P(S, [(0.5 + math.cos(a) * 0.40, 0.5 + math.sin(a) * 0.40),
                        (0.5 + math.cos(a + 0.18) * 0.16, 0.5 + math.sin(a + 0.18) * 0.16),
                        (0.5 + math.cos(a - 0.18) * 0.16, 0.5 + math.sin(a - 0.18) * 0.16)]), fill=(200, 240, 255))
    d.ellipse(P(S, [(0.22, 0.22), (0.78, 0.78)]), outline=(120, 210, 255), width=int(S * 0.035))
    d.ellipse(P(S, [(0.40, 0.40), (0.60, 0.60)]), fill=(255, 255, 255))


def g_tempest(d, S):
    for k, (y, w) in enumerate(((0.20, 0.62), (0.34, 0.50), (0.48, 0.38), (0.62, 0.26), (0.74, 0.16))):
        x = 0.5 + (0.04 if k % 2 else -0.04)
        d.arc(P(S, [(x - w / 2, y - 0.06), (x + w / 2, y + 0.06)]), 10, 330, fill=(200, 240, 230), width=int(S * 0.045))
    d.line(P(S, [(0.70, 0.30), (0.62, 0.50), (0.72, 0.52), (0.60, 0.80)]), fill=(255, 236, 90), width=int(S * 0.04))


def g_spike(d, S):
    d.polygon(P(S, [(0.10, 0.86), (0.90, 0.86), (0.90, 0.78), (0.10, 0.78)]), fill=(120, 86, 56))
    for x, h, w in ((0.30, 0.42, 0.10), (0.52, 0.14, 0.13), (0.72, 0.36, 0.10)):
        d.polygon(P(S, [(x, h), (x + w, 0.80), (x - w, 0.80)]), fill=(214, 178, 120))
        d.polygon(P(S, [(x, h), (x + w, 0.80), (x, 0.80)]), fill=(150, 112, 70))


def g_horn(d, S):
    pts = [(0.18 + 0.6 * t, 0.64 - 0.36 * t + 0.2 * math.sin(t * math.pi)) for t in [i / 20 for i in range(21)]]
    lower = [(x, y + 0.04 + 0.16 * (i / 20)) for i, (x, y) in enumerate(pts)]
    d.polygon(P(S, pts + lower[::-1]), fill=(236, 222, 190))
    d.line(P(S, [(0.40, 0.48), (0.42, 0.66)]), fill=(232, 186, 90), width=int(S * 0.04))
    for k in range(3):
        a = -0.6 + k * 0.5
        d.arc(P(S, [(0.62 - 0.1 * k, 0.10 - 0.06 * k), (0.96 + 0.06 * k, 0.44 + 0.06 * k)]), -70, 20, fill=(255, 220, 140), width=int(S * 0.03))


def g_wall(d, S):
    for x in (0.08, 0.36, 0.64):
        d.rounded_rectangle(P(S, [(x, 0.22), (x + 0.28, 0.80)]), radius=int(S * 0.05), fill=(232, 186, 90))
        d.rounded_rectangle(P(S, [(x + 0.03, 0.25), (x + 0.25, 0.77)]), radius=int(S * 0.04), fill=(120, 160, 210))
        d.rectangle(P(S, [(x + 0.12, 0.30), (x + 0.16, 0.72)]), fill=(232, 186, 90))


def g_roar(d, S):
    d.pieslice(P(S, [(0.08, 0.20), (0.68, 0.80)]), -40, 40, fill=(200, 80, 60))
    d.polygon(P(S, [(0.40, 0.42), (0.46, 0.50), (0.52, 0.42)]), fill=WHITE)
    d.polygon(P(S, [(0.40, 0.58), (0.46, 0.50), (0.52, 0.58)]), fill=WHITE)
    for k in range(3):
        r = 0.18 + k * 0.1
        d.arc(P(S, [(0.5 - r, 0.5 - r), (0.5 + r, 0.5 + r)]), -40, 40, fill=(255, 200, 120), width=int(S * 0.035))


def g_claw(d, S):
    for k in range(3):
        x = 0.28 + k * 0.17
        d.polygon(P(S, [(x + 0.18, 0.14), (x + 0.22, 0.18), (x - 0.02, 0.86), (x - 0.06, 0.80)]), fill=(255, 120, 110))
        d.line(P(S, [(x + 0.18, 0.16), (x - 0.03, 0.80)]), fill=(255, 230, 220), width=int(S * 0.015))


def g_split(d, S):
    d.ellipse(P(S, [(0.10, 0.40), (0.48, 0.80)]), fill=(120, 230, 210))
    d.ellipse(P(S, [(0.52, 0.30), (0.90, 0.70)]), fill=(90, 200, 190))
    d.ellipse(P(S, [(0.18, 0.48), (0.26, 0.56)]), fill=(255, 255, 255, 170))
    d.ellipse(P(S, [(0.60, 0.38), (0.68, 0.46)]), fill=(255, 255, 255, 170))
    d.line(P(S, [(0.5, 0.16), (0.5, 0.86)]), fill=(255, 255, 255, 120), width=int(S * 0.02))


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
    # добыча, рюкзак, ответ на удар
    "bone": ("#6a6050", "#24201a", g_bone),
    "silk": ("#5a5a70", "#1e1e28", lambda d, S: g_web(d, S, (245, 245, 255))),
    "scrap": ("#5a4a3a", "#1e1810", g_scrap),
    "tusk": ("#6a5a3a", "#241c10", g_tusk),
    "gel": ("#2a7a7a", "#0a2a2a", lambda d, S: g_drop(d, S, (120, 230, 210))),
    "bag": ("#6a4a2a", "#22160a", g_bag),
    "dodge": ("#2a7aa8", "#0c2a3e", g_dodge),
    "parry": ("#a87a2a", "#3e2a0c", g_parry),
    # статусы (круглые)
    "heart": ("#b0304a", "#3a0c18", g_heart),
    "star": ("#c9a227", "#4a3008", lambda d, S: g_star(d, S, (255, 236, 150), 0.38, 0.16, 5)),
    "water": ("#2a6ac8", "#0c2252", g_water),
    "bleed": ("#a02a2a", "#3a0a0a", lambda d, S: g_drop(d, S, (230, 50, 60))),
    "crack": ("#5a6a8a", "#1e2436", g_crack),
    "taunt": ("#a8503a", "#3a1408", g_taunt),
    "st_taunt": ("#a8503a", "#3a1408", g_taunt),
    "st_wet": ("#2a6ac8", "#0c2252", lambda d, S: g_drop(d, S, (110, 190, 255))),
    "st_frozen": ("#6ac0e8", "#14466a", g_snow),
    "st_armor_break": ("#5a6a8a", "#1e2436", g_crack),
    "st_bleed": ("#a02a2a", "#3a0a0a", lambda d, S: g_drop(d, S, (240, 60, 70))),
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
    "pt_wolf": ("#3a4a5a", "#10161e", p_wolf),
    "pt_skeleton": ("#4a4a3a", "#1a1a10", p_skeleton),
    "pt_trork": ("#6a4a2a", "#22160a", p_trork),
    "pt_unknown": ("#4a4a5a", "#18181e", lambda d, S: g_letter(d, S, "?")),
    # снаряжение (0.10.3): тип оружия и слот брони; качество показывает рамка tier_*
    "gear_axe": ("#6a4a3a", "#22140c", g_axe),
    "gear_mace": ("#5a5a6a", "#1a1a24", g_mace),
    "gear_spear": ("#5a6a4a", "#1a2410", g_spear),
    "gear_staff": ("#5a3a8a", "#1a0e30", g_staff),
    "gear_daggers": ("#4a5a6a", "#141c24", g_daggers),
    "gear_helm": ("#4a5a7a", "#141c2a", g_helm),
    "gear_chest": ("#4a5a7a", "#141c2a", g_chest),
    "gear_gloves": ("#6a4a2a", "#22160a", g_gloves),
    "gear_boots": ("#6a4a2a", "#22160a", g_boots),
    "gear_ring": ("#7a3a5a", "#2a0e1e", g_ring),
    "gear_amulet": ("#2a6a7a", "#0a222a", g_amulet),
    # способности 0.10
    "meteor": ("#c4402a", "#3a0c08", g_meteor),
    "shadow": ("#4a2a6a", "#140a24", g_shadow),
    "nova": ("#4aa6d6", "#0c2a44", g_nova),
    "tempest": ("#2a8a7a", "#0a2a26", g_tempest),
    "spike": ("#8a6a3a", "#2a1c0c", g_spike),
    "warcry": ("#a86a2a", "#3a2008", g_horn),
    "wall": ("#4f7aa8", "#1f3352", g_wall),
    "roar": ("#a8402a", "#3a0e08", g_roar),
    "claw": ("#8a3a3a", "#2a0c0c", g_claw),
    "split": ("#2a7a7a", "#0a2a2a", g_split),
    "gem_ruby": ("#a83a3a", "#2a0a0a", gem_glyph((232, 80, 80))),
    "gem_sapphire": ("#3a5aa8", "#0a142a", gem_glyph((80, 140, 240))),
    "gem_topaz": ("#a8902a", "#2a220a", gem_glyph((240, 200, 70))),
    "gem_emerald": ("#2a8a4a", "#0a2a14", gem_glyph((90, 210, 120))),
    "gem_amethyst": ("#6a3aa8", "#1e0a2e", gem_glyph((180, 120, 240))),
    "gem_diamond": ("#6a7a8a", "#1a1e24", gem_glyph((225, 232, 245))),
    "smith": ("#7a5a3a", "#22160a", g_mace),
}

# Рамки уровня: одна прозрачная рамка на уровень поверх любой иконки (иконка + рамка = два слоя в слоте),
# так уровни не множат текстуры. Цвета — как у качества вещей (Equipment.Quality).
TIERS = {"t1": ((111, 220, 140), 1), "t2": ((127, 178, 255), 2), "t3": ((255, 184, 74), 3), "t4": ((176, 124, 240), 3)}


SPARKLY = {"gem_ruby", "gem_sapphire", "gem_topaz", "gem_emerald", "gem_amethyst", "gem_diamond", "meteor", "nova", "tempest", "magic", "focus", "ether", "levelup", "star", "st_charged", "haste", "st_haste", "thunder", "ice",
           "st_frozen", "heal", "regen", "st_regen"}


def accent_of(top):
    """Цвет свечения стихии: верхний цвет плитки, светлее и насыщеннее."""
    r, g, b = top
    m = max(r, g, b) or 1
    sat = tuple(min(255, int(c * 255 / m)) for c in (r, g, b))
    return art.lerp(sat, (255, 255, 255), 0.18)


def render(key, size=128):
    """Иконка в стиле референса: тёмная плитка с цветным свечением стихии, объёмный глиф с обводкой
    и бликом, золотая кайма с фаской. Портреты и статусы — круглые."""
    top, bottom, glyph = ICONS[key]
    top, bottom = art.hexc(top), art.hexc(bottom)
    circle = key.startswith("st_") or key.startswith("pt_")
    S = size * SS
    dim = (S, S)
    pad = int(S * 0.02)
    shape = art.ellipse_mask(dim, pad) if circle else art.rounded_mask(dim, pad, int(S * 0.2))
    accent = accent_of(top)
    # фон: свечение стихии в центре, к краям почти чёрный синий
    bg = art.radial(dim, art.lerp(art.lerp(top, bottom, 0.5), art.NAVY, 0.25), art.lerp(bottom, (6, 9, 18), 0.75),
                    cy=0.44, r=0.7, power=1.05)
    bg[..., :3] = np.clip(bg[..., :3] + art.noise(dim, 0.025, seed=len(key), blur=S * 0.004)[..., None], 0, 1)
    # глиф
    layer = Image.new("RGBA", dim, (0, 0, 0, 0))
    glyph(ImageDraw.Draw(layer), S)
    g = art.arr(layer)
    a = g[..., 3]
    # мягкое свечение за глифом
    halo = art.blur_mask(art.dilate(a, S * 0.02), S * 0.07)
    bg = art.add(bg, accent, halo, 0.7)
    if key in SPARKLY:
        rays = Image.new("L", dim, 0)
        rd = ImageDraw.Draw(rays)
        for k in range(12):
            ang = k * math.pi / 6 + 0.13
            rd.polygon([(S * 0.5, S * 0.46), (S * 0.5 + math.cos(ang - 0.07) * S * 0.6, S * 0.46 + math.sin(ang - 0.07) * S * 0.6),
                        (S * 0.5 + math.cos(ang + 0.07) * S * 0.6, S * 0.46 + math.sin(ang + 0.07) * S * 0.6)], fill=40)
        bg = art.add(bg, accent, art.blur_mask(art.mask_from(rays), S * 0.02), 0.5)
    tile = art.with_alpha(bg, shape)
    # тень от каймы внутрь
    inner_shadow = art.blur_mask(1 - art.erode(shape, S * 0.06), S * 0.05) * shape
    tile = art.over(tile, art.with_alpha(art.solid(dim, (0, 0, 0)), inner_shadow * 0.55))
    # обводка глифа (тёмная), тень вниз-вправо
    outline = art.blur_mask(art.dilate(a, S * 0.022), S * 0.006)
    drop = art.shift(art.blur_mask(a, S * 0.02), int(S * 0.02), int(S * 0.03))
    tile = art.over(tile, art.with_alpha(art.solid(dim, (0, 0, 0)), drop * 0.55 * shape))
    tile = art.over(tile, art.with_alpha(art.solid(dim, art.INK), outline * 0.9))
    # объём: свет сверху, низ темнее, светлая кромка сверху-слева, тёмная снизу-справа
    x, y = art.coords(dim)
    shade = np.clip(1.3 - 0.6 * y + 0.22 * (0.5 - x), 0.5, 1.35)
    g2 = g.copy()
    g2[..., :3] = np.clip(g[..., :3] * shade[..., None], 0, 1)
    rim = np.clip(a - art.shift(a, int(S * 0.014), int(S * 0.02)), 0, 1)
    low = np.clip(a - art.shift(a, -int(S * 0.012), -int(S * 0.018)), 0, 1)
    g2 = art.add(g2, (255, 255, 255), art.blur_mask(rim, S * 0.004), 0.55)
    g2[..., :3] = np.clip(g2[..., :3] * (1 - 0.35 * art.blur_mask(low, S * 0.004)[..., None]), 0, 1)
    tile = art.over(tile, g2)
    # цветная кромка свечения вокруг глифа
    edge = np.clip(art.blur_mask(art.dilate(a, S * 0.03), S * 0.012) - art.dilate(a, S * 0.018), 0, 1)
    tile = art.add(tile, accent, edge * shape, 0.45)
    # блик стекла сверху
    gloss = Image.new("L", dim, 0)
    ImageDraw.Draw(gloss).ellipse([int(S * 0.08), int(-S * 0.55), int(S * 0.92), int(S * 0.36)], fill=255)
    gm = art.blur_mask(art.mask_from(gloss), S * 0.03) * art.erode(shape, S * 0.07)
    tile = art.add(tile, (255, 255, 255), gm, 0.09)
    if key in SPARKLY:
        sp = Image.new("RGBA", dim, (0, 0, 0, 0))
        sd = ImageDraw.Draw(sp)
        light = art.lerp(accent, (255, 255, 255), 0.6) + (255,)
        art.sparkle(sd, S * 0.76, S * 0.24, S * 0.075, light)
        art.sparkle(sd, S * 0.24, S * 0.72, S * 0.05, light)
        spa = art.arr(sp)
        tile = art.add(tile, accent, art.blur_mask(spa[..., 3], S * 0.02), 0.6)
        tile = art.over(tile, spa)
    # кайма
    ring, _ = art.bevel_ring(dim, shape, max(4, int(S * 0.065)))
    tile = art.over(tile, ring)
    return art.downsample(art.img_of(tile), (size, size))


def render_tier(tier, size=96, round_=False):
    """Прозрачная рамка уровня: цветная металлическая кайма поверх золотой и ромбики-«звёзды» внизу.
    round_ — круглая, для иконок-медальонов."""
    color, pips = TIERS[tier]
    S = size * SS
    dim = (S, S)
    pad = int(S * 0.02)
    shape = art.ellipse_mask(dim, pad) if round_ else art.rounded_mask(dim, pad, int(S * 0.2))
    ring, _ = art.bevel_ring(dim, shape, max(4, int(S * 0.075)), base=color)
    glow = np.clip(art.blur_mask(art.erode(shape, S * 0.075), S * 0.04) - art.erode(shape, S * 0.14), 0, 1)
    out = art.with_alpha(art.solid(dim, color), glow * 0.55)
    out = art.over(out, ring)
    gem = Image.new("RGBA", dim, (0, 0, 0, 0))
    gd = ImageDraw.Draw(gem)
    r = S * 0.075
    for k in range(pips):
        cx = S * 0.5 + (k - (pips - 1) / 2) * r * 2.3
        cy = S - pad - r * 1.05
        gd.polygon([(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)], fill=(20, 16, 24, 255))
        gd.polygon([(cx, cy - r * 0.7), (cx + r * 0.7, cy), (cx, cy + r * 0.7), (cx - r * 0.7, cy)],
                   fill=tuple(art.lerp(color, (255, 255, 255), 0.35)) + (255,))
    out = art.over(out, art.arr(gem))
    return art.downsample(art.img_of(out), (size, size))


UI = """Group {{
  Anchor: (Width: {n}, Height: {n});
  Background: (TexturePath: "{key}.png");
}}
"""


# Иконки, нарисованные отдельно (Kling, 2026-10-19): их PNG лежат в Icons/ готовыми, скрипт их не перерисовывает,
# только пишет обёртки .ui. Список — tools/external_icons.txt.
EXTERNAL_LIST = os.path.join(os.path.dirname(__file__), "external_icons.txt")


def external():
    if not os.path.exists(EXTERNAL_LIST):
        return []
    with open(EXTERNAL_LIST, encoding="utf-8") as f:
        return [l.strip() for l in f if l.strip()]


def is_round(path):
    """Круглая иконка (медальон): угол прозрачный. У квадратных со скруглением угол (10,10) закрашен."""
    im = Image.open(path).convert("RGBA")
    k = max(1, im.width * 10 // 96)
    return im.getpixel((k, k))[3] < 40


def main():
    os.makedirs(OUT, exist_ok=True)
    ext = external()
    keys_all = list(ICONS) + [k for k in ext if k not in ICONS]
    for key in keys_all:
        if key not in ext:
            size = 128 if key.startswith("pt_") else 96
            render(key, size).save(os.path.join(OUT, key + ".png"))
        for suffix, n in SIZES.items():
            with open(os.path.join(OUT, f"{key}_{suffix}.ui"), "w", encoding="utf-8") as f:
                f.write(UI.format(n=n, key=key))
    # рамки уровня: квадратные tier_t1..3 и круглые tier_c1..3 (для медальонов)
    for tier in TIERS:
        for shape, name in ((False, f"tier_{tier}"), (True, f"tier_c{tier[1:]}")):
            render_tier(tier, round_=shape).save(os.path.join(OUT, name + ".png"))
            for suffix, n in SIZES.items():
                with open(os.path.join(OUT, f"{name}_{suffix}.ui"), "w", encoding="utf-8") as f:
                    f.write(UI.format(n=n, key=name))
    rounds = [k for k in keys_all if is_round(os.path.join(OUT, k + ".png"))]
    with open(os.path.join(os.path.dirname(__file__), "..", "hytale-plugin", "src", "main", "resources", "data",
                           "icons_round.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(rounds) + "\n")
    # список ключей для плагина (какие иконки существуют)
    idx = os.path.join(os.path.dirname(__file__), "..", "hytale-plugin", "src", "main", "resources", "data", "icons.txt")
    os.makedirs(os.path.dirname(idx), exist_ok=True)
    with open(idx, "w", encoding="utf-8") as f:
        f.write("\n".join(keys_all) + "\n")
    # лист-превью для просмотра всех иконок разом
    keys = keys_all
    cols = 10
    rows = (len(keys) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * 72, rows * 72), (18, 16, 26, 255))
    for i, k in enumerate(keys):
        sheet.alpha_composite(Image.open(os.path.join(OUT, k + ".png")).convert("RGBA").resize((64, 64), Image.LANCZOS),
                              ((i % cols) * 72 + 4, (i // cols) * 72 + 4))
    tiers = Image.new("RGBA", (len(TIERS) * 144 + 4, 72), (18, 16, 26, 255))
    def icon64(k):
        return Image.open(os.path.join(OUT, k + ".png")).convert("RGBA").resize((64, 64), Image.LANCZOS)
    for i, t in enumerate(TIERS):
        tiers.alpha_composite(icon64("gear_chest"), (i * 144 + 4, 4))
        tiers.alpha_composite(render_tier(t, 64), (i * 144 + 4, 4))
        tiers.alpha_composite(icon64("gear_sword"), (i * 144 + 76, 4))
        tiers.alpha_composite(render_tier(t, 64, round_=True), (i * 144 + 76, 4))
    big = Image.new("RGBA", (sheet.width, sheet.height + 72), (18, 16, 26, 255))
    big.alpha_composite(sheet, (0, 0))
    big.alpha_composite(tiers, (0, sheet.height))
    sheet = big
    preview = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "icons_preview.png")
    sheet.save(preview)
    print("icons:", len(keys), "->", os.path.abspath(OUT))


if __name__ == "__main__":
    main()
