package dev.voxyexclude;

import dev.voxyexclude.mixin.VoxelIngestServiceAccessor;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.Mipper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.Biome;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Applies replacement voxel data before Voxy builds its normal section meshes.
 *
 * Biome and light inputs are sampled on the client thread. Voxy ingestion runs
 * on worker threads, so the mixin only consumes this immutable snapshot there.
 * Synthetic section changes are deliberately not persisted to Voxy storage.
 */
public final class ZoneVoxelOverlay {
    private static final int SECTION_BLOCK_SIZE = 32;
    private static final int REPLACEMENT_DEPTH_BLOCKS = 30;
    // Reserved synthetic marker in Voxy's 9-bit biome field (vanilla IDs are below 256).
    public static final long SYNTHETIC_VOXEL_MARKER = 1L;
    private static final int MAX_QUEUED_SECTIONS = 500_000;

    private static final ThreadLocal<Boolean> SUPPRESS_SAVE =
            ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> CLEAR_ON_RETURN =
            ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> SUPPRESS_ACQUIRE_ENQUEUE =
            ThreadLocal.withInitial(() -> false);
    private static final ReentrantLock[] UPDATE_LOCKS = createUpdateLocks();
    private static final ThreadLocal<ArrayDeque<ReentrantLock>> HELD_UPDATE_LOCKS =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<Integer>> HELD_UPDATE_LOCK_COUNTS =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ReentrantLock> HELD_MESH_BUILD_LOCK = new ThreadLocal<>();

    private static volatile Snapshot snapshot = Snapshot.empty();

    private static volatile WorldEngine activeEngine;
    private static volatile Snapshot appliedSnapshot = Snapshot.empty();
    private static volatile Snapshot pendingTargetSnapshot;
    private static final ConcurrentLinkedQueue<SectionWork> pendingSections = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<VoxelIngestService, ConcurrentLinkedQueue<QueuedIngestWork>> VOXY_WORK_QUEUES = new ConcurrentHashMap<>();
    private static final AtomicBoolean workerScheduled = new AtomicBoolean();
    private static final Set<Long> acquiredSectionKeys = ConcurrentHashMap.newKeySet();
    private static final Set<Long> processingSectionKeys = ConcurrentHashMap.newKeySet();

    private ZoneVoxelOverlay() {}

    /** Refreshes biome/light inputs on the client thread and schedules section work in the background. */
    public static void refreshSnapshot(MinecraftClient client) {
        Snapshot previous = snapshot;
        ClientWorld world = client.world;
        Snapshot current;

        if (world == null) {
            current = Snapshot.empty();
        } else {
            final ZoneManager.EnabledSnapshot config;
            try {
                config = ZoneManager.enabledSnapshot();
            } catch (IllegalStateException unloaded) {
                current = Snapshot.empty();
                snapshot = current;
                syncEngine(client, previous, current);
                processPendingSections();
                return;
            }

            if (previous.world == world && previous.revision == config.revision()) {
                current = previous;
            } else {
                current = buildSnapshot(world, config);
            }
        }

        snapshot = current;
        syncEngine(client, previous, current);
        processPendingSections();
    }

    private static Snapshot buildSnapshot(ClientWorld world, ZoneManager.EnabledSnapshot config) {
        int bottomY = world.getBottomY();
        int topY = world.getBottomY() + world.getHeight();
        List<ZoneData> prepared = new ArrayList<>();
        for (Zone zone : config.zones()) {
            if (!zone.surfaceEnabled() || zone.surfaceY() <= bottomY) continue;

            int sampleY = Math.max(bottomY, zone.surfaceY() - 1);
            BlockPos samplePos = new BlockPos(zone.centerX(), sampleY, zone.centerZ());
            RegistryEntry<Biome> biome = world.getBiome(samplePos);
            int sky = 15;
            int block = 0;
            if (world.isChunkLoaded(zone.centerX() >> 4, zone.centerZ() >> 4)) {
                sky = world.getLightLevel(LightType.SKY, samplePos);
                block = world.getLightLevel(LightType.BLOCK, samplePos);
            }

            BlockState state = switch (zone.surfaceMaterial()) {
                case WATER -> Blocks.WATER.getDefaultState();
                case GRASS -> Blocks.GRASS_BLOCK.getDefaultState();
                case SAND -> Blocks.SAND.getDefaultState();
            };
            prepared.add(new ZoneData(zone, state, biome, (byte) (sky | (block << 4))));
        }

        return new Snapshot(world, config.revision(), bottomY, topY, List.copyOf(prepared));
    }

