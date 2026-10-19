"""Собирает разметку боевого меню (BattleMenu.ui), круговой сетки навыков (Progression.ui) и рюкзака (Backpack.ui).

Много однотипных слотов и узлов проще генерировать. Запуск из корня проекта: python3 tools/gen_battle_ui.py
Используются только проверенные в игре конструкции: Group, Label, $C.@SecondaryTextButton,
$C.@CancelTextButton, Anchor (Width/Height/Left/Top), LayoutMode Top/Left, FlexWeight, Background, Padding.
"""
import math
import os
import re

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

LOG_W = 440
LOG_LINES = 14
INS_W = 360
INS_LINES = 16

BAR_W_PARTY = 220
BAR_W_ENEMY = 150


def label(id_, text="", size=14, color=TEXT, bold=False, height=None, width=None, flex=False, wrap=False, center=False,
          font=None):
    anchor = []
    if width: anchor.append(f"Width: {width}")
    if height: anchor.append(f"Height: {height}")
    style = [f"FontSize: {size}", f"TextColor: {color}"]
    if bold: style.append("RenderBold: true")
    if wrap: style.append("Wrap: true")
    if center: style.append("HorizontalAlignment: Center")
    if font: style.append(f'FontName: "{font}"')
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
    content = group(f"{id_}Body", [f"Anchor: (Left: {inset}, Top: {inset}, Width: {w - 2 * inset}, Height: {h - 2 * inset});"]
                    + props, children)
    return group(id_, [f"Anchor: (Width: {w}, Height: {h});"], [glow, content])


def button(id_, text, w, h, cancel=False, left=None, top=None, skin=False):
    kind = "CancelTextButton" if cancel else "SecondaryTextButton"
    anchor = ([f"Left: {left}", f"Top: {top}"] if left is not None else []) + [f"Width: {w}", f"Height: {h}"]
    if skin and SKIN_BUTTONS:
        # своя кнопка на текстурах jr_btn*.png; стиль @JrButton объявлен в начале BattleMenu.ui
        return f'TextButton #{id_} {{ Text: "{text}"; Anchor: ({", ".join(anchor)}); Style: @JrButton; }}'
    return f'$C.@{kind} #{id_} {{ @Text = "{text}"; Anchor: ({", ".join(anchor)}); }}'


# ---------- бой ----------
# Шрифт заголовков и кнопки на текстурах задаются только разметкой: менять Style.FontName из плагина клиент
# запрещает (краш 0.9.0). TITLE_FONT = None — обычный шрифт; SKIN_BUTTONS = False — кнопки игры.
TITLE_FONT = None  # "Secondary" после проверки в игре
SKIN_BUTTONS = False  # True после проверки в игре


def title_font():
    return f', FontName: "{TITLE_FONT}"' if TITLE_FONT else ""


def jr_button_style():
    def state(tex, color):
        return (f'({tex and f"Background: (TexturePath: {chr(34)}{tex}{chr(34)}, Border: 12), "}'
                f"LabelStyle: (FontSize: 13, TextColor: {color}, RenderBold: true, "
                f"HorizontalAlignment: Center, VerticalAlignment: Center))")
    return ("@JrButton = TextButtonStyle(\n"
            f"  Default: {state('jr_btn.png', '#f3e7c9')},\n"
            f"  Hovered: {state('jr_btn_hover.png', '#fff4d0')},\n"
            f"  Pressed: {state('jr_btn_press.png', '#e8c46a')},\n"
            f"  Disabled: {state('jr_btn_off.png', '#8a8478')}\n"
            ");\n\n") if SKIN_BUTTONS else ""
# Стиль «синий камень и золото»: окна на текстуре jr_panel*.png (9-slice, рисует tools/gen_frames.py),
# полоски на jr_bar_*.png. Текст крупнее и светлее, чем в 0.8: основной 14, мелкий не меньше 12.

INK_TITLE = "#e8c46a"
PARTY_NAME = "#a9d8ff"
ENEMY_NAME = "#ffb3a6"
SUB = "#b4c2d6"


def skin(name, border=22):
    return f'Background: (TexturePath: "{name}.png", Border: {border});'


def tex(name):
    return f'Background: (TexturePath: "{name}.png");'


def tbar(prefix, width, fill, h=8):
    """Полоска на текстурах: тёмная подложка jr_bar_back и цветная заливка (ширину заливки ставит плагин)."""
    return f"""Group #{prefix}Back {{
  Anchor: (Width: {width}, Height: {h});
  LayoutMode: Left;
  {tex("jr_bar_back")}
  Group #{prefix}Fill {{ Anchor: (Width: {width}, Height: {h}); {tex(fill)} }}
}}"""


def layered(id_, w, h, layers, content_props, children, inset=0):
    """Контейнер с наложенными слоями: #<id>Glow (подсветка при наведении), фоны из layers
    (список (id, текстура)), сверху содержимое. Видимость слоёв переключает плагин."""
    kids = [f"Group #{id_}Glow {{\n  Anchor: (Left: 0, Top: 0, Width: {w}, Height: {h});\n"
            f"  {skin('jr_panel_gold')}\n}}"]
    for lid, texture in layers:
        kids.append(f"Group #{lid} {{ Anchor: (Left: {2 if lid.endswith('Bg') else 0}, Top: {2 if lid.endswith('Bg') else 0}, "
                    f"Width: {w - (4 if lid.endswith('Bg') else 0)}, Height: {h - (4 if lid.endswith('Bg') else 0)}); {skin(texture)} }}")
    kids.append(group("", [f"Anchor: (Left: {inset}, Top: {inset}, Width: {w - 2 * inset}, Height: {h - 2 * inset});"]
                      + content_props, children))
    move = group(f"{id_}Move", [f"Anchor: (Left: 0, Top: 0, Width: {w}, Height: {h});"], kids)
    return group(id_, [f"Anchor: (Width: {w}, Height: {h});"], [move])


def panel_title(text, id_=None, color=INK_TITLE):
    return label(id_ or "", text, 13, color, True, height=20)


QW_LEFT, QW_W, QW_CUR_H, QW_H, QW_GAP, QW_TOP = 11, 214, 46, 34, 3, 36


def qw_top(i):
    return QW_TOP if i == 0 else QW_TOP + QW_CUR_H + QW_GAP + (i - 1) * (QW_H + QW_GAP)


def queue_slot(i):
    """Строка очереди в окне. Стоит по абсолютным координатам: плагин двигает её (Anchor) при смене хода —
    ходивший уезжает влево, остальные поднимаются (BattleMenuPage, геометрия QW_* там же)."""
    big = i == 0
    return group(f"Q{i}", [
        f"Anchor: (Left: {QW_LEFT}, Top: {qw_top(i)}, Width: {QW_W}, Height: {QW_CUR_H if big else QW_H});",
        "LayoutMode: Left;",
        skin("jr_panel_gold" if big else "jr_panel", 22),
        "Padding: (Left: 8, Right: 6, Top: 4, Bottom: 4);",
    ], [
        slot(f"Q{i}Icon", 38 if big else 24),
        spacer(w=8),
        group("", ["FlexWeight: 1;", "LayoutMode: Top;"], [
            label(f"Q{i}Name", size=15 if big else 13, bold=True, height=20 if big else 16),
            label(f"Q{i}Sub", size=12, color=GOLD if big else SUB, height=16 if big else 12),
        ]),
    ])


ENEMY_W, ENEMY_H = 214, 84


