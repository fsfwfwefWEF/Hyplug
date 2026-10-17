"""Собирает разметку боевого меню (BattleMenu.ui): много однотипных слотов проще генерировать.

Запуск из корня проекта: python3 tools/gen_battle_ui.py
"""
import os

OUT = os.path.join(os.path.dirname(__file__), "..", "hytale-plugin", "src", "main", "resources",
                   "Common", "UI", "Custom", "JrpgBattle", "BattleMenu.ui")

QUEUE = 8
ENEMIES = 5
PARTY = 3
OPTIONS = 6

PANEL = "#0b111c(0.88)"
PANEL2 = "#141d2e(0.92)"
GOLD = "#e8c46a"
TEXT = "#e9eef6"
DIM = "#9fb0c6"
HP_BACK = "#3a1c22"
HP = "#5fd27a"
MP_BACK = "#1c2440"
MP = "#5aa2ff"

BAR_W_CARD = 260
BAR_W_ENEMY = 196
BAR_W_ACT = 270


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


def queue_slot(i):
    big = i == 0
    return group(f"Q{i}", [
        f"Anchor: (Height: {58 if big else 48});",
        "LayoutMode: Left;",
        f"Background: {'#2c3c5e(0.95)' if big else '#121a28(0.9)'};",
        "Padding: (Full: 4);",
    ], [
        slot(f"Q{i}Icon", 40),
        spacer(w=8),
        group("", ["FlexWeight: 1;", "LayoutMode: Top;"], [
            label(f"Q{i}Name", size=16 if big else 14, bold=big, height=22),
            label(f"Q{i}Sub", size=12, color=GOLD if big else DIM, height=16),
        ]),
    ])


def enemy_card(i):
    return group(f"E{i}", [
        "Anchor: (Width: 214);",
        "LayoutMode: Top;",
        f"Background: {PANEL};",
        "Padding: (Full: 6);",
    ], [
        group("", ["Anchor: (Height: 42);", "LayoutMode: Left;"], [
            slot(f"E{i}Icon", 40),
            spacer(w=6),
            group("", ["FlexWeight: 1;", "LayoutMode: Top;"], [
                label(f"E{i}Name", size=14, bold=True, color="#ffb3a6", height=20),
                label(f"E{i}Hp", size=12, color=DIM, height=18),
            ]),
        ]),
        bar(f"E{i}Hp", BAR_W_ENEMY, HP_BACK, "#e0605a"),
        spacer(h=4),
        group("", ["Anchor: (Height: 26);", "LayoutMode: Left;"], [
            group(f"E{i}Status", ["FlexWeight: 1;", "LayoutMode: Left;"], []),
            f'$C.@SecondaryTextButton #E{i}Info {{ @Text = "?"; Anchor: (Width: 30, Height: 24); }}',
        ]),
    ])


def party_card(i):
    return group(f"P{i}", [
        "Anchor: (Width: 290);",
        "LayoutMode: Top;",
        f"Background: {PANEL};",
        "Padding: (Full: 6);",
    ], [
        group(f"P{i}Turn", ["Anchor: (Height: 3);", f"Background: {GOLD};"], []),
        group("", ["Anchor: (Height: 42);", "LayoutMode: Left;"], [
            slot(f"P{i}Icon", 40),
            spacer(w=6),
            group("", ["FlexWeight: 1;", "LayoutMode: Top;"], [
                label(f"P{i}Name", size=15, bold=True, color="#a9d8ff", height=20),
                label(f"P{i}Nums", size=12, color=DIM, height=18),
            ]),
            f'$C.@SecondaryTextButton #P{i}Info {{ @Text = "?"; Anchor: (Width: 30, Height: 24); }}',
        ]),
        bar(f"P{i}Hp", BAR_W_CARD, HP_BACK, HP),
        spacer(h=3),
        bar(f"P{i}Mp", BAR_W_CARD, MP_BACK, MP, h=5),
        spacer(h=4),
        group(f"P{i}Status", ["Anchor: (Height: 24);", "LayoutMode: Left;"], []),
    ])


def option_row(i):
    return group(f"Row{i}", ["Anchor: (Height: 42);", "LayoutMode: Left;"], [
        slot(f"Opt{i}Icon", 40),
        spacer(w=6),
        f'$C.@SecondaryTextButton #Opt{i} {{ @Text = ""; Anchor: (Width: 250, Height: 38); }}',
        spacer(w=6),
        label(f"Opt{i}Hint", size=13, color=GOLD, width=90, height=38),
    ])


