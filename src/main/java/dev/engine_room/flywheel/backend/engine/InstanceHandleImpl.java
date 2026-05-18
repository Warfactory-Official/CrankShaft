package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;

public class InstanceHandleImpl<I extends Instance> implements InstanceHandle {
    /** Process-wide write-only "trash" slot. Hidden/Deleted handles route slab writes here so
     *  setters can be branch-free. Sized for the largest std140 instance layout in flywheel
     *  (POSED = 112 bytes); 256 bytes leaves headroom. Never read by anyone. */
    public static final long SLAB_TRASH_PTR = FlwMemoryTracker.calloc(1, 256);

    public State<I> state;
    public int index;

    public InstanceHandleImpl(State<I> state) {
        this.state = state;
    }

    @Override
    public void setChanged() {
        state = state.setChanged(index);
    }

    @Override
    public void setDeleted() {
        state = state.setDeleted(index);
        // invalidate ourselves
        clear();
    }

    @Override
    public void setVisible(boolean visible) {
        state = state.setVisible(this, index, visible);
    }

    @Override
    public boolean isVisible() {
        return state instanceof AbstractInstancer<?>;
    }

    @Override
    public long slabPtr() {
        return state.slabPtrAt(index);
    }

    public void clear() {
        index = -1;
    }

    public interface State<I extends Instance> {
        State<I> setChanged(int index);

        State<I> setDeleted(int index);

        State<I> setVisible(InstanceHandleImpl<I> handle, int index, boolean visible);

        long slabPtrAt(int index);
    }

    public record Hidden<I extends Instance>(AbstractInstancer.Recreate<I> recreate, I instance) implements State<I> {
        @Override
        public State<I> setChanged(int index) {
            return this;
        }

        @Override
        public State<I> setDeleted(int index) {
            return this;
        }

        @Override
        public State<I> setVisible(InstanceHandleImpl<I> handle, int index, boolean visible) {
            if (!visible) {
                return this;
            }
            var instancer = recreate.recreate();
            return instancer.revealInstance(handle, instance);
        }

        @Override
        public long slabPtrAt(int index) {
            return SLAB_TRASH_PTR;
        }
    }

    public record Deleted<I extends Instance>() implements State<I> {
        private static final Deleted<?> INSTANCE = new Deleted<>();

        @SuppressWarnings("unchecked")
        public static <I extends Instance> Deleted<I> instance() {
            return (Deleted<I>) INSTANCE;
        }

        @Override
        public State<I> setChanged(int index) {
            return this;
        }

        @Override
        public State<I> setDeleted(int index) {
            return this;
        }

        @Override
        public State<I> setVisible(InstanceHandleImpl<I> handle, int index, boolean visible) {
            return this;
        }

        @Override
        public long slabPtrAt(int index) {
            return SLAB_TRASH_PTR;
        }
    }
}
