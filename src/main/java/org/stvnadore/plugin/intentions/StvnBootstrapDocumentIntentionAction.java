package org.stvnadore.plugin.intentions;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        if (violatesLayer1Identity(file, project)) {
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
        Document doc = file.getViewProvider().getDocument();
        if (doc == null && editor != null) {
            doc = editor.getDocument();
        }
        if (doc == null) {
            doc = PsiDocumentManager.getInstance(project).getDocument(file);
        }
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

        // Interpolate document stem into #name and #domain facets if present
        int lastDot = fileName.lastIndexOf('.');
        String stem = lastDot >= 0 ? fileName.substring(0, lastDot) : fileName;
        String[] dotParts = stem.split("\\.");
        String slot0 = dotParts.length > 0 && !dotParts[0].isEmpty() ? dotParts[0] : "document";
        String resolvedTemplate = template.replace("\"schema\"", "\"" + slot0 + "\"")
                                          .replace("\"payload\"", "\"" + slot0 + "\"");

        if (dotParts.length > 1 && !dotParts[1].isEmpty()) {
            String slot1 = dotParts[1];
            Pattern domainPattern = Pattern.compile("(#domain\\s+\")[^\"]*(\")");
            Matcher domainMatcher = domainPattern.matcher(resolvedTemplate);
            if (domainMatcher.find()) {
                resolvedTemplate = domainMatcher.replaceFirst("$1" + Matcher.quoteReplacement(slot1) + "$2");
            } else {
                Pattern namePattern = Pattern.compile("(?m)^([ \\t]*#name\\s+\"[^\"]*\"[ \\t]*\\r?\\n)");
                Matcher nameMatcher = namePattern.matcher(resolvedTemplate);
                if (nameMatcher.find()) {
                    String nameLine = nameMatcher.group(1);
                    int firstNonWs = 0;
                    while (firstNonWs < nameLine.length() && (nameLine.charAt(firstNonWs) == ' ' || nameLine.charAt(firstNonWs) == '\t')) {
                        firstNonWs++;
                    }
                    String indent = nameLine.substring(0, firstNonWs);
                    String lineEnding = nameLine.endsWith("\r\n") ? "\r\n" : "\n";
                    String domainLine = indent + "#domain \"" + slot1 + "\"" + lineEnding;
                    resolvedTemplate = resolvedTemplate.substring(0, nameMatcher.end())
                            + domainLine
                            + resolvedTemplate.substring(nameMatcher.end());
                }
            }
        }

        doc.replaceString(0, doc.getTextLength(), resolvedTemplate);
        PsiDocumentManager.getInstance(project).commitDocument(doc);
        CodeStyleManager.getInstance(project).reformat(file);

        if (editor != null) {
            repositionCaret(editor, doc, fileName);
        }
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        if (!isAvailable(project, editor, file)) {
            return IntentionPreviewInfo.EMPTY;
        }
        invoke(project, editor, file);
        return IntentionPreviewInfo.DIFF;
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

    private static boolean violatesLayer1Identity(PsiFile file, Project project) {
        var fileName = file.getName();
        if (fileName.startsWith(".")) {
            return true;
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0) {
            return true;
        }
        String stem = fileName.substring(0, lastDot);
        if (stem.isEmpty()) {
            return true;
        }
        String[] dotParts = stem.split("\\.");
        if (dotParts.length > 1 && !dotParts[1].isEmpty()) {
            String slot1 = dotParts[1];
            if ("stvn_core".equals(slot1) || "stvn_ir".equals(slot1) || "stvn_cas".equals(slot1)) {
                var vFile = file.getVirtualFile();
                if (vFile != null && ProjectFileIndex.getInstance(project).isInContent(vFile)) {
                    return true;
                }
            }
        }
        return false;
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
