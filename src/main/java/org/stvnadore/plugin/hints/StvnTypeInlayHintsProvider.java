package org.stvnadore.plugin.hints;

import com.intellij.codeInsight.hints.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.settings.StvnProjectSettings;
import org.stvnadore.psi.*;

/**
 * Provides inlay type hints in the editor for inferred or complex types.
 */
@NullMarked
public final class StvnTypeInlayHintsProvider implements InlayHintsProvider<NoSettings> {

    /** Constructs an StvnTypeInlayHintsProvider instance. */
    public StvnTypeInlayHintsProvider() {}

    private static final SettingsKey<NoSettings> KEY = new SettingsKey<>("stvn.type.hints");

    @Override
    public NoSettings createSettings() {
        return new NoSettings();
    }

    @Override
    public @NotNull SettingsKey<NoSettings> getKey() {
        return KEY;
    }

    @Override
    public @NotNull String getName() {
        return "Type Inlay Hints";
    }

    @Override
    public @Nullable String getPreviewText() {
        return "{\n" +
               "  :type :Tuple( :Either( :Int32 :String ) )\n" +
               "  :body ( #Left -105 )\n" +
               "}";
    }

    @Override
    public boolean isVisibleInSettings() {
        return true;
    }

    @Override
    public @NotNull ImmediateConfigurable createConfigurable(@NotNull NoSettings settings) {
        return changeListener -> new javax.swing.JPanel();
    }

    @Nullable
    @Override
    public InlayHintsCollector getCollectorFor(
        @NotNull PsiFile file,
        @NotNull Editor editor,
        @NotNull NoSettings settings,
        @NotNull InlayHintsSink sink
    ) {
        var projectSettings = StvnProjectSettings.getInstance(file.getProject());
        if (projectSettings != null && !projectSettings.getState().showTypeHints) {
            return null;
        }
        return new FactoryInlayHintsCollector(editor) {
            @Override
            public boolean collect(@NotNull PsiElement element, @NotNull Editor editor, @NotNull InlayHintsSink sink) {
                if (element instanceof PsiFile) {
                    element.accept(new com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
                        @Override
                        public void visitElement(@NotNull PsiElement child) {
                            super.visitElement(child);
                            if (child instanceof Value valueElement) {
                                if (isInnerChildOfAlgebraicContainer(valueElement)) {
                                    return;
                                }

                                var coreNode = StvnTypeResolver.resolveCoreValue(valueElement);
                                var finalLabel = StvnTypeResolver.resolveValueType(valueElement);
                                if (finalLabel != null && !finalLabel.isEmpty()) {
                                    var offset = calculateInlayBadgeOffset(valueElement, coreNode);
                                    var presentation = getFactory().text(finalLabel);
                                    sink.addInlineElement(offset, true, presentation, false);
                                }
                            }
                        }
                    });
                }
                return true;
            }
        };
    }

    /**
     * Computes the precise anchor offset for inlay badges:
     * - Head keyword offset for unspooled product/collection elements.
     * - Container end offset for true algebraic sum constructs.
     */
    private static int calculateInlayBadgeOffset(Value valueElement, @Nullable StvnValue coreNode) {
        var optPsi = valueElement.getExplicitOptionValue();
        if (optPsi != null && coreNode != null && StvnTypeResolver.isUnspooledContainer(optPsi, coreNode)) {
            var someLit = optPsi.getSomeLiteral();
            if (someLit != null) return someLit.getTextRange().getEndOffset();
            var someShortLit = optPsi.getSomeShortLiteral();
            if (someShortLit != null) return someShortLit.getTextRange().getEndOffset();
        }

        var eitherPsi = valueElement.getExplicitEitherValue();
        if (eitherPsi != null && coreNode != null && StvnTypeResolver.isUnspooledContainer(eitherPsi, coreNode)) {
            var leftLit = eitherPsi.getLeftLiteral();
            if (leftLit != null) return leftLit.getTextRange().getEndOffset();
            var leftShortLit = eitherPsi.getLeftShortLiteral();
            if (leftShortLit != null) return leftShortLit.getTextRange().getEndOffset();
            var rightLit = eitherPsi.getRightLiteral();
            if (rightLit != null) return rightLit.getTextRange().getEndOffset();
            var rightShortLit = eitherPsi.getRightShortLiteral();
            if (rightShortLit != null) return rightShortLit.getTextRange().getEndOffset();
        }

        var unionPsi = valueElement.getExplicitUnionValue();
        if (unionPsi != null && coreNode != null && StvnTypeResolver.isUnspooledContainer(unionPsi, coreNode)) {
            var tagPrefix = unionPsi.getUnionTagPrefix();
            if (tagPrefix != null) {
                return tagPrefix.getTextRange().getEndOffset();
            }
            var firstChild = unionPsi.getFirstChild();
            if (firstChild != null) {
                return firstChild.getTextRange().getEndOffset();
            }
        }

        return valueElement.getTextRange().getEndOffset();
    }

    private static boolean isInnerChildOfAlgebraicContainer(PsiElement element) {
        var curr = element.getParent();
        while (curr != null && !(curr instanceof PsiFile) && !(curr instanceof BodyEntry)) {
            if (curr instanceof ExplicitOptionValue || curr instanceof ExplicitEitherValue || curr instanceof ExplicitUnionValue) {
                var containerVal = (curr instanceof Value) ? (Value) curr : PsiTreeUtil.getParentOfType(curr, Value.class);
                if (containerVal != null) {
                    var coreNode = StvnTypeResolver.resolveCoreValue(containerVal);
                    if (coreNode != null && StvnTypeResolver.isUnspooledContainer(curr, coreNode)) {
                        curr = curr.getParent();
                        continue;
                    }
                }
                return true;
            }
            curr = curr.getParent();
        }
        return false;
    }
}
