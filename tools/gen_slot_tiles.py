"""Плитки для ячеек сеток предметов (ItemGrid): непрозрачный квадрат = ячейка jr_cell + наша иконка + рамка уровня.
Плитка кладётся поверх предмета игры (ItemGridSlot.setOverlay), чтобы в рюкзаке/на кукле были видны наши иконки,
а не похожие предметы Hytale. Стили — Icons/Tiles.ui (@T<Имя><уровень>), картинки — Icons/tiles/*.png.
Запуск: python3 tools/gen_slot_tiles.py (после gen_icons.py)."""
import os
from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "hytale-plugin", "src", "main", "resources", "Common", "UI", "Custom", "JrpgBattle")
ICONS = os.path.join(ROOT, "Icons")
OUT = os.path.join(ICONS, "tiles")
SIZE = 72
ROUND = set(l.strip() for l in open(os.path.join(os.path.dirname(__file__), "..", "hytale-plugin", "src", "main", "resources", "data", "icons_round.txt"), encoding="utf-8") if l.strip() and not l.startswith("#"))


def style_name(key, tier):
    return "T" + "".join(p[:1].upper() + p[1:] for p in key.replace("-", "_").split("_")) + str(tier)


def main():
    os.makedirs(OUT, exist_ok=True)
    cell = Image.open(os.path.join(ROOT, "jr_cell.png")).convert("RGBA").resize((SIZE, SIZE), Image.LANCZOS)
    base = Image.new("RGBA", (SIZE, SIZE), (14, 18, 30, 255))
    base.alpha_composite(cell)
    keys = sorted(f[:-4] for f in os.listdir(ICONS) if f.endswith(".png") and not f.startswith("tier_")
                  and not f.startswith("portrait") and not f.startswith("st_"))
    lines = []
    for key in keys:
        icon = Image.open(os.path.join(ICONS, key + ".png")).convert("RGBA")
        inner = int(SIZE * 0.8)
        icon = icon.resize((inner, inner), Image.LANCZOS)
        for tier in range(5):
            t = base.copy()
            t.alpha_composite(icon, ((SIZE - inner) // 2, (SIZE - inner) // 2))
            if tier:
                fr = os.path.join(ICONS, ("tier_c" if key in ROUND else "tier_t") + str(tier) + ".png")
                if os.path.exists(fr):
                    t.alpha_composite(Image.open(fr).convert("RGBA").resize((SIZE, SIZE), Image.LANCZOS))
            name = f"{key}_{tier}.png"
            t.convert("RGB").quantize(colors=96, method=Image.MEDIANCUT, dither=Image.Dither.NONE).save(os.path.join(OUT, name), optimize=True)
            lines.append(f'@{style_name(key, tier)} = PatchStyle(TexturePath: "tiles/{name}");')
    with open(os.path.join(ICONS, "Tiles.ui"), "w", encoding="utf-8") as f:
        f.write("// Плитки ячеек сеток предметов. Собрано tools/gen_slot_tiles.py — правь скрипт.\n" + "\n".join(lines) + "\n")
    print(len(keys), "иконок,", len(lines), "плиток")


main()
