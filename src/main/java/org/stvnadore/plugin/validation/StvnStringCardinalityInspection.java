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
                List<LocalQuickFix> fixes = buildCardinalityQuickFixes(entry, isNominalDerivation);
                holder.registerProblem(
                    entry,
                    StvnDiagnosticFormatter.formatPlugin(
                        coreParityMsg,
                        StvnDiagnosticCodes.ERR_STRING_CARDINALITY_PROHIBITED
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                );
            }
        }
    }

    private static List<LocalQuickFix> buildCardinalityQuickFixes(MetadataEntry entry, boolean isNominalDerivation) {
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
        if (val.getIntegerLiteral() != null) {
            BigInteger parsed = StvnPsiLiteralEvaluator.parseBigInteger(argText).orElse(null);
            // Safeguard 1: Under STVN Spec § 6.1, :String #maxSize requires n >= 1; never generate #maxSize 0
            if (parsed == null || parsed.signum() <= 0) {
                return List.of(new RemoveRedundantFacetQuickFix(
                    entry,
                    rawSnippet,
                    "Remove prohibited facet",
                    PriorityAction.Priority.HIGH
                ));
            }
            String cleanN = parsed.toString();
            if (parsed.equals(BigInteger.ONE)) {
                return List.of(
                    new ConvertSizeToStringBoundsQuickFix(entry, rawSnippet, "1", "1", PriorityAction.Priority.HIGH)
                );
            }
            List<LocalQuickFix> result = new ArrayList<>(2);
            result.add(new ConvertSizeToStringBoundsQuickFix(entry, rawSnippet, "1", cleanN, PriorityAction.Priority.HIGH));
            result.add(new ConvertSizeToStringBoundsQuickFix(entry, rawSnippet, cleanN, cleanN, PriorityAction.Priority.NORMAL));
            return result;
        }
        if (val.getValueKeyword() != null && argText.startsWith(StvnVocabulary.SIGIL_VALUE)) {
            BigInteger resolvedConst = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(entry, argText).orElse(null);
            if (resolvedConst != null && resolvedConst.signum() <= 0) {
                return List.of(new RemoveRedundantFacetQuickFix(
                    entry,
                    rawSnippet,
                    "Remove prohibited facet",
                    PriorityAction.Priority.HIGH
                ));
            }
            return List.of(
                new ConvertSizeToStringBoundsQuickFix(entry, rawSnippet, "1", argText, PriorityAction.Priority.HIGH),
                new ConvertSizeToStringBoundsQuickFix(entry, rawSnippet, argText, argText, PriorityAction.Priority.NORMAL)
            );
        }
        return List.of(new RemoveRedundantFacetQuickFix(
            entry,
            rawSnippet,
            "Remove prohibited facet",
            PriorityAction.Priority.HIGH
        ));
    }

    private static String resolveBaseTypeString(SchemaType schemaType) {
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
            var file = schemaType.getContainingFile();
            if (file != null) {
                var resolved = StvnTypeReference.resolveTypeInFile(file, text, new java.util.HashSet<>());
                if (resolved != null) {
                    var parentDef = PsiTreeUtil.getParentOfType(resolved, TypeDefinition.class);
                    if (parentDef != null && parentDef.getSchemaType() != null) {
                        return resolveBaseTypeString(parentDef.getSchemaType());
                    }
                }
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
        private final String minSizeToken;
        @SafeFieldForPreview
        private final String maxSizeToken;
        @SafeFieldForPreview
        private final Priority priority;

        ConvertSizeToStringBoundsQuickFix(
                MetadataEntry entry,
                String sourceSnippet,
                String minSizeToken,
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
            String targetBounds = StvnVocabulary.FACET_KW_MIN_SIZE + " " + minSizeToken
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
                false
            );
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }
    }
}
