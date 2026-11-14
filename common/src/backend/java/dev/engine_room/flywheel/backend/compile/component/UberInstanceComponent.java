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
    private final String name;
    private final String function;
    private final String unpack;
    private final boolean[] current;
    private final boolean @Nullable [] timeAnimated;

    /**
     * Also {@code bool _flw_timeAnimatedUber(uint typeId)}: {@link InstanceType#timeAnimated()} by type index.
     */
    public UberInstanceComponent(List<InstanceType<?>> types, ShaderSources sources) {
        this(types.size(), "uber_instance", "_flw_instanceVertexUber", InstanceAssemblerComponent.UNPACK_FN_NAME,
                new boolean[types.size()]);
        included.add(buffer("uber_instance_buffer", BufferBindings.INSTANCE, "InstanceBuffer",
                InstanceAssemblerComponent.BUFFER_NAME));
        for (int i = 0; i < types.size(); i++) {
            InstanceType<?> type = types.get(i);
            included.add(new InstanceStructComponent(type, "FlwInstance_" + i));
            included.add(new NamespacedVertex(InstanceStructComponent.typeShader(type, sources.get(type.vertexShader())),
                    i));
            included.add(new SsboInstanceComponent(type, i));
            timeAnimated[i] = type.timeAnimated();
        }
    }

    private UberInstanceComponent(int typeCount, String name, String function, String unpack,
                                  boolean @Nullable [] timeAnimated) {
        this.typeCount = typeCount;
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
        var history = new UberInstanceComponent(types.size(), "uber_instance_history",
                "_flw_prevInstanceVertexUber", InstanceAssemblerComponent.HISTORY_UNPACK_FN_NAME, null);
        history.included.add(buffer("uber_instance_history_buffer", binding, "_FlwPrevInstanceBuffer",
                InstanceAssemblerComponent.HISTORY_BUFFER_NAME));
        for (int i = 0; i < types.size(); i++) {
            history.current[i] = current.contains(types.get(i));
            if (!history.current[i]) {
                history.included.add(new SsboInstanceComponent(types.get(i), i,
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
        sb.append("    switch (typeId) {\n");
        for (int i = 0; i < typeCount; i++) {
            sb.append("    case ").append(i).append("u: {\n");
            sb.append("        FlwInstance_").append(i).append(" instance = ")
              .append(current[i] ? InstanceAssemblerComponent.UNPACK_FN_NAME : unpack).append('_').append(i)
              .append("(objectUint);\n");
            sb.append("        flw_instanceVertex_").append(i).append("(instance);\n");
            sb.append("        break;\n");
            sb.append("    }\n");
        }
        sb.append("    }\n");
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

    private record NamespacedVertex(SourceComponent file, int index) implements SourceComponent {
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
                    + "#define flw_instanceVertex flw_instanceVertex_" + index + "\n"
                    + file.source()
                    + "\n#undef FlwInstance\n"
                    + "#undef flw_instanceVertex\n";
        }
    }

    private record Raw(String name, String source) implements SourceComponent {
        @Override
        public Collection<? extends SourceComponent> included() {
            return List.of();
        }
    }
}
