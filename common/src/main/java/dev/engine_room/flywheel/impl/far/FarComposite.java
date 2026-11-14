package dev.engine_room.flywheel.impl.far;

import dev.engine_room.flywheel.backend.compile.AsyncGlProgram;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import org.jspecify.annotations.Nullable;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.stream.Collectors;

/**
 * Far visuals into a host's targets from inside the host's own GL work, each far fragment re-projected into the host's
 * depth and tested against it; and into the main target where vanilla drew nearer than the vanilla radius, for far
 * pixels in front of it. Every state it sets is read back first and restored. Render thread.
 */
final class FarComposite {
    private static final int COLOR_UNIT = 10;
    private static final int DEPTH_UNIT = 11;
    private static final int MASK_UNIT = 12;
    private static final int[] HOST_UNITS = {COLOR_UNIT, DEPTH_UNIT};
    private static final int[] MAIN_UNITS = {COLOR_UNIT, DEPTH_UNIT, MASK_UNIT};
    private static @Nullable AsyncGlProgram hostBuild;
    private static @Nullable AsyncGlProgram mainBuild;
    private static int hostProgram;
    private static int mainProgram;
    private static @Nullable HostUniforms hostUniforms;
    private static @Nullable MainUniforms mainUniforms;
    private static int hostFramebuffer;
    private static int mainFramebuffer;
    private static int vertexArray;
    private static int sampler;
    // The main depth as vanilla left it, sampled while the main depth is attached.
    private static int mainDepthCopy;
    private static int copyFormat;
    private static int copyWidth;
    private static int copyHeight;

    private FarComposite() {
    }

