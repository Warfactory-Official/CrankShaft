package com.luna.ausm.impl.pipeline.shader;

public abstract class ComputeProgram {
    public abstract ShaderProgram program();

    public abstract String name();

    public abstract int arrayIndex();

    public abstract boolean hasIndirectPointer();

    public abstract int indirectBuffer();

    public abstract long indirectOffset();

    public abstract int[] workGroups(int width, int height);
}
