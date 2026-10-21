package com.luna.ausm.api.pipeline.fbo;

public enum ColorBufferFormat {
    RGBA8;

    public int internalFormat() {
        throw new UnsupportedOperationException();
    }

    public int pixelFormat() {
        throw new UnsupportedOperationException();
    }
}
