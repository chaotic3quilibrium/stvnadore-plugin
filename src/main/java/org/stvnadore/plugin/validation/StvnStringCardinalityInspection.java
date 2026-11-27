package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Validates STVN 2.0.0 string cardinality rules (STVN Spec § 6.1).
 * Flags prohibited '#size' facets on ':String' and offers intention quick-fixes.
 */
@NullMarked
public final class StvnStringCardinalityInspection extends LocalInspectionTool {

    /**
     * Constructs a new StvnStringCardinalityInspection instance.
     */
    public StvnStringCardinalityInspection() {
    }

    @Override
    public @NotNull String getShortName() {
        return "StvnStringCardinality";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "String cardinality facet governance inspection";
    }

    @Override
    public @NotNull String getGroupDisplayName() {
        return "STVN";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitTypeDefinition(@NotNull TypeDefinition typeDef) {
                super.visitTypeDefinition(typeDef);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(typeDef)) {
                    return;
                }
                var schemaType = typeDef.getSchemaType();
                var metaMap = typeDef.getMetadataMap() != null
                        ? typeDef.getMetadataMap()
                        : (schemaType != null ? schemaType.getMetadataMap() : null);
                if (schemaType != null && metaMap != null) {
                    validateStringCardinality(schemaType, metaMap, holder);
                }
            }

