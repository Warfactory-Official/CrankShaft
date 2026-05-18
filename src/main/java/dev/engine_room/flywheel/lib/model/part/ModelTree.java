package dev.engine_room.flywheel.lib.model.part;

import dev.engine_room.flywheel.api.model.Model;
import org.jspecify.annotations.Nullable;

public final class ModelTree {
    @Nullable
    private final Model model;
    private final PartPose initialPose;
    private final ModelTree[] children;

    public ModelTree(@Nullable Model model, PartPose initialPose, ModelTree[] children) {
        this.model = model;
        this.initialPose = initialPose;
        this.children = children;
    }

    @Nullable
    public Model model() {
        return model;
    }

    public PartPose initialPose() {
        return initialPose;
    }

    public int childCount() {
        return children.length;
    }

    public ModelTree child(int index) {
        return children[index];
    }
}
