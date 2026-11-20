package dev.voxyexclude;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.Biome;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies replacement voxel data before Voxy builds its normal section meshes.
 *
 * The game-thread snapshot keeps Minecraft world and lighting queries off
 * Voxy's ingestion workers. Synthetic section changes are deliberately not
 * persisted to Voxy's world storage.
 */
public final class ZoneVoxelOverlay {
    private static final ThreadLocal<Boolean> SUPPRESS_SAVE =
            ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> CLEAR_ON_RETURN =
            ThreadLocal.withInitial(() -> false);

    private static volatile Snapshot snapshot = Snapshot.empty();

    private ZoneVoxelOverlay() {}

    /** Refreshes biome/light inputs on the client thread. */
    public static void refreshSnapshot(MinecraftClient client) {
        ClientWorld world = client.world;
        if (world == null) {
            snapshot = Snapshot.empty();
            return;
        }

        final ZoneManager.EnabledSnapshot config;
        try {
            config = ZoneManager.enabledSnapshot();
        } catch (IllegalStateException unloaded) {
            snapshot = Snapshot.empty();
            return;
        }

        Snapshot current = snapshot;
        if (current.world == world && current.revision == config.revision()) return;

        int bottomY = world.getBottomY();
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

        snapshot = new Snapshot(world, config.revision(), bottomY, List.copyOf(prepared));
    }

    /**
     * Called at the head of Voxy's WorldUpdater.insertUpdate.
     * Returns true when this section was changed by an active replacement zone.
     */
    public static boolean beginIngest(WorldEngine engine, VoxelizedSection section) {
        if (SUPPRESS_SAVE.get()) return false;

        Snapshot current = snapshot;
        if (current.world == null || !engine.isLive() || current.zones.isEmpty()) return false;

        int sectionMinX = section.x << 4;
        int sectionMinY = section.y << 4;
        int sectionMinZ = section.z << 4;
        int sectionMaxY = sectionMinY + 16;
        if (sectionMaxY <= current.bottomY) return false;

        Mapper mapper = engine.getMapper();
        long[] replacementIds = new long[current.zones.size()];
        boolean[] zoneIntersectsSection = new boolean[current.zones.size()];

        for (int i = 0; i < current.zones.size(); i++) {
            ZoneData zoneData = current.zones.get(i);
            Zone zone = zoneData.zone;
            int fillMinY = Math.max(current.bottomY, sectionMinY);
            int fillMaxY = Math.min(sectionMaxY, zone.surfaceY());
            if (fillMinY >= fillMaxY || !intersectsHorizontal(sectionMinX, sectionMinZ, zone)) {
                continue;
            }

            replacementIds[i] = mapper.getBaseId(zoneData.light, zoneData.state, zoneData.biome);
            zoneIntersectsSection[i] = true;
        }

        boolean changed = false;
        int nonAirCount = 0;
        for (int y = 0; y < 16; y++) {
            int blockY = sectionMinY + y;
            if (blockY < current.bottomY) continue;
            for (int z = 0; z < 16; z++) {
                int blockZ = sectionMinZ + z;
                for (int x = 0; x < 16; x++) {
                    int blockX = sectionMinX + x;
                    int index = (y << 8) | (z << 4) | x;
                    long replacement = section.section[index];

                    for (int i = 0; i < current.zones.size(); i++) {
                        Zone zone = current.zones.get(i).zone;
                        if (zoneIntersectsSection[i]
                                && blockY < zone.surfaceY()
                                && containsBlock(zone, blockX, blockZ)) {
                            replacement = replacementIds[i];
                        }
                    }

                    if (section.section[index] != replacement) {
                        section.section[index] = replacement;
                        changed = true;
                    }
                    if (!Mapper.isAir(replacement)) nonAirCount++;
                }
            }
        }

        if (!changed) return false;

        section.lvl0NonAirCount = nonAirCount;
        WorldVoxilizedSectionMipper.mipSection(section, mapper);
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

    private static boolean intersectsHorizontal(int sectionMinX, int sectionMinZ, Zone zone) {
        double minX = sectionMinX;
        double minZ = sectionMinZ;
        double maxX = sectionMinX + 16.0;
        double maxZ = sectionMinZ + 16.0;
        double closestX = Math.max(minX, Math.min(zone.centerX(), maxX));
        double closestZ = Math.max(minZ, Math.min(zone.centerZ(), maxZ));
        double dx = closestX - zone.centerX();
        double dz = closestZ - zone.centerZ();
        if (zone.type() == Zone.Type.CIRCLE) {
            return dx * dx + dz * dz <= (double) zone.size() * zone.size();
        }
        return maxX > zone.centerX() - zone.size()
                && minX < zone.centerX() + zone.size()
                && maxZ > zone.centerZ() - zone.size()
                && minZ < zone.centerZ() + zone.size();
    }

    private static boolean containsBlock(Zone zone, int blockX, int blockZ) {
        double dx = blockX + 0.5 - zone.centerX();
        double dz = blockZ + 0.5 - zone.centerZ();
        if (zone.type() == Zone.Type.CIRCLE) {
            return dx * dx + dz * dz <= (double) zone.size() * zone.size();
        }
        return Math.abs(dx) <= zone.size() && Math.abs(dz) <= zone.size();
    }

    private record ZoneData(Zone zone, BlockState state, RegistryEntry<Biome> biome, byte light) {}
    private record Snapshot(ClientWorld world, long revision, int bottomY, List<ZoneData> zones) {
        private static Snapshot empty() {
            return new Snapshot(null, -1, 0, List.of());
        }
    }
}
