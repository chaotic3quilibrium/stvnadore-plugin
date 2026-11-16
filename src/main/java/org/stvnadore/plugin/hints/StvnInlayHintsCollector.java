package org.stvnadore.plugin.hints;

import com.intellij.codeInsight.hints.FactoryInlayHintsCollector;
import com.intellij.codeInsight.hints.InlayHintsSink;
import com.intellij.codeInsight.hints.presentation.InlayPresentation;
import com.intellij.codeInsight.hints.presentation.StaticDelegatePresentation;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.settings.AliasExpansionMode;
import org.stvnadore.plugin.settings.StvnProjectSettings;
import org.stvnadore.plugin.util.StvnTypePresentationUtil;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.Value;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * Builds an inlay hint presentation for a {@code :body} value element, wrapping type segments in a
     * clickable PSI reference to {@code :defs} and inferred {@code [#tag]} sum steps in clickable
     * prefix-materializing badges.
     *
     * @param valueElement the payload value element
     * @param badgeText    the formatted inlay badge text
     * @return the constructed {@link InlayPresentation}
     */
    public @NotNull InlayPresentation buildInlayPresentation(@NotNull Value valueElement, @NotNull String badgeText) {
        var targetElement = StvnTypeResolver.resolveGoverningDeclarationTarget(valueElement);
        List<StvnTypeResolver.SumTrajectoryStep> sumSteps = StvnTypeResolver.resolveSumTrajectorySteps(valueElement);
        List<Integer> inferredStepIndices = new ArrayList<>();
        for (int i = 0; i < sumSteps.size(); i++) {
            if (sumSteps.get(i).inferred()) {
                inferredStepIndices.add(i);
            }
        }
        String inferredPrefixMarker = StvnVocabulary.DELIM_OPEN_BRACKET + StvnVocabulary.SIGIL_VALUE;
        if (inferredStepIndices.isEmpty() || !badgeText.contains(inferredPrefixMarker)) {
            return createTypeSegmentPresentation(badgeText, targetElement);
        }

        SmartPsiElementPointer<Value> valuePointer = SmartPointerManager
            .getInstance(valueElement.getProject())
            .createSmartPsiElementPointer(valueElement);
        List<InlayPresentation> segments = new ArrayList<>();
        int searchFrom = 0;
        for (int stepIdx : inferredStepIndices) {
            String expectedBadge = sumSteps.get(stepIdx).displayTag();
            int matchPos = badgeText.indexOf(expectedBadge, searchFrom);
            if (matchPos < 0) {
                continue;
            }
            if (matchPos > searchFrom) {
                segments.add(createTypeSegmentPresentation(badgeText.substring(searchFrom, matchPos), targetElement));
            }
            final int targetStepIndex = stepIdx;
            String prefixPreview = buildPrefixInsertionPreview(sumSteps, targetStepIndex);
            InlayPresentation tagTextPres = getFactory().text(expectedBadge);
            InlayPresentation withTip = getFactory().withTooltip(
                "Click to insert explicit '" + prefixPreview.trim() + "'",
                tagTextPres
            );
            InlayPresentation clickableTag = getFactory().referenceOnHover(
                withTip,
                (event, translated) -> {
                    Value liveVal = valuePointer.getElement();
                    if (liveVal != null && liveVal.isValid()) {
                        materializeInferredSumStep(liveVal, targetStepIndex);
                    }
                }
            );
            segments.add(clickableTag);
            searchFrom = matchPos + expectedBadge.length();
        }
        if (segments.isEmpty()) {
            return createTypeSegmentPresentation(badgeText, targetElement);
        }
        if (searchFrom < badgeText.length()) {
            segments.add(createTypeSegmentPresentation(badgeText.substring(searchFrom), targetElement));
        }
        InlayPresentation seq = getFactory().seq(segments.toArray(InlayPresentation[]::new));
        return new TextPreservingSequencePresentation(seq, badgeText);
    }

    /**
     * Materializes all unmaterialized inferred sum-constructor prefix tags in {@code [0, inclusiveStepIndex]}
     * for {@code valueElement} in descending document offset order so the resulting STVN payload compiles
     * with zero {@code E024} or {@code E023} inference diagnostics.
     *
     * @param valueElement       the target {@code :body} {@link Value} element
     * @param inclusiveStepIndex 0-based index of the clicked step within {@link StvnTypeResolver#resolveSumTrajectorySteps(Value)}
     * @return {@code true} if one or more inferred tags were inserted; {@code false} otherwise
     */
    public static boolean materializeInferredSumStep(@NotNull Value valueElement, int inclusiveStepIndex) {
        if (!valueElement.isValid()) {
            return false;
        }
        PsiFile file = valueElement.getContainingFile();
        if (file == null) {
            return false;
        }
        var doc = file.getViewProvider().getDocument();
        if (doc == null || !doc.isWritable()) {
            return false;
        }
        List<StvnTypeResolver.SumTrajectoryStep> steps = StvnTypeResolver.resolveSumTrajectorySteps(valueElement);
        if (steps.isEmpty() || inclusiveStepIndex < 0) {
            return false;
        }
        int limit = Math.min(inclusiveStepIndex, steps.size() - 1);
        Map<Integer, List<String>> tokensByOffset = new LinkedHashMap<>();
        for (int i = 0; i <= limit; i++) {
            var step = steps.get(i);
            if (step.inferred()) {
                tokensByOffset.computeIfAbsent(step.anchorStartOffset(), k -> new ArrayList<>()).add(step.insertToken());
            }
        }
        if (tokensByOffset.isEmpty()) {
            return false;
        }
        List<Map.Entry<Integer, List<String>>> orderedEdits = new ArrayList<>(tokensByOffset.entrySet());
        orderedEdits.sort(Comparator.comparingInt((Map.Entry<Integer, List<String>> e) -> e.getKey()).reversed());
        var project = valueElement.getProject();
        WriteCommandAction.runWriteCommandAction(
            project,
            "Insert Inferred Sum Tags",
            null,
            () -> {
                for (var entry : orderedEdits) {
                    int offset = entry.getKey();
                    if (offset >= 0 && offset <= doc.getTextLength()) {
                        String insertion = String.join(" ", entry.getValue()) + " ";
                        doc.insertString(offset, insertion);
                    }
                }
                PsiDocumentManager.getInstance(project).commitDocument(doc);
            },
            file
        );
        return true;
    }

    private @NotNull InlayPresentation createTypeSegmentPresentation(
        @NotNull String segmentText,
        @Nullable PsiElement targetElement
    ) {
        var baseText = getFactory().text(segmentText);
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

    private static String buildPrefixInsertionPreview(
        List<StvnTypeResolver.SumTrajectoryStep> steps,
        int inclusiveStepIndex
    ) {
        List<String> tokens = new ArrayList<>();
        int limit = Math.min(inclusiveStepIndex, steps.size() - 1);
        for (int i = 0; i <= limit; i++) {
            if (steps.get(i).inferred()) {
                tokens.add(steps.get(i).insertToken());
            }
        }
        return String.join(" ", tokens);
    }

    private static final class TextPreservingSequencePresentation extends StaticDelegatePresentation {
        private final String exactBadgeText;

        TextPreservingSequencePresentation(InlayPresentation delegate, String exactBadgeText) {
            super(delegate);
            this.exactBadgeText = exactBadgeText;
        }

        @Override
        public @NotNull String toString() {
            return exactBadgeText;
        }
    }
}
