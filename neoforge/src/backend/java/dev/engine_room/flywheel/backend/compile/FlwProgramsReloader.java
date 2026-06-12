package dev.engine_room.flywheel.backend.compile;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;

/** Compiles the shader programs on the render thread during resource reload; without it no backend is supported. */
public final class FlwProgramsReloader implements PreparableReloadListener {
	public static final FlwProgramsReloader INSTANCE = new FlwProgramsReloader();
	public static final Identifier ID = Identifier.fromNamespaceAndPath(Flywheel.ID, "programs");

	private FlwProgramsReloader() {
	}

	@Override
	public CompletableFuture<Void> reload(SharedState state, Executor backgroundExecutor, PreparationBarrier barrier, Executor gameExecutor) {
		return CompletableFuture.supplyAsync(state::resourceManager, backgroundExecutor)
				.thenCompose(barrier::wait)
				.thenAcceptAsync(rm -> {
					FlwPrograms.reload(rm);
					BackendManagerImpl.onEndClientResourceReload(false);
				}, gameExecutor);
	}
}
