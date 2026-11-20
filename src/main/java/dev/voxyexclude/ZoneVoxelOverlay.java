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
 * The synthetic data is deliberately transient: WorldEngineMixin adds Voxy's
 * UPDATE_TYPE_DONT_SAVE flag while these updates are being committed.
 */
public final class ZoneVoxelOverlay {
    private static final ThreadLocal<Boolean> SUPPRESS_SAVE =
            ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> CLEAR_ON_RETURN =
            ThreadLocal.withInitial(() -> false);

    private ZoneVoxelOverlay() {}

    /**
     * Called at the head of Voxy's WorldUpdater.insertUpdate.
     * Returns true when this section was changed by an active replacement zone.
     */
    public static boolean beginIngest(WorldEngine engine, VoxelizedSection section) {
        if (SUPPRESS_SAVE.get()) return false;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null || !engine.isLive()) return false;

        List<Zone> zones = new ArrayList<>();
        for (Zone zone : ZoneManager.enabledSnapshot().zones()) {
            if (zone.surfaceEnabled() && zone.surfaceY() > world.getBottomY()) {
                zones.add(zone);
            }
        }
        if (zones.isEmpty()) return false;

        int sectionMinX = section.x << 4;
        int sectionMinY = section.y << 4;
        int sectionMinZ = section.z << 4;
        int sectionMaxY = sectionMinY + 16;
        int worldBottomY = world.getBottomY();
        if (sectionMaxY <= worldBottomY) return false;

        Mapper mapper = engine.getMapper();
        long[] replacementIds = new long[zones.size()];
        boolean[] zoneIntersectsSection = new boolean[zones.size()];

        for (int i = 0; i < zones.size(); i++) {
            Zone zone = zones.get(i);
            int fillMinY = Math.max(worldBottomY, sectionMinY);
            int fillMaxY = Math.min(sectionMaxY, zone.surfaceY());
            if (fillMinY >= fillMaxY || !intersectsHorizontal(sectionMinX, sectionMinZ, zone)) {
                continue;
            }

            int sampleX = sectionMinX + 8;
            int sampleZ = sectionMinZ + 8;
            int sampleY = Math.max(fillMinY, Math.min(fillMaxY - 1, sectionMinY + 8));
            BlockPos samplePos = new BlockPos(sampleX, sampleY, sampleZ);
            RegistryEntry<Biome> biome = world.getBiome(samplePos);
            int sky = 15;
            int block = 0;
            if (world.isChunkLoaded(sampleX >> 4, sampleZ >> 4)) {
                sky = world.getLightLevel(LightType.SKY, samplePos);
                block = world.getLightLevel(LightType.BLOCK, samplePos);
            }

            BlockState state = switch (zone.surfaceMaterial()) {
                case WATER -> Blocks.WATER.getDefaultState();
                case GRASS -> Blocks.GRASS_BLOCK.getDefaultState();
                case SAND -> Blocks.SAND.getDefaultState();
            };
            replacementIds[i] = mapper.getBaseId((byte) (sky | (block << 4)), state, biome);
            zoneIntersectsSection[i] = true;
        }

        boolean changed = false;
        int nonAirCount = 0;
        for (int y = 0; y < 16; y++) {
            int blockY = sectionMinY + y;
            if (blockY < worldBottomY) continue;
            for (int z = 0; z < 16; z++) {
                int blockZ = sectionMinZ + z;
                for (int x = 0; x < 16; x++) {
                    int blockX = sectionMinX + x;
                    int index = (y << 8) | (z << 4) | x;
                    long replacement = section.section[index];

                    for (int i = 0; i < zones.size(); i++) {
                        if (zoneIntersectsSection[i]
                                && blockY < zones.get(i).surfaceY()
                                && containsBlock(zones.get(i), blockX, blockZ)) {
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
}
