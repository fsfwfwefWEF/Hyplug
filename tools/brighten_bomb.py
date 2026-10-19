"""Осветляет иконку бомбы Kling: фон медальона светлее, корпус бомбы тёмный с светлым контуром —
так она читается в 22 px. Запуск: python3 tools/brighten_bomb.py <исходный bomb.png> (пишет в Icons/bomb.png)."""
import os
import sys

import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "hytale-plugin", "src", "main", "resources",
                   "Common", "UI", "Custom", "JrpgBattle", "Icons", "bomb.png")


def main(src):
    a = np.asarray(Image.open(src).convert("RGBA")).astype(float) / 255
    h, w = a.shape[:2]
    y, x = np.mgrid[0:h, 0:w]
    r = np.hypot(x - (w - 1) / 2, y - (h - 1) / 2) / (w / 2)
    inner = np.clip((0.84 - r) / 0.05, 0, 1)  # внутри золотого ободка
    rgb = a[..., :3]
    # корпус: круг по тёмным пикселям в середине
    d = (rgb.mean(-1) < 0.11) & (r < 0.55)
    cy, cx = np.argwhere(d).mean(0)
    rad = np.sqrt(d.sum() / np.pi)
    dist = np.hypot(x - cx, y - cy)
    body = np.clip((rad + 1.5 - dist) / 2.0, 0, 1)
    edge = np.clip(1 - np.abs(dist - (rad + 1.8)) / 1.6, 0, 1) * (1 - body)
    bg = np.clip(rgb ** 0.6 * 1.1 + np.array([0.08, 0.12, 0.20]) * np.clip(1 - r / 0.85, 0, 1)[..., None], 0, 1)
    new = bg * (1 - body[..., None]) + rgb * body[..., None]
    new = np.clip(new + edge[..., None] * np.array([0.85, 0.80, 0.65]) * 0.6, 0, 1)
    rgb2 = rgb * (1 - inner[..., None]) + new * inner[..., None]
    Image.fromarray((np.dstack([rgb2, a[..., 3]]) * 255).astype(np.uint8)).save(OUT)


if __name__ == "__main__":
    main(sys.argv[1])
