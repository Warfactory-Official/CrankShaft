package dev.engine_room.flywheel.backend.compile.component;

import dev.engine_room.flywheel.backend.MaterialShaderIndices;
import dev.engine_room.flywheel.backend.glsl.ShaderSources;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class UberMaterialShaderComponent implements SourceComponent {
    private final List<SourceComponent> included = new ArrayList<>();
    private final int cutoutCount;
    private final int fogCount;

    public UberMaterialShaderComponent(ShaderSources sources) {
        List<Identifier> cutouts = List.copyOf(MaterialShaderIndices.cutoutSources().all());
        List<Identifier> fogs = List.copyOf(MaterialShaderIndices.fogSources().all());
        cutoutCount = cutouts.size();
        fogCount = fogs.size();
        for (int i = 0; i < cutouts.size(); i++) {
            included.add(new Namespaced(sources.get(cutouts.get(i)), "flw_discardPredicate", i));
        }
        for (int i = 0; i < fogs.size(); i++) {
            included.add(new Namespaced(sources.get(fogs.get(i)), "flw_fogFilter", i));
        }
    }

    @Override
    public String name() {
        return ResourceUtil.rl("uber_material").toString() + "/" + cutoutCount + "_" + fogCount;
    }

    @Override
    public Collection<? extends SourceComponent> included() {
        return included;
    }

    @Override
    public String source() {
        var sb = new StringBuilder();
        sb.append("bool flw_discardPredicateUber(uint cutoutIndex, vec4 color) {\n");
        sb.append("    switch (cutoutIndex) {\n");
        for (int i = 0; i < cutoutCount; i++) {
            sb.append("    case ").append(i).append("u: return flw_discardPredicate_").append(i).append("(color);\n");
        }
        sb.append("    }\n");
        sb.append("    return false;\n");
        sb.append("}\n");
        sb.append(
                "vec4 flw_fogFilterUber(uint fogIndex, vec4 color, float sphericalDistance, float cylindricalDistance) {\n");
        sb.append("    switch (fogIndex) {\n");
        for (int i = 0; i < fogCount; i++) {
            sb.append("    case ").append(i).append("u: return flw_fogFilter_").append(i)
              .append("(color, sphericalDistance, cylindricalDistance);\n");
        }
        sb.append("    }\n");
        sb.append("    return color;\n");
        sb.append("}\n");
        return sb.toString();
    }

    private record Namespaced(SourceComponent file, String fnName, int index) implements SourceComponent {
        @Override
        public String name() {
            return file.name() + "#" + fnName + index;
        }

        @Override
        public Collection<? extends SourceComponent> included() {
            return file.included();
        }

        @Override
        public String source() {
            return "#define " + fnName + " " + fnName + "_" + index + "\n"
                    + file.source()
                    + "\n#undef " + fnName + "\n";
        }
    }
}
