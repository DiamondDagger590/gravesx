package com.ranull.graves.manager;

import com.ranull.graves.data.BlockData;
import com.ranull.graves.data.BlockKey;
import com.ranull.graves.data.ChunkData;
import com.ranull.graves.type.Grave;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the grave and block indexes maintained by {@link CacheManager}.
 */
class CacheManagerIndexTest {

    /** World UID used by the test world. */
    private final UUID worldId = UUID.randomUUID();

    /** Mocked world; only {@code getUID} and {@code getName} are stubbed. */
    private final World world = mockWorld();

    /**
     * Creates the mocked world.
     *
     * @return the mocked world
     */
    private World mockWorld() {
        World mocked = mock(World.class);
        when(mocked.getUID()).thenReturn(worldId);
        when(mocked.getName()).thenReturn("world");
        return mocked;
    }

    /**
     * Creates a location in the test world.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return the location
     */
    private Location at(double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    /**
     * Creates a grave with a death location.
     *
     * @param death the death location
     * @return the grave
     */
    private static Grave grave(Location death) {
        Grave grave = new Grave(UUID.randomUUID());
        grave.setLocationDeath(death);
        return grave;
    }

    /**
     * Verifies: put indexes death block.
     */
    @Test
    void putIndexesDeathBlock() {
        CacheManager cache = new CacheManager();
        Grave grave = grave(at(10, 64, 10));

        cache.getGraveMap().put(grave.getUUID(), grave);

        assertSame(grave, cache.getGrave(at(10, 64, 10)));
        assertSame(grave, cache.getGrave(at(10.9, 64.5, 10.1)));
        assertNull(cache.getGrave(at(11, 64, 10)));
        assertNull(cache.getGrave((Location) null));
    }

    /**
     * Verifies: remove and clear unindex.
     */
    @Test
    void removeAndClearUnindex() {
        CacheManager cache = new CacheManager();
        Grave a = grave(at(0, 64, 0));
        Grave b = grave(at(5, 64, 5));
        cache.getGraveMap().put(a.getUUID(), a);
        cache.getGraveMap().put(b.getUUID(), b);

        cache.getGraveMap().remove(a.getUUID());
        assertNull(cache.getGrave(at(0, 64, 0)));
        assertSame(b, cache.getGrave(at(5, 64, 5)));

        cache.getGraveMap().clear();
        assertNull(cache.getGrave(at(5, 64, 5)));
        assertTrue(cache.getGravesAt(BlockKey.of(at(5, 64, 5))).isEmpty());
    }

    /**
     * Verifies: iterator and remove if unindex.
     */
    @Test
    void iteratorAndRemoveIfUnindex() {
        CacheManager cache = new CacheManager();
        Grave a = grave(at(0, 64, 0));
        Grave b = grave(at(5, 64, 5));
        cache.getGraveMap().put(a.getUUID(), a);
        cache.getGraveMap().put(b.getUUID(), b);

        Iterator<Grave> values = cache.getGraveMap().values().iterator();
        while (values.hasNext()) {
            if (values.next() == a) {
                values.remove();
            }
        }
        assertFalse(cache.getGraveMap().containsKey(a.getUUID()));
        assertTrue(cache.getGravesAt(BlockKey.of(at(0, 64, 0))).isEmpty());

        cache.getGraveMap().entrySet().removeIf(e -> e.getValue() == b);
        assertTrue(cache.getGraveMap().isEmpty());
        assertTrue(cache.getGravesAt(BlockKey.of(at(5, 64, 5))).isEmpty());
    }

    /**
     * Verifies: put all indexes every entry and set value reindexes.
     */
    @Test
    void putAllIndexesEveryEntryAndSetValueReindexes() {
        CacheManager cache = new CacheManager();
        Grave a = grave(at(0, 64, 0));
        Grave b = grave(at(5, 64, 5));
        Map<UUID, Grave> source = new HashMap<>();
        source.put(a.getUUID(), a);
        source.put(b.getUUID(), b);

        cache.getGraveMap().putAll(source);
        assertSame(a, cache.getGrave(at(0, 64, 0)));
        assertSame(b, cache.getGrave(at(5, 64, 5)));

        Grave replacement = new Grave(a.getUUID());
        replacement.setLocationDeath(at(20, 70, 20));
        for (Map.Entry<UUID, Grave> entry : cache.getGraveMap().entrySet()) {
            if (entry.getKey().equals(a.getUUID())) {
                assertSame(a, entry.setValue(replacement));
            }
        }

        assertNull(cache.getGrave(at(0, 64, 0)));
        assertSame(replacement, cache.getGrave(at(20, 70, 20)));
        assertSame(replacement, cache.getGraveMap().get(a.getUUID()));
    }

    /**
     * Verifies: null keys are tolerated.
     */
    @Test
    void nullKeysAreTolerated() {
        CacheManager cache = new CacheManager();

        assertDoesNotThrow(() -> {
            assertNull(cache.getGraveMap().get(null));
            assertFalse(cache.getGraveMap().containsKey(null));
            assertNull(cache.getGraveMap().remove(null));
            assertNull(cache.getGrave((UUID) null));
        });
    }

    /**
     * Verifies: reindex grave moves entry and stale hit self heals.
     */
    @Test
    void reindexGraveMovesEntryAndStaleHitSelfHeals() {
        CacheManager cache = new CacheManager();
        Grave grave = grave(at(0, 64, 0));
        cache.getGraveMap().put(grave.getUUID(), grave);

        grave.setLocationDeath(at(3, 64, 3));
        cache.reindexGrave(grave);
        assertNull(cache.getGrave(at(0, 64, 0)));
        assertSame(grave, cache.getGrave(at(3, 64, 3)));

        grave.setLocationDeath(at(7, 64, 7));
        assertNull(cache.getGrave(at(3, 64, 3))); // stale entry dropped rather than returning a moved grave
        assertTrue(cache.getGravesAt(BlockKey.of(at(3, 64, 3))).isEmpty());

        cache.reindexGrave(grave);
        assertSame(grave, cache.getGrave(at(7, 64, 7)));
    }

    /**
     * Verifies: two graves at one death block.
     */
    @Test
    void twoGravesAtOneDeathBlock() {
        CacheManager cache = new CacheManager();
        Grave first = grave(at(0, 64, 0));
        Grave second = grave(at(0.5, 64.5, 0.5));
        cache.getGraveMap().put(first.getUUID(), first);
        cache.getGraveMap().put(second.getUUID(), second);

        assertSame(first, cache.getGrave(at(0, 64, 0)));
        assertEquals(List.of(first, second), cache.getGravesAt(BlockKey.of(at(0, 64, 0))));

        cache.getGraveMap().remove(first.getUUID());
        assertSame(second, cache.getGrave(at(0, 64, 0)));
    }

    /**
     * Verifies: block index maintenance.
     */
    @Test
    void blockIndexMaintenance() {
        CacheManager cache = new CacheManager();
        UUID graveId = UUID.randomUUID();
        Location lower = at(1, 64, 1);
        Location upper = at(1, 65, 1);
        BlockData lowerData = new BlockData(lower, graveId, "AIR", "minecraft:air");
        BlockData upperData = new BlockData(upper, graveId, "AIR", "minecraft:air");

        cache.addBlockData(lowerData);
        cache.addBlockData(upperData);
        cache.addBlockData(null);
        assertEquals(2, cache.getBlockDataForGrave(graveId).size());
        assertSame(lowerData, cache.getBlockDataAt(BlockKey.of(lower)));

        cache.removeBlockData(lowerData);
        cache.removeBlockData(null);
        assertEquals(List.of(upperData), cache.getBlockDataForGrave(graveId));
        assertNull(cache.getBlockDataAt(BlockKey.of(lower)));

        ChunkData chunk = new ChunkData(lower);
        chunk.addBlockData(lowerData);
        chunk.addBlockData(upperData);
        cache.getChunkMap().put("chunk", chunk);

        cache.rebuildBlockIndex();
        cache.rebuildBlockIndex(); // idempotent
        assertEquals(2, cache.getBlockDataForGrave(graveId).size());

        // Additive: an entry that is not in the chunk map survives a rebuild.
        UUID otherGrave = UUID.randomUUID();
        BlockData elsewhere = new BlockData(at(100, 64, 100), otherGrave, "AIR", "minecraft:air");
        cache.addBlockData(elsewhere);
        cache.rebuildBlockIndex();
        assertSame(elsewhere, cache.getBlockDataAt(BlockKey.of(at(100, 64, 100))));

        cache.removeChunkBlockData(chunk);
        cache.removeChunkBlockData(null);
        assertTrue(cache.getBlockDataForGrave(graveId).isEmpty());
        assertNull(cache.getBlockDataAt(BlockKey.of(upper)));
        assertEquals(List.of(elsewhere), cache.getBlockDataForGrave(otherGrave));
    }
}
