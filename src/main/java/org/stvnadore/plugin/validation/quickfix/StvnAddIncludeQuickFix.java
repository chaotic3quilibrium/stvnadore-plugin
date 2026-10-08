package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnInclfFile;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TypeKeyword;

/**
 * Quick-fix injecting an :include statement into :defs.
 */
@NullMarked
public final class StvnAddIncludeQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {

    private final String includePath;

    /**
     * Constructs an include injection quick-fix.
     *
     * @param element the unresolved TypeKeyword element
     * @param includePath relative or workspace path to include
     */
    public StvnAddIncludeQuickFix(TypeKeyword element, String includePath) {
        super(element);
        this.includePath = includePath;
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @NotNull PsiFile file, @NotNull PsiElement startElement, @NotNull PsiElement endElement) {
        // Leaf modules (.stvn_inclf) cannot contain includes (Rule INC-01)
        return !(file instanceof StvnInclfFile) && super.isAvailable(project, file, startElement, endElement);
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.LOW;
    }

    @Override
    public @NotNull String getText() {
        return "Add :include [ \"" + includePath + "\" ] to :defs";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "STVN Add Include (:defs)";
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        var doc = PsiDocumentManager.getInstance(project).getDocument(file);
        if (doc == null) return;

        var defs = StvnPsiUtils.getOrCreateDefsBlock(file);
        if (defs == null) return;

        var openBrace = defs.getNode().findChildByType(StvnTypes.LBRACE);
        if (openBrace == null) return;

        int insertOffset = openBrace.getStartOffset() + 1;
        var includeStatement = "\n    :include [ \"" + includePath + "\" ]";

        doc.insertString(insertOffset, includeStatement);
        PsiDocumentManager.getInstance(project).commitDocument(doc);
        CodeStyleManager.getInstance(project).reformat(file);
    }
}
