package dev.engine_room.flywheel.backend;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.engine_room.flywheel.api.layout.FloatRepr;
import dev.engine_room.flywheel.api.layout.Layout;
import dev.engine_room.flywheel.api.layout.LayoutBuilder;
import dev.engine_room.flywheel.backend.gl.array.VertexAttribute;
import dev.engine_room.flywheel.lib.vertex.FullVertexView;
import dev.engine_room.flywheel.lib.vertex.VertexView;

import java.util.List;

public final class InternalVertex {
    public static final Layout LAYOUT = LayoutBuilder.create()
                                                     .vector("position", FloatRepr.FLOAT, 3)
                                                     .vector("color", FloatRepr.NORMALIZED_UNSIGNED_BYTE, 4)
                                                     .vector("tex", FloatRepr.FLOAT, 2)
                                                     .vector("overlay", FloatRepr.SHORT, 2)
                                                     .vector("light", FloatRepr.UNSIGNED_SHORT, 2)
                                                     .vector("normal", FloatRepr.NORMALIZED_BYTE, 3)
                                                     .build();

    public static final List<VertexAttribute> ATTRIBUTES = LayoutAttributes.attributes(LAYOUT);
    public static final int STRIDE = LAYOUT.byteSize();

    // 26.2: Mojang VertexFormat equivalent of LAYOUT. Normal uses a 4-byte attribute (vs. 3 in LAYOUT)
    // to satisfy VertexFormat's 4-aligned stride requirement.
    public static final VertexFormat VERTEX_FORMAT = VertexFormat.builder(0)
                                                                 .addAttribute("Position", GpuFormat.RGB32_FLOAT)
                                                                 .addAttribute("Color", GpuFormat.RGBA8_UNORM)
                                                                 .addAttribute("UV0", GpuFormat.RG32_FLOAT)
                                                                 .addAttribute("UV1", GpuFormat.RG16_SINT)   // overlay
                                                                 .addAttribute("UV2", GpuFormat.RG16_UINT)   // light
                                                                 .addAttribute("Normal", 4, GpuFormat.RGB8_SNORM)
                                                                 .build();

    private InternalVertex() {
    }

    public static VertexView createVertexView() {
        return new FullVertexView();
    }
}
