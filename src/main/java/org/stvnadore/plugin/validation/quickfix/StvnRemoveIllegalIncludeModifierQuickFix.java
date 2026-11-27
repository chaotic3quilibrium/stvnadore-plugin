package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.psi.IncludeStmt;

/**
 * Surgical quick-fix removing an illegal modifier block (such as {@code { #strip }},
 * {@code { :Old :New }}, or {@code {}}) inside {@code :include [...]}.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnRemoveIllegalIncludeModifierQuickFix
        extends LocalQuickFixAndIntentionActionOnPsiElement
        implements PriorityAction {

    /**
     * Canonical STE quick-fix label.
     */
    public static final String QUICK_FIX_NAME =
        "Remove illegal modifier from '" + StvnVocabulary.KEYWORD_INCLUDE + "'";

    @SafeFieldForPreview
    private final int relStartInStmt;

    @SafeFieldForPreview
    private final int relEndInStmt;

    /**
     * Constructs a new {@code StvnRemoveIllegalIncludeModifierQuickFix} anchored to {@code includeStmt}.
     *
     * @param includeStmt  enclosing {@code :include} statement
     * @param modifierSpan absolute document {@link TextRange} of the illegal modifier block
     */
    public StvnRemoveIllegalIncludeModifierQuickFix(IncludeStmt includeStmt, TextRange modifierSpan) {
        super(includeStmt);
        int base = includeStmt.getTextRange().getStartOffset();
        this.relStartInStmt = Math.max(0, modifierSpan.getStartOffset() - base);
        this.relEndInStmt = Math.max(this.relStartInStmt, modifierSpan.getEndOffset() - base);
    }

    @Override
    public @NotNull String getText() {
        return QUICK_FIX_NAME;
    }

    @Override
    public @NotNull String getFamilyName() {
        return QUICK_FIX_NAME;
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.TOP;
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        if (!(startElement instanceof IncludeStmt stmt) || !stmt.isValid()) {
            return;
        }
        Document doc = file.getViewProvider().getDocument();
        if (doc == null) {
            return;
        }
        int stmtStart = stmt.getTextRange().getStartOffset();
        int stmtEnd = stmt.getTextRange().getEndOffset();
        int absStart = stmtStart + relStartInStmt;
        int absEnd = stmtStart + relEndInStmt;
        if (absStart < stmtStart || absEnd > stmtEnd || absStart >= absEnd) {
            return;
        }

        CharSequence chars = doc.getCharsSequence();
        int delStart = absStart;
        int delEnd = absEnd;
        while (delStart > stmtStart && (chars.charAt(delStart - 1) == ' ' || chars.charAt(delStart - 1) == '\t')) {
            delStart--;
        }
        if (delStart == absStart) {
            while (delEnd < stmtEnd && (chars.charAt(delEnd) == ' ' || chars.charAt(delEnd) == '\t')) {
                delEnd++;
            }
        }

        PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
        boolean commitPhysical = file.isPhysical() && !IntentionPreviewUtils.isPreviewElement(stmt);
        if (commitPhysical) {
            docManager.doPostponedOperationsAndUnblockDocument(doc);
        }
        doc.deleteString(delStart, delEnd);
        if (commitPhysical) {
            docManager.commitDocument(doc);
        }
    }
}
