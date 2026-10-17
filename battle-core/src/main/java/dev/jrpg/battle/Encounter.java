package dev.jrpg.battle;

import java.util.List;

/**
 * Встреча, поставленная вручную в конкретное место мира (encounters.json). Случайных встреч нет:
 * враги появляются только там, где их поставил автор.
 *
 * @param unique         одноразовая (босс): после победы больше не появляется
 * @param respawnMinutes для обычных встреч: через сколько минут после зачистки появится снова (0 — никогда)
 * @param canFlee        можно ли сбежать
 * @param enemies        враги и их места на арене относительно центра (партия стоит на z = 0, враги на z > 0)
 * @param intro          реплики в начале боя
 * @param phases         фазы боссов по HP
 * @param script         id эксклюзивного Java-скрипта (BossScripts), может быть null
 */
public record Encounter(String id, String name, Location location, boolean unique, int respawnMinutes,
                        boolean canFlee, List<Spot> enemies, List<Line> intro, List<PhaseScript.Phase> phases,
                        String script, List<Loot> loot, int exp) {

    /** Точка в мире; бой начинается, когда игрок входит в triggerRadius или бьёт врага встречи. */
    public record Location(String world, double x, double y, double z, double triggerRadius) {}

    public record Spot(String template, double x, double z) {}

    public record Line(String speaker, String text) {}

    public record Loot(String item, int count, double chance) {}

    public List<Line> intro() { return intro == null ? List.of() : intro; }
    public List<PhaseScript.Phase> phases() { return phases == null ? List.of() : phases; }
    public List<Loot> loot() { return loot == null ? List.of() : loot; }
}