def main():
    left = group("LeftCol", ["Anchor: (Width: 300);", "LayoutMode: Top;"], [
        group("QueuePanel", ["Anchor: (Height: 476);", "LayoutMode: Top;", f"Background: {PANEL};", "Padding: (Full: 8);"],
              [label("", "ОЧЕРЕДЬ ХОДОВ", 15, GOLD, True, height=24)]
              + sum(([queue_slot(i), spacer(h=3)] for i in range(QUEUE)), [])),
        spacer(flex=True),
        group("ActiveCard", ["Anchor: (Height: 278);", "LayoutMode: Top;", f"Background: {PANEL2};", "Padding: (Full: 10);"], [
            label("ActHeader", "ХОДИТ", 12, GOLD, True, height=18),
            group("", ["Anchor: (Height: 92);", "LayoutMode: Left;"], [
                slot("ActIcon", 88),
                spacer(w=10),
                group("", ["FlexWeight: 1;", "LayoutMode: Top;"], [
                    label("ActName", "", 22, TEXT, True, height=30),
                    label("ActTitle", "", 14, GOLD, height=20),
                    label("ActNums", "", 13, DIM, height=36, wrap=True),
                ]),
            ]),
            bar("ActHp", BAR_W_ACT, HP_BACK, HP, h=10),
            spacer(h=3),
            bar("ActMp", BAR_W_ACT, MP_BACK, MP, h=6),
            spacer(h=6),
            group("ActStatus", ["Anchor: (Height: 24);", "LayoutMode: Left;"], []),
            label("ActStatusText", "", 12, TEXT, height=54, wrap=True),
            label("ActImmune", "", 12, "#c9a7ff", height=18),
        ]),
    ])

    enemy_row = group("EnemyRow", ["Anchor: (Height: 118);", "LayoutMode: Left;"],
                      sum(([enemy_card(i), spacer(w=8)] for i in range(ENEMIES)), []))
    party_row = group("PartyRow", ["Anchor: (Height: 128);", "LayoutMode: Left;"],
                      sum(([party_card(i), spacer(w=8)] for i in range(PARTY)), []))
    center = group("Center", ["FlexWeight: 1;", "LayoutMode: Top;"], [
        enemy_row,
        spacer(h=10),
        label("Banner", "", 30, GOLD, True, height=44),
        spacer(flex=True),
        group("LogPanel", ["Anchor: (Height: 118);", "LayoutMode: Top;", f"Background: {PANEL};", "Padding: (Full: 8);"], [
            label("Log", "", 13, "#d8e2ee", flex=True, wrap=True),
        ]),
        spacer(h=8),
        party_row,
    ])

    right = group("RightCol", ["Anchor: (Width: 420);", "LayoutMode: Top;"], [
        spacer(flex=True),
        group("InfoPanel", ["Anchor: (Height: 260);", "LayoutMode: Top;", f"Background: {PANEL2};", "Padding: (Full: 10);"], [
            group("", ["Anchor: (Height: 44);", "LayoutMode: Left;"], [
                slot("InfoIcon", 40),
                spacer(w=8),
                label("InfoTitle", "", 18, GOLD, True, height=40, flex=True),
            ]),
            label("InfoText", "", 13, TEXT, flex=True, wrap=True),
        ]),
        spacer(h=8),
        group("MenuPanel", ["Anchor: (Height: 372);", "LayoutMode: Top;", f"Background: {PANEL};", "Padding: (Full: 10);"],
              [label("MenuTitle", "", 18, TEXT, True, height=30)]
              + [option_row(i) for i in range(OPTIONS)]
              + [spacer(h=4),
                 '$C.@CancelTextButton #Back { @Text = "Назад"; Anchor: (Height: 32); }',
                 label("Error", "", 13, "#ff7070", height=22)]),
    ])

    root = group("", ["LayoutMode: Left;", "Padding: (Full: 12);"], [left, spacer(w=12), center, spacer(w=12), right])
    text = ('$C = "../Common.ui";\n\n'
            "// Боевое меню JRPG. Файл собран скриптом tools/gen_battle_ui.py — правь скрипт, а не этот файл.\n"
            "// Слева очередь ходов и карточка ходящего, сверху враги, снизу партия и журнал,\n"
            "// справа окно описаний и команды. Иконки вставляются в пустые слоты (#...Icon) из Icons/*.ui.\n\n"
            + root + "\n")
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(text)
    print("written", os.path.abspath(OUT), len(text.splitlines()), "lines")
    progression()


STATS = 6
NODES = 4
TABS = 3


def stat_row(i):
    return group(f"StatRow{i}", ["Anchor: (Height: 36);", "LayoutMode: Left;"], [
        label(f"Stat{i}", "", 15, TEXT, height=34, flex=True),
        f'$C.@SecondaryTextButton #StatPlus{i} {{ @Text = "+"; Anchor: (Width: 44, Height: 32); }}',
    ])


