package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.ValueKeyword;
import org.stvnadore.psi.VariantList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * In-place token-only rewrite quick-fix sorting filter facet variants according to
 * the declaration order of the root {@code :Enum} by replacing strictly individual
 * {@link ValueKeyword} token ranges right-to-left with an exact {@code 0}-character width delta,
 * preserving all authored bracket spacing, column alignment, and comments.
 */
@NullMarked
public final class StvnReorderEnumFilterVariantsQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement {

    /**
     * Constructs a reordering quick-fix for the specified variant list element.
     *
     * @param variantList the variant list element to reorder
     */
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
        if (variantList == null || !variantList.isValid()) {
            return;
        }

        List<String> rootVariants = resolveRootVariants(variantList);
        if (rootVariants.isEmpty()) {
            return;
        }

        List<ValueKeyword> currentKeywords = new ArrayList<>(variantList.getValueKeywordList());
        if (currentKeywords.size() < 2) {
            return;
        }

        List<String> sortedTexts = currentKeywords.stream()
            .sorted(Comparator.comparingInt(k -> {
                int idx = rootVariants.indexOf(k.getText().trim());
                return idx >= 0 ? idx : Integer.MAX_VALUE;
            }))
            .map(k -> k.getText().trim())
            .toList();

        boolean isPreview = !file.isPhysical()
            || IntentionPreviewUtils.isPreviewElement(startElement)
            || IntentionPreviewUtils.isIntentionPreviewActive();
        if (!isPreview) {
            var doc = file.getViewProvider().getDocument();
            if (doc != null) {
                for (int i = currentKeywords.size() - 1; i >= 0; i--) {
                    var kwRange = currentKeywords.get(i).getTextRange();
                    doc.replaceString(kwRange.getStartOffset(), kwRange.getEndOffset(), sortedTexts.get(i));
                }
                PsiDocumentManager.getInstance(project).commitDocument(doc);
                return;
            }
        }

        for (int i = currentKeywords.size() - 1; i >= 0; i--) {
            ValueKeyword targetKw = currentKeywords.get(i);
            String newTokenText = sortedTexts.get(i);
            var firstLeaf = targetKw.getNode() != null ? targetKw.getNode().getFirstChildNode() : null;
            if (firstLeaf instanceof LeafPsiElement leafPsi) {
                leafPsi.replaceWithText(newTokenText);
            } else {
                targetKw.replace(StvnElementFactory.createValueKeyword(project, newTokenText));
            }
        }
    }

    private static List<String> resolveRootVariants(VariantList variantList) {
        SchemaType governingSchema = PsiTreeUtil.getParentOfType(variantList, SchemaType.class);
        if (governingSchema == null) {
            var typeDef = PsiTreeUtil.getParentOfType(variantList, TypeDefinition.class);
            governingSchema = typeDef != null ? typeDef.getSchemaType() : null;
        }
        if (governingSchema == null) {
            return List.of();
        }

        var subset = StvnTypeResolver.resolveEnumSubset(governingSchema);
        if (subset != null && !subset.rootVariants().isEmpty()) {
            return subset.rootVariants();
        }

        var resolved = StvnTypeResolver.resolveNominalSchema(governingSchema);
        if (resolved != null
                && resolved.getSchemaConstructor() != null
                && resolved.getSchemaConstructor().getSumType() != null
                && resolved.getSchemaConstructor().getSumType().getEnumDef() != null) {
            return resolved.getSchemaConstructor().getSumType().getEnumDef()
                .getValueKeywordList().stream()
                .map(vk -> vk.getText().trim())
                .toList();
        }

        var kw = governingSchema.getTypeKeyword();
        var file = variantList.getContainingFile();
        if (kw != null && file != null) {
            var target = StvnTypeReference.resolveTypeInFile(file, kw.getText().trim(), new HashSet<>());
            var targetTd = StvnPsiUtils.resolveTargetTypeDefinition(target);
            if (targetTd != null) {
                var tdSubset = StvnTypeResolver.resolveEnumSubset(targetTd);
                if (tdSubset != null && !tdSubset.rootVariants().isEmpty()) {
                    return tdSubset.rootVariants();
                }
            }
        }
        return List.of();
    }
}
