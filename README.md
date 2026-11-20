# Voxy Exclusion Zones

A standalone Fabric client addon for Minecraft 1.21.10 and 1.21.11. It excludes configured horizontal areas from Voxy's LOD terrain and can replace those areas with full-depth virtual material volumes rendered through Voxy's own voxel and mesh pipeline.

## Build on Windows 11

- Minecraft 1.21.10: double-click `build.bat`.
- Minecraft 1.21.11: double-click `build-1.21.11.bat`.

You do **not** need to install Gradle manually.

1. Install a JDK 21.
2. Extract this project ZIP.
3. Double-click the matching build script.
4. The script downloads the required Gradle distribution, runs the build, and reports the output JAR.

The first build requires an internet connection to download Gradle and the Minecraft/Fabric/Voxy dependencies. Later builds reuse the Gradle distribution and dependency cache.

Expected output:
- 1.21.10: `build/libs/voxy-exclusion-zones-0.2.0.jar`
- 1.21.11: `build/libs/voxy-exclusion-zones-mc1.21.11-0.2.0.jar`

## Pinned build inputs

| Dependency | Minecraft 1.21.10 | Minecraft 1.21.11 |
|---|---|---|
| Java | 21 | 21 |
| Fabric Loom | 1.11.8 | 1.14.8 |
| Gradle | 8.14.3 | 9.2.1 |
| Yarn | 1.21.10+build.3 | 1.21.11+build.3 |
| Fabric API | 0.138.4+1.21.10 | 0.141.6+1.21.11 |
| Voxy | 0.2.6-alpha | 0.2.13-alpha |
| Sodium | mc1.21.10-0.7.3-fabric | mc1.21.11-0.8.7-fabric |

Voxy and Sodium are compile-time dependencies only. Neither is bundled into the addon JAR; keep both installed separately in your MultiMC instance.

## Features

- Named circular zones (`radius`) and square zones (`halfSize`) in horizontal X/Z coordinates.
- Separate JSON configs are stored under `config/voxy-exclusion-zones/`. Multiplayer servers use a SHA-256 key derived from the configured server address; single-player worlds use a SHA-256 key derived from the persistent save path. The old `config/voxy-exclusion-zones.json` is migrated to the first world/server config entered after updating.
- Commands:
  - `/voxyexclude add circle <name> <x> <z> <radius>`
  - `/voxyexclude add square <name> <x> <z> <halfSize>`
  - `/voxyexclude list`
  - `/voxyexclude enable <name>`
  - `/voxyexclude disable <name>`
  - `/voxyexclude remove <name>`
  - `/voxyexclude clear`
  - `/voxyexclude surface <name> <water|grass|sand> <y>`
  - `/voxyexclude surfaceoff <name>`
- New zones are disabled by default. The initial configuration is empty.
- Per-zone replacement material and Y settings are persisted in config version 2. Existing version 1 files are migrated with replacement volumes disabled by default.
- Configs switch automatically on world/server join and are unloaded on disconnect.

## Replacement volumes

The `surface` command configures a **solid virtual volume**, not a thin platform. It fills from the dimension's minimum build height up to (but not including) the configured Y coordinate, within the zone's circle or square footprint.

The selected material is a real Minecraft block state:
- `water`: water
- `grass`: grass block
- `sand`: sand

The addon supplies these voxels to Voxy's section-update pipeline. Voxy then performs its normal mip generation and block-model mesh rendering, so the replacement uses Voxy's material/model path rather than a separately drawn overlay. The addon does not place Minecraft blocks in the world and does not create collision.

Synthetic voxel changes are marked as non-persistent in Voxy's section storage and are reconstructed while the zone is active. Since Voxy stores section data as a unit, updates to real terrain in an affected Voxy section may also need to be re-ingested from the Minecraft world rather than saved as a modified Voxy section.

## Exclusion rendering

- The addon patches Voxy's `voxy:lod/gl46/quads3.vert` and `voxy:lod/gl46/quads.frag` shaders.
- The vertex shader passes interpolated block-space positions to the fragment shader. The fragment shader discards fragments whose X/Z positions fall inside enabled zones.
- The exclusion SSBO is bound at shader-storage binding 11 while Voxy renders opaque, temporal, and translucent terrain, then the previous binding is restored.
- The renderer mixin releases the SSBO when Voxy frees its MDIC section renderer.
- The addon does not modify Voxy's hierarchical traversal decisions. Partially intersecting LOD meshes remain available to render, and only covered fragments are discarded.

## Verification status

GitHub Actions successfully compiled both Minecraft targets on the current implementation branch. Runtime validation is still required, including Mixin application against the installed Voxy jars, water/grass/sand appearance, LOD transitions and section reloads, zone enable/disable restoration, and performance with large volumes.

This remains a draft implementation. A successful Gradle build does not by itself prove that the runtime Mixin hooks or rendered output work correctly on the user's Minecraft/OpenGL setup.
