package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.ValueKeyword;
import org.stvnadore.psi.VariantList;

/**
 * Surgically deletes an invalid {@link ValueKeyword} plus strictly its single separating whitespace
 * slice from an {@code :Enum} subset {@link VariantList} ({@code E014 / ENUM_SUBSET_NOT_SUBSET}),
 * leaving surrounding bracket padding, sibling spacing, and comments untouched.
 */
@NullMarked
public final class StvnRemoveFilterVariantQuickFix
        extends LocalQuickFixAndIntentionActionOnPsiElement
        implements PriorityAction {

    @SafeFieldForPreview
    private final String variantText;
    @SafeFieldForPreview
    private final String filterKeyword;
    @SafeFieldForPreview
    private final Priority priority;

    /**
     * Constructs a variant-removal quick-fix for {@code targetVariant}.
     *
     * @param targetVariant offending {@link ValueKeyword} inside {@link VariantList}
     * @param variantText   variant token text (for example {@code "#SCRAPPED"})
     * @param filterKeyword enclosing filter facet keyword ({@code "#filterIncl"} or {@code "#filterExcl"})
     * @param priority      intention priority ordering
     */
    public StvnRemoveFilterVariantQuickFix(
            ValueKeyword targetVariant,
            String variantText,
            String filterKeyword,
            Priority priority
    ) {
        super(targetVariant);
        this.variantText = variantText;
        this.filterKeyword = filterKeyword;
        this.priority = priority;
    }

    @Override
    public @NotNull Priority getPriority() {
        return priority;
    }

    @Override
    public @Nls @NotNull String getText() {
        return "Remove '" + variantText + "' from '" + filterKeyword + "'";
    }

    @Override
    public @Nls @NotNull String getFamilyName() {
        return "Remove invalid variant from enum filter list";
    }

    @Override
    public boolean isAvailable(
            @NotNull Project project,
            @NotNull PsiFile file,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        VariantList list = PsiTreeUtil.getParentOfType(startElement, VariantList.class, false);
        return list != null && list.getValueKeywordList().size() >= 2;
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        ValueKeyword targetVkw = startElement instanceof ValueKeyword vkw
            ? vkw
            : PsiTreeUtil.getParentOfType(startElement, ValueKeyword.class, false);
        if (targetVkw == null || !targetVkw.isValid()) {
            return;
        }
        VariantList variantList = PsiTreeUtil.getParentOfType(targetVkw, VariantList.class);
        if (variantList == null || !variantList.isValid()) {
            return;
        }
        deleteSingleVariantSlice(project, file, variantList, targetVkw);
    }

    /**
     * Surgically deletes {@code target} plus strictly its single separating whitespace slice inside
     * {@code variantList}, preserving leading/trailing bracket padding, sibling spacing, and comments
     * across both live documents and {@code IntentionPreviewInfo.DIFF} PSI copies.
     *
     * @param project     active IntelliJ project
     * @param file        target PSI file (live buffer or intention preview copy)
     * @param variantList enclosing {@link VariantList}
     * @param target      targeted {@link ValueKeyword} occurrence to remove
     * @return {@code true} if the variant slice was removed
     */
    public static boolean deleteSingleVariantSlice(
            Project project,
            PsiFile file,
            VariantList variantList,
            ValueKeyword target
    ) {
        List<ValueKeyword> variants = variantList.getValueKeywordList();
        int k = variants.size();
        if (k < 2) {
            return false;
        }
        int idx = -1;
        for (int i = 0; i < k; i++) {
            if (variants.get(i) == target) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return false;
        }

        CharSequence fileChars = file.getText();
        int deleteStart;
        int deleteEnd;
        if (idx < k - 1) {
            deleteStart = target.getTextRange().getStartOffset();
            deleteEnd = variants.get(idx + 1).getTextRange().getStartOffset();
            if (containsCommentMarker(fileChars, target.getTextRange().getEndOffset(), deleteEnd)) {
                deleteEnd = trimHorizontalSpacesRight(fileChars, target.getTextRange().getEndOffset());
            }
        } else {
            deleteStart = variants.get(k - 2).getTextRange().getEndOffset();
            deleteEnd = target.getTextRange().getEndOffset();
            if (containsCommentMarker(fileChars, deleteStart, target.getTextRange().getStartOffset())) {
                deleteStart = trimHorizontalSpacesLeft(fileChars, target.getTextRange().getStartOffset());
            }
        }

        Document doc = file.getViewProvider().getDocument();
        if (doc != null) {
            doc.deleteString(deleteStart, deleteEnd);
            if (file.isPhysical() && !IntentionPreviewUtils.isPreviewElement(target)) {
                PsiDocumentManager.getInstance(project).commitDocument(doc);
            }
            return true;
        }

        var listRange = variantList.getTextRange();
        int relStart = deleteStart - listRange.getStartOffset();
        int relEnd = deleteEnd - listRange.getStartOffset();
        String rawList = variantList.getText();
        if (relStart >= 0 && relEnd <= rawList.length() && relStart <= relEnd) {
            String surgicallySliced = rawList.substring(0, relStart) + rawList.substring(relEnd);
            variantList.replace(StvnElementFactory.createVariantList(project, surgicallySliced));
            return true;
        }
        return false;
    }

    private static boolean containsCommentMarker(CharSequence chars, int from, int to) {
        int end = Math.min(chars.length(), to);
        for (int i = Math.max(0, from); i + 1 < end; i++) {
            if (chars.charAt(i) == '/' && chars.charAt(i + 1) == '/') {
                return true;
            }
        }
        return false;
    }

    private static int trimHorizontalSpacesRight(CharSequence chars, int offset) {
        int cur = offset;
        while (cur < chars.length() && (chars.charAt(cur) == ' ' || chars.charAt(cur) == '\t')) {
            cur++;
        }
        return cur;
    }

    private static int trimHorizontalSpacesLeft(CharSequence chars, int offset) {
        int cur = offset;
        while (cur > 0 && (chars.charAt(cur - 1) == ' ' || chars.charAt(cur - 1) == '\t')) {
            cur--;
        }
        return cur;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof StvnRemoveFilterVariantQuickFix other)) {
            return false;
        }
        return Objects.equals(getStartElement(), other.getStartElement())
            && Objects.equals(variantText, other.variantText)
            && Objects.equals(filterKeyword, other.filterKeyword);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getStartElement(), variantText, filterKeyword);
    }
}
