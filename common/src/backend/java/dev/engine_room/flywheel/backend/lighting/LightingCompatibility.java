package dev.engine_room.flywheel.backend.lighting;

public final class LightingCompatibility {
    public static final boolean SODIUM = LightingCompatibility.class.getClassLoader()
                                                                    .getResource(
                                                                            "net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer.class") != null;

    private LightingCompatibility() {
    }
}
