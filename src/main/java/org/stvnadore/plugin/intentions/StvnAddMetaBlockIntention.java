package org.stvnadore.plugin.intentions;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFile;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.MetaEntry;

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
    var metaEntry = PsiTreeUtil.findChildOfType(file, MetaEntry.class);
    return metaEntry == null;
  }

  @Override
  public void invoke(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element)
      throws IncorrectOperationException {
    var file = element.getContainingFile();
    if (file == null) {
      return;
    }
    StvnMetaTemplateUtil.insertCanonicalMetaBlock(project, file, editor);
  }
}
