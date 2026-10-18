"""Собирает разметку боевого меню (BattleMenu.ui), круговой сетки навыков (Progression.ui) и рюкзака (Backpack.ui).

Много однотипных слотов и узлов проще генерировать. Запуск из корня проекта: python3 tools/gen_battle_ui.py
Используются только проверенные в игре конструкции: Group, Label, $C.@SecondaryTextButton,
$C.@CancelTextButton, Anchor (Width/Height/Left/Top), LayoutMode Top/Left, FlexWeight, Background, Padding.
"""
import math
import os

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "hytale-plugin", "src", "main", "resources",
                       "Common", "UI", "Custom", "JrpgBattle")

QUEUE = 8
ENEMIES = 5
PARTY = 3
OPTIONS = 6

PANEL = "#0b111c(0.82)"
PANEL2 = "#141d2e(0.88)"
GOLD = "#e8c46a"
TEXT = "#e9eef6"
DIM = "#9fb0c6"
HP_BACK = "#3a1c22"
HP = "#5fd27a"
MP_BACK = "#1c2440"
MP = "#5aa2ff"

LOG_W = 340
LOG_LINES = 13
INS_W = 340
INS_LINES = 16

BAR_W_PARTY = 210
BAR_W_ENEMY = 150


def label(id_, text="", size=14, color=TEXT, bold=False, height=None, width=None, flex=False, wrap=False):
    anchor = []
    if width: anchor.append(f"Width: {width}")
    if height: anchor.append(f"Height: {height}")
    style = [f"FontSize: {size}", f"TextColor: {color}"]
    if bold: style.append("RenderBold: true")
    if wrap: style.append("Wrap: true")
    lines = [f"Label{(' #' + id_) if id_ else ''} {{", f'  Text: "{text}";']
    if anchor: lines.append(f"  Anchor: ({', '.join(anchor)});")
    if flex: lines.append("  FlexWeight: 1;")
    lines.append(f"  Style: ({', '.join(style)});")
    lines.append("}")
    return "\n".join(lines)


def spacer(w=None, h=None, flex=False):
    if flex:
        return "Group { FlexWeight: 1; }"
    if w:
        return f"Group {{ Anchor: (Width: {w}); }}"
    return f"Group {{ Anchor: (Height: {h}); }}"


def slot(id_, n):
    return f"Group #{id_} {{ Anchor: (Width: {n}, Height: {n}); }}"


def bar(prefix, width, back, fill, h=8):
    return f"""Group #{prefix}Back {{
  Anchor: (Width: {width}, Height: {h});
  LayoutMode: Left;
  Background: {back};
  Group #{prefix}Fill {{ Anchor: (Width: {width}, Height: {h}); Background: {fill}; }}
}}"""


def indent(text, n):
    pad = " " * n
    return "\n".join(pad + l if l.strip() else l for l in text.split("\n"))


def group(id_, props, children):
    head = f"Group{(' #' + id_) if id_ else ''} {{"
    body = "\n".join(props + [""] + children) if children else "\n".join(props)
    return head + "\n" + indent(body, 2) + "\n}"


def aura(id_, w, h, props, children, inset=2):
    """Обёртка с «аурой» для подсветки при наведении: #<id>Glow (мягкое золотое свечение) под содержимым.
    Свечение включает/выключает плагин (.Visible). Дети обёртки стоят по Anchor (Left/Top)."""
    glow = (f"Group #{id_}Glow {{\n"
            f"  Anchor: (Left: 0, Top: 0, Width: {w}, Height: {h});\n  Background: #e8c46a(0.22);\n"
            f"  Group {{ Anchor: (Left: 1, Top: 1, Width: {w - 2}, Height: {h - 2}); Background: #fff0b0(0.22); }}\n}}")
    content = group("", [f"Anchor: (Left: {inset}, Top: {inset}, Width: {w - 2 * inset}, Height: {h - 2 * inset});"]
                    + props, children)
    return group(id_, [f"Anchor: (Width: {w}, Height: {h});"], [glow, content])


def button(id_, text, w, h, cancel=False, left=None, top=None):
    kind = "CancelTextButton" if cancel else "SecondaryTextButton"
    anchor = ([f"Left: {left}", f"Top: {top}"] if left is not None else []) + [f"Width: {w}", f"Height: {h}"]
    return f'$C.@{kind} #{id_} {{ @Text = "{text}"; Anchor: ({", ".join(anchor)}); }}'


# ---------- бой ----------

