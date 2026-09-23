package com.ranull.graves.manager;

import com.ranull.graves.Graves;
import com.ranull.graves.type.Grave;
import com.ranull.graves.util.StringUtil;
import com.ranull.graves.util.UUIDUtil;
import com.ranull.graves.util.YAMLUtil;
import dev.cwhead.GravesX.util.SkinTextureUtil_post_1_21_9;
import me.jay.GravesX.util.SkinSignatureUtil;
import me.jay.GravesX.util.SkinTextureUtil;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imports graves from external plugins (currently AngelChest) and converts them to GravesX {@link Grave} objects.
 * <p>
 * <b>Threading:</b> the entry points {@link #scanAngelChestAsync()} and {@link #importAngelChestAsync()} are
 * called on the main thread. They snapshot the loaded worlds (and online players) there, read and parse the
 * AngelChest files on an async thread, and place imported graves on the owning thread of each death location
 * in batches of {@value #IMPORT_BATCH_SIZE} per tick. Their futures complete on the main thread.
 * </p>
 */
public class ImportManager {

    /**
     * Main plugin instance.
     */
    private final Graves plugin;

    /**
     * Filename pattern: player_world_x_y_z.yml (supports negative coords).
     */
    private static final Pattern FILENAME_PATTERN = Pattern.compile(
            "^(.+?)_(.+?)_(-?\\d+)_(-?\\d+)_(-?\\d+)\\.ya?ml$", Pattern.CASE_INSENSITIVE);

    /**
     * Graves placed per tick during an import.
     *
     * @since 2026.4.9.3
     */
    private static final int IMPORT_BATCH_SIZE = 25;

    /**
     * Set while an import is running; at most one import runs at a time. Lives on the manager (which is
     * recreated on reload) rather than on the command, so a reload never leaves it stuck.
     *
     * @since 2026.4.9.3
     */
    private final AtomicBoolean importInFlight = new AtomicBoolean(false);

    /**
     * Creates a new importer bound to the given plugin instance.
     *
     * @param plugin the GravesX plugin instance
     */
    public ImportManager(Graves plugin) {
        this.plugin = plugin;
    }

    /**
     * Immutable view of the worlds loaded at capture time, so async code never calls {@code Server#getWorld}
     * off the main thread.
     *
     * @param byId   loaded worlds by UID
     * @param byName loaded worlds by lower-case name
     * @since 2026.4.9.3
     */
    public record WorldSnapshot(@NotNull Map<UUID, World> byId, @NotNull Map<String, World> byName) {

        /**
         * Captures the currently loaded worlds. Main-thread only.
         *
         * @param server the server
         * @return the snapshot
         * @since 2026.4.9.3
         */
        public static @NotNull WorldSnapshot capture(@NotNull Server server) {
            Map<UUID, World> byId = new HashMap<>();
            Map<String, World> byName = new HashMap<>();
            for (World world : server.getWorlds()) {
                byId.put(world.getUID(), world);
                byName.put(world.getName().toLowerCase(Locale.ROOT), world);
            }
            return new WorldSnapshot(Map.copyOf(byId), Map.copyOf(byName));
        }

        /**
         * Resolves a world by UID.
         *
         * @param id the world UID; may be {@code null}
         * @return the world, or {@code null} if it was not loaded at capture time
         * @since 2026.4.9.3
         */
        public @Nullable World resolve(@Nullable UUID id) {
            return id != null ? byId.get(id) : null;
        }

        /**
         * Resolves a world by name, case-insensitively (as {@code Server#getWorld(String)} does).
         *
         * @param name the world name; may be {@code null}
         * @return the world, or {@code null} if it was not loaded at capture time
         * @since 2026.4.9.3
         */
        public @Nullable World resolve(@Nullable String name) {
            if (name == null) {
                return null;
            }

            World world = byName.get(name);
            return world != null ? world : byName.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Block coordinates parsed from an AngelChest file.
     *
     * @param x block X
     * @param y block Y
     * @param z block Z
     * @since 2026.4.9.3
     */
    public record BlockCoords(int x, int y, int z) {}

    /**
     * One AngelChest file as seen by the dry-run scan. Parsed configuration is not retained.
     *
     * @param file                 the file
     * @param status               the scan outcome
     * @param world                the resolved world, or {@code null}
     * @param coords               the resolved coordinates, or {@code null} if incomplete
     * @param ownerName            the owner name hint, or {@code null}
     * @param ownerUUID            the owner UUID hint, or {@code null}
     * @param worldUUIDPrimary     the {@code worldid} hint, or {@code null}
     * @param worldUUIDSecondary   the {@code customblock.location.worldid} hint, or {@code null}
     * @param worldNameFromFile    the world name from the file name, or {@code null}
     * @param worldNameFromLogfile the world name from the {@code logfile} entry, or {@code null}
     * @since 2026.4.9.3
     */
    public record AngelChestEntry(
            @NotNull File file,
            @NotNull Status status,
            @Nullable World world,
            @Nullable BlockCoords coords,
            @Nullable String ownerName,
            @Nullable UUID ownerUUID,
            @Nullable UUID worldUUIDPrimary,
            @Nullable UUID worldUUIDSecondary,
            @Nullable String worldNameFromFile,
            @Nullable String worldNameFromLogfile) {

        /**
         * Outcome of scanning one file.
         *
         * @since 2026.4.9.3
         */
        public enum Status {
            /** The file is not a loadable YAML file. */
            INVALID_YAML,
            /** The referenced world is not loaded on this server. */
            MISSING_WORLD,
            /** The world resolved but the coordinates are incomplete. */
            MISSING_COORDS,
            /** The file can be imported. */
            IMPORTABLE
        }
    }

    /**
     * Dry-run scan result.
     *
     * @param entries      one entry per scanned file
     * @param total        number of files scanned
     * @param valid        number of loadable YAML files
     * @param importable   number of files with a resolved world and complete coordinates
     * @param missingWorld number of loadable files whose world is not loaded
     * @since 2026.4.9.3
     */
    public record AngelChestScan(@NotNull List<AngelChestEntry> entries, int total, int valid, int importable, int missingWorld) {

        /**
         * Canonical constructor; stores an immutable copy of {@code entries}.
         *
         * @param entries      one entry per scanned file
         * @param total        number of files scanned
         * @param valid        number of loadable YAML files
         * @param importable   number of importable files
         * @param missingWorld number of files whose world is not loaded
         */
        public AngelChestScan {
            entries = List.copyOf(entries);
        }

        /**
         * Builds a scan from its entries, computing the counters once.
         *
         * @param entries one entry per scanned file
         * @since 2026.4.9.3
         */
        public AngelChestScan(@NotNull List<AngelChestEntry> entries) {
            this(entries, entries.size(),
                    entries.size() - count(entries, AngelChestEntry.Status.INVALID_YAML),
                    count(entries, AngelChestEntry.Status.IMPORTABLE),
                    count(entries, AngelChestEntry.Status.MISSING_WORLD));
        }

        /**
         * Number of files that are not loadable YAML.
         *
         * @return {@code total - valid}
         * @since 2026.4.9.3
         */
        public int invalid() {
            return total - valid;
        }

        /**
         * The entries whose world is not loaded, in scan order.
         *
         * @return the missing-world entries
         * @since 2026.4.9.3
         */
        public @NotNull List<AngelChestEntry> missingWorldEntries() {
            List<AngelChestEntry> out = new ArrayList<>();
            for (AngelChestEntry entry : entries) {
                if (entry.status() == AngelChestEntry.Status.MISSING_WORLD) {
                    out.add(entry);
                }
            }
            return out;
        }

        /**
         * Counts the entries with the given status.
         *
         * @param entries the entries
         * @param status  the status to count
         * @return the count
         */
        private static int count(@NotNull List<AngelChestEntry> entries, @NotNull AngelChestEntry.Status status) {
            int n = 0;
            for (AngelChestEntry entry : entries) {
                if (entry.status() == status) {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * A grave built off-thread that still needs its owning-thread parts (owner texture, inventory,
     * persistence, placement).
     *
     * @param grave     the grave
     * @param items     the items to put into the grave inventory
     * @param ownerUUID the owner UUID, or {@code null}
     * @since 2026.4.9.3
     */
    public record PendingGrave(@NotNull Grave grave, @NotNull List<ItemStack> items, @Nullable UUID ownerUUID) {}

    /**
     * Result of a completed import.
     *
     * @param total  number of graves imported
     * @param placed number of graves placed in the world
     * @since 2026.4.9.3
     */
    public record ImportReport(int total, int placed) {}

    /**
     * Dry-run scan of the AngelChest data directory. Main-thread entry point: captures the loaded worlds,
     * then reads and parses the files on an async thread. The future completes on the main thread.
     *
     * @return a future completing with the scan, or exceptionally if reading the files failed
     * @since 2026.4.9.3
     */
    public @NotNull CompletableFuture<AngelChestScan> scanAngelChestAsync() {
        WorldSnapshot worlds = WorldSnapshot.capture(plugin.getServer());
        CompletableFuture<AngelChestScan> future = new CompletableFuture<>();
        plugin.getSchedulerManager().runTaskAsynchronously(() -> {
            AngelChestScan scan;
            try {
                File[] files = listAngelChestFiles();
                scan = scan(files != null ? files : new File[0], worlds);
            } catch (Throwable t) {
                plugin.getSchedulerManager().runTask(() -> future.completeExceptionally(t));
                return;
            }
            plugin.getSchedulerManager().runTask(() -> future.complete(scan));
        });
        return future;
    }

    /**
     * Imports all AngelChest graves. Main-thread entry point. At most one import runs at a time; a
     * second call while one is in flight returns a future already completed exceptionally with
     * {@link IllegalStateException}. The future completes on the main thread.
     *
     * @return a future completing with the import report
     * @since 2026.4.9.3
     */
    public @NotNull CompletableFuture<ImportReport> importAngelChestAsync() {
        if (!importInFlight.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("An AngelChest import is already running."));
        }

        CompletableFuture<ImportReport> future = new CompletableFuture<>();
        WorldSnapshot worlds;
        Map<UUID, Player> online = new HashMap<>();
        try {
            worlds = WorldSnapshot.capture(plugin.getServer());
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                online.put(player.getUniqueId(), player);
            }
        } catch (Throwable t) {
            finishImport(future, t);
            return future;
        }

        plugin.getSchedulerManager().runTaskAsynchronously(() -> {
            List<PendingGrave> pending;
            try {
                pending = buildPending(listAngelChestFiles(), worlds);
            } catch (Throwable t) {
                finishImport(future, t);
                return;
            }

            plugin.getSchedulerManager().runTask(() -> placeInBatches(new ArrayDeque<>(pending), online, pending.size(),
                    new AtomicInteger(), new AtomicInteger(pending.size()), future));
        });
        return future;
    }

    /**
     * Whether an AngelChest import is currently running.
     *
     * @return {@code true} while an import is in flight
     * @since 2026.4.9.3
     */
    public boolean isImportInFlight() {
        return importInFlight.get();
    }

    /**
     * Renders the dry-run summary exactly as {@link #countAngelChestStatusText()} always has. The
     * "Importable" line keeps its historical meaning: loadable files whose world resolved
     * ({@code valid - missingWorld}), including files whose coordinates are incomplete.
     *
     * @param scan the scan to render
     * @return multiline human-readable summary
     * @since 2026.4.9.3
     */
    public @NotNull String statusText(@NotNull AngelChestScan scan) {
        if (scan.total() == 0) {
            return "No files found in plugins/AngelChest/angelchests. Returning as none.";
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append("AngelChest Import Scan\n");
        sb.append("  Total files: ").append(scan.total()).append('\n');
        sb.append("  Valid YAML: ").append(scan.valid()).append('\n');
        sb.append("  Importable: ").append(scan.valid() - scan.missingWorld()).append('\n');
        sb.append("  Missing world: ").append(scan.missingWorld()).append('\n');
        sb.append("  Invalid YAML: ").append(scan.invalid());
        return sb.toString();
    }

    /**
     * Renders the missing-world report exactly as {@link #listAngelChestMissingWorldText()} always has.
     *
     * @param scan the scan to render
     * @return multiline human-readable list of missing-world entries
     * @since 2026.4.9.3
     */
    public @NotNull String missingWorldText(@NotNull AngelChestScan scan) {
        if (scan.total() == 0) {
            return "No files found in plugins/AngelChest/angelchests. Returning as none.";
        }

        List<AngelChestEntry> missing = scan.missingWorldEntries();
        if (missing.isEmpty()) {
            return "AngelChest Missing-World Report\n  None — all referenced worlds are present.";
        }

        StringBuilder sb = new StringBuilder(512);
        sb.append("AngelChest Missing-World Report\n");
        sb.append("  (These graves cannot import until the referenced world exists)\n");

        for (AngelChestEntry entry : missing) {
            BlockCoords coords = entry.coords();
            sb.append("• File: ").append(entry.file().getName()).append('\n');
            sb.append("    Owner: ").append(nullOr(entry.ownerName()))
                    .append("  UUID: ").append(nullOr(entry.ownerUUID())).append('\n');
            sb.append("    World UUIDs: primary=").append(nullOr(entry.worldUUIDPrimary()))
                    .append(" secondary=").append(nullOr(entry.worldUUIDSecondary())).append('\n');
            sb.append("    World names: file=").append(nullOr(entry.worldNameFromFile()))
                    .append(" logfile=").append(nullOr(entry.worldNameFromLogfile())).append('\n');
            sb.append("    Coords: ").append(coords != null ? coords.x() : "-").append(',')
                    .append(coords != null ? coords.y() : "-").append(',')
                    .append(coords != null ? coords.z() : "-").append('\n');
        }

        return sb.toString();
    }

    /**
     * Scans AngelChest files in one pass. Pure over its inputs: file I/O and YAML parsing only, worlds
     * are read from {@code worlds}; safe to call off the main thread.
     * <p>
     * World resolution order: {@code worldid}, {@code customblock.location.worldid}, the file name's world
     * segment, then the {@code logfile} entry's second segment. Coordinates fall back from {@code x/y/z}
     * to {@code customblock.location.*} to the file name.
     * </p>
     *
     * @param files  the files to scan
     * @param worlds the worlds loaded when the scan was requested
     * @return the scan
     * @since 2026.4.9.3
     */
    public static @NotNull AngelChestScan scan(@NotNull File[] files, @NotNull WorldSnapshot worlds) {
        List<AngelChestEntry> entries = new ArrayList<>(files.length);

        for (File file : files) {
            FileConfiguration ac = loadFile(file);
            if (ac == null) {
                entries.add(new AngelChestEntry(file, AngelChestEntry.Status.INVALID_YAML,
                        null, null, null, null, null, null, null, null));
                continue;
            }

            String ownerName = parseOwnerFromFilename(file.getName());
            String[] logfileParts = logfileParts(ac);
            if ((ownerName == null || ownerName.isEmpty())
                    && logfileParts.length > 0 && logfileParts[0] != null && !logfileParts[0].isEmpty()) {
                ownerName = logfileParts[0];
            }

            World world = resolveWorldForScan(ac, file.getName(), worlds);
            BlockCoords coords = resolveCoords(ac, file.getName());

            AngelChestEntry.Status status = world == null ? AngelChestEntry.Status.MISSING_WORLD
                    : coords == null ? AngelChestEntry.Status.MISSING_COORDS
                    : AngelChestEntry.Status.IMPORTABLE;

            entries.add(new AngelChestEntry(file, status, world, coords, ownerName,
                    uuidOrNull(ac.getString("owner", null)),
                    uuidOrNull(ac.getString("worldid", null)),
                    uuidOrNull(ac.getString("customblock.location.worldid", null)),
                    parseWorldFromFilename(file.getName()),
                    logfileParts.length > 1 ? logfileParts[1] : null));
        }

        return new AngelChestScan(entries);
    }

    /**
     * Builds the graves for an import off the main thread: owner identity, death location, timing,
     * protection, experience, death cause, items and equipment. Owning-thread parts (owner texture,
     * inventory, persistence, placement) are left to {@link #placePendingGrave(PendingGrave, Map)}.
     *
     * @param files  the AngelChest files; may be {@code null}
     * @param worlds the worlds loaded when the import was requested
     * @return one pending grave per loadable file
     */
    private @NotNull List<PendingGrave> buildPending(@Nullable File[] files, @NotNull WorldSnapshot worlds) {
        List<PendingGrave> pending = new ArrayList<>();
        if (files == null) {
            return pending;
        }

        for (File file : files) {
            FileConfiguration ac = loadFile(file);
            if (ac == null) {
                continue;
            }

            Grave grave = new Grave(UUID.randomUUID());

            grave.setOwnerType(EntityType.PLAYER);
            UUID ownerUUID = uuidOrNull(ac.getString("owner", null));
            grave.setOwnerUUID(ownerUUID);

            String ownerNameFromFile = parseOwnerFromFilename(file.getName());
            if (ownerNameFromFile != null && !ownerNameFromFile.isEmpty()) {
                grave.setOwnerName(ownerNameFromFile);
            } else {
                String[] split = logfileParts(ac);
                if (split.length > 0 && split[0] != null && !split[0].isEmpty()) {
                    grave.setOwnerName(split[0]);
                }
            }

            World world = resolveWorldForScan(ac, file.getName(), worlds);
            BlockCoords coords = resolveCoords(ac, file.getName());
            if (world != null && coords != null) {
                grave.setLocationDeath(new Location(world, coords.x(), coords.y(), coords.z()));
            }

            grave.setTimeCreation(ac.getLong("created", System.currentTimeMillis()));
            if (ac.getBoolean("infinite", false)) {
                grave.setTimeAlive(-1);
            } else {
                long alive = resolveTimeAliveMillis(ac, grave);
                grave.setTimeAlive(alive);
                grave.setTimeAliveRemaining(alive);
            }

            grave.setProtection(ac.getBoolean("isProtected", false));
            long protectionTimeRemaining = ac.getLong("unlockIn", 0);
            if (protectionTimeRemaining == -1) {
                grave.setTimeProtection(-1);
            } else {
                grave.setTimeProtection(resolveProtectionMillis(ac, grave));
            }
            grave.setExperience(ac.getInt("experience", 0));

            if (ac.isConfigurationSection("deathCause")) {
                String damageCause = ac.getString("deathCause.damageCause", "VOID");
                String killer = ac.getString("deathCause.killer", "null");
                grave.setKillerName(!"null".equalsIgnoreCase(killer) ? killer : StringUtil.format(damageCause));
            }

            List<ItemStack> armor = readItemList(ac, "armorInv", true);
            List<ItemStack> storage = readItemList(ac, "storageInv", false);
            List<ItemStack> extra = readItemList(ac, "extraInv", false);
            List<ItemStack> overflow = readItemList(ac, "overflowInv", false);

            EnumMap<EquipmentSlot, ItemStack> equip = new EnumMap<>(EquipmentSlot.class);
            if (armor.size() > 0 && armor.get(0) != null) equip.put(EquipmentSlot.HEAD, armor.get(0));
            if (armor.size() > 1 && armor.get(1) != null) equip.put(EquipmentSlot.CHEST, armor.get(1));
            if (armor.size() > 2 && armor.get(2) != null) equip.put(EquipmentSlot.LEGS, armor.get(2));
            if (armor.size() > 3 && armor.get(3) != null) equip.put(EquipmentSlot.FEET, armor.get(3));
            if (!extra.isEmpty() && extra.get(0) != null) {
                equip.put(EquipmentSlot.OFF_HAND, extra.get(0));
            }
            grave.setEquipmentMap(equip);

            List<ItemStack> itemStackList = new ArrayList<>();
            itemStackList.addAll(armor);
            itemStackList.addAll(storage);
            itemStackList.addAll(extra);
            itemStackList.addAll(overflow);

            pending.add(new PendingGrave(grave, itemStackList, ownerUUID));
        }

        return pending;
    }

    /**
     * Dispatches at most {@value #IMPORT_BATCH_SIZE} placements, each to the owning thread of its death
     * location, and re-schedules itself on the next tick while graves remain. Outcomes are counted inside
     * each placement task; the last one to finish completes the import.
     *
     * @param queue     graves still to dispatch
     * @param online    players online when the import started, by UUID
     * @param total     number of graves in the import
     * @param placed    number of graves placed so far
     * @param remaining number of graves whose placement task has not finished yet
     * @param future    the import future
     */
    private void placeInBatches(@NotNull Deque<PendingGrave> queue, @NotNull Map<UUID, Player> online, int total,
                                @NotNull AtomicInteger placed, @NotNull AtomicInteger remaining,
                                @NotNull CompletableFuture<ImportReport> future) {
        try {
            if (remaining.get() == 0) {
                finishImport(future, new ImportReport(total, placed.get()));
                return;
            }

            for (int i = 0; i < IMPORT_BATCH_SIZE && !queue.isEmpty(); i++) {
                PendingGrave pendingGrave = queue.poll();
                Location loc = pendingGrave.grave().getLocationDeath();
                Runnable place = () -> {
                    try {
                        if (placePendingGrave(pendingGrave, online)) {
                            placed.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        plugin.getLogger().warning("Failed to import AngelChest grave " + pendingGrave.grave().getUUID() + ": " + t.getMessage());
                        plugin.logStackTrace(t);
                    } finally {
                        if (remaining.decrementAndGet() == 0) {
                            finishImport(future, new ImportReport(total, placed.get()));
                        }
                    }
                };

                if (loc != null && loc.getWorld() != null) {
                    plugin.getSchedulerManager().execute(loc, place);
                } else {
                    place.run();
                }
            }

            if (!queue.isEmpty()) {
                plugin.getSchedulerManager().runTask(() -> placeInBatches(queue, online, total, placed, remaining, future));
            }
        } catch (Throwable t) {
            finishImport(future, t);
        }
    }

    /**
     * Completes the import successfully on the main thread and releases the in-flight flag.
     *
     * @param future the import future
     * @param report the import report
     */
    private void finishImport(@NotNull CompletableFuture<ImportReport> future, @NotNull ImportReport report) {
        plugin.getSchedulerManager().runTask(() -> {
            importInFlight.set(false);
            future.complete(report);
        });
    }

    /**
     * Completes the import exceptionally on the main thread and releases the in-flight flag.
     *
     * @param future the import future
     * @param error  the failure
     */
    private void finishImport(@NotNull CompletableFuture<ImportReport> future, @NotNull Throwable error) {
        plugin.getSchedulerManager().runTask(() -> {
            importInFlight.set(false);
            future.completeExceptionally(error);
        });
    }

    /**
     * Finishes and places one imported grave. Runs on the owning thread of the grave's death location
     * (or the main thread when it has none): applies the owner texture and inventory, persists the grave,
     * and places it.
     *
     * @param pending the grave to finish
     * @param online  players online when the import started, by UUID
     * @return {@code true} if the grave was placed; {@code false} if it has no death location
     */
    private boolean placePendingGrave(@NotNull PendingGrave pending, @NotNull Map<UUID, Player> online) {
        Grave grave = pending.grave();
        completeGrave(pending, pending.ownerUUID() != null ? online.get(pending.ownerUUID()) : null);

        plugin.getDataManager().addGrave(grave);

        Location location = grave.getLocationDeath();
        if (location != null) {
            plugin.getGraveManager().placeGrave(location, grave);
            return true;
        }
        return false;
    }

    /**
     * Applies the owning-thread parts of an imported grave that do not persist anything: the owner's skin
     * texture and signature (when the owner is online) and the grave inventory (when there are items and a
     * death location).
     *
     * @param pending the grave to finish
     * @param owner   the online owner, or {@code null}
     */
    private void completeGrave(@NotNull PendingGrave pending, @Nullable Player owner) {
        Grave grave = pending.grave();

        if (owner != null) {
            try {
                if (plugin.getVersionManager().isPost1_21_9()) {
                    grave.setOwnerTexture(SkinTextureUtil_post_1_21_9.getTexture(owner));
                } else {
                    grave.setOwnerTexture(SkinTextureUtil.getTexture(owner));
                }

            } catch (Throwable ignored) {
            }
            try {
                grave.setOwnerTextureSignature(SkinSignatureUtil.getSignature(owner));
            } catch (Throwable ignored) {
            }
        }

        List<ItemStack> itemStackList = pending.items();
        Location location = grave.getLocationDeath();
        if (!itemStackList.isEmpty() && location != null) {
            String title = StringUtil.parseString(
                    plugin.getConfigManager().getConfigSection("gui.grave.title", grave).getString("gui.grave.title", "Grave"),
                    location, grave, plugin
            );

            Grave.StorageMode storageMode = plugin.getGraveManager()
                    .getStorageMode(plugin.getConfigManager().getConfigSection("storage.mode", grave).getString("storage.mode", "INVENTORY"));

            Inventory inventory = plugin.getGraveManager().createGraveInventory(grave, location, itemStackList, title, storageMode);
            grave.setInventory(inventory);
        }
    }

    /**
     * Counts AngelChest graves that will import successfully.
     * <p>
     * Main-thread only; performs file I/O on the calling thread.
     * </p>
     *
     * @return the number of graves that can be imported from AngelChest
     * @deprecated Deprecated as of 2026.4.9.3 and scheduled for removal in 2027.4.9.1. Use
     * {@link #scanAngelChestAsync()} and {@link AngelChestScan#importable()} instead.
     */
    @Deprecated(since = "2026.4.9.3", forRemoval = true)
    @ApiStatus.ScheduledForRemoval(inVersion = "2027.4.9.1")
    public long countAngelChestImportableOnly() {
        return scanNow().importable();
    }

    /**
     * Imports all AngelChest graves found on disk. The graves are neither persisted nor placed.
     * <p>
     * Main-thread only; performs file I/O on the calling thread.
     * </p>
     *
     * @return a list of converted {@link Grave} objects
     * @deprecated Deprecated as of 2026.4.9.3 and scheduled for removal in 2027.4.9.1. Use
     * {@link #importAngelChestAsync()} instead.
     */
    @Deprecated(since = "2026.4.9.3", forRemoval = true)
    @ApiStatus.ScheduledForRemoval(inVersion = "2027.4.9.1")
    public List<Grave> importExternalPluginAngelChest() {
        List<Grave> graveList = new ArrayList<>();
        for (PendingGrave pending : buildPending(listAngelChestFiles(), WorldSnapshot.capture(plugin.getServer()))) {
            completeGrave(pending, pending.ownerUUID() != null ? plugin.getServer().getPlayer(pending.ownerUUID()) : null);
            graveList.add(pending.grave());
        }
        return graveList;
    }

    /**
     * Dry-run scan (text): counts total/importable/missing-world/invalid-YAML AngelChest files.
     * Does NOT create graves or inventories.
     * <p>
     * Main-thread only; performs file I/O on the calling thread.
     * </p>
     *
     * @return multiline human-readable summary
     * @deprecated Deprecated as of 2026.4.9.3 and scheduled for removal in 2027.4.9.1. Use
     * {@link #scanAngelChestAsync()} and {@link #statusText(AngelChestScan)} instead.
     */
    @Deprecated(since = "2026.4.9.3", forRemoval = true)
    @ApiStatus.ScheduledForRemoval(inVersion = "2027.4.9.1")
    public String countAngelChestStatusText() {
        return statusText(scanNow());
    }

    /**
     * Dry-run scan (text): list files whose world cannot be resolved on this server,
     * including helpful hints (UUIDs/names/coords) to aid manual fixes or world restores.
     * <p>
     * Main-thread only; performs file I/O on the calling thread.
     * </p>
     *
     * @return multiline human-readable list of missing-world entries
     * @deprecated Deprecated as of 2026.4.9.3 and scheduled for removal in 2027.4.9.1. Use
     * {@link #scanAngelChestAsync()} and {@link #missingWorldText(AngelChestScan)} instead.
     */
    @Deprecated(since = "2026.4.9.3", forRemoval = true)
    @ApiStatus.ScheduledForRemoval(inVersion = "2027.4.9.1")
    public String listAngelChestMissingWorldText() {
        return missingWorldText(scanNow());
    }

    /**
     * Converts a single AngelChest YAML file into a {@link Grave}, applying fallbacks for world, coords, and
     * metadata. The grave is neither persisted nor placed.
     * <p>
     * Main-thread only; performs file I/O on the calling thread.
     * </p>
     *
     * @param file the AngelChest YAML file to convert
     * @return the converted {@link Grave}, or {@code null} if the file is invalid
     * @deprecated Deprecated as of 2026.4.9.3 and scheduled for removal in 2027.4.9.1. Use
     * {@link #importAngelChestAsync()} instead.
     */
    @Deprecated(since = "2026.4.9.3", forRemoval = true)
    @ApiStatus.ScheduledForRemoval(inVersion = "2027.4.9.1")
    public Grave convertAngelChestToGrave(File file) {
        List<PendingGrave> pending = buildPending(new File[]{file}, WorldSnapshot.capture(plugin.getServer()));
        if (pending.isEmpty()) {
            return null;
        }

        PendingGrave only = pending.get(0);
        completeGrave(only, only.ownerUUID() != null ? plugin.getServer().getPlayer(only.ownerUUID()) : null);
        return only.grave();
    }

    /**
     * Runs a scan synchronously on the calling thread, for the deprecated shims.
     *
     * @return the scan
     */
    private @NotNull AngelChestScan scanNow() {
        File[] files = listAngelChestFiles();
        return scan(files != null ? files : new File[0], WorldSnapshot.capture(plugin.getServer()));
    }

    /**
     * Renders {@code null} as {@code "-"}.
     *
     * @param o the value
     * @return the rendered value
     */
    private static String nullOr(Object o) {
        return o == null ? "-" : String.valueOf(o);
    }

    /**
     * Loads a YAML file if it has a YAML name and parses.
     *
     * @param file the file to load
     * @return the {@link FileConfiguration}, or {@code null} if missing, misnamed or unparsable
     */
    private static @Nullable FileConfiguration loadFile(@Nullable File file) {
        if (file == null || !file.exists()) return null;
        if (!YAMLUtil.isValidYAML(file)) return null;

        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(file);
            return configuration;
        } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
            return null;
        }
    }

    /**
     * Parses a UUID, tolerating {@code null}.
     *
     * @param value the value; may be {@code null}
     * @return the UUID, or {@code null} if absent or malformed
     */
    private static @Nullable UUID uuidOrNull(@Nullable String value) {
        return value != null ? UUIDUtil.getUUID(value) : null;
    }

    /**
     * Splits the {@code logfile} entry on {@code _}.
     *
     * @param ac the AngelChest configuration
     * @return the parts; empty when the entry is absent
     */
    private static @NotNull String[] logfileParts(@NotNull FileConfiguration ac) {
        String logfile = ac.getString("logfile", "");
        return logfile != null ? logfile.split("_") : new String[0];
    }

    /**
     * Resolves an AngelChest file's world from the snapshot: {@code worldid}, then
     * {@code customblock.location.worldid}, then the file name's world segment, then the {@code logfile}
     * entry's second segment.
     *
     * @param ac       the AngelChest configuration
     * @param fileName the file name
     * @param worlds   the world snapshot
     * @return the world, or {@code null} if none of the sources names a loaded world
     */
    private static @Nullable World resolveWorldForScan(@NotNull FileConfiguration ac, @NotNull String fileName,
                                                       @NotNull WorldSnapshot worlds) {
        World world = worlds.resolve(uuidOrNull(ac.getString("worldid", null)));

        if (world == null) {
            world = worlds.resolve(uuidOrNull(ac.getString("customblock.location.worldid", null)));
        }

        if (world == null) {
            world = worlds.resolve(parseWorldFromFilename(fileName));
        }

        if (world == null) {
            String[] split = logfileParts(ac);
            if (split.length > 1) {
                world = worlds.resolve(split[1]);
            }
        }

        return world;
    }

    /**
     * Resolves an AngelChest file's block coordinates: {@code x/y/z}, then {@code customblock.location.*},
     * then the file name, per axis.
     *
     * @param ac       the AngelChest configuration
     * @param fileName the file name
     * @return the coordinates, or {@code null} if any axis cannot be resolved
     */
    private static @Nullable BlockCoords resolveCoords(@NotNull FileConfiguration ac, @NotNull String fileName) {
        Integer x = ac.isInt("x") ? ac.getInt("x") : null;
        Integer y = ac.isInt("y") ? ac.getInt("y") : null;
        Integer z = ac.isInt("z") ? ac.getInt("z") : null;

        if (x == null && ac.isInt("customblock.location.x")) x = ac.getInt("customblock.location.x");
        if (y == null && ac.isInt("customblock.location.y")) y = ac.getInt("customblock.location.y");
        if (z == null && ac.isInt("customblock.location.z")) z = ac.getInt("customblock.location.z");

        if (x == null || y == null || z == null) {
            int[] coords = parseCoordsFromFilename(fileName);
            if (coords != null) {
                if (x == null) x = coords[0];
                if (y == null) y = coords[1];
                if (z == null) z = coords[2];
            }
        }

        return x != null && y != null && z != null ? new BlockCoords(x, y, z) : null;
    }

    /**
     * Reads an item list at the given path, skipping nulls and deserializing map entries.
     *
     * @param cfg           the configuration to read
     * @param path          the list path
     * @param reverseIfList whether to reverse a list of already-deserialized stacks
     * @return the items; never {@code null}
     */
    private static List<ItemStack> readItemList(FileConfiguration cfg, String path, boolean reverseIfList) {
        if (cfg == null || path == null || !cfg.contains(path)) return Collections.emptyList();

        Object raw = cfg.get(path);
        List<ItemStack> out = new ArrayList<>();

        if (raw instanceof List<?> list) {
            boolean anyStacks = false;
            for (Object o : list) {
                if (o instanceof ItemStack is) {
                    out.add(is);
                    anyStacks = true;
                }
            }
            if (anyStacks) {
                if (reverseIfList && !out.isEmpty()) Collections.reverse(out);
                return out;
            }

            for (Object o : list) {
                if (o instanceof Map<?, ?> map) {
                    try {
                        Map<String, Object> m = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : map.entrySet()) {
                            if (e.getKey() != null) m.put(String.valueOf(e.getKey()), e.getValue());
                        }
                        ItemStack is = ItemStack.deserialize(m);
                        out.add(is);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return out;
    }

    /**
     * Extracts the player name from an AngelChest filename.
     *
     * @param name the file name
     * @return the player name, or {@code null} if the name does not match the AngelChest pattern
     */
    private static String parseOwnerFromFilename(String name) {
        Matcher m = FILENAME_PATTERN.matcher(name);
        if (m.matches()) return m.group(1);
        return null;
    }

    /**
     * Extracts the world name from an AngelChest filename.
     *
     * @param name the file name
     * @return the world name, or {@code null} if the name does not match the AngelChest pattern
     */
    private static String parseWorldFromFilename(String name) {
        Matcher m = FILENAME_PATTERN.matcher(name);
        if (m.matches()) return m.group(2);
        return null;
    }

    /**
     * Extracts integer coordinates from an AngelChest filename.
     *
     * @param name the file name
     * @return [x,y,z] or null if not matched
     */
    private static int[] parseCoordsFromFilename(String name) {
        Matcher m = FILENAME_PATTERN.matcher(name);
        if (!m.matches()) return null;
        try {
            int x = Integer.parseInt(m.group(3));
            int y = Integer.parseInt(m.group(4));
            int z = Integer.parseInt(m.group(5));
            return new int[]{x, y, z};
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Lists the AngelChest data files ({@code .yml} / {@code .yaml}, not hidden).
     *
     * @return the files, or {@code null} if the AngelChest data directory does not exist
     */
    private File[] listAngelChestFiles() {
        File base = new File(plugin.getPluginsFolder(), "AngelChest");
        if (!base.exists()) return null;
        File dir = new File(base, "angelchests");
        if (!dir.exists()) return null;
        return dir.listFiles((d, name) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return (lower.endsWith(".yml") || lower.endsWith(".yaml")) && !name.startsWith(".");
        });
    }

    /**
     * Resolves the remaining alive time of an imported grave.
     *
     * @param ac    the AngelChest configuration
     * @param grave the grave being built
     * @return the alive time in milliseconds, or {@code -1} for infinite
     */
    private long resolveTimeAliveMillis(FileConfiguration ac, Grave grave) {
        if (ac.getBoolean("infinite", false)) {
            return -1L;
        }

        long secondsLeft = ac.getLong("secondsLeft", -1L);
        if (secondsLeft >= 0L) {
            return secondsLeft * 1000L;
        }

        return plugin.getConfigManager().getConfigSection("grave.time", grave).getInt("grave.time", 0) * 1000L;
    }

    /**
     * Resolves the remaining protection time of an imported grave.
     *
     * @param ac    the AngelChest configuration
     * @param grave the grave being built
     * @return the protection time in milliseconds
     */
    private long resolveProtectionMillis(FileConfiguration ac, Grave grave) {
        long secondsLeft = ac.getLong("unlockIn", -1L);
        if (secondsLeft >= 0L) {
            return secondsLeft * 1000L;
        }

        return plugin.getConfigManager().getConfigSection("grave.time", grave).getInt("grave.time", 0) * 1000L;
    }
}