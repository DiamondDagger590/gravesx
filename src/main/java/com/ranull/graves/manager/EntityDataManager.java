package com.ranull.graves.manager;

import com.ranull.graves.Graves;
import com.ranull.graves.data.BlockKey;
import com.ranull.graves.data.ChunkData;
import com.ranull.graves.data.ChunkKey;
import com.ranull.graves.data.EntityData;
import com.ranull.graves.type.Grave;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages entity data and interactions within the Graves plugin.
 */
public class EntityDataManager {
    /**
     * The main plugin instance associated with Graves.
     * <p>
     * This {@link Graves} instance represents the core plugin that this Graves is part of. It provides access
     * to the plugin's functionality, configuration, and other services.
     * </p>
     */
    private final Graves plugin;

    /**
     * Ticks after which an unfinished entity resolution completes with whatever was found.
     */
    private static final long RESOLVE_TIMEOUT_TICKS = 100L;

    private static Method SERVER_GET_ENTITY;

    static {
        try {
            SERVER_GET_ENTITY = org.bukkit.Server.class.getMethod("getEntity", UUID.class);
        } catch (Throwable ignored) { /* not available */ }
    }

    /**
     * Initializes the EntityDataManager with the specified plugin instance.
     *
     * @param plugin the Graves plugin instance.
     */
    public EntityDataManager(Graves plugin) {
        this.plugin = plugin;
    }

    /**
     * Creates entity data for a specified entity and grave.
     *
     * @param entity the entity for which to create the data.
     * @param grave  the grave associated with the entity.
     * @param type   the type of entity data.
     */
    public void createEntityData(Entity entity, Grave grave, EntityData.Type type) {
        createEntityData(entity.getLocation(), entity.getUniqueId(), grave.getUUID(), type);
    }

    /**
     * Creates entity data for a specified location, entity UUID, grave UUID, and entity data type.
     *
     * @param location   the location of the entity.
     * @param entityUUID the UUID of the entity.
     * @param graveUUID  the UUID of the grave.
     * @param type       the type of entity data.
     */
    public void createEntityData(Location location, UUID entityUUID, UUID graveUUID, EntityData.Type type) {
        EntityData entityData = new EntityData(location.clone(), entityUUID, graveUUID, type);

        plugin.getDataManager().addEntityData(entityData);

        if (plugin.getIntegrationManager().hasMultiPaper()) {
            plugin.getIntegrationManager().getMultiPaper().notifyEntityCreation(entityData);
        }
    }

    /**
     * Retrieves entity data for a specified location and entity UUID.
     *
     * @param location the location of the entity.
     * @param uuid     the UUID of the entity.
     * @return the entity data, or null if not found.
     */
    public EntityData getEntityData(Location location, UUID uuid) {
        if (plugin.getDataManager().hasChunkData(location)) {
            ChunkData chunkData = plugin.getDataManager().getChunkData(location);

            if (chunkData.getEntityDataMap().containsKey(uuid)) {
                return chunkData.getEntityDataMap().get(uuid);
            }
        }

        return null;
    }

    /**
     * Retrieves a grave for a specified location and entity UUID.
     *
     * @param location the location of the entity.
     * @param uuid     the UUID of the entity.
     * @return the grave, or null if not found.
     */
    public Grave getGrave(Location location, UUID uuid) {
        EntityData entityData = getEntityData(location, uuid);

        return entityData != null && plugin.getCacheManager().getGraveMap()
                .containsKey(entityData.getUUIDGrave())
                ? plugin.getCacheManager().getGraveMap().get(entityData.getUUIDGrave()) : null;
    }

    /**
     * Retrieves a grave for a specified entity.
     *
     * @param entity the entity for which to retrieve the grave.
     * @return the grave, or null if not found.
     */
    public Grave getGrave(Entity entity) {
        return getGrave(entity.getLocation(), entity.getUniqueId());
    }

    /**
     * Removes entity data for a specified entity data.
     *
     * @param entityData the entity data to remove.
     */
    public void removeEntityData(EntityData entityData) {
        removeEntityData(Collections.singletonList(entityData));
    }

