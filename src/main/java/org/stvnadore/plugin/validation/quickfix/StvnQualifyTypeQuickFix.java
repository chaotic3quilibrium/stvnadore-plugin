package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.PriorityAction;
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
public final class StvnQualifyTypeQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {

    private final String fqni;
    private final String customLabel;
    private final Priority priority;

    /**
     * Constructs a qualification quick-fix with standard label and HIGH priority.
     *
     * @param element the unresolved TypeKeyword element
     * @param fqni the target fully qualified nominal identifier
     */
    public StvnQualifyTypeQuickFix(TypeKeyword element, String fqni) {
        this(element, fqni, "Qualify as '" + fqni + "'", Priority.HIGH);
    }

    /**
     * Constructs a qualification quick-fix with custom label and HIGH priority.
     *
     * @param element the unresolved TypeKeyword element
     * @param fqni the target fully qualified nominal identifier
     * @param customLabel user-facing intention action text
     */
    public StvnQualifyTypeQuickFix(TypeKeyword element, String fqni, String customLabel) {
        this(element, fqni, customLabel, Priority.HIGH);
    }

    /**
     * Constructs a qualification quick-fix with custom label and explicit priority.
     *
     * @param element the unresolved TypeKeyword element
     * @param fqni the target fully qualified nominal identifier
     * @param customLabel user-facing intention action text
     * @param priority intention action display priority
     */
    public StvnQualifyTypeQuickFix(TypeKeyword element, String fqni, String customLabel, Priority priority) {
        super(element);
        this.fqni = fqni;
        this.customLabel = customLabel;
        this.priority = priority;
    }

    @Override
    public @NotNull Priority getPriority() {
        return priority;
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
