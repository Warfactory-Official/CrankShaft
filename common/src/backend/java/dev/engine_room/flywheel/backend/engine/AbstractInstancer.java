package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.backend.engine.embed.Environment;

public abstract class AbstractInstancer<I extends Instance> implements Instancer<I> {
    public final InstanceType<I> type;
    public final Environment environment;
    public final Recreate<I> recreate;

    protected AbstractInstancer(InstancerKey<I> key, Recreate<I> recreate) {
        this.type = key.type();
        this.environment = key.environment();
        this.recreate = recreate;
        // Port: hidden/deleted handles write into the fixed-size slab trash slot, so an oversized
        // layout would be a silent native-heap overflow -- reject it up front.
        int byteSize = type.layout().byteSize();
        if (byteSize > InstanceHandleImpl.SLAB_TRASH_BYTES) {
            throw new IllegalArgumentException("Instance type layout is " + byteSize + " bytes; at most "
                    + InstanceHandleImpl.SLAB_TRASH_BYTES + " bytes are supported: " + type);
        }
    }

    public abstract InstanceHandleImpl.State<I> revealInstance(InstanceHandleImpl<I> handle, I instance);

    public abstract int instanceCount();

    public abstract void parallelUpdate();

    public abstract void delete();

    public abstract void clear();

    @Override
    public String toString() {
        return "AbstractInstancer[" + instanceCount() + ']';
    }

    public record Recreate<I extends Instance>(InstancerKey<I> key, DrawManager<?> drawManager) {
        public AbstractInstancer<I> recreate() {
            return drawManager.getInstancer(key);
        }
    }
}
