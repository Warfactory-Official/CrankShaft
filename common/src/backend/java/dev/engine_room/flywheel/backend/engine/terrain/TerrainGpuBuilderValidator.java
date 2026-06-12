package dev.engine_room.flywheel.backend.engine.terrain;

import dev.engine_room.flywheel.backend.gl.buffer.GlResidentBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.NVShaderBufferStore;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Debug-only CPU oracle for {@link TerrainDrawDispatcher}'s GPU command builder, diffed against the GPU-built
 * command SSBO; only ever called behind {@code TerrainDebug.DEBUG_VALIDATE_GPU_BUILDER}.
 */
final class TerrainGpuBuilderValidator {
    private final GlResidentBuffer regionVisBuffer;
    private final GlResidentBuffer[][] commandBuffers;
    private final GlResidentBuffer[][] regionCommandCounts;
    private final TerrainSectionRegistry registry;

    private int oracleCamBlockX;
    private int oracleCamBlockY;
    private int oracleCamBlockZ;
    private float oracleCamFracX;
    private float oracleCamFracY;
    private float oracleCamFracZ;

    TerrainGpuBuilderValidator(GlResidentBuffer regionVisBuffer, GlResidentBuffer[][] commandBuffers,
                               GlResidentBuffer[][] regionCommandCounts, TerrainSectionRegistry registry) {
        this.regionVisBuffer = regionVisBuffer;
        this.commandBuffers = commandBuffers;
        this.regionCommandCounts = regionCommandCounts;
        this.registry = registry;
    }

    private static String passName(int passIndex) {
        return passIndex == TerrainDrawDispatcher.PASS_SOLID ? "SOLID" : "CUTOUT";
    }

    private static void diffCommandStream(String stream, int regionId, int slot, long streamBase,
                                          ByteBuffer cpuBuf, ByteBuffer gpuBuf, int n) {
        if (n == 0) {
            return;
        }
        int[][] cpu = new int[n][];
        int[][] gpu = new int[n][];
        int base = (int) streamBase;
        for (int c = 0; c < n; c++) {
            int off = base + c * TerrainDrawDispatcher.COMMAND_STRIDE;
            int cpuInstanceCount = cpuBuf.getInt(off + 4);
            int cpuBaseInstance = cpuBuf.getInt(off + 16);
            int gpuInstanceCount = gpuBuf.getInt(off + 4);
            int gpuBaseInstance = gpuBuf.getInt(off + 16);
            if (cpuInstanceCount != 1 || cpuBaseInstance != 0) {
                throw new IllegalStateException(String.format(
                        "flw.debug.terrainGpuBuilderDiff: region %d (slot %d) %s CPU command %d non-constant "
                                + "(instanceCount=%d baseInstance=%d)", regionId, slot, stream, c,
                        cpuInstanceCount, cpuBaseInstance));
            }
            if (gpuInstanceCount != 1 || gpuBaseInstance != 0) {
                throw new IllegalStateException(String.format(
                        "flw.debug.terrainGpuBuilderDiff: region %d (slot %d) %s GPU command %d non-constant "
                                + "(instanceCount=%d baseInstance=%d)", regionId, slot, stream, c,
                        gpuInstanceCount, gpuBaseInstance));
            }
            cpu[c] = new int[]{cpuBuf.getInt(off), cpuBuf.getInt(off + 8), cpuBuf.getInt(off + 12)};
            gpu[c] = new int[]{gpuBuf.getInt(off), gpuBuf.getInt(off + 8), gpuBuf.getInt(off + 12)};
        }
        Comparator<int[]> cmp = (a, b) -> {
            for (int i = 0; i < 3; i++) {
                int ai = a[i], bi = b[i];
                if (ai == bi) {
                    continue;
                }
                long al = ai & 0xFFFFFFFFL, bl = bi & 0xFFFFFFFFL;
                return Long.compare(al, bl);
            }
            return 0;
        };
        Arrays.sort(cpu, cmp);
        Arrays.sort(gpu, cmp);
        for (int c = 0; c < n; c++) {
            if (!Arrays.equals(cpu[c], gpu[c])) {
                throw new IllegalStateException(String.format(
                        "flw.debug.terrainGpuBuilderDiff: region %d (slot %d) %s stream multiset mismatch at sorted "
                                + "index %d -- GPU(indexCount=%d firstIndex=%d baseVertex=%d) "
                                + "vs CPU(indexCount=%d firstIndex=%d baseVertex=%d)",
                        regionId, slot, stream, c,
                        gpu[c][0], gpu[c][1], gpu[c][2], cpu[c][0], cpu[c][1], cpu[c][2]));
            }
        }
    }

