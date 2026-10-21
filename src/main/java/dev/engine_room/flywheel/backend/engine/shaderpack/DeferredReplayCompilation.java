package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.FlwBackend;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.Loader;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

public final class DeferredReplayCompilation {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final Pattern INDEXED_PROGRAM = Pattern.compile(
            "^(setup|begin|prepare|deferred|composite|shadowcomp)_?(\\d+)$");

    private DeferredReplayCompilation() {
    }

    static ContractPatches.Plan stage(GuestShaderPack pack, Map<String, String> originals,
                                      DeferredOitProfile.Checked checked) throws IOException {
        var profile = checked.profile();
        if (!Loader.isModLoaded("ausm")
                && FMLClientHandler.instance().hasOptifine() && profile != DeferredOitProfile.SEUS) {
            FlwBackend.LOGGER.info("Native host lacks the material target/compute ABI for {}; retaining native sources",
                    profile);
            return new ContractPatches.Plan(Map.copyOf(originals), pack.prefix(), false);
        }
        return profile.adapter().stage(pack, originals, checked.recipe());
    }

    public static Scope enter(ContractPatches.Plan plan, String name) {
        return new Scope(plan, name);
    }

    public static boolean active() {
        var scope = CURRENT.get();
        return scope != null && scope.plan != null && scope.plan.replayProfile() != null;
    }

    public static String transform(String source, int type) {
        var scope = CURRENT.get();
        return scope == null ? source : transform(scope.plan, scope.name, source, type);
    }

    static String transform(ContractPatches.Plan plan, String name, String source, int type) {
        if (plan == null || plan.replayProfile() == null) return source;
        name = name.substring(name.lastIndexOf('/') + 1).replaceFirst("\\.(?:vsh|fsh|gsh|csh)$", "");
        // AUSM's fixed-slot identities use an underscore; file-based and OptiFine identities do not.
        var indexed = INDEXED_PROGRAM.matcher(name);
        if (indexed.matches()) {
            var index = Integer.parseInt(indexed.group(2));
            name = indexed.group(1) + (index == 0 ? "" : index);
        }
        var profile = DeferredOitProfile.valueOf(plan.replayProfile());
        return profile.adapter().transform(name, source, type);
    }

    public static final class Scope implements AutoCloseable {
        private final Scope previous;
        private final ContractPatches.Plan plan;
        private final String name;

        private Scope(ContractPatches.Plan plan, String name) {
            previous = CURRENT.get();
            this.plan = plan;
            this.name = name;
            CURRENT.set(this);
        }

        @Override
        public void close() {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
