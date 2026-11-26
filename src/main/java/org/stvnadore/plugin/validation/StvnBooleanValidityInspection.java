package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnConstantReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.*;

import java.util.Locale;
import java.util.Set;

/**
 * Inspects boolean value literals and keywords to ensure canonical representations.
 */
@NullMarked
public final class StvnBooleanValidityInspection extends LocalInspectionTool {

    /**
     * Constructs a new StvnBooleanValidityInspection instance.
     */
    public StvnBooleanValidityInspection() {
    }

    private static final Set<String> BOOLEAN_WHITELIST = Set.of(
        StvnVocabulary.VAL_TRUE,
        StvnVocabulary.VAL_TRUE_SHORT,
        StvnVocabulary.VAL_FALSE,
        StvnVocabulary.VAL_FALSE_SHORT
    );

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                super.visitElement(element);
                if (element.getFirstChild() == null) {
                    checkBareBooleanIdentifier(element, holder);
                }
            }

            @Override
            public void visitValueKeyword(@NotNull ValueKeyword element) {
                checkBooleanValidity(element, holder);
            }

            @Override
            public void visitBooleanValue(@NotNull BooleanValue element) {
                checkBooleanValidity(element, holder);
            }

            @Override
            public void visitTrueShortLiteral(@NotNull TrueShortLiteral element) {
                checkBooleanValidity(element, holder);
            }

            @Override
            public void visitFalseShortLiteral(@NotNull FalseShortLiteral element) {
                checkBooleanValidity(element, holder);
            }
        };
    }

    /**
     * Determines whether {@code offset} in {@code file} points to a bare un-sigiled boolean
     * {@code IDENTIFIER} token governed by {@link StvnBooleanValidityInspection} (primary {@code BodyEntry}
     * after {@code :body}, primary {@code ConstantDefinition} RHS after {@code schemaType}, or primary
     * {@code MetadataTrait} or {@code MetadataBool} after its facet keyword, excluding path segments after {@code '/'}).
     *
     * @param file   PSI file under evaluation
     * @param offset document start offset of the diagnostic
     * @return {@code true} if governed by {@link StvnBooleanValidityInspection}
     */
    public static boolean isGovernedBareBooleanOffset(PsiFile file, int offset) {
        if (offset < 0 || offset >= file.getTextLength()) {
            return false;
        }
        PsiElement leaf = file.findElementAt(offset);
        return leaf != null && isGovernedBareBooleanLeaf(leaf);
    }

    /**
     * Determines whether {@code offset} in {@code file} points to a sigiled near-miss boolean
     * {@link ValueKeyword} ({@code #true}, {@code #false}, {@code #True}, {@code #False}, {@code #t}, {@code #f})
     * governed by {@link StvnBooleanValidityInspection}.
     *
     * @param file   PSI file under evaluation
     * @param offset document start offset of the diagnostic
     * @return {@code true} if governed by {@link StvnBooleanValidityInspection}
     */
    public static boolean isGovernedSigiledBooleanOffset(PsiFile file, int offset) {
        if (offset < 0 || offset >= file.getTextLength()) {
            return false;
        }
        PsiElement leaf = file.findElementAt(offset);
        if (leaf == null) {
            return false;
        }
        ValueKeyword valKw = leaf instanceof ValueKeyword vk
            ? vk
            : PsiTreeUtil.getParentOfType(leaf, ValueKeyword.class, false);
        if (valKw == null || StvnMetaTemplateUtil.isInSecondaryRootSection(valKw)) {
            return false;
        }
        String text = valKw.getText();
        if (BOOLEAN_WHITELIST.contains(text)) {
            return false;
        }
        var resolved = StvnConstantReference.resolveConstantWithContext(valKw);
        if (StvnPsiUtils.isConstantDefinitionTarget(resolved)
                || StvnPsiUtils.resolveTargetConstantDefinition(resolved) != null) {
            return false;
        }
        if (isNearMissSigiledBoolean(text) && isInsideBooleanMetadataFacetValue(valKw)) {
            return true;
        }
        Value valueParent = PsiTreeUtil.getParentOfType(valKw, Value.class);
        return valueParent != null && StvnTypeResolver.resolvesToBoolean(valueParent);
    }

    /**
     * Creates the two toggle-ordered quick-fixes for replacing a bare un-sigiled boolean token
     * with {@code #TRUE}/{@code #FALSE} and {@code #T}/{@code #F}, shared across Pass B
     * ({@link StvnBooleanValidityInspection}) and Pass A ({@code StvnExternalAnnotator}).
     *
     * @param leaf    offending {@code IDENTIFIER} PSI leaf
     * @param project active IntelliJ project
     * @return two ordered quick-fix actions supporting both live {@code Document} edits and {@code IntentionPreviewInfo.DIFF}
     */
    public static LocalQuickFixAndIntentionActionOnPsiElement[] createBareBooleanQuickFixes(
            PsiElement leaf,
            Project project
    ) {
        String rawText = leaf.getText();
        boolean isTrue = "true".equalsIgnoreCase(rawText);
        String longTag = isTrue ? StvnVocabulary.VAL_TRUE : StvnVocabulary.VAL_FALSE;
        String shortTag = isTrue ? StvnVocabulary.VAL_TRUE_SHORT : StvnVocabulary.VAL_FALSE_SHORT;
        boolean useLong = StvnTypeResolver.useLongFormSumTypes(project);
        String primaryTag = useLong ? longTag : shortTag;
        String secondaryTag = useLong ? shortTag : longTag;
        return new LocalQuickFixAndIntentionActionOnPsiElement[]{
            new ReplaceBooleanTokenQuickFix(
                leaf,
                primaryTag,
                "Replace '" + rawText + "' with '" + primaryTag + "'",
                "Replace bare boolean with canonical STVN boolean",
                PriorityAction.Priority.TOP
            ),
            new ReplaceBooleanTokenQuickFix(
                leaf,
                secondaryTag,
                "Replace '" + rawText + "' with '" + secondaryTag + "'",
                "Replace bare boolean with canonical STVN boolean",
                PriorityAction.Priority.HIGH
            )
        };
    }

    /**
     * Creates the canonical casing quick-fix for a sigiled near-miss boolean token ({@code #true}, {@code #false},
     * {@code #True}, {@code #False}, {@code #t}, {@code #f}) across {@code Value} and boolean metadata facet slots.
     *
     * @param element offending PSI element
     * @param project active IntelliJ project
     * @return quick-fix action supporting both live edits and {@code IntentionPreviewInfo.DIFF}
     */
    public static LocalQuickFixAndIntentionActionOnPsiElement createSigiledNearMissQuickFix(
            PsiElement element,
            Project project
    ) {
        String lower = element.getText().toLowerCase(Locale.ROOT);
        boolean isTrue = lower.equals(StvnVocabulary.SIGIL_VALUE + "true")
            || lower.equals(StvnVocabulary.SIGIL_VALUE + "t");
        boolean useLong = StvnTypeResolver.useLongFormSumTypes(project);
        String targetTag = isTrue
            ? (useLong ? StvnVocabulary.VAL_TRUE : StvnVocabulary.VAL_TRUE_SHORT)
            : (useLong ? StvnVocabulary.VAL_FALSE : StvnVocabulary.VAL_FALSE_SHORT);
        return new ReplaceBooleanTokenQuickFix(
            element,
            targetTag,
            "Change tag to " + targetTag,
            "Change variant tag form",
            PriorityAction.Priority.TOP
        );
    }

    private static void checkBareBooleanIdentifier(PsiElement leaf, ProblemsHolder holder) {
        if (!isGovernedBareBooleanLeaf(leaf)) {
            return;
        }
        String rawText = leaf.getText();
        boolean isTrue = "true".equalsIgnoreCase(rawText);
        String canonicalForm = isTrue ? StvnVocabulary.VAL_TRUE : StvnVocabulary.VAL_FALSE;
        String shortForm = isTrue ? StvnVocabulary.VAL_TRUE_SHORT : StvnVocabulary.VAL_FALSE_SHORT;
        String coreAlignedMessage = "STVN Syntax Error: Bare boolean literal '" + rawText
            + "' is prohibited in STVN syntax; use '" + canonicalForm + "' (or '" + shortForm + "')";
        holder.registerProblem(
            leaf,
            StvnDiagnosticFormatter.formatPlugin(
                coreAlignedMessage,
                StvnDiagnosticCodes.ERR_BARE_BOOLEAN_PROHIBITED
            ),
            ProblemHighlightType.GENERIC_ERROR,
            createBareBooleanQuickFixes(leaf, holder.getProject())
        );
    }

    private static boolean isGovernedBareBooleanLeaf(PsiElement leaf) {
        if (leaf.getNode() == null || leaf.getNode().getElementType() != StvnTypes.IDENTIFIER) {
            return false;
        }
        String rawText = leaf.getText();
        if (!"true".equalsIgnoreCase(rawText) && !"false".equalsIgnoreCase(rawText)) {
            return false;
        }
        if (StvnMetaTemplateUtil.isInSecondaryRootSection(leaf)) {
            return false;
        }
        PsiElement prevVisible = prevVisibleLeaf(leaf);
        if (prevVisible != null && prevVisible.getNode() != null
                && prevVisible.getNode().getElementType() == StvnTypes.FSLASH) {
            return false;
        }
        int leafStart = leaf.getTextRange().getStartOffset();

        // 1. Boolean metadata trait/capability entries (#preserveIndent true, #equatable false, #comparable True)
        MetadataTrait trait = PsiTreeUtil.getParentOfType(leaf, MetadataTrait.class, false);
        if (trait != null) {
            var kwNode = trait.getNode().findChildByType(StvnTypes.KW_PRESERVE_INDENT);
            return kwNode != null && leafStart >= kwNode.getTextRange().getEndOffset();
        }
        MetadataBool boolFacet = PsiTreeUtil.getParentOfType(leaf, MetadataBool.class, false);
        if (boolFacet != null) {
            var kwNode = boolFacet.getNode().findChildByType(StvnTypes.KW_EQUATABLE);
            if (kwNode == null) {
                kwNode = boolFacet.getNode().findChildByType(StvnTypes.KW_COMPARABLE);
            }
            return kwNode != null && leafStart >= kwNode.getTextRange().getEndOffset();
        }
        if (PsiTreeUtil.getParentOfType(leaf, MetadataMap.class, false) != null) {
            return false;
        }

        // 2. Primary BodyEntry (strictly after :body keyword)
        BodyEntry bodyEntry = PsiTreeUtil.getParentOfType(leaf, BodyEntry.class, false);
        if (bodyEntry != null) {
            var kwBody = bodyEntry.getNode().findChildByType(StvnTypes.KW_BODY);
            return kwBody != null && leafStart >= kwBody.getTextRange().getEndOffset();
        }

        // 3. Primary ConstantDefinition RHS value slot (strictly after schemaType)
        ConstantDefinition constDef = PsiTreeUtil.getParentOfType(leaf, ConstantDefinition.class, false);
        if (constDef != null && constDef.getSchemaType() != null) {
            return leafStart >= constDef.getSchemaType().getTextRange().getEndOffset();
        }
        return false;
    }

    private static @Nullable PsiElement prevVisibleLeaf(PsiElement element) {
        PsiElement cur = PsiTreeUtil.prevLeaf(element);
        while (cur != null && (cur instanceof PsiWhiteSpace || cur instanceof PsiComment || cur.getTextLength() == 0)) {
            cur = PsiTreeUtil.prevLeaf(cur);
        }
        return cur;
    }

    private static boolean isNearMissSigiledBoolean(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.equals(StvnVocabulary.SIGIL_VALUE + "true")
            || lower.equals(StvnVocabulary.SIGIL_VALUE + "false")
            || lower.equals(StvnVocabulary.SIGIL_VALUE + "t")
            || lower.equals(StvnVocabulary.SIGIL_VALUE + "f");
    }

    private static boolean isInsideBooleanMetadataFacetValue(PsiElement element) {
        MetadataValue mv = PsiTreeUtil.getParentOfType(element, MetadataValue.class, false);
        if (mv == null) {
            return false;
        }
        PsiElement parent = mv.getParent();
        return parent instanceof MetadataTrait || parent instanceof MetadataBool;
    }

    private static void checkBooleanValidity(PsiElement element, ProblemsHolder holder) {
        if (StvnMetaTemplateUtil.isInSecondaryRootSection(element)) {
            return;
        }
        if (element.getParent() instanceof BooleanValue) {
            return;
        }

        var text = element.getText();
        if (BOOLEAN_WHITELIST.contains(text)) {
            return;
        }
        if (element instanceof ValueKeyword valKw) {
            var resolved = StvnConstantReference.resolveConstantWithContext(valKw);
            if (StvnPsiUtils.isConstantDefinitionTarget(resolved)
                    || StvnPsiUtils.resolveTargetConstantDefinition(resolved) != null) {
                return;
            }
        }

        boolean inBoolMetadataFacet = isInsideBooleanMetadataFacetValue(element);
        var valueParent = PsiTreeUtil.getParentOfType(element, Value.class);
        if (!inBoolMetadataFacet) {
            if (valueParent == null || !StvnTypeResolver.resolvesToBoolean(valueParent)) {
                return;
            }
        } else if (!isNearMissSigiledBoolean(text)) {
            return;
        }

        var useLong = StvnTypeResolver.useLongFormSumTypes(holder.getProject());
        var isNearMiss = isNearMissSigiledBoolean(text);

        if (isNearMiss) {
            var message = StvnDiagnosticFormatter.formatPlugin(
                "Invalid boolean literal casing: '" + text + "'. Valid forms are case-sensitive: "
                    + StvnVocabulary.VAL_TRUE + ", " + StvnVocabulary.VAL_TRUE_SHORT + ", "
                    + StvnVocabulary.VAL_FALSE + ", " + StvnVocabulary.VAL_FALSE_SHORT,
                StvnDiagnosticCodes.ERR_INVALID_BOOLEAN_CASING
            );
            holder.registerProblem(
                element,
                message,
                ProblemHighlightType.GENERIC_ERROR,
                createSigiledNearMissQuickFix(element, holder.getProject())
            );
        } else {
            var message = StvnDiagnosticFormatter.formatPlugin(
                "Value '" + text + "' is not a valid boolean literal. Expected exactly "
                    + StvnVocabulary.VAL_TRUE + ", " + StvnVocabulary.VAL_TRUE_SHORT + ", "
                    + StvnVocabulary.VAL_FALSE + ", or " + StvnVocabulary.VAL_FALSE_SHORT,
                StvnDiagnosticCodes.ERR_INVALID_BOOLEAN_LITERAL
            );
            if (useLong) {
                holder.registerProblem(
                    element,
                    message,
                    ProblemHighlightType.GENERIC_ERROR,
                    new ChangeToBooleanLiteralQuickFix(StvnVocabulary.VAL_TRUE),
                    new ChangeToBooleanLiteralQuickFix(StvnVocabulary.VAL_FALSE)
                );
            } else {
                holder.registerProblem(
                    element,
                    message,
                    ProblemHighlightType.GENERIC_ERROR,
                    new ChangeToBooleanLiteralQuickFix(StvnVocabulary.VAL_TRUE_SHORT),
                    new ChangeToBooleanLiteralQuickFix(StvnVocabulary.VAL_FALSE_SHORT)
                );
            }
        }
    }

    private static final class ReplaceBooleanTokenQuickFix
            extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {
        @SafeFieldForPreview
        private final String targetTag;
        @SafeFieldForPreview
        private final String label;
        @SafeFieldForPreview
        private final String familyName;
        @SafeFieldForPreview
        private final Priority priority;

        ReplaceBooleanTokenQuickFix(
                PsiElement element,
                String targetTag,
                String label,
                String familyName,
                Priority priority
        ) {
            super(element);
            this.targetTag = targetTag;
            this.label = label;
            this.familyName = familyName;
            this.priority = priority;
        }

        @Override
        public @NotNull String getText() {
            return label;
        }

        @Override
        public @NotNull String getFamilyName() {
            return familyName;
        }

        @Override
        public @NotNull Priority getPriority() {
            return priority;
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            if (!startElement.isValid()) {
                return;
            }
            if (IntentionPreviewUtils.isPreviewElement(startElement)) {
                if (startElement instanceof LeafPsiElement leaf) {
                    leaf.replaceWithText(targetTag);
                    return;
                }
                PsiElement firstLeaf = PsiTreeUtil.getDeepestFirst(startElement);
                PsiElement lastLeaf = PsiTreeUtil.getDeepestLast(startElement);
                if (firstLeaf == lastLeaf && firstLeaf instanceof LeafPsiElement singleLeaf) {
                    singleLeaf.replaceWithText(targetTag);
                    return;
                }
            }
            var doc = file.getViewProvider().getDocument();
            if (doc != null && !IntentionPreviewUtils.isPreviewElement(startElement)) {
                var range = startElement.getTextRange();
                doc.replaceString(range.getStartOffset(), range.getEndOffset(), targetTag);
                PsiDocumentManager.getInstance(project).commitDocument(doc);
                return;
            }
            var valueParent = PsiTreeUtil.getParentOfType(startElement, Value.class, false);
            if (valueParent != null) {
                valueParent.replace(StvnElementFactory.createValue(project, targetTag));
            }
        }
    }

    private static final class ChangeToBooleanLiteralQuickFix implements LocalQuickFix {
        private final String targetTag;

        public ChangeToBooleanLiteralQuickFix(String targetTag) {
            this.targetTag = targetTag;
        }

        @Override
        public @NotNull String getName() {
            return "Change to " + targetTag;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Change to boolean literal";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element == null) {
                return;
            }
            var valueParent = PsiTreeUtil.getParentOfType(element, Value.class);
            if (valueParent != null) {
                if (!StvnTypeResolver.resolvesToBoolean(valueParent)) {
                    return;
                }
                var dummy = StvnElementFactory.createValue(project, targetTag);
                valueParent.replace(dummy);
            }
        }
    }
}
