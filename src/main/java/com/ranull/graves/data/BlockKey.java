package com.ranull.graves.data;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Immutable block-coordinate key: world UID plus floored block coordinates.
 * <p>
 * Used by {@code CacheManager} to index graves and grave blocks by position without holding
 * {@link org.bukkit.World} references or relying on {@link Location#equals(Object)} (which also
 * compares yaw and pitch).
 * </p>
 *
 * @param worldId the world's {@link org.bukkit.World#getUID() UID}
 * @param x       block X (floored)
 * @param y       block Y (floored)
 * @param z       block Z (floored)
 * @since 2026.4.9.3
 */
public record BlockKey(@NotNull UUID worldId, int x, int y, int z) {

    /**
     * Builds a key for the block containing {@code location}.
     *
     * @param location the location to key; may be {@code null}
     * @return the key, or {@code null} if the location or its world is {@code null}
     * @since 2026.4.9.3
     */
    public static @Nullable BlockKey of(@Nullable Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }

        return new BlockKey(location.getWorld().getUID(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /**
     * Builds a key for {@code block}.
     *
     * @param block the block to key; may be {@code null}
     * @return the key, or {@code null} if the block is {@code null}
     * @since 2026.4.9.3
     */
    public static @Nullable BlockKey of(@Nullable Block block) {
        if (block == null) {
            return null;
        }

        return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    /**
     * The chunk containing this block.
     *
     * @return the chunk key, using arithmetic-shift chunk coordinates
     * @since 2026.4.9.3
     */
    public @NotNull ChunkKey chunk() {
        return new ChunkKey(worldId, x >> 4, z >> 4);
    }
}
