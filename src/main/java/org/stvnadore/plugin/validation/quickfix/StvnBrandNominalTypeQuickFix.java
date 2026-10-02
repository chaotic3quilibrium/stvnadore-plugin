package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.TypeKeyword;

/**
 * Quick-fix injecting an explicit nominal branding definition into :defs.
 */
@NullMarked
public final class StvnBrandNominalTypeQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement {

    private final String targetFqni;
    private final String customLabel;

    /**
     * Constructs a nominal branding quick-fix with standard label.
     *
     * @param element the unresolved TypeKeyword element
     * @param targetFqni the target FQNI to brand from
     */
    public StvnBrandNominalTypeQuickFix(TypeKeyword element, String targetFqni) {
        this(element, targetFqni,
            targetFqni.startsWith(":org/stvnadore/prelude/")
                ? "Brand new nominal type '" + element.getText() + "' from Prelude (creates distinct type)"
                : "Brand new nominal type '" + element.getText() + "' from " + targetFqni + " (creates distinct type)");
    }

    /**
     * Constructs a nominal branding quick-fix with custom label.
     *
     * @param element the unresolved TypeKeyword element
     * @param targetFqni the target FQNI to brand from
     * @param customLabel user-facing intention action text
     */
    public StvnBrandNominalTypeQuickFix(TypeKeyword element, String targetFqni, String customLabel) {
        super(element);
        this.targetFqni = targetFqni;
        this.customLabel = customLabel;
    }

    @Override
    public @NotNull String getText() {
        return customLabel;
    }

    @Override
    public @NotNull String getFamilyName() {
        return "STVN Brand Nominal Type (:defs)";
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

        var tokenText = startElement.getText();
        var defsEntry = PsiTreeUtil.getParentOfType(startElement, DefsEntry.class);
        if (defsEntry == null) return;

        var openBrace = defsEntry.getNode().findChildByType(org.stvnadore.psi.StvnTypes.LBRACE);
        if (openBrace == null) return;

        int insertOffset = openBrace.getStartOffset() + 1;
        var brandStatement = "\n    " + tokenText + " " + targetFqni;

        doc.insertString(insertOffset, brandStatement);
        PsiDocumentManager.getInstance(project).commitDocument(doc);
        CodeStyleManager.getInstance(project).reformat(file);
    }
}
