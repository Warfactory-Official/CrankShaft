package dev.engine_room.flywheel.backend.compile;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Render thread. One program, or programs drawn together, across {@link ProgramGeneration}s: the newest published
 * version draws while newer generations build in the background, and a version superseded by a newer published one
 * is released. Publication happens only at the tracker's frame boundary, so a selection holds for the whole frame.
 */
public final class ProgramVersions<P> {
    private final Tracker<P> tracker;
    private final List<P> pending = new ArrayList<>(1);
    private final List<ProgramGeneration> pendingGenerations = new ArrayList<>(1);
    private @Nullable P published;
    private ProgramGeneration publishedGeneration = ProgramGeneration.NONE;

    public ProgramVersions(Tracker<P> tracker) {
        this.tracker = tracker;
    }

    /**
     * The newest published version, null before the first; {@code build} requests {@code current}'s when no version
     * is at it.
     */
    public <K> @Nullable P select(ProgramGeneration current, K key, Function<? super K, ? extends P> build) {
        if (published != null && tracker.state(published) != State.PUBLISHED) published = null;
        for (int i = pending.size() - 1; i >= 0; i--) {
            State state = tracker.state(pending.get(i));
            if (state == State.PUBLISHED) {
                promote(i);
                break;
            }
            if (state == State.ABSENT) {
                pending.remove(i);
                pendingGenerations.remove(i);
            }
        }
        ProgramGeneration newest = !pending.isEmpty() ? pendingGenerations.getLast()
                : published != null ? publishedGeneration : null;
        if (!current.equals(newest)) {
            pending.add(build.apply(key));
            pendingGenerations.add(current);
            if (tracker.state(pending.getLast()) == State.PUBLISHED) promote(pending.size() - 1);
        }
        return published;
    }

    /**
     * The generation of the version {@link #select} returned.
     */
    public ProgramGeneration generation() {
        return publishedGeneration;
    }

    /**
     * Releases every version.
     */
    public void release() {
        if (published != null) tracker.release(published);
        pending.forEach(tracker::release);
        published = null;
        pending.clear();
        pendingGenerations.clear();
    }

    private void promote(int index) {
        if (published != null) tracker.release(published);
        for (int i = 0; i < index; i++) tracker.release(pending.get(i));
        published = pending.get(index);
        publishedGeneration = pendingGenerations.get(index);
        pending.subList(0, index + 1).clear();
        pendingGenerations.subList(0, index + 1).clear();
    }

    public enum State {
        PUBLISHED,
        PENDING,
        /**
         * Neither published nor building, e.g. after a pipeline-cache reset.
         */
        ABSENT
    }

    public interface Tracker<P> {
        State state(P program);

        /**
         * A superseded version; never selected again.
         */
        void release(P program);
    }
}