    private static void emitOracleCommand(ByteBuffer out, long streamBase, int wi,
                                          long indexCount, long baseVertex, long firstIndex) {
        int off = (int) (streamBase + (long) wi * TerrainDrawDispatcher.COMMAND_STRIDE);
        out.putInt(off, (int) indexCount);
        out.putInt(off + 4, 1);
        out.putInt(off + 8, (int) firstIndex);
        out.putInt(off + 12, (int) baseVertex);
        out.putInt(off + 16, 0);
    }

    void captureCamera(int camBlockX, int camBlockY, int camBlockZ, float camFracX, float camFracY, float camFracZ) {
        oracleCamBlockX = camBlockX;
        oracleCamBlockY = camBlockY;
        oracleCamBlockZ = camBlockZ;
        oracleCamFracX = camFracX;
        oracleCamFracY = camFracY;
        oracleCamFracZ = camFracZ;
    }

    void validateGpuBuilder(TerrainDrawDispatcher.VisibleRegionBatch visible, int currBufferIndex) {
        // The cull/builder write sectionVis + commands + counts through NV-bindless GLOBAL stores; client readback
        GL42.glMemoryBarrier(
                GL42.GL_BUFFER_UPDATE_BARRIER_BIT | NVShaderBufferStore.GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV);
        ByteBuffer sectionVisBack = MemoryUtil.memAlloc(
                TerrainDrawDispatcher.REGION_SIZE * TerrainDrawDispatcher.VISIBILITY_STRIDE);
        long cmdBytesPerRegion = ((long) TerrainDrawDispatcher.MAX_COMMANDS_PER_REGION + TerrainDrawDispatcher.MAX_TEMPORAL_COMMANDS_PER_REGION) * TerrainDrawDispatcher.COMMAND_STRIDE;
        ByteBuffer gpuCommandsBack = MemoryUtil.memAlloc((int) cmdBytesPerRegion);
        ByteBuffer cpuCommands = MemoryUtil.memCalloc((int) cmdBytesPerRegion);
        ByteBuffer gpuCountBack = MemoryUtil.memAlloc(8);

        ByteBuffer regionVisBack = MemoryUtil.memAlloc(4);
        try {
            int[] presentMaskShadow = registry.presentMaskShadow(visible.passIndex);
            for (int slot = 0; slot < visible.count; slot++) {
                int regionId = visible.regionIds[slot];
                regionVisBack.clear();
                GL45.glGetNamedBufferSubData(regionVisBuffer.handle(), (long) slot * 4L, regionVisBack);
                if (regionVisBack.getInt(0) == 0) {
                    long cOff = (long) slot * 8L;
                    gpuCountBack.clear();
                    GL45.glGetNamedBufferSubData(regionCommandCounts[visible.passIndex][currBufferIndex].handle(),
                            cOff, gpuCountBack);
                    if (gpuCountBack.getInt(0) != 0 || gpuCountBack.getInt(4) != 0) {
                        throw new IllegalStateException(String.format(
                                "flw.debug.terrainGpuBuilderDiff: %s region %d slot %d occluded (regionVis=0) but GPU "
                                        + "counts main=%d temporal=%d", passName(visible.passIndex), regionId, slot,
                                gpuCountBack.getInt(0), gpuCountBack.getInt(4)));
                    }
                    continue;
                }
                // Present mask is now REGION-ID-KEYED: base = regionId * GEOMETRY_MASK_WORDS (was slot-keyed).
                int maskBase = regionId * TerrainDrawDispatcher.GEOMETRY_MASK_WORDS;

                long svOffset = (long) regionId * TerrainDrawDispatcher.REGION_SIZE * TerrainDrawDispatcher.VISIBILITY_STRIDE;
                sectionVisBack.clear();
                GL45.glGetNamedBufferSubData(registry.sectionVisHandle(visible.passIndex), svOffset, sectionVisBack);
                sectionVisBack.rewind();

                int[] cpuCounts = buildCommandsOracleForRegion(visible.storages[slot], sectionVisBack,
                        presentMaskShadow, maskBase, cpuCommands,
                        visible.originChunkX[slot], visible.originChunkY[slot], visible.originChunkZ[slot]);
                if (cpuCounts[2] != 0) {
                    throw new IllegalStateException(String.format(
                            "flw.debug.terrainGpuBuilderDiff: %s region %d slot %d CPU oracle saw overflow/stale visibility",
                            passName(visible.passIndex), regionId, slot));
                }

                long slotByteOffset = (long) slot * cmdBytesPerRegion;
                gpuCommandsBack.clear();
                GL45.glGetNamedBufferSubData(commandBuffers[visible.passIndex][currBufferIndex].handle(),
                        slotByteOffset, gpuCommandsBack);
                gpuCommandsBack.rewind();

                long countOffset = (long) slot * 8L;
                gpuCountBack.clear();
                GL45.glGetNamedBufferSubData(regionCommandCounts[visible.passIndex][currBufferIndex].handle(),
                        countOffset, gpuCountBack);
                int gpuMainCount = gpuCountBack.getInt(0);
                int gpuTemporalCount = gpuCountBack.getInt(4);

                if (gpuMainCount != cpuCounts[0] || gpuTemporalCount != cpuCounts[1]) {
                    throw new IllegalStateException(String.format(
                            "flw.debug.terrainGpuBuilderDiff: %s region %d slot %d count mismatch -- "
                                    + "GPU main=%d temporal=%d vs CPU main=%d temporal=%d "
                                    + "[svPass=%d geoPass=%d slicesPass=%d firstS=%d firstSliceMask=0x%x firstCamMask=0x%x "
                                    + "origin=(%d,%d,%d) camBlock=(%d,%d,%d) camFrac=(%f,%f,%f)]",
                            passName(visible.passIndex), regionId, slot, gpuMainCount, gpuTemporalCount,
                            cpuCounts[0], cpuCounts[1],
                            cpuCounts[3], cpuCounts[4], cpuCounts[5], cpuCounts[6], cpuCounts[7], cpuCounts[8],
                            visible.originChunkX[slot], visible.originChunkY[slot], visible.originChunkZ[slot],
                            oracleCamBlockX, oracleCamBlockY, oracleCamBlockZ,
                            oracleCamFracX, oracleCamFracY, oracleCamFracZ));
                }

                diffCommandStream(passName(visible.passIndex) + " main", regionId, slot,
                        0, cpuCommands, gpuCommandsBack, cpuCounts[0]);
                long temporalByteBase = (long) TerrainDrawDispatcher.MAX_COMMANDS_PER_REGION * TerrainDrawDispatcher.COMMAND_STRIDE;
                diffCommandStream(passName(visible.passIndex) + " temporal", regionId, slot,
                        temporalByteBase, cpuCommands, gpuCommandsBack, cpuCounts[1]);
            }
        } finally {
            MemoryUtil.memFree(sectionVisBack);
            MemoryUtil.memFree(gpuCommandsBack);
            MemoryUtil.memFree(cpuCommands);
            MemoryUtil.memFree(gpuCountBack);
            MemoryUtil.memFree(regionVisBack);
        }
    }

