package dev.engine_room.flywheel.lib.instance;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.layout.FloatRepr;
import dev.engine_room.flywheel.api.layout.IntegerRepr;
import dev.engine_room.flywheel.api.layout.LayoutBuilder;
import dev.engine_room.flywheel.lib.util.ExtraMemoryOps;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.system.MemoryUtil;

public final class InstanceTypes {
    private static final Matrix4f IDENTITY_M4 = new Matrix4f();
    private static final Matrix3f IDENTITY_M3 = new Matrix3f();
    private static final Quaternionf IDENTITY_QUAT = new Quaternionf();
    public static final InstanceType<TransformedInstance> TRANSFORMED = SimpleInstanceType.builder(
                                                                                                  TransformedInstance::new)
                                                                                          .layout(LayoutBuilder.create()
                                                                                                               .vector("color",
                                                                                                                       FloatRepr.NORMALIZED_UNSIGNED_BYTE,
                                                                                                                       4)
                                                                                                               .vector("overlay",
                                                                                                                       IntegerRepr.SHORT,
                                                                                                                       2)
                                                                                                               .vector("light",
                                                                                                                       FloatRepr.UNSIGNED_SHORT,
                                                                                                                       2)
                                                                                                               .matrix("pose",
                                                                                                                       FloatRepr.FLOAT,
                                                                                                                       4)
                                                                                                               .build())
                                                                                          .seed(ptr -> {
                                                                                              MemoryUtil.memPutInt(
                                                                                                      ptr + ColoredLitInstance.OFF_RGBA,
                                                                                                      0xFFFFFFFF);
                                                                                              ExtraMemoryOps.put2x16(
                                                                                                      ptr + ColoredLitOverlayInstance.OFF_OVERLAY,
                                                                                                      OverlayTexture.NO_OVERLAY);
                                                                                              ExtraMemoryOps.putMatrix4f(
                                                                                                      ptr + TransformedInstance.OFF_POSE,
                                                                                                      IDENTITY_M4);
                                                                                          })
                                                                                          .vertexShader(ResourceUtil.rl(
                                                                                                  "instance/transformed.vert"))
                                                                                          .cullShader(ResourceUtil.rl(
                                                                                                  "instance/cull/transformed.glsl"))
                                                                                          .build();
    public static final InstanceType<PosedInstance> POSED = SimpleInstanceType.builder(PosedInstance::new)
                                                                              .layout(LayoutBuilder.create()
                                                                                                   .vector("color",
                                                                                                           FloatRepr.NORMALIZED_UNSIGNED_BYTE,
                                                                                                           4)
                                                                                                   .vector("overlay",
                                                                                                           IntegerRepr.SHORT,
                                                                                                           2)
                                                                                                   .vector("light",
                                                                                                           FloatRepr.UNSIGNED_SHORT,
                                                                                                           2)
                                                                                                   .matrix("pose",
                                                                                                           FloatRepr.FLOAT,
                                                                                                           4)
                                                                                                   .matrix("normal",
                                                                                                           FloatRepr.FLOAT,
                                                                                                           3)
                                                                                                   .build())
                                                                              .seed(ptr -> {
                                                                                  MemoryUtil.memPutInt(ptr + ColoredLitInstance.OFF_RGBA, 0xFFFFFFFF);
                                                                                  ExtraMemoryOps.put2x16(ptr + ColoredLitOverlayInstance.OFF_OVERLAY,
                                                                                          OverlayTexture.NO_OVERLAY);
                                                                                  ExtraMemoryOps.putMatrix4f(ptr + PosedInstance.OFF_POSE,
                                                                                          IDENTITY_M4);
                                                                                  ExtraMemoryOps.putMatrix3f(ptr + PosedInstance.OFF_NORMAL,
                                                                                          IDENTITY_M3);
                                                                              })
                                                                              .vertexShader(ResourceUtil.rl(
                                                                                      "instance/posed.vert"))
                                                                              .cullShader(ResourceUtil.rl(
                                                                                      "instance/cull/posed.glsl"))
                                                                              .build();
    public static final InstanceType<OrientedInstance> ORIENTED = SimpleInstanceType.builder(OrientedInstance::new)
                                                                                    .layout(LayoutBuilder.create()
                                                                                                         .vector("color",
                                                                                                                 FloatRepr.NORMALIZED_UNSIGNED_BYTE,
                                                                                                                 4)
                                                                                                         .vector("overlay",
                                                                                                                 IntegerRepr.SHORT,
                                                                                                                 2)
                                                                                                         .vector("light",
                                                                                                                 FloatRepr.UNSIGNED_SHORT,
                                                                                                                 2)
                                                                                                         .vector("position",
                                                                                                                 FloatRepr.FLOAT,
                                                                                                                 3)
                                                                                                         .vector("pivot",
                                                                                                                 FloatRepr.FLOAT,
                                                                                                                 3)
                                                                                                         .vector("rotation",
                                                                                                                 FloatRepr.FLOAT,
                                                                                                                 4)
                                                                                                         .build())
                                                                                    .seed(ptr -> {
                                                                                        MemoryUtil.memPutInt(
                                                                                                ptr + ColoredLitInstance.OFF_RGBA,
                                                                                                0xFFFFFFFF);
                                                                                        ExtraMemoryOps.put2x16(
                                                                                                ptr + ColoredLitOverlayInstance.OFF_OVERLAY,
                                                                                                OverlayTexture.NO_OVERLAY);
                                                                                        ExtraMemoryOps.putVector3f(
                                                                                                ptr + OrientedInstance.OFF_PIVOT,
                                                                                                0.5f, 0.5f, 0.5f);
                                                                                        ExtraMemoryOps.putQuaternionf(
                                                                                                ptr + OrientedInstance.OFF_ROT,
                                                                                                IDENTITY_QUAT);
                                                                                    })
                                                                                    .vertexShader(ResourceUtil.rl(
                                                                                            "instance/oriented.vert"))
                                                                                    .cullShader(ResourceUtil.rl(
                                                                                            "instance/cull/oriented.glsl"))
                                                                                    .build();
    /**
     * Door variants whose animated panels slide into their frame.
     */
    public static final InstanceType<ClipTransformedInstance> CLIP_TRANSFORMED = SimpleInstanceType.builder(
                                                                                                           ClipTransformedInstance::new)
                                                                                                   .layout(LayoutBuilder.create()
                                                                                                                        .vector("color",
                                                                                                                                FloatRepr.NORMALIZED_UNSIGNED_BYTE,
                                                                                                                                4)
                                                                                                                        .vector("overlay",
                                                                                                                                IntegerRepr.SHORT,
                                                                                                                                2)
                                                                                                                        .vector("light",
                                                                                                                                FloatRepr.UNSIGNED_SHORT,
                                                                                                                                2)
                                                                                                                        .matrix("pose",
                                                                                                                                FloatRepr.FLOAT,
                                                                                                                                4)
                                                                                                                        .vector("slide",
                                                                                                                                FloatRepr.FLOAT,
                                                                                                                                3)
                                                                                                                        .vector("plane",
                                                                                                                                FloatRepr.FLOAT,
                                                                                                                                4)
                                                                                                                        .build())
                                                                                                   .seed(ptr -> {
                                                                                                       MemoryUtil.memPutInt(
                                                                                                               ptr + ColoredLitInstance.OFF_RGBA,
                                                                                                               0xFFFFFFFF);
                                                                                                       ExtraMemoryOps.put2x16(
                                                                                                               ptr + ColoredLitOverlayInstance.OFF_OVERLAY,
                                                                                                               OverlayTexture.NO_OVERLAY);
                                                                                                       ExtraMemoryOps.putMatrix4f(
                                                                                                               ptr + TransformedInstance.OFF_POSE,
                                                                                                               IDENTITY_M4);
                                                                                                   })
                                                                                                   .vertexShader(
                                                                                                           ResourceUtil.rl(
                                                                                                                   "instance/clip_transformed.vert"))
                                                                                                   .cullShader(
                                                                                                           ResourceUtil.rl(
                                                                                                                   "instance/cull/clip_transformed.glsl"))
                                                                                                   .build();
    public static final InstanceType<UvTransformedInstance> UV_TRANSFORMED = SimpleInstanceType.builder(
                                                                                                       UvTransformedInstance::new)
                                                                                               .layout(LayoutBuilder.create()
                                                                                                                    .vector("color",
                                                                                                                            FloatRepr.NORMALIZED_UNSIGNED_BYTE,
                                                                                                                            4)
                                                                                                                    .vector("overlay",
                                                                                                                            IntegerRepr.SHORT,
                                                                                                                            2)
                                                                                                                    .vector("light",
                                                                                                                            FloatRepr.UNSIGNED_SHORT,
                                                                                                                            2)
                                                                                                                    .matrix("pose",
                                                                                                                            FloatRepr.FLOAT,
                                                                                                                            4)
                                                                                                                    .vector("uvRegion",
                                                                                                                            FloatRepr.FLOAT,
                                                                                                                            4)
                                                                                                                    .build())
                                                                                               .seed(ptr -> {
                                                                                                   MemoryUtil.memPutInt(
                                                                                                           ptr + ColoredLitInstance.OFF_RGBA,
                                                                                                           0xFFFFFFFF);
                                                                                                   ExtraMemoryOps.put2x16(
                                                                                                           ptr + ColoredLitOverlayInstance.OFF_OVERLAY,
                                                                                                           OverlayTexture.NO_OVERLAY);
                                                                                                   ExtraMemoryOps.putMatrix4f(
                                                                                                           ptr + TransformedInstance.OFF_POSE,
                                                                                                           IDENTITY_M4);
                                                                                                   // uvRegion default MUST be the identity remap (0,0,1,1); a zero-fill collapses every UV to a point.
                                                                                                   ExtraMemoryOps.putVector4f(
                                                                                                           ptr + UvTransformedInstance.OFF_UV_REGION,
                                                                                                           0.0f, 0.0f,
                                                                                                           1.0f, 1.0f);
                                                                                               })
                                                                                               .vertexShader(
                                                                                                       ResourceUtil.rl(
                                                                                                               "instance/transformed_uv.vert"))
                                                                                               .cullShader(
                                                                                                       ResourceUtil.rl(
                                                                                                               "instance/cull/transformed.glsl"))
                                                                                               .build();
    public static final InstanceType<BillboardInstance> BILLBOARD = SimpleInstanceType.builder(BillboardInstance::new)
                                                                                      .layout(LayoutBuilder.create()
                                                                                                           .vector("color",
                                                                                                                   FloatRepr.NORMALIZED_UNSIGNED_BYTE,
                                                                                                                   4)
                                                                                                           .vector("overlay",
                                                                                                                   IntegerRepr.SHORT,
                                                                                                                   2)
                                                                                                           .vector("light",
                                                                                                                   FloatRepr.UNSIGNED_SHORT,
                                                                                                                   2)
                                                                                                           .vector("position",
                                                                                                                   FloatRepr.FLOAT,
                                                                                                                   3)
                                                                                                           .scalar("size",
                                                                                                                   FloatRepr.FLOAT)
                                                                                                           .vector("uvRegion",
                                                                                                                   FloatRepr.FLOAT,
                                                                                                                   4)
                                                                                                           .build())
                                                                                      .seed(ptr -> {
                                                                                          MemoryUtil.memPutInt(
                                                                                                  ptr + ColoredLitInstance.OFF_RGBA,
                                                                                                  0xFFFFFFFF);
                                                                                          ExtraMemoryOps.put2x16(
                                                                                                  ptr + ColoredLitOverlayInstance.OFF_OVERLAY,
                                                                                                  OverlayTexture.NO_OVERLAY);
                                                                                          ExtraMemoryOps.putVector4f(
                                                                                                  ptr + BillboardInstance.OFF_UV_REGION,
                                                                                                  0.0f, 0.0f,
                                                                                                  1.0f, 1.0f);
                                                                                      })
                                                                                      .vertexShader(ResourceUtil.rl(
                                                                                              "instance/billboard.vert"))
                                                                                      .cullShader(ResourceUtil.rl(
                                                                                              "instance/cull/billboard.glsl"))
                                                                                      .build();
    /**
     * One nameplate element, billboarded about a shared anchor; the seed writes {@code size} (0,0).
     */
    public static final InstanceType<GlyphInstance> GLYPH = SimpleInstanceType.builder(GlyphInstance::new)
                                                                              .layout(LayoutBuilder.create()
                                                                                                   .vector("color",
                                                                                                           FloatRepr.NORMALIZED_UNSIGNED_BYTE,
                                                                                                           4)
                                                                                                   .vector("light",
                                                                                                           FloatRepr.UNSIGNED_SHORT,
                                                                                                           2)
                                                                                                   .vector("anchor",
                                                                                                           FloatRepr.FLOAT,
                                                                                                           3)
                                                                                                   .vector("offset",
                                                                                                           FloatRepr.FLOAT,
                                                                                                           2)
                                                                                                   .vector("size",
                                                                                                           FloatRepr.FLOAT,
                                                                                                           2)
                                                                                                   .scalar("shear",
                                                                                                           FloatRepr.FLOAT)
                                                                                                   .vector("uvRegion",
                                                                                                           FloatRepr.FLOAT,
                                                                                                           4)
                                                                                                   .scalar("depth",
                                                                                                           FloatRepr.FLOAT)
                                                                                                   .build())
                                                                              .seed(ptr -> {
                                                                                  // A recycled slot keeps the previous occupant's bytes (ensureSlabBlock zeroes only on the
                                                                                  // first page touch), so seed every field. size (0,0) keeps the quad degenerate until posed.
                                                                                  MemoryUtil.memPutInt(
                                                                                          ptr + GlyphInstance.OFF_RGBA,
                                                                                          0xFFFFFFFF);
                                                                                  MemoryUtil.memSet(
                                                                                          ptr + GlyphInstance.OFF_LIGHT,
                                                                                          0,
                                                                                          GlyphInstance.OFF_UV_REGION - GlyphInstance.OFF_LIGHT);
                                                                                  ExtraMemoryOps.putVector4f(
                                                                                          ptr + GlyphInstance.OFF_UV_REGION,
                                                                                          0.0f, 0.0f, 1.0f, 1.0f);
                                                                                  MemoryUtil.memPutFloat(
                                                                                          ptr + GlyphInstance.OFF_DEPTH,
                                                                                          0.0f);
                                                                              })
                                                                              .vertexShader(ResourceUtil.rl(
                                                                                      "instance/glyph.vert"))
                                                                              .cullShader(ResourceUtil.rl(
                                                                                      "instance/cull/glyph.glsl"))
                                                                              .build();
    /**
     * One leash rope: the vertex shader evaluates vanilla's rope curve from {@code start}/{@code delta}.
     */
    public static final InstanceType<LeashInstance> LEASH = SimpleInstanceType.builder(LeashInstance::new)
                                                                              .layout(LayoutBuilder.create()
                                                                                                   .vector("light",
                                                                                                           FloatRepr.UNSIGNED_SHORT,
                                                                                                           2)
                                                                                                   .scalar("scale",
                                                                                                           FloatRepr.FLOAT)
                                                                                                   .vector("start",
                                                                                                           FloatRepr.FLOAT,
                                                                                                           3)
                                                                                                   .vector("delta",
                                                                                                           FloatRepr.FLOAT,
                                                                                                           3)
                                                                                                   .build())
                                                                              .seed(ptr -> {
                                                                                  // scale 0 keeps the rope degenerate until first posed. OFF_DELTA + 12 spans
                                                                                  // through delta (a vec3 = 12 bytes), i.e. the whole instance stride.
                                                                                  MemoryUtil.memSet(
                                                                                          ptr + LeashInstance.OFF_LIGHT,
                                                                                          0,
                                                                                          LeashInstance.OFF_DELTA + 12);
                                                                              })
                                                                              .vertexShader(ResourceUtil.rl(
                                                                                      "instance/leash.vert"))
                                                                              .cullShader(ResourceUtil.rl(
                                                                                      "instance/cull/leash.glsl"))
                                                                              .build();
    public static final InstanceType<ShadowInstance> SHADOW = SimpleInstanceType.builder(ShadowInstance::new)
                                                                                .layout(LayoutBuilder.create()
                                                                                                     .vector("pos",
                                                                                                             FloatRepr.FLOAT,
                                                                                                             3)
                                                                                                     .vector("entityPosXZ",
                                                                                                             FloatRepr.FLOAT,
                                                                                                             2)
                                                                                                     .vector("size",
                                                                                                             FloatRepr.FLOAT,
                                                                                                             2)
                                                                                                     .scalar("alpha",
                                                                                                             FloatRepr.FLOAT)
                                                                                                     .scalar("radius",
                                                                                                             FloatRepr.FLOAT)
                                                                                                     .build())
                                                                                .seed(ptr -> {
                                                                                })
                                                                                .vertexShader(ResourceUtil.rl(
                                                                                        "instance/shadow.vert"))
                                                                                .cullShader(ResourceUtil.rl(
                                                                                        "instance/cull/shadow.glsl"))
                                                                                .build();

    private InstanceTypes() {
    }
}
