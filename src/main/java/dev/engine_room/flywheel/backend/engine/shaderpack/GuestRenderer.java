package dev.engine_room.flywheel.backend.engine.shaderpack;

public interface GuestRenderer {
    boolean sharedOitActive();

    boolean authoredContractActive();

    boolean shadowContractActive();

    boolean shadowOitActive();
}