    private int[] buildCommandsOracleForRegion(SectionRenderDataStorage storage, ByteBuffer sectionVisBack,
                                               int[] geometryMaskWords, int maskBase, ByteBuffer out,
                                               int originChunkX, int originChunkY, int originChunkZ) {
        long cmdBytesPerRegion = ((long) TerrainDrawDispatcher.MAX_COMMANDS_PER_REGION + TerrainDrawDispatcher.MAX_TEMPORAL_COMMANDS_PER_REGION) * TerrainDrawDispatcher.COMMAND_STRIDE;
        for (long i = 0; i < cmdBytesPerRegion; i += 8L) {
            out.putLong((int) i, 0L);
        }
        int mainWi = 0;
        int temporalWi = 0;
        int overflow = 0;
        int svPass = 0;
        int geoPass = 0;
        int slicesPass = 0;
        int firstS = -1;
        int firstSliceMask = 0;
        int firstCamMask = 0;

        for (int s = 0; s < TerrainDrawDispatcher.REGION_SIZE; s++) {
            int sv = sectionVisBack.getInt(s * TerrainDrawDispatcher.VISIBILITY_STRIDE) & 0xFF;
            if ((sv & 1) != 1) {
                continue;
            }
            svPass++;
            if ((geometryMaskWords[maskBase + (s >>> 5)] & (1 << (s & 31))) == 0) {
                overflow = 1;
                continue;
            }
            geoPass++;
            long pMeshData = storage.getDataPointer(s);
            long baseVertex = SectionRenderDataUnsafe.getBaseVertex(pMeshData);
            long elementOffsetBytes = SectionRenderDataUnsafe.getBaseElement(pMeshData);
            long facingList = SectionRenderDataUnsafe.getFacingList(pMeshData);
            int sliceMask = SectionRenderDataUnsafe.getSliceMask(pMeshData);
            // The builder's camera facing mask, mirrored FLOAT-FOR-FLOAT (same operand order as the shader's
            float relX = (float) (originChunkX + ((s >>> 5) & 7)) * 16.0f - (float) oracleCamBlockX - oracleCamFracX;
            float relY = (float) (originChunkY + (s & 3)) * 16.0f - (float) oracleCamBlockY - oracleCamFracY;
            float relZ = (float) (originChunkZ + ((s >>> 2) & 7)) * 16.0f - (float) oracleCamBlockZ - oracleCamFracZ;
            int camMask = 1 << 6;
            if (relX < 0.0f) camMask |= 1;
            if (relY < 0.0f) camMask |= 1 << 1;
            if (relZ < 0.0f) camMask |= 1 << 2;
            if (relX > -16.0f) camMask |= 1 << 3;
            if (relY > -16.0f) camMask |= 1 << 4;
            if (relZ > -16.0f) camMask |= 1 << 5;
            if (firstS < 0) {
                firstS = s;
                firstSliceMask = sliceMask;
                firstCamMask = camMask;
            }
            int slices = camMask & sliceMask;
            if (slices == 0) {
                continue;
            }
            slicesPass++;
            boolean newlyVisible = (sv & 3) == 1;
            long groupVertexCount = 0;
            long runBaseVertex = baseVertex;
            int lastMaskBit = 0;
            for (int i = 0; i <= TerrainDrawDispatcher.FACING_COUNT; i++) {
                int maskBit = 0;
                long vertexCount = 0;
                if (i < TerrainDrawDispatcher.FACING_COUNT) {
                    vertexCount = SectionRenderDataUnsafe.getVertexCount(pMeshData, i);
                    if (vertexCount != 0) {
                        int facing = (int) ((facingList >>> (i << 3)) & 0xFFL);
                        maskBit = (slices >>> facing) & 1;
                    }
                }
                // Sodium run-continuation: a zero-vertex facing does NOT break a run (mirrors the GPU builder).
                if (i < TerrainDrawDispatcher.FACING_COUNT && vertexCount == 0) {
                    continue;
                }
                if (maskBit == 0) {
                    if (lastMaskBit == 1) {
                        long indexCount = (groupVertexCount >> 2) * 6;
                        if (newlyVisible) {
                            if (temporalWi < TerrainDrawDispatcher.MAX_TEMPORAL_COMMANDS_PER_REGION) {
                                emitOracleCommand(out,
                                        (long) TerrainDrawDispatcher.MAX_COMMANDS_PER_REGION * TerrainDrawDispatcher.COMMAND_STRIDE,
                                        temporalWi, indexCount, runBaseVertex, elementOffsetBytes);
                                temporalWi++;
                            } else {
                                overflow = 1;
                            }
                        } else {
                            if (mainWi < TerrainDrawDispatcher.MAX_COMMANDS_PER_REGION) {
                                emitOracleCommand(out, 0L, mainWi, indexCount, runBaseVertex, elementOffsetBytes);
                                mainWi++;
                            } else {
                                overflow = 1;
                            }
                        }
                        runBaseVertex += groupVertexCount;
                        groupVertexCount = 0;
                    }
                    runBaseVertex += vertexCount;
                } else {
                    groupVertexCount += vertexCount;
                }
                lastMaskBit = maskBit;
            }
        }
        return new int[]{mainWi, temporalWi, overflow, svPass, geoPass, slicesPass, firstS, firstSliceMask, firstCamMask};
    }
}