    /**
     * Retrieves a list of loaded entity data associated with a specified grave.
     *
     * @param grave the grave for which to retrieve the loaded entity data.
     * @return the list of loaded entity data.
     */
    public List<EntityData> getLoadedEntityDataList(Grave grave) {
        List<EntityData> entityDataList = new ArrayList<>();

        for (Map.Entry<String, ChunkData> chunkDataEntry : plugin.getCacheManager().getChunkMap().entrySet()) {
            ChunkData chunkData = chunkDataEntry.getValue();

            if (chunkData.isLoaded()) {
                for (EntityData entityData : new ArrayList<>(chunkData.getEntityDataMap().values())) {
                    if (entityData != null && grave.getUUID().equals(entityData.getUUIDGrave())) {
                        entityDataList.add(entityData);
                    }
                }
            }
        }

        return entityDataList;
    }

    /**
     * Resolves live entities for the given entity data without blocking the calling thread.
     * <p>
     * Loaded entities are resolved synchronously via {@code Server#getEntity(UUID)}. Entities whose
     * chunk is <em>unloaded</em> are resolved after that chunk is loaded via
     * {@link dev.cwhead.GravesX.manager.ChunkManager#ensureLoadedAndExecute}; those loads are grouped so each
     * chunk is loaded once. An entity whose chunk is loaded but which {@code getEntity} cannot find no longer
     * exists and is omitted.
     * </p>
     * <p>
     * <b>Completion thread:</b> the future always completes on the owning thread of {@code anchor}
     * (the primary thread, or {@code anchor}'s region thread on Folia). When everything resolves
     * synchronously and the caller already owns that thread, the returned future is complete and
     * continuations run inline. Continuations may therefore touch world state at {@code anchor};
     * mutations to entities elsewhere must still be dispatched with {@code executeRegion}.
     * </p>
     * <p>
     * If a chunk load never reports back (Folia region that does not tick, failed async load), the
     * future completes after {@value #RESOLVE_TIMEOUT_TICKS} ticks with the entities found so far.
     * </p>
     *
     * @param entityDataList entity data to resolve; {@code null} entries are skipped
     * @param anchor         location whose owning thread the future completes on; typically the grave's death location
     * @return a future completing with the entity data &rarr; entity map for every entity that still exists
     * @since 2026.4.9.3
     */
    public @NotNull CompletableFuture<Map<EntityData, Entity>> resolveEntities(@NotNull Collection<EntityData> entityDataList,
                                                                               @Nullable Location anchor) {
        Map<EntityData, Entity> resolved = new ConcurrentHashMap<>();
        Map<ChunkKey, List<EntityData>> pendingByChunk = new HashMap<>();

        for (EntityData entityData : entityDataList) {
            if (entityData == null || entityData.getUUIDEntity() == null) {
                continue;
            }

            Entity found = fastGetEntity(entityData.getUUIDEntity());
            if (found != null) {
                resolved.put(entityData, found);
                continue;
            }

            Location location = entityData.getLocation();
            BlockKey key = BlockKey.of(location);
            if (key == null) {
                continue;
            }

            ChunkKey chunk = key.chunk();
            if (location.getWorld().isChunkLoaded(chunk.x(), chunk.z())) {
                continue; // loaded and not found => gone
            }

            pendingByChunk.computeIfAbsent(chunk, k -> new ArrayList<>()).add(entityData);
        }

        CompletableFuture<Map<EntityData, Entity>> future = new CompletableFuture<>();

        if (pendingByChunk.isEmpty()) {
            completeOnOwningThread(anchor, future, resolved);
            return future;
        }

        AtomicInteger remaining = new AtomicInteger(pendingByChunk.size());
        Runnable onChunkDone = () -> {
            if (remaining.decrementAndGet() == 0) {
                completeOnOwningThread(anchor, future, resolved);
            }
        };

        for (Map.Entry<ChunkKey, List<EntityData>> entry : pendingByChunk.entrySet()) {
            List<EntityData> group = entry.getValue();
            Location groupAnchor = group.get(0).getLocation();
            ChunkKey chunk = entry.getKey();

            Runnable scanGroup = () -> {
                try {
                    Map<UUID, Entity> byId = new HashMap<>();
                    for (Entity entity : groupAnchor.getWorld().getChunkAt(chunk.x(), chunk.z()).getEntities()) {
                        byId.put(entity.getUniqueId(), entity);
                    }

                    for (EntityData entityData : group) {
                        Entity entity = byId.get(entityData.getUUIDEntity());
                        if (entity == null) {
                            entity = fastGetEntity(entityData.getUUIDEntity());
                        }

                        if (entity != null) {
                            resolved.put(entityData, entity);
                        }
                    }
                } catch (Throwable t) {
                    plugin.getLogger().severe(t.getMessage());
                    plugin.logStackTrace(t);
                } finally {
                    onChunkDone.run();
                }
            };

            boolean scheduled;
            try {
                scheduled = plugin.getChunkManager().ensureLoadedAndExecute(groupAnchor, groupAnchor, false, false, scanGroup);
            } catch (Throwable t) {
                plugin.getLogger().severe("Failed to schedule entity resolution for chunk " + chunk + ": " + t.getMessage());
                plugin.logStackTrace(t);
                onChunkDone.run();
                continue;
            }

            if (!scheduled) {
                onChunkDone.run(); // Folia without an async chunk API: nothing more we can do
            }
        }

        // A load that never reports back must not strand the continuation.
        if (anchor != null && anchor.getWorld() != null) {
            plugin.getSchedulerManager().runTaskLater(anchor, () -> future.complete(resolved), RESOLVE_TIMEOUT_TICKS);
        } else {
            plugin.getSchedulerManager().runTaskLater(() -> future.complete(resolved), RESOLVE_TIMEOUT_TICKS);
        }

        return future;
    }

