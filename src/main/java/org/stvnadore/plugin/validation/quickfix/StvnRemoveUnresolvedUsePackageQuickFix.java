package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.codeInspection.ProblemDescriptor;
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
import org.stvnadore.plugin.validation.StvnFlatDocumentIncludeInspection;
import org.stvnadore.psi.UseElement;
import org.stvnadore.psi.UseStmt;

import java.util.List;

/**
 * Normal-priority quick-fix ({@link Priority#NORMAL}) that removes an unresolved package entry
 * from a {@code :use} directive, deleting strictly the offending {@link UseElement} when sibling
 * entries exist or removing the enclosing {@link UseStmt} when it is the sole entry.
 */
@NullMarked
public final class StvnRemoveUnresolvedUsePackageQuickFix
        extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {

    @SafeFieldForPreview
    private final String targetPackage;

    /**
     * Constructs an element-scoped or directive-scoped {@code :use} package removal quick-fix.
     *
     * @param element       unresolved {@code UseTarget} or {@link UseElement} PSI node
     * @param targetPackage unresolved package path (for example, {@code ":external/missing_pkg"})
     */
    public StvnRemoveUnresolvedUsePackageQuickFix(PsiElement element, String targetPackage) {
        super(element);
        this.targetPackage = targetPackage;
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.NORMAL;
    }

    @Override
    public @NotNull String getText() {
        return "Remove unresolved package '" + targetPackage + "' from " + StvnVocabulary.KEYWORD_USE;
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Remove unresolved package from " + StvnVocabulary.KEYWORD_USE;
    }

    @Override
    public boolean isAvailable(
            @NotNull Project project,
            @NotNull PsiFile file,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        return startElement.isValid()
            && PsiTreeUtil.getParentOfType(startElement, UseStmt.class, false) != null;
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file
    ) {
        PsiElement origStart = getStartElement();
        if (origStart == null || !origStart.isValid()) {
            return IntentionPreviewInfo.EMPTY;
        }
        PsiElement previewElem = origStart.getContainingFile() == file
            ? origStart
            : PsiTreeUtil.findSameElementInCopy(origStart, file);
        removeTargetFromUse(project, previewElem);
        return IntentionPreviewInfo.DIFF;
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull ProblemDescriptor previewDescriptor
    ) {
        PsiElement previewElem = previewDescriptor.getPsiElement();
        if (previewElem == null) {
            return IntentionPreviewInfo.EMPTY;
        }
        removeTargetFromUse(project, previewElem);
        return IntentionPreviewInfo.DIFF;
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        removeTargetFromUse(project, startElement);
    }

    /**
     * Removes either the offending {@link UseElement} (when the enclosing {@link UseStmt} has two or
     * more elements) or the entire {@link UseStmt} (when it is the sole element), preserving sibling
     * {@link UseElement}s and all same-line or adjacent {@code //} comments.
     *
     * @param project active project
     * @param anchor  anchor PSI element inside the target {@link UseElement} or {@link UseStmt}
     */
    public static void removeTargetFromUse(Project project, PsiElement anchor) {
        UseStmt useStmt = anchor instanceof UseStmt u
            ? u
            : PsiTreeUtil.getParentOfType(anchor, UseStmt.class, false);
        if (useStmt == null) {
            return;
        }
        UseElement useElem = anchor instanceof UseElement ue
            ? ue
            : PsiTreeUtil.getParentOfType(anchor, UseElement.class, false);
        List<UseElement> elements = useStmt.getUseElementList();
        if (useElem != null && elements.size() > 1) {
            removeSingleUseElementPreserveComments(project, useStmt, useElem);
            return;
        }
        StvnFlatDocumentIncludeInspection.removeDirectiveFromDefs(project, useStmt);
    }

    private static void removeSingleUseElementPreserveComments(
            Project project,
            UseStmt useStmt,
            UseElement targetElem
    ) {
        PsiFile file = useStmt.getContainingFile();
        if (file == null) {
            return;
        }
        Document doc = file.getViewProvider().getDocument();
        if (doc == null) {
            doc = PsiDocumentManager.getInstance(project).getDocument(file);
        }
        if (doc == null) {
            return;
        }
        PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
        if (file.isPhysical()) {
            docManager.doPostponedOperationsAndUnblockDocument(doc);
        }

        CharSequence chars = doc.getCharsSequence();
        int start = targetElem.getTextRange().getStartOffset();
        int end = targetElem.getTextRange().getEndOffset();

        int lineStart = start;
        while (lineStart > 0 && (chars.charAt(lineStart - 1) == ' ' || chars.charAt(lineStart - 1) == '\t')) {
            lineStart--;
        }
        int wsEnd = end;
        while (wsEnd < chars.length() && (chars.charAt(wsEnd) == ' ' || chars.charAt(wsEnd) == '\t')) {
            wsEnd++;
        }

        boolean ownsLinePrefix = lineStart == 0 || chars.charAt(lineStart - 1) == '\n';
        if (ownsLinePrefix
                && (wsEnd == chars.length() || chars.charAt(wsEnd) == '\n' || chars.charAt(wsEnd) == '\r')) {
            int deleteEnd = advancePastSingleNewline(chars, wsEnd, wsEnd);
            doc.deleteString(lineStart, deleteEnd);
        } else if (ownsLinePrefix
                && wsEnd + 1 < chars.length()
                && chars.charAt(wsEnd) == '/'
                && chars.charAt(wsEnd + 1) == '/') {
            // Preserve leading indentation and same-line '//' comment intact on this line
            doc.deleteString(start, wsEnd);
        } else {
            // Inline sharing line with '[', ']', or sibling UseElements without crossing newlines or comments
            boolean followedByInlineCommentOrClose = wsEnd >= chars.length()
                || chars.charAt(wsEnd) == '\n'
                || chars.charAt(wsEnd) == '\r'
                || chars.charAt(wsEnd) == ']'
                || (wsEnd + 1 < chars.length() && chars.charAt(wsEnd) == '/' && chars.charAt(wsEnd + 1) == '/');
            if (!followedByInlineCommentOrClose && wsEnd > end) {
                doc.deleteString(start, wsEnd);
            } else if (lineStart < start && chars.charAt(lineStart - 1) != '[') {
                doc.deleteString(lineStart, end);
            } else if (lineStart < start && chars.charAt(lineStart - 1) == '[') {
                int keepSingleSpace = Math.min(start, lineStart + 1);
                doc.deleteString(keepSingleSpace, end);
            } else {
                doc.deleteString(start, end);
            }
        }
        docManager.commitDocument(doc);
    }

    private static int advancePastSingleNewline(CharSequence chars, int wsEnd, int fallbackEnd) {
        if (wsEnd < chars.length()) {
            if (chars.charAt(wsEnd) == '\r') {
                return (wsEnd + 1 < chars.length() && chars.charAt(wsEnd + 1) == '\n') ? wsEnd + 2 : wsEnd + 1;
            }
            if (chars.charAt(wsEnd) == '\n') {
                return wsEnd + 1;
            }
        }
        return fallbackEnd;
    }
}
