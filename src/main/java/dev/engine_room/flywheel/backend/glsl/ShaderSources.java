package dev.engine_room.flywheel.backend.glsl;

import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ShaderSources {
    public static final String SHADER_DIR = "flywheel/";

    protected final Map<ResourceLocation, LoadResult> cache;
    private final SourceFinder sourceFinder;

    public ShaderSources(IResourceManager manager) {
        this.sourceFinder = new SourceFinder(manager);
        this.cache = sourceFinder.results;
    }

    public LoadResult find(ResourceLocation location) {
        var existing = cache.get(location);
        if (existing != null) {
            return existing;
        }
        return sourceFinder.recursiveLoad(location);
    }

    public SourceFile get(ResourceLocation location) {
        return find(location).unwrap();
    }

    private static class SourceFinder {
        private final Deque<ResourceLocation> findStack = new ArrayDeque<>();
        private final Map<ResourceLocation, LoadResult> results = new HashMap<>();
        private final IResourceManager manager;

        public SourceFinder(IResourceManager manager) {
            this.manager = manager;
        }

        public LoadResult recursiveLoad(ResourceLocation location) {
            if (findStack.contains(location)) {
                findStack.addLast(location);
                var copy = List.copyOf(findStack);
                findStack.removeLast();
                return new LoadResult.Failure(new LoadError.CircularDependency(location, copy));
            }
            findStack.addLast(location);

            LoadResult out = _find(location);

            findStack.removeLast();
            return out;
        }

        private LoadResult _find(ResourceLocation location) {
            var out = results.get(location);
            if (out == null) {
                out = load(location);
                results.put(location, out);
            }
            return out;
        }

        private LoadResult load(ResourceLocation loc) {
            ResourceLocation prefixed = new ResourceLocation(loc.getNamespace(), SHADER_DIR + loc.getPath());
            IResource resource;
            try {
                resource = manager.getResource(prefixed);
            } catch (FileNotFoundException e) {
                return new LoadResult.Failure(new LoadError.ResourceError(loc));
            } catch (IOException e) {
                return new LoadResult.Failure(new LoadError.IOError(loc, e));
            }
            return readResource(loc, resource);
        }

        private LoadResult readResource(ResourceLocation loc, IResource resource) {
            try (InputStream stream = resource.getInputStream()) {
                String sourceString = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                return SourceFile.parse(this::recursiveLoad, loc, sourceString);
            } catch (IOException e) {
                return new LoadResult.Failure(new LoadError.IOError(loc, e));
            }
        }
    }
}