def enemy_card(i):
    return layered(f"E{i}", ENEMY_W, ENEMY_H, [(f"E{i}Bg", "jr_panel_enemy")], [
        "LayoutMode: Top;", "Padding: (Left: 11, Right: 9, Top: 9, Bottom: 7);",
    ], [
        group("", ["Anchor: (Height: 28);", "LayoutMode: Left;"], [
            slot(f"E{i}Icon", 26),
            spacer(w=6),
            label(f"E{i}Name", size=15, bold=True, color=ENEMY_NAME, height=26, flex=True, font=TITLE_FONT),
            button(f"E{i}Info", "?", 26, 24, skin=True),
        ]),
        group("", ["Anchor: (Height: 14);", "LayoutMode: Left;"], [
            tbar(f"E{i}Hp", BAR_W_ENEMY, "jr_bar_ehp", h=7),
            spacer(w=6),
            label(f"E{i}Hp", size=12, color=SUB, height=14, flex=True),
        ]),
        spacer(h=2),
        group(f"E{i}Status", ["Anchor: (Height: 22);", "LayoutMode: Left;"], []),
    ])


PARTY_W, PARTY_H = 448, 72


def party_row(i):
    return layered(f"P{i}", PARTY_W, PARTY_H, [(f"P{i}Bg", "jr_panel_party"), (f"P{i}Turn", "jr_panel_gold")], [
        "LayoutMode: Left;", "Padding: (Left: 10, Right: 9, Top: 8, Bottom: 6);",
    ], [
        slot(f"P{i}Icon", 44),
        spacer(w=8),
        group("", ["FlexWeight: 1;", "LayoutMode: Top;"], [
            group("", ["Anchor: (Height: 20);", "LayoutMode: Left;"], [
                label(f"P{i}Name", size=16, bold=True, color=PARTY_NAME, width=120, height=20, font=TITLE_FONT),
                label(f"P{i}Nums", size=12, color=SUB, height=20, flex=True),
            ]),
            spacer(h=2),
            tbar(f"P{i}Hp", BAR_W_PARTY, "jr_bar_hp", h=8),
            spacer(h=2),
            tbar(f"P{i}Mp", BAR_W_PARTY, "jr_bar_mp", h=5),
            spacer(h=2),
            group(f"P{i}Status", ["Anchor: (Height: 22);", "LayoutMode: Left;"], []),
        ]),
        button(f"P{i}Info", "?", 26, 24, skin=True),
    ])


def option_row(i):
    return aura(f"Row{i}", 324, 36, ["LayoutMode: Left;"], [
        slot(f"Opt{i}Icon", 26),
        spacer(w=6),
        button(f"Opt{i}", "", 206, 30, skin=True),
        spacer(w=6),
        label(f"Opt{i}Hint", size=12, color=GOLD, height=30, flex=True),
    ])


SPEECH = 3
SP_W, SP_H = 300, 80


def speech_layer(i):
    """Окно реплики над головой (как в FF): слой на весь экран, положение задаёт Anchor распорок
    #Sp<i>Top (высота) и #Sp<i>Left (ширина) в пикселях экрана 1920×1080, #Sp<i>Bottom/#Sp<i>Right тянутся."""
    box = group(f"Sp{i}", [f"Anchor: (Width: {SP_W}, Height: {SP_H});", "LayoutMode: Top;"], [
        group("", [f"Anchor: (Height: {SP_H - 14});", "LayoutMode: Top;", skin("jr_bubble", 16),
                   "Padding: (Left: 12, Right: 10, Top: 7, Bottom: 6);"], [
            label(f"Sp{i}Name", "", 14, GOLD, True, height=18),
            label(f"Sp{i}Text", "", 15, TEXT, flex=True, wrap=True),
        ]),
        group("", ["Anchor: (Height: 14);", "LayoutMode: Left;"], [
            spacer(flex=True),
            label(f"Sp{i}Tail", "v", 14, GOLD, True, width=14, height=14),
            spacer(flex=True),
        ]),
    ])
    return group(f"SpLayer{i}", ["Anchor: (Full: 0);", "LayoutMode: Top;"], [
        f"Group #Sp{i}Top {{ Anchor: (Height: 500); }}",
        group("", [f"Anchor: (Height: {SP_H});", "LayoutMode: Left;"], [
            f"Group #Sp{i}Left {{ Anchor: (Width: 800); }}",
            box,
            f"Group #Sp{i}Right {{ FlexWeight: 1; }}",
        ]),
        f"Group #Sp{i}Bottom {{ FlexWeight: 1; }}",
    ])


HIT_W, HIT_H = 140, 180


def hit_layer(i):
    """Невидимая кнопка над врагом в мире (наведение — кольцо и описание, клик — выбор цели). Пока открыто
    окно боя, клики в мир клиент не шлёт, поэтому мышь ловит окно: плагин ставит кнопку туда, где враг на экране
    (BattleCamera.project), через Anchor распорок #Hit<i>Top/#Hit<i>Left и размер самой кнопки #Hit<i>."""
    return group(f"HitLayer{i}", ["Anchor: (Full: 0);", "LayoutMode: Top;"], [
        f"Group #Hit{i}Top {{ Anchor: (Height: 400); }}",
        group(f"Hit{i}Row", [f"Anchor: (Height: {HIT_H});", "LayoutMode: Left;"], [
            f"Group #Hit{i}Left {{ Anchor: (Width: 800); }}",
            f"Button #Hit{i} {{ Anchor: (Width: {HIT_W}, Height: {HIT_H}); }}",
            f"Group {{ FlexWeight: 1; }}",
        ]),
        f"Group {{ FlexWeight: 1; }}",
    ])


BOOK_W, BOOK_H = 440, 290
REWARD_W, REWARD_H, REWARD_HEROES, REWARD_LOOT = 660, 560, 3, 8