    /**
     * Completes {@code future} on the owning thread of {@code anchor}: inline if already there, else via the
     * region-aware scheduler. A {@code null} anchor (or one without a world) completes inline.
     *
     * @param anchor the location whose owning thread should complete the future; may be {@code null}
     * @param future the future to complete
     * @param value  the value to complete it with
     */
    private void completeOnOwningThread(@Nullable Location anchor,
                                        @NotNull CompletableFuture<Map<EntityData, Entity>> future,
                                        @NotNull Map<EntityData, Entity> value) {
        if (anchor == null || anchor.getWorld() == null || plugin.getSchedulerManager().isRegionThread(anchor)) {
            future.complete(value);
        } else {
            plugin.getSchedulerManager().execute(anchor, () -> future.complete(value));
        }
    }

    /**
     * Resolves the currently loaded entities for the given entity data.
     * <p>
     * <b>API note:</b> as of 2026.4.9.3 this never blocks and never loads chunks; it resolves loaded entities
     * only. Use {@link #resolveEntities(Collection, Location)} when entities in unloaded chunks matter.
     * </p>
     *
     * @param entityDataList the list of entity data to map.
     * @return the map of entity data and entities.
     */
    public Map<EntityData, Entity> getEntityDataMap(List<EntityData> entityDataList) {
        Map<EntityData, Entity> entityDataMap = new HashMap<>();

        for (EntityData entityData : entityDataList) {
            if (entityData == null || entityData.getUUIDEntity() == null) {
                continue;
            }

            Entity found = fastGetEntity(entityData.getUUIDEntity());
            if (found != null) {
                entityDataMap.put(entityData, found);
            }
        }

        return entityDataMap;
    }

    /**
     * Removes a list of entity data.
     * <p>
     * Resolves the entities without blocking (see {@link #resolveEntities(Collection, Location)}) and removes the
     * records of every entity that still exists once resolution completes.
     * </p>
     *
     * @param entityDataList the list of entity data to remove.
     */
    public void removeEntityData(List<EntityData> entityDataList) {
        Location anchor = entityDataList.stream()
                .filter(Objects::nonNull)
                .map(EntityData::getLocation)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        resolveEntities(entityDataList, anchor).thenAccept(map ->
                plugin.getDataManager().removeEntityData(new ArrayList<>(map.keySet())));
    }

    /**
     * Looks up a loaded entity by UUID via {@code Server#getEntity(UUID)}, which is present on every supported
     * server ({@code api-version} 1.13+) and finds every loaded entity.
     *
     * @param uuid the entity UUID
     * @return the entity, or {@code null} if it is not loaded or the lookup is unavailable
     */
    private Entity fastGetEntity(UUID uuid) {
        try {
            if (SERVER_GET_ENTITY != null) {
                Object e = SERVER_GET_ENTITY.invoke(plugin.getServer(), uuid);
                if (e instanceof Entity) return (Entity) e;
            }
        } catch (Throwable ignored) { /* continue */ }

        return null;
    }
}
