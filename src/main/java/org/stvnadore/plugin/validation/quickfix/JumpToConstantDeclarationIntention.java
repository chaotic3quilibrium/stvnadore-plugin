package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;

import java.util.Objects;

/**
 * Navigates from a {@code :body} poisoned or circular constant reference directly to the target
 * {@code ConstantDefinition} keyword in same-file or {@code :include}d {@code :defs} sections.
 */
@NullMarked
public final class JumpToConstantDeclarationIntention
        extends LocalQuickFixAndIntentionActionOnPsiElement
        implements PriorityAction {

    @SafeFieldForPreview
    private final String constantName;

    /**
     * Constructs a navigation intention targeting {@code targetKeyword} in {@code :defs}.
     *
     * @param targetKeyword target {@code ConstantDefinition.getValueKeyword()} element
     * @param constantName  display name of the constant (for example {@code "#CONST_PING"})
     */
    public JumpToConstantDeclarationIntention(PsiElement targetKeyword, String constantName) {
        super(targetKeyword);
        this.constantName = constantName;
    }

    @Override
    public @Nls @NotNull String getText() {
        return "Jump to '" + constantName + "' declaration in " + StvnVocabulary.KEYWORD_DEFS;
    }

    @Override
    public @Nls @NotNull String getFamilyName() {
        return "Jump to constant declaration";
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.TOP;
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file
    ) {
        return IntentionPreviewInfo.EMPTY;
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull ProblemDescriptor previewDescriptor
    ) {
        return IntentionPreviewInfo.EMPTY;
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        var targetFile = startElement.getContainingFile();
        var virtualFile = targetFile != null
            ? (targetFile.getVirtualFile() != null
                ? targetFile.getVirtualFile()
                : targetFile.getOriginalFile().getVirtualFile())
            : null;
        if (virtualFile == null) {
            return;
        }
        var descriptor = new OpenFileDescriptor(project, virtualFile, startElement.getTextOffset());
        var targetEditor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
        if (targetEditor != null) {
            var range = startElement.getTextRange();
            targetEditor.getCaretModel().moveToOffset(range.getStartOffset());
            targetEditor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
            targetEditor.getSelectionModel().setSelection(range.getStartOffset(), range.getEndOffset());
        }
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof JumpToConstantDeclarationIntention other)) {
            return false;
        }
        return Objects.equals(getStartElement(), other.getStartElement())
            && Objects.equals(constantName, other.constantName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getStartElement(), constantName);
    }
}
