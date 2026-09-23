package com.ranull.graves.manager;

import com.ranull.graves.data.BlockKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GraveIndex}.
 */
class GraveIndexTest {

    /** World UID shared by the keys in this test. */
    private final UUID world = UUID.randomUUID();

    /** A death block. */
    private final BlockKey keyA = new BlockKey(world, 0, 64, 0);

    /** Another death block. */
    private final BlockKey keyB = new BlockKey(world, 1, 64, 0);

    /**
     * Verifies: add then lookup.
     */
    @Test
    void addThenLookup() {
        GraveIndex index = new GraveIndex();
        UUID grave = UUID.randomUUID();

        index.add(grave, keyA);

        assertEquals(List.of(grave), index.lookup(keyA));
        assertTrue(index.lookup(keyB).isEmpty());
        assertEquals(keyA, index.keyOf(grave));
    }

    /**
     * Verifies: two graves at one key earliest first.
     */
    @Test
    void twoGravesAtOneKeyEarliestFirst() {
        GraveIndex index = new GraveIndex();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        index.add(first, keyA);
        index.add(second, keyA);
        assertEquals(List.of(first, second), index.lookup(keyA));

        index.remove(first);
        assertEquals(List.of(second), index.lookup(keyA));
    }

    /**
     * Verifies: add at new key moves grave.
     */
    @Test
    void addAtNewKeyMovesGrave() {
        GraveIndex index = new GraveIndex();
        UUID grave = UUID.randomUUID();

        index.add(grave, keyA);
        index.add(grave, keyB);

        assertTrue(index.lookup(keyA).isEmpty());
        assertEquals(List.of(grave), index.lookup(keyB));
        assertEquals(keyB, index.keyOf(grave));
    }

    /**
     * Verifies: remove unknown is no op and clear empties both directions.
     */
    @Test
    void removeUnknownIsNoOpAndClearEmptiesBothDirections() {
        GraveIndex index = new GraveIndex();
        UUID grave = UUID.randomUUID();
        index.add(grave, keyA);

        index.remove(UUID.randomUUID());
        assertEquals(List.of(grave), index.lookup(keyA));

        index.clear();
        assertTrue(index.lookup(keyA).isEmpty());
        assertNull(index.keyOf(grave));
    }

    /**
     * Verifies: lookup null is empty and results are snapshots.
     */
    @Test
    void lookupNullIsEmptyAndResultsAreSnapshots() {
        GraveIndex index = new GraveIndex();
        UUID first = UUID.randomUUID();
        index.add(first, keyA);

        assertTrue(index.lookup(null).isEmpty());

        List<UUID> snapshot = index.lookup(keyA);
        index.add(UUID.randomUUID(), keyA);
        index.remove(first);

        assertEquals(List.of(first), snapshot);
    }
}