    private static void syncEngine(MinecraftClient client, Snapshot previous, Snapshot current) {
        WorldEngine engine = client.world == null ? null : WorldIdentifier.ofEngine(client.world);

        if (engine != activeEngine) {
            activeEngine = engine;
            clearPending();
            appliedSnapshot = Snapshot.empty();
            pendingTargetSnapshot = null;
            if (engine != null && !current.zones.isEmpty()) {
                queueZones(current.zones, current.bottomY, current.topY, false);
                pendingTargetSnapshot = current;
            } else {
                appliedSnapshot = current;
            }
            return;
        }

        if (engine == null) return;

        Snapshot targetSnapshot = pendingTargetSnapshot == null ? appliedSnapshot : pendingTargetSnapshot;
        if (current.revision == targetSnapshot.revision && current.world == targetSnapshot.world) return;

        clearPending();
        Set<ZoneData> zonesToRefresh = new LinkedHashSet<>();
        zonesToRefresh.addAll(appliedSnapshot.zones);
        if (pendingTargetSnapshot != null) zonesToRefresh.addAll(pendingTargetSnapshot.zones);
        zonesToRefresh.addAll(current.zones);

        queueZones(List.copyOf(zonesToRefresh), current.bottomY, current.topY, true);
        pendingTargetSnapshot = current;
        if (pendingSections.isEmpty()) {
            appliedSnapshot = current;
            pendingTargetSnapshot = null;
        }
    }

    private static void queueZones(List<ZoneData> zones, int bottomY, int topY, boolean restoreFromStorage) {
        List<ZoneSectionCursor> cursors = new ArrayList<>();
        for (ZoneData zoneData : zones) {
            ZoneSectionCursor cursor = new ZoneSectionCursor(zoneData.zone, bottomY, topY);
            if (!cursor.done) cursors.add(cursor);
        }

        // Round-robin zones so one large nearby zone cannot monopolize the
        // bounded queue and leave every later zone untouched.
        Set<Long> unique = new LinkedHashSet<>();
        boolean madeProgress;
        do {
            madeProgress = false;
            for (ZoneSectionCursor cursor : cursors) {
                Long key = cursor.next();
                if (key == null) continue;
                unique.add(key);
                madeProgress = true;
                if (unique.size() >= MAX_QUEUED_SECTIONS) break;
            }
        } while (madeProgress && unique.size() < MAX_QUEUED_SECTIONS);

        for (long key : unique) pendingSections.offer(new SectionWork(key, restoreFromStorage));
    }

    private static final class ZoneSectionCursor {
        private final Zone zone;
        private final int minX;
        private final int maxX;
        private final int minZ;
        private final int maxZ;
        private final int minY;
        private int x;
        private int z;
        private int y;
        private boolean done;

        private ZoneSectionCursor(Zone zone, int bottomY, int topY) {
            this.zone = zone;
            this.minX = Math.floorDiv(zone.centerX() - zone.size(), SECTION_BLOCK_SIZE);
            this.maxX = Math.floorDiv(zone.centerX() + zone.size() - 1, SECTION_BLOCK_SIZE);
            this.minZ = Math.floorDiv(zone.centerZ() - zone.size(), SECTION_BLOCK_SIZE);
            this.maxZ = Math.floorDiv(zone.centerZ() + zone.size() - 1, SECTION_BLOCK_SIZE);
            int fillBottomY = Math.max(bottomY, zone.surfaceY() - REPLACEMENT_DEPTH_BLOCKS);
            this.minY = Math.floorDiv(fillBottomY, SECTION_BLOCK_SIZE);
            // Refresh every section from the dimension's sky limit down through
            // the replacement layer. Sections above the surface must be rewritten
            // as air too, otherwise their previously ingested LOD remains visible.
            this.y = Math.floorDiv(topY - 1, SECTION_BLOCK_SIZE);
            this.x = minX;
            this.z = minZ;
            this.done = zone.surfaceY() <= bottomY || topY <= bottomY || y < minY;
        }

        private Long next() {
            while (!done) {
                int currentX = x;
                int currentY = y;
                int currentZ = z;
                advance();
                if (intersectsHorizontalSection(currentX, currentZ, zone)) {
                    return WorldEngine.getWorldSectionId(0, currentX, currentY, currentZ);
                }
            }
            return null;
        }

