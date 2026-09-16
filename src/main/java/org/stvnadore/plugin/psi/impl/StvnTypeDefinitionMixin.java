package org.stvnadore.plugin.psi.impl;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeKeyword;

@NullMarked
public abstract class StvnTypeDefinitionMixin extends ASTWrapperPsiElement implements TypeDefinition {

    protected StvnTypeDefinitionMixin(ASTNode node) {
        super(node);
    }

    @Override
    public @Nullable TypeKeyword getTypeKeyword() {
        var target = getTypeDefTarget();
        if (target != null) {
            return target.getTypeKeyword();
        }
        return findChildByClass(TypeKeyword.class);
    }

    @Override
    public @Nullable PsiElement getNameIdentifier() {
        var target = getTypeDefTarget();
        if (target != null) {
            if (target.getTypeKeyword() != null) {
                return target.getTypeKeyword();
            }
            return target;
        }
        return findChildByClass(TypeKeyword.class);
    }

    @Override
    public String getName() {
        var identifier = getNameIdentifier();
        if (identifier instanceof com.intellij.psi.PsiNamedElement named) {
            var name = named.getName();
            return name != null ? name : "";
        }
        return identifier != null ? identifier.getText() : "";
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
     * Returns the text offset within the file where the declared type identifier begins.
     *
     * @return the start offset of the type name identifier token, or the container offset if null
     */
    @Override
    public int getTextOffset() {
        var identifier = getNameIdentifier();
        return identifier != null ? identifier.getTextOffset() : super.getTextOffset();
    }
}

