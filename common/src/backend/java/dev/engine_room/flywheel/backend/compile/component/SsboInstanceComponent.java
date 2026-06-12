package dev.engine_room.flywheel.backend.compile.component;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.layout.Layout;
import dev.engine_room.flywheel.backend.engine.indirect.BufferBindings;
import dev.engine_room.flywheel.backend.glsl.generate.*;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.util.Mth;

import java.util.ArrayList;

/**
 * Unpacks an instance from the object SSBO. The argument is the instance's base UINT OFFSET into the buffer
 * (what the uber cull writes into the index table), NOT a serial index.
 */
public class SsboInstanceComponent extends InstanceAssemblerComponent {
    private final String structName;
    private final String fnName;
    private final boolean declareBuffer;

    public SsboInstanceComponent(InstanceType<?> type) {
        super(type);
        structName = STRUCT_NAME;
        fnName = UNPACK_FN_NAME;
        declareBuffer = true;
    }

    public SsboInstanceComponent(InstanceType<?> type, int typeId) {
        super(type);
        structName = STRUCT_NAME + "_" + typeId;
        fnName = UNPACK_FN_NAME + "_" + typeId;
        declareBuffer = false;
    }

    @Override
    public String name() {
        return ResourceUtil.rl("ssbo_instance_assembler").toString() + "/" + fnName;
    }

    @Override
    protected void generateUnpacking(GlslBuilder builder) {
        var fnBody = new GlslBlock();

        int uintCount = Mth.positiveCeilDiv(layout.byteSize(), 4);

        for (int i = 0; i < uintCount; i++) {
            fnBody.add(GlslStmt.raw("uint u" + i + " = _flw_instances[" + UNPACK_ARG + " + " + i + "u];"));
        }

        var unpackArgs = new ArrayList<GlslExpr>();
        for (Layout.Element element : layout.elements()) {
            unpackArgs.add(unpackElement(element));
        }

        fnBody.ret(GlslExpr.call(structName, unpackArgs));

        if (declareBuffer) {
            builder._raw(
                    "layout(std430, binding = " + BufferBindings.INSTANCE + ") restrict readonly buffer InstanceBuffer {\n"
                            + "    uint _flw_instances[];\n"
                            + "};");
            builder.blankLine();
        }
        builder.function()
               .signature(FnSignature.create()
                                     .returnType(structName)
                                     .name(fnName)
                                     .arg("uint", UNPACK_ARG)
                                     .build())
               .body(fnBody);
    }

    @Override
    protected GlslExpr access(int uintOffset) {
        return GlslExpr.variable("u" + uintOffset);
    }
}
