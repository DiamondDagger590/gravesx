package com.ranull.graves.manager;

import com.ranull.graves.manager.ImportManager.AngelChestEntry;
import com.ranull.graves.manager.ImportManager.AngelChestScan;
import com.ranull.graves.manager.ImportManager.BlockCoords;
import com.ranull.graves.manager.ImportManager.WorldSnapshot;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the pure AngelChest dry-run scan and its renderers in {@link ImportManager}.
 */
class AngelChestScanTest {

    /** UID of the loaded "world". */
    private final UUID worldId = UUID.randomUUID();

    /** UID of the loaded "nether". */
    private final UUID netherId = UUID.randomUUID();

    /** The loaded "world". */
    private final World world = mockWorld(worldId, "world");

    /** The loaded "nether". */
    private final World nether = mockWorld(netherId, "nether");

    /** Snapshot built directly from the record constructor, not from a server. */
    private final WorldSnapshot worlds = new WorldSnapshot(
            Map.of(worldId, world, netherId, nether),
            Map.of("world", world, "nether", nether));

    /** Directory holding the YAML fixtures. */
    @TempDir
    Path dir;

    /**
     * Creates a mocked world.
     *
     * @param uid  the world UID
     * @param name the world name
     * @return the mocked world
     */
    private static World mockWorld(UUID uid, String name) {
        World mocked = mock(World.class);
        when(mocked.getUID()).thenReturn(uid);
        when(mocked.getName()).thenReturn(name);
        return mocked;
    }

    /**
     * Writes a fixture file.
     *
     * @param name    the file name
     * @param content the YAML content
     * @return the file
     * @throws IOException if writing fails
     */
    private File fixture(String name, String content) throws IOException {
        Path path = dir.resolve(name);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path.toFile();
    }

    /**
     * Scans a single file and returns its entry.
     *
     * @param file the file
     * @return the entry
     */
    private AngelChestEntry only(File file) {
        AngelChestScan scan = ImportManager.scan(new File[]{file}, worlds);
        assertEquals(1, scan.entries().size());
        return scan.entries().get(0);
    }

    /**
     * Verifies: empty file array.
     */
    @Test
    void emptyFileArray() {
        AngelChestScan scan = ImportManager.scan(new File[0], worlds);

        assertEquals(0, scan.total());
        assertTrue(ImportManager.statusText(scan).startsWith("No files found"));
        assertTrue(ImportManager.missingWorldText(scan).startsWith("No files found"));
    }

    /**
     * Verifies: invalid yaml is counted as invalid.
     */
    @Test
    void invalidYamlIsCountedAsInvalid() throws IOException {
        File bad = fixture("bad.yml", "x: [unclosed\n  - : :\n");

        AngelChestScan scan = ImportManager.scan(new File[]{bad}, worlds);

        assertEquals(AngelChestEntry.Status.INVALID_YAML, scan.entries().get(0).status());
        assertEquals(1, scan.total());
        assertEquals(0, scan.valid());
        assertEquals(1, scan.invalid());
    }

    /**
     * Verifies: world resolved from world id.
     */
    @Test
    void worldResolvedFromWorldId() throws IOException {
        AngelChestEntry entry = only(fixture("chest1.yml", "worldid: " + worldId + "\nx: 1\ny: 2\nz: 3\n"));

        assertEquals(AngelChestEntry.Status.IMPORTABLE, entry.status());
        assertSame(world, entry.world());
    }

    /**
     * Verifies: world resolved from custom block world id.
     */
    @Test
    void worldResolvedFromCustomBlockWorldId() throws IOException {
        AngelChestEntry entry = only(fixture("chest2.yml",
                "customblock:\n  location:\n    worldid: " + netherId + "\nx: 1\ny: 2\nz: 3\n"));

        assertEquals(AngelChestEntry.Status.IMPORTABLE, entry.status());
        assertSame(nether, entry.world());
    }

    /**
     * Verifies: world resolved from filename.
     */
    @Test
    void worldResolvedFromFilename() throws IOException {
        AngelChestEntry entry = only(fixture("Steve_nether_1_2_3.yml", "created: 1\n"));

        assertEquals(AngelChestEntry.Status.IMPORTABLE, entry.status());
        assertSame(nether, entry.world());
        assertEquals("Steve", entry.ownerName());
    }

    /**
     * Verifies: world resolved from logfile.
     */
    @Test
    void worldResolvedFromLogfile() throws IOException {
        AngelChestEntry entry = only(fixture("chest3.yml", "logfile: Alex_world_2024\nx: 1\ny: 2\nz: 3\n"));

        assertEquals(AngelChestEntry.Status.IMPORTABLE, entry.status());
        assertSame(world, entry.world());
        assertEquals("Alex", entry.ownerName());
    }

    /**
     * Verifies: world id takes precedence over later sources.
     */
    @Test
    void worldIdTakesPrecedenceOverLaterSources() throws IOException {
        AngelChestEntry entry = only(fixture("Steve_nether_1_2_3.yml", "worldid: " + worldId + "\nlogfile: Steve_nether_1\n"));

        assertSame(world, entry.world());
    }