def node_row(i):
    return group(f"Node{i}", ["Anchor: (Height: 86);", "LayoutMode: Left;", f"Background: {PANEL2};", "Padding: (Full: 6);"], [
        slot(f"Node{i}Icon", 40),
        spacer(w=8),
        group("", ["FlexWeight: 1;", "LayoutMode: Top;"], [
            label(f"Node{i}Name", "", 16, GOLD, True, height=22),
            label(f"Node{i}Text", "", 12, TEXT, flex=True, wrap=True),
        ]),
        spacer(w=8),
        group("", ["Anchor: (Width: 150);", "LayoutMode: Top;"], [
            label(f"Node{i}State", "", 12, DIM, height=30, wrap=True),
            f'$C.@SecondaryTextButton #Learn{i} {{ @Text = "Изучить"; Anchor: (Height: 34); }}',
        ]),
    ])


def progression():
    tabs = group("Tabs", ["Anchor: (Height: 42);", "LayoutMode: Left;"],
                 sum(([f'$C.@SecondaryTextButton #Tab{i} {{ @Text = ""; Anchor: (Width: 220, Height: 38); }}', spacer(w=8)]
                      for i in range(TABS)), []))
    left = group("HeroCol", ["Anchor: (Width: 400);", "LayoutMode: Top;", f"Background: {PANEL2};", "Padding: (Full: 10);"], [
        group("", ["Anchor: (Height: 92);", "LayoutMode: Left;"], [
            slot("HeroIcon", 88),
            spacer(w=10),
            group("", ["FlexWeight: 1;", "LayoutMode: Top;"], [
                label("HeroName", "", 22, TEXT, True, height=30),
                label("HeroTitle", "", 14, GOLD, height=20),
                label("HeroLevel", "", 14, DIM, height=36, wrap=True),
            ]),
        ]),
        bar("Exp", 370, "#2a2410", GOLD, h=8),
        spacer(h=8),
        label("Points", "", 14, GOLD, True, height=40, wrap=True),
        label("", "ХАРАКТЕРИСТИКИ", 13, DIM, True, height=22),
    ] + [stat_row(i) for i in range(STATS)] + [
        spacer(h=6),
        label("Known", "", 12, DIM, flex=True, wrap=True),
    ])
    right = group("TreeCol", ["FlexWeight: 1;", "LayoutMode: Top;", f"Background: {PANEL};", "Padding: (Full: 10);"], [
        label("", "ДЕРЕВО НАВЫКОВ", 15, GOLD, True, height=26),
        label("", "Очки навыков даются за уровень. Навыки открываются по порядку.", 12, DIM, height=20),
    ] + sum(([node_row(i), spacer(h=6)] for i in range(NODES)), []) + [
        spacer(flex=True),
        label("Message", "", 14, "#ffd27a", height=24),
    ])
    window = group("Window", ["Anchor: (Width: 1120);", "LayoutMode: Top;", "Background: #070b12(0.94);", "Padding: (Full: 14);"], [
        group("", ["Anchor: (Height: 40);", "LayoutMode: Left;"], [
            label("", "ПРОКАЧКА ПАРТИИ", 24, GOLD, True, height=38, flex=True),
            label("", "тестовая версия", 13, DIM, width=160, height=38),
        ]),
        tabs,
        spacer(h=8),
        group("", ["FlexWeight: 1;", "LayoutMode: Left;"], [left, spacer(w=12), right]),
        spacer(h=10),
        group("", ["Anchor: (Height: 40);", "LayoutMode: Left;"], [
            '$C.@SecondaryTextButton #AddExp { @Text = "+100 опыта (тест)"; Anchor: (Width: 230, Height: 36); }',
            spacer(w=8),
            '$C.@SecondaryTextButton #Respec { @Text = "Сбросить очки"; Anchor: (Width: 200, Height: 36); }',
            spacer(flex=True),
            '$C.@CancelTextButton #Close { @Text = "Закрыть"; Anchor: (Width: 160, Height: 36); }',
        ]),
    ])
    root = group("", ["LayoutMode: Top;"], [
        spacer(flex=True),
        group("", ["Anchor: (Height: 760);", "LayoutMode: Left;"], [spacer(flex=True), window, spacer(flex=True)]),
        spacer(flex=True),
    ])
    out = os.path.join(os.path.dirname(OUT), "Progression.ui")
    text = ('$C = "../Common.ui";\n\n'
            "// Меню прокачки партии (тестовое). Собрано tools/gen_battle_ui.py — правь скрипт.\n\n"
            + root + "\n")
    with open(out, "w", encoding="utf-8") as f:
        f.write(text)
    print("written", os.path.abspath(out), len(text.splitlines()), "lines")


if __name__ == "__main__":
    main()
