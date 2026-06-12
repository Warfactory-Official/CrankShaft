package dev.engine_room.flywheel.backend.compile.component;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.backend.engine.indirect.BufferBindings;
import dev.engine_room.flywheel.backend.glsl.ShaderSources;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.lib.util.ResourceUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class UberCullComponent implements SourceComponent {
    private final List<SourceComponent> included = new ArrayList<>();
    private final int typeCount;

    public UberCullComponent(List<InstanceType<?>> types, ShaderSources sources) {
        typeCount = types.size();
        included.add(new Raw(ResourceUtil.rl("uber_instance_buffer").toString(),
                "layout(std430, binding = " + BufferBindings.INSTANCE + ") restrict readonly buffer InstanceBuffer {\n"
                        + "    uint _flw_instances[];\n"
                        + "};\n"));
        for (int i = 0; i < types.size(); i++) {
            InstanceType<?> type = types.get(i);
            included.add(new InstanceStructComponent(type, "FlwInstance_" + i));
            included.add(new NamespacedCull(sources.get(type.cullShader()), i));
            included.add(new SsboInstanceComponent(type, i));
        }
    }

    @Override
    public String name() {
        return ResourceUtil.rl("uber_cull").toString() + "/" + typeCount;
    }

    @Override
    public Collection<? extends SourceComponent> included() {
        return included;
    }

    @Override
    public String source() {
        var sb = new StringBuilder();
        sb.append(
                "void _flw_transformBoundingSphereUber(uint typeId, uint objectUint, inout vec3 center, inout float radius) {\n");
        sb.append("    switch (typeId) {\n");
        for (int i = 0; i < typeCount; i++) {
            sb.append("    case ").append(i).append("u: {\n");
            sb.append("        FlwInstance_").append(i).append(" instance = _flw_unpackInstance_").append(i)
              .append("(objectUint);\n");
            sb.append("        flw_transformBoundingSphere_").append(i).append("(instance, center, radius);\n");
            sb.append("        break;\n");
            sb.append("    }\n");
        }
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private record NamespacedCull(SourceComponent file, int index) implements SourceComponent {
        @Override
        public String name() {
            return file.name() + "#" + index;
        }

        @Override
        public Collection<? extends SourceComponent> included() {
            return file.included();
        }

        @Override
        public String source() {
            return "#define FlwInstance FlwInstance_" + index + "\n"
                    + "#define flw_transformBoundingSphere flw_transformBoundingSphere_" + index + "\n"
                    + file.source()
                    + "\n#undef FlwInstance\n"
                    + "#undef flw_transformBoundingSphere\n";
        }
    }

    private record Raw(String name, String source) implements SourceComponent {
        @Override
        public Collection<? extends SourceComponent> included() {
            return List.of();
        }
    }
}