    /**
     * Verifies: coordinates fall back in order.
     */
    @Test
    void coordinatesFallBackInOrder() throws IOException {
        AngelChestEntry direct = only(fixture("c1.yml", "worldid: " + worldId + "\nx: 1\ny: 2\nz: 3\n"
                + "customblock:\n  location:\n    x: 9\n    y: 9\n    z: 9\n"));
        assertEquals(new BlockCoords(1, 2, 3), direct.coords());

        AngelChestEntry custom = only(fixture("c2.yml", "worldid: " + worldId
                + "\ncustomblock:\n  location:\n    x: 4\n    y: 5\n    z: 6\n"));
        assertEquals(new BlockCoords(4, 5, 6), custom.coords());

        AngelChestEntry fromName = only(fixture("Steve_world_-7_8_-9.yml", "worldid: " + worldId + "\n"));
        assertEquals(new BlockCoords(-7, 8, -9), fromName.coords());

        AngelChestEntry none = only(fixture("c3.yml", "worldid: " + worldId + "\nx: 1\n"));
        assertEquals(AngelChestEntry.Status.MISSING_COORDS, none.status());
        assertNull(none.coords());
    }

    /**
     * Verifies: missing world text lists exactly missing world entries.
     */
    @Test
    void missingWorldTextListsExactlyMissingWorldEntries() throws IOException {
        UUID unknownWorld = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        File missing = fixture("Steve_gone_10_64_-5.yml", "owner: " + owner + "\nworldid: " + unknownWorld
                + "\nlogfile: Steve_gone_1\n");
        File present = fixture("chest.yml", "worldid: " + worldId + "\nx: 1\ny: 2\nz: 3\n");

        AngelChestScan scan = ImportManager.scan(new File[]{missing, present}, worlds);
        String text = ImportManager.missingWorldText(scan);

        assertEquals(List.of(scan.entries().get(0)), scan.missingWorldEntries());
        assertEquals("AngelChest Missing-World Report\n"
                + "  (These graves cannot import until the referenced world exists)\n"
                + "• File: Steve_gone_10_64_-5.yml\n"
                + "    Owner: Steve  UUID: " + owner + "\n"
                + "    World UUIDs: primary=" + unknownWorld + " secondary=-\n"
                + "    World names: file=gone logfile=gone\n"
                + "    Coords: 10,64,-5\n", text);
        assertFalse(text.contains("chest.yml"));

        AngelChestScan allPresent = ImportManager.scan(new File[]{present}, worlds);
        assertEquals("AngelChest Missing-World Report\n  None — all referenced worlds are present.",
                ImportManager.missingWorldText(allPresent));
    }

    /**
     * Verifies: missing world text renders partial coordinates per axis.
     */
    @Test
    void missingWorldTextRendersPartialCoordinatesPerAxis() throws IOException {
        File partial = fixture("partial.yml", "worldid: " + UUID.randomUUID() + "\nx: 5\ncustomblock:\n  location:\n    z: -2\n");

        AngelChestScan scan = ImportManager.scan(new File[]{partial}, worlds);
        AngelChestEntry entry = scan.entries().get(0);

        assertNull(entry.coords());
        assertEquals(5, entry.x());
        assertNull(entry.y());
        assertEquals(-2, entry.z());
        assertTrue(ImportManager.missingWorldText(scan).contains("    Coords: 5,-,-2\n"));
    }

    /**
     * Verifies: status text counts match scan.
     */
    @Test
    void statusTextCountsMatchScan() throws IOException {
        File importable = fixture("a.yml", "worldid: " + worldId + "\nx: 1\ny: 2\nz: 3\n");
        File importable2 = fixture("Steve_world_1_2_3.yml", "created: 1\n");
        File missingWorld = fixture("b.yml", "worldid: " + UUID.randomUUID() + "\n");
        File invalid = fixture("c.yml", "x: [unclosed\n  - : :\n");

        AngelChestScan scan = ImportManager.scan(new File[]{importable, importable2, missingWorld, invalid}, worlds);

        assertEquals(4, scan.total());
        assertEquals(3, scan.valid());
        assertEquals(2, scan.importable());
        assertEquals(1, scan.missingWorld());
        assertEquals(1, scan.invalid());
        assertEquals("AngelChest Import Scan\n"
                + "  Total files: 4\n"
                + "  Valid YAML: 3\n"
                + "  Importable: 2\n"
                + "  Missing world: 1\n"
                + "  Invalid YAML: 1", ImportManager.statusText(scan));
    }

    /**
     * Verifies: status text importable line keeps historical meaning.
     */
    @Test
    void statusTextImportableLineKeepsHistoricalMeaning() throws IOException {
        // The rendered "Importable" line has always counted files whose world resolved, even without
        // coordinates; the scan's importable() counter additionally requires coordinates.
        File noCoords = fixture("n.yml", "worldid: " + worldId + "\n");

        AngelChestScan scan = ImportManager.scan(new File[]{noCoords}, worlds);

        assertEquals(0, scan.importable());
        assertTrue(ImportManager.statusText(scan).contains("  Importable: 1\n"));
    }
}
