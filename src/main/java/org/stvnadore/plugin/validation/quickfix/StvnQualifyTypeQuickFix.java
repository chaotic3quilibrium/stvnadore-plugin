package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.TypeKeyword;

/**
 * Quick-fix performing in-place FQNI qualification of an unresolved type keyword.
 */
@NullMarked
public final class StvnQualifyTypeQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement {

    private final String fqni;
    private final String customLabel;

    /**
     * Constructs a qualification quick-fix with standard label.
     *
     * @param element the unresolved TypeKeyword element
     * @param fqni the target fully qualified nominal identifier
     */
    public StvnQualifyTypeQuickFix(TypeKeyword element, String fqni) {
        this(element, fqni, "Qualify as '" + fqni + "'");
    }

    /**
     * Constructs a qualification quick-fix with custom label.
     *
     * @param element the unresolved TypeKeyword element
     * @param fqni the target fully qualified nominal identifier
     * @param customLabel user-facing intention action text
     */
    public StvnQualifyTypeQuickFix(TypeKeyword element, String fqni, String customLabel) {
        super(element);
        this.fqni = fqni;
        this.customLabel = customLabel;
    }

    @Override
    public @NotNull String getText() {
        return customLabel;
    }

    @Override
    public @NotNull String getFamilyName() {
        return "STVN Qualify Type Reference";
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        if (!(startElement instanceof TypeKeyword typeKw)) {
            return;
        }
        var newKeyword = StvnElementFactory.createTypeKeyword(project, fqni);
        typeKw.replace(newKeyword);
    }
}
