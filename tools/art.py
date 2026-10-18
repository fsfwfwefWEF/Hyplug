"""Общие приёмы рисования для иконок и рамок мода (tools/gen_icons.py, tools/gen_frames.py).

Всё рисуется с суперсэмплингом и numpy: градиенты, мягкое свечение, объём глифа (свет сверху-слева,
тень снизу), металлическая кайма с фаской. Стиль — «синий камень и золото» из референсов пользователя.
"""
import math

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageChops

GOLD_HI = (255, 236, 170)
GOLD = (226, 186, 98)
GOLD_MID = (176, 128, 52)
GOLD_LO = (92, 60, 22)
INK = (10, 12, 20)
NAVY = (12, 18, 32)


def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(len(a)))


def mix(a, b, t):
    return lerp(a, b, t)


def lighten(c, t):
    return lerp(c, (255, 255, 255), t)


def darken(c, t):
    return lerp(c, (0, 0, 0), t)


# ---------- numpy-слои ----------

def arr(img):
    return np.asarray(img, dtype=np.float32) / 255.0


def img_of(a):
    return Image.fromarray(np.clip(a * 255.0 + 0.5, 0, 255).astype(np.uint8), "RGBA")


def solid(size, color, alpha=1.0):
    w, h = size
    out = np.zeros((h, w, 4), np.float32)
    out[..., :3] = np.array(color[:3], np.float32) / 255.0
    out[..., 3] = alpha
    return out


def coords(size):
    w, h = size
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    return (x + 0.5) / w, (y + 0.5) / h


def radial(size, inner, outer, cx=0.5, cy=0.45, r=0.62, power=1.4):
    """Радиальный градиент inner → outer (цвета RGB 0..255)."""
    x, y = coords(size)
    d = np.sqrt((x - cx) ** 2 + (y - cy) ** 2) / r
    t = np.clip(d, 0, 1) ** power
    a = np.array(inner, np.float32) / 255.0
    b = np.array(outer, np.float32) / 255.0
    rgb = a[None, None, :] * (1 - t[..., None]) + b[None, None, :] * t[..., None]
    out = np.ones((size[1], size[0], 4), np.float32)
    out[..., :3] = rgb
    return out


def vgrad(size, stops):
    """Вертикальный градиент по точкам [(t, (r,g,b)), ...]."""
    w, h = size
    ts = np.linspace(0, 1, h, dtype=np.float32)
    rgb = np.zeros((h, 3), np.float32)
    for k in range(3):
        rgb[:, k] = np.interp(ts, [s[0] for s in stops], [s[1][k] / 255.0 for s in stops])
    out = np.ones((h, w, 4), np.float32)
    out[..., :3] = rgb[:, None, :]
    return out


def noise(size, amount=0.04, seed=1, blur=0.0):
    rng = np.random.default_rng(seed)
    n = rng.normal(0, 1, (size[1], size[0])).astype(np.float32)
    if blur:
        im = Image.fromarray(((n * 40) + 128).clip(0, 255).astype(np.uint8), "L").filter(ImageFilter.GaussianBlur(blur))
        n = (np.asarray(im, np.float32) - 128) / 40
    return n * amount


def over(dst, src):
    """src поверх dst (оба RGBA float, не premultiplied)."""
    sa = src[..., 3:4]
    da = dst[..., 3:4]
    oa = sa + da * (1 - sa)
    rgb = np.where(oa > 1e-6, (src[..., :3] * sa + dst[..., :3] * da * (1 - sa)) / np.maximum(oa, 1e-6), 0)
    return np.concatenate([rgb, oa], axis=-1)


def add(dst, color, alpha_map, strength=1.0):
    """Аддитивный свет цвета color по карте alpha_map (0..1)."""
    out = dst.copy()
    c = np.array(color[:3], np.float32) / 255.0
    out[..., :3] = np.clip(out[..., :3] + c[None, None, :] * (alpha_map[..., None] * strength), 0, 1)
    out[..., 3] = np.clip(np.maximum(out[..., 3], alpha_map * strength), 0, 1)
    return out


