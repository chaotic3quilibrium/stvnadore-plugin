package org.stvnadore.plugin.editor;

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.codeInsight.template.Template;
import com.intellij.codeInsight.template.TemplateManager;
import com.intellij.codeInsight.template.impl.TemplateSettings;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFile;

/**
 * Automatically launches the interactive 'fence' Live Template when typing '[' immediately following '"""'.
 */
@NullMarked
public final class StvnFencedStringTypedHandler extends TypedHandlerDelegate {

    public StvnFencedStringTypedHandler() {}

    @Override
    public Result charTyped(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        if (c != '[' || !(file instanceof StvnFile)) {
            return Result.CONTINUE;
        }

        var doc = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        if (offset < 4) {
            return Result.CONTINUE;
        }

        String prevChars = doc.getText(new TextRange(offset - 4, offset));
        if (!"\"\"\"[".equals(prevChars)) {
            return Result.CONTINUE;
        }

        if (offset >= 5 && doc.getCharsSequence().charAt(offset - 5) == '"') {
            return Result.CONTINUE;
        }

        if (offset < doc.getTextLength() && doc.getCharsSequence().charAt(offset) == ']') {
            return Result.CONTINUE;
        }

        // Delete the delimiter prefix '"""[' (including the typed '[') to prepare insertion boundary
        doc.deleteString(offset - 4, offset);
        PsiDocumentManager.getInstance(project).commitDocument(doc);

        Template template = TemplateSettings.getInstance().getTemplate("fence", "STVN");
        if (template != null) {
            TemplateManager.getInstance(project).startTemplate(editor, template);
        } else {
            var templateManager = TemplateManager.getInstance(project);
            Template dynamicTemplate = templateManager.createTemplate("", "", "\"\"\"[$TAG$]\n  $END$\n[$TAG$]\"\"\"");
            dynamicTemplate.addVariable("TAG", new com.intellij.codeInsight.template.impl.ConstantNode("TEXT"), true);
            dynamicTemplate.setToReformat(false);
            templateManager.startTemplate(editor, dynamicTemplate);
        }

        return Result.STOP;
    }
}
