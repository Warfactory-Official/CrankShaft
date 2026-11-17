package dev.engine_room.flywheel.backend.compile.component;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.backend.engine.indirect.BufferBindings;
import dev.engine_room.flywheel.backend.glsl.ShaderSources;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public final class UberInstanceComponent implements SourceComponent {
    private final List<SourceComponent> included = new ArrayList<>();
    private final int typeCount;
    private final int[] cases;
    private final String name;
    private final String function;
    private final String unpack;
    private final boolean[] current;
    private final boolean @Nullable [] timeAnimated;

    /**
     * Also {@code bool _flw_timeAnimatedUber(uint typeId)}: {@link InstanceType#timeAnimated()} by type index.
     */
    public UberInstanceComponent(List<InstanceType<?>> types, ShaderSources sources) {
        this(types, "uber_instance", "_flw_instanceVertexUber", InstanceAssemblerComponent.UNPACK_FN_NAME,
                new boolean[types.size()]);
        included.add(buffer("uber_instance_buffer", BufferBindings.INSTANCE, "InstanceBuffer",
                InstanceAssemblerComponent.BUFFER_NAME));
        var scoped = new InstanceScopedSources("flw_instanceVertex");
        for (int i = 0; i < types.size(); i++) {
            InstanceType<?> type = types.get(i);
            timeAnimated[i] = type.timeAnimated();
            if (cases[i] != i) continue;
            var struct = new InstanceStructComponent(type, "FlwInstance_" + i);
            included.add(struct);
            included.add(scoped.typeShader(sources.get(type.vertexShader()), type.layout(), struct, i));
            included.add(new SsboInstanceComponent(type, i));
        }
    }

    private UberInstanceComponent(List<InstanceType<?>> types, String name, String function, String unpack,
                                  boolean @Nullable [] timeAnimated) {
        typeCount = types.size();
        cases = TypeCases.of(types, InstanceType::vertexShader);
        this.name = name;
        this.function = function;
        this.unpack = unpack;
        this.timeAnimated = timeAnimated;
        current = new boolean[typeCount];
    }

    /**
     * {@code _flw_prevInstanceVertexUber}: the same instance vertex shaders over the previous frame's object buffer at
     * {@code binding}, {@code current} types over this frame's; assembled after the component of the same types.
     */
    public static UberInstanceComponent history(List<InstanceType<?>> types, int binding,
                                                Set<InstanceType<?>> current) {
        var history = new UberInstanceComponent(types, "uber_instance_history", "_flw_prevInstanceVertexUber",
                InstanceAssemblerComponent.HISTORY_UNPACK_FN_NAME, null);
        history.included.add(buffer("uber_instance_history_buffer", binding, "_FlwPrevInstanceBuffer",
                InstanceAssemblerComponent.HISTORY_BUFFER_NAME));
        boolean[] unpacked = new boolean[types.size()];
        for (int i = 0; i < types.size(); i++) {
            history.current[i] = current.contains(types.get(i));
            int typeCase = history.cases[i];
            if (!history.current[i] && !unpacked[typeCase]) {
                unpacked[typeCase] = true;
                history.included.add(new SsboInstanceComponent(types.get(typeCase), typeCase,
                        InstanceAssemblerComponent.HISTORY_BUFFER_NAME,
                        InstanceAssemblerComponent.HISTORY_UNPACK_FN_NAME));
            }
        }
        return history;
    }

    private static SourceComponent buffer(String name, int binding, String block, String array) {
        return new Raw(ResourceUtil.rl(name).toString(),
                "layout(std430, binding = " + binding + ") restrict readonly buffer " + block + " {\n"
                        + "    uint " + array + "[];\n"
                        + "};\n");
    }

    @Override
    public String name() {
        return ResourceUtil.rl(name).toString() + "/" + typeCount;
    }

    @Override
    public Collection<? extends SourceComponent> included() {
        return included;
    }

    @Override
    public String source() {
        var sb = new StringBuilder();
        sb.append("void ").append(function).append("(uint typeId, uint objectUint) {\n");
        TypeCases.appendSwitch(sb, cases, (type, typeCase) -> "        FlwInstance_" + typeCase + " instance = "
                + (current[type] ? InstanceAssemblerComponent.UNPACK_FN_NAME : unpack) + "_" + typeCase
                + "(objectUint);\n        flw_instanceVertex_" + typeCase + "(instance);\n");
        sb.append("}\n");
        if (timeAnimated != null) {
            sb.append("bool _flw_timeAnimatedUber(uint typeId) {\n");
            sb.append("    return false");
            for (int i = 0; i < typeCount; i++) {
                if (timeAnimated[i]) sb.append(" || typeId == ").append(i).append('u');
            }
            sb.append(";\n");
            sb.append("}\n");
        }
        return sb.toString();
    }

    private record Raw(String name, String source) implements SourceComponent {
        @Override
        public Collection<? extends SourceComponent> included() {
            return List.of();
        }
    }
}
