package dev.engine_room.flywheel.backend;

import dev.engine_room.flywheel.api.material.CutoutShader;
import dev.engine_room.flywheel.api.material.FogShader;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.resources.Identifier;

import java.util.List;

public final class MaterialShaderIndices {
    private static final Index cutoutSources = new Index();
    private static final Index fogSources = new Index();

    private MaterialShaderIndices() {
    }

    public static Index cutoutSources() {
        return cutoutSources;
    }

    public static Index fogSources() {
        return fogSources;
    }

    public static int cutoutIndex(CutoutShader cutoutShader) {
        return cutoutSources().index(cutoutShader.source());
    }

    public static int fogIndex(FogShader fogShader) {
        return fogSources().index(fogShader.source());
    }

    public static class Index {
        private final Object2IntMap<Identifier> sources2Index;
        private final ObjectList<Identifier> sources;

        private Index() {
            this.sources2Index = new Object2IntOpenHashMap<>();
            sources2Index.defaultReturnValue(-1);
            this.sources = new ObjectArrayList<>();
        }

        public Identifier get(int index) {
            return sources.get(index);
        }

        public int index(Identifier source) {
            var out = sources2Index.getInt(source);

            if (out == -1) {
                add(source);
                return sources2Index.getInt(source);
            }

            return out;
        }

        public List<Identifier> all() {
            return sources;
        }

        private void add(Identifier source) {
            if (sources2Index.putIfAbsent(source, sources.size()) == -1) {
                sources.add(source);
            }
        }
    }
}
