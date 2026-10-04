package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceHandle;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import org.lwjgl.system.MemoryUtil;

public class InstanceHandleImpl<I extends Instance> implements InstanceHandle {
    /**
     * Process-wide write-only "trash" slot: deleted handles route slab writes here so setters stay
     * branch-free. Sized for the largest std140 layout (POSED = 112 bytes); larger layouts are rejected.
     */
    public static final int SLAB_TRASH_BYTES = 256;
    public static final long SLAB_TRASH_PTR = FlwMemoryTracker.calloc(1, SLAB_TRASH_BYTES);

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
    public boolean isVisible() {
        // Port: indirect storage keeps a page, not its instancer, as the live state.
        return !(state instanceof Hidden<?> || state instanceof Deleted<?>);
    }

    @Override
    public void setVisible(boolean visible) {
        state = state.setVisible(this, index, visible);
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

    public static final class Hidden<I extends Instance> implements State<I> {
        private final I instance;
        private final InstanceHandleImpl<I> handle;
        private final MemoryBlock storage;
        private final long ptr;
        private AbstractInstancer.Recreate<I> recreate;

        public Hidden(AbstractInstancer.Recreate<I> recreate, I instance, InstanceHandleImpl<I> handle, long source) {
            this.recreate = recreate;
            this.instance = instance;
            this.handle = handle;
            int bytes = instance.type().layout().byteSize();
            storage = MemoryBlock.mallocTracked(Math.max(1, bytes));
            ptr = storage.ptr();
            MemoryUtil.memCopy(source, ptr, bytes);
            recreate.drawManager().retainHidden(this);
        }

        public AbstractInstancer.Recreate<I> recreate() {
            return recreate;
        }

        public void retarget(AbstractInstancer.Recreate<I> recreate) {
            if (this.recreate.drawManager() != recreate.drawManager()) {
                recreate.drawManager().retainHidden(this);
                this.recreate.drawManager().releaseHidden(this);
            }
            this.recreate = recreate;
        }

        void managerDeleted(DrawManager<?> owner) {
            assert recreate.drawManager() == owner && handle.state == this;
            handle.state = Deleted.instance();
            handle.clear();
            storage.free();
        }

        @Override
        public State<I> setChanged(int index) {
            return this;
        }

        @Override
        public State<I> setDeleted(int index) {
            handle.state = Deleted.instance();
            handle.clear();
            recreate.drawManager().releaseHidden(this);
            storage.free();
            return Deleted.instance();
        }

        @Override
        public State<I> setVisible(InstanceHandleImpl<I> handle, int index, boolean visible) {
            if (!visible) {
                return this;
            }
            var instancer = recreate.recreate();
            var state = instancer.revealInstance(handle, instance, ptr);
            handle.state = state;
            recreate.drawManager().releaseHidden(this);
            storage.free();
            return state;
        }

        @Override
        public long slabPtrAt(int index) {
            return ptr;
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
