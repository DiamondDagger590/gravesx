package com.ranull.graves.data;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link BlockKey}, {@link ChunkKey} and {@link LocationData#toBlockKey()}.
 */
class BlockKeyTest {

    /**
     * Creates a mocked world with the given UID and name.
     *
     * @param uid  the world UID
     * @param name the world name
     * @return the mocked world
     */
    static World world(UUID uid, String name) {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(uid);
        when(world.getName()).thenReturn(name);
        return world;
    }

    /**
     * Verifies: of location floors coordinates and uses world uid.
     */
    @Test
    void ofLocationFloorsCoordinatesAndUsesWorldUid() {
        UUID uid = UUID.randomUUID();
        World world = world(uid, "world");

        BlockKey key = BlockKey.of(new Location(world, -0.5, 64.9, 10.2));

        assertEquals(new BlockKey(uid, -1, 64, 10), key);
    }

    /**
     * Verifies: of location returns null for null location or world.
     */
    @Test
    void ofLocationReturnsNullForNullLocationOrWorld() {
        assertNull(BlockKey.of((Location) null));
        assertNull(BlockKey.of(new Location(null, 1, 2, 3)));
    }

    /**
     * Verifies: of block matches of block location.
     */
    @Test
    void ofBlockMatchesOfBlockLocation() {
        UUID uid = UUID.randomUUID();
        World world = world(uid, "world");
        Location location = new Location(world, -17, 5, 33);

        // No concrete Block implementation exists in the API, so a minimal stub is required here.
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(-17);
        when(block.getY()).thenReturn(5);
        when(block.getZ()).thenReturn(33);
        when(block.getLocation()).thenReturn(location);

        assertEquals(BlockKey.of(block.getLocation()), BlockKey.of(block));
        assertNull(BlockKey.of((Block) null));
    }

    /**
     * Verifies: chunk uses arithmetic shift.
     */
    @Test
    void chunkUsesArithmeticShift() {
        UUID uid = UUID.randomUUID();

        assertEquals(new ChunkKey(uid, -1, -1), new BlockKey(uid, -1, 0, -1).chunk());
        assertEquals(new ChunkKey(uid, -2, 0), new BlockKey(uid, -17, 0, 15).chunk());
        assertEquals(new ChunkKey(uid, 1, 2), new BlockKey(uid, 16, 0, 47).chunk());
    }

    /**
     * Verifies: location data to block key matches block key of.
     */
    @Test
    void locationDataToBlockKeyMatchesBlockKeyOf() {
        UUID uid = UUID.randomUUID();
        World world = world(uid, "world");

        Location integer = new Location(world, 4, 70, -9);
        Location fractional = new Location(world, -0.25, 70.75, -8.5);

        assertEquals(BlockKey.of(integer), new LocationData(integer).toBlockKey());
        assertEquals(BlockKey.of(fractional), new LocationData(fractional).toBlockKey());
        assertEquals(uid, new LocationData(integer).getWorldUUID());
    }

    /**
     * Verifies: location data to block key is null without world.
     */
    @Test
    void locationDataToBlockKeyIsNullWithoutWorld() {
        LocationData data = new LocationData(new Location(null, 1, 2, 3));

        assertNull(data.toBlockKey());
        assertNull(data.getWorldUUID());
    }

    /**
     * Verifies: equality and hash code.
     */
    @Test
    void equalityAndHashCode() {
        UUID uid = UUID.randomUUID();
        BlockKey a = new BlockKey(uid, 1, 2, 3);
        BlockKey b = new BlockKey(uid, 1, 2, 3);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new BlockKey(UUID.randomUUID(), 1, 2, 3));
        assertEquals(new ChunkKey(uid, 1, 2), new ChunkKey(uid, 1, 2));
        assertNotEquals(new ChunkKey(uid, 1, 2), new ChunkKey(UUID.randomUUID(), 1, 2));
    }
}
