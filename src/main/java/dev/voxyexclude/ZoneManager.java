package dev.voxyexclude;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class ZoneManager {
    private static final int CONFIG_VERSION = 2;
    private static final Path LEGACY_CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("voxy-exclusion-zones.json");
    private static Path configFile = LEGACY_configFile;
    private static boolean legacyMigrationConsidered;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, Zone> ZONES = new LinkedHashMap<>();
    private static boolean loaded;
    private static long revision;

    public record EnabledSnapshot(long revision, List<Zone> zones) {}

    private ZoneManager() {}

    public static void load() throws IOException {
        synchronized (LOCK) {
            if (Files.exists(configFile)) {
                JsonObject root = JsonParser.parseString(Files.readString(configFile)).getAsJsonObject();
                int version = root.has("version") ? root.get("version").getAsInt() : CONFIG_VERSION;
                if (version < 1 || version > CONFIG_VERSION) throw new IOException("Unsupported config version: " + version);
                LinkedHashMap<String, Zone> parsed = new LinkedHashMap<>();
                JsonArray zones = root.getAsJsonArray("zones");
                if (zones != null) for (JsonElement element : zones) {
                    JsonObject z = element.getAsJsonObject();
                    Zone zone = new Zone(
                        z.get("name").getAsString(),
                        Zone.Type.valueOf(z.get("type").getAsString().toUpperCase(Locale.ROOT)),
                        z.get("centerX").getAsInt(), z.get("centerZ").getAsInt(),
                        z.get("size").getAsInt(), z.get("enabled").getAsBoolean(),
                        z.has("surfaceEnabled") && z.get("surfaceEnabled").getAsBoolean(),
                        z.has("surfaceMaterial")
                            ? Zone.SurfaceMaterial.valueOf(z.get("surfaceMaterial").getAsString().toUpperCase(Locale.ROOT))
                            : Zone.SurfaceMaterial.GRASS,
                        z.has("surfaceY") ? z.get("surfaceY").getAsInt() : 64
                    );
                    if (parsed.putIfAbsent(zone.name(), zone) != null) throw new IOException("Duplicate zone: " + zone.name());
                }
                ZONES.clear();
                ZONES.putAll(parsed);
            } else {
                saveMap(Map.of());
            }
            loaded = true;
            revision++;
        }
    }

    public static Zone add(String name, Zone.Type type, int x, int z, int size) throws IOException {
        synchronized (LOCK) {
            ensureLoaded();
            Zone zone = new Zone(name, type, x, z, size, false);
            if (ZONES.containsKey(zone.name())) throw new IllegalArgumentException("Zone already exists: " + zone.name());
            ZONES.put(zone.name(), zone);
            try { saveInternal(); } catch (IOException e) { ZONES.remove(zone.name()); throw e; }
            revision++;
            return copy(zone);
        }
    }

    public static Zone require(String name) {
        synchronized (LOCK) {
            ensureLoaded();
            Zone zone = ZONES.get(Zone.validateName(name));
            if (zone == null) throw new IllegalArgumentException("Unknown zone: " + name);
            return copy(zone);
        }
    }

    public static void setEnabled(String name, boolean enabled) throws IOException {
        synchronized (LOCK) {
            ensureLoaded();
            String key = Zone.validateName(name);
            Zone zone = ZONES.get(key);
            if (zone == null) throw new IllegalArgumentException("Unknown zone: " + name);
            boolean old = zone.enabled();
            zone.setEnabled(enabled);
            try { saveInternal(); } catch (IOException e) { zone.setEnabled(old); throw e; }
            revision++;
        }
    }

    public static void setSurface(String name, Zone.SurfaceMaterial material, int y) throws IOException {
        synchronized (LOCK) {
            ensureLoaded();
            String key = Zone.validateName(name);
            Zone zone = ZONES.get(key);
            if (zone == null) throw new IllegalArgumentException("Unknown zone: " + name);
            Zone old = copy(zone);
            zone.setSurface(material, y);
            try { saveInternal(); } catch (IOException e) { ZONES.put(key, old); throw e; }
            revision++;
        }
    }

    public static void disableSurface(String name) throws IOException {
        synchronized (LOCK) {
            ensureLoaded();
            String key = Zone.validateName(name);
            Zone zone = ZONES.get(key);
            if (zone == null) throw new IllegalArgumentException("Unknown zone: " + name);
            Zone old = copy(zone);
            zone.disableSurface();
            try { saveInternal(); } catch (IOException e) { ZONES.put(key, old); throw e; }
            revision++;
        }
    }

    public static boolean remove(String name) throws IOException {
        synchronized (LOCK) {
            ensureLoaded();
            Zone removed = ZONES.remove(Zone.validateName(name));
            if (removed == null) return false;
            try { saveInternal(); } catch (IOException e) { ZONES.put(removed.name(), removed); throw e; }
            revision++;
            return true;
        }
    }

    public static int clear() throws IOException {
        synchronized (LOCK) {
            ensureLoaded();
            LinkedHashMap<String, Zone> old = new LinkedHashMap<>(ZONES);
            int count = ZONES.size();
            ZONES.clear();
            try { saveInternal(); } catch (IOException e) { ZONES.putAll(old); throw e; }
            revision++;
            return count;
        }
    }

    public static List<Zone> all() {
        synchronized (LOCK) { ensureLoaded(); return ZONES.values().stream().map(ZoneManager::copy).toList(); }
    }

    public static List<Zone> enabled() {
        synchronized (LOCK) { ensureLoaded(); return ZONES.values().stream().filter(Zone::enabled).map(ZoneManager::copy).toList(); }
    }

    public static EnabledSnapshot enabledSnapshot() {
        synchronized (LOCK) {
            ensureLoaded();
            List<Zone> snapshot = ZONES.values().stream()
                    .filter(Zone::enabled)
                    .map(ZoneManager::copy)
                    .toList();
            return new EnabledSnapshot(revision, snapshot);
        }
    }

    public static Path configPath() { synchronized (LOCK) { return configFile; } }

    public static void switchStorage(Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        synchronized (LOCK) {
            ensureLoaded();
            if (normalized.equals(configFile.toAbsolutePath().normalize())) return;
            if (!legacyMigrationConsidered) {
                legacyMigrationConsidered = true;
                if (!Files.exists(normalized) && Files.exists(LEGACY_CONFIG_FILE)) {
                    Files.createDirectories(normalized.getParent());
                    try {
                        Files.move(LEGACY_CONFIG_FILE, normalized, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(LEGACY_CONFIG_FILE, normalized, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            configFile = normalized;
            ZONES.clear();
            loadCurrentFile();
            revision++;
        }
    }

    private static void loadCurrentFile() throws IOException {
        if (!Files.exists(configFile)) {
            saveMap(Map.of());
            return;
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(configFile)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid zone config: " + configFile, e);
        }
        int version = root.has("version") ? root.get("version").getAsInt() : CONFIG_VERSION;
        if (version < 1 || version > CONFIG_VERSION) throw new IOException("Unsupported config version: " + version);
        LinkedHashMap<String, Zone> parsed = new LinkedHashMap<>();
        JsonArray zones = root.getAsJsonArray("zones");
        if (zones != null) for (JsonElement element : zones) {
            JsonObject z = element.getAsJsonObject();
            Zone zone = new Zone(z.get("name").getAsString(),
                Zone.Type.valueOf(z.get("type").getAsString().toUpperCase(Locale.ROOT)),
                z.get("centerX").getAsInt(), z.get("centerZ").getAsInt(),
                z.get("size").getAsInt(), z.get("enabled").getAsBoolean(),
                z.has("surfaceEnabled") && z.get("surfaceEnabled").getAsBoolean(),
                z.has("surfaceMaterial") ? Zone.SurfaceMaterial.valueOf(z.get("surfaceMaterial").getAsString().toUpperCase(Locale.ROOT)) : Zone.SurfaceMaterial.GRASS,
                z.has("surfaceY") ? z.get("surfaceY").getAsInt() : 64);
            if (parsed.putIfAbsent(zone.name(), zone) != null) throw new IOException("Duplicate zone: " + zone.name());
        }
        ZONES.clear();
        ZONES.putAll(parsed);
    }

    private static void ensureLoaded() {
        if (!loaded) throw new IllegalStateException("ZoneManager has not been loaded");
    }

    private static Zone copy(Zone z) {
        return new Zone(z.name(), z.type(), z.centerX(), z.centerZ(), z.size(), z.enabled(),
                z.surfaceEnabled(), z.surfaceMaterial(), z.surfaceY());
    }

    private static void saveInternal() throws IOException { saveMap(ZONES); }

    private static void saveMap(Map<String, Zone> zones) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", CONFIG_VERSION);
        JsonArray array = new JsonArray();
        for (Zone z : zones.values()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", z.name());
            item.addProperty("type", z.type().name().toLowerCase(Locale.ROOT));
            item.addProperty("centerX", z.centerX());
            item.addProperty("centerZ", z.centerZ());
            item.addProperty("size", z.size());
            item.addProperty("enabled", z.enabled());
            item.addProperty("surfaceEnabled", z.surfaceEnabled());
            item.addProperty("surfaceMaterial", z.surfaceMaterial().name().toLowerCase(Locale.ROOT));
            item.addProperty("surfaceY", z.surfaceY());
            array.add(item);
        }
        root.add("zones", array);
        Path temp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
        Files.createDirectories(configFile.getParent());
        Files.writeString(temp, GSON.toJson(root));
        try {
            Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
