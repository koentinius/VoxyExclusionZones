package dev.voxyexclude;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class Zone {
    private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    public enum Type { CIRCLE, SQUARE }
    public enum SurfaceMaterial { WATER, GRASS, SAND }

    private final String name;
    private final Type type;
    private final int centerX, centerZ, size;
    private boolean enabled;
    private boolean surfaceEnabled;
    private SurfaceMaterial surfaceMaterial;
    private int surfaceY;

    public Zone(String name, Type type, int centerX, int centerZ, int size, boolean enabled) {
        this(name, type, centerX, centerZ, size, enabled, false, SurfaceMaterial.GRASS, 64);
    }

    public Zone(String name, Type type, int centerX, int centerZ, int size, boolean enabled,
                boolean surfaceEnabled, SurfaceMaterial surfaceMaterial, int surfaceY) {
        this.name = validateName(name);
        this.type = Objects.requireNonNull(type, "type");
        if (size <= 0) throw new IllegalArgumentException("Size must be positive");
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.size = size;
        this.enabled = enabled;
        this.surfaceEnabled = surfaceEnabled;
        this.surfaceMaterial = Objects.requireNonNull(surfaceMaterial, "surfaceMaterial");
        this.surfaceY = surfaceY;
    }

    public static String validateName(String name) {
        Objects.requireNonNull(name, "name");
        String normalized = name.toLowerCase(Locale.ROOT);
        if (!VALID_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Name must match [a-z0-9_-]{1,32}");
        }
        return normalized;
    }

    public String name() { return name; }
    public Type type() { return type; }
    public int centerX() { return centerX; }
    public int centerZ() { return centerZ; }
    public int size() { return size; }
    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean surfaceEnabled() { return surfaceEnabled; }
    public SurfaceMaterial surfaceMaterial() { return surfaceMaterial; }
    public int surfaceY() { return surfaceY; }
    public void setSurface(SurfaceMaterial material, int y) {
        this.surfaceMaterial = Objects.requireNonNull(material, "material");
        this.surfaceY = y;
        this.surfaceEnabled = true;
    }
    public void disableSurface() { this.surfaceEnabled = false; }
}
