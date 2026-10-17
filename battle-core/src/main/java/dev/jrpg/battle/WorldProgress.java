package dev.jrpg.battle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Прогресс мира: какие уникальные встречи (боссы) побеждены и когда зачищены обычные.
 * В Hytale сохраняется вместе с миром/игроком (этап 7); здесь — простое состояние.
 */
public final class WorldProgress {
    public final Set<String> defeatedUnique = new HashSet<>();
    public final Map<String, Long> clearedAtMillis = new HashMap<>();

    public boolean isAvailable(Encounter e, long nowMillis) {
        if (e.unique()) return !defeatedUnique.contains(e.id());
        Long cleared = clearedAtMillis.get(e.id());
        if (cleared == null) return true;
        if (e.respawnMinutes() <= 0) return false;
        return nowMillis - cleared >= e.respawnMinutes() * 60_000L;
    }

    public void markCleared(Encounter e, long nowMillis) {
        if (e.unique()) defeatedUnique.add(e.id());
        else clearedAtMillis.put(e.id(), nowMillis);
    }
}
