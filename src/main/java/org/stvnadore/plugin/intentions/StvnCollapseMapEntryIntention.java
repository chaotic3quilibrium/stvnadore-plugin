package org.stvnadore.plugin.intentions;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.plugin.StvnBundle;
import org.stvnadore.plugin.StvnLanguage;

/**
 * Intention action that collapses a comment-free, fenced-string-free multi-line
 * {@code [key value]} map entry at the caret onto a single canonical line.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnCollapseMapEntryIntention
        extends PsiElementBaseIntentionAction
        implements PriorityAction {

    /**
     * Canonical user-facing action text and family name.
     */
    public static final String TEXT = "Collapse map entry to single line";

    @SafeFieldForPreview
    private volatile Priority currentPriority = Priority.HIGH;

    /**
     * Constructs a new {@code StvnCollapseMapEntryIntention}.
     */
    public StvnCollapseMapEntryIntention() {
        setText(StvnBundle.message("stvn.intention.collapse.map.entry.text"));
    }

    @Override
    public @NotNull Priority getPriority() {
        return currentPriority;
    }

    @Override
    public @NotNull String getText() {
        return StvnBundle.message("stvn.intention.collapse.map.entry.text");
    }

    @Override
    public @NotNull String getFamilyName() {
        return StvnBundle.message("stvn.intention.collapse.map.entry.text");
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element) {
        PsiFile file = element.getContainingFile();
        if (editor == null || file == null || !file.getLanguage().isKindOf(StvnLanguage.INSTANCE)) {
            return false;
        }
        var op = StvnContainerLayoutUtil.findCollapseMapEntryOperation(file, editor.getCaretModel().getOffset());
        if (op == null) {
            return false;
        }
        this.currentPriority = op.priority();
        return true;
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element)
            throws IncorrectOperationException {
        PsiFile file = element.getContainingFile();
        if (editor == null || file == null) {
            return;
        }
        var op = StvnContainerLayoutUtil.findCollapseMapEntryOperation(file, editor.getCaretModel().getOffset());
        if (op != null) {
            StvnContainerLayoutUtil.applyOperation(project, file, op);
        }
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file
    ) {
        var op = StvnContainerLayoutUtil.findCollapseMapEntryOperation(file, editor.getCaretModel().getOffset());
        if (op == null) {
            return IntentionPreviewInfo.EMPTY;
        }
        StvnContainerLayoutUtil.applyOperation(project, file, op);
        return IntentionPreviewInfo.DIFF;
    }
}
