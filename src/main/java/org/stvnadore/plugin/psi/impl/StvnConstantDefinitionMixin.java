package org.stvnadore.plugin.psi.impl;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.navigation.ItemPresentation;
import com.intellij.psi.PsiElement;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.icons.StvnIcons;
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
        var name = identifier instanceof com.intellij.psi.PsiNamedElement named ? named.getName() : null;
        if (name != null) {
            return name;
        }
        if (identifier != null) {
            var text = identifier.getText();
            return text.startsWith("#") ? text.substring(1) : text;
        }
        return "";
    }

    @Override
    public PsiElement setName(@NotNull String name) throws IncorrectOperationException {
        if (name.startsWith(":") || name.startsWith("#")) {
            throw new IncorrectOperationException("Identifier must be a bare name without ':' or '#' prefix: " + name);
        }
        var identifier = getNameIdentifier();
        if (identifier != null) {
            var newValueKw = StvnElementFactory.createValueKeyword(getProject(), "#" + name);
            identifier.replace(newValueKw);
        }
        return this;
    }

    @Override
    public @Nullable ItemPresentation getPresentation() {
        return new ItemPresentation() {
            @Override
            public @Nullable String getPresentableText() {
                var name = getName();
                return name != null && !name.isEmpty() ? "#" + name : null;
            }

            @Override
            public @Nullable String getLocationString() {
                var file = getContainingFile();
                return file != null ? file.getName() : null;
            }

            @Override
            public @Nullable javax.swing.Icon getIcon(boolean unused) {
                return StvnIcons.FILE;
            }
        };
    }

    /**
     * Returns the text offset within the file where the declared constant identifier begins.
     *
     * @return the start offset of the constant name identifier token, or the container offset if null
     */
    @Override
    public int getTextOffset() {
        var identifier = getNameIdentifier();
        return identifier != null ? identifier.getTextOffset() : super.getTextOffset();
    }
}

