package org.stvnadore.plugin.psi.impl;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiReference;
import org.jetbrains.annotations.NotNull;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.plugin.reference.StvnIncludeReference;

public abstract class StvnStringLiteralMixin extends ASTWrapperPsiElement implements StringLiteral {
    
    protected StvnStringLiteralMixin(@NotNull ASTNode node) {
        super(node);
    }

    @Override
    public PsiReference getReference() {
        PsiReference[] references = getReferences();
        return references.length > 0 ? references[0] : null;
    }

    @Override
    public PsiReference @NotNull [] getReferences() {
        if (getParent() instanceof IncludeElement) {
            return new PsiReference[]{new StvnIncludeReference((StringLiteral) this)};
        }
        return PsiReference.EMPTY_ARRAY;
    }
}
