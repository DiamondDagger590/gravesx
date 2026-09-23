package com.ranull.graves.manager;

import com.ranull.graves.data.BlockKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Index from death-block position to grave UUIDs and back. Thread-safe; reads never block.
 * <p>
 * Owned and maintained by {@link CacheManager}. Per-key lists are copy-on-write: they are tiny
 * (almost always one element) and written only when a grave is added or removed. The index is
 * multi-valued because two graves can share a death block (for example through the grave creation
 * API or an import); lookups return the graves in insertion order so the earliest one wins.
 * </p>
 *
 * @since 2026.4.9.3
 */
final class GraveIndex {

    /**
     * Grave UUIDs recorded at each death block, earliest-inserted first.
     */
    private final Map<BlockKey, CopyOnWriteArrayList<UUID>> gravesByDeathBlock = new ConcurrentHashMap<>();

    /**
     * The recorded death block of each indexed grave; the reverse direction of {@link #gravesByDeathBlock}.
     */
    private final Map<UUID, BlockKey> deathBlockByGrave = new ConcurrentHashMap<>();

    /**
     * Records {@code graveUUID} at {@code key}, replacing any previous position for that grave.
     *
     * @param graveUUID the grave UUID
     * @param key       the grave's death block
     */
    void add(@NotNull UUID graveUUID, @NotNull BlockKey key) {
        remove(graveUUID);
        deathBlockByGrave.put(graveUUID, key);
        // compute (not computeIfAbsent + add) so a concurrent remove() cannot drop the list between the two steps
        gravesByDeathBlock.compute(key, (k, list) -> {
            CopyOnWriteArrayList<UUID> target = list != null ? list : new CopyOnWriteArrayList<>();
            target.addIfAbsent(graveUUID);
            return target;
        });
    }

    /**
     * Forgets {@code graveUUID}. No-op if unknown.
     *
     * @param graveUUID the grave UUID
     */
    void remove(@NotNull UUID graveUUID) {
        BlockKey old = deathBlockByGrave.remove(graveUUID);
        if (old == null) {
            return;
        }

        gravesByDeathBlock.computeIfPresent(old, (k, list) -> {
            list.remove(graveUUID);
            return list.isEmpty() ? null : list;
        });
    }

    /**
     * Empties both directions of the index.
     */
    void clear() {
        gravesByDeathBlock.clear();
        deathBlockByGrave.clear();
    }

    /**
     * Grave UUIDs recorded at {@code key}, earliest first.
     *
     * @param key the death block to look up; may be {@code null}
     * @return a safe-to-iterate snapshot; never {@code null}, empty when {@code key} is {@code null} or unknown
     */
    @NotNull
    List<UUID> lookup(@Nullable BlockKey key) {
        if (key == null) {
            return List.of();
        }

        List<UUID> list = gravesByDeathBlock.get(key);
        return list != null ? List.copyOf(list) : List.of();
    }

    /**
     * The recorded death block of {@code graveUUID}.
     *
     * @param graveUUID the grave UUID
     * @return the recorded death block, or {@code null} if the grave is not indexed
     */
    @Nullable
    BlockKey keyOf(@NotNull UUID graveUUID) {
        return deathBlockByGrave.get(graveUUID);
    }
}
