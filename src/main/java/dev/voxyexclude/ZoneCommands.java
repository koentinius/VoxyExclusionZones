package dev.voxyexclude;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import java.io.IOException;

public final class ZoneCommands {
    private ZoneCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerCommands(dispatcher));
    }

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommandManager.literal("voxyexclude")
            .then(ClientCommandManager.literal("add")
                .then(ClientCommandManager.literal("circle")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                            .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("radius", IntegerArgumentType.integer(1))
                                    .executes(ctx -> add(ctx.getSource(), StringArgumentType.getString(ctx, "name"),
                                        Zone.Type.CIRCLE, IntegerArgumentType.getInteger(ctx, "x"),
                                        IntegerArgumentType.getInteger(ctx, "z"), IntegerArgumentType.getInteger(ctx, "radius"))))))))
                .then(ClientCommandManager.literal("square")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                            .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("halfSize", IntegerArgumentType.integer(1))
                                    .executes(ctx -> add(ctx.getSource(), StringArgumentType.getString(ctx, "name"),
                                        Zone.Type.SQUARE, IntegerArgumentType.getInteger(ctx, "x"),
                                        IntegerArgumentType.getInteger(ctx, "z"), IntegerArgumentType.getInteger(ctx, "halfSize")))))))))
            .then(ClientCommandManager.literal("surface")
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .then(ClientCommandManager.argument("material", StringArgumentType.word())
                        .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                            .executes(ctx -> setSurface(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name"),
                                StringArgumentType.getString(ctx, "material"),
                                IntegerArgumentType.getInteger(ctx, "y")))))))
            .then(ClientCommandManager.literal("surfaceoff")
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .executes(ctx -> disableSurface(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
            .then(ClientCommandManager.literal("list").executes(ctx -> list(ctx.getSource())))
            .then(ClientCommandManager.literal("enable")
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .executes(ctx -> setEnabled(ctx.getSource(), StringArgumentType.getString(ctx, "name"), true))))
            .then(ClientCommandManager.literal("disable")
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .executes(ctx -> setEnabled(ctx.getSource(), StringArgumentType.getString(ctx, "name"), false))))
            .then(ClientCommandManager.literal("remove")
                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                    .executes(ctx -> remove(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
            .then(ClientCommandManager.literal("clear").executes(ctx -> clear(ctx.getSource())))
        );
    }

    private static int add(FabricClientCommandSource source, String name, Zone.Type type, int x, int z, int size) {
        try {
            Zone zone = ZoneManager.add(name, type, x, z, size);
            source.sendFeedback(Text.literal("Added disabled " + type.name().toLowerCase() + " zone '" + zone.name() + "' at " + x + ", " + z + " (size " + size + ")."));
            return 1;
        } catch (Exception e) { return error(source, e); }
    }

    private static int list(FabricClientCommandSource source) {
        try {
            var zones = ZoneManager.all();
            if (zones.isEmpty()) { source.sendFeedback(Text.literal("No exclusion zones configured.")); return 0; }
            for (Zone z : zones) source.sendFeedback(Text.literal((z.enabled() ? "[enabled] " : "[disabled] ") +
                z.name() + " " + z.type().name().toLowerCase() + " center=" + z.centerX() + "," + z.centerZ() + " size=" + z.size()
                + (z.surfaceEnabled() ? " surface=" + z.surfaceMaterial().name().toLowerCase() + "@Y=" + z.surfaceY() : " surface=off")));
            return zones.size();
        } catch (Exception e) { return error(source, e); }
    }

    private static int setSurface(FabricClientCommandSource source, String name, String materialName, int y) {
        Zone.SurfaceMaterial material;
        try {
            material = Zone.SurfaceMaterial.valueOf(materialName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return error(source, new IllegalArgumentException("Material must be water, grass, or sand."));
        }
        try {
            ZoneManager.setSurface(name, material, y);
            source.sendFeedback(Text.literal("Configured " + name + " replacement surface: " + material.name().toLowerCase() + " at Y=" + y + "."));
            return 1;
        } catch (Exception e) { return error(source, e); }
    }

    private static int disableSurface(FabricClientCommandSource source, String name) {
        try { ZoneManager.disableSurface(name); source.sendFeedback(Text.literal("Disabled replacement surface for " + name)); return 1; }
        catch (Exception e) { return error(source, e); }
    }

    private static int setEnabled(FabricClientCommandSource source, String name, boolean enabled) {
        try { ZoneManager.setEnabled(name, enabled); source.sendFeedback(Text.literal((enabled ? "Enabled " : "Disabled ") + name)); return 1; }
        catch (Exception e) { return error(source, e); }
    }

    private static int remove(FabricClientCommandSource source, String name) {
        try {
            if (!ZoneManager.remove(name)) { source.sendError(Text.literal("Unknown zone: " + name)); return 0; }
            source.sendFeedback(Text.literal("Removed zone " + name)); return 1;
        } catch (Exception e) { return error(source, e); }
    }

    private static int clear(FabricClientCommandSource source) {
        try { int count = ZoneManager.clear(); source.sendFeedback(Text.literal("Removed " + count + " exclusion zone(s).")); return count; }
        catch (Exception e) { return error(source, e); }
    }

    private static int error(FabricClientCommandSource source, Exception e) {
        source.sendError(Text.literal(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        return 0;
    }
}
