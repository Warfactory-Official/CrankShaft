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
    private final int[] cases;

    public UberCullComponent(List<InstanceType<?>> types, ShaderSources sources) {
        cases = TypeCases.of(types, InstanceType::cullShader);
        included.add(new Raw(ResourceUtil.rl("uber_instance_buffer").toString(),
                "layout(std430, binding = " + BufferBindings.INSTANCE + ") restrict readonly buffer InstanceBuffer {\n"
                        + "    uint _flw_instances[];\n"
                        + "};\n"));
        var scoped = new InstanceScopedSources("flw_transformBoundingSphere");
        for (int i = 0; i < types.size(); i++) {
            if (cases[i] != i) continue;
            InstanceType<?> type = types.get(i);
            var struct = new InstanceStructComponent(type, "FlwInstance_" + i);
            included.add(struct);
            included.add(scoped.typeShader(sources.get(type.cullShader()), type.layout(), struct, i));
            included.add(new SsboInstanceComponent(type, i));
        }
    }

    @Override
    public String name() {
        return ResourceUtil.rl("uber_cull").toString() + "/" + cases.length;
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
        TypeCases.appendSwitch(sb, cases, (_, typeCase) -> "        FlwInstance_" + typeCase
                + " instance = _flw_unpackInstance_" + typeCase + "(objectUint);\n        flw_transformBoundingSphere_"
                + typeCase + "(instance, center, radius);\n");
        sb.append("}\n");
        return sb.toString();
    }

    private record Raw(String name, String source) implements SourceComponent {
        @Override
        public Collection<? extends SourceComponent> included() {
            return List.of();
        }
    }
}