def reward_layer():
    """Окно наград после победы (#RewardLayer поверх всего): опыт каждому герою с полоской, повышения
    уровня, добыча иконками; #RewardWin опускается сверху (плагин двигает Anchor)."""
    heroes = []
    for i in range(REWARD_HEROES):
        heroes += [group(f"Rw{i}", ["Anchor: (Height: 56);", "LayoutMode: Left;", skin("jr_panel_party", 16),
                                    "Padding: (Left: 8, Right: 10, Top: 5, Bottom: 5);"], [
            slot(f"Rw{i}Icon", 44),
            spacer(w=10),
            group("", ["Anchor: (Width: 230);", "LayoutMode: Top;"], [
                label(f"Rw{i}Name", "", 16, PARTY_NAME, True, height=22),
                label(f"Rw{i}Lvl", "", 12, GOLD, True, height=18),
            ]),
            group("", ["FlexWeight: 1;", "LayoutMode: Top;"], [
                label(f"Rw{i}Exp", "", 13, "#9fe6a8", True, height=20),
                spacer(h=4),
                tbar(f"Rw{i}Bar", 300, "jr_bar_ap", h=8),
                label(f"Rw{i}Next", "", 11, SUB, height=14),
            ]),
        ]), spacer(h=4)]
    loot_cells = []
    for row in range(REWARD_LOOT // 2):
        cells = []
        for col in range(2):
            i = row * 2 + col
            cells += [group(f"Loot{i}", ["Anchor: (Width: 290, Height: 34);", "LayoutMode: Left;"], [
                group("", ["Anchor: (Width: 34, Height: 34);", skin("jr_slot", 8)], [
                    f"Group #Loot{i}Icon {{ Anchor: (Left: 2, Top: 2, Width: 30, Height: 30); }}"]),
                spacer(w=8),
                label(f"Loot{i}Name", "", 13, TEXT, height=34, flex=True),
            ]), spacer(w=12)]
        loot_cells += [group("", ["Anchor: (Height: 36);", "LayoutMode: Left;"], cells), spacer(h=2)]
    win = group("RewardWin", [f"Anchor: (Left: 0, Top: 0, Width: {REWARD_W}, Height: {REWARD_H});", "LayoutMode: Top;",
                              skin("jr_window", 86), "Padding: (Left: 26, Right: 26, Top: 14, Bottom: 22);"], [
        group("", ["Anchor: (Height: 52);", "LayoutMode: Left;"], [
            spacer(flex=True),
            group("", ["Anchor: (Width: 520, Height: 52);", "LayoutMode: Top;", tex("jr_banner"), "Padding: (Top: 6);"], [
                label("RewardTitle", "ПОБЕДА!", 22, GOLD, True, height=26, center=True),
                label("RewardSub", "", 12, SUB, height=16, center=True),
            ]),
            spacer(flex=True),
        ]),
        spacer(h=4),
        label("RewardExp", "", 15, "#9fe6a8", True, height=22, center=True),
        spacer(h=4),
    ] + heroes + [
        divider(),
        spacer(h=4),
        label("RewardLootTitle", "ДОБЫЧА", 13, INK_TITLE, True, height=20),
    ] + loot_cells + [
        spacer(flex=True),
        group("", ["Anchor: (Height: 40);", "LayoutMode: Left;"], [
            spacer(flex=True), button("RewardOk", "Забрать награду", 240, 38), spacer(w=10),
            button("RewardGrid", "Сетка навыков", 200, 38), spacer(flex=True)]),
    ])
    return group("RewardLayer", ["Anchor: (Full: 0);", "LayoutMode: Top;"], [
        spacer(flex=True),
        group("", [f"Anchor: (Height: {REWARD_H});", "LayoutMode: Left;"], [
            spacer(flex=True), group("RewardSlot", [f"Anchor: (Width: {REWARD_W});"], [win]), spacer(flex=True)]),
        spacer(flex=True),
        spacer(h=120),
    ])
# текст журнала: ширина панели − поля 14+12, высота панели 280 − поля 10+8 − шапка 29
LOG_TEXT_W, LOG_TEXT_H = BOOK_W - 26, BOOK_H - 10 - 18 - 29


def battle():
    enemy_row = group("EnemyRow", [f"Anchor: (Height: {ENEMY_H});", "LayoutMode: Left;"],
                      [spacer(flex=True)] + sum(([enemy_card(i), spacer(w=6)] for i in range(ENEMIES)), [])
                      + [spacer(flex=True)])
    # Баннер хода, очередь и журнал в окне — запасной вид; обычно они в HUD (BattleHud.ui) и анимированы.
    # #BannerSlide выезжает сверху при смене хода (плагин двигает его Anchor.Top).
    banner = group("BannerRow", ["Anchor: (Height: 62);"], [group("BannerSlide", [
        "Anchor: (Left: 0, Right: 0, Top: 0, Height: 62);", "LayoutMode: Left;"], [
        spacer(flex=True),
        group("BannerBox", ["Anchor: (Width: 600, Height: 60);", "LayoutMode: Top;", tex("jr_banner"),
                            "Padding: (Top: 9);"], [
            label("Banner", "", 22, GOLD, True, height=26, center=True, font=TITLE_FONT),
            label("BannerSub", "", 13, TEXT, height=18, center=True),
        ]),
        spacer(flex=True),
    ])])
    q_h = qw_top(QUEUE - 1) + QW_H + 12
    queue = group("QueueSlot", [f"Anchor: (Height: {q_h});"], [group("QueuePanel", [
        f"Anchor: (Left: 0, Top: 0, Width: 236, Height: {q_h});", skin("jr_panel")], [
        f'Label #QueueTitle {{ Text: "ОЧЕРЕДЬ ХОДОВ"; Anchor: (Left: 13, Top: 11, Width: 200, Height: 20); '
        f"Style: (FontSize: 13, TextColor: {INK_TITLE}, RenderBold: true); }}",
    ] + [queue_slot(i) for i in range(QUEUE)])])
    # Журнал — тёмная панель в стиле остального окна (jr_panel): заголовок, золотая черта, строки журнала
    # цветом события; старые строки тусклее (BattleMenuPage.fillLog). Книга-пергамент выбивалась из стиля.
    bh = BOOK_H - 10
    log = group("LogPanel", [f"Anchor: (Width: {BOOK_W}, Height: {bh});", "LayoutMode: Top;", skin("jr_panel"),
                             "Padding: (Left: 14, Right: 12, Top: 10, Bottom: 8);"], [
        group("", ["Anchor: (Height: 20);", "LayoutMode: Left;"], [
            label("LogTitle", "ЖУРНАЛ БОЯ", 13, INK_TITLE, True, height=20, width=130),
            label("LogSub", "", 12, SUB, height=20, flex=True),
            label("LogTurn", "", 12, GOLD, height=20, width=80),
        ]),
        spacer(h=3),
        "Group { Anchor: (Height: 1); Background: #e8c46a(0.45); }",
        "Group { Anchor: (Height: 1); Background: #000000(0.35); }",
        spacer(h=4),
        # #LogText по абсолютным координатам в #LogBox: при новой строке плагин сдвигает его снизу вверх
        group("LogBox", ["FlexWeight: 1;"], [
            f'Label #LogText {{ Text: ""; Anchor: (Left: 0, Top: 0, Width: {LOG_TEXT_W}, Height: {LOG_TEXT_H}); '
            f"Style: (FontSize: 13, TextColor: {TEXT}, Wrap: true); }}"]),
    ])
    inspect = group("InsPanel", [f"Anchor: (Width: {INS_W});", "LayoutMode: Top;", skin("jr_panel"),
                                 "Padding: (Full: 13);"], [
        panel_title("ОСМОТР И ЭФФЕКТЫ", "InsHeader"),
        label("InsTitle", "", 16, ENEMY_NAME, True, height=22, font=TITLE_FONT),
        label("InsSub", "", 12, SUB, height=17),
        spacer(h=4),
    ] + [label(f"Ins{i}", "", 13, TEXT, height=17) for i in range(INS_LINES)])
    middle = group("Middle", ["FlexWeight: 1;", "LayoutMode: Left;"], [
        group("QueueCol", ["Anchor: (Width: 236);", "LayoutMode: Top;"], [queue, spacer(flex=True)]),
        spacer(flex=True),
        group("", [f"Anchor: (Width: {INS_W});", "LayoutMode: Top;"], [spacer(h=6), inspect, spacer(flex=True)]),
    ])
    party = group("PartyPanel", [f"Anchor: (Width: {PARTY_W + 2});", "LayoutMode: Top;"],
                  sum(([party_row(i), spacer(h=4)] for i in range(PARTY)), []))
    menu = group("MenuPanel", [f"Anchor: (Left: 0, Top: 0, Width: 350, Height: {BOOK_H - 10});", "LayoutMode: Top;", skin("jr_panel"),
                               "Padding: (Left: 13, Right: 13, Top: 11, Bottom: 10);"], [
        group("", ["Anchor: (Height: 28);", "LayoutMode: Left;"], [
            label("MenuTitle", "", 15, GOLD, True, height=26, flex=True, font=TITLE_FONT),
            button("Back", "Назад", 84, 26, cancel=True, skin=True),
        ]),
        spacer(h=2),
    ] + [option_row(i) for i in range(OPTIONS)] + [label("Error", "", 12, "#ff8070", height=16)])
    # #MenuSlot держит место, #MenuPanel внутри уезжает вниз, когда команды не нужны, и выезжает в ход героя.
    menu = group("MenuSlot", ["Anchor: (Width: 350);"], [menu])
    info = group("InfoPanel", ["Anchor: (Width: 470);", "LayoutMode: Top;", skin("jr_panel"),
                               "Padding: (Left: 14, Right: 14, Top: 12, Bottom: 10);"], [
        group("", ["Anchor: (Height: 32);", "LayoutMode: Left;"], [
            slot("InfoIcon", 30),
            spacer(w=8),
            label("InfoTitle", "", 17, GOLD, True, height=30, flex=True, font=TITLE_FONT),
        ]),
        spacer(h=4),
        label("InfoText", "", 14, TEXT, flex=True, wrap=True),
    ])
    # Левый нижний угол: книга-журнал из HUD (#BookSpace держит под неё место) или запасной #LogPanel.
    bottom = group("BottomBar", [f"Anchor: (Height: {BOOK_H - 10});", "LayoutMode: Left;"], [
        group("BookSpace", [f"Anchor: (Width: {BOOK_W});"], []), log,
        spacer(flex=True), party, spacer(w=8), menu, spacer(w=8), info, spacer(flex=True),
    ])
    main = group("", ["Anchor: (Full: 0);", "LayoutMode: Top;", "Padding: (Full: 10);"],
                 [enemy_row, spacer(h=6), banner, middle, bottom])
    # Кнопки над врагами — первыми, под всем остальным: панели окна перекрывают их и забирают клик.
    root = group("", [], [hit_layer(i) for i in range(ENEMIES)] + [main] + [speech_layer(i) for i in range(SPEECH)]
                 + [reward_layer()])
    text = ('$C = "../Common.ui";\n\n'
            "// Боевое меню JRPG. Файл собран скриптом tools/gen_battle_ui.py — правь скрипт, а не этот файл.\n"
            "// Сверху враги, справа осмотр бойца, внизу партия, команды и описание. Баннер хода, очередь и\n"
            "// журнал-книга обычно в HUD (BattleHud.ui); здесь их запасной вид для settings.json → ui.hud = false.\n"
            "// Рамки — текстуры jr_*.png (tools/gen_frames.py), иконки вставляются в слоты #...Icon из Icons/*.ui.\n\n"
            + jr_button_style() + root + "\n")
    write("BattleMenu.ui", text)


# ---------- HUD боя: баннер хода, очередь, книга журнала ----------
# Всё стоит по абсолютным координатам: плагин двигает элементы (Anchor) шагами — так сделаны анимации.
# HUD не ждёт подтверждений клиента, поэтому частые обновления не мешают кнопкам окна боя.

HUD_Q_W = 236
Q_ITEM_W = 214
Q_CUR_H, Q_H, Q_GAP = 50, 36, 4
Q_TOP = 38


def q_top(i):
    return Q_TOP if i == 0 else Q_TOP + Q_CUR_H + 6 + (i - 1) * (Q_H + Q_GAP)


def hud_queue_item(i):
    h = Q_CUR_H if i == 0 else Q_H
    icon = 38 if i == 0 else 26
    layers = [f"Group #HQ{i}{k} {{ Anchor: (Left: 0, Top: 0, Width: {Q_ITEM_W}, Height: {h}); {tex(t)} }}"
              for k, t in (("Cur", "jr_q_cur"), ("Pty", "jr_q_party"), ("Ene", "jr_q_enemy"))]
    content = group("", [f"Anchor: (Left: 12, Top: 0, Width: {Q_ITEM_W - 18}, Height: {h});", "LayoutMode: Left;"], [
        group("", ["LayoutMode: Top;", f"Anchor: (Width: {icon});"], [spacer(flex=True), slot(f"HQ{i}Icon", icon), spacer(flex=True)]),
        spacer(w=8),
        group("", ["FlexWeight: 1;", "LayoutMode: Top;"], [
            spacer(flex=True),
            label(f"HQ{i}Name", "", 15 if i == 0 else 13, TEXT, True, height=19 if i == 0 else 16),
            label(f"HQ{i}Sub", "", 12, GOLD if i == 0 else SUB, height=15 if i == 0 else 13),
            spacer(flex=True),
        ]),
    ])
    return group(f"HQ{i}", [f"Anchor: (Left: 11, Top: {q_top(i)}, Width: {Q_ITEM_W}, Height: {h});"], layers + [content])


def hud():
    banner = group("HBannerRow", ["Anchor: (Left: 0, Right: 0, Top: 96, Height: 62);", "LayoutMode: Left;"], [
        spacer(flex=True),
        group("HBannerBox", ["Anchor: (Width: 600, Height: 60);", "LayoutMode: Top;", tex("jr_banner"),
                             "Padding: (Top: 8);"], [
            label("HBanner", "", 22, GOLD, True, height=27, center=True, font=TITLE_FONT),
            label("HBannerSub", "", 13, TEXT, height=18, center=True),
        ]),
        spacer(flex=True),
    ])
    q_h = q_top(QUEUE - 1) + Q_H + 12
    queue = group("HQueue", [f"Anchor: (Left: 12, Top: 170, Width: {HUD_Q_W}, Height: {q_h});", skin("jr_panel")], [
        f'Label #HQueueTitle {{ Text: "ОЧЕРЕДЬ ХОДОВ"; Anchor: (Left: 16, Top: 12, Width: 200, Height: 20); '
        f"Style: (FontSize: 13, TextColor: {GOLD}, RenderBold: true{title_font()}); }}",
    ] + [hud_queue_item(i) for i in range(QUEUE)])
    # Книга: левая страница — прошлая, правая — текущая. #HFlip — переворачиваемый лист (анимация).
    page_l, page_r, page_top, page_w, page_h = 28, 238, 22, 176, 232
    book = group("HBook", [f"Anchor: (Left: 12, Bottom: 12, Width: {BOOK_W}, Height: {BOOK_H});", tex("jr_book")], [
        f'Label #HBookLHead {{ Text: ""; Anchor: (Left: {page_l}, Top: {page_top - 4}, Width: {page_w}, Height: 16); '
        f"Style: (FontSize: 12, TextColor: #7a6040, RenderBold: true, HorizontalAlignment: Center{title_font()}); }}",
        f'Label #HBookRHead {{ Text: ""; Anchor: (Left: {page_r}, Top: {page_top - 4}, Width: {page_w}, Height: 16); '
        f"Style: (FontSize: 12, TextColor: #7a6040, RenderBold: true, HorizontalAlignment: Center{title_font()}); }}",
        f'Label #HBookL {{ Text: ""; Anchor: (Left: {page_l}, Top: {page_top + 16}, Width: {page_w}, Height: {page_h - 22}); '
        f"Style: (FontSize: 13, TextColor: #3a2a1a, Wrap: true); }}",
        f'Label #HBookR {{ Text: ""; Anchor: (Left: {page_r}, Top: {page_top + 16}, Width: {page_w}, Height: {page_h - 22}); '
        f"Style: (FontSize: 13, TextColor: #3a2a1a, Wrap: true); }}",
        f'Label #HBookLNum {{ Text: ""; Anchor: (Left: {page_l}, Top: {BOOK_H - 30}, Width: {page_w}, Height: 14); '
        f"Style: (FontSize: 11, TextColor: #8a7050, HorizontalAlignment: Center); }}",
        f'Label #HBookRNum {{ Text: ""; Anchor: (Left: {page_r}, Top: {BOOK_H - 30}, Width: {page_w}, Height: 14); '
        f"Style: (FontSize: 11, TextColor: #8a7050, HorizontalAlignment: Center); }}",
        f"Group #HFlip {{ Anchor: (Left: 222, Top: 12, Width: 206, Height: 266); {tex('jr_page')} }}",
    ])
    root = group("", ["Anchor: (Full: 0);"], [banner, queue, book])
    write("BattleHud.ui", '$C = "../Common.ui";\n\n'
          "// HUD боя: баннер хода #HBannerRow, очередь #HQueue (#HQ0..7, фоны #HQ<n>Cur/Pty/Ene), книга журнала #HBook\n"
          "// (страницы #HBookL/#HBookR, лист #HFlip). Положение элементов плагин меняет шагами — это анимации.\n"
          "// Собрано tools/gen_battle_ui.py — правь скрипт.\n\n" + root + "\n")


# ---------- общий стиль меню (0.10.3) ----------
# Меню вне боя в том же стиле, что окно боя: золотая рамка окна jr_panel_gold, заголовок на ленте jr_banner,
# колонки на jr_panel, ячейки jr_slot, выбор — золотая рамка jr_panel_gold.

def menu_title(text, sub_id=None, sub="", width=560):
    kids = [label("", text, 21, GOLD, True, height=26, center=True)]
    kids.append(label(sub_id or "", sub, 12, SUB, height=16, center=True))
    return group("", ["Anchor: (Height: 52);", "LayoutMode: Left;"], [
        spacer(flex=True),
        group("", [f"Anchor: (Width: {width}, Height: 52);", "LayoutMode: Top;", tex("jr_banner"), "Padding: (Top: 6);"], kids),
        spacer(flex=True),
    ])


def menu_window(width, children, pad=24):
    # jr_window.png — окно с резными золотыми углами (Kling, panel_bg), углы по 86 px не растягиваются
    return group("Window", [f"Anchor: (Width: {width});", "LayoutMode: Top;", skin("jr_window", 86),
                            f"Padding: (Left: {pad}, Right: {pad}, Top: 16, Bottom: {pad});"], children)


def centered(height, window):
    # Окно стоит в #WindowSlot по абсолютным координатам: при открытии плагин опускает его сверху
    # (WindowIntro, Anchor.Top). Ширину берём из Anchor окна (menu_window).
    m = re.search(r"Group #Window \{\n  Anchor: \(Width: (\d+)\);", window)
    w = int(m.group(1))
    h = height + 16  # +16: поля окна jr_window шире, чем были у jr_panel_gold
    window = window.replace(m.group(0), f"Group #Window {{\n  Anchor: (Left: 0, Top: 0, Width: {w}, Height: {h});", 1)
    return group("", ["LayoutMode: Top;"], [
        spacer(flex=True),
        group("", [f"Anchor: (Height: {h});", "LayoutMode: Left;"], [
            spacer(flex=True), group("WindowSlot", [f"Anchor: (Width: {w});"], [window]), spacer(flex=True)]),
        spacer(flex=True),
    ])


def column(id_, props, children, pad=10):
    return group(id_, props + ["LayoutMode: Top;", skin("jr_panel"), f"Padding: (Full: {pad});"], children)


def sel_frame(id_, w, h, border=14):
    """Подсветка выбора: золотая рамка поверх ячейки (видимость ставит плагин)."""
    return f"Group #{id_} {{ Anchor: (Left: 0, Top: 0, Width: {w}, Height: {h}); {skin('jr_panel_gold', border)} }}"


def divider():
    return ("Group { Anchor: (Height: 1); Background: #e8c46a(0.40); }\n"
            "Group { Anchor: (Height: 1); Background: #000000(0.35); }")


# ---------- круговая сетка навыков ----------

GRID = 800
SIZE = 37      # узлов сетки (Progression.Grid.SIZE)
CENTER = GRID // 2
RADII = {0: 0, 1: 110, 2: 200, 3: 280, 4: 362}
NODE = 72      # контейнер узла
# Цвета секторов ролей (как в Progression.Grid.sector): защитник, целитель, боец; мосты — серые.
SECTOR_LOCK = {"T": "#16243c(0.96)", "H": "#14301c(0.96)", "D": "#341616(0.96)", "X": "#262630(0.96)"}
SECTOR_DOT = {"T": "#3a5f9a(0.9)", "H": "#3a8a4e(0.9)", "D": "#9a3a3a(0.9)"}
TABS = 3


def ring(i):
    return 0 if i == 0 else 1 if i <= 6 else 2 if i <= 18 else 3 if i <= 24 else 4


def angle(i):
    r = ring(i)
    if r == 1: return -90 + 60 * (i - 1)
    if r == 2: return -90 + 30 * (i - 7)
    if r == 3: return -60 + 60 * (i - 19)
    if r == 4: return -90 + 30 * (i - 25)
    return 0


def neighbors(i):
    r = ring(i)
    if r == 0: return list(range(1, 7))
    if r == 1: return [0, 7 + 2 * (i - 1)]
    if r == 2:
        j = i - 7
        out = [7 + (j + 11) % 12, 7 + (j + 1) % 12]
        out += [1 + j // 2, 25 + j] if j % 2 == 0 else [19 + j // 2]
        return out
    if r == 3: return [7 + 2 * (i - 19) + 1, 25 + 2 * (i - 19) + 1]
    k = i - 25
    return [25 + (k + 11) % 12, 25 + (k + 1) % 12, 19 + k // 2 if k % 2 == 1 else 7 + k]


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
    for a in range(SIZE):
        for b in neighbors(a):
            if a < b: out.append((a, b))
    return out


def edge_points(a, b, step=11, gap=36):
    """Точки ребра от узла a к узлу b (по порядку от a)."""
    (x1, y1), (x2, y2) = pos(a), pos(b)
    length = math.hypot(x2 - x1, y2 - y1)
    n = max(1, int((length - 2 * gap) / step))
    out = []
    for k in range(n + 1):
        t = (gap + k * (length - 2 * gap) / max(1, n)) / length
        out.append((x1 + (x2 - x1) * t, y1 + (y2 - y1) * t))
    return out


def edge_dots(a, b, color, size=4, every=1, phase=0):
    """Точки ребра; every/phase — только каждая every-я, начиная с phase (бегущий сигнал)."""
    return [f"Group {{ Anchor: (Left: {int(x) - size // 2}, Top: {int(y) - size // 2}, Width: {size}, Height: {size}); "
            f"Background: {color}; }}"
            for k, (x, y) in enumerate(edge_points(a, b)) if k % every == phase]


PULSE = 3  # фаз бегущего сигнала по ребру


def full(id_, children):
    return group(id_, [f"Anchor: (Left: 0, Top: 0, Width: {GRID}, Height: {GRID});"], children)


def node(i):
    x, y = pos(i)
    left, top = int(x) - NODE // 2, int(y) - NODE // 2
    lock = SECTOR_LOCK[sector(i)] if i else SECTOR_LOCK["X"]
    return group(f"G{i}", [f"Anchor: (Left: {left}, Top: {top}, Width: {NODE}, Height: {NODE});"], [
        # выбранный узел — мягкое золотое свечение вокруг
        f"Group #G{i}Sel {{ Anchor: (Left: 0, Top: 0, Width: 72, Height: 72); Background: #e8c46a(0.25);\n"
        f"  Group {{ Anchor: (Left: 2, Top: 2, Width: 68, Height: 68); Background: #fff0b0(0.35); }}\n}}",
        # закрыт — тёмная ячейка с оттенком сектора, доступен — синяя рамка, открыт — золотая
        f"Group #G{i}Lock {{ Anchor: (Left: 4, Top: 4, Width: 64, Height: 64); {skin('jr_slot', 8)}\n"
        f"  Group {{ Anchor: (Left: 4, Top: 4, Width: 56, Height: 56); Background: {lock}; }}\n}}",
        f"Group #G{i}Avail {{ Anchor: (Left: 4, Top: 4, Width: 64, Height: 64); {skin('jr_panel_party', 16)} }}",
        f"Group #G{i}Open {{ Anchor: (Left: 4, Top: 4, Width: 64, Height: 64); {skin('jr_panel_gold', 16)} }}",
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
                 + [spacer(flex=True), group("PointsBox", ["Anchor: (Width: 270, Height: 34);", "LayoutMode: Left;",
                                                         skin("jr_panel_gold", 12), "Padding: (Left: 8, Right: 8, Top: 4);"], [
                     label("PointsNum", "0", 20, GOLD, True, width=46, height=26, center=True),
                     spacer(w=6),
                     label("Points", "", 13, TEXT, height=26, flex=True),
                 ])])
    hero = column("HeroCol", ["Anchor: (Width: 290);"], [
        group("", ["Anchor: (Height: 92);", "LayoutMode: Left;"], [
            slot("HeroIcon", 88),
            spacer(w=10),
            group("", ["FlexWeight: 1;", "LayoutMode: Top;"], [
                label("HeroName", "", 20, PARTY_NAME, True, height=28),
                label("HeroTitle", "", 13, GOLD, height=20),
                label("HeroLevel", "", 12, SUB, height=36, wrap=True),
            ]),
        ]),
        tbar("Exp", 266, "jr_bar_ap", h=7),
        spacer(h=6),
        divider(),
        spacer(h=6),
        label("HeroStats", "", 12, TEXT, height=96, wrap=True),
        label("HeroPassives", "", 12, "#c9a0ff", height=60, wrap=True),
        label("Known", "", 12, SUB, flex=True, wrap=True),
        button("AddExp", "+100 опыта (тест)", 266, 30),
        spacer(h=4),
        button("Respec", "Сбросить сетку", 266, 30),
    ])
    edge_list = edges()
    # Рёбра: закрытые — серые точки; к доступному узлу — голубые; между открытыми — золотые крупнее;
    # у выбранного узла по рёбрам бежит яркий сигнал (#EdgeP<k>_<фаза>, фазы переключает плагин).
    off = full("EdgesOff", sum((edge_dots(a, b, "#5a6c90(0.85)") for a, b in edge_list), []))
    on = []
    for k, (a, b) in enumerate(edge_list):
        on.append(full(f"EdgeA{k}", edge_dots(a, b, "#7fb6ff(0.95)", size=5)))
        on.append(full(f"Edge{k}", edge_dots(a, b, GOLD, size=6)))
    pulse = [full(f"EdgeP{k}_{ph}", edge_dots(a, b, "#fff6d0", size=8, every=PULSE, phase=ph))
             for k, (a, b) in enumerate(edge_list) for ph in range(PULSE)]
    on += pulse
    rings = full("Rings", sum((ring_dots(r) for r in (1, 2, 3, 4)), []))
    labels = full("Sectors", [
        sector_label("ЗАЩИТНИК", "#8fbaff", GRID - 150, 10),
        sector_label("ЦЕЛИТЕЛЬ", "#8fe0a0", GRID - 150, GRID - 32),
        sector_label("БОЕЦ", "#ff9a8a", 16, 10),
        sector_label("мосты — гибриды", "#9fb0c6", 16, GRID - 32),
    ])
    grid = group("Grid", [f"Anchor: (Width: {GRID}, Height: {GRID});", skin("jr_panel")],
                 [rings, labels, off] + on + [node(i) for i in range(SIZE)])
    detail = column("DetailCol", ["FlexWeight: 1;"], [
        group("", ["Anchor: (Height: 44);", "LayoutMode: Left;"], [
            slot("SelIcon", 40),
            spacer(w=8),
            group("", ["FlexWeight: 1;", "LayoutMode: Top;"], [
                label("SelTitle", "", 16, GOLD, True, height=22),
                label("SelKind", "", 12, SUB, height=18),
            ]),
        ]),
        spacer(h=4),
        divider(),
        spacer(h=6),
        label("SelText", "", 12, TEXT, flex=True, wrap=True),
        group("", ["Anchor: (Height: 30);", "LayoutMode: Left;"], [
            button("PrevNode", "<", 60, 28), spacer(w=6),
            label("", "соседние узлы", 12, SUB, height=28, flex=True),
            button("NextNode", ">", 60, 28),
        ]),
        spacer(h=6),
        button("Learn", "Открыть узел", 250, 34),
        spacer(h=4),
        label("Message", "", 12, "#ffd27a", height=36, wrap=True),
        spacer(h=4),
        button("Close", "Закрыть", 250, 32, cancel=True),
    ])
    window = menu_window(1400, [
        menu_title("СЕТКА НАВЫКОВ", sub="открывай узлы рядом с уже открытыми"),
        tabs,
        spacer(h=8),
        group("", [f"Anchor: (Height: {GRID});", "LayoutMode: Left;"], [hero, spacer(w=10), grid, spacer(w=10), detail]),
    ])
    text = ('$C = "../Common.ui";\n\n'
            "// Круговая сетка навыков. Собрано tools/gen_battle_ui.py — правь скрипт.\n"
            "// Узел #G<n>: слои #G<n>Sel (выбран), #G<n>Lock/Avail/Open (состояние), кнопка #G<n>Btn, иконка #G<n>Icon.\n"
            "// Рёбра: серые точки #EdgesOff всегда, золотые #Edge<k> — когда оба конца открыты.\n\n"
            + centered(GRID + 130, window) + "\n")
    write("Progression.ui", text)
    # порядок рёбер нужен плагину: печатаем для проверки с Progression.Grid.edges()
    print("edges:", edge_list)


# ---------- окно перед боем ----------

def prebattle():
    def card(i):
        return group(f"En{i}", ["Anchor: (Width: 112);", "LayoutMode: Top;"], [
            group("", ["Anchor: (Height: 52);", "LayoutMode: Left;"], [
                spacer(flex=True),
                group("", ["Anchor: (Width: 52, Height: 52);", skin("jr_slot", 8)], [
                    f"Group #En{i}Icon {{ Anchor: (Left: 6, Top: 6, Width: 40, Height: 40); }}"]),
                spacer(flex=True),
            ]),
            spacer(h=2),
            label(f"En{i}Name", "", 12, ENEMY_NAME, True, height=16, center=True),
            label(f"En{i}Hp", "", 11, SUB, height=14, center=True),
        ])
    enemies = group("Enemies", ["Anchor: (Height: 86);", "LayoutMode: Left;"], [spacer(flex=True)] + sum((
        [card(i), spacer(w=4)] for i in range(ENEMIES)), []) + [spacer(flex=True)])
    buttons = group("", ["Anchor: (Height: 50);", "LayoutMode: Left;"], [
        spacer(flex=True),
        aura("FightBox", 244, 48, ["LayoutMode: Left;"], [button("Fight", "Сражаться", 236, 40)], inset=4),
        spacer(w=18),
        aura("FleeBox", 244, 48, ["LayoutMode: Left;"], [button("Flee", "Сбежать", 236, 40, cancel=True)], inset=4),
        spacer(flex=True),
    ])
    title = group("", ["Anchor: (Height: 56);", "LayoutMode: Left;"], [
        spacer(flex=True),
        group("", ["Anchor: (Width: 600, Height: 56);", "LayoutMode: Top;", tex("jr_banner"), "Padding: (Top: 6);"], [
            label("Title", "", 21, GOLD, True, height=26, center=True),
            label("Sub", "", 12, TEXT, height=18, center=True),
        ]),
        spacer(flex=True),
    ])
    window = menu_window(680, [
        title,
        spacer(h=6),
        group("", ["LayoutMode: Top;", skin("jr_panel"), "Padding: (Full: 8);", "Anchor: (Height: 104);"], [enemies]),
        spacer(h=8),
        label("Chance", "", 13, TEXT, height=38, wrap=True, center=True),
        spacer(h=6),
        buttons,
    ])
    write("PreBattle.ui", '$C = "../Common.ui";\n\n'
          "// Окно перед боем: «Сражаться / Сбежать». Собрано tools/gen_battle_ui.py — правь скрипт.\n\n"
          + centered(330, window) + "\n")


# ---------- рюкзак ----------

BAG_COLS, BAG_ROWS, CELL = 6, 4, 86
BAG_TABS = ["Все", "Зелья", "Боевые", "Материалы", "Снаряжение"]
BAG_SORTS = [("SortKind", "Тип"), ("SortName", "Имя"), ("SortCount", "Кол-во")]


def bag_cell(i):
    left, top = (i % BAG_COLS) * CELL, (i // BAG_COLS) * CELL
    return group(f"S{i}", [f"Anchor: (Left: {left}, Top: {top}, Width: {CELL - 6}, Height: {CELL - 6});"], [
        f"Group #S{i}Back {{ Anchor: (Left: 2, Top: 2, Width: 76, Height: 76); {skin('jr_cell', 28)} }}",
        sel_frame(f"S{i}Sel", 80, 80, 16),
        button(f"S{i}Btn", "", 64, 64, left=8, top=8),
        f"Group #S{i}Icon {{ Anchor: (Left: 16, Top: 14, Width: 48, Height: 48); }}",
        f'Label #S{i}Count {{ Text: ""; Anchor: (Left: 44, Top: 58, Width: 34, Height: 18); '
        f"Style: (FontSize: 13, TextColor: #ffffff, RenderBold: true); }}",
    ])


def backpack():
    tabs = group("Tabs", ["Anchor: (Height: 34);", "LayoutMode: Left;"],
                 sum(([button(f"Tab{i}", t, 112, 32), spacer(w=5)] for i, t in enumerate(BAG_TABS)), [])
                 + [spacer(flex=True), label("", "Сортировка:", 13, SUB, width=100, height=32)]
                 + sum(([button(bid, t, 96, 32), spacer(w=4)] for bid, t in BAG_SORTS), []))
    grid_w, grid_h = BAG_COLS * CELL, BAG_ROWS * CELL
    grid = group("Grid", [f"Anchor: (Width: {grid_w}, Height: {grid_h});"], [bag_cell(i) for i in range(BAG_COLS * BAG_ROWS)])
    left = column("GridCol", [f"Anchor: (Width: {grid_w + 20});"], [
        grid,
        spacer(h=6),
        group("", ["Anchor: (Height: 30);", "LayoutMode: Left;"], [
            button("PrevPage", "<", 60, 28), spacer(w=6),
            label("Page", "", 13, SUB, height=28, flex=True, center=True),
            button("NextPage", ">", 60, 28),
        ]),
        label("Empty", "", 13, SUB, height=24),
    ])
    detail = column("DetailCol", ["FlexWeight: 1;"], [
        group("", ["Anchor: (Height: 68);", "LayoutMode: Left;"], [
            group("", ["Anchor: (Width: 68, Height: 68);", skin("jr_cell", 26)], [
                "Group #SelIcon { Anchor: (Left: 10, Top: 10, Width: 48, Height: 48); }"]),
            spacer(w=10),
            group("", ["FlexWeight: 1;", "LayoutMode: Top;"], [
                label("SelName", "", 18, GOLD, True, height=26),
                label("SelKind", "", 12, SUB, height=20),
            ]),
        ]),
        spacer(h=6),
        divider(),
        spacer(h=6),
        label("SelText", "", 13, TEXT, flex=True, wrap=True),
        label("Message", "", 12, "#ffd27a", height=36, wrap=True),
        button("Drop", "Выбросить 1", 300, 32),
        spacer(h=6),
        button("Close", "Закрыть", 300, 32, cancel=True),
    ], pad=12)
    window = menu_window(1000, [
        menu_title("РЮКЗАК", "Total"),
        tabs,
        spacer(h=8),
        group("", [f"Anchor: (Height: {grid_h + 84});", "LayoutMode: Left;"], [left, spacer(w=10), detail]),
    ])
    write("Backpack.ui", '$C = "../Common.ui";\n\n'
          "// Рюкзак: вкладки-фильтры #Tab0..4, сортировка #SortKind/#SortName/#SortCount, ячейки #S<n>\n"
          "// (#S<n>Sel — выбрана, #S<n>Btn — кнопка, #S<n>Icon — иконка, #S<n>Count — количество).\n"
          "// Собрано tools/gen_battle_ui.py — правь скрипт.\n\n" + centered(grid_h + 200, window) + "\n")


# ---------- экипировка ----------

EQ_SLOTS = 8
EQ_ITEMS = 8
EQ_CMP = 11
CMP_W = 344  # ширина колонки сравнения внутри поля
CMP_COLS = (("Name", 128, False), ("Was", 54, True), ("Arrow", 26, True), ("Now", 54, True), ("Delta", 82, True))


def cmp_head():
    """Шапка таблицы сравнения: что, сейчас, станет, итог."""
    titles = {"Name": "", "Was": "сейчас", "Arrow": "", "Now": "станет", "Delta": "итог"}
    return group("CmpHead", ["Anchor: (Height: 16);", "LayoutMode: Left;"],
                 [label("", titles[k], 11, SUB, width=w, height=16, center=c) for k, w, c in CMP_COLS])


def cmp_row(i):
    """Строка сравнения: подложка лучше/хуже (#Cmp<i>Good/#Cmp<i>Bad), таблица #Cmp<i>Row
    (Name, Was → Now, Delta) или одна строка текста #Cmp<i> (навыки, описание надетой вещи)."""
    h = 20
    return group(f"Cmp{i}Box", [f"Anchor: (Height: {h});"], [
        f"Group #Cmp{i}Good {{ Anchor: (Left: 0, Top: 1, Width: {CMP_W}, Height: {h - 2}); Background: #3fae5a(0.20); }}",
        f"Group #Cmp{i}Bad {{ Anchor: (Left: 0, Top: 1, Width: {CMP_W}, Height: {h - 2}); Background: #c8553a(0.22); }}",
        f'Label #Cmp{i} {{ Text: ""; Anchor: (Left: 6, Top: 0, Width: {CMP_W - 6}, Height: {h}); '
        f"Style: (FontSize: 13, TextColor: {TEXT}); }}",
        group(f"Cmp{i}Row", [f"Anchor: (Left: 6, Top: 0, Width: {CMP_W - 6}, Height: {h});", "LayoutMode: Left;"],
              [label(f"Cmp{i}{k}", "", 13, TEXT, bold=k in ("Now", "Delta"), width=w - (6 if k == "Name" else 0),
                     height=h, center=c) for k, w, c in CMP_COLS]),
    ])
EQ_ROW = 40


def sel_row(id_, w, h, text_w, extra=None):
    """Строка: ячейка с иконкой #<id>Icon слева, кнопка #<id>Btn, золотая рамка выбора #<id>Sel;
    extra — подпись справа (#<id>Q)."""
    kids = [f"Group {{ Anchor: (Left: 0, Top: 0, Width: {w}, Height: {h}); {skin('jr_slot', 8)} }}",
            sel_frame(f"{id_}Sel", w, h, 12),
            f"Group #{id_}Icon {{ Anchor: (Left: 6, Top: 5, Width: 30, Height: 30); }}",
            button(f"{id_}Btn", "", text_w - 40, h - 8, left=40, top=4)]
    if extra:
        kids.append(f'Label #{id_}Q {{ Text: ""; Anchor: (Left: {text_w + 8}, Top: 10, Width: {w - text_w - 12}, Height: 20); '
                    f"Style: (FontSize: 12, TextColor: {DIM}, RenderBold: true); }}")
    return group(id_, [f"Anchor: (Width: {w}, Height: {h});"], kids)


def equipment():
    tabs = group("Tabs", ["Anchor: (Height: 34);", "LayoutMode: Left;"],
                 sum(([button(f"Tab{i}", "", 200, 32), spacer(w=6)] for i in range(PARTY)), [])
                 + [spacer(flex=True), label("Hint", "", 12, SUB, width=330, height=32, wrap=True)])
    rows = []
    for i in range(EQ_SLOTS):
        rows += [sel_row(f"Slot{i}", 314, EQ_ROW, 310), spacer(h=2)]
    left = column("SlotCol", ["Anchor: (Width: 334);"], [
        label("HeroName", "", 18, PARTY_NAME, True, height=24),
        label("HeroSub", "", 12, SUB, height=18),
        spacer(h=4),
    ] + rows + [
        spacer(h=2),
        label("HeroStats", "", 12, TEXT, height=52, wrap=True),
    ])
    items = []
    for i in range(EQ_ITEMS):
        items += [sel_row(f"Item{i}", 380, EQ_ROW, 270, extra=True), spacer(h=2)]
    middle = column("ListCol", ["FlexWeight: 1;"], [
        label("ListTitle", "", 16, GOLD, True, height=24),
        label("ListSub", "", 12, SUB, height=18),
        spacer(h=4),
    ] + items + [
        spacer(h=2),
        group("", ["Anchor: (Height: 30);", "LayoutMode: Left;"], [
            button("PrevPage", "<", 50, 28), spacer(w=6),
            label("Page", "", 12, SUB, height=28, flex=True, center=True),
            button("NextPage", ">", 50, 28),
        ]),
        label("Empty", "", 12, SUB, height=40, wrap=True),
    ])
    right = column("CmpCol", ["Anchor: (Width: 364);"], [
        group("", ["Anchor: (Height: 44);", "LayoutMode: Left;"], [
            group("", ["Anchor: (Width: 44, Height: 44);", skin("jr_slot", 8)], [
                "Group #CmpIcon { Anchor: (Left: 2, Top: 2, Width: 40, Height: 40); }"]),
            spacer(w=8),
            group("", ["FlexWeight: 1;", "LayoutMode: Top;"], [
                label("CmpTitle", "", 17, GOLD, True, height=24),
                label("CmpSub", "", 12, SUB, height=18),
            ]),
        ]),
        spacer(h=4),
        divider(),
        spacer(h=4),
    ] + [cmp_head()] + [cmp_row(i) for i in range(EQ_CMP)] + [
        label("CmpSum", "", 12, GOLD, True, height=18),
        label("CmpDesc", "", 12, SUB, height=42, wrap=True),
        label("Message", "", 12, "#ffd27a", height=32, wrap=True),
        group("", ["Anchor: (Height: 34);", "LayoutMode: Left;"], [
            button("Equip", "Надеть", 168, 32), spacer(w=4), button("Unequip", "Снять", 168, 32)]),
        spacer(h=4),
        group("", ["Anchor: (Height: 34);", "LayoutMode: Left;"], [
            button("Back", "Меню героя", 168, 32), spacer(w=4), button("Close", "Закрыть", 168, 32, cancel=True)]),
    ])
    body_h = 24 + 18 + 4 + EQ_SLOTS * (EQ_ROW + 2) + 2 + 52 + 22
    window = menu_window(1150, [
        menu_title("ЭКИПИРОВКА", sub="оружие, броня и аксессуары партии"),
        tabs,
        spacer(h=8),
        group("", [f"Anchor: (Height: {body_h});", "LayoutMode: Left;"], [left, spacer(w=8), middle, spacer(w=8), right]),
    ])
    write("Equipment.ui", '$C = "../Common.ui";\n\n'
          "// Экипировка: вкладки героев #Tab0..2, слоты #Slot0..7 (#Slot<n>Sel, #Slot<n>Btn, #Slot<n>Icon), вещи из\n"
          "// рюкзака #Item0..7 (+ #Item<n>Q — качество, #Item<n>Icon), сравнение #Cmp0..11 и #CmpIcon,\n"
          "// кнопки #Equip/#Unequip/#Back/#Close. Собрано tools/gen_battle_ui.py — правь скрипт.\n\n"
          + centered(body_h + 140, window) + "\n")


# ---------- меню героя (клавиша O) ----------

MAIN_BUTTONS = [("Party", "Прокачка партии", "levelup"), ("Gear", "Экипировка", "sword"), ("Bag", "Рюкзак", "bag"),
                ("Follow", "Компаньоны", "pt_bram"), ("Mode", "Режим игры", "info")]


def mainmenu():
    rows = []
    for bid, text, icon in MAIN_BUTTONS:
        rows += [group("", ["Anchor: (Height: 46);", "LayoutMode: Left;"], [
            group("", ["Anchor: (Width: 46, Height: 46);", skin("jr_slot", 8)], [
                f'Group {{ Anchor: (Left: 5, Top: 5, Width: 36, Height: 36); Background: (TexturePath: "Icons/{icon}.png"); }}']),
            spacer(w=8),
            # окно 450 − поля 2×24 − колонка 2×14 = 374: иконка 46 + 8 + кнопка 316 влезают в рамку
            f'$C.@SecondaryTextButton #{bid} {{ @Text = "{text}"; Anchor: (Width: 316, Height: 42); }}',
        ]), spacer(h=6)]
    body = column("", [f"Anchor: (Height: {len(MAIN_BUTTONS) * 52 + 56 + 28});"], rows + [
        spacer(h=4),
        divider(),
        spacer(h=8),
        group("", ["Anchor: (Height: 42);", "LayoutMode: Left;"], [
            spacer(flex=True),
            '$C.@CancelTextButton #Close { @Text = "Закрыть"; Anchor: (Width: 260, Height: 42); }',
            spacer(flex=True),
        ]),
    ], pad=14)
    window = menu_window(450, [
        menu_title("МЕНЮ ГЕРОЯ", "Sub", "клавиша O · креатив — кнопкой «Режим игры»", width=400),
        spacer(h=4),
        body,
    ])
    write("MainMenu.ui", '$C = "../Common.ui";\n\n'
          "// Меню героя на клавише режима (MainMenuPage). Кнопки: #Party, #Gear, #Bag, #Follow, #Mode, #Close;\n"
          "// тексты #Follow и #Mode ставит код. Собрано tools/gen_battle_ui.py — правь скрипт.\n\n"
          + centered(470, window) + "\n")


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
    hud()
    progression()
    prebattle()
    backpack()
    equipment()
    mainmenu()
