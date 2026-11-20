package dev.voxyexclude;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;

/**
 * Draws flat replacement platforms over the areas removed by the Voxy fragment
 * exclusion shader. Geometry is camera-relative and submitted through the
 * vanilla world-render consumer pipeline so normal world depth testing applies.
 */
public final class ZoneSurfaceRenderer {
    private static final int CIRCLE_SEGMENTS = 64;
    private static final float SURFACE_EPSILON = 0.002f;
    private static final RenderLayer DEBUG_QUADS_LAYER = resolveDebugQuadsLayer();

    private ZoneSurfaceRenderer() {}

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(ZoneSurfaceRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        if (context.consumers() == null || context.matrices() == null) {
            return;
        }

        var zones = ZoneManager.enabledSnapshot().zones().stream()
                .filter(Zone::surfaceEnabled)
                .toList();
        if (zones.isEmpty()) {
            return;
        }

        Vec3d camera = context.worldState().cameraRenderState.pos;
        MatrixStack matrices = context.matrices();
        matrices.push();

        try {
            VertexConsumer vertices = context.consumers().getBuffer(DEBUG_QUADS_LAYER);
            MatrixStack.Entry matrix = matrices.peek();

            for (Zone zone : zones) {
                int color = colorFor(zone.surfaceMaterial());
                float y = (float) (zone.surfaceY() + SURFACE_EPSILON - camera.y);
                double centerX = zone.centerX() - camera.x;
                double centerZ = zone.centerZ() - camera.z;
                if (zone.type() == Zone.Type.CIRCLE) {
                    emitCircle(vertices, matrix, centerX, y, centerZ, zone.size(), color);
                } else {
                    emitSquare(vertices, matrix, centerX, y, centerZ, zone.size(), color);
                }
            }
        } finally {
            matrices.pop();
        }
    }

    /**
     * Minecraft 1.21.10 exposes this layer on RenderLayer; 1.21.11 moved it
     * to RenderLayers. Resolve once so the same source supports both targets.
     */
    private static RenderLayer resolveDebugQuadsLayer() {
        try {
            Method method;
            try {
                method = RenderLayer.class.getMethod("getDebugQuads");
            } catch (NoSuchMethodException movedIn12111) {
                Class<?> renderLayers = Class.forName("net.minecraft.client.render.RenderLayers");
                method = renderLayers.getMethod("debugQuads");
            }
            return (RenderLayer) method.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to locate Minecraft's debug quad render layer", e);
        }
    }

    private static int colorFor(Zone.SurfaceMaterial material) {
        return switch (material) {
            // These are deliberately solid, readable material tints rather than
            // block-atlas textures; the platform is real depth-tested geometry.
            case WATER -> rgba(45, 105, 220, 190);
            case GRASS -> rgba(83, 157, 62, 255);
            case SAND -> rgba(218, 196, 125, 255);
        };
    }

    private static int rgba(int red, int green, int blue, int alpha) {
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static void emitSquare(VertexConsumer out, MatrixStack.Entry matrix,
                                   double centerX, float y, double centerZ,
                                   double halfSize, int color) {
        double minX = centerX - halfSize;
        double maxX = centerX + halfSize;
        double minZ = centerZ - halfSize;
        double maxZ = centerZ + halfSize;

        // Two upward-facing triangles, each encoded as a degenerate quad because
        // the vanilla debug-quads layer uses the QUADS vertex format.
        triangle(out, matrix, minX, minZ, minX, maxZ, maxX, maxZ, y, color);
        triangle(out, matrix, minX, minZ, maxX, maxZ, maxX, minZ, y, color);
    }

    private static void emitCircle(VertexConsumer out, MatrixStack.Entry matrix,
                                   double centerX, float y, double centerZ,
                                   double radius, int color) {
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double a = (Math.PI * 2.0 * i) / CIRCLE_SEGMENTS;
            double b = (Math.PI * 2.0 * (i + 1)) / CIRCLE_SEGMENTS;
            double ax = centerX + Math.cos(a) * radius;
            double az = centerZ + Math.sin(a) * radius;
            double bx = centerX + Math.cos(b) * radius;
            double bz = centerZ + Math.sin(b) * radius;
            // Reverse perimeter order to keep the normal facing upward.
            triangle(out, matrix, centerX, centerZ, bx, bz, ax, az, y, color);
        }
    }

    private static void triangle(VertexConsumer out, MatrixStack.Entry matrix,
                                 double ax, double az, double bx, double bz,
                                 double cx, double cz, float y, int color) {
        vertex(out, matrix, ax, y, az, color);
        vertex(out, matrix, bx, y, bz, color);
        vertex(out, matrix, cx, y, cz, color);
        vertex(out, matrix, cx, y, cz, color);
    }

    private static void vertex(VertexConsumer out, MatrixStack.Entry matrix,
                               double x, float y, double z, int color) {
        out.vertex(matrix, (float) x, y, (float) z).color(color);
    }
}
