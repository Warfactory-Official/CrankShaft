package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.component.InstanceStructComponent;
import dev.engine_room.flywheel.backend.compile.component.SsboInstanceComponent;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class GuestInstanceShader {
    static String source(List<InstanceType<?>> types) {
        var result = new StringBuilder("""
                layout(std430,binding=0) readonly buffer _FlwGuestTypeBases { uvec4 _flw_typeBases[]; };
                layout(std430,binding=1) readonly buffer _FlwGuestInstances { uint _flw_instances[]; };
                uint _flw_instanceType;
                """);
        var included = new HashSet<String>();
        for (var index = 0; index < types.size(); index++) {
            var type = types.get(index);
            var vertex = FlwPrograms.SOURCES.get(type.vertexShader());
            for (var dependency : vertex.included()) append(dependency, included, result);
            result.append(new InstanceStructComponent(type, "FlwInstance_" + index).source());
            result.append(new SsboInstanceComponent(type, index).source());
            result.append("\n#define FlwInstance FlwInstance_").append(index)
                  .append("\n#define flw_instanceVertex flw_instanceVertex_").append(index).append('\n')
                  .append(vertex.source()).append("\n#undef FlwInstance\n#undef flw_instanceVertex\n");
        }
        result.append("void _flw_instanceVertexUber(uint objectUint) { switch (_flw_instanceType) {\n");
        for (var index = 0; index < types.size(); index++) {
            result.append("case ").append(index).append("u: flw_instanceVertex_").append(index)
                  .append("(_flw_unpackInstance_").append(index).append("(objectUint)); break;\n");
        }
        return result.append("} }\n").toString();
    }

    private static void append(SourceComponent source, Set<String> included, StringBuilder result) {
        if (!included.add(source.name())) return;
        for (var dependency : source.included()) append(dependency, included, result);
        result.append(source.source()).append('\n');
    }
}