    static void draw(FarHost host, int color, int depth, Matrix4fc inverseProjection) {
        if (!ready()) return;
        attach(hostFramebuffer, host.color(), host.depth(),
                host.stencil() >= 0 ? GL30C.GL_DEPTH_STENCIL_ATTACHMENT : GL30C.GL_DEPTH_ATTACHMENT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Saved saved = Saved.read(stack, HOST_UNITS, host.stencil() >= 0);
            begin(hostFramebuffer, hostProgram, host.width(), host.height(), color, depth);
            GL20C.glUniformMatrix4fv(hostUniforms.inverseProjection, false,
                    inverseProjection.get(stack.mallocFloat(16)));
            GL20C.glUniformMatrix4fv(hostUniforms.viewToHostClip, false,
                    host.viewToHostClip().get(stack.mallocFloat(16)));
            GL20C.glUniform2f(hostUniforms.size, host.width(), host.height());
            GL20C.glUniform1i(hostUniforms.zeroToOne, host.zeroToOne() ? 1 : 0);
            GL11C.glDepthFunc(host.reverseZ() ? GL11C.GL_GEQUAL : GL11C.GL_LEQUAL);
            if (host.stencil() >= 0) {
                GL11C.glEnable(GL11C.GL_STENCIL_TEST);
                GL11C.glStencilFunc(GL11C.GL_EQUAL, host.stencil(), 0xFF);
                GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_KEEP);
                GL11C.glStencilMask(0);
            }
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
            saved.restore();
        }
    }

    /**
     * {@code mainDepth}: zero-to-one reversed-Z, as vanilla leaves it; {@code viewToMainClip} and
     * {@code mainInverseViewProjection} (camera relative): the main projection.
     */
    static void drawMain(int mainColor, int mainDepth, int mainDepthFormat, int width, int height, int color, int depth,
                         Matrix4fc inverseProjection, Matrix4fc viewToMainClip, Matrix4fc mainInverseViewProjection,
                         float vanillaRadius) {
        if (!ready()) return;
        copyMainDepth(mainDepth, mainDepthFormat, width, height);
        boolean stencil = mainDepthFormat == GL30C.GL_DEPTH24_STENCIL8 || mainDepthFormat == GL30C.GL_DEPTH32F_STENCIL8;
        attach(mainFramebuffer, mainColor, mainDepth,
                stencil ? GL30C.GL_DEPTH_STENCIL_ATTACHMENT : GL30C.GL_DEPTH_ATTACHMENT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Saved saved = Saved.read(stack, MAIN_UNITS, false);
            begin(mainFramebuffer, mainProgram, width, height, color, depth);
            GL45C.glBindTextureUnit(MASK_UNIT, mainDepthCopy);
            GL33C.glBindSampler(MASK_UNIT, sampler);
            GL20C.glUniformMatrix4fv(mainUniforms.inverseProjection, false,
                    inverseProjection.get(stack.mallocFloat(16)));
            GL20C.glUniformMatrix4fv(mainUniforms.viewToMainClip, false,
                    viewToMainClip.get(stack.mallocFloat(16)));
            GL20C.glUniformMatrix4fv(mainUniforms.mainInverseViewProjection, false,
                    mainInverseViewProjection.get(stack.mallocFloat(16)));
            GL20C.glUniform2f(mainUniforms.size, width, height);
            GL20C.glUniform1f(mainUniforms.vanillaRadius, vanillaRadius);
            GL11C.glDepthFunc(GL11C.GL_GREATER);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
            saved.restore();
        }
    }

    static void close() {
        if (hostBuild != null) {
            hostBuild.close();
            mainBuild.close();
            hostBuild = null;
            mainBuild = null;
            GL30C.glDeleteFramebuffers(hostFramebuffer);
            GL30C.glDeleteFramebuffers(mainFramebuffer);
            GL30C.glDeleteVertexArrays(vertexArray);
            GL33C.glDeleteSamplers(sampler);
            hostProgram = 0;
        }
        if (mainDepthCopy != 0) {
            GL11C.glDeleteTextures(mainDepthCopy);
            mainDepthCopy = 0;
        }
    }

    // The state both composites share: depth test with writes, no stencil, blend, culling or scissor.
    private static void begin(int framebuffer, int program, int width, int height, int color, int depth) {
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL11C.glViewport(0, 0, width, height);
        GL20C.glUseProgram(program);
        GL30C.glBindVertexArray(vertexArray);
        GL45C.glBindTextureUnit(COLOR_UNIT, color);
        GL33C.glBindSampler(COLOR_UNIT, sampler);
        GL45C.glBindTextureUnit(DEPTH_UNIT, depth);
        GL33C.glBindSampler(DEPTH_UNIT, sampler);
        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(true);
        GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        GL30C.glDisablei(GL11C.GL_BLEND, 0);
        GL30C.glColorMaski(0, true, true, true, true);
        GL11C.glDisable(GL11C.GL_CULL_FACE);
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
    }

    private static void copyMainDepth(int mainDepth, int format, int width, int height) {
        if (mainDepthCopy == 0 || copyFormat != format || copyWidth != width || copyHeight != height) {
            if (mainDepthCopy != 0) {
                GL11C.glDeleteTextures(mainDepthCopy);
            }
            mainDepthCopy = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(mainDepthCopy, 1, format, width, height);
            copyFormat = format;
            copyWidth = width;
            copyHeight = height;
        }
        GL43C.glCopyImageSubData(mainDepth, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, mainDepthCopy, GL11C.GL_TEXTURE_2D, 0, 0,
                0, 0, width, height, 1);
    }

    private static boolean ready() {
        if (hostBuild == null) create();
        var host = hostBuild.get();
        var main = mainBuild.get();
        if (host == null || main == null) return false;
        hostProgram = host.handle();
        mainProgram = main.handle();
        return true;
    }

    private static void create() {
        hostBuild = program("far composite", "composite.frag", false);
        mainBuild = program("far main composite", "composite_main.frag", true);
        hostFramebuffer = GL45C.glCreateFramebuffers();
        mainFramebuffer = GL45C.glCreateFramebuffers();
        vertexArray = GL45C.glCreateVertexArrays();
        sampler = GL45C.glCreateSamplers();
        GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_WRAP_S, GL14C.GL_CLAMP_TO_EDGE);
        GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_WRAP_T, GL14C.GL_CLAMP_TO_EDGE);
    }

    private static AsyncGlProgram program(String name, String fragmentSource, boolean main) {
        var vertex = new Compilation();
        vertex.version(GlslVersion.V330);
        vertex.appendComponent(new RawSource("far composite vertex", source("composite.vert")));
        var fragment = new Compilation();
        fragment.version(GlslVersion.V330);
        fragment.appendComponent(new RawSource(name, source(fragmentSource)));
        return AsyncGlProgram.graphics(name, vertex, fragment, program -> {
            int handle = program.handle();
            GL41C.glProgramUniform1i(handle, program.getUniformLocation("farColor"), COLOR_UNIT);
            GL41C.glProgramUniform1i(handle, program.getUniformLocation("farDepth"), DEPTH_UNIT);
            if (main) {
                GL41C.glProgramUniform1i(handle, program.getUniformLocation("mainDepth"), MASK_UNIT);
                mainUniforms = new MainUniforms(program.getUniformLocation("farInverseProjection"),
                        program.getUniformLocation("viewToMainClip"),
                        program.getUniformLocation("mainInverseViewProjection"),
                        program.getUniformLocation("mainSize"), program.getUniformLocation("vanillaRadius"));
            } else {
                hostUniforms = new HostUniforms(program.getUniformLocation("farInverseProjection"),
                        program.getUniformLocation("viewToHostClip"), program.getUniformLocation("hostSize"),
                        program.getUniformLocation("hostZeroToOne"));
            }
        });
    }

    // Every draw: hosts recreate their targets, and a recreated texture may reuse the deleted one's name.
    private static void attach(int framebuffer, int color, int depth, int depthAttachment) {
        GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_COLOR_ATTACHMENT0, color, 0);
        GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, 0, 0);
        GL45C.glNamedFramebufferTexture(framebuffer, depthAttachment, depth, 0);
        int status = GL45C.glCheckNamedFramebufferStatus(framebuffer, GL30C.GL_DRAW_FRAMEBUFFER);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Far composite framebuffer incomplete: 0x" + Integer.toHexString(status));
        }
    }

    private static void toggle(int capability, boolean enabled) {
        if (enabled) {
            GL11C.glEnable(capability);
        } else {
            GL11C.glDisable(capability);
        }
    }

    private static String source(String name) {
        try (BufferedReader reader = Minecraft.getInstance().getResourceManager()
                                              .getResourceOrThrow(ResourceUtil.rl("flywheel/far/" + name))
                                              .openAsReader()) {
            return reader.lines().collect(Collectors.joining("\n", "", "\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record HostUniforms(int inverseProjection, int viewToHostClip, int size, int zeroToOne) {
    }

    private record MainUniforms(int inverseProjection, int viewToMainClip, int mainInverseViewProjection, int size,
                                int vanillaRadius) {
    }

    // Every state a composite sets, read back before it.
    private record Saved(int framebuffer, int[] viewport, int program, int vertexArray, int activeTexture,
                         int[] units, int[] textures, int[] samplers, boolean depthTest, int depthFunc,
                         boolean depthMask,
                         boolean stencilTest, @Nullable Stencil stencil, boolean blend, ByteBuffer colorMask,
                         boolean cull, boolean scissor) {
        static Saved read(MemoryStack stack, int[] units, boolean changesStencil) {
            int[] viewport = new int[4];
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
            int activeTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            int[] textures = new int[units.length];
            int[] samplers = new int[units.length];
            for (int i = 0; i < units.length; i++) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + units[i]);
                textures[i] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
                samplers[i] = GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
            }
            GL13C.glActiveTexture(activeTexture);
            ByteBuffer colorMask = stack.malloc(4);
            GL30C.glGetBooleani_v(GL11C.GL_COLOR_WRITEMASK, 0, colorMask);
            return new Saved(GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING), viewport,
                    GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM), GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING),
                    activeTexture, units, textures, samplers, GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST),
                    GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC), GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK),
                    GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST), changesStencil ? Stencil.read() : null,
                    GL30C.glIsEnabledi(GL11C.GL_BLEND, 0), colorMask,
                    GL11C.glIsEnabled(GL11C.GL_CULL_FACE), GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST));
        }

        void restore() {
            toggle(GL11C.GL_SCISSOR_TEST, scissor);
            toggle(GL11C.GL_CULL_FACE, cull);
            GL30C.glColorMaski(0, colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0,
                    colorMask.get(3) != 0);
            if (blend) {
                GL30C.glEnablei(GL11C.GL_BLEND, 0);
            }
            if (stencil != null) stencil.restore();
            toggle(GL11C.GL_STENCIL_TEST, stencilTest);
            GL11C.glDepthMask(depthMask);
            GL11C.glDepthFunc(depthFunc);
            toggle(GL11C.GL_DEPTH_TEST, depthTest);
            for (int i = 0; i < units.length; i++) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + units[i]);
                GL33C.glBindSampler(units[i], samplers[i]);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures[i]);
            }
            GL13C.glActiveTexture(activeTexture);
            GL30C.glBindVertexArray(vertexArray);
            GL20C.glUseProgram(program);
            GL11C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
        }
    }

    private record Stencil(StencilFace front, StencilFace back) {
        static Stencil read() {
            return new Stencil(StencilFace.read(GL11C.GL_FRONT), StencilFace.read(GL11C.GL_BACK));
        }

        void restore() {
            front.restore(GL11C.GL_FRONT);
            back.restore(GL11C.GL_BACK);
        }
    }

    private record StencilFace(int func, int ref, int valueMask, int writeMask, int fail, int depthFail, int pass) {
        static StencilFace read(int face) {
            boolean front = face == GL11C.GL_FRONT;
            return new StencilFace(
                    GL11C.glGetInteger(front ? GL11C.GL_STENCIL_FUNC : GL20C.GL_STENCIL_BACK_FUNC),
                    GL11C.glGetInteger(front ? GL11C.GL_STENCIL_REF : GL20C.GL_STENCIL_BACK_REF),
                    GL11C.glGetInteger(front ? GL11C.GL_STENCIL_VALUE_MASK : GL20C.GL_STENCIL_BACK_VALUE_MASK),
                    GL11C.glGetInteger(front ? GL11C.GL_STENCIL_WRITEMASK : GL20C.GL_STENCIL_BACK_WRITEMASK),
                    GL11C.glGetInteger(front ? GL11C.GL_STENCIL_FAIL : GL20C.GL_STENCIL_BACK_FAIL),
                    GL11C.glGetInteger(front ? GL11C.GL_STENCIL_PASS_DEPTH_FAIL
                            : GL20C.GL_STENCIL_BACK_PASS_DEPTH_FAIL),
                    GL11C.glGetInteger(front ? GL11C.GL_STENCIL_PASS_DEPTH_PASS
                            : GL20C.GL_STENCIL_BACK_PASS_DEPTH_PASS));
        }

        void restore(int face) {
            GL20C.glStencilFuncSeparate(face, func, ref, valueMask);
            GL20C.glStencilOpSeparate(face, fail, depthFail, pass);
            GL20C.glStencilMaskSeparate(face, writeMask);
        }
    }
}
