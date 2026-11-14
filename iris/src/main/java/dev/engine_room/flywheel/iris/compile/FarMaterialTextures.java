package dev.engine_room.flywheel.iris.compile;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.engine_room.flywheel.backend.engine.BindlessSlots;
import dev.engine_room.flywheel.backend.gl.GlBindlessTable;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import net.irisshaders.iris.pbr.texture.PBRTextureManager;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.ARBBindlessTexture;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Original albedo, normal and specular textures per retained material slot. Refreshed after the far draw, outside
 * any render pass, so Iris can queue companion loading and a newly loaded holder replaces its default next frame.
 * The table borrows texture lifetimes from Minecraft and Iris; render thread only.
 */
public final class FarMaterialTextures {
    public static final int BINDING = 9;
    private static final int CHANNELS = 3;
    private static GpuTexture[] textures = new GpuTexture[0];
    private static int[] samplers = new int[0];
    private static long[] handles = new long[0];
    private static int buffer;

    private FarMaterialTextures() {
    }

    public static void refresh(IrisRenderingPipeline pipeline) {
        if (!GlCompat.SUPPORTS_BINDLESS_TEXTURES) return;
        int count = BindlessSlots.count();
        int length = (count + BindlessSlots.FIRST_MATERIAL_SLOT) * CHANNELS;
        boolean grown = length > handles.length;
        if (grown) {
            int capacity = Integer.highestOneBit(length - 1) << 1;
            textures = Arrays.copyOf(textures, capacity);
            samplers = Arrays.copyOf(samplers, capacity);
            handles = Arrays.copyOf(handles, capacity);
            if (buffer == 0) buffer = GL45C.glCreateBuffers();
            GL45C.glNamedBufferData(buffer, (long) capacity * Long.BYTES, GL15C.GL_DYNAMIC_DRAW);
        }
        var manager = Minecraft.getInstance().getTextureManager();
        for (int i = 0; i < count; i++) {
            int slot = i + BindlessSlots.FIRST_MATERIAL_SLOT;
            var albedo = (GlTexture) manager.getTexture(BindlessSlots.key(i).texture()).getTextureView().texture();
            var holder = PBRTextureManager.INSTANCE.getOrLoadHolder(albedo.glId());
            int base = slot * CHANNELS;
            handles[base] = GlBindlessTable.handle(slot);
            update(base + 1, holder.normalTexture().getTextureView().texture(), pipeline.getNormalSampler().getId());
            update(base + 2, holder.specularTexture().getTextureView().texture(),
                    pipeline.getSpecularSampler().getId());
        }
        GL45C.glNamedBufferSubData(buffer, 0, handles);
    }

    private static void update(int index, GpuTexture texture, int sampler) {
        if (textures[index] == texture && samplers[index] == sampler) return;
        long handle = ARBBindlessTexture.glGetTextureSamplerHandleARB(((GlTexture) texture).glId(), sampler);
        GlBindlessTable.makeResident(handle);
        textures[index] = texture;
        samplers[index] = sampler;
        handles[index] = handle;
    }

    public static void bind() {
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, BINDING, buffer);
    }

    /**
     * Redirects a pack fragment's reachable {@code normals}/{@code specular} samples to the retained material's maps,
     * declaring the table it reads; {@code uv}, if present, replaces their coordinate. Whether any sample was
     * redirected.
     */
    static boolean redirect(SingleASTTransformer<JobParameters> parser, TranslationUnit tree, Root root,
                            @Nullable String uv) {
        var reachable = FarVoxyNormals.reachable(root, "main");
        var samplers = root.nodeIndex.getStream(DeclarationMember.class)
                                     .filter(member -> !member.hasAncestor(FunctionDefinition.class))
                                     .filter(member -> Set.of("normals", "specular")
                                                          .contains(member.getName().getName()))
                                     .filter(member -> ASTPrinter.printSimple(
                                             member.getAncestor(TypeAndInitDeclaration.class)
                                                   .getType().getTypeSpecifier()).strip().equals("sampler2D"))
                                     .map(member -> member.getName().getName()).collect(Collectors.toSet());
        boolean redirected = false;
        for (var reference : root.nodeIndex.getStream(ReferenceExpression.class).toList()) {
            String name = reference.getIdentifier().getName();
            if (!samplers.contains(name) || !reachable.contains(reference.getAncestor(FunctionDefinition.class)))
                continue;
            var call = reference.getAncestor(FunctionCallExpression.class);
            if (uv != null && call != null && call.getFunctionName() != null
                    && call.getFunctionName().getName().startsWith("texture") && call.getParameters().size() >= 2) {
                call.getParameters().get(1).replaceByAndDelete(parser.parseExpression(root, uv));
                if (call.getFunctionName().getName().startsWith("textureGrad")) {
                    call.getParameters().get(2).replaceByAndDelete(parser.parseExpression(root, "dFdx(" + uv + ")"));
                    call.getParameters().get(3).replaceByAndDelete(parser.parseExpression(root, "dFdy(" + uv + ")"));
                }
            }
            reference.replaceByAndDelete(parser.parseExpression(root,
                    "_flw_farMaterialSampler(" + (name.equals("normals") ? "1u" : "2u") + ")"));
            redirected = true;
        }
        if (!redirected) return false;
        if (!GlCompat.SUPPORTS_BINDLESS_TEXTURES)
            throw new IllegalStateException("Retained material maps require GL_ARB_bindless_texture");
        GuestShaders.preamble(parser, tree, compilation -> compilation.requireExtension("GL_ARB_bindless_texture"));
        var library = new Compilation();
        library.version(GuestShaders.glslVersion());
        library.appendComponent(new RawSource("far layer material maps", SHADER));
        var declarations = parser.parseTranslationUnit(root, library.assembledSource());
        tree.injectNodes(ASTInjectionPoint.BEFORE_DECLARATIONS,
                declarations.getChildren().stream().map(node -> node.cloneInto(root)).toList());
        declarations.detachAndDelete();
        return true;
    }

    static final String SHADER = """
            flat in uint _flw_farTextureSlot;
            layout(std430, binding=9) readonly buffer FarMaterialHandles {
                uvec2 _flw_farMaterialHandles[];
            };
            sampler2D _flw_farMaterialSampler(uint channel) {
                return sampler2D(_flw_farMaterialHandles[_flw_farTextureSlot * 3u + channel]);
            }
            """;
}
