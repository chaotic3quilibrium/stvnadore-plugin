package org.stvnadore.plugin.psi.impl;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.ValueKeyword;
import org.stvnadore.plugin.reference.StvnConstantReference;

@NullMarked
public abstract class StvnValueKeywordMixin extends ASTWrapperPsiElement implements ValueKeyword, com.intellij.psi.PsiNameIdentifierOwner {

    protected StvnValueKeywordMixin(ASTNode node) {
        super(node);
    }

    @Override
    public @Nullable PsiElement getNameIdentifier() {
        return this;
    }

    @Override
    public String getName() {
        var text = getText();
        return text.startsWith("#") ? text.substring(1) : text;
    }

    @Override
    public PsiElement setName(@NotNull String name) throws com.intellij.util.IncorrectOperationException {
        var newKw = org.stvnadore.plugin.psi.StvnElementFactory.createValueKeyword(getProject(), name.startsWith("#") ? name : "#" + name);
        return replace(newKw);
    }

    @Override
    public @Nullable PsiReference getReference() {
        var references = getReferences();
        return references.length > 0 ? references[0] : null;
    }

    @Override
    public PsiReference @NotNull [] getReferences() {
        var parent = getParent();
        if (parent instanceof ConstantDefinition constDef && constDef.getValueKeyword() == this) {
            return PsiReference.EMPTY_ARRAY;
        }
        if (parent instanceof org.stvnadore.psi.EnumDef) {
            return PsiReference.EMPTY_ARRAY;
        }
        return new PsiReference[]{new org.stvnadore.plugin.reference.StvnValueKeywordReference(this)};
    }
}
