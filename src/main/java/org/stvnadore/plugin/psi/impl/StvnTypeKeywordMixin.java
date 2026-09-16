package org.stvnadore.plugin.psi.impl;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.IncludeMapAlias;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.plugin.reference.StvnTypeReference;

@NullMarked
public abstract class StvnTypeKeywordMixin extends ASTWrapperPsiElement implements TypeKeyword, com.intellij.psi.PsiNameIdentifierOwner {

    protected StvnTypeKeywordMixin(ASTNode node) {
        super(node);
    }

    @Override
    public @Nullable PsiElement getNameIdentifier() {
        return this;
    }

    @Override
    public String getName() {
        return getText();
    }

    @Override
    public PsiElement setName(@NotNull String name) throws com.intellij.util.IncorrectOperationException {
        if (name.startsWith("#")) {
            throw new com.intellij.util.IncorrectOperationException("Cannot rename type to constant symbol '" + name + "'");
        }
        var newKw = org.stvnadore.plugin.psi.StvnElementFactory.createTypeKeyword(getProject(), name.startsWith(":") ? name : ":" + name);
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
        if (org.stvnadore.plugin.psi.StvnPsiUtils.isTypeDefinitionTarget(this)) {
            return PsiReference.EMPTY_ARRAY;
        }
        if (parent instanceof org.stvnadore.psi.PackagePath) {
            return PsiReference.EMPTY_ARRAY;
        }
        if (parent instanceof IncludeMapAlias alias) {
            var list = alias.getTypeKeywordList();
            if (list.size() >= 2 && list.get(1) == this) {
                return PsiReference.EMPTY_ARRAY;
            }
        }
        if (parent instanceof org.stvnadore.psi.UseMapAlias alias) {
            var list = alias.getTypeKeywordList();
            if (list.size() >= 2 && list.get(1) == this) {
                return PsiReference.EMPTY_ARRAY;
            }
        }
        return new PsiReference[]{new StvnTypeReference((TypeKeyword) this)};
    }
}
