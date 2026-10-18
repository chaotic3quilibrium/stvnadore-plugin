package org.stvnadore.plugin.intentions;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFile;
import org.stvnadore.plugin.settings.StvnSettings;

/**
 * Intention action (Alt+Enter) that bootstraps a valid, canonical STVN document structure
 * conforming to file extension in an empty or whitespace/comment-only document.
 */
@NullMarked
public final class StvnBootstrapDocumentIntentionAction implements IntentionAction {

    @Override
    public @NotNull String getText() {
        return "Bootstrap STVN Document";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Bootstrap STVN Document";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        if (!(file instanceof StvnFile)) {
            return false;
        }
        var fileName = file.getName();
        if (!fileName.endsWith(".stvn") && !fileName.endsWith(".stvn_i")
                && !fileName.endsWith(".stvn_d") && !fileName.endsWith(".stvn_di")) {
            return false;
        }
        return isBufferUninitialized(file);
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file)
            throws IncorrectOperationException {
        if (!(file instanceof StvnFile)) {
            return;
        }
        Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
        if (doc == null) {
            return;
        }

        var fileName = file.getName();
        var settings = StvnSettings.getInstance(project).getState();
        String template;
        if (fileName.endsWith(".stvn_d")) {
            template = settings.bootstrapTemplateStvnD;
        } else if (fileName.endsWith(".stvn_di")) {
            template = settings.bootstrapTemplateStvnDi;
        } else if (fileName.endsWith(".stvn_i")) {
            template = settings.bootstrapTemplateStvnI;
        } else {
            template = settings.bootstrapTemplateStvn;
        }

        // Interpolate document stem into #name facet if present
        int lastDot = fileName.lastIndexOf('.');
        String stem = lastDot >= 0 ? fileName.substring(0, lastDot) : fileName;
        String[] dotParts = stem.split("\\.");
        String slot0 = dotParts.length > 0 && !dotParts[0].isEmpty() ? dotParts[0] : "document";
        String resolvedTemplate = template.replace("\"schema\"", "\"" + slot0 + "\"")
                                          .replace("\"payload\"", "\"" + slot0 + "\"");

        doc.replaceString(0, doc.getTextLength(), resolvedTemplate);
        PsiDocumentManager.getInstance(project).commitDocument(doc);
        CodeStyleManager.getInstance(project).reformat(file);

        if (editor != null) {
            repositionCaret(editor, doc, fileName);
        }
    }

    @Override
    public boolean startInWriteAction() {
        return true;
    }

    private static boolean isBufferUninitialized(PsiFile file) {
        CharSequence chars = file.getViewProvider().getContents();
        if (chars.toString().trim().isEmpty()) {
            return true;
        }
        for (PsiElement child = file.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof PsiWhiteSpace) && !(child instanceof PsiComment)) {
                return false;
            }
        }
        return true;
    }

    private static void repositionCaret(Editor editor, Document doc, String fileName) {
        String text = doc.getText();
        int targetOffset;
        if (fileName.endsWith(".stvn") || fileName.endsWith(".stvn_i")) {
            int bodyIdx = text.indexOf(":body \"\"");
            targetOffset = bodyIdx >= 0 ? bodyIdx + 7 : doc.getTextLength();
        } else {
            int typeIdx = text.indexOf(":Type :String");
            targetOffset = typeIdx >= 0 ? typeIdx : doc.getTextLength();
        }
        editor.getCaretModel().moveToOffset(Math.min(targetOffset, doc.getTextLength()));
    }
}
