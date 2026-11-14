package dev.engine_room.flywheel.backend.compile.component;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.layout.Layout;
import dev.engine_room.flywheel.backend.compile.LayoutInterpreter;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.backend.glsl.generate.GlslBuilder;
import dev.engine_room.flywheel.lib.util.ResourceUtil;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Pattern;

public class InstanceStructComponent implements SourceComponent {
    private static final String STRUCT_NAME = "FlwInstance";
    // Port: glslang resolves a .length selection as the array method before any struct member, so that element is
    // declared and selected under a reserved prefix.
    private static final String SELECTED_AS_METHOD = "length";
    private static final String RENAMED_PREFIX = "_flw_";
    private static final Pattern METHOD_NAME_SELECTION = Pattern.compile("\\.(\\s*)length\\b(?!\\s*\\()");

    private final Layout layout;
    private final String structName;

    public InstanceStructComponent(InstanceType<?> type) {
        this(type, STRUCT_NAME);
    }

    public InstanceStructComponent(InstanceType<?> type, String structName) {
        layout = type.layout();
        this.structName = structName;
    }

    private static String memberName(String element) {
        return element.equals(SELECTED_AS_METHOD) ? RENAMED_PREFIX + element : element;
    }

    /**
     * {@code shader}, one of the type's own shaders, selecting the struct's renamed members by their declared names.
     */
    public static SourceComponent typeShader(InstanceType<?> type, SourceComponent shader) {
        if (type.layout().elements().stream().noneMatch(element -> element.name().equals(SELECTED_AS_METHOD))) {
            return shader;
        }
        String source = METHOD_NAME_SELECTION.matcher(shader.source())
                                             .replaceAll(".$1" + RENAMED_PREFIX + SELECTED_AS_METHOD);
        return new RenamedSelections(shader.name(), source, shader.included());
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
            instance.addField(LayoutInterpreter.typeName(element.type()), memberName(element.name()));
        }

        builder.blankLine();
        return builder.build();
    }

    private record RenamedSelections(String name, String source, Collection<? extends SourceComponent> included)
            implements SourceComponent {
    }
}
