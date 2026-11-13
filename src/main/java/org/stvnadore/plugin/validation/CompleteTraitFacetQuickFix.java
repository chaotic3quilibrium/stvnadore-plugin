package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.MetadataBool;
import org.stvnadore.psi.MetadataTrait;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TypeDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Quick-fix and intention action that repairs bare trait facets by inserting the predictive,
 * non-redundant overriding boolean value based on parent schema effective constraints.
 */
@NullMarked
public final class CompleteTraitFacetQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {

    private final String replacementValue;
    private final String actionText;
    private final Priority priority;

    /**
     * Constructs a quick-fix with normal priority.
     *
     * @param element the PSI element representing the incomplete trait
     * @param replacementValue the boolean replacement token ("#TRUE" or "#FALSE")
     * @param actionText the user-visible action text
     */
    public CompleteTraitFacetQuickFix(PsiElement element, String replacementValue, String actionText) {
        this(element, replacementValue, actionText, Priority.NORMAL);
    }

    /**
     * Constructs a quick-fix with explicit priority.
     *
     * @param element the PSI element representing the incomplete trait
     * @param replacementValue the boolean replacement token ("#TRUE" or "#FALSE")
     * @param actionText the user-visible action text
     * @param priority the intention action display priority
     */
    public CompleteTraitFacetQuickFix(PsiElement element, String replacementValue, String actionText, Priority priority) {
        super(element);
        this.replacementValue = replacementValue;
        this.actionText = actionText;
        this.priority = priority;
    }

    @Override
    public @NotNull Priority getPriority() {
        return priority;
    }

    @Override
    public @NotNull String getText() {
        return actionText;
    }

    @Override
    public @NotNull String getFamilyName() {
        return "STVN Complete Trait Facet";
    }

    @Override
    public boolean isAvailable(@NotNull Project project,
                               @NotNull PsiFile file,
                               @NotNull PsiElement startElement,
                               @NotNull PsiElement endElement) {
        return startElement.isValid();
    }

    @Override
    public void invoke(@NotNull Project project,
                       @NotNull PsiFile file,
                       @Nullable Editor editor,
                       @NotNull PsiElement startElement,
                       @NotNull PsiElement endElement) {
        if (!startElement.isValid()) {
            return;
        }

        var doc = file.getViewProvider().getDocument();
        if (doc == null) {
            return;
        }

        var range = startElement.getTextRange();
        var rawText = startElement.getText().trim();
        String facetKw = extractTraitKeyword(rawText);
        String replacement = facetKw + " " + replacementValue;

        doc.replaceString(range.getStartOffset(), range.getEndOffset(), replacement);
        PsiDocumentManager.getInstance(project).commitDocument(doc);

        if (editor != null && file.isPhysical()) {
            editor.getCaretModel().moveToOffset(range.getStartOffset() + replacement.length());
        }
    }

    private static String extractTraitKeyword(String rawText) {
        if (rawText.startsWith(StvnVocabulary.FACET_KW_PRESERVE_INDENT)) {
            return StvnVocabulary.FACET_KW_PRESERVE_INDENT;
        }
        if (rawText.startsWith(StvnVocabulary.FACET_KW_EQUATABLE)) {
            return StvnVocabulary.FACET_KW_EQUATABLE;
        }
        if (rawText.startsWith(StvnVocabulary.FACET_KW_COMPARABLE)) {
            return StvnVocabulary.FACET_KW_COMPARABLE;
        }
        return rawText;
    }

