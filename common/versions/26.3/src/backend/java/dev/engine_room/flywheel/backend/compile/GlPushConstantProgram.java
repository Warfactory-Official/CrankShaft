package dev.engine_room.flywheel.backend.compile;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.opengl.GlProgram;
import com.mojang.renderpearl.backend.opengl.Uniform;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL31C;

final class GlPushConstantProgram extends GlProgram {
    private static final Identifier SODIUM_VERTEX = Identifier.fromNamespaceAndPath("sodium",
            "blocks/block_layer_opaque");
    private final Uniform.Ubo pushConstants = new Uniform.Ubo(0);

    GlPushConstantProgram(int handle, String label) {
        super(handle, label);
        int block = GL31C.glGetUniformBlockIndex(handle, "_push_constants");
        if (block == GL31C.GL_INVALID_INDEX)
            throw new IllegalStateException("Missing lowered push-constant block: " + label);
        GL31C.glUniformBlockBinding(handle, block, pushConstants.blockBinding());
    }

    static boolean supports(RenderPipeline pipeline) {
        return pipeline.pushConstantSize() == 20 && SODIUM_VERTEX.equals(pipeline.getShaders().get(ShaderType.VERTEX));
    }

    static void configure(Compilation compilation, boolean vulkan) {
        compilation.define("VULKAN", "100");
        if (!vulkan) {
            compilation.define("push_constant", "std140");
            compilation.define("PC", "_push_constants");
        }
    }

    @Override
    public Uniform.Ubo pushConstant() {
        return pushConstants;
    }
}
