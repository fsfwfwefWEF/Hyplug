# JRPG Battles для Hytale

Пошаговые бои в духе Final Fantasy / Expedition 33: партия из 3, меню действий, способности, предметы, статусы, уникальные боссы.

- Сборка мода: `./gradlew :hytale-plugin:jar` (JDK 25) → `hytale-plugin/build/libs/JrpgBattles-<версия>.jar`
- Тесты ядра: `./gradlew :battle-core:test`
- Консольный прототип: `play.bat` / `sh play.sh`

Подробности: [DEVLOG.md](DEVLOG.md) (как устроено и как переносить), [DESIGN.md](DESIGN.md) (дизайн).

Команды в игре: `/jrpg list`, `/jrpg start <id>`, `/jrpg menu`, `/jrpg stop`, `/jrpg party` (прокачка), `/jrpg roles <фильтр>`, `/jrpg reload`.
Генераторы интерфейса и иконок: `tools/gen_battle_ui.py`, `tools/gen_icons.py`.
