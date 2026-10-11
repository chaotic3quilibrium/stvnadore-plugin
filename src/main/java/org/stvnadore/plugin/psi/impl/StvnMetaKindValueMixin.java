package org.stvnadore.plugin.psi.impl;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.reference.StvnMetaReference;
import org.stvnadore.psi.MetaKindValue;

/**
 * Mixin implementation for {@link MetaKindValue} providing reference resolution
 * to the corresponding enum variant inside {@code :org/stvnadore/prelude/meta/StvnDocumentKind}.
 */
@NullMarked
public abstract class StvnMetaKindValueMixin extends ASTWrapperPsiElement implements MetaKindValue {

  /**
   * Constructs an StvnMetaKindValueMixin instance.
   *
   * @param node the underlying AST node
   */
  protected StvnMetaKindValueMixin(@NotNull ASTNode node) {
    super(node);
  }

  @Override
  public @Nullable PsiReference getReference() {
    var references = getReferences();
    return references.length > 0 ? references[0] : null;
  }

  @Override
  public PsiReference @NotNull [] getReferences() {
    return new PsiReference[]{StvnMetaReference.forKindValue(this)};
  }
}
