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
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.Value;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects and renders contextual type hints inside STVN body blocks
 * using bounded depth traversal for mutually recursive schemas.
 */
@NullMarked
public final class StvnInlayHintsCollector extends FactoryInlayHintsCollector {

    private static final Pattern TYPE_TOKEN_PATTERN = Pattern.compile(
        StvnVocabulary.SIGIL_TYPIC + "[a-zA-Z_][a-zA-Z0-9_]*(?:/[a-zA-Z_][a-zA-Z0-9_]*)*"
    );

    /**
     * Associates a displayed {@code :Type} token inside an inlay badge with its navigable PSI declaration target.
     *
     * @param tokenText     the displayed {@code :Type} identifier
     * @param targetElement the navigable PSI declaration target
     */
    public record ClickableTypeSegment(String tokenText, PsiElement targetElement) {
    }

    /**
     * Describes a clickable {@code :body}-level inferred {@code [#tag]} segment bound inside an inlay badge.
     *
     * @param stepIndex    0-based index within {@link StvnTypeResolver#resolveSumTrajectorySteps(Value)}
     * @param badgeToken   rendered bracketed tag token (for example {@code "[#Some]"})
     * @param startInBadge inclusive start character offset within {@code badgeText}
     * @param endInBadge   exclusive end character offset within {@code badgeText}
     */
    public record MaterializableTagSegment(
        int stepIndex,
        String badgeToken,
        int startInBadge,
        int endInBadge
    ) {
    }

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
            var rootSettings = StvnProjectSettings.getInstance(element.getProject());
            if (rootSettings != null && !rootSettings.getState().showTypeHints) {
                return true;
            }
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
        Map<String, PsiElement> hopTargets = StvnTypeResolver.resolveClickableTypeTargetsForValue(valueElement);
        List<StvnTypeResolver.SumTrajectoryStep> sumSteps = StvnTypeResolver.resolveSumTrajectorySteps(valueElement);
        List<MaterializableTagSegment> boundTagSegments = extractBoundMaterializableTagSegments(valueElement, badgeText);
        if (boundTagSegments.isEmpty()) {
            return buildMultiHopOrSinglePresentation(badgeText, targetElement, hopTargets, false, true);
        }

