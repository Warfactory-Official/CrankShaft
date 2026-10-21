package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.api.material.Transparency;
import org.lwjgl.opengl.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;

final class GuestOitContract {
    static final int FRONTMOST = -1;
    private final int[] drawBuffers;
    private final int[] ranks;
    private final Accumulate[] outputs;
    private final boolean[] blending;
    private final int[] blendFactors;

    private GuestOitContract(int[] drawBuffers, int[] ranks, Accumulate[] outputs, boolean[] blending,
                             int[] blendFactors) {
        this.drawBuffers = drawBuffers;
        this.ranks = ranks;
        this.outputs = outputs;
        this.blending = blending;
        this.blendFactors = blendFactors;
    }

    static GuestOitContract capture(GuestRenderPass pass) throws IOException {
        var maximum = Math.min(8, GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS));
        var nativeBuffers = pass.host.drawBuffers(pass.role());
        var authoredSource = pass.host.contracts().translucent(pass.shadow);
        var union = new LinkedHashSet<Integer>();
        if (authoredSource != null) for (var buffer : authoredSource.drawBuffers) union.add(buffer);
        else for (var buffer : nativeBuffers) union.add(buffer);
        var buffers = union.stream().mapToInt(Integer::intValue).toArray();
        var count = buffers.length;
        if (count == 0) throw new UnsupportedOperationException("OIT world program has no color outputs");
        if (count > maximum) throw new UnsupportedOperationException("OIT outputs exceed draw-buffer limit");
        pass.targets(buffers);
        try (var pack = pass.host.openPack()) {
            var properties = pass.host.contracts().properties.values;
            var group = pass.shadow ? "oit.shadow" : "oit.gbuffers";
            var explicit = properties.getProperty(group, properties.getProperty("oit"));
            if ("false".equalsIgnoreCase(explicit) || pass.shadow && !Boolean.parseBoolean(explicit))
                throw new UnsupportedOperationException("Pack disables " + group);
            var authored = authoredSource != null && Boolean.parseBoolean(explicit);
            var checkedForward = authored || pass.shadow ? null : GuestPackPatches.forwardOit(pack);
            var certified = authored || checkedForward != null;
            var ranks = authored ? Arrays.stream(properties.getProperty(group + ".coefficientRanks", "").split(","))
                                         .filter(rank -> !rank.isBlank())
                                         .mapToInt(rank -> Integer.parseInt(rank.trim())).toArray() : new int[count];
            if (!authored) Arrays.fill(ranks, count <= 2 ? 3 : count <= 4 ? 2 : 1);
            for (var rank : ranks) {
                if (rank < 1 || rank > 3) throw new IllegalStateException("OIT coefficient rank must be 1..3");
            }
            var outputs = new Accumulate[count];
            var blending = new boolean[count];
            var factors = new int[count * 4];
            var coefficientCount = 0;
            for (var slot = 0; slot < count; slot++) {
                var buffer = buffers[slot];
                if (authoredSource != null) {
                    pass.host.contracts().properties.blend(authoredSource.program, buffer,
                            pass.shadow ? ContractProperties.Blend.OFF : ContractProperties.Blend.material(
                                    Transparency.TRANSLUCENT)).apply(slot);
                }
                blending[slot] = GL30.glIsEnabledi(GL11.GL_BLEND, slot);
                factors[slot * 4] = GL30.glGetIntegeri(GL14.GL_BLEND_SRC_RGB, slot);
                factors[slot * 4 + 1] = GL30.glGetIntegeri(GL14.GL_BLEND_DST_RGB, slot);
                factors[slot * 4 + 2] = GL30.glGetIntegeri(GL14.GL_BLEND_SRC_ALPHA, slot);
                factors[slot * 4 + 3] = GL30.glGetIntegeri(GL14.GL_BLEND_DST_ALPHA, slot);
                var key = group + (pass.shadow ? ".shadowcolor" : ".colortex") + buffer;
                var declaration = properties.getProperty(key);
                if (declaration == null && properties.containsKey(key + ".format")) declaration = "frontmost";
                if (authored && declaration == null)
                    throw new UnsupportedOperationException("Undeclared OIT output colortex" + buffer);
                // AUSM leaves BSL's opaque VL/refraction outputs unblended; the checked recipe names those data targets.
                var frontmost = checkedForward != null && checkedForward.frontmost()
                                                                        .contains(buffer) && !blending[slot];
                var coefficient = authored ? "frontmost".equals(declaration) ? FRONTMOST : Integer.parseInt(declaration)
                        : frontmost ? FRONTMOST : coefficientCount++;
                if (coefficient < FRONTMOST || coefficient >= ranks.length)
                    throw new IllegalStateException("OIT coefficient set out of range");
                // AUSM packs logical colortex targets into consecutive physical attachment slots.
                var attachment = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0 + slot);
                var component = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, attachment,
                        GL30.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE);
                var texture = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, attachment,
                        GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                var nativeFormat = GL45.glGetTextureLevelParameteri(texture, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
                var normalized = component == GL30.GL_UNSIGNED_NORMALIZED;
                if (coefficient != FRONTMOST && !authored) {
                    var source = factors[slot * 4];
                    if (!blending[slot] || factors[slot * 4 + 1] != GL11.GL_ONE_MINUS_SRC_ALPHA
                            || source != GL11.GL_SRC_ALPHA && source != GL11.GL_ONE) {
                        throw new UnsupportedOperationException("colortex" + buffer + " is not a source-over signal");
                    }
                    if (!certified && !normalized)
                        throw new UnsupportedOperationException("Floating-point opacity contract is unchecked");
                    var alpha = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, attachment,
                            GL30.GL_FRAMEBUFFER_ATTACHMENT_ALPHA_SIZE);
                    if (!authored && alpha > 0 && !sourceOverAlpha(factors[slot * 4 + 2], factors[slot * 4 + 3])) {
                        throw new UnsupportedOperationException("colortex" + buffer + " alpha is not source-over");
                    }
                    if (!authored && component == GL31.GL_SIGNED_NORMALIZED)
                        throw new UnsupportedOperationException("Signed normalized native opacity is unsupported");
                }
                var defaultFormat = authored ? GL11.GL_RGBA8
                        : coefficient == FRONTMOST ? nativeFormat : GL30.GL_RGBA32F;
                var formatName = properties.getProperty(key + ".format");
                var requestedFormat = formatName == null ? null : format(formatName);
                // Coefficient reconstruction divides by the accumulated weight stored in alpha.
                if (coefficient != FRONTMOST && requestedFormat != null && requestedFormat.pixelFormat() != GL11.GL_RGBA) {
                    throw new UnsupportedOperationException(
                            "OIT coefficient accumulation needs RGBA storage: " + formatName);
                }
                var format = requestedFormat == null ? defaultFormat : requestedFormat.internalFormat();
                if (component == GL11.GL_INT || component == GL11.GL_UNSIGNED_INT) {
                    throw new UnsupportedOperationException("Integer OIT output is unsupported: colortex" + buffer);
                }
                outputs[slot] = new Accumulate(coefficient, format, !authored && factors[slot * 4] == GL11.GL_ONE,
                        !authored && normalized);
            }
            if (!authored) {
                ranks = new int[coefficientCount];
                Arrays.fill(ranks, coefficientCount <= 2 ? 3 : coefficientCount <= 4 ? 2 : 1);
            }
            var attachments = 0;
            for (var rank : ranks) attachments += GuestOitTargets.layers(rank);
            if (attachments > maximum)
                throw new UnsupportedOperationException("OIT coefficient outputs exceed GL draw-buffer limit");
            return new GuestOitContract(buffers, ranks, outputs, blending, factors);
        }
    }

    private static GuestColorFormat format(String name) {
        return GuestColorFormat.valueOf(name.strip().toUpperCase(Locale.ROOT));
    }

    private static boolean sourceOverAlpha(int source, int destination) {
        return source == GL11.GL_ONE && destination == GL11.GL_ONE_MINUS_SRC_ALPHA
                || source == GL11.GL_ONE_MINUS_DST_ALPHA && destination == GL11.GL_ONE
                || source == GL11.GL_ZERO && destination == GL11.GL_ONE;
    }

    int[] drawBuffers() {
        return drawBuffers;
    }

    int[] ranks() {
        return ranks;
    }

    int[] formats() {
        return Arrays.stream(outputs).mapToInt(Accumulate::format).toArray();
    }

    int slot(int buffer) {
        for (var slot = 0; slot < drawBuffers.length; slot++) if (drawBuffers[slot] == buffer) return slot;
        throw new IllegalStateException("OIT target is undeclared: colortex" + buffer);
    }

    boolean blending(int slot) {
        return blending[slot];
    }

    int blend(int slot, int component) {
        return blendFactors[slot * 4 + component];
    }

    Accumulate accumulate(int drawBuffer) {
        for (var slot = 0; slot < drawBuffers.length; slot++) {
            if (drawBuffers[slot] == drawBuffer) return outputs[slot];
        }
        throw new IllegalStateException("Unknown OIT draw buffer " + drawBuffer);
    }

    record Accumulate(int coefficient, int format, boolean premultiplied, boolean clampInput) {
    }
}
