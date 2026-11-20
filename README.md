# Voxy Exclusion Zones

Separate Fabric client addon for Minecraft 1.21.10, targeting Voxy 0.2.6-alpha at source commit `1c9ef1e` and Sodium 0.7.3.

## Build on Windows 11

- For Minecraft 1.21.10, double-click `build.bat`.
- For Minecraft 1.21.11, double-click `build-1.21.11.bat`. This selects Yarn `1.21.11+build.3`, Fabric API `0.141.5+1.21.11`, Voxy `0.2.8-alpha`, and Sodium `0.8.2`.

The 1.21.11 launcher is a separate dependency/build target (Voxy 0.2.8-alpha and Sodium 0.8.2 for Fabric). A CI workflow now compiles both targets. It is not yet a verified runtime port: the Voxy shader and renderer Mixin targets must still be checked against that exact Voxy build and tested in-game.

You do **not** need to install Gradle manually.

1. Install a JDK 21 (Minecraft 1.21.10 requires Java 21).
2. Extract this project ZIP.
3. Double-click `build.bat`.
4. The script downloads the official Gradle 8.14.3 distribution, runs `gradle build`, and reports the output JAR.

The output should be:

`build/libs/voxy-exclusion-zones-0.1.0.jar`

The first build requires an internet connection to download Gradle and the Minecraft/Fabric/Voxy dependencies. On later builds the downloaded Gradle distribution and Gradle dependency cache are reused.

Linux/macOS: run `./build.sh` from the project directory.

## Pinned build inputs

- Default Minecraft target: `1.21.10`
- Java toolchain: `21`
- Yarn: `1.21.10+build.3`
- Fabric Loader: `0.17.3`
- Fabric API: `0.138.4+1.21.10`
- Fabric Loom: `1.11.8`
- Default Voxy compile-only: Modrinth `maven.modrinth:fxxUqruK:agZsxLao` (`0.2.6-alpha`)
- Default Sodium compile-only: Modrinth `maven.modrinth:AANobbMI:sFfidWgd` (`mc1.21.10-0.7.3-fabric`)
- 1.21.11 build launcher overrides Voxy to `0.2.8-alpha` and Sodium to `0.8.2`.

Voxy and Sodium are compile-time dependencies only. Neither is bundled into the addon JAR; keep both installed separately in your MultiMC instance.

## Features in this source snapshot

- Named circular zones (`radius`) and square zones (`halfSize`) in horizontal X/Z coordinates.
- Separate JSON configs are stored under `config/voxy-exclusion-zones/`: multiplayer servers use a SHA-256 key derived from the configured server address, and single-player worlds use a SHA-256 key derived from the persistent save path. The old `config/voxy-exclusion-zones.json` is migrated to the first world/server config entered after updating.
- Commands:
  - `/voxyexclude add circle <name> <x> <z> <radius>`
  - `/voxyexclude add square <name> <x> <z> <halfSize>`
  - `/voxyexclude list`
  - `/voxyexclude enable <name>`
  - `/voxyexclude disable <name>`
  - `/voxyexclude remove <name>`
  - `/voxyexclude clear`
- `/voxyexclude surface <name> <water|grass|sand> <y>` — configure a replacement surface for a zone at an absolute world Y level.
- `/voxyexclude surfaceoff <name>` — disable that zone's replacement surface.
- New zones are disabled by default. The initial configuration is empty.
- Per-zone replacement-surface settings are persisted in config version 2. Existing version 1 files are migrated when loaded; their replacement surfaces default to off. Configs now switch automatically on world/server join and are unloaded on disconnect.
- Enabled replacement surfaces now generate camera-relative, depth-tested platform geometry in the Fabric world-render pass. Circular zones are tessellated into 64 segments; square zones are rendered as two triangles. The first renderer uses distinct solid material tints (blue water, green grass, tan sand) rather than sampling Minecraft block textures. These are visual surfaces only; they do not create collision or place blocks in the world.
- The addon patches Voxy 0.2.6-alpha's `voxy:lod/gl46/quads3.vert` and `voxy:lod/gl46/quads.frag` shaders.
- The vertex shader passes interpolated block-space positions to the fragment shader. The fragment shader discards individual fragments whose X/Z positions fall inside enabled zones.
- The exclusion SSBO is bound at shader-storage binding 11 while Voxy renders opaque, temporal, and translucent terrain, then the previous binding is restored.
- The renderer mixin releases the SSBO when Voxy frees its MDIC section renderer.
- The addon does not modify Voxy's hierarchical traversal decisions. Partially intersecting LOD meshes remain available to render, and only covered fragments are discarded.

## Geometry behavior and limitations

- Exclusion is evaluated per rasterized fragment in the horizontal X/Z plane. It therefore applies at every height and to opaque, temporal, and translucent terrain.
- Circular zones use their configured radius; square zones use their configured half-size.
- Boundaries are rasterized at screen resolution, so the visible edge can have normal pixel-level aliasing.
- This patch targets the GL46 MDIC renderer and the exact Voxy shader IDs used by Voxy 0.2.6-alpha at source commit `1c9ef1e`. It is not a general compatibility patch for other Voxy versions or rendering backends.
- This implementation has not yet been tested in-game. A successful Gradle build and runtime shader compilation are still required before treating it as verified.

## Verification status

The original exclusion shader source anchors were checked against Voxy commit `1c9ef1e`, including `quads3.vert`, `quad_util.glsl`, `quads.frag`, `bindings.glsl`, `MDICSectionRenderer.java`, `MDICViewport.java`, and `Viewport.java`.

**Not yet verified:** a real Gradle/Loom dependency resolution, full project compilation after the replacement-surface data changes, Mixin application against the installed Voxy jar, GLSL compilation on the user's OpenGL driver, or an in-game boundary test. The replacement platform renderer is present in this branch but still requires successful CI compilation and in-game validation, including draw order with Voxy and shader-pack compatibility.
