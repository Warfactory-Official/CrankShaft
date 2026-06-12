package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.apache.commons.io.IOUtils;
import org.jspecify.annotations.Nullable;

import java.io.Reader;
import java.util.HashSet;
import java.util.Set;

public final class MojImportPreprocessor {
    private MojImportPreprocessor() {
    }

    public static String flatten(String source) {
        ResourceManager resources = Minecraft.getInstance()
                                             .getResourceManager();
        GlslPreprocessor preprocessor = new GlslPreprocessor() {
            private final Set<Identifier> imported = new HashSet<>();

            @Override
            public @Nullable String applyImport(boolean isRelative, String path) {
                if (isRelative) {
                    return "#error relative #moj_import is unsupported in generated flywheel source: " + path;
                }
                Identifier location = Identifier.parse(path)
                                                .withPrefix("shaders/include/");
                if (!imported.add(location)) {
                    return null;
                }
                try (Reader reader = resources.getResource(location)
                                              .orElseThrow()
                                              .openAsReader()) {
                    return IOUtils.toString(reader);
                } catch (Exception e) {
                    return "#error could not open #moj_import " + location + ": " + e.getMessage();
                }
            }
        };
        return String.join("", preprocessor.process(source));
    }
}
