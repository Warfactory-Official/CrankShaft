package dev.engine_room.flywheel.backend.compile.component;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.layout.Layout;
import dev.engine_room.flywheel.backend.glsl.generate.*;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.util.Mth;

import java.util.ArrayList;

public class BufferTextureInstanceComponent extends InstanceAssemblerComponent {
    private static final String[] SWIZZLE_SELECTORS = {"x", "y", "z", "w"};

    private final String sampler;
    private final String fnName;

    public BufferTextureInstanceComponent(InstanceType<?> type) {
        this(type, BUFFER_NAME, UNPACK_FN_NAME);
    }

    private BufferTextureInstanceComponent(InstanceType<?> type, String sampler, String fnName) {
        super(type);
        this.sampler = sampler;
        this.fnName = fnName;
    }

    /**
     * {@code _flw_unpackPrevInstance} over {@link #HISTORY_BUFFER_NAME}.
     */
    public static BufferTextureInstanceComponent history(InstanceType<?> type) {
        return new BufferTextureInstanceComponent(type, HISTORY_BUFFER_NAME, HISTORY_UNPACK_FN_NAME);
    }

    @Override
    public String name() {
        String name = ResourceUtil.rl("buffer_texture_instance_assembler").toString();
        return sampler.equals(BUFFER_NAME) ? name : name + "/" + sampler;
    }

    @Override
    protected void generateUnpacking(GlslBuilder builder) {
        var fnBody = new GlslBlock();

        int texels = Mth.positiveCeilDiv(layout.byteSize(), 16);

        fnBody.add(GlslStmt.raw("int base = " + UNPACK_ARG + " * " + texels + ";"));

        for (int i = 0; i < texels; i++) {
            // Fetch all the texels for the given instance ahead of time to simplify the unpacking generators.
            fnBody.add(GlslStmt.raw("uvec4 u" + i + " = texelFetch(" + sampler + ", base + " + i + ");"));
        }

        var unpackArgs = new ArrayList<GlslExpr>();
        for (Layout.Element element : layout.elements()) {
            unpackArgs.add(unpackElement(element));
        }

        fnBody.ret(GlslExpr.call(STRUCT_NAME, unpackArgs));

        builder.uniform()
               .type("usamplerBuffer")
               .name(sampler);
        builder.blankLine();
        builder.function()
               .signature(FnSignature.create()
                                     .returnType(STRUCT_NAME)
                                     .name(fnName)
                                     .arg("int", UNPACK_ARG)
                                     .build())
               .body(fnBody);
    }

    @Override
    protected GlslExpr access(int uintOffset) {
        return GlslExpr.variable("u" + (uintOffset >> 2))
                       .swizzle(SWIZZLE_SELECTORS[uintOffset & 3]);
    }
}
