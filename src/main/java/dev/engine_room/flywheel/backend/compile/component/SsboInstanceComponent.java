package dev.engine_room.flywheel.backend.compile.component;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.layout.Layout;
import dev.engine_room.flywheel.backend.engine.indirect.BufferBindings;
import dev.engine_room.flywheel.backend.glsl.generate.*;
import dev.engine_room.flywheel.lib.math.MoreMath;
import dev.engine_room.flywheel.lib.util.ResourceUtil;

import java.util.ArrayList;

public class SsboInstanceComponent extends InstanceAssemblerComponent {
    private final boolean shared;

    public SsboInstanceComponent(InstanceType<?> type) {
        super(type);
        shared = false;
    }

    public SsboInstanceComponent(InstanceType<?> type, int namespace) {
        super(type, "FlwInstance_" + namespace, "_flw_unpackInstance_" + namespace);
        shared = true;
    }

    @Override
    public String name() {
        return ResourceUtil.rl("ssbo_instance_assembler").toString();
    }

    @Override
    protected void generateUnpacking(GlslBuilder builder) {
        var fnBody = new GlslBlock();

        int uintCount = MoreMath.ceilingDiv(layout.byteSize(), 4);

        fnBody.add(GlslStmt.raw("uint base = " + UNPACK_ARG + (shared ? ";" : " * " + uintCount + "u;")));

        for (int i = 0; i < uintCount; i++) {
            fnBody.add(GlslStmt.raw("uint u" + i + " = _flw_instances[base + " + i + "u];"));
        }

        var unpackArgs = new ArrayList<GlslExpr>();
        for (Layout.Element element : layout.elements()) {
            unpackArgs.add(unpackElement(element));
        }

        fnBody.ret(GlslExpr.call(STRUCT_NAME, unpackArgs));

        if (!shared) builder._raw(
                "layout(std430, binding = " + BufferBindings.INSTANCE + ") restrict readonly buffer InstanceBuffer {\n"
                        + "    uint _flw_instances[];\n"
                        + "};");
        builder.blankLine();
        builder.function()
               .signature(FnSignature.create()
                                     .returnType(STRUCT_NAME)
                                     .name(UNPACK_FN_NAME)
                                     .arg("uint", UNPACK_ARG)
                                     .build())
               .body(fnBody);
    }

    @Override
    protected GlslExpr access(int uintOffset) {
        return GlslExpr.variable("u" + uintOffset);
    }
}