def queue_slot(i):
    big = i == 0
    return group(f"Q{i}", [
        f"Anchor: (Height: {46 if big else 32});",
        "LayoutMode: Left;",
        f"Background: {'#2c3c5e(0.95)' if big else '#121a28(0.0)'};",
        "Padding: (Full: 3);",
    ], [
        slot(f"Q{i}Icon", 40 if big else 22),
        spacer(w=6),
        group("", ["FlexWeight: 1;", "LayoutMode: Top;"], [
            label(f"Q{i}Name", size=14 if big else 12, bold=big, height=20 if big else 15),
            label(f"Q{i}Sub", size=11, color=GOLD if big else DIM, height=16 if big else 12),
        ]),
    ])


def enemy_card(i):
    return aura(f"E{i}", 200, 70, [
        "LayoutMode: Top;",
        f"Background: {PANEL};",
        "Padding: (Full: 5);",
    ], [
        group("", ["Anchor: (Height: 26);", "LayoutMode: Left;"], [
            slot(f"E{i}Icon", 22),
            spacer(w=5),
            label(f"E{i}Name", size=13, bold=True, color="#ffb3a6", height=24, flex=True),
            button(f"E{i}Info", "?", 26, 22),
        ]),
        group("", ["Anchor: (Height: 12);", "LayoutMode: Left;"], [
            bar(f"E{i}Hp", BAR_W_ENEMY, HP_BACK, "#e0605a", h=5),
            spacer(w=4),
            label(f"E{i}Hp", size=10, color=DIM, height=12, flex=True),
        ]),
        group(f"E{i}Status", ["Anchor: (Height: 22);", "LayoutMode: Left;"], []),
    ])


def party_row(i):
    return aura(f"P{i}", 438, 64, [
        "LayoutMode: Left;",
        f"Background: {PANEL2};",
        "Padding: (Full: 4);",
    ], [
        group(f"P{i}Turn", ["Anchor: (Width: 4);", f"Background: {GOLD};"], []),
        spacer(w=5),
        slot(f"P{i}Icon", 40),
        spacer(w=6),
        group("", ["FlexWeight: 1;", "LayoutMode: Top;"], [
            group("", ["Anchor: (Height: 20);", "LayoutMode: Left;"], [
                label(f"P{i}Name", size=14, bold=True, color="#a9d8ff", width=110, height=20),
                label(f"P{i}Nums", size=11, color=DIM, height=20, flex=True),
            ]),
            bar(f"P{i}Hp", BAR_W_PARTY, HP_BACK, HP, h=6),
            spacer(h=2),
            bar(f"P{i}Mp", BAR_W_PARTY, MP_BACK, MP, h=4),
            spacer(h=2),
            group(f"P{i}Status", ["Anchor: (Height: 22);", "LayoutMode: Left;"], []),
        ]),
        button(f"P{i}Info", "?", 26, 22),
    ])


def option_row(i):
    return aura(f"Row{i}", 318, 32, ["LayoutMode: Left;"], [
        slot(f"Opt{i}Icon", 22),
        spacer(w=6),
        button(f"Opt{i}", "", 200, 28),
        spacer(w=6),
        label(f"Opt{i}Hint", size=11, color=GOLD, height=28, flex=True),
    ])


SPEECH = 3
SP_W, SP_H = 300, 76


def speech_layer(i):
    """Окно реплики над головой (как в FF): слой на весь экран, положение задают FlexWeight распорок
    #Sp<i>Top/#Sp<i>Bottom и #Sp<i>Left/#Sp<i>Right (доли экрана), так что размер экрана знать не нужно."""
    box = group(f"Sp{i}", [f"Anchor: (Width: {SP_W}, Height: {SP_H});", "LayoutMode: Top;"], [
        group("", [f"Anchor: (Height: {SP_H - 14});", "Background: #e8c46a(0.95);", "Padding: (Full: 2);"], [
            group("", ["LayoutMode: Top;", "Background: #0b111c(0.94);", "Padding: (Full: 5);"], [
                label(f"Sp{i}Name", "", 13, GOLD, True, height=17),
                label(f"Sp{i}Text", "", 14, TEXT, flex=True, wrap=True),
            ]),
        ]),
        group("", ["Anchor: (Height: 14);", "LayoutMode: Left;"], [
            spacer(flex=True),
            label(f"Sp{i}Tail", "v", 13, GOLD, True, width=14, height=14),
            spacer(flex=True),
        ]),
    ])
    return group(f"SpLayer{i}", ["Anchor: (Full: 0);", "LayoutMode: Top;"], [
        f"Group #Sp{i}Top {{ FlexWeight: 500; }}",
        group("", [f"Anchor: (Height: {SP_H});", "LayoutMode: Left;"], [
            f"Group #Sp{i}Left {{ FlexWeight: 500; }}",
            box,
            f"Group #Sp{i}Right {{ FlexWeight: 500; }}",
        ]),
        f"Group #Sp{i}Bottom {{ FlexWeight: 500; }}",
    ])


