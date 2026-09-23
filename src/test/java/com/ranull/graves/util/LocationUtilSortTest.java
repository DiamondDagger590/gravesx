package com.ranull.graves.util;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link LocationUtil#sortByDistance(Location, List)}.
 */
class LocationUtilSortTest {

    /** The base world. */
    private final World world = mockWorld();

    /** A different world. */
    private final World other = mockWorld();

    /**
     * Creates a mocked world with a random UID.
     *
     * @return the mocked world
     */
    private static World mockWorld() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        return world;
    }

    /**
     * Verifies: same world nearest first.
     */
    @Test
    void sameWorldNearestFirst() {
        Location base = new Location(world, 0, 0, 0);
        Location far = new Location(world, 10, 0, 0);
        Location near = new Location(world, 1, 0, 0);
        Location mid = new Location(world, 0, 5, 0);

        List<Location> sorted = LocationUtil.sortByDistance(base, List.of(far, near, mid));

        assertEquals(List.of(near, mid, far), sorted);
    }

    /**
     * Verifies: equal distances are retained in input order.
     */
    @Test
    void equalDistancesAreRetainedInInputOrder() {
        Location base = new Location(world, 0, 0, 0);
        Location east = new Location(world, 3, 0, 0);
        Location west = new Location(world, -3, 0, 0);
        Location north = new Location(world, 0, 0, -3);

        List<Location> sorted = LocationUtil.sortByDistance(base, List.of(east, west, north));

        assertEquals(3, sorted.size());
        assertSame(east, sorted.get(0));
        assertSame(west, sorted.get(1));
        assertSame(north, sorted.get(2));
    }

    /**
     * Verifies: other world follows in input order and nulls are skipped.
     */
    @Test
    void otherWorldFollowsInInputOrderAndNullsAreSkipped() {
        Location base = new Location(world, 0, 0, 0);
        Location otherA = new Location(other, 0, 0, 0);
        Location noWorld = new Location(null, 1, 1, 1);
        Location same = new Location(world, 50, 0, 0);
        Location otherB = new Location(other, 1, 0, 0);

        List<Location> sorted = LocationUtil.sortByDistance(base, Arrays.asList(otherA, null, noWorld, same, otherB));

        assertEquals(4, sorted.size());
        assertSame(same, sorted.get(0));
        assertSame(otherA, sorted.get(1));
        assertSame(noWorld, sorted.get(2));
        assertSame(otherB, sorted.get(3));
    }

    /**
     * Verifies: base without world keeps input order.
     */
    @Test
    void baseWithoutWorldKeepsInputOrder() {
        Location base = new Location(null, 0, 0, 0);
        Location far = new Location(world, 10, 0, 0);
        Location near = new Location(world, 1, 0, 0);

        List<Location> sorted = LocationUtil.sortByDistance(base, List.of(far, near));

        assertSame(far, sorted.get(0));
        assertSame(near, sorted.get(1));
    }
}