    /**
     * Inspects the enclosing type definition and parent schema to generate predictive repair actions.
     *
     * @param element the incomplete trait PSI element
     * @return list of context-aware quick-fix actions
     */
    public static List<CompleteTraitFacetQuickFix> createFixes(PsiElement element) {
        var fixes = new ArrayList<CompleteTraitFacetQuickFix>();
        PsiElement targetElement = (element instanceof MetadataTrait || element instanceof MetadataBool)
            ? element
            : PsiTreeUtil.getParentOfType(element, MetadataTrait.class, false);
        if (targetElement == null) {
            targetElement = PsiTreeUtil.getParentOfType(element, MetadataBool.class, false);
        }
        var anchor = (targetElement != null) ? targetElement : element;
        boolean isBoolTrait = anchor instanceof MetadataBool;
        boolean isEquatable = isBoolTrait && anchor.getNode().findChildByType(StvnTypes.KW_EQUATABLE) != null;

        var typeDef = PsiTreeUtil.getParentOfType(element, TypeDefinition.class);
        var constDef = PsiTreeUtil.getParentOfType(element, ConstantDefinition.class);
        SchemaType schemaType = typeDef != null
            ? typeDef.getSchemaType()
            : (constDef != null ? constDef.getSchemaType() : PsiTreeUtil.getParentOfType(element, SchemaType.class));
        if (schemaType == null || (constDef != null && isBoolTrait)) {
            fixes.add(new CompleteTraitFacetQuickFix(
                anchor,
                StvnVocabulary.VAL_TRUE,
                "Complete trait with '" + StvnVocabulary.VAL_TRUE + "'",
                Priority.HIGH
            ));
            fixes.add(new CompleteTraitFacetQuickFix(
                anchor,
                StvnVocabulary.VAL_FALSE,
                "Complete trait with '" + StvnVocabulary.VAL_FALSE + "'",
                Priority.NORMAL
            ));
            return fixes;
        }

        String parentAlias = null;
        if (schemaType.getTypeKeyword() != null) {
            parentAlias = schemaType.getTypeKeyword().getText().trim();
        } else if (schemaType.getSchemaConstructor() != null) {
            var constructor = schemaType.getSchemaConstructor();
            if (constructor.getAtomicType() != null) {
                parentAlias = constructor.getAtomicType().getText().trim();
            } else {
                parentAlias = constructor.getText().trim();
            }
        }

        if (parentAlias == null) {
            fixes.add(new CompleteTraitFacetQuickFix(
                anchor,
                StvnVocabulary.VAL_TRUE,
                "Complete trait with '" + StvnVocabulary.VAL_TRUE + "'",
                Priority.HIGH
            ));
            fixes.add(new CompleteTraitFacetQuickFix(
                anchor,
                StvnVocabulary.VAL_FALSE,
                "Complete trait with '" + StvnVocabulary.VAL_FALSE + "'",
                Priority.NORMAL
            ));
            return fixes;
        }

        if (!isBoolTrait) {
            // Direct primitive base :String defaults to preserveIndent = false
            if (StvnVocabulary.TYPE_STRING.equals(parentAlias)) {
                fixes.add(new CompleteTraitFacetQuickFix(
                    anchor,
                    StvnVocabulary.VAL_TRUE,
                    "Complete trait with '" + StvnVocabulary.VAL_TRUE + "' (overrides parent " + StvnVocabulary.TYPE_STRING + ")",
                    Priority.HIGH
                ));
                return fixes;
            }

            // Nominal ancestor resolution via pure-IR StvnSchema
            StvnSchema parentIr = resolveParentIr(element, parentAlias);
            if (parentIr instanceof StvnSchema.StringSchema strSchema) {
                boolean parentEff = strSchema.preserveIndent();
                if (!parentEff) {
                    fixes.add(new CompleteTraitFacetQuickFix(
                        anchor,
                        StvnVocabulary.VAL_TRUE,
                        "Complete trait with '" + StvnVocabulary.VAL_TRUE + "' (overrides parent " + parentAlias + ")",
                        Priority.HIGH
                    ));
                } else {
                    fixes.add(new CompleteTraitFacetQuickFix(
                        anchor,
                        StvnVocabulary.VAL_FALSE,
                        "Complete trait with '" + StvnVocabulary.VAL_FALSE + "' (overrides parent " + parentAlias + ")",
                        Priority.HIGH
                    ));
                }
                return fixes;
            }
        } else {
            // Boolean traits (#equatable / #comparable) on scalar primitives default to #TRUE
            if (StvnVocabulary.TYPE_FLOAT.equals(parentAlias)) {
                var enclosingMap = typeDef != null
                    ? typeDef.getMetadataMap()
                    : (constDef != null ? constDef.getMetadataMap() : schemaType.getMetadataMap());
                boolean hasExact = enclosingMap != null && enclosingMap.getMetadataEntryList().stream()
                    .anyMatch(e -> e.getNode().findChildByType(StvnTypes.KW_EXACT) != null);
                boolean defaultTrait = hasExact || !isEquatable;
                String overrideVal = defaultTrait ? StvnVocabulary.VAL_FALSE : StvnVocabulary.VAL_TRUE;
                fixes.add(new CompleteTraitFacetQuickFix(
                    anchor,
                    overrideVal,
                    "Complete trait with '" + overrideVal + "' (overrides parent " + parentAlias + ")",
                    Priority.HIGH
                ));
                return fixes;
            }
            if (StvnVocabulary.TYPE_STRING.equals(parentAlias)
                    || StvnVocabulary.TYPE_INT.equals(parentAlias)
                    || StvnVocabulary.TYPE_BOOLEAN.equals(parentAlias)
                    || StvnVocabulary.TYPE_TIME_EPOCH.equals(parentAlias)
                    || StvnVocabulary.TYPE_DATE_TIME.equals(parentAlias)) {
                fixes.add(new CompleteTraitFacetQuickFix(
                    anchor,
                    StvnVocabulary.VAL_FALSE,
                    "Complete trait with '" + StvnVocabulary.VAL_FALSE + "' (overrides parent " + parentAlias + ")",
                    Priority.HIGH
                ));
                return fixes;
            }

            StvnSchema parentIr = resolveParentIr(element, parentAlias);
            if (parentIr != null && !(parentIr instanceof StvnSchema.PoisonedSchema)) {
                boolean parentEff = isEquatable ? parentIr.traits().equatable() : parentIr.traits().comparable();
                String overrideVal = parentEff ? StvnVocabulary.VAL_FALSE : StvnVocabulary.VAL_TRUE;
                fixes.add(new CompleteTraitFacetQuickFix(
                    anchor,
                    overrideVal,
                    "Complete trait with '" + overrideVal + "' (overrides parent " + parentAlias + ")",
                    Priority.HIGH
                ));
                return fixes;
            }
        }

        // Unresolvable parent fallback
        fixes.add(new CompleteTraitFacetQuickFix(
            anchor,
            StvnVocabulary.VAL_TRUE,
            "Complete trait with '" + StvnVocabulary.VAL_TRUE + "'",
            Priority.HIGH
        ));
        fixes.add(new CompleteTraitFacetQuickFix(
            anchor,
            StvnVocabulary.VAL_FALSE,
            "Complete trait with '" + StvnVocabulary.VAL_FALSE + "'",
            Priority.NORMAL
        ));
        return fixes;
    }

    private static @Nullable StvnSchema resolveParentIr(PsiElement context, String parentAlias) {
        var file = context.getContainingFile();
        if (file == null) {
            return null;
        }
        StvnSchema parentIr = null;
        var resolvedParent = StvnTypeReference.resolveTypeInFile(file, parentAlias, new HashSet<>());
        var parentTypeDef = StvnPsiUtils.getParentTypeDefinition(resolvedParent);
        if (parentTypeDef != null) {
            parentIr = StvnTypeResolver.resolveNominalIrSchema(parentTypeDef);
        }
        if (parentIr == null) {
            parentIr = StvnTypeResolver.resolveIrSchemaByAlias(file, parentAlias);
        }
        return parentIr;
    }
}
