package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.backend.FlwBackend;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.ArrayAccessExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.BinaryExpression;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.print.PrintType;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import io.github.douira.glsl_transformer.util.LRUCache;
import net.irisshaders.iris.pipeline.transform.vulkan.VulkanGlsl;

import java.util.List;
import java.util.Map;

/**
 * Render-thread packing of Photon's sky SH to avoid shared vec3 padding.
 */
public final class PhotonSharedMemory {
    private static final Map<String, String> CACHE = new LRUCache<>(64);

    private PhotonSharedMemory() {
    }

    public static String patch(String source) {
        if (!source.contains("Photon Shader by SixthSurge") || !source.contains("shared_memory")) return source;
        return CACHE.computeIfAbsent(source, PhotonSharedMemory::transform);
    }

    public static void clear() {
        CACHE.clear();
    }

    private static String transform(String source) {
        var parser = new SingleASTTransformer<JobParameters>();
        var tree = parser.parseSeparateTranslationUnit(source);
        var root = tree.getRoot();
        var declarations = root.nodeIndex.getStream(TypeAndInitDeclaration.class).filter(declaration ->
                VulkanGlsl.hasStorage(declaration.getType().getTypeQualifier(), StorageType.SHARED)
                        && ASTPrinter.printSimple(declaration.getType().getTypeSpecifier()).equals("vec3")
                        && declaration.getMembers().size() == 1
                        && declaration.getMembers().getFirst().getName().getName().equals("shared_memory")
                        && VulkanGlsl.dimensions(root, declaration.getMembers().getFirst().getArraySpecifier(),
                        declaration.getType().getTypeSpecifier().getArraySpecifier()).equals(List.of(256, 9))).toList();
        if (declarations.size() != 1) return source;
        if (root.identifierIndex.has("_flwPhotonSkySh") || root.identifierIndex.has("_flwPhotonReadSh")
                || root.identifierIndex.has("_flwPhotonWriteSh") || root.identifierIndex.has("_flwPhotonReadRow")) {
            throw new IllegalStateException("Photon shared-memory helper name collision");
        }
        var accesses = root.nodeIndex.getStream(ArrayAccessExpression.class).filter(PhotonSharedMemory::sample)
                                     .toList();
        for (var access : accesses) {
            if (write(access)) continue;
            access.replaceByAndDelete(parser.parseExpression(root, read(access)));
        }
        for (var access : accesses) {
            if (!write(access)) continue;
            var assignment = (BinaryExpression) access.getParent();
            String value = ASTPrinter.printSimple(assignment.getRight());
            value = switch (assignment.getExpressionType()) {
                case ASSIGNMENT -> value;
                case ADDITION_ASSIGNMENT -> read(access) + " + (" + value + ")";
                default -> throw new IllegalStateException(
                        "Photon shared-memory update changed: " + assignment.getExpressionType());
            };
            var row = (ArrayAccessExpression) access.getLeft();
            String first = ASTPrinter.printSimple(row.getRight());
            String second = ASTPrinter.printSimple(access.getRight());
            if (first.contains("++") || first.contains("--") || second.contains("++") || second.contains("--")) {
                throw new IllegalStateException("Photon shared-memory index has side effects");
            }
            assignment.replaceByAndDelete(parser.parseExpression(root,
                    "_flwPhotonWriteSh(int(" + first + "), int(" + second + "), " + value + ")"));
        }
        // The final irradiance evaluation consumes a complete coefficient row as an input array.
        for (var row : root.nodeIndex.getStream(ArrayAccessExpression.class).filter(access ->
                access.getLeft() instanceof ReferenceExpression reference
                        && reference.getIdentifier().getName().equals("shared_memory")).toList()) {
            if (write(row)) throw new IllegalStateException("Photon shared-memory row is written as a whole");
            row.replaceByAndDelete(parser.parseExpression(root,
                    "_flwPhotonReadRow(int(" + ASTPrinter.printSimple(row.getRight()) + "))"));
        }
        declarations.getFirst().getParent().detachAndDelete();
        if (root.identifierIndex.has("shared_memory")) {
            throw new IllegalStateException("Photon shared-memory access shape changed");
        }
        // 256 samples x 9 coefficients x 3 scalar channels x 4 bytes = 27 KiB.
        // Change only storage layout; preserve arithmetic and barriers.
        tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_ALL,
                "shared float _flwPhotonSkySh[6912];", """
                        vec3 _flwPhotonReadSh(int sampleIndex, int coefficient) {
                            int offset = (sampleIndex * 9 + coefficient) * 3;
                            return vec3(_flwPhotonSkySh[offset], _flwPhotonSkySh[offset + 1], _flwPhotonSkySh[offset + 2]);
                        }
                        """, """
                        vec3 _flwPhotonWriteSh(int sampleIndex, int coefficient, vec3 value) {
                            int offset = (sampleIndex * 9 + coefficient) * 3;
                            _flwPhotonSkySh[offset] = value.x;
                            _flwPhotonSkySh[offset + 1] = value.y;
                            _flwPhotonSkySh[offset + 2] = value.z;
                            return value;
                        }
                        """, """
                        vec3[9] _flwPhotonReadRow(int sampleIndex) {
                            vec3 coefficients[9];
                            for (int coefficient = 0; coefficient < 9; ++coefficient) {
                                coefficients[coefficient] = _flwPhotonReadSh(sampleIndex, coefficient);
                            }
                            return coefficients;
                        }
                        """);
        FlwBackend.LOGGER.info("Photon sky SH shared storage packed from 36 KiB to 27 KiB");
        return ASTPrinter.print(PrintType.INDENTED, tree);
    }

    private static boolean sample(ArrayAccessExpression access) {
        return access.getLeft() instanceof ArrayAccessExpression row && row.getLeft() instanceof ReferenceExpression reference
                && reference.getIdentifier().getName().equals("shared_memory");
    }

    private static boolean write(ArrayAccessExpression access) {
        return access.getParent() instanceof BinaryExpression expression && expression.getLeft() == access
                && expression.getExpressionType().name().endsWith("ASSIGNMENT");
    }

    private static String read(ArrayAccessExpression access) {
        var row = (ArrayAccessExpression) access.getLeft();
        return "_flwPhotonReadSh(int(" + ASTPrinter.printSimple(row.getRight()) + "), int("
                + ASTPrinter.printSimple(access.getRight()) + "))";
    }
}
