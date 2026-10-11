package org.stvnadore.plugin.psi.impl;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.reference.StvnMetaReference;
import org.stvnadore.psi.MetaKindFacet;

/**
 * Mixin implementation for {@link MetaKindFacet} providing reference resolution
 * to {@code :org/stvnadore/prelude/meta/StvnDocumentKind}.
 */
@NullMarked
public abstract class StvnMetaKindFacetMixin extends ASTWrapperPsiElement implements MetaKindFacet {

  /**
   * Constructs an StvnMetaKindFacetMixin instance.
   *
   * @param node the underlying AST node
   */
  protected StvnMetaKindFacetMixin(@NotNull ASTNode node) {
    super(node);
  }

  @Override
  public @Nullable PsiReference getReference() {
    var references = getReferences();
    return references.length > 0 ? references[0] : null;
  }

  @Override
  public PsiReference @NotNull [] getReferences() {
    return new PsiReference[]{StvnMetaReference.forKindFacet(this)};
  }
}
