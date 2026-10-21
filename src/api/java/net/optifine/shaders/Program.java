package net.optifine.shaders;

import net.optifine.render.GlBlendState;
import net.optifine.shaders.config.RenderScale;

import java.nio.IntBuffer;

public class Program {
    public Program(int index, String name, ProgramStage stage, Program backup) {
    }

    public GlBlendState getBlendState() {
        throw new UnsupportedOperationException();
    }

    public int getId() {
        throw new UnsupportedOperationException();
    }

    public void setId(int id) {
        throw new UnsupportedOperationException();
    }

    public String getName() {
        throw new UnsupportedOperationException();
    }

    public ProgramStage getProgramStage() {
        throw new UnsupportedOperationException();
    }

    public Program getProgramBackup() {
        throw new UnsupportedOperationException();
    }

    public IntBuffer getDrawBuffers() {
        throw new UnsupportedOperationException();
    }

    public void copyFrom(Program program) {
        throw new UnsupportedOperationException();
    }

    public RenderScale getRenderScale() {
        throw new UnsupportedOperationException();
    }

    public boolean[] getToggleColorTextures() {
        throw new UnsupportedOperationException();
    }

    public int getCountInstances() {
        throw new UnsupportedOperationException();
    }

    public int getCompositeMipmapSetting() {
        throw new UnsupportedOperationException();
    }
}
