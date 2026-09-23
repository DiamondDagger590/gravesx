package com.ranull.graves.manager;

import com.ranull.graves.data.BlockData;
import com.ranull.graves.data.BlockKey;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link BlockIndex}.
 */
class BlockIndexTest {

    /** World UID used by the test world. */
    private final UUID worldId = UUID.randomUUID();

    /** Mocked world; only {@code getUID} is stubbed. */
    private final World world = mockWorld(worldId);

    /**
     * Creates a mocked world.
     *
     * @param uid the world UID
     * @return the mocked world
     */
    private static World mockWorld(UUID uid) {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(uid);
        return world;
    }

    /**
     * Creates a block for {@code grave} at the given coordinates.
     *
     * @param grave the grave UUID
     * @param x     block X
     * @param y     block Y
     * @param z     block Z
     * @return the block data
     */
    private BlockData block(UUID grave, int x, int y, int z) {
        return new BlockData(new Location(world, x, y, z), grave, "AIR", "minecraft:air");
    }

    /**
     * Verifies: add is visible in both directions.
     */
    @Test
    void addIsVisibleInBothDirections() {
        BlockIndex index = new BlockIndex();
        UUID grave = UUID.randomUUID();
        BlockData data = block(grave, 1, 2, 3);

        index.add(data);

        assertEquals(List.of(data), index.forGrave(grave));
        assertSame(data, index.at(new BlockKey(worldId, 1, 2, 3)));
    }

    /**
     * Verifies: remove drops both directions and unknown is no op.
     */
    @Test
    void removeDropsBothDirectionsAndUnknownIsNoOp() {
        BlockIndex index = new BlockIndex();
        UUID grave = UUID.randomUUID();
        BlockData data = block(grave, 1, 2, 3);
        index.add(data);

        index.remove(block(grave, 9, 9, 9));
        assertEquals(List.of(data), index.forGrave(grave));

        index.remove(data);
        assertTrue(index.forGrave(grave).isEmpty());
        assertNull(index.at(new BlockKey(worldId, 1, 2, 3)));
    }

    /**
     * Verifies: overwrite at same key unlinks previous grave.
     */
    @Test
    void overwriteAtSameKeyUnlinksPreviousGrave() {
        BlockIndex index = new BlockIndex();
        UUID oldGrave = UUID.randomUUID();
        UUID newGrave = UUID.randomUUID();
        BlockData oldData = block(oldGrave, 1, 2, 3);
        BlockData newData = block(newGrave, 1, 2, 3);

        index.add(oldData);
        index.add(newData);

        assertTrue(index.forGrave(oldGrave).isEmpty());
        assertEquals(List.of(newData), index.forGrave(newGrave));
        assertSame(newData, index.at(new BlockKey(worldId, 1, 2, 3)));
    }

    /**
     * Verifies: removing overwritten block keeps newer one.
     */
    @Test
    void removingOverwrittenBlockKeepsNewerOne() {
        BlockIndex index = new BlockIndex();
        UUID grave = UUID.randomUUID();
        BlockData oldData = block(grave, 1, 2, 3);
        BlockData newData = block(grave, 1, 2, 3);

        index.add(oldData);
        index.add(newData);
        index.remove(oldData);

        assertSame(newData, index.at(new BlockKey(worldId, 1, 2, 3)));
        assertEquals(List.of(newData), index.forGrave(grave));
    }

    /**
     * Verifies: grave with two blocks and null lookups.
     */
    @Test
    void graveWithTwoBlocksAndNullLookups() {
        BlockIndex index = new BlockIndex();
        UUID grave = UUID.randomUUID();
        BlockData first = block(grave, 1, 2, 3);
        BlockData second = block(grave, 1, 3, 3);

        index.add(first);
        index.add(second);
        index.add(first); // idempotent

        assertEquals(Set.of(first, second), Set.copyOf(index.forGrave(grave)));
        assertEquals(2, index.forGrave(grave).size());
        assertTrue(index.forGrave(null).isEmpty());
        assertNull(index.at(null));
    }

    /**
     * Verifies: clear empties both directions.
     */
    @Test
    void clearEmptiesBothDirections() {
        BlockIndex index = new BlockIndex();
        UUID grave = UUID.randomUUID();
        index.add(block(grave, 1, 2, 3));

        index.clear();

        assertTrue(index.forGrave(grave).isEmpty());
        assertNull(index.at(new BlockKey(worldId, 1, 2, 3)));
    }
}
