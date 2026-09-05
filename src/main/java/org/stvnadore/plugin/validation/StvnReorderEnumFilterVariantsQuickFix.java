package org.stvnadore.plugin.validation;

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
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.ValueKeyword;
import org.stvnadore.psi.VariantList;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * In-place AST rewrite quick-fix sorting filter facet variants according to
 * the declaration order of the root :Enum.
 */
@NullMarked
public final class StvnReorderEnumFilterVariantsQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement {

    public StvnReorderEnumFilterVariantsQuickFix(VariantList variantList) {
        super(variantList);
    }

    @Override
    public @NotNull String getText() {
        return "Sort variants to match root enum declaration order";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "STVN Enum Subset Reorder";
    }

    @Override
    public boolean isAvailable(
            @NotNull Project project,
            @NotNull PsiFile file,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        if (!startElement.isValid()) return false;
        var variantList = PsiTreeUtil.getNonStrictParentOfType(startElement, VariantList.class);
        return variantList != null && variantList.isValid();
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        var variantList = PsiTreeUtil.getNonStrictParentOfType(startElement, VariantList.class);
        if (variantList == null) return;

        var typeDef = PsiTreeUtil.getParentOfType(variantList, TypeDefinition.class);
        if (typeDef == null || typeDef.getSchemaType() == null) return;

        var subset = StvnTypeResolver.resolveEnumSubset(typeDef.getSchemaType());
        List<String> rootVariants;
        if (subset != null) {
            rootVariants = subset.rootVariants();
        } else {
            var resolved = StvnTypeResolver.resolveNominalSchema(typeDef.getSchemaType());
            if (resolved == null || resolved.getSchemaConstructor() == null ||
                resolved.getSchemaConstructor().getSumType() == null ||
                resolved.getSchemaConstructor().getSumType().getEnumDef() == null) {
                return;
            }
            rootVariants = resolved.getSchemaConstructor().getSumType().getEnumDef()
                .getValueKeywordList().stream().map(ValueKeyword::getText).toList();
        }

        var currentKeywords = variantList.getValueKeywordList();
        var sorted = currentKeywords.stream()
            .sorted(Comparator.comparingInt(k -> {
                int idx = rootVariants.indexOf(k.getText());
                return idx >= 0 ? idx : Integer.MAX_VALUE;
            }))
            .toList();

        var replacementText = "[ " + sorted.stream().map(ValueKeyword::getText).collect(Collectors.joining(" ")) + " ]";

        var docManager = PsiDocumentManager.getInstance(project);
        var doc = file.getViewProvider().getDocument();
        if (doc != null) {
            var range = variantList.getTextRange();
            doc.replaceString(range.getStartOffset(), range.getEndOffset(), replacementText);
            docManager.commitDocument(doc);
        }
    }
}
