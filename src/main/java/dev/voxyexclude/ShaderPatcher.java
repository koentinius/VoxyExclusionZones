package dev.voxyexclude;

public final class ShaderPatcher {
    public static final String VERTEX_SHADER = "voxy:lod/gl46/quads3.vert";
    public static final String FRAGMENT_SHADER = "voxy:lod/gl46/quads.frag";

    private static final String VERTEX_MARKER = "VOXY_EXCLUSION_ZONES_VERTEX_V1";
    private static final String FRAGMENT_MARKER = "VOXY_EXCLUSION_ZONES_FRAGMENT_V1";

    private static final String VERTEX_DECLARATION = """
        // VOXY_EXCLUSION_ZONES_VERTEX_V1
        layout(location = 2) out vec3 voxyExcludeWorldPos;
        """;

    private static final String VERTEX_POSITION = """
            // Carry the exact interpolated block-space position to the fragment shader.
            vec2 voxyExcludeCornerMask = vec2((cornerId >> 1) & 1u, cornerId & 1u) * quad.lodScale;
            voxyExcludeWorldPos = quad.basePoint
                + swizzelDataAxis(quad.axis, vec3(quad.quadSizeAddin * voxyExcludeCornerMask, 0.0));
        """;

    private static final String FRAGMENT_DECLARATION = """
        // VOXY_EXCLUSION_ZONES_FRAGMENT_V1
        layout(location = 2) in vec3 voxyExcludeWorldPos;

        struct VoxyExcludeZone {
            ivec4 centerSectionOffset; // section X, section Z, block offset X, block offset Z
            ivec4 shape;               // type, radius/half-size, surface enabled, surface Y
            ivec4 surface;             // material: 0 water / 1 grass / 2 sand
        };

        layout(binding = 11, std430) readonly buffer VoxyExcludeData {
            uvec4 voxyExcludeHeader; // x = enabled zone count
            VoxyExcludeZone voxyExcludeZones[];
        };

        bool voxyExcludeFragment(vec2 fragmentXZ) {
            for (uint i = 0u; i < voxyExcludeHeader.x; ++i) {
                VoxyExcludeZone zone = voxyExcludeZones[i];

                // quad positions are relative to baseSectionPos in 32-block sections.
                ivec2 sectionDelta = ivec2(
                    zone.centerSectionOffset.x - baseSectionPos.x,
                    zone.centerSectionOffset.y - baseSectionPos.z
                );
                vec2 zoneCenter = vec2(sectionDelta) * 32.0
                    + vec2(zone.centerSectionOffset.z, zone.centerSectionOffset.w);

                float size = float(zone.shape.y);
                if (zone.shape.x == 0) {
                    vec2 delta = fragmentXZ - zoneCenter;
                    if (dot(delta, delta) <= size * size) {
                        return true;
                    }
                } else {
                    vec2 zoneMin = zoneCenter - vec2(size);
                    vec2 zoneMax = zoneCenter + vec2(size);
                    if (all(greaterThanEqual(fragmentXZ, zoneMin))
                            && all(lessThanEqual(fragmentXZ, zoneMax))) {
                        return true;
                    }
                }
            }
            return false;
        }
        """;

    private ShaderPatcher() {}

    public static String patch(String id, String source) {
        if (VERTEX_SHADER.equals(id)) {
            return patchVertex(source);
        }
        if (FRAGMENT_SHADER.equals(id)) {
            return patchFragment(source);
        }
        return source;
    }

    private static String patchVertex(String source) {
        if (source.contains(VERTEX_MARKER)) {
            return source;
        }

        String uvAnchor = "layout(location = 1) out vec2 uv;";
        String cornerAnchor = "    uint cornerId = gl_VertexID&3;";
        requireOnce(source, uvAnchor, "vertex UV output declaration");
        requireOnce(source, cornerAnchor, "vertex corner position");

        source = source.replace(uvAnchor, uvAnchor + "\n" + VERTEX_DECLARATION);
        source = source.replace(cornerAnchor, cornerAnchor + "\n" + VERTEX_POSITION);
        return source;
    }

    private static String patchFragment(String source) {
        if (source.contains(FRAGMENT_MARKER)) {
            return source;
        }

        String mainAnchor = "void main() {";
        requireOnce(source, mainAnchor, "fragment main function");

        source = source.replace(mainAnchor, FRAGMENT_DECLARATION + "\n" + mainAnchor);
        String firstMainStatement = "void main() {\n";
        requireOnce(source, firstMainStatement, "fragment main body");
        source = source.replace(firstMainStatement, firstMainStatement
                + "    if (voxyExcludeFragment(voxyExcludeWorldPos.xz)) {\n"
                + "        discard;\n"
                + "        return;\n"
                + "    }\n");
        return source;
    }

    private static void requireOnce(String source, String anchor, String description) {
        int first = source.indexOf(anchor);
        if (first < 0 || source.indexOf(anchor, first + anchor.length()) >= 0) {
            throw new IllegalStateException("Voxy exclusion shader patch: expected exactly one " + description);
        }
    }
}
