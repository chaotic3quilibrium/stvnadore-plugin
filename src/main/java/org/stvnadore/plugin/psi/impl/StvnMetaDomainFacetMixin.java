package org.stvnadore.plugin.psi.impl;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.reference.StvnMetaReference;
import org.stvnadore.psi.MetaDomainFacet;

/**
 * Mixin implementation for {@link MetaDomainFacet} providing reference resolution
 * to {@code :org/stvnadore/prelude/meta/StvnPosixId64}.
 */
@NullMarked
public abstract class StvnMetaDomainFacetMixin extends ASTWrapperPsiElement implements MetaDomainFacet {

  /**
   * Constructs an StvnMetaDomainFacetMixin instance.
   *
   * @param node the underlying AST node
   */
  protected StvnMetaDomainFacetMixin(@NotNull ASTNode node) {
    super(node);
  }

  @Override
  public @Nullable PsiReference getReference() {
    var references = getReferences();
    return references.length > 0 ? references[0] : null;
  }

  @Override
  public PsiReference @NotNull [] getReferences() {
    return new PsiReference[]{StvnMetaReference.forPosixIdFacet(this, 7)};
  }
}
