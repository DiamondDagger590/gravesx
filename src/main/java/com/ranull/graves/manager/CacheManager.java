package com.ranull.graves.manager;

import com.ranull.graves.data.BlockData;
import com.ranull.graves.data.BlockKey;
import com.ranull.graves.data.ChunkData;
import com.ranull.graves.data.EntityData;
import com.ranull.graves.data.LocationData;
import com.ranull.graves.type.Grave;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CacheManager {
    /**
     * A map of grave UUIDs to their corresponding {@link Grave} objects.
     * <p>
     * This {@link Map} associates each {@link UUID} with a {@link Grave} instance, allowing for quick retrieval
     * of grave information based on its unique identifier.
     * </p>
     */
    private final Map<UUID, Grave> graveMap;

    /**
     * Death-block &harr; grave index kept in step with {@link #graveMap} by {@link IndexedGraveMap}.
     */
    private final GraveIndex graveIndex = new GraveIndex();

    /**
     * Grave &harr; placed-block index maintained through {@link #addBlockData(BlockData)},
     * {@link #removeBlockData(BlockData)}, {@link #removeChunkBlockData(ChunkData)} and
     * {@link #rebuildBlockIndex()}.
     */
    private final BlockIndex blockIndex = new BlockIndex();

    /**
     * A map of chunk identifiers to their corresponding {@link ChunkData} objects.
     * <p>
     * This {@link Map} associates each chunk identifier (as a {@link String}) with {@link ChunkData}, which holds
     * information about the specific chunk.
     * </p>
     */
    private final Map<String, ChunkData> chunkMap;

    /**
     * A map of entity UUIDs to their last known {@link Location}.
     * <p>
     * This {@link Map} tracks the most recent {@link Location} for each entity identified by its {@link UUID}.
     * </p>
     */
    private final Map<UUID, Location> lastLocationMap;

    /**
     * A map of entity UUIDs to lists of removed {@link ItemStack} objects.
     * <p>
     * This {@link Map} associates each entity's {@link UUID} with a {@link List} of {@link ItemStack} objects
     * that have been removed from the entity.
     * </p>
     */
    private final Map<UUID, List<ItemStack>> removedItemStackMap;

    /**
     * A map of block identifiers to their corresponding {@link Location} objects where the block was right-clicked.
     * <p>
     * This {@link Map} tracks the locations of blocks that have been right-clicked, identified by a {@link String}
     * representing the block identifier.
     * </p>
     */
    private final Map<String, Location> rightClickedBlocks = new HashMap<>();

    /**
     * A map of grave UUIDs to the UUID of the player currently viewing that grave.
     * <p>
     * Used to prevent multiple players from accessing the same grave at the same time.
     * If a grave UUID is present in this map, the grave is considered "in use" / "locked".
     * </p>
     */
    private final Map<UUID, UUID> graveViewerMap;

    /**
     * A map of entity UUIDs to their corresponding {@link EntityData}.
     * <p>
     * This provides a fast global lookup for tracked entity-backed grave data
     * without requiring a chunk scan.
     * </p>
     */
    private final Map<UUID, EntityData> entityMap;


    /**
     * Constructs a new {@link CacheManager} with initialized maps.
     * <p>
     * The constructor initializes all the maps used for caching data related to graves, chunks, locations, and items
     * </p>
     */
    public CacheManager() {
        this.graveMap = new IndexedGraveMap();
        this.chunkMap = new HashMap<>();
        this.lastLocationMap = new HashMap<>();
        this.removedItemStackMap = new HashMap<>();
        this.graveViewerMap = new HashMap<>();
        this.entityMap = new HashMap<>();
    }

    /**
     * Returns the map of grave UUIDs to their corresponding {@link Grave} objects.
     * @return the map of graves
     */
    public Map<UUID, Grave> getGraveMap() {
        return graveMap;
    }

    /**
     * Adds a right-clicked block location for a specified player.
     * @param playerName the name of the player
     * @param location the location of the right-clicked block
     */
    public void addRightClickedBlock(String playerName, Location location) {
        rightClickedBlocks.put(playerName, location);
    }

    /**
     * Retrieves the location of the right-clicked block for a specified player.
     * @param playerName the name of the player
     * @return the location of the right-clicked block, or {@code null} if not found
     */
    public Location getRightClickedBlock(String playerName) {
        return rightClickedBlocks.get(playerName);
    }

    /**
     * Removes the right-clicked block location for a specified player.
     * @param playerName the name of the player
     * @param location the location of the right-clicked block
     */
    public void removeRightClickedBlock(String playerName, Location location) {
        rightClickedBlocks.remove(playerName, location);
    }

    /**
     * Checks if a right-clicked block location exists for a specified player.
     * @param playerName the name of the player
     * @return {@code true} if the right-clicked block location exists, {@code false} otherwise
     */
    public boolean hasRightClickedBlock(String playerName) {
        return rightClickedBlocks.containsKey(playerName);
    }

    /**
     * Returns the map of chunk identifiers to their corresponding {@link ChunkData} objects.
     * @return the map of chunk data
     */
    public Map<String, ChunkData> getChunkMap() {
        return chunkMap;
    }

    /**
     * Returns the map of entity UUIDs to their last known {@link Location}.
     * @return the map of last known locations
     */
    public Map<UUID, Location> getLastLocationMap() {
        return lastLocationMap;
    }

    /**
     * Returns the map of entity UUIDs to lists of removed {@link ItemStack} objects.
     * @return the map of removed item stacks
     */
    public Map<UUID, List<ItemStack>> getRemovedItemStackMap() {
        return removedItemStackMap;
    }

    /**
     * Marks a grave as currently being viewed by the given player.
     * <p>
     * If the grave is already being viewed by someone else, this will NOT overwrite the current viewer.
     * Use {@link #canAccessGrave(UUID, UUID)} / {@link #isGraveBeingViewed(UUID)} to check first.
     * </p>
     *
     * @param graveUUID  the grave UUID
     * @param viewerUUID the player's UUID
     * @return {@code true} if the viewer was set (lock acquired), {@code false} if someone else already holds it
     */
    public boolean startViewingGrave(UUID graveUUID, UUID viewerUUID) {
        UUID current = graveViewerMap.get(graveUUID);
        if (current == null || current.equals(viewerUUID)) {
            graveViewerMap.put(graveUUID, viewerUUID);
            return true;
        }
        return false;
    }

    /**
     * Clears the viewer lock for a grave if the given player is the current viewer.
     *
     * @param graveUUID  the grave UUID
     * @param viewerUUID the player's UUID
     */
    public void stopViewingGrave(UUID graveUUID, UUID viewerUUID) {
        UUID current = graveViewerMap.get(graveUUID);
        if (current != null && current.equals(viewerUUID)) {
            graveViewerMap.remove(graveUUID);
        }
    }

    /**
     * Force-clears the viewer lock for a grave (regardless of who is viewing).
     * Useful for cleanup if a viewer disconnects unexpectedly.
     *
     * @param graveUUID the grave UUID
     */
    public void clearGraveViewer(UUID graveUUID) {
        graveViewerMap.remove(graveUUID);
    }

    /**
     * Clears any grave-viewer locks held by the specified player.
     * Useful to call on PlayerQuitEvent.
     *
     * @param viewerUUID the player's UUID
     */
    public void clearAllGraveViewersFor(UUID viewerUUID) {
        graveViewerMap.entrySet().removeIf(e -> viewerUUID.equals(e.getValue()));
    }

    /**
     * Checks whether a grave is currently being viewed by someone.
     *
     * @param graveUUID the grave UUID
     * @return {@code true} if the grave is being viewed, otherwise {@code false}
     */
    public boolean isGraveBeingViewed(UUID graveUUID) {
        return graveViewerMap.containsKey(graveUUID);
    }

    /**
     * Gets the UUID of the player currently viewing a grave, or {@code null} if none.
     *
     * @param graveUUID the grave UUID
     * @return the viewer UUID, or {@code null}
     */
    public UUID getGraveViewer(UUID graveUUID) {
        return graveViewerMap.get(graveUUID);
    }

    /**
     * Checks if a player can access a grave right now.
     * <p>
     * Access is allowed if the grave is not being viewed, or if it is being viewed by the same player.
     * </p>
     *
     * @param graveUUID  the grave UUID
     * @param viewerUUID the player's UUID
     * @return {@code true} if access is allowed, otherwise {@code false}
     */
    public boolean canAccessGrave(UUID graveUUID, UUID viewerUUID) {
        UUID current = graveViewerMap.get(graveUUID);
        return (current == null || current.equals(viewerUUID));
    }

    /**
     * Retrieves a {@link Grave} from the cache by its UUID
     * @param graveUUID the UUID of the grave to retrieve
     * @return the {@link Grave} associated with the UUID provided, or {@code null} if not present
     */
    public Grave getGrave(UUID graveUUID) {
        return graveMap.get(graveUUID);
    }

    /**
     * Convenience method to retrieve a {@link Grave} from the cache based on a {@link Block}.
     * <p>
     * Internally delegates to {@link #getGrave(Location)} using the block's location.
     * </p>
     *
     * @param block the block to check
     * @return the matching {@link Grave}, or {@code null} if none is found
     */
    public Grave getGrave(Block block) {
        List<Grave> graves = gravesAtDeathBlock(BlockKey.of(block));
        return graves.isEmpty() ? null : graves.get(0);
    }

    /**
     * Returns the oldest grave for a given player.
     * @param playerUUID The UUID of the player whose graves to consider.
     * @return The oldest grave for the specified player.
     */
    public Grave getOldestGrave(UUID playerUUID) {
        long oldestTime = Long.MAX_VALUE;
        Grave oldestGrave = null;

        for (Grave cur : graveMap.values()) {
            if (cur.getOwnerUUID().equals(playerUUID)) {
                long curTime = cur.getTimeCreation();
                if (curTime < oldestTime) {
                    oldestTime = curTime;
                    oldestGrave = cur;
                }
            }
        }

        return oldestGrave;
    }

    /**
     * Retrieves the {@link Grave} whose block is located at the given {@link Location}.
     * <p>
     * This compares block coordinates (world + block X/Y/Z) instead of raw double coordinates
     * to ensure it matches the actual block the grave is placed on.
     * </p>
     *
     * @param location the location of the grave block
     * @return the matching {@link Grave}, or {@code null} if none is found
     */
    public Grave getGrave(Location location) {
        List<Grave> graves = gravesAtDeathBlock(BlockKey.of(location));
        return graves.isEmpty() ? null : graves.get(0);
    }

    /**
     * All cached graves whose death block is {@code key}, earliest-inserted first.
     *
     * @param key the death block; may be {@code null}
     * @return the matching graves; never {@code null}
     * @since 2026.4.9.3
     */
    public @NotNull List<Grave> getGravesAt(@Nullable BlockKey key) {
        return gravesAtDeathBlock(key);
    }

    /**
     * Re-indexes a cached grave after its death location changed. Must be called by any code that
     * invokes {@link Grave#setLocationDeath(Location)} on a grave already in the cache.
     *
     * @param grave the grave whose death location changed; ignored if {@code null}
     * @since 2026.4.9.3
     */
    public void reindexGrave(@Nullable Grave grave) {
        if (grave == null || grave.getUUID() == null) {
            return;
        }

        if (graveMap.containsKey(grave.getUUID())) {
            indexDeath(grave.getUUID(), grave);
        } else {
            graveIndex.remove(grave.getUUID());
        }
    }

    /**
     * Records a placed grave block in the index. Idempotent.
     *
     * @param blockData the block; ignored if {@code null}
     * @since 2026.4.9.3
     */
    public void addBlockData(@Nullable BlockData blockData) {
        if (blockData != null) {
            blockIndex.add(blockData);
        }
    }

    /**
     * Forgets a placed grave block. No-op if unknown.
     *
     * @param blockData the block; ignored if {@code null}
     * @since 2026.4.9.3
     */
    public void removeBlockData(@Nullable BlockData blockData) {
        if (blockData != null) {
            blockIndex.remove(blockData);
        }
    }

    /**
     * Forgets every block of a chunk being dropped from the cache.
     *
     * @param chunkData the chunk; ignored if {@code null}
     * @since 2026.4.9.3
     */
    public void removeChunkBlockData(@Nullable ChunkData chunkData) {
        if (chunkData == null) {
            return;
        }

        for (BlockData blockData : new ArrayList<>(chunkData.getBlockDataMap().values())) {
            blockIndex.remove(blockData);
        }
    }

    /**
     * Re-adds every block in the chunk map to the index. Additive and idempotent: it never clears, so
     * it is safe to run while other threads are still writing.
     *
     * @since 2026.4.9.3
     */
    public void rebuildBlockIndex() {
        for (ChunkData chunkData : new ArrayList<>(chunkMap.values())) {
            for (BlockData blockData : new ArrayList<>(chunkData.getBlockDataMap().values())) {
                blockIndex.add(blockData);
            }
        }
    }

    /**
     * Snapshot of the placed blocks recorded for a grave.
     *
     * @param graveUUID the grave UUID; may be {@code null}
     * @return the recorded blocks; never {@code null}
     * @since 2026.4.9.3
     */
    public @NotNull List<BlockData> getBlockDataForGrave(@Nullable UUID graveUUID) {
        return blockIndex.forGrave(graveUUID);
    }

    /**
     * The grave block recorded at a position.
     *
     * @param key the block position; may be {@code null}
     * @return the recorded block, or {@code null} if none
     * @since 2026.4.9.3
     */
    public @Nullable BlockData getBlockDataAt(@Nullable BlockKey key) {
        return blockIndex.at(key);
    }

    /**
     * Indexes {@code grave}'s death block from its stored {@link LocationData} &mdash; no world lookup, no
     * {@link Location} allocation. A grave without a stored death location is removed from the index.
     *
     * @param graveUUID the grave UUID
     * @param grave     the grave; may be {@code null}
     */
    private void indexDeath(@NotNull UUID graveUUID, @Nullable Grave grave) {
        LocationData deathData = grave != null ? grave.getLocationDeathData() : null;
        BlockKey key = deathData != null ? deathData.toBlockKey() : null;
        if (key != null) {
            graveIndex.add(graveUUID, key);
        } else {
            graveIndex.remove(graveUUID);
        }
    }

    /**
     * Cached graves whose stored death block is {@code key}, earliest-inserted first. Stale index entries
     * (grave removed, or death location changed without {@link #reindexGrave(Grave)}) are dropped on the way.
     *
     * @param key the death block; may be {@code null}
     * @return the matching graves; never {@code null}
     */
    private @NotNull List<Grave> gravesAtDeathBlock(@Nullable BlockKey key) {
        List<UUID> uuids = graveIndex.lookup(key);
        if (uuids.isEmpty()) {
            return List.of();
        }

        List<Grave> graves = new ArrayList<>(uuids.size());
        for (UUID uuid : uuids) {
            Grave grave = graveMap.get(uuid);
            LocationData deathData = grave != null ? grave.getLocationDeathData() : null;
            BlockKey current = deathData != null ? deathData.toBlockKey() : null;
            if (grave != null && key.equals(current)) {
                graves.add(grave);
            } else {
                // Self-heal: the grave was removed, or its death location changed without reindexGrave().
                graveIndex.remove(uuid);
            }
        }

        return graves;
    }

    /**
     * Returns the map of entity UUIDs to their corresponding {@link EntityData}.
     *
     * @return the entity map
     */
    public Map<UUID, EntityData> getEntityMap() {
        return entityMap;
    }

    /**
     * Retrieves tracked entity data by entity UUID.
     *
     * @param entityUUID the entity UUID
     * @return the tracked entity data, or {@code null} if not cached
     */
    public EntityData getEntityData(UUID entityUUID) {
        if (entityUUID == null) {
            return null;
        }

        return entityMap.get(entityUUID);
    }

    /**
     * Adds or replaces tracked entity data in the cache.
     *
     * @param entityData the entity data to cache
     */
    public void addEntityData(EntityData entityData) {
        if (entityData == null || entityData.getUUIDEntity() == null) {
            return;
        }

        entityMap.put(entityData.getUUIDEntity(), entityData);
    }

    /**
     * Removes tracked entity data from the cache by entity UUID.
     *
     * @param entityUUID the entity UUID
     */
    public void removeEntityData(UUID entityUUID) {
        if (entityUUID == null) {
            return;
        }

        entityMap.remove(entityUUID);
    }

    /**
     * Removes tracked entity data from the cache.
     *
     * @param entityData the entity data to remove
     */
    public void removeEntityData(EntityData entityData) {
        if (entityData == null) {
            return;
        }

        removeEntityData(entityData.getUUIDEntity());
    }

    /**
     * {@code Map<UUID, Grave>} view over a {@link ConcurrentHashMap} whose mutators keep
     * {@link #graveIndex} in step. Backed by a concurrent map so the async database loader and
     * the main-thread timer can no longer race a {@code HashMap}.
     * <p>
     * All {@link Map} default methods ({@code putIfAbsent}, {@code compute*}, {@code merge},
     * {@code replace}, {@code remove(k, v)}, {@code replaceAll}) are implemented in terms of
     * {@link #get}, {@link #put}, {@link #remove} and {@link #entrySet()}, so overriding those plus
     * {@link #clear()}, {@link #putAll} and the entry iterator covers every mutation path. Null keys
     * are tolerated by the read and remove paths, as the previous {@code HashMap} did.
     * </p>
     * <p>
     * {@link #remove(Object)} deliberately does not touch {@link #blockIndex}: some removal paths drop the
     * grave from the map before the grave's blocks are removed, and still need the block list.
     * </p>
     */
    private final class IndexedGraveMap extends AbstractMap<UUID, Grave> {

        /**
         * The backing map holding the actual entries.
         */
        private final ConcurrentHashMap<UUID, Grave> delegate = new ConcurrentHashMap<>();

        /**
         * Returns the grave mapped to {@code key}.
         *
         * @param key the grave UUID; may be {@code null}
         * @return the grave, or {@code null} if absent or {@code key} is {@code null}
         */
        @Override
        public @Nullable Grave get(@Nullable Object key) {
            return key == null ? null : delegate.get(key);
        }

        /**
         * Returns whether {@code key} is mapped.
         *
         * @param key the grave UUID; may be {@code null}
         * @return {@code true} if mapped; {@code false} if absent or {@code key} is {@code null}
         */
        @Override
        public boolean containsKey(@Nullable Object key) {
            return key != null && delegate.containsKey(key);
        }

        /**
         * Returns the number of cached graves.
         *
         * @return the size of the map
         */
        @Override
        public int size() {
            return delegate.size();
        }

        /**
         * Maps {@code key} to {@code value} and indexes the grave's death block.
         *
         * @param key   the grave UUID
         * @param value the grave
         * @return the previously mapped grave, or {@code null}
         */
        @Override
        public @Nullable Grave put(@NotNull UUID key, @NotNull Grave value) {
            Grave previous = delegate.put(key, value);
            indexDeath(key, value);
            return previous;
        }

        /**
         * Removes the mapping for {@code key} and unindexes its death block.
         *
         * @param key the grave UUID; may be {@code null}
         * @return the removed grave, or {@code null} if absent or {@code key} is {@code null}
         */
        @Override
        public @Nullable Grave remove(@Nullable Object key) {
            if (key == null) {
                return null;
            }

            Grave removed = delegate.remove(key);
            if (removed != null && key instanceof UUID uuid) {
                graveIndex.remove(uuid);
            }

            return removed;
        }

        /**
         * Puts every entry of {@code m} through {@link #put} so each is indexed.
         *
         * @param m the entries to add
         */
        @Override
        public void putAll(@NotNull Map<? extends UUID, ? extends Grave> m) {
            m.forEach(this::put);
        }

        /**
         * Removes every grave and empties the death-block index.
         */
        @Override
        public void clear() {
            delegate.clear();
            graveIndex.clear();
        }

        /**
         * Entry-set view whose iterator removal and {@code Entry.setValue} keep the index in step.
         *
         * @return the entry set view
         */
        @Override
        public @NotNull Set<Entry<UUID, Grave>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public int size() {
                    return delegate.size();
                }

                /**
                 * Returns an iterator whose {@code remove} unindexes the removed grave.
                 *
                 * @return the entry iterator
                 */
                @Override
                public @NotNull Iterator<Entry<UUID, Grave>> iterator() {
                    Iterator<Entry<UUID, Grave>> it = delegate.entrySet().iterator();
                    return new Iterator<>() {
                        /**
                         * The entry most recently returned by {@link #next()}.
                         */
                        private Entry<UUID, Grave> current;

                        @Override
                        public boolean hasNext() {
                            return it.hasNext();
                        }

                        @Override
                        public Entry<UUID, Grave> next() {
                            current = it.next();
                            return new IndexedEntry(current);
                        }

                        /**
                         * Removes the current entry and unindexes its grave.
                         */
                        @Override
                        public void remove() {
                            it.remove();
                            if (current != null) {
                                graveIndex.remove(current.getKey());
                            }
                        }
                    };
                }
            };
        }

        /**
         * Entry whose {@code setValue} writes through {@link IndexedGraveMap#put} so the index sees it.
         */
        private final class IndexedEntry implements Entry<UUID, Grave> {

            /**
             * The backing map's entry.
             */
            private final Entry<UUID, Grave> delegateEntry;

            /**
             * Wraps a backing entry.
             *
             * @param delegateEntry the backing map's entry
             */
            IndexedEntry(@NotNull Entry<UUID, Grave> delegateEntry) {
                this.delegateEntry = delegateEntry;
            }

            @Override
            public UUID getKey() {
                return delegateEntry.getKey();
            }

            @Override
            public Grave getValue() {
                return delegateEntry.getValue();
            }

            /**
             * Replaces the grave through {@link IndexedGraveMap#put}, re-indexing it.
             *
             * @param value the new grave
             * @return the previous grave
             */
            @Override
            public Grave setValue(Grave value) {
                return put(getKey(), value);
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof Entry<?, ?> e
                        && Objects.equals(getKey(), e.getKey())
                        && Objects.equals(getValue(), e.getValue());
            }

            @Override
            public int hashCode() {
                return Objects.hashCode(getKey()) ^ Objects.hashCode(getValue());
            }
        }
    }
}