def battle():
    enemy_row = group("EnemyRow", ["Anchor: (Height: 70);", "LayoutMode: Left;"],
                      [spacer(flex=True)] + sum(([enemy_card(i), spacer(w=4)] for i in range(ENEMIES)), [])
                      + [spacer(flex=True)])
    banner = group("BannerRow", ["Anchor: (Height: 60);", "LayoutMode: Left;"], [
        spacer(flex=True),
        group("BannerBox", ["Anchor: (Width: 620, Height: 56);", "LayoutMode: Top;", "Background: #070b12(0.72);",
                            "Padding: (Full: 6);"], [
            label("Banner", "", 20, GOLD, True, height=24),
            label("BannerSub", "", 13, TEXT, height=18),
        ]),
        spacer(flex=True),
    ])
    queue = group("QueuePanel", ["Anchor: (Width: 176);", "LayoutMode: Top;", f"Background: {PANEL};",
                                 "Padding: (Full: 6);"],
                  [label("", "ОЧЕРЕДЬ", 12, GOLD, True, height=18)]
                  + sum(([queue_slot(i), spacer(h=2)] for i in range(QUEUE)), []))
    # Журнал боя слева (кто кому что сделал, цвета по типу события) и окно «Осмотр» справа
    # (выбранный боец: статусы с описанием и ходами, иммунитеты, слабости). Цвет строк ставит плагин
    # через #LogN.Style.TextColor / #InsN.Style.TextColor.
    log = group("LogPanel", [f"Anchor: (Width: {LOG_W});", "LayoutMode: Top;", f"Background: {PANEL};",
                             "Padding: (Full: 6);"],
                [label("", "ЖУРНАЛ БОЯ", 12, GOLD, True, height=18)]
                + [label(f"Log{i}", "", 11, TEXT, height=15) for i in range(LOG_LINES)])
    inspect = group("InsPanel", [f"Anchor: (Width: {INS_W});", "LayoutMode: Top;", f"Background: {PANEL};",
                                 "Padding: (Full: 6);"], [
        label("", "ОСМОТР И ЭФФЕКТЫ", 12, GOLD, True, height=18),
        label("InsTitle", "", 14, "#ffb3a6", True, height=20),
        label("InsSub", "", 11, DIM, height=15),
        spacer(h=3),
    ] + [label(f"Ins{i}", "", 11, TEXT, height=15) for i in range(INS_LINES)])
    middle = group("Middle", ["FlexWeight: 1;", "LayoutMode: Left;"], [
        group("", ["Anchor: (Width: 176);", "LayoutMode: Top;"], [queue, spacer(flex=True)]),
        spacer(flex=True),
        group("", [f"Anchor: (Width: {INS_W});", "LayoutMode: Top;"], [inspect, spacer(flex=True)]),
    ])
    party = group("PartyPanel", ["Anchor: (Width: 450);", "LayoutMode: Top;", f"Background: {PANEL};",
                                 "Padding: (Full: 6);"],
                  sum(([party_row(i), spacer(h=3)] for i in range(PARTY)), []))
    menu = group("MenuPanel", ["Anchor: (Width: 330);", "LayoutMode: Top;", f"Background: {PANEL};",
                               "Padding: (Full: 6);"], [
        group("", ["Anchor: (Height: 26);", "LayoutMode: Left;"], [
            label("MenuTitle", "", 14, TEXT, True, height=24, flex=True),
            button("Back", "Назад", 84, 24, cancel=True),
        ]),
    ] + [option_row(i) for i in range(OPTIONS)] + [label("Error", "", 11, "#ff7070", height=16)])
    info = group("InfoPanel", ["Anchor: (Width: 540);", "LayoutMode: Top;", f"Background: {PANEL2};",
                               "Padding: (Full: 8);"], [
        group("", ["Anchor: (Height: 26);", "LayoutMode: Left;"], [
            slot("InfoIcon", 22),
            spacer(w=6),
            label("InfoTitle", "", 15, GOLD, True, height=24, flex=True),
        ]),
        label("InfoText", "", 12, TEXT, flex=True, wrap=True),
    ])
    # Журнал боя — в левом нижнем углу (свободное место слева от партии).
    bottom = group("BottomBar", ["Anchor: (Height: 232);", "LayoutMode: Left;"], [
        log, spacer(flex=True), party, spacer(w=8), menu, spacer(w=8), info, spacer(flex=True),
    ])
    main = group("", ["Anchor: (Full: 0);", "LayoutMode: Top;", "Padding: (Full: 10);"],
                 [enemy_row, spacer(h=6), banner, middle, bottom])
    root = group("", [], [main] + [speech_layer(i) for i in range(SPEECH)])
    text = ('$C = "../Common.ui";\n\n'
            "// Боевое меню JRPG. Файл собран скриптом tools/gen_battle_ui.py — правь скрипт, а не этот файл.\n"
            "// Сверху враги и баннер хода, слева очередь и журнал боя, справа осмотр бойца,\n"
            "// внизу одной полосой: партия, команды, описание.\n"
            "// Иконки вставляются в пустые слоты (#...Icon) из Icons/*.ui.\n\n"
            + root + "\n")
    write("BattleMenu.ui", text)


