package dev.voxyexclude;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.world.BiomeColors;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;

import java.util.ArrayList;
import java.util.List;

/**
 * Textured replacement platforms for areas removed by the Voxy exclusion shader.
 *
 * The surfaces use the vanilla block atlas, animated atlas sprites, biome tints,
 * and the world's sky/block light values. Coordinates are camera-relative, as
 * required by Fabric's world-render consumer pipeline.
 */
public final class ZoneSurfaceRenderer {
    private static final double SURFACE_EPSILON = 0.002;
    private static final int CIRCLE_SEGMENTS = 64;
    private static final int MAX_TILES_PER_ZONE = 16_384;

    private static final Identifier BLOCK_ATLAS = SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE;
    private static final SpriteIdentifier WATER_SPRITE =
            new SpriteIdentifier(BLOCK_ATLAS, Identifier.of("minecraft", "block/water_still"));
    private static final SpriteIdentifier GRASS_SPRITE =
            new SpriteIdentifier(BLOCK_ATLAS, Identifier.of("minecraft", "block/grass_block_top"));
    private static final SpriteIdentifier SAND_SPRITE =
            new SpriteIdentifier(BLOCK_ATLAS, Identifier.of("minecraft", "block/sand"));

    private ZoneSurfaceRenderer() {}

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(ZoneSurfaceRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        if (context.consumers() == null || context.matrices() == null || context.worldState() == null
                || context.worldState().cameraRenderState == null
                || !context.worldState().cameraRenderState.initialized) {
            return;
        }

        List<Zone> zones = ZoneManager.enabledSnapshot().zones().stream()
                .filter(Zone::surfaceEnabled)
                .toList();
        if (zones.isEmpty()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getAtlasManager() == null) return;

        Vec3d camera = context.worldState().cameraRenderState.pos;
        MatrixStack matrices = context.matrices();
        matrices.push();
        try {
            MatrixStack.Entry matrix = matrices.peek();
            for (Zone zone : zones) {
                Sprite sprite = spriteFor(client, zone.surfaceMaterial());
                if (sprite == null) continue;

                RenderLayer layer = ZoneSurfaceLayer.get(BLOCK_ATLAS);
                VertexConsumer base = context.consumers().getBuffer(layer);
                VertexConsumer vertices = sprite.getTextureSpecificVertexConsumer(base);

                BlockPos samplePos = BlockPos.ofFloored(zone.centerX(), zone.surfaceY(), zone.centerZ());
                int color = tintFor(client.world, samplePos, zone.surfaceMaterial());
                int light = lightFor(client.world, samplePos);

                double minX = zone.centerX() - (double) zone.size();
                double maxX = zone.centerX() + (double) zone.size();
                double minZ = zone.centerZ() - (double) zone.size();
                double maxZ = zone.centerZ() + (double) zone.size();
                double y = zone.surfaceY() + SURFACE_EPSILON - camera.y;

                int tileSize = tileSize(zone);
                if (zone.type() == Zone.Type.CIRCLE) {
                    emitCircle(vertices, matrix, zone.centerX() - camera.x, y,
                            zone.centerZ() - camera.z, zone.size(), minX, maxX, minZ, maxZ,
                            tileSize, camera.x, camera.z, color, light);
                } else {
                    emitSquare(vertices, matrix, minX, maxX, minZ, maxZ, tileSize,
                            camera.x, camera.z, y, color, light);
                }
            }
        } finally {
            matrices.pop();
        }
    }

    private static Sprite spriteFor(MinecraftClient client, Zone.SurfaceMaterial material) {
        SpriteIdentifier id = switch (material) {
            case WATER -> WATER_SPRITE;
            case GRASS -> GRASS_SPRITE;
            case SAND -> SAND_SPRITE;
        };
        try {
            return client.getAtlasManager().getSprite(id);
        } catch (RuntimeException exception) {
            // A resource pack may omit or break a vanilla atlas entry. Skip this
            // surface rather than crashing the whole world renderer.
            return null;
        }
    }

