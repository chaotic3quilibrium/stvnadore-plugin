package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DoNotAskOption;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.core.printer.AstCompactPrinter;
import org.stvnadore.plugin.StvnFile;
import org.stvnadore.plugin.settings.StvnProjectSettings;

import java.util.Optional;

/**
 * Editor context action converting active STVN document text into canonical compact single-line layout.
 * <p>
 * This action checks for {@link PsiComment} tokens. If comments exist, it presents a confirmation
 * dialog warning that compact formatting strips comments, with option to persist the decision.
 * Upon confirmation, it lowers the document to an AST {@link StvnValue} and serializes via {@link AstCompactPrinter}.
 * </p>
 *
 * @since 1.3.0
 */
@NullMarked
public final class StvnConvertToCompactPrintAction extends AnAction {

    /**
     * Default constructor for the compact-print projection action.
     */
    public StvnConvertToCompactPrintAction() {
        super();
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        if (project == null || editor == null || !(psiFile instanceof StvnFile)) {
            return;
        }

        // Comment Destruction Warning Guard
        boolean hasComments = PsiTreeUtil.findChildOfType(psiFile, PsiComment.class) != null;
        var settings = StvnProjectSettings.getInstance(project);

        if (hasComments && !settings.getState().suppressCompactCommentWarning) {
            DoNotAskOption doNotAsk = new DoNotAskOption.Adapter() {
                @Override
                public void rememberChoice(boolean isSelected, int exitCode) {
                    if (isSelected) {
                        settings.getState().suppressCompactCommentWarning = true;
                    }
                }

                @Override
                public @NotNull String getDoNotShowMessage() {
                    return "Do not show this warning again for this project";
                }

                @Override
                public boolean shouldSaveOptionsOnCancel() {
                    return false;
                }
            };

            boolean confirmed = MessageDialogBuilder.yesNo(
                    "STVN: Convert to Compact Print",
                    "Compact formatting strips all comments. This action cannot be reversed except via Undo. Do you want to proceed?"
            ).doNotAsk(doNotAsk).ask(project);

            if (!confirmed) {
                return;
            }
        }

        Document document = editor.getDocument();
        String text = document.getText();

        Optional<StvnValue> astOptional;
        try {
            astOptional = StvnCompiler.compile(text);
        } catch (Exception ex) {
            StvnWorkspaceNotificationHelper.showGenericError(project, ex);
            return;
        }

        if (astOptional.isEmpty()) {
            return;
        }

        String compact = AstCompactPrinter.print(astOptional.get());
        WriteCommandAction.runWriteCommandAction(project, "STVN: Convert to Compact Print", null, () -> {
            document.replaceString(0, document.getTextLength(), compact);
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        boolean enabled = project != null && editor != null && (psiFile instanceof StvnFile);
        e.getPresentation().setEnabledAndVisible(enabled);
    }
}
