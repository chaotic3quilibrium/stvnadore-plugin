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
 * Intention action that expands every single-line {@code [key value]} entry inside the
 * innermost eligible {@code :Map} literal at the caret into canonical 4-slot multi-line symmetry.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnExpandAllMapEntriesIntention
        extends PsiElementBaseIntentionAction
        implements PriorityAction {

    /**
     * Canonical user-facing action text and family name.
     */
    public static final String TEXT = "Expand all map entries to multiple lines";

    @SafeFieldForPreview
    private volatile Priority currentPriority = Priority.HIGH;

    /**
     * Constructs a new {@code StvnExpandAllMapEntriesIntention}.
     */
    public StvnExpandAllMapEntriesIntention() {
        setText(StvnBundle.message("stvn.intention.expand.all.map.entries.text"));
    }

    @Override
    public @NotNull Priority getPriority() {
        return currentPriority;
    }

    @Override
    public @NotNull String getText() {
        return StvnBundle.message("stvn.intention.expand.all.map.entries.text");
    }

    @Override
    public @NotNull String getFamilyName() {
        return StvnBundle.message("stvn.intention.expand.all.map.entries.text");
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element) {
        PsiFile file = element.getContainingFile();
        if (editor == null || file == null || !file.getLanguage().isKindOf(StvnLanguage.INSTANCE)) {
            return false;
        }
        var op = StvnContainerLayoutUtil.findExpandAllMapEntriesOperation(file, editor.getCaretModel().getOffset());
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
        var op = StvnContainerLayoutUtil.findExpandAllMapEntriesOperation(file, editor.getCaretModel().getOffset());
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
        var op = StvnContainerLayoutUtil.findExpandAllMapEntriesOperation(file, editor.getCaretModel().getOffset());
        if (op == null) {
            return IntentionPreviewInfo.EMPTY;
        }
        StvnContainerLayoutUtil.applyOperation(project, file, op);
        return IntentionPreviewInfo.DIFF;
    }
}
