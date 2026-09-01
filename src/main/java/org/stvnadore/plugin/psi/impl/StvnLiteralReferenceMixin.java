package org.stvnadore.plugin.psi.impl;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.reference.StvnValueKeywordReference;

@NullMarked
public abstract class StvnLiteralReferenceMixin extends ASTWrapperPsiElement {

    protected StvnLiteralReferenceMixin(ASTNode node) {
        super(node);
    }

    @Override
    public @Nullable PsiReference getReference() {
        var references = getReferences();
        return references.length > 0 ? references[0] : null;
    }

    @Override
    public PsiReference @NotNull [] getReferences() {
        var parent = getParent();
        if (parent instanceof org.stvnadore.psi.ConstantDefinition constDef && constDef.getValueKeyword() == this) {
            return PsiReference.EMPTY_ARRAY;
        }
        if (parent instanceof org.stvnadore.psi.EnumDef) {
            return PsiReference.EMPTY_ARRAY;
        }
        return new PsiReference[]{new StvnValueKeywordReference(this)};
    }
}
