package dev.engine_room.flywheel.backend.compile.component;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.layout.Layout;
import dev.engine_room.flywheel.backend.engine.indirect.BufferBindings;
import dev.engine_room.flywheel.backend.glsl.generate.*;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;

/**
 * Unpacks an instance from the object SSBO. The argument is the instance's base UINT OFFSET into the buffer
 * (what the uber cull writes into the index table), NOT a serial index.
 */
public class SsboInstanceComponent extends InstanceAssemblerComponent {
    private final String structName;
    private final String fnName;
    private final String buffer;
    private final int binding;
    // null: the caller declares the buffer.
    private final @Nullable String block;

    public SsboInstanceComponent(InstanceType<?> type) {
        this(type, STRUCT_NAME, UNPACK_FN_NAME, BUFFER_NAME, BufferBindings.INSTANCE, "InstanceBuffer");
    }

    public SsboInstanceComponent(InstanceType<?> type, int typeId) {
        this(type, typeId, BUFFER_NAME, UNPACK_FN_NAME);
    }

    /**
     * Uber {@code typeId} unpacking from {@code buffer} as {@code fnPrefix_typeId}; the caller declares the buffer.
     */
    public SsboInstanceComponent(InstanceType<?> type, int typeId, String buffer, String fnPrefix) {
        this(type, STRUCT_NAME + "_" + typeId, fnPrefix + "_" + typeId, buffer, -1, null);
    }

    private SsboInstanceComponent(InstanceType<?> type, String structName, String fnName, String buffer, int binding,
                                  @Nullable String block) {
        super(type);
        this.structName = structName;
        this.fnName = fnName;
        this.buffer = buffer;
        this.binding = binding;
        this.block = block;
    }

    /**
     * {@code _flw_unpackPrevInstance} over {@link #HISTORY_BUFFER_NAME} at {@code binding}.
     */
    public static SsboInstanceComponent history(InstanceType<?> type, int binding) {
        return new SsboInstanceComponent(type, STRUCT_NAME, HISTORY_UNPACK_FN_NAME, HISTORY_BUFFER_NAME, binding,
                "_FlwPrevInstanceBuffer");
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
            fnBody.add(GlslStmt.raw("uint u" + i + " = " + buffer + "[" + UNPACK_ARG + " + " + i + "u];"));
        }

        var unpackArgs = new ArrayList<GlslExpr>();
        for (Layout.Element element : layout.elements()) {
            unpackArgs.add(unpackElement(element));
        }

        fnBody.ret(GlslExpr.call(structName, unpackArgs));

        if (block != null) {
            builder._raw(
                    "layout(std430, binding = " + binding + ") restrict readonly buffer " + block + " {\n"
                            + "    uint " + buffer + "[];\n"
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