        private void advance() {
            x++;
            if (x > maxX) {
                x = minX;
                z++;
                if (z > maxZ) {
                    z = minZ;
                    // Prioritize sections nearest the configured surface. This
                    // makes the visible replacement appear first, even when a
                    // very large zone reaches the bounded queue limit.
                    y--;
                    if (y < minY) done = true;
                }
            }
        }
    }
    private static boolean intersectsHorizontalSection(int sectionX, int sectionZ, Zone zone) {
        double minX = sectionX * (double) SECTION_BLOCK_SIZE;
        double minZ = sectionZ * (double) SECTION_BLOCK_SIZE;
        double maxX = minX + SECTION_BLOCK_SIZE;
        double maxZ = minZ + SECTION_BLOCK_SIZE;
        if (zone.type() == Zone.Type.SQUARE) {
            return maxX > zone.centerX() - zone.size()
                    && minX < zone.centerX() + zone.size()
                    && maxZ > zone.centerZ() - zone.size()
                    && minZ < zone.centerZ() + zone.size();
        }

        double closestX = Math.max(minX, Math.min(zone.centerX(), maxX));
        double closestZ = Math.max(minZ, Math.min(zone.centerZ(), maxZ));
        double dx = closestX - zone.centerX();
        double dz = closestZ - zone.centerZ();
        return dx * dx + dz * dz <= (double) zone.size() * zone.size();
    }

    /**
     * Submits synthetic section refreshes to Voxy's ingestion service instead
     * of running them on a separate addon-owned executor. Each queued callback
     * runs on a normal Voxy service worker and still updates existing voxel data
     * through WorldUpdater.insertUpdate.
     */
    private static void processPendingSections() {
        WorldEngine engine = activeEngine;
        if (engine == null || !engine.isLive() || pendingSections.isEmpty()) return;
        if (!workerScheduled.compareAndSet(false, true)) return;
        scheduleNextSection(engine);
    }

    /**
     * Runs one overlay section as a job on Voxy's own ingestion service. The
     * service therefore applies its normal worker-pool scheduling and lifecycle
     * management, while the job retains our already-voxelized storage data.
     */
    private static void scheduleNextSection(WorldEngine engine) {
        if (activeEngine != engine || !engine.isLive()) {
            workerScheduled.set(false);
            return;
        }

        SectionWork work = pendingSections.poll();
        if (work == null) {
            if (pendingTargetSnapshot != null) {
                appliedSnapshot = pendingTargetSnapshot;
                pendingTargetSnapshot = null;
            }
            workerScheduled.set(false);
            // Close the enqueue-versus-completion race.
            if (!pendingSections.isEmpty()) processPendingSections();
            return;
        }

        boolean queued = enqueueVoxyWork(engine, () -> {
            try {
                if (activeEngine == engine && engine.isLive()) {
                    acquiredSectionKeys.remove(work.key);
                    processSection(engine, work.key, work.restoreFromStorage);
                }
            } catch (Throwable error) {
                System.err.println("[Voxy Exclusion Zones] Failed to refresh section "
                        + work.key + ": " + error);
                error.printStackTrace();
            } finally {
                scheduleNextSection(engine);
            }
        });

        if (!queued) {
            pendingSections.offer(work);
            workerScheduled.set(false);
        }
    }

    private static boolean enqueueVoxyWork(WorldEngine engine, Runnable task) {
        if (engine.instanceIn == null) return false;
        VoxelIngestService ingestService = engine.instanceIn.getIngestService();
        if (!(ingestService instanceof VoxelIngestServiceAccessor accessor)) {
            System.err.println("[Voxy Exclusion Zones] Voxy ingestion-service accessor mixin is unavailable");
            return false;
        }
        Service service = accessor.voxyexclude$getService();
        if (!service.isLive()) return false;

        engine.acquireRef();
        ConcurrentLinkedQueue<QueuedIngestWork> queue =
                VOXY_WORK_QUEUES.computeIfAbsent(ingestService, ignored -> new ConcurrentLinkedQueue<>());
        QueuedIngestWork work = new QueuedIngestWork(engine, task);
        queue.offer(work);
        try {
            service.execute();
            return true;
        } catch (Throwable error) {
            queue.remove(work);
            engine.releaseRef();
            System.err.println("[Voxy Exclusion Zones] Could not schedule work on Voxy ingestion service: " + error);
            return false;
        }
    }

