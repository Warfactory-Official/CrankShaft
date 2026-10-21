package dev.engine_room.flywheel.backend.engine.shaderpack.shader;

import io.github.douira.glsl_transformer.ast.node.declaration.InterfaceBlockDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.LayoutQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.NamedLayoutQualifierPart;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinNumericTypeSpecifier;
import io.github.douira.glsl_transformer.util.Type;

import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class GlslInterface {
    private static final Set<String> GUEST_INPUTS = Set.of("mc_Entity", "iris_Entity", "mc_midTexCoord", "at_tangent",
            "at_midBlock", "ausm_ChunkOffsetInstanced", "a_Offset");

    private GlslInterface() { }

    public static Map<String, String> vertexInputs(GlslAst ast) {
        var variables = new Object2ObjectArrayMap<String, String>();
        for (var declaration : ast.globals().filter(value -> GlslAst.storage(value, StorageType.IN)
                || GlslAst.storage(value, StorageType.ATTRIBUTE)).toList()) {
            var members = declaration.getMembers().stream()
                    .filter(member -> GUEST_INPUTS.contains(member.getName().getName())).toList();
            for (var member : members) {
                if (member.getArraySpecifier() != null || declaration.getType().getTypeSpecifier().getArraySpecifier() != null)
                    throw new UnsupportedOperationException("Guest vertex inputs cannot be arrays");
                variables.put(member.getName().getName(), GlslAst.print(declaration.getType().getTypeSpecifier()).strip());
                member.detachAndDelete();
            }
            if (declaration.getMembers().isEmpty()) declaration.getParent().detachAndDelete();
        }
        return variables;
    }

    public static Int2ObjectMap<String> fragmentOutputs(GlslAst ast) {
        var variables = new Int2ObjectArrayMap<String>();
        if (ast.nodes(InterfaceBlockDeclaration.class).anyMatch(block -> GlslAst.storage(block.getTypeQualifier(), StorageType.OUT)))
            throw new UnsupportedOperationException("Fragment output blocks are unsupported");
        for (var declaration : ast.globals().filter(value -> GlslAst.storage(value, StorageType.OUT)).toList()) {
            if (!(declaration.getType().getTypeSpecifier() instanceof BuiltinNumericTypeSpecifier numeric)
                    || numeric.type != Type.F32VEC4 || numeric.getArraySpecifier() != null || declaration.getMembers().size() != 1)
                throw new UnsupportedOperationException("OIT needs a single vec4 fragment output per declaration");
            var member = declaration.getMembers().getFirst();
            var name = member.getName().getName();
            if (member.getArraySpecifier() != null) throw new UnsupportedOperationException("OIT output arrays are unsupported");
            var locations = declaration.getType().getTypeQualifier().getParts().stream()
                    .flatMap(part -> part instanceof LayoutQualifier layout ? layout.getParts().stream() : Stream.empty())
                    .filter(NamedLayoutQualifierPart.class::isInstance).map(NamedLayoutQualifierPart.class::cast)
                    .filter(part -> part.getName().getName().equals("location")).toList();
            if (locations.size() != 1 || !(locations.getFirst().getExpression() instanceof LiteralExpression literal)
                    || !literal.isInteger())
                throw new UnsupportedOperationException("OIT fragment output needs a literal location: " + name);
            var location = Math.toIntExact(literal.getInteger());
            if (location < 0 || variables.put(location, name) != null)
                throw new UnsupportedOperationException("Invalid or duplicate fragment output location: " + location);
            // Keep native names so local variables and helper parameters retain their original bindings.
            declaration.getType().setTypeQualifier(null);
        }
        return variables;
    }

    public static Coordinates fragmentCoordinates(GlslAst ast) {
        var upperLeft = false;
        var integerCenter = false;
        for (var declaration : ast.globals().filter(value -> GlslAst.storage(value, StorageType.IN)).toList()) {
            if (declaration.getMembers().stream().noneMatch(member -> member.getName().getName().equals("gl_FragCoord"))) continue;
            var layout = ast.nodes(NamedLayoutQualifierPart.class).filter(part -> part.hasAncestor(declaration))
                    .map(part -> part.getName().getName()).collect(Collectors.toSet());
            upperLeft |= layout.contains("origin_upper_left");
            integerCenter |= layout.contains("pixel_center_integer");
            declaration.getParent().detachAndDelete();
        }
        return new Coordinates(upperLeft, integerCenter);
    }

    public record Rewritten(String source, Map<String, String> variables) { }
    public record Coordinates(boolean upperLeft, boolean integerCenter) { }
}
