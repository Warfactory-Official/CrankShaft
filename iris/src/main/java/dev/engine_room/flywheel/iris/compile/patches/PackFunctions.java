package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.iris.compile.GuestShaders;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.type.struct.StructSpecifier;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Copies functions from another preprocessed stage of the same pack into a target unit, with every donor function
 * and global they reach that the target does not already define. Definitions are appended in dependency order.
 */
final class PackFunctions {
    private PackFunctions() {
    }

    /**
     * {@code renames} apply to the donor before copying, then a non-null {@code namespace} prefixes every donor
     * function, struct and non-interface global so same-named target definitions cannot shadow them. {@code early}:
     * target code before the appended copies calls them, so prototypes and globals go to the top; otherwise copied
     * types and globals are appended first, then prototypes and definitions.
     * {@code extension} may pull further donor statements through the importer.
     */
    static void copy(TranslationUnit target, Root root, ASTParser parser, String source, List<String> functions,
                     Map<String, String> renames, @Nullable String namespace, boolean early,
                     BiConsumer<TranslationUnit, Importer> extension) {
        var libraryParser = GuestShaders.versionedTransformer();
        libraryParser.setTransformation((library, _) -> {
            renames.forEach(library.getRoot()::rename);
            var names = namespace == null ? Map.<String, String>of() : DeferredSources.namespace(library, namespace);
            var importer = new Importer(target, root, parser, library, early);
            functions.forEach(name -> importer.function(names.getOrDefault(name, name)));
            extension.accept(library, importer);
            importer.finish();
        });
        libraryParser.transform(source);
    }

    static final class Importer {
        private final TranslationUnit target;
        private final Root root;
        private final ASTParser parser;
        private final TranslationUnit library;
        private final boolean early;
        private final Map<String, List<FunctionDefinition>> definitions = new HashMap<>();
        private final Map<String, DeclarationExternalDeclaration> globals = new HashMap<>();
        private final Map<String, DeclarationExternalDeclaration> structs = new HashMap<>();
        private final Set<String> imported = new HashSet<>();
        // One declaration may declare several reached globals.
        private final Set<DeclarationExternalDeclaration> declarations = Collections.newSetFromMap(
                new IdentityHashMap<>());
        private final List<FunctionDefinition> appended = new ArrayList<>();
        private final List<ExternalDeclaration> types = new ArrayList<>();
        private final List<ExternalDeclaration> variables = new ArrayList<>();

        private Importer(TranslationUnit target, Root root, ASTParser parser, TranslationUnit library,
                         boolean early) {
            this.target = target;
            this.root = root;
            this.parser = parser;
            this.library = library;
            this.early = early;
            for (var child : library.getChildren()) {
                if (child instanceof FunctionDefinition function) definitions.computeIfAbsent(
                        function.getFunctionPrototype().getName().getName(), _ -> new ArrayList<>()).add(function);
                else if (child instanceof DeclarationExternalDeclaration declaration
                        && declaration.getDeclaration() instanceof TypeAndInitDeclaration typed) {
                    for (var member : typed.getMembers()) globals.put(member.getName().getName(), declaration);
                    if (typed.getType()
                             .getTypeSpecifier() instanceof StructSpecifier struct && struct.getName() != null)
                        structs.put(struct.getName().getName(), declaration);
                }
            }
        }

        void function(String name) {
            if (!imported.add(name)) return;
            var overloads = definitions.get(name);
            if (overloads == null) throw new IllegalStateException("Donor stage has no function " + name);
            for (var definition : overloads) {
                String signature = signature(definition);
                if (root.nodeIndex.getStream(FunctionDefinition.class)
                                  .anyMatch(value -> value != definition && signature(value).equals(signature)))
                    continue;
                dependencies(definition);
                if (early) target.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                        ASTPrinter.printSimple(definition.getFunctionPrototype()) + ';');
                if (early) target.getChildren().add(definition.cloneInto(root));
                else appended.add(definition.cloneInto(root));
            }
        }

        /**
         * Overloads may call each other, and global initializers may call functions, out of donor order: copied
         * types, then prototypes, then globals, then definitions.
         */
        private void finish() {
            if (early) return;
            target.getChildren().addAll(types);
            for (var definition : appended)
                target.getChildren().add(parser.parseExternalDeclaration(root,
                        ASTPrinter.printSimple(definition.getFunctionPrototype()) + ';'));
            target.getChildren().addAll(variables);
            target.getChildren().addAll(appended);
        }

        private static String signature(FunctionDefinition function) {
            var prototype = function.getFunctionPrototype();
            var signature = new StringBuilder(prototype.getName().getName()).append('(');
            for (var parameter : prototype.getParameters())
                signature.append(ASTPrinter.printSimple(parameter.getType()).strip()).append(',');
            return signature.toString();
        }

        void dependencies(ASTNode node) {
            for (var entry : structs.entrySet()) {
                String name = entry.getKey();
                if (root.nodeIndex.getStream(StructSpecifier.class).anyMatch(struct -> struct.getName() != null
                        && struct.getName().getName().equals(name))) continue;
                if (library.getRoot().identifierIndex.getStream(name)
                                                     .anyMatch(identifier -> identifier.hasAncestor(node))
                        && imported.add("struct:" + name)) {
                    dependencies(entry.getValue().getDeclaration());
                    if (early) target.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                            ASTPrinter.printSimple(entry.getValue()));
                    else types.add(entry.getValue().cloneInto(root));
                }
            }
            for (var call : library.getRoot().nodeIndex.getStream(FunctionCallExpression.class).toList()) {
                if (call.hasAncestor(node) && call.getFunctionName() != null
                        && definitions.containsKey(call.getFunctionName().getName()))
                    function(call.getFunctionName().getName());
            }
            for (var entry : globals.entrySet()) {
                String name = entry.getKey();
                if (root.nodeIndex.getStream(TypeAndInitDeclaration.class).anyMatch(value -> value.getMembers()
                                                                                                  .stream().anyMatch(
                                member -> member.getName().getName().equals(name)))) continue;
                if (library.getRoot().identifierIndex.getStream(name)
                                                     .anyMatch(identifier -> identifier.hasAncestor(node))
                        && imported.add("global:" + name) && declarations.add(entry.getValue())) {
                    dependencies(entry.getValue());
                    if (early) target.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_FUNCTIONS,
                            ASTPrinter.printSimple(entry.getValue()));
                    else variables.add(entry.getValue().cloneInto(root));
                }
            }
        }
    }
}
