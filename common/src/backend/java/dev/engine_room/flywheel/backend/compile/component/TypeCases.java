package dev.engine_room.flywheel.backend.compile.component;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.layout.Layout;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

/**
 * Cases of a merged program. A type with the layout and shader of an earlier type takes that type's case, whose struct,
 * shader copy and unpacking it would otherwise duplicate.
 */
final class TypeCases {
    private TypeCases() {
    }

    /**
     * Per type index, the index of the first type with its layout and {@code shader}.
     */
    static int[] of(List<InstanceType<?>> types, Function<InstanceType<?>, Identifier> shader) {
        var first = new HashMap<Key, Integer>();
        int[] cases = new int[types.size()];
        for (int i = 0; i < cases.length; i++) {
            InstanceType<?> type = types.get(i);
            Integer earlier = first.putIfAbsent(new Key(type.layout(), shader.apply(type)), i);
            cases[i] = earlier == null ? i : earlier;
        }
        return cases;
    }

    /**
     * {@code switch (typeId)} over every type index, one labelled block per case, as {@code body} spells it.
     */
    static void appendSwitch(StringBuilder sb, int[] cases, CaseBody body) {
        var labels = new LinkedHashMap<String, StringBuilder>();
        for (int i = 0; i < cases.length; i++) {
            labels.computeIfAbsent(body.apply(i, cases[i]), _ -> new StringBuilder())
                  .append("    case ").append(i).append("u:\n");
        }
        sb.append("    switch (typeId) {\n");
        labels.forEach((block, label) -> sb.append(label).append("    {\n").append(block).append("        break;\n")
                                           .append("    }\n"));
        sb.append("    }\n");
    }

    @FunctionalInterface
    interface CaseBody {
        String apply(int type, int typeCase);
    }

    private record Key(Layout layout, Identifier shader) {
    }
}
