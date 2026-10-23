package org.stvnadore.plugin.intentions;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFile;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;

/**
 * Context action (Alt+Enter) offering to insert a canonical root :meta header into
 * STVN documents lacking a root :meta block.
 */
@NullMarked
public final class StvnAddMetaBlockIntention extends PsiElementBaseIntentionAction {

  /**
   * Constructs a new StvnAddMetaBlockIntention instance.
   */
  public StvnAddMetaBlockIntention() {}

  @Override
  public @NotNull String getText() {
    return "Add root :meta header";
  }

  @Override
  public @NotNull String getFamilyName() {
    return "Add root :meta header";
  }

  @Override
  public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element) {
    if (!element.isValid()) {
      return false;
    }
    var file = element.getContainingFile();
    if (!(file instanceof StvnFile)) {
      return false;
    }
    if (file.getTextLength() == 0 || file.getText().isBlank()) {
      return false;
    }
    var rootBrace = StvnMetaTemplateUtil.findRootOpeningBrace(file);
    if (rootBrace == null || !"{".equals(rootBrace.getText())) {
      return false;
    }
    return !StvnMetaTemplateUtil.hasMetaHeader(file);
  }

  @Override
  public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
    if (!(file instanceof StvnFile) || file.getTextLength() == 0 || file.getText().isBlank()) {
      return IntentionPreviewInfo.EMPTY;
    }
    var rootBrace = StvnMetaTemplateUtil.findRootOpeningBrace(file);
    if (rootBrace == null || !"{".equals(rootBrace.getText())) {
      return IntentionPreviewInfo.EMPTY;
    }
    if (StvnMetaTemplateUtil.hasMetaHeader(file)) {
      return IntentionPreviewInfo.EMPTY;
    }
    StvnMetaTemplateUtil.insertCanonicalMetaBlock(project, file, editor);
    return IntentionPreviewInfo.DIFF;
  }

  @Override
  public void invoke(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element)
      throws IncorrectOperationException {
    var file = element.getContainingFile();
    if (file == null || !(file instanceof StvnFile)) {
      return;
    }
    if (file.getTextLength() == 0 || file.getText().isBlank()) {
      return;
    }
    var rootBrace = StvnMetaTemplateUtil.findRootOpeningBrace(file);
    if (rootBrace == null || !"{".equals(rootBrace.getText())) {
      return;
    }
    if (StvnMetaTemplateUtil.hasMetaHeader(file)) {
      return;
    }
    StvnMetaTemplateUtil.insertCanonicalMetaBlock(project, file, editor);
  }
}