    /**
     * Called by the VoxelIngestService mixin on a Voxy service worker. Returning
     * true consumes exactly one service job; false lets Voxy process its normal
     * Minecraft-chunk ingestion queue.
     */
    public static boolean runQueuedIngestWork(VoxelIngestService ingestService) {
        ConcurrentLinkedQueue<QueuedIngestWork> queue = VOXY_WORK_QUEUES.get(ingestService);
        if (queue == null) return false;
        QueuedIngestWork work = queue.poll();
        if (work == null) return false;

        try {
            work.task.run();
        } catch (Throwable error) {
            System.err.println("[Voxy Exclusion Zones] Voxy worker task failed: " + error);
            error.printStackTrace();
        } finally {
            work.engine.releaseRef();
        }
        return true;
    }

    private static void processSection(WorldEngine engine, long key, boolean restoreFromStorage) {
        // Prevent duplicate work while this section is actively being refreshed.
        // Do not remember it permanently: Voxy may load or update its real terrain
        // after an earlier acquisition, and that later acquisition needs a refresh.
        if (!processingSectionKeys.add(key)) return;
        lockSectionChain(key);
        WorldSection worldSection = null;
        try {
            SUPPRESS_ACQUIRE_ENQUEUE.set(true);
            try {
                worldSection = engine.acquire(key);
            } finally {
                SUPPRESS_ACQUIRE_ENQUEUE.remove();
            }
            long[] raw = worldSection._unsafeGetRawDataArray();
            long[] previousData = restoreFromStorage ? raw.clone() : null;

            if (restoreFromStorage) {
                int status = engine.storage.loadSection(worldSection);
                if (status < 0) status = 1;
                if (status == 1) {
                    Arrays.fill(raw, Mapper.composeMappingId((byte) 15, 0, 0));
                }
            }

            int baseX = worldSection.x << 1;
            int baseY = worldSection.y << 1;
            int baseZ = worldSection.z << 1;

            for (int subY = 0; subY < 2; subY++) {
                for (int subZ = 0; subZ < 2; subZ++) {
                    for (int subX = 0; subX < 2; subX++) {
                        VoxelizedSection voxelized = VoxelizedSection.createEmpty()
                                .setPosition(baseX + subX, baseY + subY, baseZ + subZ);

                        boolean changed = copySubSection(raw, voxelized, subX, subY, subZ, previousData);
                        changed |= applyZones(voxelized, snapshot, engine.getMapper());
                        if (!changed) continue;

                        voxelized.lvl0NonAirCount = countNonAir(voxelized.section);
                        mipSection(voxelized, engine.getMapper());
                        insertWithoutSaving(engine, voxelized);
                    }
                }
            }
        } finally {
            processingSectionKeys.remove(key);
            if (worldSection != null) worldSection.release();
            unlockUpdate();
        }
    }

    /** Queues a lightweight refresh when Voxy reacquires a level-zero section. */
    public static void onSectionAcquired(WorldEngine engine, WorldSection section) {
        if (section == null || section.lvl != 0 || SUPPRESS_ACQUIRE_ENQUEUE.get()
                || engine != activeEngine || !engine.isLive()) return;

        Snapshot current = snapshot;
        if (current.world == null || current.zones.isEmpty()) return;

        int minY = section.y << 5;
        int maxY = minY + SECTION_BLOCK_SIZE;
        for (ZoneData zoneData : current.zones) {
            Zone zone = zoneData.zone;
            int zoneBottomY = Math.max(current.bottomY, zone.surfaceY() - REPLACEMENT_DEPTH_BLOCKS);
            if (maxY > zoneBottomY && minY < current.topY
                    && !processingSectionKeys.contains(section.key)
                    && intersectsHorizontalSection(section.x, section.z, zone)) {
                if (acquiredSectionKeys.add(section.key)) {
                    pendingSections.offer(new SectionWork(section.key, false));
                    processPendingSections();
                }
                return;
            }
        }
    }

    private static boolean containsPoint(Zone zone, double x, double z) {
        double dx = x - zone.centerX();
        double dz = z - zone.centerZ();
        if (zone.type() == Zone.Type.CIRCLE) {
            return dx * dx + dz * dz <= (double) zone.size() * zone.size();
        }
        return Math.abs(dx) <= zone.size() && Math.abs(dz) <= zone.size();
    }

