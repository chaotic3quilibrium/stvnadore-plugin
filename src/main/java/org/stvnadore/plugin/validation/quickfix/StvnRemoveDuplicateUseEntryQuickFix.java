package org.stvnadore.plugin.validation.quickfix;

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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.psi.UseElement;
import org.stvnadore.psi.UseStmt;

import java.util.List;

/**
 * Surgical quick-fix removing a duplicate {@link UseElement} entry in the same lexical scope
 * without leaving orphan blank lines or disturbing sibling {@code :use} entries.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnRemoveDuplicateUseEntryQuickFix
        extends LocalQuickFixAndIntentionActionOnPsiElement
        implements PriorityAction {

    /**
     * Canonical STE quick-fix label.
     */
    public static final String QUICK_FIX_NAME =
        "Remove duplicate '" + StvnVocabulary.KEYWORD_USE + "' entry";

    /**
     * Constructs a new {@code StvnRemoveDuplicateUseEntryQuickFix}.
     *
     * @param duplicateElement duplicate later {@link UseElement}
     */
    public StvnRemoveDuplicateUseEntryQuickFix(UseElement duplicateElement) {
        super(duplicateElement);
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
        if (!(startElement instanceof UseElement useElement) || !useElement.isValid()) {
            return;
        }
        UseStmt useStmt = PsiTreeUtil.getParentOfType(useElement, UseStmt.class, false);
        Document doc = file.getViewProvider().getDocument();
        if (useStmt == null || doc == null) {
            return;
        }
        PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
        boolean commitPhysical = file.isPhysical() && !IntentionPreviewUtils.isPreviewElement(useElement);
        if (commitPhysical) {
            docManager.doPostponedOperationsAndUnblockDocument(doc);
        }

        List<UseElement> siblings = useStmt.getUseElementList();
        if (siblings.size() <= 1) {
            deleteLineOrSpanCleanly(doc, useStmt.getTextRange().getStartOffset(), useStmt.getTextRange().getEndOffset());
        } else {
            deleteLineOrSpanCleanly(doc, useElement.getTextRange().getStartOffset(), useElement.getTextRange().getEndOffset());
        }

        if (commitPhysical) {
            docManager.commitDocument(doc);
        }
    }

    static void deleteLineOrSpanCleanly(Document doc, int elemStart, int elemEnd) {
        CharSequence chars = doc.getCharsSequence();
        int len = chars.length();
        int startLine = doc.getLineNumber(elemStart);
        int endLine = doc.getLineNumber(Math.max(elemStart, elemEnd - 1));
        int lineStart = doc.getLineStartOffset(startLine);
        int lineEnd = doc.getLineEndOffset(endLine);

        boolean onlyWhitespaceBeforeOnLine = true;
        for (int i = lineStart; i < elemStart; i++) {
            char c = chars.charAt(i);
            if (c != ' ' && c != '\t') {
                onlyWhitespaceBeforeOnLine = false;
                break;
            }
        }
        boolean onlyWhitespaceAfterOnLine = true;
        for (int i = elemEnd; i < lineEnd; i++) {
            char c = chars.charAt(i);
            if (c != ' ' && c != '\t') {
                onlyWhitespaceAfterOnLine = false;
                break;
            }
        }

        if (onlyWhitespaceBeforeOnLine && onlyWhitespaceAfterOnLine) {
            if (lineStart > 0 && chars.charAt(lineStart - 1) == '\n') {
                int delStart = (lineStart >= 2 && chars.charAt(lineStart - 2) == '\r')
                    ? lineStart - 2
                    : lineStart - 1;
                doc.deleteString(delStart, lineEnd);
                return;
            }
            if (lineEnd < len) {
                int delEnd = lineEnd;
                if (chars.charAt(delEnd) == '\r') {
                    delEnd++;
                }
                if (delEnd < len && chars.charAt(delEnd) == '\n') {
                    delEnd++;
                }
                doc.deleteString(lineStart, delEnd);
                return;
            }
        }

        int delStart = elemStart;
        int delEnd = elemEnd;
        while (delStart > lineStart && (chars.charAt(delStart - 1) == ' ' || chars.charAt(delStart - 1) == '\t')) {
            delStart--;
        }
        if (delStart == elemStart) {
            while (delEnd < lineEnd && (chars.charAt(delEnd) == ' ' || chars.charAt(delEnd) == '\t')) {
                delEnd++;
            }
        }
        doc.deleteString(delStart, delEnd);
    }
}