def with_alpha(layer, mask):
    out = layer.copy()
    out[..., 3] = out[..., 3] * mask
    return out


def mask_from(img_l):
    return np.asarray(img_l, np.float32) / 255.0


def blur_mask(m, radius):
    im = Image.fromarray((np.clip(m, 0, 1) * 255).astype(np.uint8), "L").filter(ImageFilter.GaussianBlur(radius))
    return np.asarray(im, np.float32) / 255.0


def dilate(m, px):
    im = Image.fromarray((np.clip(m, 0, 1) * 255).astype(np.uint8), "L")
    size = max(3, int(px) * 2 + 1)
    return np.asarray(im.filter(ImageFilter.MaxFilter(size)), np.float32) / 255.0


def erode(m, px):
    im = Image.fromarray((np.clip(m, 0, 1) * 255).astype(np.uint8), "L")
    size = max(3, int(px) * 2 + 1)
    return np.asarray(im.filter(ImageFilter.MinFilter(size)), np.float32) / 255.0


def shift(m, dx, dy):
    out = np.zeros_like(m)
    h, w = m.shape[:2]
    dx, dy = int(dx), int(dy)
    xs0, xs1 = max(0, -dx), min(w, w - dx)
    ys0, ys1 = max(0, -dy), min(h, h - dy)
    out[ys0 + dy:ys1 + dy, xs0 + dx:xs1 + dx] = m[ys0:ys1, xs0:xs1]
    return out


def rounded_mask(size, pad, radius, ss=1):
    w, h = size
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle([pad, pad, w - 1 - pad, h - 1 - pad], radius=radius, fill=255)
    return mask_from(m)


def ellipse_mask(size, pad):
    w, h = size
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).ellipse([pad, pad, w - 1 - pad, h - 1 - pad], fill=255)
    return mask_from(m)


def metal(size, base=GOLD, angle_light=True):
    """Металл каймы: светлый верх, тёмный низ, блик посередине."""
    return vgrad(size, [(0.0, lighten(base, 0.55)), (0.22, lighten(base, 0.15)), (0.5, base),
                        (0.62, darken(base, 0.35)), (0.85, darken(base, 0.1)), (1.0, darken(base, 0.55))])


def bevel_ring(size, outer_mask, width, base=GOLD):
    """Металлическое кольцо шириной width по краю outer_mask, с фаской и тонкими тёмными линиями."""
    inner = erode(outer_mask, width)
    ring = np.clip(outer_mask - inner, 0, 1)
    layer = metal(size, base)
    # фаска: светлая полоса у внешнего края сверху, тёмная у внутреннего
    hi = np.clip(outer_mask - shift(outer_mask, 0, max(1, width // 3)), 0, 1)
    lo = np.clip(inner - shift(inner, 0, -max(1, width // 3)), 0, 1)
    layer = add(layer, (255, 245, 210), hi * ring, 0.55)
    out = with_alpha(layer, ring)
    # тонкая тёмная линия снаружи и внутри
    edge_out = np.clip(outer_mask - erode(outer_mask, max(1, width // 6)), 0, 1)
    edge_in = np.clip(dilate(inner, max(1, width // 6)) - inner, 0, 1)
    dark = solid(size, darken(base, 0.75))
    out = over(out, with_alpha(dark, edge_out * 0.85))
    out = over(out, with_alpha(dark, edge_in * 0.9))
    out = over(out, with_alpha(solid(size, (0, 0, 0)), lo * 0.25))
    return out, inner


def sparkle(draw, cx, cy, r, color, ss=1, thin=0.18):
    """Четырёхлучевая искра."""
    t = r * thin
    draw.polygon([(cx, cy - r), (cx + t, cy - t), (cx + r, cy), (cx + t, cy + t), (cx, cy + r),
                  (cx - t, cy + t), (cx - r, cy), (cx - t, cy - t)], fill=color)


def downsample(img, size):
    return img.resize(size, Image.LANCZOS)