# ---------- круговая сетка навыков ----------

GRID = 640
CENTER = GRID // 2
RADII = {0: 0, 1: 110, 2: 202, 3: 280}
NODE = 72      # контейнер узла
# Цвета секторов ролей (как в Progression.Grid.sector): защитник, целитель, боец; мосты — серые.
SECTOR_LOCK = {"T": "#16243c(0.96)", "H": "#14301c(0.96)", "D": "#341616(0.96)", "X": "#262630(0.96)"}
SECTOR_DOT = {"T": "#3a5f9a(0.9)", "H": "#3a8a4e(0.9)", "D": "#9a3a3a(0.9)"}
TABS = 3


def ring(i):
    return 0 if i == 0 else 1 if i <= 6 else 2 if i <= 18 else 3


def angle(i):
    r = ring(i)
    if r == 1: return -90 + 60 * (i - 1)
    if r == 2: return -90 + 30 * (i - 7)
    if r == 3: return -60 + 60 * (i - 19)
    return 0


def neighbors(i):
    r = ring(i)
    if r == 0: return list(range(1, 7))
    if r == 1: return [0, 7 + 2 * (i - 1)]
    if r == 2:
        j = i - 7
        out = [7 + (j + 11) % 12, 7 + (j + 1) % 12]
        out.append(1 + j // 2 if j % 2 == 0 else 19 + j // 2)
        return out
    return [7 + 2 * (i - 19) + 1]


def sector(i):
    """Сектор узла: T/H/D или X для мостов (как Progression.Grid.sector)."""
    a = (round(angle(i)) + 120) % 360
    if a % 120 == 0:
        return "X"
    return "THD"[a // 120]


def sector_at(deg):
    a = (deg + 120) % 360
    return "THD"[int(a // 120) % 3]


def pos(i):
    a = math.radians(angle(i))
    r = RADII[ring(i)]
    return CENTER + r * math.cos(a), CENTER + r * math.sin(a)


def edges():
    out = []
    for a in range(25):
        for b in neighbors(a):
            if a < b: out.append((a, b))
    return out


def edge_dots(a, b, color, step=11, gap=36):
    (x1, y1), (x2, y2) = pos(a), pos(b)
    length = math.hypot(x2 - x1, y2 - y1)
    n = max(1, int((length - 2 * gap) / step))
    dots = []
    for k in range(n + 1):
        t = (gap + k * (length - 2 * gap) / max(1, n)) / length
        x, y = x1 + (x2 - x1) * t, y1 + (y2 - y1) * t
        dots.append(f"Group {{ Anchor: (Left: {int(x) - 2}, Top: {int(y) - 2}, Width: 4, Height: 4); Background: {color}; }}")
    return dots


def full(id_, children):
    return group(id_, [f"Anchor: (Left: 0, Top: 0, Width: {GRID}, Height: {GRID});"], children)


def node(i):
    x, y = pos(i)
    left, top = int(x) - NODE // 2, int(y) - NODE // 2
    lock = SECTOR_LOCK[sector(i)] if i else SECTOR_LOCK["X"]
    return group(f"G{i}", [f"Anchor: (Left: {left}, Top: {top}, Width: {NODE}, Height: {NODE});"], [
        # выбранный узел — золотая аура вокруг
        f"Group #G{i}Sel {{ Anchor: (Left: 0, Top: 0, Width: 72, Height: 72); Background: #e8c46a(0.30);\n"
        f"  Group {{ Anchor: (Left: 2, Top: 2, Width: 68, Height: 68); Background: #fff0b0(0.55); }}\n}}",
        f"Group #G{i}Lock {{ Anchor: (Left: 4, Top: 4, Width: 64, Height: 64); Background: {lock}; }}",
        f"Group #G{i}Avail {{ Anchor: (Left: 4, Top: 4, Width: 64, Height: 64); Background: #3d7bd9; }}",
        f"Group #G{i}Open {{ Anchor: (Left: 4, Top: 4, Width: 64, Height: 64); Background: {GOLD}; }}",
        # кнопка шире иконки: край кнопки вокруг иконки всегда кликается
        button(f"G{i}Btn", "", 52, 52, left=10, top=10),
        f"Group #G{i}Icon {{ Anchor: (Left: 16, Top: 16, Width: 40, Height: 40); }}",
    ])


def sector_label(text, color, left, top, width=170):
    return (f'Label {{ Text: "{text}"; Anchor: (Left: {left}, Top: {top}, Width: {width}, Height: 22); '
            f'Style: (FontSize: 14, TextColor: {color}, RenderBold: true); }}')


def progression():
    tabs = group("Tabs", ["Anchor: (Height: 34);", "LayoutMode: Left;"],
                 sum(([button(f"Tab{i}", "", 190, 32), spacer(w=6)] for i in range(TABS)), [])
                 + [spacer(flex=True), label("Points", "", 15, GOLD, True, width=260, height=32)])
    hero = group("HeroCol", ["Anchor: (Width: 290);", "LayoutMode: Top;", f"Background: {PANEL2};", "Padding: (Full: 10);"], [
        group("", ["Anchor: (Height: 92);", "LayoutMode: Left;"], [
            slot("HeroIcon", 88),
            spacer(w=10),
            group("", ["FlexWeight: 1;", "LayoutMode: Top;"], [
                label("HeroName", "", 20, TEXT, True, height=28),
                label("HeroTitle", "", 13, GOLD, height=20),
                label("HeroLevel", "", 12, DIM, height=36, wrap=True),
            ]),
        ]),
        bar("Exp", 266, "#2a2410", GOLD, h=6),
        spacer(h=8),
        label("HeroStats", "", 12, TEXT, height=96, wrap=True),
        label("HeroPassives", "", 12, "#c58cff", height=60, wrap=True),
        label("Known", "", 12, DIM, flex=True, wrap=True),
        button("AddExp", "+100 опыта (тест)", 266, 30),
        spacer(h=4),
        button("Respec", "Сбросить сетку", 266, 30),
    ])
    edge_list = edges()
    off = full("EdgesOff", sum((edge_dots(a, b, "#4a5a78(0.8)") for a, b in edge_list), []))
    on = [full(f"Edge{k}", edge_dots(a, b, GOLD)) for k, (a, b) in enumerate(edge_list)]
    rings = full("Rings", sum((ring_dots(r) for r in (1, 2, 3)), []))
    labels = full("Sectors", [
        sector_label("ЗАЩИТНИК", "#8fbaff", GRID - 150, 8),
        sector_label("ЦЕЛИТЕЛЬ", "#8fe0a0", GRID - 150, GRID - 30),
        sector_label("БОЕЦ", "#ff9a8a", 12, 8),
        sector_label("мосты — гибриды", "#9fb0c6", 12, GRID - 30),
    ])
    grid = group("Grid", [f"Anchor: (Width: {GRID}, Height: {GRID});", f"Background: {PANEL};"],
                 [rings, labels, off] + on + [node(i) for i in range(25)])
    detail = group("DetailCol", ["FlexWeight: 1;", "LayoutMode: Top;", f"Background: {PANEL2};", "Padding: (Full: 10);"], [
        group("", ["Anchor: (Height: 44);", "LayoutMode: Left;"], [
            slot("SelIcon", 40),
            spacer(w=8),
            group("", ["FlexWeight: 1;", "LayoutMode: Top;"], [
                label("SelTitle", "", 16, GOLD, True, height=22),
                label("SelKind", "", 12, DIM, height=18),
            ]),
        ]),
        spacer(h=6),
        label("SelText", "", 12, TEXT, flex=True, wrap=True),
        group("", ["Anchor: (Height: 30);", "LayoutMode: Left;"], [
            button("PrevNode", "<", 60, 28), spacer(w=6),
            label("", "соседние узлы", 12, DIM, height=28, flex=True),
            button("NextNode", ">", 60, 28),
        ]),
        spacer(h=6),
        button("Learn", "Открыть узел", 250, 34),
        spacer(h=4),
        label("Message", "", 12, "#ffd27a", height=36, wrap=True),
        spacer(h=4),
        button("Close", "Закрыть", 250, 32, cancel=True),
    ])
    window = group("Window", ["Anchor: (Width: 1250);", "LayoutMode: Top;", "Background: #070b12(0.95);", "Padding: (Full: 12);"], [
        group("", ["Anchor: (Height: 32);", "LayoutMode: Left;"], [
            label("", "СЕТКА НАВЫКОВ", 22, GOLD, True, height=30, flex=True),
            label("", "открывай узлы рядом с уже открытыми", 12, DIM, width=330, height=30),
        ]),
        tabs,
        spacer(h=8),
        group("", [f"Anchor: (Height: {GRID});", "LayoutMode: Left;"], [hero, spacer(w=10), grid, spacer(w=10), detail]),
    ])
    root = group("", ["LayoutMode: Top;"], [
        spacer(flex=True),
        group("", [f"Anchor: (Height: {GRID + 110});", "LayoutMode: Left;"], [spacer(flex=True), window, spacer(flex=True)]),
        spacer(flex=True),
    ])
    text = ('$C = "../Common.ui";\n\n'
            "// Круговая сетка навыков. Собрано tools/gen_battle_ui.py — правь скрипт.\n"
            "// Узел #G<n>: слои #G<n>Sel (выбран), #G<n>Lock/Avail/Open (состояние), кнопка #G<n>Btn, иконка #G<n>Icon.\n"
            "// Рёбра: серые точки #EdgesOff всегда, золотые #Edge<k> — когда оба конца открыты.\n\n"
            + root + "\n")
    write("Progression.ui", text)
    # порядок рёбер нужен плагину: печатаем для проверки с Progression.Grid.edges()
    print("edges:", edge_list)


# ---------- окно перед боем ----------

def prebattle():
    enemies = group("Enemies", ["Anchor: (Height: 74);", "LayoutMode: Left;"], [spacer(flex=True)] + sum((
        [group(f"En{i}", ["Anchor: (Width: 112);", "LayoutMode: Top;"], [
            group("", ["Anchor: (Height: 42);", "LayoutMode: Left;"], [spacer(w=34), slot(f"En{i}Icon", 40)]),
            label(f"En{i}Name", "", 12, "#ffb3a6", True, height=16),
            label(f"En{i}Hp", "", 11, DIM, height=14),
        ]), spacer(w=4)] for i in range(ENEMIES)), []) + [spacer(flex=True)])
    buttons = group("", ["Anchor: (Height: 50);", "LayoutMode: Left;"], [
        spacer(flex=True),
        aura("FightBox", 244, 48, ["LayoutMode: Left;"], [button("Fight", "Сражаться", 236, 40)], inset=4),
        spacer(w=18),
        aura("FleeBox", 244, 48, ["LayoutMode: Left;"], [button("Flee", "Сбежать", 236, 40, cancel=True)], inset=4),
        spacer(flex=True),
    ])
    window = group("Window", ["Anchor: (Width: 640);", "LayoutMode: Top;", "Background: #070b12(0.94);",
                              "Padding: (Full: 16);"], [
        label("Title", "", 22, GOLD, True, height=30),
        label("Sub", "", 13, TEXT, height=36, wrap=True),
        spacer(h=6),
        enemies,
        spacer(h=8),
        label("Chance", "", 13, TEXT, height=38, wrap=True),
        spacer(h=8),
        buttons,
    ])
    root = group("", ["LayoutMode: Top;"], [
        spacer(flex=True),
        group("", ["Anchor: (Height: 300);", "LayoutMode: Left;"], [spacer(flex=True), window, spacer(flex=True)]),
        spacer(flex=True),
    ])
    write("PreBattle.ui", '$C = "../Common.ui";\n\n'
          "// Окно перед боем: «Сражаться / Сбежать». Собрано tools/gen_battle_ui.py — правь скрипт.\n\n" + root + "\n")


# ---------- рюкзак ----------

BAG_COLS, BAG_ROWS, CELL = 6, 4, 86
BAG_TABS = ["Все", "Зелья", "Боевые", "Материалы", "Снаряжение"]
BAG_SORTS = [("SortKind", "Тип"), ("SortName", "Имя"), ("SortCount", "Кол-во")]


def bag_cell(i):
    left, top = (i % BAG_COLS) * CELL, (i // BAG_COLS) * CELL
    return group(f"S{i}", [f"Anchor: (Left: {left}, Top: {top}, Width: {CELL - 6}, Height: {CELL - 6});"], [
        f"Group #S{i}Sel {{ Anchor: (Left: 0, Top: 0, Width: 80, Height: 80); Background: #e8c46a(0.35);\n"
        f"  Group {{ Anchor: (Left: 2, Top: 2, Width: 76, Height: 76); Background: #fff0b0(0.45); }}\n}}",
        f"Group #S{i}Back {{ Anchor: (Left: 4, Top: 4, Width: 72, Height: 72); Background: #1a2438; }}",
        button(f"S{i}Btn", "", 64, 64, left=8, top=8),
        f"Group #S{i}Icon {{ Anchor: (Left: 16, Top: 14, Width: 48, Height: 48); }}",
        f'Label #S{i}Count {{ Text: ""; Anchor: (Left: 44, Top: 58, Width: 34, Height: 18); '
        f"Style: (FontSize: 13, TextColor: #ffffff, RenderBold: true); }}",
    ])


def backpack():
    tabs = group("Tabs", ["Anchor: (Height: 34);", "LayoutMode: Left;"],
                 sum(([button(f"Tab{i}", t, 112, 32), spacer(w=5)] for i, t in enumerate(BAG_TABS)), [])
                 + [spacer(flex=True), label("", "Сортировка:", 13, DIM, width=100, height=32)]
                 + sum(([button(bid, t, 96, 32), spacer(w=4)] for bid, t in BAG_SORTS), []))
    grid_w, grid_h = BAG_COLS * CELL, BAG_ROWS * CELL
    grid = group("Grid", [f"Anchor: (Width: {grid_w}, Height: {grid_h});"], [bag_cell(i) for i in range(BAG_COLS * BAG_ROWS)])
    left = group("GridCol", [f"Anchor: (Width: {grid_w + 20});", "LayoutMode: Top;", f"Background: {PANEL};",
                             "Padding: (Full: 10);"], [
        grid,
        spacer(h=6),
        group("", ["Anchor: (Height: 30);", "LayoutMode: Left;"], [
            button("PrevPage", "<", 60, 28), spacer(w=6),
            label("Page", "", 13, DIM, height=28, flex=True),
            button("NextPage", ">", 60, 28),
        ]),
        label("Empty", "", 13, DIM, height=24),
    ])
    detail = group("DetailCol", ["FlexWeight: 1;", "LayoutMode: Top;", f"Background: {PANEL2};", "Padding: (Full: 12);"], [
        group("", ["Anchor: (Height: 68);", "LayoutMode: Left;"], [
            slot("SelIcon", 64),
            spacer(w=10),
            group("", ["FlexWeight: 1;", "LayoutMode: Top;"], [
                label("SelName", "", 18, GOLD, True, height=26),
                label("SelKind", "", 12, DIM, height=20),
            ]),
        ]),
        spacer(h=8),
        label("SelText", "", 13, TEXT, flex=True, wrap=True),
        label("Message", "", 12, "#ffd27a", height=36, wrap=True),
        button("Drop", "Выбросить 1", 300, 32),
        spacer(h=6),
        button("Close", "Закрыть", 300, 32, cancel=True),
    ])
    window = group("Window", ["Anchor: (Width: 1000);", "LayoutMode: Top;", "Background: #070b12(0.95);",
                              "Padding: (Full: 12);"], [
        group("", ["Anchor: (Height: 32);", "LayoutMode: Left;"], [
            label("", "РЮКЗАК", 22, GOLD, True, height=30, flex=True),
            label("Total", "", 13, DIM, width=360, height=30),
        ]),
        tabs,
        spacer(h=8),
        group("", [f"Anchor: (Height: {grid_h + 84});", "LayoutMode: Left;"], [left, spacer(w=10), detail]),
    ])
    root = group("", ["LayoutMode: Top;"], [
        spacer(flex=True),
        group("", [f"Anchor: (Height: {grid_h + 170});", "LayoutMode: Left;"], [spacer(flex=True), window, spacer(flex=True)]),
        spacer(flex=True),
    ])
    write("Backpack.ui", '$C = "../Common.ui";\n\n'
          "// Рюкзак: вкладки-фильтры #Tab0..4, сортировка #SortKind/#SortName/#SortCount, ячейки #S<n>\n"
          "// (#S<n>Sel — выбрана, #S<n>Btn — кнопка, #S<n>Icon — иконка, #S<n>Count — количество).\n"
          "// Собрано tools/gen_battle_ui.py — правь скрипт.\n\n" + root + "\n")


# ---------- экипировка ----------

EQ_SLOTS = 8
EQ_ITEMS = 8
EQ_CMP = 12
EQ_ROW = 38


def sel_row(id_, w, h, text_w, extra=None):
    """Строка-кнопка с подсветкой выбора #<id>Sel под ней; extra — подпись справа (#<id>Q)."""
    kids = [f"Group #{id_}Sel {{ Anchor: (Left: 0, Top: 0, Width: {w}, Height: {h}); Background: #e8c46a(0.35);\n"
            f"  Group {{ Anchor: (Left: 2, Top: 2, Width: {w - 4}, Height: {h - 4}); Background: #fff0b0(0.30); }}\n}}",
            button(f"{id_}Btn", "", text_w, h - 6, left=3, top=3)]
    if extra:
        kids.append(f'Label #{id_}Q {{ Text: ""; Anchor: (Left: {text_w + 10}, Top: 9, Width: {w - text_w - 12}, Height: 20); '
                    f"Style: (FontSize: 12, TextColor: {DIM}, RenderBold: true); }}")
    return group(id_, [f"Anchor: (Width: {w}, Height: {h});"], kids)


def equipment():
    tabs = group("Tabs", ["Anchor: (Height: 34);", "LayoutMode: Left;"],
                 sum(([button(f"Tab{i}", "", 200, 32), spacer(w=6)] for i in range(PARTY)), [])
                 + [spacer(flex=True), label("Hint", "", 12, DIM, width=330, height=32, wrap=True)])
    left = group("SlotCol", ["Anchor: (Width: 330);", "LayoutMode: Top;", f"Background: {PANEL};", "Padding: (Full: 8);"], [
        label("HeroName", "", 18, GOLD, True, height=24),
        label("HeroSub", "", 12, DIM, height=18),
        spacer(h=4),
    ] + [sel_row(f"Slot{i}", 314, EQ_ROW, 308) for i in range(EQ_SLOTS)] + [
        spacer(h=4),
        label("HeroStats", "", 12, TEXT, height=52, wrap=True),
    ])
    middle = group("ListCol", ["FlexWeight: 1;", "LayoutMode: Top;", f"Background: {PANEL2};", "Padding: (Full: 8);"], [
        label("ListTitle", "", 16, GOLD, True, height=24),
        label("ListSub", "", 12, DIM, height=18),
        spacer(h=4),
    ] + [sel_row(f"Item{i}", 380, EQ_ROW, 262, extra=True) for i in range(EQ_ITEMS)] + [
        spacer(h=4),
        group("", ["Anchor: (Height: 30);", "LayoutMode: Left;"], [
            button("PrevPage", "<", 50, 28), spacer(w=6),
            label("Page", "", 12, DIM, height=28, flex=True),
            button("NextPage", ">", 50, 28),
        ]),
        label("Empty", "", 12, DIM, height=40, wrap=True),
    ])
    right = group("CmpCol", ["Anchor: (Width: 360);", "LayoutMode: Top;", f"Background: {PANEL};", "Padding: (Full: 10);"], [
        label("CmpTitle", "", 17, GOLD, True, height=24),
        label("CmpSub", "", 12, DIM, height=18),
        spacer(h=4),
    ] + [label(f"Cmp{i}", "", 13, TEXT, height=19) for i in range(EQ_CMP)] + [
        label("CmpDesc", "", 12, DIM, height=58, wrap=True),
        label("Message", "", 12, "#ffd27a", height=32, wrap=True),
        group("", ["Anchor: (Height: 34);", "LayoutMode: Left;"], [
            button("Equip", "Надеть", 168, 32), spacer(w=4), button("Unequip", "Снять", 168, 32)]),
        spacer(h=4),
        group("", ["Anchor: (Height: 34);", "LayoutMode: Left;"], [
            button("Back", "Меню героя", 168, 32), spacer(w=4), button("Close", "Закрыть", 168, 32, cancel=True)]),
    ])
    body_h = 24 + 18 + 4 + EQ_SLOTS * EQ_ROW + 4 + 52 + 16
    window = group("Window", ["Anchor: (Width: 1130);", "LayoutMode: Top;", "Background: #070b12(0.95);",
                              "Padding: (Full: 12);"], [
        group("", ["Anchor: (Height: 32);", "LayoutMode: Left;"], [
            label("", "ЭКИПИРОВКА", 22, GOLD, True, height=30, flex=True),
        ]),
        tabs,
        spacer(h=8),
        group("", [f"Anchor: (Height: {body_h});", "LayoutMode: Left;"], [left, spacer(w=8), middle, spacer(w=8), right]),
    ])
    root = group("", ["LayoutMode: Top;"], [
        spacer(flex=True),
        group("", [f"Anchor: (Height: {body_h + 110});", "LayoutMode: Left;"], [spacer(flex=True), window, spacer(flex=True)]),
        spacer(flex=True),
    ])
    write("Equipment.ui", '$C = "../Common.ui";\n\n'
          "// Экипировка: вкладки героев #Tab0..2, слоты #Slot0..7 (#Slot<n>Sel, #Slot<n>Btn), вещи из рюкзака\n"
          "// #Item0..7 (+ #Item<n>Q — качество), сравнение #Cmp0..11, кнопки #Equip/#Unequip/#Back/#Close.\n"
          "// Собрано tools/gen_battle_ui.py — правь скрипт.\n\n" + root + "\n")


def ring_dots(r):
    radius = RADII[r]
    n = int(2 * math.pi * radius / 18)
    dots = []
    for k in range(n):
        a = 2 * math.pi * k / n
        x, y = CENTER + radius * math.cos(a), CENTER + radius * math.sin(a)
        color = SECTOR_DOT[sector_at(math.degrees(a))]
        dots.append(f"Group {{ Anchor: (Left: {int(x) - 1}, Top: {int(y) - 1}, Width: 3, Height: 3); Background: {color}; }}")
    return dots


def write(name, text):
    path = os.path.join(OUT_DIR, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print("written", os.path.abspath(path), len(text.splitlines()), "lines")


if __name__ == "__main__":
    battle()
    progression()
    prebattle()
    backpack()
    equipment()