            @Override
            public void visitConstantDefinition(@NotNull ConstantDefinition constDef) {
                super.visitConstantDefinition(constDef);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(constDef)) {
                    return;
                }
                var schemaType = constDef.getSchemaType();
                var metaMap = constDef.getMetadataMap() != null
                        ? constDef.getMetadataMap()
                        : (schemaType != null ? schemaType.getMetadataMap() : null);
                if (schemaType != null && metaMap != null) {
                    validateStringCardinality(schemaType, metaMap, holder);
                }
            }

            @Override
            public void visitSchemaType(@NotNull SchemaType schemaType) {
                super.visitSchemaType(schemaType);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(schemaType)) {
                    return;
                }
                if (schemaType.getParent() instanceof TypeDefinition
                        || schemaType.getParent() instanceof ConstantDefinition) {
                    return;
                }
                var metaMap = schemaType.getMetadataMap();
                if (metaMap == null) {
                    return;
                }
                validateStringCardinality(schemaType, metaMap, holder);
            }
        };
    }

    private static void validateStringCardinality(
            @NotNull SchemaType schemaType,
            @NotNull MetadataMap metaMap,
            @NotNull ProblemsHolder holder
    ) {
        var baseType = resolveBaseTypeString(schemaType);
        if (!baseType.equals(StvnVocabulary.TYPE_STRING)) {
            return;
        }
        boolean isNominalDerivation = schemaType.getSchemaConstructor() == null;
        String coreParityMsg = "Facet '" + StvnVocabulary.FACET_KW_SIZE + "' is prohibited on "
            + StvnVocabulary.TYPE_STRING + "; use '" + StvnVocabulary.FACET_KW_MIN_SIZE
            + "' and '" + StvnVocabulary.FACET_KW_MAX_SIZE + "' (e.g. { "
            + StvnVocabulary.FACET_KW_MIN_SIZE + " N " + StvnVocabulary.FACET_KW_MAX_SIZE
            + " N } " + StvnVocabulary.TYPE_STRING + ")";

        for (var entry : metaMap.getMetadataEntryList()) {
            String kw = StvnMetadataOrderInspection.extractFacetKeyword(entry);
            if (StvnVocabulary.FACET_KW_SIZE.equals(kw)
                    || entry.getNode().findChildByType(StvnTypes.KW_SIZE) != null) {
                List<LocalQuickFix> fixes = createCardinalityQuickFixes(entry, isNominalDerivation);
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        coreParityMsg,
                        StvnDiagnosticCodes.ERR_STRING_CARDINALITY_PROHIBITED
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    StvnMetadataOrderInspection.appendReorderFixIfDisordered(
                        entry,
                        fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                    )
                );
            }
        }
    }

    /**
     * Determines whether {@code entry} resides on a derived nominal {@code :String} alias
     * rather than a direct {@code :String} constructor.
     *
     * @param entry target {@code #size} metadata entry
     * @return {@code true} if the governing schema is a nominal type reference; {@code false} if direct constructor
     */
    public static boolean isNominalStringDerivation(MetadataEntry entry) {
        MetadataMap metaMap = PsiTreeUtil.getParentOfType(entry, MetadataMap.class);
        if (metaMap == null) {
            return false;
        }
        PsiElement owner = metaMap.getParent();
        SchemaType schemaType = switch (owner) {
            case SchemaType st -> st;
            case TypeDefinition td -> td.getSchemaType();
            case ConstantDefinition cd -> cd.getSchemaType();
            case TypeEntry te -> te.getSchemaType();
            case null, default -> null;
        };
        return schemaType != null && schemaType.getSchemaConstructor() == null;
    }

    /**
     * Builds the deterministic quick-fix suite for a prohibited {@code #size} facet on {@code :String}
     * across both {@code Pass A} ({@link StvnExternalAnnotator}) and {@code Pass B} ({@link StvnStringCardinalityInspection}).
     *
     * @param entry               the offending {@code #size} metadata entry
     * @param isNominalDerivation {@code true} when declared on a nominal alias rather than direct {@code :String}
     * @return ordered quick-fixes
     */
    public static List<LocalQuickFix> createCardinalityQuickFixes(
            MetadataEntry entry,
            boolean isNominalDerivation
    ) {
        String rawSnippet = entry.getText().trim();
        if (isNominalDerivation) {
            return List.of(new RemoveRedundantFacetQuickFix(
                entry,
                rawSnippet,
                "Remove prohibited facet",
                PriorityAction.Priority.HIGH
            ));
        }
        MetadataValue val = entry.getMetadataValue();
        if (val == null) {
            return List.of(new RemoveRedundantFacetQuickFix(
                entry,
                rawSnippet,
                "Remove prohibited facet",
                PriorityAction.Priority.HIGH
            ));
        }
        String argText = val.getText().trim();
        BigInteger resolvedN;
        String maxToken;
        if (val.getIntegerLiteral() != null) {
            resolvedN = StvnPsiLiteralEvaluator.parseBigInteger(argText).orElse(null);
            // Safeguard 1: Under STVN Spec § 6.1, :String #maxSize requires n >= 1; never generate #maxSize 0
            if (resolvedN == null || resolvedN.signum() <= 0) {
                return List.of(new RemoveRedundantFacetQuickFix(
                    entry,
                    rawSnippet,
                    "Remove prohibited facet",
                    PriorityAction.Priority.HIGH
                ));
            }
            maxToken = resolvedN.toString();
        } else if (val.getValueKeyword() != null && argText.startsWith(StvnVocabulary.SIGIL_VALUE)) {
            resolvedN = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(entry, argText).orElse(null);
            if (resolvedN != null && resolvedN.signum() <= 0) {
                return List.of(new RemoveRedundantFacetQuickFix(
                    entry,
                    rawSnippet,
                    "Remove prohibited facet",
                    PriorityAction.Priority.HIGH
                ));
            }
            maxToken = argText;
        } else {
            return List.of(new RemoveRedundantFacetQuickFix(
                entry,
                rawSnippet,
                "Remove prohibited facet",
                PriorityAction.Priority.HIGH
            ));
        }

        MetadataMap metaMap = PsiTreeUtil.getParentOfType(entry, MetadataMap.class);
        BigInteger existingMin = null;
        boolean hasExistingMax = false;
        if (metaMap != null) {
            for (MetadataEntry sibling : metaMap.getMetadataEntryList()) {
                if (sibling == entry) {
                    continue;
                }
                String sibKw = StvnMetadataOrderInspection.extractFacetKeyword(sibling);
                if (StvnVocabulary.FACET_KW_MIN_SIZE.equals(sibKw) && sibling.getMetadataValue() != null) {
                    existingMin = StvnPsiLiteralEvaluator
                        .resolveBigIntegerOrConst(sibling, sibling.getMetadataValue().getText().trim())
                        .orElse(null);
                } else if (StvnVocabulary.FACET_KW_MAX_SIZE.equals(sibKw)) {
                    hasExistingMax = true;
                }
            }
        }

        // Safeguard 2: Intrinsic 16,777,216 ceiling (#maxSize 16777216 triggers W003 on root :String)
        if (resolvedN != null && resolvedN.equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
            List<LocalQuickFix> defaultCapFixes = new ArrayList<>(3);
            boolean existingMinExceedsDefault = existingMin != null
                && existingMin.compareTo(StvnVocabulary.DEFAULT_MAX_SIZE) > 0;
            if (!existingMinExceedsDefault && !hasExistingMax) {
                defaultCapFixes.add(new DefaultCapacityStringBoundsQuickFix(
                    entry,
                    rawSnippet,
                    null,
                    PriorityAction.Priority.TOP
                ));
            }
            if (!hasExistingMax) {
                defaultCapFixes.add(new DefaultCapacityStringBoundsQuickFix(
                    entry,
                    rawSnippet,
                    "1",
                    PriorityAction.Priority.HIGH
                ));
                defaultCapFixes.add(new DefaultCapacityStringBoundsQuickFix(
                    entry,
                    rawSnippet,
                    maxToken,
                    PriorityAction.Priority.NORMAL
                ));
            }
            if (existingMinExceedsDefault || hasExistingMax) {
                defaultCapFixes.add(new RemoveRedundantFacetQuickFix(
                    entry,
                    rawSnippet,
                    "Remove prohibited facet",
                    defaultCapFixes.isEmpty() ? PriorityAction.Priority.HIGH : PriorityAction.Priority.NORMAL
                ));
            }
            return defaultCapFixes;
        }

        boolean canPreserveExistingMin = existingMin == null
            || resolvedN == null
            || existingMin.compareTo(resolvedN) <= 0;
        List<LocalQuickFix> result = new ArrayList<>(4);
        if (canPreserveExistingMin) {
            result.add(new ConvertSizeToStringBoundsQuickFix(
                entry,
                rawSnippet,
                null,
                maxToken,
                PriorityAction.Priority.TOP
            ));
        }
        result.add(new ConvertSizeToStringBoundsQuickFix(
            entry,
            rawSnippet,
            "1",
            maxToken,
            PriorityAction.Priority.HIGH
        ));
        boolean isUnitLength = "1".equals(maxToken) || (resolvedN != null && resolvedN.equals(BigInteger.ONE));
        if (!isUnitLength) {
            result.add(new ConvertSizeToStringBoundsQuickFix(
                entry,
                rawSnippet,
                maxToken,
                maxToken,
                PriorityAction.Priority.NORMAL
            ));
        }
        if (!canPreserveExistingMin || hasExistingMax) {
            result.add(new RemoveRedundantFacetQuickFix(
                entry,
                rawSnippet,
                "Remove prohibited facet",
                PriorityAction.Priority.NORMAL
            ));
        }
        return result;
    }

    private static String resolveBaseTypeString(SchemaType schemaType) {
        return resolveBaseTypeString(schemaType, new java.util.HashSet<>());
    }

    private static String resolveBaseTypeString(SchemaType schemaType, java.util.Set<com.intellij.psi.PsiElement> visited) {
        if (!visited.add(schemaType)) {
            return schemaType.getText().trim();
        }
        var constructor = schemaType.getSchemaConstructor();
        if (constructor != null) {
            var atomic = constructor.getAtomicType();
            if (atomic != null) {
                return atomic.getText().trim();
            }
        }
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            var text = kw.getText().trim();
            var parentDef = StvnTypeReference.resolveTypeDefinitionAtContext(kw, text);
            if (parentDef != null && parentDef.getSchemaType() != null) {
                return resolveBaseTypeString(parentDef.getSchemaType(), visited);
            }
            return text;
        }
        return schemaType.getText().trim();
    }

    private static final class ConvertSizeToStringBoundsQuickFix
            extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {

        @SafeFieldForPreview
        private final String sourceSnippet;
        @SafeFieldForPreview
        private final @Nullable String minSizeToken;
        @SafeFieldForPreview
        private final String maxSizeToken;
        @SafeFieldForPreview
        private final Priority priority;

        ConvertSizeToStringBoundsQuickFix(
                MetadataEntry entry,
                String sourceSnippet,
                @Nullable String minSizeToken,
                String maxSizeToken,
                Priority priority
        ) {
            super(entry);
            this.sourceSnippet = sourceSnippet;
            this.minSizeToken = minSizeToken;
            this.maxSizeToken = maxSizeToken;
            this.priority = priority;
        }

        @Override
        public @NotNull Priority getPriority() {
            return priority;
        }

        @Override
        public @Nls @NotNull String getText() {
            String targetBounds = minSizeToken == null
                ? StvnVocabulary.FACET_KW_MAX_SIZE + " " + maxSizeToken
                : StvnVocabulary.FACET_KW_MIN_SIZE + " " + minSizeToken
                    + " " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + maxSizeToken;
            return "Convert '" + sourceSnippet + "' to '" + targetBounds + "'";
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Convert '" + StvnVocabulary.FACET_KW_SIZE + "' to string length bounds";
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            MetadataEntry targetEntry = startElement instanceof MetadataEntry me
                ? me
                : PsiTreeUtil.getParentOfType(startElement, MetadataEntry.class, false);
            if (targetEntry == null || !targetEntry.isValid()) {
                return;
            }
            if (!(targetEntry.getParent() instanceof MetadataMap metaMap)) {
                return;
            }
            Document doc = file.getViewProvider().getDocument();
            if (doc == null) {
                return;
            }
            StvnMetadataFacetInsertionUtil.insertOrReplaceCapacityFacets(
                doc,
                metaMap,
                minSizeToken,
                maxSizeToken,
                minSizeToken == null
            );
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ConvertSizeToStringBoundsQuickFix other)) {
                return false;
            }
            return Objects.equals(
                    RemoveRedundantFacetQuickFix.resolveMetadataEntry(getStartElement()),
                    RemoveRedundantFacetQuickFix.resolveMetadataEntry(other.getStartElement())
                )
                && Objects.equals(minSizeToken, other.minSizeToken)
                && Objects.equals(maxSizeToken, other.maxSizeToken);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                RemoveRedundantFacetQuickFix.resolveMetadataEntry(getStartElement()),
                minSizeToken,
                maxSizeToken
            );
        }
    }

    private static final class DefaultCapacityStringBoundsQuickFix
            extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {

        @SafeFieldForPreview
        private final String sourceSnippet;
        @SafeFieldForPreview
        private final @Nullable String explicitMinToken;
        @SafeFieldForPreview
        private final Priority priority;

        DefaultCapacityStringBoundsQuickFix(
                MetadataEntry entry,
                String sourceSnippet,
                @Nullable String explicitMinToken,
                Priority priority
        ) {
            super(entry);
            this.sourceSnippet = sourceSnippet;
            this.explicitMinToken = explicitMinToken;
            this.priority = priority;
        }

        @Override
        public @NotNull Priority getPriority() {
            return priority;
        }

        @Override
        public @Nls @NotNull String getText() {
            if (explicitMinToken == null) {
                return "Use default 16777216 '" + StvnVocabulary.TYPE_STRING
                    + "' capacity (remove '" + sourceSnippet + "')";
            }
            return "Convert '" + sourceSnippet + "' to '"
                + StvnVocabulary.FACET_KW_MIN_SIZE + " " + explicitMinToken + "'";
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Convert '" + StvnVocabulary.FACET_KW_SIZE + "' to string length bounds";
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            MetadataEntry targetEntry = RemoveRedundantFacetQuickFix.resolveMetadataEntry(startElement);
            if (targetEntry == null || !targetEntry.isValid() || !(targetEntry.getParent() instanceof MetadataMap metaMap)) {
                return;
            }
            Document doc = file.getViewProvider().getDocument();
            if (doc == null) {
                return;
            }
            StvnMetadataFacetInsertionUtil.replaceSizeWithOptionalMinOnly(doc, metaMap, explicitMinToken);
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof DefaultCapacityStringBoundsQuickFix other)) {
                return false;
            }
            return Objects.equals(
                    RemoveRedundantFacetQuickFix.resolveMetadataEntry(getStartElement()),
                    RemoveRedundantFacetQuickFix.resolveMetadataEntry(other.getStartElement())
                )
                && Objects.equals(explicitMinToken, other.explicitMinToken);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                RemoveRedundantFacetQuickFix.resolveMetadataEntry(getStartElement()),
                explicitMinToken
            );
        }
    }
}
