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
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.MetadataTrait;
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
        String replacement = rawText.startsWith("#preserveIndent")
            ? "#preserveIndent " + replacementValue
            : rawText + " " + replacementValue;

        doc.replaceString(range.getStartOffset(), range.getEndOffset(), replacement);
        PsiDocumentManager.getInstance(project).commitDocument(doc);

        if (editor != null) {
            editor.getCaretModel().moveToOffset(range.getStartOffset() + replacement.length());
        }
    }

    /**
     * Inspects the enclosing type definition and parent schema to generate predictive repair actions.
     *
     * @param element the incomplete trait PSI element
     * @return list of context-aware quick-fix actions
     */
    public static List<CompleteTraitFacetQuickFix> createFixes(PsiElement element) {
        var fixes = new ArrayList<CompleteTraitFacetQuickFix>();
        var targetElement = (element instanceof MetadataTrait mt)
            ? mt
            : PsiTreeUtil.getParentOfType(element, MetadataTrait.class, false);
        var anchor = (targetElement != null) ? targetElement : element;

        var typeDef = PsiTreeUtil.getParentOfType(element, TypeDefinition.class);
        if (typeDef == null) {
            fixes.add(new CompleteTraitFacetQuickFix(anchor, "#TRUE", "Complete trait with '#TRUE'", Priority.HIGH));
            fixes.add(new CompleteTraitFacetQuickFix(anchor, "#FALSE", "Complete trait with '#FALSE'", Priority.NORMAL));
            return fixes;
        }

        var schemaType = typeDef.getSchemaType();
        if (schemaType == null) {
            fixes.add(new CompleteTraitFacetQuickFix(anchor, "#TRUE", "Complete trait with '#TRUE'", Priority.HIGH));
            fixes.add(new CompleteTraitFacetQuickFix(anchor, "#FALSE", "Complete trait with '#FALSE'", Priority.NORMAL));
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
            fixes.add(new CompleteTraitFacetQuickFix(anchor, "#TRUE", "Complete trait with '#TRUE'", Priority.HIGH));
            fixes.add(new CompleteTraitFacetQuickFix(anchor, "#FALSE", "Complete trait with '#FALSE'", Priority.NORMAL));
            return fixes;
        }

        // Direct primitive base :String defaults to preserveIndent = false
        if (StvnVocabulary.TYPE_STRING.equals(parentAlias)) {
            fixes.add(new CompleteTraitFacetQuickFix(
                anchor,
                "#TRUE",
                "Complete trait with '#TRUE' (overrides parent :String)",
                Priority.HIGH
            ));
            return fixes;
        }

        // Nominal ancestor resolution
        var resolvedParent = StvnTypeReference.resolveTypeInFile(typeDef.getContainingFile(), parentAlias, new HashSet<>());
        var parentTypeDef = org.stvnadore.plugin.psi.StvnPsiUtils.getParentTypeDefinition(resolvedParent);
        if (parentTypeDef != null) {
            var parentRs = StvnTypeResolver.resolveNominalResolvedSchema(parentTypeDef);
            if (parentRs != null && !parentRs.isPoisonedSentinel()) {
                boolean parentEff = parentRs.constraints().preserveIndent();
                if (!parentEff) {
                    fixes.add(new CompleteTraitFacetQuickFix(
                        anchor,
                        "#TRUE",
                        "Complete trait with '#TRUE' (overrides parent " + parentAlias + ")",
                        Priority.HIGH
                    ));
                } else {
                    fixes.add(new CompleteTraitFacetQuickFix(
                        anchor,
                        "#FALSE",
                        "Complete trait with '#FALSE' (overrides parent " + parentAlias + ")",
                        Priority.HIGH
                    ));
                }
                return fixes;
            }
        }

        // Unresolvable parent fallback
        fixes.add(new CompleteTraitFacetQuickFix(anchor, "#TRUE", "Complete trait with '#TRUE'", Priority.HIGH));
        fixes.add(new CompleteTraitFacetQuickFix(anchor, "#FALSE", "Complete trait with '#FALSE'", Priority.NORMAL));
        return fixes;
    }
}
