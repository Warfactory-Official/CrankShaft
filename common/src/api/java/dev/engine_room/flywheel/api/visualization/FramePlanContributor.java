package dev.engine_room.flywheel.api.visualization;

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.task.Plan;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-mod registry for splicing {@link Plan}s into flywheel's per-frame plan tree.
 */
public final class FramePlanContributor {
    private static final List<Plan<RenderContext>> CONTRIBUTORS = new ArrayList<>();

    private FramePlanContributor() {
    }

    public static void register(Plan<RenderContext> contributor) {
        CONTRIBUTORS.add(contributor);
    }

    public static List<Plan<RenderContext>> all() {
        return CONTRIBUTORS;
    }
}