    private static boolean copySubSection(
            long[] raw, VoxelizedSection target, int subX, int subY, int subZ, long[] previousData
    ) {
        boolean changed = false;
        int offsetX = subX << 4;
        int offsetY = subY << 4;
        int offsetZ = subZ << 4;

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int targetIndex = (y << 8) | (z << 4) | x;
                    int sourceIndex = WorldSection.getIndex(offsetX + x, offsetY + y, offsetZ + z);
                    long value = raw[sourceIndex];
                    target.section[targetIndex] = value;
                    if (previousData != null && previousData[sourceIndex] != value) changed = true;
                }
            }
        }
        return changed;
    }

    private static boolean applyZones(VoxelizedSection section, Snapshot current, Mapper mapper) {
        if (current.world == null || current.zones.isEmpty()) return false;

        int sectionMinX = section.x << 4;
        int sectionMinY = section.y << 4;
        int sectionMinZ = section.z << 4;
        int sectionMaxY = sectionMinY + 16;
        if (sectionMaxY <= current.bottomY) return false;

        long[] replacementIds = new long[current.zones.size()];
        boolean[] intersects = new boolean[current.zones.size()];
        boolean anyIntersect = false;
        for (int i = 0; i < current.zones.size(); i++) {
            ZoneData zoneData = current.zones.get(i);
            Zone zone = zoneData.zone;
            int zoneBottomY = Math.max(current.bottomY, zone.surfaceY() - REPLACEMENT_DEPTH_BLOCKS);
            // A section intersects the overlay if it contains any part of either
            // the 30-block replacement layer or the air volume above the surface.
            int affectedMinY = Math.max(sectionMinY, current.bottomY);
            int affectedMaxY = Math.min(sectionMaxY, current.topY);
            if ((affectedMinY >= affectedMaxY
                    || affectedMaxY <= zoneBottomY)
                    || !intersectsHorizontalSection16(sectionMinX, sectionMinZ, zone)) {
                continue;
            }
            // Every voxel in the synthetic volume must use identical material,
            // light, and biome data. Preserving metadata from the original terrain
            // creates visible block-shaped shading/biome seams in the replacement.
            replacementIds[i] = Mapper.composeMappingId(zoneData.light,
                    mapper.getIdForBlockState(zoneData.state),
                    mapper.getIdForBiome(zoneData.biome)) | SYNTHETIC_VOXEL_MARKER;
            intersects[i] = true;
            anyIntersect = true;
        }
        if (!anyIntersect) return false;

        boolean changed = false;
        for (int y = 0; y < 16; y++) {
            int blockY = sectionMinY + y;
            if (blockY < current.bottomY) continue;
            for (int z = 0; z < 16; z++) {
                int blockZ = sectionMinZ + z;
                for (int x = 0; x < 16; x++) {
                    int blockX = sectionMinX + x;
                    int index = (y << 8) | (z << 4) | x;
                    long original = section.section[index];
                    long replacement = original;

                    for (int i = 0; i < current.zones.size(); i++) {
                        Zone zone = current.zones.get(i).zone;
                        int zoneBottomY = Math.max(current.bottomY, zone.surfaceY() - REPLACEMENT_DEPTH_BLOCKS);
                        if (intersects[i] && containsBlock(zone, blockX, blockZ)) {
                            if (blockY >= zone.surfaceY()) {
                                // The shader excludes all original terrain at and
                                // above the configured surface. Keep the voxel data
                                // consistent before Voxy builds mip levels: leaving
                                // terrain above this plane in the input lets an
                                // original block win Mipper's corner-priority
                                // selection, then get discarded by the shader. That
                                // can erase the replacement in coarse LODs.
                                replacement = Mapper.composeMappingId((byte) 15, 0, 0);
                            } else if (blockY >= zoneBottomY) {
                                replacement = replacementIds[i];
                            }
                        }
                    }

                    if (section.section[index] != replacement) {
                        section.section[index] = replacement;
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    private static boolean intersectsHorizontalSection16(int sectionMinX, int sectionMinZ, Zone zone) {
        double minX = sectionMinX;
        double minZ = sectionMinZ;
        double maxX = sectionMinX + 16.0;
        double maxZ = sectionMinZ + 16.0;
        if (zone.type() == Zone.Type.SQUARE) {
            return maxX > zone.centerX() - zone.size()
                    && minX < zone.centerX() + zone.size()
                    && maxZ > zone.centerZ() - zone.size()
                    && minZ < zone.centerZ() + zone.size();
        }
        double closestX = Math.max(minX, Math.min(zone.centerX(), maxX));
        double closestZ = Math.max(minZ, Math.min(zone.centerZ(), maxZ));
        double dx = closestX - zone.centerX();
        double dz = closestZ - zone.centerZ();
        return dx * dx + dz * dz <= (double) zone.size() * zone.size();
    }

    private static boolean containsBlock(Zone zone, int blockX, int blockZ) {
        double dx = blockX + 0.5 - zone.centerX();
        double dz = blockZ + 0.5 - zone.centerZ();
        if (zone.type() == Zone.Type.CIRCLE) {
            return dx * dx + dz * dz <= (double) zone.size() * zone.size();
        }
        return Math.abs(dx) <= zone.size() && Math.abs(dz) <= zone.size();
    }


    private static void mipSection(VoxelizedSection section, Mapper mapper) {
        long[] data = section.section;

        // 16^3 -> 8^3
        int out = 16 * 16 * 16;
        for (int y = 0; y < 8; y++) {
            for (int z = 0; z < 8; z++) {
                for (int x = 0; x < 8; x++) {
                    int sx = x << 1, sy = y << 1, sz = z << 1;
                    data[out++] = mipWithSyntheticPriority(
                            data[voxelIndex(sx, sy, sz)], data[voxelIndex(sx + 1, sy, sz)],
                            data[voxelIndex(sx, sy, sz + 1)], data[voxelIndex(sx + 1, sy, sz + 1)],
                            data[voxelIndex(sx, sy + 1, sz)], data[voxelIndex(sx + 1, sy + 1, sz)],
                            data[voxelIndex(sx, sy + 1, sz + 1)], data[voxelIndex(sx + 1, sy + 1, sz + 1)],
                            mapper
                    );
                }
            }
        }

        // 8^3 -> 4^3
        out = 16 * 16 * 16 + 8 * 8 * 8;
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    int sx = x << 1, sy = y << 1, sz = z << 1;
                    data[out++] = mipWithSyntheticPriority(
                            data[mip1Index(sx, sy, sz)], data[mip1Index(sx + 1, sy, sz)],
                            data[mip1Index(sx, sy, sz + 1)], data[mip1Index(sx + 1, sy, sz + 1)],
                            data[mip1Index(sx, sy + 1, sz)], data[mip1Index(sx + 1, sy + 1, sz)],
                            data[mip1Index(sx, sy + 1, sz + 1)], data[mip1Index(sx + 1, sy + 1, sz + 1)],
                            mapper
                    );
                }
            }
        }

        // 4^3 -> 2^3
        out = 16 * 16 * 16 + 8 * 8 * 8 + 4 * 4 * 4;
        for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 2; x++) {
                    int sx = x << 1, sy = y << 1, sz = z << 1;
                    data[out++] = mipWithSyntheticPriority(
                            data[mip2Index(sx, sy, sz)], data[mip2Index(sx + 1, sy, sz)],
                            data[mip2Index(sx, sy, sz + 1)], data[mip2Index(sx + 1, sy, sz + 1)],
                            data[mip2Index(sx, sy + 1, sz)], data[mip2Index(sx + 1, sy + 1, sz)],
                            data[mip2Index(sx, sy + 1, sz + 1)], data[mip2Index(sx + 1, sy + 1, sz + 1)],
                            mapper
                    );
                }
            }
        }

        // 2^3 -> 1^3
        data[16 * 16 * 16 + 8 * 8 * 8 + 4 * 4 * 4 + 2 * 2 * 2] = mipWithSyntheticPriority(
                data[mip3Index(0, 0, 0)], data[mip3Index(1, 0, 0)],
                data[mip3Index(0, 0, 1)], data[mip3Index(1, 0, 1)],
                data[mip3Index(0, 1, 0)], data[mip3Index(1, 1, 0)],
                data[mip3Index(0, 1, 1)], data[mip3Index(1, 1, 1)],
                mapper
        );
    }

    /** Preserve synthetic replacement material when any child contributes to a coarse mip cell. */
    private static long mipWithSyntheticPriority(
            long i000, long i100, long i001, long i101,
            long i010, long i110, long i011, long i111,
            Mapper mapper
    ) {
        if ((i111 & SYNTHETIC_VOXEL_MARKER) != 0) return i111;
        if ((i110 & SYNTHETIC_VOXEL_MARKER) != 0) return i110;
        if ((i011 & SYNTHETIC_VOXEL_MARKER) != 0) return i011;
        if ((i010 & SYNTHETIC_VOXEL_MARKER) != 0) return i010;
        if ((i101 & SYNTHETIC_VOXEL_MARKER) != 0) return i101;
        if ((i100 & SYNTHETIC_VOXEL_MARKER) != 0) return i100;
        if ((i001 & SYNTHETIC_VOXEL_MARKER) != 0) return i001;
        if ((i000 & SYNTHETIC_VOXEL_MARKER) != 0) return i000;
        return Mipper.mip(i000, i100, i001, i101, i010, i110, i011, i111, mapper);
    }

    private static int voxelIndex(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    private static int mip1Index(int x, int y, int z) {
        return 4096 + (y << 6) + (z << 3) + x;
    }

    private static int mip2Index(int x, int y, int z) {
        return 4096 + 512 + (y << 4) + (z << 2) + x;
    }

    private static int mip3Index(int x, int y, int z) {
        return 4096 + 512 + 64 + (y << 2) + (z << 1) + x;
    }

    private static int countNonAir(long[] data) {
        int count = 0;
        for (int i = 0; i < 16 * 16 * 16; i++) {
            if (!Mapper.isAir(data[i])) count++;
        }
        return count;
    }

    private static void insertWithoutSaving(WorldEngine engine, VoxelizedSection section) {
        SUPPRESS_SAVE.set(true);
        try {
            WorldUpdater.insertUpdate(engine, section);
        } finally {
            SUPPRESS_SAVE.remove();
            CLEAR_ON_RETURN.remove();
        }
    }

    private static ReentrantLock[] createUpdateLocks() {
        ReentrantLock[] locks = new ReentrantLock[256];
        for (int i = 0; i < locks.length; i++) locks[i] = new ReentrantLock();
        return locks;
    }

    private static int lockIndexForKey(long key) {
        long mixed = key ^ (key >>> 32);
        return (int) mixed & (UPDATE_LOCKS.length - 1);
    }

    private static ReentrantLock lockForKey(long key) {
        return UPDATE_LOCKS[lockIndexForKey(key)];
    }

    /** Serializes mesh reads with synthetic section mutations for the same Voxy section. */
    public static void lockMeshBuild(WorldSection section) {
        if (HELD_MESH_BUILD_LOCK.get() != null) {
            throw new IllegalStateException("A Voxy mesh-build lock is already held on this thread");
        }
        ReentrantLock lock = lockForKey(section.key);
        lock.lock();
        HELD_MESH_BUILD_LOCK.set(lock);
    }

    /** Releases the mesh-build lock after Voxy has submitted the generated geometry result. */
    public static void unlockMeshBuild() {
        ReentrantLock lock = HELD_MESH_BUILD_LOCK.get();
        if (lock != null) {
            HELD_MESH_BUILD_LOCK.remove();
            lock.unlock();
        }
    }

    /** Serializes our section mutations with Voxy's normal ingestion updates. */
    public static void lockUpdate(VoxelizedSection section) {
        // WorldUpdater propagates this source section into every parent LOD.
        // Lock all affected section keys in a stable order so mesh workers at
        // any LOD cannot observe a partially updated mip chain.
        boolean[] required = new boolean[UPDATE_LOCKS.length];
        for (int level = 0; level <= WorldEngine.MAX_LOD_LAYER; level++) {
            long key = WorldEngine.getWorldSectionId(
                    level,
                    section.x >> (level + 1),
                    section.y >> (level + 1),
                    section.z >> (level + 1)
            );
            required[lockIndexForKey(key)] = true;
        }
        lockRequired(required);
    }

    /** Locks a level-zero 32-block section and all of its parent LOD sections. */
    private static void lockSectionChain(long levelZeroKey) {
        int x = WorldEngine.getX(levelZeroKey);
        int y = WorldEngine.getY(levelZeroKey);
        int z = WorldEngine.getZ(levelZeroKey);
        boolean[] required = new boolean[UPDATE_LOCKS.length];
        for (int level = 0; level <= WorldEngine.MAX_LOD_LAYER; level++) {
            long key = WorldEngine.getWorldSectionId(level, x >> level, y >> level, z >> level);
            required[lockIndexForKey(key)] = true;
        }
        lockRequired(required);
    }

    private static void lockRequired(boolean[] required) {
        ArrayDeque<ReentrantLock> held = HELD_UPDATE_LOCKS.get();
        int count = 0;
        for (int i = 0; i < required.length; i++) {
            if (required[i]) {
                ReentrantLock lock = UPDATE_LOCKS[i];
                lock.lock();
                held.push(lock);
                count++;
            }
        }
        HELD_UPDATE_LOCK_COUNTS.get().push(count);
    }

    public static void unlockUpdate() {
        ArrayDeque<ReentrantLock> held = HELD_UPDATE_LOCKS.get();
        ArrayDeque<Integer> counts = HELD_UPDATE_LOCK_COUNTS.get();
        if (counts.isEmpty()) throw new IllegalStateException("No Voxy section update lock held");
        int count = counts.pop();
        for (int i = 0; i < count; i++) {
            held.pop().unlock();
        }
        if (held.isEmpty()) HELD_UPDATE_LOCKS.remove();
        if (counts.isEmpty()) HELD_UPDATE_LOCK_COUNTS.remove();
    }

    private static void clearPending() {
        pendingSections.clear();
        acquiredSectionKeys.clear();
        processingSectionKeys.clear();
        pendingTargetSnapshot = null;
    }

    /** Called at the head of Voxy's WorldUpdater.insertUpdate. */
    public static boolean beginIngest(WorldEngine engine, VoxelizedSection section) {
        if (SUPPRESS_SAVE.get()) return false;

        Snapshot current = snapshot;
        if (current.world == null || !engine.isLive() || current.zones.isEmpty()) return false;

        boolean changed = applyZones(section, current, engine.getMapper());
        if (!changed) return false;

        section.lvl0NonAirCount = countNonAir(section.section);
        mipSection(section, engine.getMapper());
        SUPPRESS_SAVE.set(true);
        CLEAR_ON_RETURN.set(true);
        return true;
    }

    public static void endIngest() {
        if (CLEAR_ON_RETURN.get()) {
            CLEAR_ON_RETURN.remove();
            SUPPRESS_SAVE.remove();
        }
    }

    public static boolean shouldSuppressSave() {
        return SUPPRESS_SAVE.get();
    }

    /**
     * Diagnostic for the central surface section of each enabled zone.
     * Counts the synthetic marker in Voxy's stored section data after
     * WorldUpdater has propagated the incoming section through all parent LODs.
     */
    public static void diagnoseParentLods(WorldEngine engine, VoxelizedSection incoming) {
        Snapshot current = snapshot;
        if (current.world == null || current.zones.isEmpty()) return;

        for (ZoneData zoneData : current.zones) {
            Zone zone = zoneData.zone;
            int targetX = Math.floorDiv(zone.centerX(), 16);
            int targetY = Math.floorDiv(zone.surfaceY() - 1, 16);
            int targetZ = Math.floorDiv(zone.centerZ(), 16);
            if (incoming.x != targetX || incoming.y != targetY || incoming.z != targetZ) continue;

            StringBuilder report = new StringBuilder();
            report.append("[Voxy Exclusion Zones] Parent LOD marker diagnostic")
                    .append(" zone=").append(zone.name())
                    .append(" material=").append(zone.surfaceMaterial())
                    .append(" surfaceY=").append(zone.surfaceY())
                    .append(" incoming=(").append(incoming.x).append(',')
                    .append(incoming.y).append(',').append(incoming.z).append(')');

            for (int lvl = 0; lvl <= 4; lvl++) {
                WorldSection stored = engine.acquireIfExists(
                        lvl,
                        incoming.x >> (lvl + 1),
                        incoming.y >> (lvl + 1),
                        incoming.z >> (lvl + 1)
                );
                if (stored == null) {
                    report.append("\n  LOD ").append(lvl).append(": section absent");
                    continue;
                }
                try {
                    int synthetic = 0;
                    int nonAir = 0;
                    for (long value : stored.data) {
                        if ((value & SYNTHETIC_VOXEL_MARKER) != 0) synthetic++;
                        if (!Mapper.isAir(value)) nonAir++;
                    }
                    report.append("\n  LOD ").append(lvl)
                            .append(": synthetic=").append(synthetic)
                            .append(", nonAir=").append(nonAir);
                } finally {
                    stored.release();
                }
            }
            System.err.println(report);
            return;
        }
    }

    private record SectionWork(long key, boolean restoreFromStorage) {}
    private record QueuedIngestWork(WorldEngine engine, Runnable task) {}
    private record ZoneData(Zone zone, BlockState state, RegistryEntry<Biome> biome, byte light) {}
    private record Snapshot(ClientWorld world, long revision, int bottomY, int topY, List<ZoneData> zones) {
        private static Snapshot empty() {
            return new Snapshot(null, -1, 0, 0, List.of());
        }
    }
}
