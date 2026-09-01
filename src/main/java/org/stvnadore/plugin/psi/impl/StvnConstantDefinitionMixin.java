package org.stvnadore.plugin.psi.impl;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.ValueKeyword;

@NullMarked
public abstract class StvnConstantDefinitionMixin extends ASTWrapperPsiElement implements ConstantDefinition {

    protected StvnConstantDefinitionMixin(ASTNode node) {
        super(node);
    }

    @Override
    public @Nullable PsiElement getNameIdentifier() {
        return findChildByClass(ValueKeyword.class);
    }

    @Override
    public String getName() {
        var identifier = getNameIdentifier();
        return identifier instanceof com.intellij.psi.PsiNamedElement ? ((com.intellij.psi.PsiNamedElement) identifier).getName() : "";
    }

    @Override
    public PsiElement setName(@NotNull String name) throws IncorrectOperationException {
        var identifier = getNameIdentifier();
        if (identifier != null) {
            var newValueKw = StvnElementFactory.createValueKeyword(getProject(), name.startsWith("#") ? name : "#" + name);
            identifier.replace(newValueKw);
        }
        return this;
    }
}
