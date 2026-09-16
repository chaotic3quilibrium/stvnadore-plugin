package org.stvnadore.plugin.psi.impl;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.IncludeMapAlias;
import org.stvnadore.psi.TypeKeyword;

@NullMarked
public abstract class StvnIncludeMapAliasMixin extends ASTWrapperPsiElement implements IncludeMapAlias {

    protected StvnIncludeMapAliasMixin(ASTNode node) {
        super(node);
    }

    @Override
    public @Nullable PsiElement getNameIdentifier() {
        var children = PsiTreeUtil.getChildrenOfType(this, TypeKeyword.class);
        if (children != null && children.length >= 2) {
            return children[1];
        }
        return null;
    }

    @Override
    public String getName() {
        var identifier = getNameIdentifier();
        var name = identifier instanceof com.intellij.psi.PsiNamedElement named ? named.getName() : null;
        return name != null ? name : (identifier != null ? identifier.getText() : "");
    }

    @Override
    public PsiElement setName(@NotNull String name) throws IncorrectOperationException {
        var identifier = getNameIdentifier();
        if (identifier != null) {
            var newKeyword = StvnElementFactory.createTypeKeyword(getProject(), name.startsWith(":") ? name : ":" + name);
            identifier.replace(newKeyword);
        }
        return this;
    }

    /**
     * Returns the text offset within the file where the declared include alias identifier begins.
     *
     * @return the start offset of the alias name identifier token, or the container offset if null
     */
    @Override
    public int getTextOffset() {
        var identifier = getNameIdentifier();
        return identifier != null ? identifier.getTextOffset() : super.getTextOffset();
    }
}

