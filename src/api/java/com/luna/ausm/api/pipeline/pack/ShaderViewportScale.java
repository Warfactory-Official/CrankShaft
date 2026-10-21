package com.luna.ausm.api.pipeline.pack;

public abstract class ShaderViewportScale {
    public abstract float scale();

    public abstract int x(int width);

    public abstract int y(int height);

    public abstract int width(int width);

    public abstract int height(int height);
}
