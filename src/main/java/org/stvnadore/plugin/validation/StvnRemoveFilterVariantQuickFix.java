package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.Objects;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.ValueKeyword;
import org.stvnadore.psi.VariantList;

/**
 * Deletes an invalid {@link ValueKeyword} from an {@code :Enum} subset {@link VariantList}
 * ({@code E014 / ENUM_SUBSET_NOT_SUBSET}), normalizing interior spacing with zero bracket padding.
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
        var kept = new ArrayList<String>();
        boolean removedTarget = false;
        for (ValueKeyword kw : variantList.getValueKeywordList()) {
            if (!removedTarget && kw == targetVkw) {
                removedTarget = true;
                continue;
            }
            kept.add(kw.getText());
        }
        if (kept.isEmpty()) {
            return;
        }
        String newBracketText = StvnVocabulary.DELIM_OPEN_BRACKET
            + String.join(" ", kept)
            + StvnVocabulary.DELIM_CLOSE_BRACKET;
        var dummyMap = StvnElementFactory.createMetadataMap(
            project,
            "{ " + StvnVocabulary.FACET_KW_FILTER_INCL + " " + newBracketText + " }"
        );
        var entries = dummyMap.getMetadataEntryList();
        if (!entries.isEmpty() && entries.getFirst().getMetadataFilter() != null) {
            var newList = entries.getFirst().getMetadataFilter().getVariantList();
            if (newList != null) {
                variantList.replace(newList);
            }
        }
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
