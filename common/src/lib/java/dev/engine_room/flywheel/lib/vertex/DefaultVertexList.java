package dev.engine_room.flywheel.lib.vertex;

import dev.engine_room.flywheel.api.vertex.MutableVertexList;
import dev.engine_room.flywheel.lib.util.OverlayTexture;

public interface DefaultVertexList extends MutableVertexList {
    @Override
    default float x(int _) {
        return 0.0f;
    }

    @Override
    default float y(int _) {
        return 0.0f;
    }

    @Override
    default float z(int _) {
        return 0.0f;
    }

    @Override
    default float r(int _) {
        return 1.0f;
    }

    @Override
    default float g(int _) {
        return 1.0f;
    }

    @Override
    default float b(int _) {
        return 1.0f;
    }

    @Override
    default float a(int _) {
        return 1.0f;
    }

    @Override
    default float u(int _) {
        return 0.0f;
    }

    @Override
    default float v(int _) {
        return 0.0f;
    }

    @Override
    default int overlay(int _) {
        return OverlayTexture.NO_OVERLAY;
    }

    @Override
    default int light(int _) {
        return 0;
    }

    @Override
    default float normalX(int _) {
        return 0.0f;
    }

    @Override
    default float normalY(int _) {
        return 1.0f;
    }

    @Override
    default float normalZ(int _) {
        return 0.0f;
    }

    @Override
    default void x(int _, float _) {
    }

    @Override
    default void y(int _, float _) {
    }

    @Override
    default void z(int _, float _) {
    }

    @Override
    default void r(int _, float _) {
    }

    @Override
    default void g(int _, float _) {
    }

    @Override
    default void b(int _, float _) {
    }

    @Override
    default void a(int _, float _) {
    }

    @Override
    default void u(int _, float _) {
    }

    @Override
    default void v(int _, float _) {
    }

    @Override
    default void overlay(int _, int _) {
    }

    @Override
    default void light(int _, int _) {
    }

    @Override
    default void normalX(int _, float _) {
    }

    @Override
    default void normalY(int _, float _) {
    }

    @Override
    default void normalZ(int _, float _) {
    }
}
