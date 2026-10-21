package com.luna.ausm.impl.pipeline.pack;

import java.io.IOException;
import java.io.InputStream;

public interface ShaderPack {
    String getName();

    InputStream getResourceAsStream(String path) throws IOException;

    boolean hasResource(String path);

    void close() throws IOException;
}
