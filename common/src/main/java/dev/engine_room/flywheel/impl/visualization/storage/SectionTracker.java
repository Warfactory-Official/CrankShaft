package dev.engine_room.flywheel.impl.visualization.storage;

import dev.engine_room.flywheel.api.visual.SectionTrackedVisual;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

import java.util.ArrayList;
import java.util.List;

public class SectionTracker implements SectionTrackedVisual.SectionCollector {
    private final List<Runnable> listeners = new ArrayList<>(2);

    private LongSet sections = LongSets.unmodifiable(new LongArraySet(0));

    public LongSet sections() {
        return sections;
    }

    @Override
    public void sections(LongSet sections) {
        this.sections = LongSets.unmodifiable(new LongArraySet(sections));
        listeners.forEach(Runnable::run);
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }
}
