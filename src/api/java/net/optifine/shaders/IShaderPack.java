package net.optifine.shaders;

import java.io.InputStream;

public interface IShaderPack {
    String getName();

    InputStream getResourceAsStream(String path);

    boolean hasDirectory(String path);

    void close();
}
