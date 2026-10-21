package com.luna.ausm.impl.pipeline.pack;

import com.luna.ausm.api.pipeline.pack.ShaderOptions;
import com.luna.ausm.api.pipeline.pack.ShaderRenderSettings;
import net.minecraft.util.ResourceLocation;

import java.util.Map;

public final class ShaderProperties {
    public ShaderOptions options() {
        throw new UnsupportedOperationException();
    }

    public ShaderPackDirectives packDirectives() {
        throw new UnsupportedOperationException();
    }

    public ShaderRenderSettings renderSettings() {
        throw new UnsupportedOperationException();
    }

    public ShaderBlockIdMap.BlockIdRules blockIds() {
        throw new UnsupportedOperationException();
    }

    public Map<ResourceLocation, Integer> entityIds() {
        throw new UnsupportedOperationException();
    }

    public ShaderItemIdMap.ItemIdRules itemIds() {
        throw new UnsupportedOperationException();
    }
}
