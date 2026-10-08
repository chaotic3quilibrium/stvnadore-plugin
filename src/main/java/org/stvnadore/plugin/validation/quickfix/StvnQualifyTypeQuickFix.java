package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.BatchQuickFix;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.TypeKeyword;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Quick-fix performing in-place FQNI qualification of an unresolved type keyword.
 * Supports both single-caret and deterministic atomic batch execution across all file descriptors.
 */
@NullMarked
public final class StvnQualifyTypeQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction, BatchQuickFix {

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

    @Override
    public void applyFix(
            @NotNull Project project,
            @NotNull CommonProblemDescriptor[] descriptors,
            @NotNull List<PsiElement> psiElementsToIgnore,
            @Nullable Runnable refreshViews
    ) {
        if (descriptors.length == 0) {
            return;
        }

        record ReplacementTarget(TypeKeyword element, String targetFqni, int startOffset) {}
        List<ReplacementTarget> targets = new ArrayList<>();
        Set<TypeKeyword> visited = new HashSet<>();
        PsiFile targetFile = null;

        for (CommonProblemDescriptor descriptor : descriptors) {
            if (descriptor instanceof ProblemDescriptor problemDescriptor) {
                var psi = problemDescriptor.getPsiElement();
                if (psi instanceof TypeKeyword typeKw && typeKw.isValid() && visited.add(typeKw)) {
                    if (targetFile == null) {
                        targetFile = typeKw.getContainingFile();
                    }
                    var text = typeKw.getText().trim();
                    String resolvedFqni;
                    if (text.startsWith(":") && !text.substring(1).contains("/")) {
                        resolvedFqni = ":org/stvnadore/prelude/" + text.substring(1);
                    } else {
                        resolvedFqni = fqni;
                    }
                    targets.add(new ReplacementTarget(typeKw, resolvedFqni, typeKw.getTextRange().getStartOffset()));
                }
            }
        }

        if (targets.isEmpty() || targetFile == null) {
            return;
        }

        // Descending offset order prevents token offset displacement
        targets.sort((a, b) -> Integer.compare(b.startOffset(), a.startOffset()));

        for (var target : targets) {
            if (target.element().isValid()) {
                var newKeyword = StvnElementFactory.createTypeKeyword(project, target.targetFqni());
                target.element().replace(newKeyword);
            }
        }

        CodeStyleManager.getInstance(project).reformat(targetFile);
        if (refreshViews != null) {
            refreshViews.run();
        }
    }
}
