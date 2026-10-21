package net.optifine.shaders;

import net.optifine.shaders.config.ShaderOption;
import net.optifine.shaders.uniform.CustomUniforms;
import net.optifine.shaders.uniform.ShaderUniforms;

import java.nio.IntBuffer;

public class Shaders {
    public static boolean shaderPackLoaded, isShadowPass, isRenderingWorld, isRenderingDfb, shouldSkipDefaultShadow;
    public static int dfb, sfb, activeProgramID;
    public static int renderWidth, renderHeight;
    public static IntBuffer activeDrawBuffers;
    public static Program activeProgram, ProgramNone, ProgramTerrain, ProgramTerrainSolid, ProgramTerrainCutoutMip, ProgramTerrainCutout,
            ProgramEntities, ProgramBlock, ProgramWater, ProgramDamagedBlock, ProgramBeaconBeam, ProgramArmorGlint, ProgramSpiderEyes, ProgramShadow;
    static final IntBuffer dfbColorTextures = null, dfbDepthTextures = null;
    static final FlipTextures dfbColorTexturesFlip = null;
    static int frameCounter;
    static final int[] colorTextureImageUnit = null;
    private static ShaderUniforms shaderUniforms;
    private static CustomUniforms customUniforms;

    public static boolean isRenderShadowTranslucent() {
        throw new UnsupportedOperationException();
    }

    public static boolean isOldLighting() {
        throw new UnsupportedOperationException();
    }

    public static boolean isSeparateAo() {
        throw new UnsupportedOperationException();
    }

    public static String getShaderPackName() {
        throw new UnsupportedOperationException();
    }

    public static IShaderPack getShaderPack() {
        throw new UnsupportedOperationException();
    }

    public static void setShaderPack(String name) {
        throw new UnsupportedOperationException();
    }

    public static IShaderPack getShaderPack(String name) {
        throw new UnsupportedOperationException();
    }

    public static ShaderOption[] getShaderPackOptions() {
        throw new UnsupportedOperationException();
    }

    public static String applyOptions(String line, ShaderOption[] options) {
        throw new UnsupportedOperationException();
    }

    public static void useProgram(Program program) {
        throw new UnsupportedOperationException();
    }

    public static void setDrawBuffers(IntBuffer buffers) {
        throw new UnsupportedOperationException();
    }

    public static void uninit() {
        throw new UnsupportedOperationException();
    }

    public static void drawComposite() {
        throw new UnsupportedOperationException();
    }

    public static void genCompositeMipmap() {
        throw new UnsupportedOperationException();
    }

    private static void preDrawComposite() {
        throw new UnsupportedOperationException();
    }

    private static void postDrawComposite() {
        throw new UnsupportedOperationException();
    }
}
