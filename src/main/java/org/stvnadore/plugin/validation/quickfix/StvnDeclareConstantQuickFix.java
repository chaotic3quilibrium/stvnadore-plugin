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
import org.stvnadore.psi.ValueKeyword;

/**
 * Quick-fix declaring a constant definition in :defs for an unresolved value keyword.
 */
@NullMarked
public final class StvnDeclareConstantQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {

    private final String schemaType;
    private final String initialValue;
    private final Priority priority;

    /**
     * Constructs a constant declaration quick-fix with default NORMAL priority.
     *
     * @param element the unresolved ValueKeyword element
     * @param schemaType the constant schema type
     * @param initialValue the initial value literal
     */
    public StvnDeclareConstantQuickFix(ValueKeyword element, String schemaType, String initialValue) {
        this(element, schemaType, initialValue, Priority.NORMAL);
    }

    /**
     * Constructs a constant declaration quick-fix with explicit priority.
     *
     * @param element the unresolved ValueKeyword element
     * @param schemaType the constant schema type
     * @param initialValue the initial value literal
     * @param priority intention action display priority
     */
    public StvnDeclareConstantQuickFix(ValueKeyword element, String schemaType, String initialValue, Priority priority) {
        super(element);
        this.schemaType = schemaType;
        this.initialValue = initialValue;
        this.priority = priority;
    }

    @Override
    public @NotNull Priority getPriority() {
        return priority;
    }

    @Override
    public @NotNull String getText() {
        var elem = getStartElement();
        var name = (elem != null) ? elem.getText() : "#CONST";
        return "Declare constant '" + name + "' in :defs";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "STVN Declare Constant (:defs)";
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
        var constStatement = "\n    " + startElement.getText() + " " + schemaType + " " + initialValue;

        doc.insertString(insertOffset, constStatement);
        PsiDocumentManager.getInstance(project).commitDocument(doc);
        CodeStyleManager.getInstance(project).reformat(file);
    }
}
