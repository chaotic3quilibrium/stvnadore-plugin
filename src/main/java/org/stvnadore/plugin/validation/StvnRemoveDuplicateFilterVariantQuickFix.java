package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.ValueKeyword;
import org.stvnadore.psi.VariantList;

import java.util.HashSet;
import java.util.Objects;

/**
 * Surgical quick-fix and intention action that removes a duplicate enum variant plus strictly its
 * single separating whitespace slice from a {@code #filterIncl} or {@code #filterExcl} filter list.
 */
@NullMarked
public final class StvnRemoveDuplicateFilterVariantQuickFix
        extends LocalQuickFixAndIntentionActionOnPsiElement
        implements PriorityAction {

    @SafeFieldForPreview
    private final String variantText;

    /**
     * Constructs a quick-fix to remove a duplicate enum filter variant.
     *
     * @param element the duplicate {@link ValueKeyword} PSI element
     * @param variantText the duplicate variant literal text (for example, {@code "#PENDING"})
     */
    public StvnRemoveDuplicateFilterVariantQuickFix(PsiElement element, String variantText) {
        super(element);
        this.variantText = variantText;
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.HIGH;
    }

    @Override
    public @NotNull String getText() {
        return "Remove duplicate variant '" + variantText + "'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Remove duplicate enum filter variant";
    }

    @Override
    public boolean isAvailable(
            @NotNull Project project,
            @NotNull PsiFile file,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        if (!startElement.isValid()) {
            return false;
        }
        var variantList = PsiTreeUtil.getNonStrictParentOfType(startElement, VariantList.class);
        return variantList != null && variantList.isValid() && variantList.getValueKeywordList().size() >= 2;
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

        var targetVkw = PsiTreeUtil.getNonStrictParentOfType(startElement, ValueKeyword.class);
        var currentKeywords = variantList.getValueKeywordList();
        ValueKeyword resolvedDuplicate = null;

        if (targetVkw != null && currentKeywords.contains(targetVkw)) {
            resolvedDuplicate = targetVkw;
        } else {
            var seen = new HashSet<String>();
            for (var k : currentKeywords) {
                var text = k.getText().trim();
                if (!seen.add(text) && text.equals(variantText.trim())) {
                    resolvedDuplicate = k;
                    break;
                }
            }
        }

        if (resolvedDuplicate != null) {
            StvnRemoveFilterVariantQuickFix.deleteSingleVariantSlice(project, file, variantList, resolvedDuplicate);
        }
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof StvnRemoveDuplicateFilterVariantQuickFix other)) {
            return false;
        }
        return Objects.equals(getStartElement(), other.getStartElement())
            && Objects.equals(variantText, other.variantText);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getStartElement(), variantText);
    }
}