    private static int tintFor(ClientWorld world, BlockPos pos, Zone.SurfaceMaterial material) {
        if (world == null || !world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            return switch (material) {
                case WATER -> withAlpha(0x3F76E4, 210);
                case GRASS -> 0xFF7FB238;
                case SAND -> 0xFFFFFFFF;
            };
        }
        return switch (material) {
            case WATER -> withAlpha(BiomeColors.getWaterColor(world, pos), 210);
            case GRASS -> BiomeColors.getGrassColor(world, pos);
            case SAND -> 0xFFFFFFFF;
        };
    }

    private static int withAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }

    private static int lightFor(ClientWorld world, BlockPos pos) {
        // Voxy can show an excluded zone well beyond Minecraft's loaded chunks.
        // In that case use full skylight; loaded areas use their actual light.
        if (world == null || !world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            return LightmapTextureManager.pack(0, 15);
        }
        int block = world.getLightLevel(LightType.BLOCK, pos);
        int sky = world.getLightLevel(LightType.SKY, pos);
        return LightmapTextureManager.pack(block, sky);
    }

    private static int tileSize(Zone zone) {
        double width = zone.size() * 2.0;
        double area = zone.type() == Zone.Type.CIRCLE
                ? Math.PI * zone.size() * (double) zone.size()
                : width * width;
        return Math.max(1, (int) Math.ceil(Math.sqrt(area / MAX_TILES_PER_ZONE)));
    }

    private static void emitSquare(VertexConsumer out, MatrixStack.Entry matrix,
                                   double minX, double maxX, double minZ, double maxZ,
                                   int tileSize, double cameraX, double cameraZ,
                                   double y, int color, int light) {
        for (double x = minX; x < maxX; x += tileSize) {
            double x1 = Math.min(x + tileSize, maxX);
            for (double z = minZ; z < maxZ; z += tileSize) {
                double z1 = Math.min(z + tileSize, maxZ);
                List<Point> tile = clockwiseRectangle(x, z, x1, z1);
                emitPolygon(out, matrix, tile, x, z, tileSize,
                        cameraX, cameraZ, y, color, light);
            }
        }
    }

    private static void emitCircle(VertexConsumer out, MatrixStack.Entry matrix,
                                   double relativeCenterX, double y, double relativeCenterZ,
                                   double radius, double minX, double maxX, double minZ, double maxZ,
                                   int tileSize, double cameraX, double cameraZ,
                                   int color, int light) {
        double centerX = relativeCenterX + cameraX;
        double centerZ = relativeCenterZ + cameraZ;
        double radiusSquared = radius * radius;
        List<Point> circle = new ArrayList<>(CIRCLE_SEGMENTS);
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double angle = -Math.PI * 2.0 * i / CIRCLE_SEGMENTS;
            circle.add(new Point(centerX + Math.cos(angle) * radius,
                    centerZ + Math.sin(angle) * radius));
        }

        for (double x = minX; x < maxX; x += tileSize) {
            double x1 = Math.min(x + tileSize, maxX);
            for (double z = minZ; z < maxZ; z += tileSize) {
                double z1 = Math.min(z + tileSize, maxZ);
                boolean allCornersInside =
                        insideCircle(x, z, centerX, centerZ, radiusSquared)
                        && insideCircle(x, z1, centerX, centerZ, radiusSquared)
                        && insideCircle(x1, z, centerX, centerZ, radiusSquared)
                        && insideCircle(x1, z1, centerX, centerZ, radiusSquared);

                List<Point> tile = clockwiseRectangle(x, z, x1, z1);
                if (!allCornersInside) {
                    double tileCenterX = (x + x1) * 0.5;
                    double tileCenterZ = (z + z1) * 0.5;
                    double reach = Math.hypot(x1 - x, z1 - z) * 0.5;
                    if (Math.hypot(tileCenterX - centerX, tileCenterZ - centerZ) > radius + reach) {
                        continue;
                    }
                    tile = clipToClockwiseConvexPolygon(tile, circle);
                    if (tile.size() < 3) continue;
                }
                emitPolygon(out, matrix, tile, x, z, tileSize,
                        cameraX, cameraZ, y, color, light);
            }
        }
    }

    private static boolean insideCircle(double x, double z, double centerX,
                                        double centerZ, double radiusSquared) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return dx * dx + dz * dz <= radiusSquared;
    }

    private static List<Point> clockwiseRectangle(double minX, double minZ,
                                                   double maxX, double maxZ) {
        return new ArrayList<>(List.of(
                new Point(minX, minZ), new Point(minX, maxZ),
                new Point(maxX, maxZ), new Point(maxX, minZ)));
    }

    /** Clips a clockwise polygon against another clockwise convex polygon. */
    private static List<Point> clipToClockwiseConvexPolygon(List<Point> subject,
                                                             List<Point> clip) {
        List<Point> output = subject;
        for (int edge = 0; edge < clip.size() && !output.isEmpty(); edge++) {
            Point a = clip.get(edge);
            Point b = clip.get((edge + 1) % clip.size());
            List<Point> input = output;
            output = new ArrayList<>();
            Point previous = input.get(input.size() - 1);
            double previousSide = side(a, b, previous);
            for (Point current : input) {
                double currentSide = side(a, b, current);
                boolean previousInside = previousSide <= 1.0E-8;
                boolean currentInside = currentSide <= 1.0E-8;
                if (currentInside != previousInside) {
                    double t = previousSide / (previousSide - currentSide);
                    output.add(new Point(
                            previous.x + (current.x - previous.x) * t,
                            previous.z + (current.z - previous.z) * t));
                }
                if (currentInside) output.add(current);
                previous = current;
                previousSide = currentSide;
            }
        }
        return output;
    }

    private static double side(Point a, Point b, Point p) {
        return (b.x - a.x) * (p.z - a.z) - (b.z - a.z) * (p.x - a.x);
    }

    private static void emitPolygon(VertexConsumer out, MatrixStack.Entry matrix,
                                    List<Point> polygon, double tileX, double tileZ,
                                    int tileSize, double cameraX, double cameraZ,
                                    double y, int color, int light) {
        Point first = polygon.get(0);
        for (int i = 1; i < polygon.size() - 1; i++) {
            Point second = polygon.get(i);
            Point third = polygon.get(i + 1);
            emitTriangle(out, matrix, first, second, third, tileX, tileZ,
                    tileSize, cameraX, cameraZ, y, color, light);
        }
    }

    private static void emitTriangle(VertexConsumer out, MatrixStack.Entry matrix,
                                     Point a, Point b, Point c, double tileX, double tileZ,
                                     int tileSize, double cameraX, double cameraZ,
                                     double y, int color, int light) {
        vertex(out, matrix, a, tileX, tileZ, tileSize, cameraX, cameraZ, y, color, light);
        vertex(out, matrix, b, tileX, tileZ, tileSize, cameraX, cameraZ, y, color, light);
        vertex(out, matrix, c, tileX, tileZ, tileSize, cameraX, cameraZ, y, color, light);
        vertex(out, matrix, c, tileX, tileZ, tileSize, cameraX, cameraZ, y, color, light);
    }

    private static void vertex(VertexConsumer out, MatrixStack.Entry matrix, Point point,
                               double tileX, double tileZ, int tileSize,
                               double cameraX, double cameraZ, double y,
                               int color, int light) {
        float u = (float) ((point.x - tileX) / tileSize);
        float v = (float) ((point.z - tileZ) / tileSize);
        out.vertex(matrix, (float) (point.x - cameraX), (float) y,
                        (float) (point.z - cameraZ))
                .color(color)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(matrix, 0.0f, 1.0f, 0.0f);
    }

    private record Point(double x, double z) {}
}
