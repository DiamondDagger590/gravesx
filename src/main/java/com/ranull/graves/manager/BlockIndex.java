package com.ranull.graves.manager;

import com.ranull.graves.data.BlockData;
import com.ranull.graves.data.BlockKey;
import com.ranull.graves.data.ChunkData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Index of placed grave blocks: by owning grave, and by block position. Thread-safe.
 * <p>
 * Owned and maintained by {@link CacheManager}. Every {@link BlockData} that enters or leaves
 * {@link ChunkData#getBlockDataMap()} must pass through {@link #add} / {@link #remove}.
 * {@link BlockData} has identity equality, so removals are exact: a block that has since been
 * overwritten at its position is never mistaken for the newer one.
 * </p>
 *
 * @since 2026.4.9.3
 */
final class BlockIndex {

    /**
     * Placed blocks of each grave, keyed by block position.
     */
    private final Map<UUID, Map<BlockKey, BlockData>> byGrave = new ConcurrentHashMap<>();

    /**
     * The placed block recorded at each position.
     */
    private final Map<BlockKey, BlockData> byKey = new ConcurrentHashMap<>();

    /**
     * Records {@code blockData}; unlinks any different {@code BlockData} previously recorded at the same key.
     * Idempotent. Blocks without a grave UUID or without a world are ignored.
     *
     * @param blockData the block to record
     */
    void add(@NotNull BlockData blockData) {
        UUID graveUUID = blockData.getGraveUUID();
        BlockKey key = BlockKey.of(blockData.getLocation());
        if (graveUUID == null || key == null) {
            return;
        }

        BlockData previous = byKey.put(key, blockData);
        if (previous != null && previous != blockData) {
            unlink(previous, key);
        }

        // compute (not computeIfAbsent + put) so a concurrent unlink() cannot drop the map between the two steps
        byGrave.compute(graveUUID, (g, perGrave) -> {
            Map<BlockKey, BlockData> target = perGrave != null ? perGrave : new ConcurrentHashMap<>();
            target.put(key, blockData);
            return target;
        });
    }

    /**
     * Forgets {@code blockData}. No-op if unknown or if a different {@code BlockData} now occupies its key.
     *
     * @param blockData the block to forget
     */
    void remove(@NotNull BlockData blockData) {
        BlockKey key = BlockKey.of(blockData.getLocation());
        if (key == null) {
            return;
        }

        byKey.remove(key, blockData);
        unlink(blockData, key);
    }

    /**
     * Empties both directions of the index.
     */
    void clear() {
        byGrave.clear();
        byKey.clear();
    }

    /**
     * Snapshot of the blocks recorded for {@code graveUUID}.
     *
     * @param graveUUID the grave UUID; may be {@code null}
     * @return the recorded blocks; never {@code null}, empty when the grave is {@code null} or unknown
     */
    @NotNull
    List<BlockData> forGrave(@Nullable UUID graveUUID) {
        Map<BlockKey, BlockData> perGrave = graveUUID != null ? byGrave.get(graveUUID) : null;
        return perGrave != null ? List.copyOf(perGrave.values()) : List.of();
    }

    /**
     * The block recorded at {@code key}.
     *
     * @param key the block position; may be {@code null}
     * @return the recorded block, or {@code null} if none (or {@code key} is {@code null})
     */
    @Nullable
    BlockData at(@Nullable BlockKey key) {
        return key != null ? byKey.get(key) : null;
    }

    /**
     * Removes {@code blockData} from its grave's per-grave map, dropping the map once it is empty.
     * Atomic per grave, so two threads touching the same grave's blocks cannot lose an update.
     *
     * @param blockData the block to unlink
     * @param key       the block's position
     */
    private void unlink(@NotNull BlockData blockData, @NotNull BlockKey key) {
        UUID graveUUID = blockData.getGraveUUID();
        if (graveUUID == null) {
            return;
        }

        byGrave.computeIfPresent(graveUUID, (g, perGrave) -> {
            perGrave.remove(key, blockData);
            return perGrave.isEmpty() ? null : perGrave;
        });
    }
}
