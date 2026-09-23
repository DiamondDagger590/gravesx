package com.ranull.graves.data;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Immutable chunk-coordinate key: world UID plus chunk X/Z.
 *
 * @param worldId the world's {@link org.bukkit.World#getUID() UID}
 * @param x       chunk X
 * @param z       chunk Z
 * @since 2026.4.9.3
 */
public record ChunkKey(@NotNull UUID worldId, int x, int z) {}
