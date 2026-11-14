package dev.engine_room.flywheel.iris.compile.patches;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;

final class ShaderDeclarations {
    private ShaderDeclarations() {
    }

    static void uniform(TranslationUnit tree, Root _, ASTParser parser, String type, String name) {
        boolean declared = tree.getChildren().stream().anyMatch(child ->
                child instanceof DeclarationExternalDeclaration external
                        && external.getDeclaration() instanceof TypeAndInitDeclaration declaration
                        && declaration.getMembers().stream()
                                      .anyMatch(member -> member.getName().getName().equals(name)));
        if (!declared) tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                "uniform " + type + ' ' + name + ';');
    }
}
