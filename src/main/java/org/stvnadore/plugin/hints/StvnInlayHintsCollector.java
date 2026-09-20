package org.stvnadore.plugin.hints;

import com.intellij.codeInsight.hints.FactoryInlayHintsCollector;
import com.intellij.codeInsight.hints.InlayHintsSink;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.Value;

/**
 * Collects and renders contextual type hints inside STVN body blocks
 * using bounded depth traversal for mutually recursive schemas.
 */
@NullMarked
public final class StvnInlayHintsCollector extends FactoryInlayHintsCollector {

    public StvnInlayHintsCollector(Editor editor) {
        super(editor);
    }

    @Override
    public boolean collect(@NotNull PsiElement element, @NotNull Editor editor, @NotNull InlayHintsSink sink) {
        if (element instanceof PsiFile) {
            element.accept(new PsiRecursiveElementWalkingVisitor() {
                @Override
                public void visitElement(@NotNull PsiElement child) {
                    super.visitElement(child);
                    if (child instanceof Value valueElement) {
                        if (StvnTypeInferenceHelper.isInnerChildOfAlgebraicContainer(valueElement)) {
                            return;
                        }

                        var coreNode = StvnTypeResolver.resolveCoreValue(valueElement);
                        var finalLabel = StvnTypeInferenceHelper.resolveValueTypeWithDepth(valueElement, 16);
                        if (finalLabel != null && !finalLabel.isEmpty()) {
                            var offset = StvnTypeInferenceHelper.calculateInlayBadgeOffset(valueElement, coreNode);
                            var presentation = getFactory().text(finalLabel);
                            sink.addInlineElement(offset, true, presentation, false);
                        }
                    }
                }
            });
        }
        return true;
    }
}
