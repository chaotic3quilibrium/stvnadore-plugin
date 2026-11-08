package org.stvnadore.plugin.hints;

import com.intellij.codeInsight.hints.FactoryInlayHintsCollector;
import com.intellij.codeInsight.hints.InlayHintsSink;
import com.intellij.codeInsight.hints.presentation.InlayPresentation;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.settings.AliasExpansionMode;
import org.stvnadore.plugin.settings.StvnProjectSettings;
import org.stvnadore.plugin.util.StvnTypePresentationUtil;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.Value;

/**
 * Collects and renders contextual type hints inside STVN body blocks
 * using bounded depth traversal for mutually recursive schemas.
 */
@NullMarked
public final class StvnInlayHintsCollector extends FactoryInlayHintsCollector {

    /**
     * Constructs a hints collector for the specified editor.
     *
     * @param editor the active text editor
     */
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
                        if (!StvnPsiUtils.isInsideBody(valueElement)) {
                            return;
                        }
                        if (StvnTypeInferenceHelper.isInnerChildOfAlgebraicContainer(valueElement)) {
                            return;
                        }

                        var coreNode = StvnTypeResolver.resolveCoreValue(valueElement);
                        if (coreNode instanceof org.stvnadore.core.ir.StvnValue.StvnErrorValue err) {
                            if (!StvnTypeResolver.isRecoverableCoreError(err, valueElement)) {
                                return;
                            }
                        }

                        var baseInfo = StvnTypeResolver.resolveBaseTypeInfo(valueElement);
                        if (baseInfo != null && !StvnTypeInferenceHelper.isStructurallyCompatible(valueElement, baseInfo.getSchema())) {
                            return;
                        }

                        var finalLabel = StvnTypeInferenceHelper.resolveValueTypeWithDepth(valueElement, 16);
                        if (finalLabel != null && !finalLabel.isEmpty()) {
                            var projSettings = StvnProjectSettings.getInstance(valueElement.getProject());
                            var mode = projSettings != null ? projSettings.getState().aliasExpansionMode : AliasExpansionMode.FULL;
                            var formatted = StvnTypePresentationUtil.formatAliasChain(finalLabel, mode);
                            if (formatted.isPresent()) {
                                var offset = StvnTypeInferenceHelper.calculateInlayBadgeOffset(valueElement, coreNode);
                                var presentation = buildInlayPresentation(valueElement, formatted.get());
                                sink.addInlineElement(offset, true, presentation, false);
                            }
                        }
                    }
                }
            });
        }
        return true;
    }

    /**
     * Builds an inlay hint presentation for a {@code :body} value element, wrapping it in a clickable
     * PSI reference when the value is governed by a nominal {@code TypeDefinition} or inline member
     * {@code SchemaType} in {@code :defs}.
     *
     * @param valueElement the payload value element
     * @param badgeText    the formatted inlay badge text
     * @return the constructed {@link InlayPresentation}
     */
    public @NotNull InlayPresentation buildInlayPresentation(@NotNull Value valueElement, @NotNull String badgeText) {
        var baseText = getFactory().text(badgeText);
        var targetElement = StvnTypeResolver.resolveGoverningDeclarationTarget(valueElement);
        if (targetElement == null) {
            return baseText;
        }
        return getFactory().psiSingleReference(baseText, () -> {
            if (targetElement instanceof TypeDefinition targetTypeDef) {
                return targetTypeDef.getTypeKeyword() != null ? targetTypeDef.getTypeKeyword() : targetTypeDef;
            }
            return targetElement;
        });
    }
}
