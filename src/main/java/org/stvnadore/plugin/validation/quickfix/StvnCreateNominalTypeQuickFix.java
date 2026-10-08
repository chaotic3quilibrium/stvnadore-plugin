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
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TypeKeyword;

/**
 * Quick-fix creating a new local nominal type definition in :defs.
 */
@NullMarked
public final class StvnCreateNominalTypeQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {

    private final String baseType;
    private final Priority priority;

    /**
     * Constructs a nominal type creation quick-fix with default LOW priority.
     *
     * @param element the unresolved TypeKeyword element
     * @param baseType the underlying base schema type
     */
    public StvnCreateNominalTypeQuickFix(TypeKeyword element, String baseType) {
        this(element, baseType, Priority.LOW);
    }

    /**
     * Constructs a nominal type creation quick-fix with explicit priority.
     *
     * @param element the unresolved TypeKeyword element
     * @param baseType the underlying base schema type
     * @param priority intention action display priority
     */
    public StvnCreateNominalTypeQuickFix(TypeKeyword element, String baseType, Priority priority) {
        super(element);
        this.baseType = baseType;
        this.priority = priority;
    }

    @Override
    public @NotNull Priority getPriority() {
        return priority;
    }

    @Override
    public @NotNull String getText() {
        var elem = getStartElement();
        var name = (elem != null) ? elem.getText() : "Type";
        return "Create nominal type '" + name + "' in :defs";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "STVN Create Nominal Type (:defs)";
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
        var typeStatement = "\n    " + startElement.getText() + " " + baseType;

        doc.insertString(insertOffset, typeStatement);
        PsiDocumentManager.getInstance(project).commitDocument(doc);
        CodeStyleManager.getInstance(project).reformat(file);
    }
}
