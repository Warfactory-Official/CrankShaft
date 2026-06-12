package dev.engine_room.flywheel.backend.compile.component;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.layout.Layout;
import dev.engine_room.flywheel.backend.compile.LayoutInterpreter;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.backend.glsl.generate.GlslBuilder;
import dev.engine_room.flywheel.lib.util.ResourceUtil;

import java.util.Collection;
import java.util.Collections;

public class InstanceStructComponent implements SourceComponent {
    private static final String STRUCT_NAME = "FlwInstance";

    private final Layout layout;
    private final String structName;

    public InstanceStructComponent(InstanceType<?> type) {
        this(type, STRUCT_NAME);
    }

    public InstanceStructComponent(InstanceType<?> type, String structName) {
        layout = type.layout();
        this.structName = structName;
    }

    @Override
    public String name() {
        return ResourceUtil.rl("instance_struct").toString() + "/" + structName;
    }

    @Override
    public Collection<? extends SourceComponent> included() {
        return Collections.emptyList();
    }

    @Override
    public String source() {
        var builder = new GlslBuilder();

        var instance = builder.struct();
        instance.name(structName);
        for (var element : layout.elements()) {
            instance.addField(LayoutInterpreter.typeName(element.type()), element.name());
        }

        builder.blankLine();
        return builder.build();
    }
}
