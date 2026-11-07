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
import org.stvnadore.psi.ValueKeyword;
import org.stvnadore.psi.VariantList;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * In-place AST rewrite quick-fix and intention action that removes a duplicate
 * enum variant from a {@code #filterIncl} or {@code #filterExcl} filter list.
 */
@NullMarked
public final class StvnRemoveDuplicateFilterVariantQuickFix
        extends LocalQuickFixAndIntentionActionOnPsiElement
        implements PriorityAction {

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
        return Priority.TOP;
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
        if (variantList == null) {
            return;
        }

        var targetVkw = PsiTreeUtil.getNonStrictParentOfType(startElement, ValueKeyword.class);
        var currentKeywords = variantList.getValueKeywordList();
        var kept = new ArrayList<String>(currentKeywords.size());

        if (targetVkw != null && currentKeywords.contains(targetVkw)) {
            for (var k : currentKeywords) {
                if (k != targetVkw) {
                    kept.add(k.getText());
                }
            }
        } else {
            var seen = new HashSet<String>();
            boolean removedTarget = false;
            for (var k : currentKeywords) {
                var text = k.getText();
                if (!seen.add(text) && !removedTarget && text.equals(variantText)) {
                    removedTarget = true;
                    continue;
                }
                kept.add(text);
            }
        }

        var replacementText = kept.isEmpty() ? "[]" : "[ " + String.join(" ", kept) + " ]";
        var docManager = PsiDocumentManager.getInstance(project);
        var doc = file.getViewProvider().getDocument();
        if (doc != null) {
            var range = variantList.getTextRange();
            doc.replaceString(range.getStartOffset(), range.getEndOffset(), replacementText);
            docManager.commitDocument(doc);
        }
    }
}