        SmartPsiElementPointer<Value> valuePointer = SmartPointerManager
            .getInstance(valueElement.getProject())
            .createSmartPsiElementPointer(valueElement);
        List<InlayPresentation> segments = new ArrayList<>();
        int searchFrom = 0;
        for (MaterializableTagSegment tagSeg : boundTagSegments) {
            int matchPos = tagSeg.startInBadge();
            String expectedBadge = tagSeg.badgeToken();
            if (matchPos > searchFrom) {
                segments.add(buildMultiHopOrSinglePresentation(
                    badgeText.substring(searchFrom, matchPos),
                    targetElement,
                    hopTargets,
                    true,
                    false
                ));
            }
            final int targetStepIndex = tagSeg.stepIndex();
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
            searchFrom = tagSeg.endInBadge();
        }
        if (segments.isEmpty()) {
            return buildMultiHopOrSinglePresentation(badgeText, targetElement, hopTargets, false, true);
        }
        if (searchFrom < badgeText.length()) {
            segments.add(buildMultiHopOrSinglePresentation(
                badgeText.substring(searchFrom),
                targetElement,
                hopTargets,
                true,
                false
            ));
        }
        InlayPresentation seq = getFactory().seq(segments.toArray(InlayPresentation[]::new));
        return new TextPreservingSequencePresentation(seq, badgeText);
    }

    /**
     * Resolves the ordered list of clickable {@code :body}-level inferred {@code [#tag]} segments
     * bound inside {@code badgeText} for {@code valueElement}, strictly excluding {@code #CONST}-internal
     * {@code [#tag]} tokens.
     *
     * @param valueElement the payload {@link Value} element
     * @param badgeText    the rendered inlay badge string
     * @return immutable list of bound {@link MaterializableTagSegment} entries
     */
    public static List<MaterializableTagSegment> extractBoundMaterializableTagSegments(
        @NotNull Value valueElement,
        @NotNull String badgeText
    ) {
        List<StvnTypeResolver.SumTrajectoryStep> sumSteps = StvnTypeResolver.resolveSumTrajectorySteps(valueElement);
        boolean hasBodyInferredSteps = sumSteps.stream().anyMatch(StvnTypeResolver.SumTrajectoryStep::inferred);
        int bodyBarrierLimit = StvnTypeResolver.resolveConstBarrierStartInBadge(valueElement, badgeText);
        String inferredPrefixMarker = StvnVocabulary.DELIM_OPEN_BRACKET + StvnVocabulary.SIGIL_VALUE;
        if (!hasBodyInferredSteps || bodyBarrierLimit <= 0 || !badgeText.contains(inferredPrefixMarker)) {
            return List.of();
        }
        List<MaterializableTagSegment> bound = new ArrayList<>();
        int cursorInBodyPrefix = StvnTypeResolver.resolveBodyTagScanStartInBadge(valueElement, badgeText);
        for (int stepIdx = 0; stepIdx < sumSteps.size(); stepIdx++) {
            var step = sumSteps.get(stepIdx);
            String expectedBadge = step.displayTag();
            int matchPos = badgeText.indexOf(expectedBadge, cursorInBodyPrefix);
            if (matchPos < 0 || matchPos + expectedBadge.length() > bodyBarrierLimit) {
                break;
            }
            int matchEnd = matchPos + expectedBadge.length();
            if (step.inferred()) {
                bound.add(new MaterializableTagSegment(stepIdx, expectedBadge, matchPos, matchEnd));
            }
            cursorInBodyPrefix = matchEnd;
        }
        return List.copyOf(bound);
    }

    /**
     * Resolves the ordered list of clickable {@code :Type} segments bound inside {@code badgeText}
     * for {@code valueElement}.
     *
     * @param valueElement the payload {@link Value} element
     * @param badgeText    the rendered inlay badge string
     * @return immutable list of bound {@link ClickableTypeSegment} entries in left-to-right badge order
     */
    public static List<ClickableTypeSegment> extractBoundClickableTypeSegments(
        @NotNull Value valueElement,
        @NotNull String badgeText
    ) {
        var fallbackTarget = StvnTypeResolver.resolveGoverningDeclarationTarget(valueElement);
        Map<String, PsiElement> hopTargets = StvnTypeResolver.resolveClickableTypeTargetsForValue(valueElement);
        List<ClickableTypeSegment> bound = new ArrayList<>();
        Matcher matcher = TYPE_TOKEN_PATTERN.matcher(badgeText);
        while (matcher.find()) {
            String token = matcher.group();
            if (StvnTypeResolver.isReservedFundamentalType(token)) {
                continue;
            }
            PsiElement target = hopTargets.get(token);
            if (target == null && hopTargets.isEmpty() && matcher.start() == 0) {
                target = fallbackTarget;
            }
            if (target instanceof TypeDefinition targetTypeDef && targetTypeDef.getTypeKeyword() != null) {
                target = targetTypeDef.getTypeKeyword();
            }
            if (target != null) {
                bound.add(new ClickableTypeSegment(token, target));
            }
        }
        return List.copyOf(bound);
    }

    private @NotNull InlayPresentation buildMultiHopOrSinglePresentation(
        @NotNull String segmentText,
        @Nullable PsiElement fallbackTarget,
        @NotNull Map<String, PsiElement> hopTargets,
        boolean isSubSegment,
        boolean wrapPreservingToString
    ) {
        if (!isSubSegment && !segmentText.contains("->") && fallbackTarget instanceof SchemaType) {
            return createTypeSegmentPresentation(segmentText, fallbackTarget);
        }
        Matcher matcher = TYPE_TOKEN_PATTERN.matcher(segmentText);
        List<InlayPresentation> parts = new ArrayList<>();
        int cursor = 0;
        boolean linkedAny = false;
        while (matcher.find()) {
            String token = matcher.group();
            if (StvnTypeResolver.isReservedFundamentalType(token)) {
                continue;
            }
            PsiElement specificTarget = hopTargets.get(token);
            if (specificTarget == null && hopTargets.isEmpty() && matcher.start() == 0) {
                specificTarget = fallbackTarget;
            }
            if (specificTarget != null) {
                if (matcher.start() > cursor) {
                    parts.add(getFactory().text(segmentText.substring(cursor, matcher.start())));
                }
                parts.add(createTypeSegmentPresentation(token, specificTarget));
                cursor = matcher.end();
                linkedAny = true;
            }
        }
        if (!linkedAny) {
            return isSubSegment ? getFactory().text(segmentText) : createTypeSegmentPresentation(segmentText, fallbackTarget);
        }
        if (cursor < segmentText.length()) {
            parts.add(getFactory().text(segmentText.substring(cursor)));
        }
        if (parts.size() == 1) {
            return parts.getFirst();
        }
        InlayPresentation seq = getFactory().seq(parts.toArray(InlayPresentation[]::new));
        return wrapPreservingToString ? new TextPreservingSequencePresentation(seq, segmentText) : seq;
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
